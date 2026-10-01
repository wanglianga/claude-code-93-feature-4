<template>
  <div class="page">
    <BranchBanners />

    <div class="kpi-row">
      <div class="kpi red"><b>{{ actionable.length }}</b><span>待处置处置单</span></div>
      <div class="kpi orange"><b>{{ pendingTickets.length }}</b><span>待分配客户</span></div>
      <div class="kpi purple"><b>{{ deferredTickets.length }}</b><span>暂缓客户</span></div>
      <div class="kpi green"><b>{{ chainOk === null ? '—' : (chainOk ? '完整' : '异常') }}</b><span>处置单哈希链</span></div>
    </div>

    <!-- 处置单工作台 -->
    <div class="card">
      <h3>处置单工作台
        <span class="hint">设备离线/转台失败自动生成不可篡改处置单（哈希链存证），同一业务单号四方协同</span>
      </h3>
      <div v-if="!actionable.length" class="empty">暂无待处置的处置单</div>
      <div v-for="d in actionable" :key="d.id" class="disp-card">
        <div class="disp-head">
          <span class="ticket-no">{{ d.orderNo }}</span>
          <span class="tag" :class="tag(L.FailureType, d.failureType).cls">{{ tag(L.FailureType, d.failureType).text }}</span>
          <span class="tag" :class="tag(L.DispositionStatus, d.status).cls">{{ tag(L.DispositionStatus, d.status).text }}</span>
          <span class="small muted">业务单号 {{ d.ticketNo }} · 哈希 {{ d.recordHash.substring(0, 12) }}…</span>
        </div>
        <div class="desc-list">
          <dt>客户</dt><dd>{{ d.customerName }}</dd>
          <dt>业务类型</dt><dd>{{ L.BusinessType[d.businessType] }}</dd>
          <dt>优先级</dt><dd>P{{ d.priority }}（权重快照 {{ d.weightSnapshot }}）</dd>
          <dt>原台位</dt><dd>{{ d.originalCounterName || '—' }}（故障时排第 {{ d.queuePosition || '—' }} 位）</dd>
          <dt>已等待</dt><dd>{{ waitText(d.waitedSeconds) }}</dd>
          <dt>原预计叫号</dt><dd>{{ fmtTime(d.estimatedCallAtSnapshot) }}</dd>
          <dt>失败原因</dt><dd>{{ d.failureReason }}</dd>
        </div>

        <!-- 三方案处置 -->
        <div class="act-row">
          <label class="act-opt" :class="{ on: form[d.id]?.action === 'MIGRATE' }">
            <input type="radio" value="MIGRATE" v-model="form[d.id].action" />
            <b>保留权重迁移</b><span>原子释放原台位、锁定目标容量、继承等待时长并重算叫号预估</span>
          </label>
          <label class="act-opt" :class="{ on: form[d.id]?.action === 'TO_MANUAL' }">
            <input type="radio" value="TO_MANUAL" v-model="form[d.id].action" />
            <b>转人工窗口</b><span>改由人工台位接待，排队权重保留</span>
          </label>
          <label class="act-opt" :class="{ on: form[d.id]?.action === 'DEFER' }">
            <input type="radio" value="DEFER" v-model="form[d.id].action" />
            <b>客户暂缓</b><span>冻结等待计时，权重保留，随时恢复</span>
          </label>
        </div>
        <div class="form-grid" v-if="form[d.id]?.action !== 'DEFER'">
          <label class="field"><b>目标台位（显示实时负载/容量/业务能力）</b>
            <select v-model="form[d.id].targetCounterId">
              <option :value="null">{{ form[d.id]?.action === 'TO_MANUAL' ? '自动选择负载最低的人工窗口' : '请选择' }}</option>
              <option v-for="c in candidateCounters(d)" :key="c.id" :value="c.id"
                      :disabled="c.status !== 'OPEN' || loadOf(c.id) >= c.capacity">
                {{ c.name }}｜{{ tag(L.CounterStatus, c.status).text }}｜负载 {{ loadOf(c.id) }}/{{ c.capacity }}｜{{ L.CounterType[c.type] }}
              </option>
            </select>
          </label>
          <label class="field"><b>备注</b><input v-model="form[d.id].note" placeholder="处置说明（可选）" /></label>
        </div>
        <div class="btn-row">
          <button class="btn" :disabled="submitting[d.id]" @click="applyAction(d)">
            {{ submitting[d.id] ? '提交中…' : '执行处置（幂等）' }}
          </button>
          <span class="small muted">重复点击/网络重试不会产生重复动作</span>
        </div>
        <div v-if="result[d.id]" class="banner" :class="result[d.id].ok ? 'blue' : 'orange'" style="margin-top:8px">
          {{ result[d.id].message }}
        </div>
      </div>
    </div>

    <div class="grid-2">
      <!-- 待分配客户（可解释状态） -->
      <div class="card">
        <h3>待分配客户 <span class="hint">迁移冲突回滚/暂缓恢复后待重新安排，原因可解释、位置不丢失</span></h3>
        <table>
          <thead><tr><th>号码</th><th>客户</th><th>业务</th><th>待分配原因</th><th>手动转台</th></tr></thead>
          <tbody>
            <tr v-for="t in pendingTickets" :key="t.id">
              <td><span class="ticket-no">{{ t.ticketNo }}</span></td>
              <td>{{ t.customerName }}</td>
              <td>{{ L.BusinessType[t.businessType] }}</td>
              <td class="small">{{ t.pendingReason || '—' }}</td>
              <td>
                <div style="display:flex;gap:6px">
                  <select v-model="transferTarget[t.id]" style="flex:1">
                    <option :value="null">选择台位</option>
                    <option v-for="c in store.counters" :key="c.id" :value="c.id">
                      {{ c.name }}（{{ tag(L.CounterStatus, c.status).text }} {{ loadOf(c.id) }}/{{ c.capacity }}）
                    </option>
                  </select>
                  <button class="btn sm" :disabled="!transferTarget[t.id]" @click="manualTransfer(t)">转台</button>
                </div>
              </td>
            </tr>
            <tr v-if="!pendingTickets.length"><td colspan="5"><div class="empty">暂无待分配客户</div></td></tr>
          </tbody>
        </table>
        <div v-if="transferMsg" class="banner orange" style="margin-top:8px">{{ transferMsg }}</div>
      </div>

      <!-- 暂缓客户 -->
      <div class="card">
        <h3>暂缓客户 <span class="hint">权重与等待时长已冻结保留，恢复时不需重新取号</span></h3>
        <table>
          <thead><tr><th>号码</th><th>客户</th><th>业务</th><th>已等待(冻结)</th><th></th></tr></thead>
          <tbody>
            <tr v-for="t in deferredTickets" :key="t.id">
              <td><span class="ticket-no">{{ t.ticketNo }}</span></td>
              <td>{{ t.customerName }}</td>
              <td>{{ L.BusinessType[t.businessType] }}</td>
              <td class="small">{{ waitText(t.accumulatedWaitSeconds) }}</td>
              <td><button class="btn sm green" @click="resume(t)">恢复排队</button></td>
            </tr>
            <tr v-if="!deferredTickets.length"><td colspan="5"><div class="empty">暂无暂缓客户</div></td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 统一查询 -->
    <div class="card">
      <h3>统一查询 <span class="hint">输入业务单号，一站查看处置记录/维修闭环/客户通知/队列变更时间线/主管复核/风控告警</span></h3>
      <div style="display:flex;gap:8px;margin-bottom:12px">
        <input v-model="traceNo" placeholder="业务单号，如 A002" style="max-width:220px"
               @keyup.enter="runTrace" />
        <button class="btn" @click="runTrace" :disabled="tracing">{{ tracing ? '查询中…' : '查询' }}</button>
        <button class="btn gray sm" v-for="s in sampleNos" :key="s" @click="traceNo = s; runTrace()">{{ s }}</button>
      </div>
      <div v-if="traceError" class="banner red">{{ traceError }}</div>
      <template v-if="trace">
        <div class="desc-list">
          <dt>单据状态</dt><dd><span class="tag" :class="tag(L.TicketStatus, trace.ticket.status).cls">{{ tag(L.TicketStatus, trace.ticket.status).text }}</span>
            <span v-if="trace.ticket.pendingReason" class="small muted">　{{ trace.ticket.pendingReason }}</span></dd>
          <dt>客户/业务</dt><dd>{{ trace.ticket.customerName }} · {{ L.BusinessType[trace.ticket.businessType] }} · 优先级 P{{ trace.ticket.priority }}</dd>
          <dt>当前权重</dt><dd>{{ trace.ticket.queueWeight }}（已等待 {{ waitText(trace.ticket.accumulatedWaitSeconds) }}）</dd>
          <dt>预计叫号</dt><dd>{{ fmtTime(trace.ticket.estimatedCallAt) }}</dd>
        </div>

        <h4 class="sec">前台处置记录（{{ trace.dispositions.length }}）</h4>
        <div v-for="d in trace.dispositions" :key="d.disposition.id" class="trace-block">
          <div>
            <span class="ticket-no">{{ d.disposition.orderNo }}</span>
            <span class="tag" :class="tag(L.FailureType, d.disposition.failureType).cls">{{ tag(L.FailureType, d.disposition.failureType).text }}</span>
            <span class="tag" :class="tag(L.DispositionStatus, d.disposition.status).cls">{{ tag(L.DispositionStatus, d.disposition.status).text }}</span>
            <span class="small muted"> {{ d.disposition.failureReason }}</span>
          </div>
          <div class="small muted" style="margin:2px 0 4px">哈希 {{ d.disposition.recordHash.substring(0, 16) }}… ← 前序 {{ d.disposition.prevHash.substring(0, 8) }}…
            <span v-if="d.disposition.resolutionNote">｜结果：{{ d.disposition.resolutionNote }}</span></div>
          <div v-for="a in d.actions" :key="a.id" class="t-who small">
            {{ fmtTime(a.createdAt) }} · {{ a.actorName }}（{{ L.Role[a.actorRole] || '系统' }}）· {{ L.DispositionActionType[a.actionType] }}：{{ a.detail }}
          </div>
        </div>
        <div v-if="!trace.dispositions.length" class="empty">无处置单</div>

        <h4 class="sec">维修闭环（{{ trace.repairs.length }}）</h4>
        <div v-for="r in trace.repairs" :key="r.id" class="trace-block small">
          <span class="ticket-no">{{ r.repairNo }}</span>
          <span class="tag" :class="tag(L.RepairStatus, r.status).cls">{{ tag(L.RepairStatus, r.status).text }}</span>
          {{ r.counterName }}｜{{ r.failureReason }}｜接单 {{ fmtTime(r.createdAt) }}｜完成 {{ fmtTime(r.completedAt) }}｜{{ r.resultNote || '—' }}
        </div>
        <div v-if="!trace.repairs.length" class="empty">无维修单</div>

        <h4 class="sec">客户通知（{{ trace.notifications.length }}）</h4>
        <div v-for="n in trace.notifications" :key="n.id" class="trace-block small">
          <span class="tag blue">{{ n.channel }}</span> {{ fmtTime(n.createdAt) }} {{ n.content }}
        </div>
        <div v-if="!trace.notifications.length" class="empty">无通知</div>

        <h4 class="sec">队列变更时间线（{{ trace.timeline.length }}）</h4>
        <div class="timeline">
          <div v-for="tr in trace.timeline" :key="tr.id" class="t-item">
            <div class="t-who">{{ L.QueueChangeType[tr.changeType] }}
              <span v-if="tr.fromStatus || tr.toStatus" class="muted">（{{ tr.fromStatus || '—' }} → {{ tr.toStatus || '—' }}）</span>
            </div>
            <div class="t-text">
              {{ tr.reason || '' }}
              <span v-if="tr.weightAfter != null" class="muted">｜权重 {{ tr.weightBefore ?? '—' }} → {{ tr.weightAfter }}</span>
              <span v-if="tr.etaAfter" class="muted">｜预计叫号 {{ fmtTime(tr.etaAfter) }}</span>
              <span class="muted">｜已等待 {{ waitText(tr.waitedSeconds) }}</span>
            </div>
            <div class="t-time">{{ fmtTime(tr.createdAt) }} · {{ tr.actorName || '系统' }}</div>
          </div>
        </div>
        <div v-if="!trace.timeline.length" class="empty">无队列变更记录</div>

        <h4 class="sec">主管复核结果（{{ trace.reviews.length }}）与风控告警（{{ trace.riskAlerts.length }}）</h4>
        <div v-for="(rv, i) in trace.reviews" :key="'rv' + i" class="trace-block small">
          <span class="tag purple">{{ L.DispositionActionType[rv.action] }}</span>
          {{ rv.orderNo }}｜{{ rv.actor }}｜{{ rv.detail }}｜{{ fmtTime(rv.time) }}
        </div>
        <div v-for="a in trace.riskAlerts" :key="a.id" class="trace-block small">
          <span class="tag" :class="tag(L.RiskAlertType, a.alertType).cls">{{ tag(L.RiskAlertType, a.alertType).text }}</span>
          {{ a.detail }}
          <span v-if="a.urgentVerifiedBypass" class="tag green">紧急已核验·不阻断</span>
          <span class="muted">｜追溯号 {{ a.traceId.substring(0, 8) }}…</span>
        </div>
        <div v-if="!trace.reviews.length && !trace.riskAlerts.length" class="empty">无复核与告警记录</div>
      </template>
    </div>

    <!-- 客户通知 -->
    <div class="card">
      <h3>客户通知 <span class="hint">处置/迁移/暂缓/恢复各节点自动推送（演示环境落库模拟）</span></h3>
      <table>
        <thead><tr><th>时间</th><th>号码</th><th>渠道</th><th>内容</th></tr></thead>
        <tbody>
          <tr v-for="n in store.notifications.slice(0, 20)" :key="n.id">
            <td class="small">{{ fmtTime(n.createdAt) }}</td>
            <td><span class="ticket-no">{{ n.ticketNo }}</span></td>
            <td><span class="tag blue">{{ n.channel }}</span></td>
            <td class="small">{{ n.content }}</td>
          </tr>
          <tr v-if="!store.notifications.length"><td colspan="4"><div class="empty">暂无通知</div></td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { computed, onUnmounted, reactive, ref, watchEffect } from 'vue'
import { api, newIdemKey } from '../api.js'
import { store } from '../store.js'
import { L, tag, fmtTime, waitText } from '../labels.js'
import { toast } from '../toast.js'
import BranchBanners from '../components/BranchBanners.vue'

defineProps({ user: Object })

const form = reactive({})        // dispositionId -> {action, targetCounterId, note, idemKey}
const submitting = reactive({})
const result = reactive({})
const transferTarget = reactive({})
const transferMsg = ref('')
const chainOk = ref(null)

const actionable = computed(() =>
  store.dispositions.filter(d => ['OPEN', 'ACTION_TAKEN'].includes(d.status)))
const pendingTickets = computed(() => store.tickets.filter(t => t.status === 'PENDING_ALLOC'))
const deferredTickets = computed(() => store.tickets.filter(t => t.status === 'DEFERRED'))
const sampleNos = computed(() => {
  const fromDisp = store.dispositions[0]?.ticketNo
  return [...new Set([fromDisp, 'A002', 'Z002'].filter(Boolean))].slice(0, 3)
})

function ensureForm(d) {
  if (!form[d.id]) form[d.id] = { action: 'MIGRATE', targetCounterId: null, note: '', idemKey: newIdemKey() }
  return form[d.id]
}

// 处置单列表变化时惰性初始化对应表单（模板渲染前保证 form[d.id] 存在）
watchEffect(() => actionable.value.forEach(ensureForm))

function candidateCounters(d) {
  return store.counters.filter(c => {
    if (form[d.id]?.action === 'TO_MANUAL') return c.type !== 'SELF_SERVICE'
    return true
  })
}

function loadOf(counterId) {
  return store.tickets.filter(t => t.counterId === counterId && ['ASSIGNED', 'SERVING'].includes(t.status)).length
}

async function applyAction(d) {
  const f = ensureForm(d)
  if (submitting[d.id]) return
  submitting[d.id] = true
  try {
    const r = await api.postIdem(`/dispositions/${d.id}/actions`,
      { actionType: f.action, targetCounterId: f.targetCounterId, note: f.note }, f.idemKey)
    result[d.id] = { ok: !!r.ok, message: r.message || (r.ok ? '处置完成' : ('已回滚：' + r.reason)) }
    // 服务端已受理（含回滚）：更换幂等键，允许再次处置；网络异常时保留原键以安全重试
    form[d.id].idemKey = newIdemKey()
    if (r.ok) {
      toast(r.message || '处置完成')
      form[d.id] = { action: 'MIGRATE', targetCounterId: null, note: '', idemKey: newIdemKey() }
    } else {
      toast(r.message || '目标不可用，已回滚待分配', 'err')
    }
    await store.refresh()
  } catch (e) {
    toast(e.message, 'err')
  } finally {
    submitting[d.id] = false
  }
}

async function manualTransfer(t) {
  const target = transferTarget[t.id]
  if (!target) return
  transferMsg.value = ''
  try {
    const r = await api.post(`/tickets/${t.id}/transfer`, { targetCounterId: target, reason: '前台手动转台' })
    if (r.ok) {
      toast('转台成功')
    } else {
      transferMsg.value = `转台失败：${r.reason}；已生成处置单 ${r.disposition?.orderNo} 并回滚至待分配（未重复排队）`
      toast('转台失败，已生成处置单', 'err')
    }
    await store.refresh()
  } catch (e) { toast(e.message, 'err') }
}

async function resume(t) {
  try {
    await api.post(`/tickets/${t.id}/resume`, {})
    toast(t.ticketNo + ' 已恢复排队')
    await store.refresh()
  } catch (e) { toast(e.message, 'err') }
}

/* 统一查询 */
const traceNo = ref('')
const trace = ref(null)
const traceError = ref('')
const tracing = ref(false)
async function runTrace() {
  if (!traceNo.value) return
  tracing.value = true
  traceError.value = ''
  trace.value = null
  try {
    trace.value = await api.get('/orders/' + encodeURIComponent(traceNo.value.trim()) + '/trace')
  } catch (e) {
    traceError.value = e.message
  } finally {
    tracing.value = false
  }
}

/* 哈希链校验 */
async function checkChain() {
  try {
    const r = await api.get('/dispositions/verify-chain')
    chainOk.value = r.valid
  } catch { chainOk.value = null }
}
checkChain()
const chainTimer = setInterval(checkChain, 15000)
onUnmounted(() => clearInterval(chainTimer))
</script>

<style scoped>
.disp-card { border: 1px solid var(--border); border-radius: 10px; padding: 12px 14px; margin-bottom: 12px; background: #fbfcff; }
.disp-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.act-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin: 10px 0; }
.act-opt { border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; cursor: pointer; display: flex; flex-direction: column; gap: 2px; font-size: 12.5px; color: var(--muted); background: #fff; }
.act-opt b { color: var(--text); font-size: 13px; }
.act-opt.on { border-color: var(--ccb-blue); background: #eef4ff; }
.act-opt input { display: none; }
.sec { margin: 14px 0 6px; font-size: 13.5px; color: var(--ccb-blue-dark); }
.trace-block { border-left: 3px solid #dbe4f5; padding: 4px 10px; margin-bottom: 6px; }
input, select { padding: 7px 10px; border: 1px solid var(--border); border-radius: 8px; font-size: 13px; }
</style>
