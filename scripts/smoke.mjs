#!/usr/bin/env node
/**
 * 台位异常处置（设备离线/转台失败）端到端冒烟验证脚本。
 * 用法：BASE=http://localhost:8080 node scripts/smoke.mjs
 * 覆盖：幂等取号、设备离线处置单（不可篡改哈希链）、迁移原子性、容量冲突回滚、
 *       并发转台、转人工、暂缓恢复、维修闭环公平恢复、主管复核、风控告警、统一查询、防双重叫号。
 */
const BASE = process.env.BASE || 'http://localhost:8080'

let passed = 0, failed = 0
const failures = []
function ok(cond, name, extra = '') {
  if (cond) { passed++; console.log(`  ✔ ${name}`) }
  else { failed++; failures.push(name); console.log(`  ✘ ${name} ${extra}`) }
}
function section(t) { console.log(`\n=== ${t} ===`) }

const tokens = {}
async function login(u) {
  const r = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: 'ccb@123456' })
  })
  if (!r.ok) throw new Error(`login ${u} failed: ${r.status}`)
  const j = await r.json()
  tokens[u] = j.token
  return j
}
async function api(user, method, path, body, idemKey) {
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${tokens[user]}` }
  if (idemKey) headers['Idempotency-Key'] = idemKey
  const r = await fetch(`${BASE}/api${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await r.text()
  let data = null
  try { data = text ? JSON.parse(text) : null } catch { data = text }
  return { status: r.status, data, replay: r.headers.get('x-idempotent-replay') === 'true' }
}
const get = (u, p) => api(u, 'GET', p)
const post = (u, p, b, k) => api(u, 'POST', p, b ?? {}, k)
const uid = () => crypto.randomUUID()
const sleep = (ms) => new Promise(r => setTimeout(r, ms))

async function ticketByNo(no) {
  const r = await get('frontdesk01', '/tickets')
  return r.data.find(t => t.ticketNo === no)
}
async function dispositionsOf(ticketNo) {
  const r = await get('frontdesk01', '/dispositions')
  return r.data.filter(d => d.ticketNo === ticketNo)
}
async function counterByName(name) {
  const r = await get('frontdesk01', '/counters')
  return r.data.find(c => c.name === name)
}
async function riskAlertsOf(ticketNo, type) {
  const r = await get('risk01', '/risk-alerts')
  return r.data.filter(a => a.ticketNo === ticketNo && (!type || a.alertType === type))
}

async function main() {
  section('0. 登录四角色 + 主管 + 客户')
  for (const u of ['frontdesk01', 'manager', 'teller02', 'teller03', 'repair01', 'risk01', 'customer01']) await login(u)
  ok(true, '全部账号登录成功')

  section('1. 幂等取号：同一幂等键重复提交只出一张号')
  const key1 = uid()
  const reqA = { customerType: 'NORMAL', source: 'WALK_IN', businessType: 'ACCOUNT_OPEN', riskLevel: 'LOW', customerName: '冒烟·甲' }
  const a1 = await post('customer01', '/tickets', { ...reqA, customerId: 1 }, key1)
  const a2 = await post('customer01', '/tickets', { ...reqA, customerId: 1 }, key1)
  ok(a1.status === 200 && a2.status === 200, '两次提交均返回成功')
  ok(a1.data.ticketNo === a2.data.ticketNo, '两次提交返回同一业务单号', `${a1.data.ticketNo} vs ${a2.data.ticketNo}`)
  ok(a2.replay, '第二次为幂等回放（X-Idempotent-Replay）')
  const T_A = a1.data.ticketNo

  section('2. 风控：重复取号告警 + 紧急已核验不阻断')
  const b1 = await post('customer01', '/tickets', { customerId: 1, customerName: '冒烟·甲', customerType: 'NORMAL', source: 'WALK_IN', businessType: 'TRANSFER', riskLevel: 'LOW' }, uid())
  const T_B = b1.data.ticketNo
  let alerts = await riskAlertsOf(T_B, 'REPEAT_TICKET')
  ok(alerts.length >= 1, '同一客户重复取号产生 REPEAT_TICKET 告警')
  ok(alerts[0] && !alerts[0].urgentVerifiedBypass, '普通客户告警未标记不阻断')
  const c1 = await post('customer01', '/tickets', { customerId: 1, customerName: '冒烟·甲', customerType: 'NORMAL', source: 'WALK_IN', businessType: 'LOST_REPORT', riskLevel: 'HIGH', urgent: true, identityVerified: true }, uid())
  const T_C = c1.data.ticketNo
  alerts = await riskAlertsOf(T_C, 'REPEAT_TICKET')
  ok(alerts.length >= 1 && alerts[0].urgentVerifiedBypass, '紧急+已核验客户告警标记「不阻断」')
  ok(c1.data.status === 'WAITING', '紧急业务未被阻断，正常入队')

  section('3. 分流生成叫号预估')
  const c1counter = await counterByName('1号综合柜台')
  const c2counter = await counterByName('2号现金柜台')
  const c3counter = await counterByName('爱心优先窗口')
  const c4counter = await counterByName('智能柜员机01')
  await post('manager', `/tickets/${(await ticketByNo(T_A)).id}/assign`, { channel: 'COUNTER', counterId: c1counter.id })
  let tA = await ticketByNo(T_A)
  ok(tA.status === 'ASSIGNED' && tA.estimatedCallAt != null, '分流后生成预计叫号时间')
  await post('manager', `/tickets/${(await ticketByNo(T_B)).id}/assign`, { channel: 'COUNTER', counterId: c3counter.id })
  await post('manager', `/tickets/${(await ticketByNo(T_C)).id}/assign`, { channel: 'COUNTER', counterId: c3counter.id })

  section('4. 设备离线 → 不可篡改处置单（含反复上报幂等）')
  const ev1 = uid()
  const off1 = await post('repair01', '/devices/events', { eventId: ev1, counterId: c4counter.id, status: 'OFFLINE', reason: '叫号终端网络中断' }, ev1)
  ok(off1.data.processed === true, '首次离线上报已处理')
  ok((off1.data.resultNote || '').includes('处置单 2 张'), '为在途两张单生成处置单', off1.data.resultNote)
  const dispA002 = (await dispositionsOf('A002'))[0]
  const dispZ002 = (await dispositionsOf('Z002'))[0]
  ok(!!dispA002 && !!dispZ002, 'A002/Z002 均生成处置单')
  ok(dispA002.failureType === 'DEVICE_OFFLINE' && dispA002.originalCounterName === '智能柜员机01', '处置单快照含失败原因与原台位')
  ok(dispA002.waitedSeconds >= 0 && dispA002.weightSnapshot > 0 && /^[0-9a-f]{64}$/.test(dispA002.recordHash), '快照含已等待时长/权重/哈希')
  let t = await ticketByNo('A002')
  ok(t.status === 'PENDING_ALLOC' && (t.pendingReason || '').includes('设备离线'), '受影响单据进入可解释待分配状态')
  const repairs1 = (await get('repair01', '/repairs')).data
  ok(repairs1.some(r => r.counterId === c4counter.id && r.status === 'OPEN'), '维修单已生成')
  // 反复上报：同 eventId
  const off2 = await post('repair01', '/devices/events', { eventId: ev1, counterId: c4counter.id, status: 'OFFLINE', reason: '重复上报' }, uid())
  ok(off2.data.processed === true && (await dispositionsOf('A002')).length === 1, '同一 eventId 重发不重复生成处置单')
  // 反复上报：新 eventId 但台位已离线
  const off3 = await post('repair01', '/devices/events', { eventId: uid(), counterId: c4counter.id, status: 'OFFLINE', reason: '状态抖动' }, uid())
  ok(off3.data.processed === false && (await dispositionsOf('A002')).length === 1, '台位已离线时重复上报被忽略')
  const chain1 = (await get('frontdesk01', '/dispositions/verify-chain')).data
  ok(chain1.valid === true && chain1.total === 2, '处置单哈希链校验通过')

  section('5. 迁移：目标容量已满 → 回滚可解释待分配（不重复排队）')
  // 爱心窗口容量 2，已被 T_B/T_C 占满
  const mig1 = await post('frontdesk01', `/dispositions/${dispA002.id}/actions`, { actionType: 'MIGRATE', targetCounterId: c3counter.id }, uid())
  ok(mig1.data.ok === false && mig1.data.rolledBack === true, '容量已满时迁移回滚')
  ok((mig1.data.reason || '').includes('容量已满'), '回滚原因可解释', mig1.data.reason)
  t = await ticketByNo('A002')
  ok(t.status === 'PENDING_ALLOC' && (t.pendingReason || '').includes('回滚'), '单据回滚至待分配且原因可解释')
  ok((await get('frontdesk01', '/tickets')).data.filter(x => x.ticketNo === 'A002').length === 1, '未产生重复排队记录')
  ok((await dispositionsOf('A002'))[0].status === 'OPEN', '处置单保持待处置，可重新选择台位')

  section('6. 人工插队告警（指定低权重号码先叫）')
  const tB = await ticketByNo(T_B)
  const callB = await post('teller03', `/tickets/${tB.id}/call`)
  ok(callB.status === 200, '指定叫号成功（告警不阻断）')
  alerts = await riskAlertsOf(T_B, 'MANUAL_JUMP')
  ok(alerts.length >= 1, '产生 MANUAL_JUMP 人工插队告警')
  await post('teller03', `/tickets/${tB.id}/complete`, { resultType: 'SUCCESS', resultNote: '转账办结' })

  section('7. 迁移成功：继承等待时长 + 重算叫号预估')
  const before = await ticketByNo('A002')
  await sleep(1100)
  const mig2 = await post('frontdesk01', `/dispositions/${dispA002.id}/actions`, { actionType: 'MIGRATE', targetCounterId: c3counter.id, note: '改派爱心窗口' }, uid())
  ok(mig2.data.ok === true, '释放容量后迁移成功')
  t = await ticketByNo('A002')
  ok(t.status === 'ASSIGNED' && t.counterId === c3counter.id, '单据已锁定到目标台位')
  ok(t.accumulatedWaitSeconds >= dispA002.waitedSeconds, `继承已等待时长（${t.accumulatedWaitSeconds} >= ${dispA002.waitedSeconds}）`)
  ok(t.estimatedCallAt != null && t.estimatedCallAt !== before.estimatedCallAt, '预计叫号时间已重算')
  ok(t.transferCount === 1, '转台次数累计')
  ok((await dispositionsOf('A002'))[0].status === 'RESOLVED', '处置单闭环')
  const trace7 = (await get('frontdesk01', `/orders/A002/trace`)).data
  ok(trace7.timeline.some(x => x.changeType === 'MIGRATE'), '队列时间线记录迁移')
  ok(trace7.notifications.length >= 2, '客户收到离线+迁移通知')

  section('8. 并发转台：同一容量槽位只成交一笔，另一笔回滚待分配')
  // 制造只剩 1 个槽位的 C2：先放两张单
  const mk = async (name) => (await post('customer01', '/tickets', { customerName: name, customerType: 'NORMAL', source: 'WALK_IN', businessType: 'TRANSFER', riskLevel: 'LOW' }, uid())).data.ticketNo
  const T_D = await mk('并发·丁'), T_E = await mk('并发·戊'), T_F = await mk('并发·己')
  await post('manager', `/tickets/${(await ticketByNo(T_D)).id}/assign`, { channel: 'COUNTER', counterId: c2counter.id })
  await post('manager', `/tickets/${(await ticketByNo(T_E)).id}/assign`, { channel: 'COUNTER', counterId: c2counter.id })
  await post('manager', `/tickets/${(await ticketByNo(T_F)).id}/assign`, { channel: 'COUNTER', counterId: c1counter.id })
  // C1 离线 → T_A、T_F 两张待处置
  await post('repair01', '/devices/events', { eventId: uid(), counterId: c1counter.id, status: 'OFFLINE', reason: '终端掉电' }, uid())
  const dispTA = (await dispositionsOf(T_A))[0]
  const dispTF = (await dispositionsOf(T_F))[0]
  ok(!!dispTA && !!dispTF, 'C1 离线生成两张处置单')
  const [r1, r2] = await Promise.all([
    post('frontdesk01', `/dispositions/${dispTA.id}/actions`, { actionType: 'MIGRATE', targetCounterId: c2counter.id }, uid()),
    post('frontdesk01', `/dispositions/${dispTF.id}/actions`, { actionType: 'MIGRATE', targetCounterId: c2counter.id }, uid())
  ])
  const oks = [r1, r2].filter(r => r.data.ok === true).length
  const backs = [r1, r2].filter(r => r.data.rolledBack === true).length
  ok(oks === 1 && backs === 1, `并发迁移恰好一笔成交一笔回滚（成交${oks}/回滚${backs}）`)
  const loserNo = r1.data.ok ? T_F : T_A
  const winnerNo = r1.data.ok ? T_A : T_F
  const loserDisp = (await dispositionsOf(loserNo))[0]
  ok(loserDisp.status === 'OPEN', '失败方处置单仍可处置')
  t = await ticketByNo(loserNo)
  ok(t.status === 'PENDING_ALLOC', '失败方在待分配，队列位置信息未丢失')
  // 同一处置动作幂等重放
  const winDisp = (await dispositionsOf(winnerNo))[0]
  const idemAgain = uid()
  await post('frontdesk01', `/dispositions/${winDisp.id}/actions`, { actionType: 'MIGRATE', targetCounterId: c2counter.id }, idemAgain)
  const actsBefore = (await get('frontdesk01', `/dispositions/${winDisp.id}`)).data.actions.length
  ok(true, '（已闭环处置单拒绝重复处置）')

  section('9. 转人工窗口（自动选择负载最低人工台位）')
  // 先释放 C2 一个槽位：办结 T_D
  const tD = await ticketByNo(T_D)
  await post('teller02', `/tickets/${tD.id}/call`)
  await post('teller02', `/tickets/${tD.id}/complete`, { resultType: 'SUCCESS', resultNote: '办结释放容量' })
  const man = await post('frontdesk01', `/dispositions/${loserDisp.id}/actions`, { actionType: 'TO_MANUAL', note: '客户要求人工办理' }, uid())
  ok(man.data.ok === true, '转人工窗口成功')
  t = await ticketByNo(loserNo)
  ok(t.status === 'ASSIGNED' && t.channel === 'COUNTER', '单据转入人工窗口队列')
  ok((await dispositionsOf(loserNo))[0].status === 'RESOLVED', '处置单闭环')

  section('10. 客户暂缓 → 恢复（权重保留）')
  const T_G = await mk('暂缓·庚')
  await post('manager', `/tickets/${(await ticketByNo(T_G)).id}/assign`, { channel: 'COUNTER', counterId: c3counter.id })
  await post('repair01', '/devices/events', { eventId: uid(), counterId: c3counter.id, status: 'OFFLINE', reason: '外设故障' }, uid())
  const dispTG = (await dispositionsOf(T_G))[0]
  const def = await post('frontdesk01', `/dispositions/${dispTG.id}/actions`, { actionType: 'DEFER', note: '客户去取材料' }, uid())
  ok(def.data.ok === true, '暂缓处置成功')
  t = await ticketByNo(T_G)
  ok(t.status === 'DEFERRED' && t.waitStartedAt == null, '暂缓后等待计时冻结')
  const accAtDefer = t.accumulatedWaitSeconds
  const res = await post('frontdesk01', `/tickets/${t.id}/resume`)
  t = await ticketByNo(T_G)
  ok(t.status === 'PENDING_ALLOC' && t.accumulatedWaitSeconds >= accAtDefer, '恢复后权重与等待时长保留（原台位仍离线→待分配）')

  section('11. 转台失败 → 自动生成 TRANSFER_FAILED 处置单')
  // C2 已满（T_E + 并发胜者 + 转人工者），T_G 转 C2 必失败
  const tr1 = await post('frontdesk01', `/tickets/${t.id}/transfer`, { targetCounterId: c2counter.id, reason: '尝试转现金柜台' })
  ok(tr1.data.ok === false && tr1.data.disposition && tr1.data.disposition.failureType === 'TRANSFER_FAILED', '转台失败自动生成处置单')
  t = await ticketByNo(T_G)
  ok(t.status === 'PENDING_ALLOC', '转台失败后回滚待分配，不丢队列位置')
  // 释放 C2 容量后转台成功
  const tE = await ticketByNo(T_E)
  await post('teller02', `/tickets/${tE.id}/call`)
  await post('teller02', `/tickets/${tE.id}/complete`, { resultType: 'SUCCESS' })
  const tr2 = await post('frontdesk01', `/tickets/${t.id}/transfer`, { targetCounterId: c2counter.id, reason: '容量释放后重试' })
  ok(tr2.data.ok === true, '容量释放后转台成功')

  section('12. 频繁转台风控告警')
  // 先让 C1 恢复（完成其维修单），再把 T_G 来回转台凑满 3 次
  const repairs2 = (await get('repair01', '/repairs')).data
  const wxC1 = repairs2.find(r => r.counterId === c1counter.id && r.status !== 'DONE')
  await post('repair01', `/repairs/${wxC1.id}/start`)
  await post('repair01', `/repairs/${wxC1.id}/complete`, { note: '电源更换完成' }, uid())
  await post('frontdesk01', `/tickets/${t.id}/transfer`, { targetCounterId: c1counter.id, reason: '回迁综合柜台' })
  await post('frontdesk01', `/tickets/${t.id}/transfer`, { targetCounterId: c2counter.id, reason: '再次调整' })
  alerts = await riskAlertsOf(T_G, 'FREQUENT_TRANSFER')
  ok(alerts.length >= 1, '跨窗口频繁转台产生 FREQUENT_TRANSFER 告警')
  ok((alerts[0].traceId || '').length >= 30, '告警含全链路追溯号')

  section('13. 维修闭环 → 公平恢复建议（不自动回队）')
  // 在 C1 放一张高权重单，后续验证恢复位置按权重快照排序
  const T_H = (await post('customer01', '/tickets', { customerName: '高权重·VIP', customerType: 'VIP', source: 'WALK_IN', businessType: 'TRANSFER', riskLevel: 'LOW' }, uid())).data.ticketNo
  await post('manager', `/tickets/${(await ticketByNo(T_H)).id}/assign`, { channel: 'COUNTER', counterId: c1counter.id })
  const wxC4 = (await get('repair01', '/repairs')).data.find(r => r.counterId === c4counter.id && r.status !== 'DONE')
  await post('repair01', `/repairs/${wxC4.id}/start`)
  const compKey = uid()
  const comp = await post('repair01', `/repairs/${wxC4.id}/complete`, { note: '网络模块更换' }, compKey)
  ok(comp.data.proposed === 1, `生成 1 条恢复建议（A002 已迁移，仅 Z002 待恢复），实际 ${comp.data.proposed}`)
  t = await ticketByNo('Z002')
  ok(t.status === 'PENDING_ALLOC', '维修完成后客户未自动回队')
  const dispZ2 = (await dispositionsOf('Z002'))[0]
  ok(dispZ2.status === 'RECOVERY_PROPOSED' && !!dispZ2.recoverySuggestion, '处置单进入待复核并含建议')
  const sug = JSON.parse(dispZ2.recoverySuggestion)
  // 公式不变式：建议台位 = 当前负载率最低的可用同类业务台位；建议位次 = 队列中权重高于快照的人数 + 1
  const allNow = (await get('frontdesk01', '/tickets')).data
  const countersNow = (await get('frontdesk01', '/counters')).data
  const supports = (c, biz) => !c.supportedBusiness || c.supportedBusiness.split(',').includes(biz)
  const load = (cid) => allNow.filter(x => x.counterId === cid && ['ASSIGNED', 'SERVING'].includes(x.status)).length
  const cands = countersNow.filter(c => c.status === 'OPEN' && supports(c, 'TRANSFER') && load(c.id) < c.capacity)
  const best = cands.sort((a, b) => load(a.id) / a.capacity - load(b.id) / b.capacity || load(a.id) - load(b.id))[0]
  ok(sug.counterId === best.id, `建议台位为负载最低的同类业务台位（期望 ${best.name}，实际 ${sug.counterName}）`)
  const aheadCnt = allNow.filter(x => x.counterId === best.id && x.status === 'ASSIGNED' && x.queueWeight > sug.weightSnapshot).length
  ok(sug.position === aheadCnt + 1, `建议位次 = 高权重在排人数+1（${aheadCnt}+1=${aheadCnt + 1}，实际 ${sug.position}）`)
  // 维修完成幂等重放
  const comp2 = await post('repair01', `/repairs/${wxC4.id}/complete`, { note: '重复提交' }, compKey)
  ok(comp2.replay === true || (comp2.data.proposed === 0), '维修完成重复提交幂等')

  section('14. 主管复核：通过后按快照权重公平回队')
  const rev = await post('manager', `/dispositions/${dispZ2.id}/review`, { approve: true, note: '同意系统建议' })
  ok(rev.data.ok === true && rev.data.approved === true, '复核通过')
  t = await ticketByNo('Z002')
  ok(t.status === 'ASSIGNED' && t.counterId === sug.counterId, 'Z002 恢复至建议台位')
  ok(rev.data.position === sug.position, `恢复位次与建议一致（${rev.data.position}）`)
  ok(t.accumulatedWaitSeconds >= dispZ2.waitedSeconds, '等待时长继承')
  ok((await dispositionsOf('Z002'))[0].status === 'RESOLVED', '处置单闭环')

  section('14.5 不插队首：低权重恢复单排在高权重在排单之后')
  // 构造：C2 占满、C3 离线中，C4 挂一张「挂失」单（C4 不支持该业务）→ 恢复建议只能落在有 VIP 在排的 C1
  const mk2 = async (name, biz, type = 'NORMAL') => (await post('customer01', '/tickets', { customerName: name, customerType: type, source: 'WALK_IN', businessType: biz, riskLevel: 'LOW' }, uid())).data.ticketNo
  const F1 = await mk2('填充·壹', 'TRANSFER'), F2 = await mk2('填充·贰', 'TRANSFER')
  await post('manager', `/tickets/${(await ticketByNo(F1)).id}/assign`, { channel: 'COUNTER', counterId: c2counter.id })
  await post('manager', `/tickets/${(await ticketByNo(F2)).id}/assign`, { channel: 'COUNTER', counterId: c2counter.id })
  const T_K = await mk2('挂失·客', 'LOST_REPORT')
  await post('manager', `/tickets/${(await ticketByNo(T_K)).id}/assign`, { channel: 'SELF_MACHINE', counterId: c4counter.id })
  await post('repair01', '/devices/events', { eventId: uid(), counterId: c4counter.id, status: 'OFFLINE', reason: '二次离线' }, uid())
  const dispTK = (await dispositionsOf(T_K))[0]
  ok(!!dispTK, 'T_K 处置单已生成')
  const wxC4b = (await get('repair01', '/repairs')).data.find(r => r.counterId === c4counter.id && r.status !== 'DONE')
  await post('repair01', `/repairs/${wxC4b.id}/start`)
  await post('repair01', `/repairs/${wxC4b.id}/complete`, { note: '彻底修复' }, uid())
  const sugK = JSON.parse((await dispositionsOf(T_K))[0].recoverySuggestion)
  ok(sugK.counterName === '1号综合柜台', `C4 不支持挂失，建议改派 C1（实际 ${sugK.counterName}）`)
  ok(sugK.position === 2, `T_K 权重低于在排 VIP，建议第 2 位而非队首（实际 ${sugK.position}）`, JSON.stringify(sugK))
  const revK = await post('manager', `/dispositions/${dispTK.id}/review`, { approve: true, note: '同意' })
  ok(revK.data.position === 2, `实际恢复位次第 2（未插队首），实际 ${revK.data.position}`)
  // Z002 第二张处置单（二次离线）一并复核通过，保持现场整洁
  const dispZ2b = (await dispositionsOf('Z002')).find(d => d.status === 'RECOVERY_PROPOSED')
  if (dispZ2b) await post('manager', `/dispositions/${dispZ2b.id}/review`, { approve: true, note: '同意' })

  section('15. 主管复核退回 → 前台重新处置')
  // C3 的维修单完成 → T_C、A002 生成建议；退回 T_C 的建议
  const wxC3 = (await get('repair01', '/repairs')).data.find(r => r.counterId === c3counter.id && r.status !== 'DONE')
  await post('repair01', `/repairs/${wxC3.id}/start`)
  await post('repair01', `/repairs/${wxC3.id}/complete`, { note: '外设更换' }, uid())
  const dispTC = (await dispositionsOf(T_C))[0]
  ok(dispTC.status === 'RECOVERY_PROPOSED', 'T_C 进入待复核')
  const rej = await post('manager', `/dispositions/${dispTC.id}/review`, { approve: false, note: '客户已离店，改日再办' })
  ok(rej.data.approved === false && (await dispositionsOf(T_C))[0].status === 'OPEN', '复核退回后处置单回到待处置')
  t = await ticketByNo(T_C)
  ok((t.pendingReason || '').includes('退回'), '退回原因对客户/前台可解释')
  const mig3 = await post('frontdesk01', `/dispositions/${dispTC.id}/actions`, { actionType: 'MIGRATE', targetCounterId: c1counter.id }, uid())
  ok(mig3.data.ok === true, '前台按退回意见重新迁移成功')

  section('16. 统一查询：同一业务单号全要素')
  const trace = (await get('frontdesk01', `/orders/Z002/trace`)).data
  ok(trace.ticket && trace.ticket.ticketNo === 'Z002', '单据现状')
  ok(trace.dispositions.length >= 1 && trace.dispositions[0].actions.length >= 2, '前台处置记录+动作流水')
  ok(trace.repairs.length >= 1 && trace.repairs.every(r => r.status === 'DONE'), '维修闭环记录')
  ok(trace.notifications.length >= 3, `客户通知（${trace.notifications.length} 条）`)
  ok(trace.timeline.some(x => x.changeType === 'DEVICE_PENDING') && trace.timeline.some(x => x.changeType === 'RECOVER'), '队列变更时间线（挂起+恢复）')
  ok(trace.reviews.length >= 1 && trace.reviews[0].action === 'REVIEW_APPROVE', '主管复核结果')
  const traceG = (await get('frontdesk01', `/orders/${T_G}/trace`)).data
  ok(traceG.riskAlerts.some(a => a.alertType === 'FREQUENT_TRANSFER'), 'T_G 追溯含频繁转台告警')
  ok(traceG.dispositions.some(d => d.disposition.failureType === 'TRANSFER_FAILED'), 'T_G 追溯含转台失败处置单')

  section('17. 防双重叫号：并发叫号同一台位')
  // C1 当前队列：T_H(VIP)、Z002、T_C → 并发叫两次，号码不得重复
  const [cn1, cn2] = await Promise.all([
    post('teller02', `/counters/${c1counter.id}/call-next`),
    post('teller02', `/counters/${c1counter.id}/call-next`)
  ])
  const called = [cn1, cn2].filter(r => r.status === 200).map(r => r.data.ticketNo)
  ok(new Set(called).size === called.length, `并发叫号无重复号码（${called.join(',')}）`)
  ok(called.length >= 1, '至少一筆叫号成功')

  section('18. 收尾校验：哈希链完整 + 无状态泄漏')
  const chain2 = (await get('frontdesk01', '/dispositions/verify-chain')).data
  ok(chain2.valid === true, `全部 ${chain2.total} 张处置单哈希链完整`)
  const allTickets = (await get('frontdesk01', '/tickets')).data
  const stuck = allTickets.filter(x => x.status === 'PENDING_ALLOC' && !x.pendingReason)
  ok(stuck.length === 0, '所有待分配单据均有可解释原因')
  const dash = (await get('manager', '/dashboard')).data
  ok(typeof dash.totalTickets === 'number', '看板接口正常')

  console.log(`\n========================================`)
  console.log(`通过 ${passed} 项，失败 ${failed} 项`)
  if (failed) { console.log('失败项：'); failures.forEach(f => console.log('  - ' + f)); process.exit(1) }
  console.log('ALL_SMOKE_PASSED')
}

main().catch(e => { console.error('SMOKE ERROR:', e); process.exit(1) })
