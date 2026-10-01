<template>
  <div class="modal-mask" @click.self="$emit('close')">
    <div class="modal wide">
      <button class="modal-close" @click="$emit('close')">×</button>
      <h2>排队记录 {{ t?.ticketNo }}
        <span v-if="t" class="tag" :class="st.cls">{{ st.text }}</span>
        <span v-if="t?.fraudStatus && t.fraudStatus !== 'NONE'" class="tag" :class="fs.cls" style="margin-left:6px">
          反诈：{{ fs.text }}
        </span>
      </h2>
      <div class="m-sub">同一记录由大堂经理、柜员、理财经理、安保、客服、客户六方协同处理</div>

      <div v-if="t" class="desc-list">
        <dt>客户</dt><dd>{{ t.customerName }} <span class="tag" :class="ct.cls">{{ ct.text }}</span></dd>
        <dt>来源</dt><dd>{{ L.Source[t.source] }}</dd>
        <dt>业务类型</dt><dd>{{ L.BusinessType[t.businessType] }}<span v-if="t.amount" class="mono">（{{ t.amount }} 元）</span></dd>
        <dt>风险等级</dt><dd><span class="tag" :class="rl.cls">{{ rl.text }}风险</span></dd>
        <dt>适老服务</dt>
        <dd colspan="3">
          <span v-if="!t.elderly && !t.wheelchair && !t.hearingAssist" class="muted">无特殊需求</span>
          <span v-if="t.elderly" class="tag orange">老人</span>
          <span v-if="t.wheelchair" class="tag purple">轮椅·无障碍</span>
          <span v-if="t.hearingAssist" class="tag purple">听力引导</span>
          <span v-if="t.escort" class="tag blue">陪同引导</span>
          <span v-if="t.priorityWindow" class="tag blue">优先窗口</span>
          <span v-if="t.preReview" class="tag blue">材料预审</span>
          <span v-if="t.familyConfirm" class="tag blue">家属远程确认（{{ t.familyContact || '未登记联系方式' }}）</span>
        </dd>
        <dt>预计时长</dt><dd>{{ t.estimatedMinutes }} 分钟</dd>
        <dt>预约时间</dt><dd>{{ fmtTime(t.appointmentTime) }}</dd>
        <dt>材料情况</dt><dd><span class="tag" :class="ma.cls">{{ ma.text }}</span> {{ t.materialNote || '' }}</dd>
        <dt>引导渠道</dt><dd>{{ t.channel ? L.Channel[t.channel] : '待分流' }}
          <span v-if="counterName" class="muted"> / {{ counterName }}</span></dd>
        <dt>取号时间</dt><dd>{{ fmtTime(t.createdAt) }}</dd>
        <dt>预计叫号</dt><dd>{{ fmtTime(t.estimatedCallAt) }}
          <span v-if="t.urgent" class="tag red">紧急</span>
          <span v-if="t.identityVerified" class="tag green">身份已核验</span></dd>
        <dt>排队权重</dt><dd class="small">
          优先级分 {{ t.priorityScore }} · 继承等待 {{ waitText(t.inheritedWaitSeconds) }}
          <span v-if="t.weightSnapshot" class="muted"> · 故障权重快照 {{ t.weightSnapshot }}</span>
          <span v-if="t.transferCount" class="muted"> · 已转台 {{ t.transferCount }} 次</span>
        </dd>
        <dt>开始办理</dt><dd>{{ fmtTime(t.servingAt) }}（等待 {{ waitText(t.waitSeconds) }}）</dd>
        <dt>办结时间</dt><dd>{{ fmtTime(t.finishedAt) }}</dd>
        <dt>办理结果</dt><dd>{{ t.resultType ? L.ResultType[t.resultType] : '—' }} {{ t.resultNote || '' }}</dd>
        <dt>风险提示</dt><dd>{{ t.riskNotice || '—' }}</dd>
        <dt>反诈记录</dt><dd>{{ t.fraudNote || '—' }}</dd>
        <dt>投诉</dt><dd>{{ t.complaint ? ('是：' + (t.complaintNote || '')) : '无' }}</dd>
        <dt>回访</dt><dd>{{ t.callbackNote || '未回访' }}</dd>
      </div>

      <!-- 角色化操作区 -->
      <div class="card" style="margin-top:14px" v-if="t">
        <h3>协同操作 <span class="hint">当前身份：{{ L.Role[user.role] }} · {{ user.displayName }}</span></h3>

        <!-- 大堂经理：分流 -->
        <template v-if="user.role === 'MANAGER' && ['WAITING','ASSIGNED','PENDING'].includes(t.status) && t.fraudStatus !== 'PENDING'">
          <div class="form-grid">
            <label class="field"><b>引导渠道（防柜台挤兑）</b>
              <select v-model="assign.channel">
                <option value="COUNTER">柜台</option>
                <option value="SELF_MACHINE">自助机</option>
                <option value="WEALTH_ROOM">理财室</option>
                <option value="REMOTE_SERVICE">远程客服</option>
              </select>
            </label>
            <label class="field"><b>窗口/设备</b>
              <select v-model="assign.counterId">
                <option :value="null">不指定</option>
                <option v-for="c in store.counters" :key="c.id" :value="c.id">
                  {{ c.name }}（{{ L.CounterType[c.type] }}·{{ tag(L.CounterStatus, c.status).text }}）
                </option>
              </select>
            </label>
            <label class="field"><b>处理人</b>
              <select v-model="assign.assigneeId">
                <option :value="null">不指定</option>
                <option v-for="s in assignableStaff" :key="s.id" :value="s.id">
                  {{ s.displayName }}（{{ L.Role[s.role] }}）
                </option>
              </select>
            </label>
            <label class="field"><b>材料完整度</b>
              <select v-model="assign.material">
                <option value="COMPLETE">齐全</option>
                <option value="INCOMPLETE">不完整</option>
                <option value="MISSING">缺失（如老人忘带证件）</option>
              </select>
            </label>
            <label class="field full"><b>缺件说明</b>
              <input v-model="assign.materialNote" placeholder="如：未带身份证原件 / 社保卡密码遗忘" />
            </label>
          </div>
          <div class="check-row">
            <label><input type="checkbox" v-model="assign.escort" />陪同引导</label>
            <label><input type="checkbox" v-model="assign.priorityWindow" />优先窗口</label>
            <label><input type="checkbox" v-model="assign.preReview" />材料预审</label>
            <label><input type="checkbox" v-model="assign.familyConfirm" />家属远程确认</label>
            <button class="btn btn-sm gray" style="margin-left:auto" @click="loadRecommend">获取渠道推荐</button>
          </div>
          <div v-if="recommendMsg" class="banner blue" style="margin-top:10px">
            建议引导至【{{ L.Channel[recommendMsg.channel] }}】：{{ recommendMsg.reason }}
          </div>
          <div class="btn-row">
            <button class="btn" @click="doAssign">确认分流</button>
            <button class="btn orange" @click="quickEvent('MISSING_ID','老人忘带/材料缺失，启动预审与家属确认')">上报：忘带证件</button>
            <button class="btn red" @click="doJump">人工插队（产生风控告警）</button>
          </div>
        </template>

        <!-- 柜员：叫号 / 办结 -->
        <template v-if="user.role === 'TELLER'">
          <div class="btn-row" v-if="t.status === 'ASSIGNED' || (t.status==='PENDING' && t.fraudStatus!=='PENDING')">
            <button class="btn green" @click="doCall">叫号办理</button>
          </div>
          <template v-if="t.status === 'SERVING'">
            <div class="form-grid">
              <label class="field"><b>办理结果</b>
                <select v-model="complete.resultType">
                  <option value="SUCCESS">办理成功</option>
                  <option value="REJECTED">拒绝/撤回</option>
                  <option value="ESCALATED">升级处理</option>
                  <option value="REDIRECTED">引导至其他渠道</option>
                </select>
              </label>
              <label class="field"><b>结果备注</b><input v-model="complete.resultNote" placeholder="办理说明" /></label>
              <label class="field full"><b>风险提示（进入运营档案）</b>
                <input v-model="complete.riskNotice" placeholder="如：已提示客户谨防电信诈骗、勿向陌生账户转账" />
              </label>
              <label class="field full"><b>客户投诉</b>
                <input v-model="complete.complaintNote" placeholder="如有投诉请填写内容，留空表示无投诉" />
              </label>
            </div>
            <div class="btn-row">
              <button class="btn green" @click="doComplete">办结归档</button>
              <button class="btn orange" @click="quickEvent('TELLER_LEAVE','柜员临时离岗，单据挂起重新分流')">临时离岗</button>
              <button class="btn red" @click="reportDeviceFault">设备故障上报</button>
            </div>
          </template>
        </template>

        <!-- 安保：反诈核验 / 无障碍 -->
        <template v-if="user.role === 'SECURITY'">
          <div v-if="t.fraudStatus === 'PENDING'" class="banner red">
            大额业务触发反诈核验，请核实交易背景、受款人关系与客户认知状态后放行或拦截
          </div>
          <div class="form-grid" v-if="t.fraudStatus === 'PENDING'">
            <label class="field full"><b>核验意见</b>
              <textarea v-model="fraudNote" rows="2" placeholder="如：与儿子电话核实属实，购药用途；或：客户被诱导转账，疑似诈骗"></textarea>
            </label>
          </div>
          <div class="btn-row">
            <button v-if="t.fraudStatus === 'PENDING'" class="btn green" @click="doFraud('PASSED')">核验通过·恢复办理</button>
            <button v-if="t.fraudStatus === 'PENDING'" class="btn red" @click="doFraud('BLOCKED')">拦截·劝阻客户</button>
            <button class="btn gray" @click="reportDeviceFault">上报设备故障</button>
          </div>
          <div v-if="t.wheelchair" class="banner blue">♿ 该客户有轮椅需求：请提前开启无障碍通道并到门口迎接</div>
        </template>

        <!-- 理财经理 -->
        <template v-if="user.role === 'ADVISOR'">
          <div class="btn-row">
            <button v-if="t.status === 'ASSIGNED'" class="btn green" @click="doCall">叫号进入理财室</button>
            <button v-if="t.status === 'SERVING'" class="btn green" @click="doComplete">双录完成·办结</button>
          </div>
        </template>

        <!-- 客服：投诉 / 回访 / 远程渠道 -->
        <template v-if="user.role === 'SERVICE'">
          <div class="btn-row" v-if="t.status !== 'DONE' && t.status !== 'CANCELLED'">
            <button class="btn red" @click="quickEvent('JUMP_COMPLAINT','客户投诉排队秩序/疑似插队，客服介入维序并致歉')">受理：投诉插队</button>
            <button class="btn gray" @click="quickEvent('LONG_WAIT','客服主动关怀长时间等待客户')">长等关怀</button>
          </div>
          <div class="form-grid" style="margin-top:8px">
            <label class="field full"><b>回访记录（办结后）</b>
              <textarea v-model="callbackNote" rows="2" placeholder="如：客户对办理结果满意，已再次提示用卡安全"></textarea>
            </label>
          </div>
          <div class="btn-row">
            <button class="btn" :disabled="t.status !== 'DONE'" @click="doCallback">保存回访到运营档案</button>
          </div>
        </template>

        <!-- 客户本人 -->
        <template v-if="user.role === 'CUSTOMER'">
          <div class="muted small">您可在等待期间取消预约；如需帮助请联系大堂经理（爱心窗口可优先安排老人业务）。</div>
          <div class="btn-row">
            <button v-if="['WAITING','ASSIGNED','PENDING'].includes(t.status)" class="btn red" @click="doCancel">取消排队</button>
          </div>
        </template>
      </div>

      <!-- 处理时间线 -->
      <div class="card" style="margin-top:14px">
        <h3>协同处理时间线 <span class="hint">取号 → 预审/分流 → 叫号 → 异常协同 → 办结 → 回访</span></h3>
        <ul class="timeline">
          <li v-for="e in timeline" :key="e.id" :class="{ alert: e.type !== 'NONE' && !e.resolved, ok: e.resolved && e.type !== 'NONE' }">
            <span class="t-time">{{ fmtTime(e.createdAt) }}</span>
            <span class="t-who" v-if="e.actorName">{{ e.actorName }}<span class="muted">（{{ e.actorRole ? L.Role[e.actorRole] : '' }}）</span></span>
            <span v-if="e.type !== 'NONE'" class="tag red">{{ L.IssueType[e.type] }}</span>
            <div class="t-text">{{ e.content }}</div>
            <div v-if="e.resolution" class="small" style="color:var(--green)">✔ 已处理：{{ e.resolution }} · {{ fmtTime(e.resolvedAt) }}</div>
            <div v-else-if="e.type !== 'NONE'" class="small">
              <button class="btn btn-sm gray" @click="resolveEvent(e.id)">标记已处理{{ t.status === 'PENDING' ? '并恢复排队' : '' }}</button>
            </div>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { api } from '../api.js'
import { store } from '../store.js'
import { L, tag, fmtTime, waitText } from '../labels.js'
import { toast } from '../toast.js'

const props = defineProps({ ticketId: Number, user: Object })
const emit = defineEmits(['close', 'changed'])

const t = ref(null)
const timeline = ref([])
const recommendMsg = ref(null)
const fraudNote = ref('')
const callbackNote = ref('')

const assign = reactive({
  channel: 'COUNTER', counterId: null, assigneeId: null,
  material: 'COMPLETE', materialNote: '',
  escort: false, priorityWindow: false, preReview: false, familyConfirm: false
})
const complete = reactive({ resultType: 'SUCCESS', resultNote: '', riskNotice: '', complaint: false, complaintNote: '' })

const st = computed(() => t.value ? tag(L.TicketStatus, t.value.status) : { text: '', cls: '' })
const fs = computed(() => t.value ? tag(L.FraudStatus, t.value.fraudStatus) : { text: '', cls: '' })
const ct = computed(() => t.value ? tag(L.CustomerType, t.value.customerType) : { text: '', cls: '' })
const rl = computed(() => t.value ? tag(L.RiskLevel, t.value.riskLevel) : { text: '', cls: '' })
const ma = computed(() => t.value ? tag(L.Material, t.value.material) : { text: '', cls: '' })
const counterName = computed(() => {
  if (!t.value?.counterId) return ''
  return store.counters.find(c => c.id === t.value.counterId)?.name || ''
})
const assignableStaff = computed(() =>
  store.staff.filter(s => ['TELLER', 'ADVISOR', 'SERVICE', 'MANAGER'].includes(s.role)))

watch(() => props.ticketId, load, { immediate: true })

async function load() {
  t.value = null
  timeline.value = []
  recommendMsg.value = null
  if (!props.ticketId) return
  t.value = await api.get('/tickets/' + props.ticketId)
  timeline.value = await api.get('/tickets/' + props.ticketId + '/timeline')
  callbackNote.value = t.value.callbackNote || ''
  if (t.value.channel) assign.channel = t.value.channel
  assign.counterId = t.value.counterId
  assign.assigneeId = t.value.assigneeId
  assign.material = t.value.material
  assign.escort = t.value.escort
  assign.priorityWindow = t.value.priorityWindow
  assign.preReview = t.value.preReview
  assign.familyConfirm = t.value.familyConfirm
  fraudNote.value = t.value.fraudNote || ''
}

async function refreshAll() {
  await load()
  await store.refresh()
  emit('changed')
}

async function loadRecommend() {
  recommendMsg.value = await api.get('/tickets/' + props.ticketId + '/recommend')
  assign.channel = recommendMsg.value.channel
  toast('已生成渠道推荐')
}

async function doAssign() {
  try {
    await api.post('/tickets/' + props.ticketId + '/assign', assign)
    toast('分流完成')
    await refreshAll()
  } catch (e) { toast(e.message, 'err') }
}

// 人工插队：提升排队权重并产生可追溯风控告警（不阻断已核验紧急业务）
async function doJump() {
  const reason = window.prompt('请填写人工插队原因（将生成风控告警并留痕）：', '客户特殊情况，现场主管同意优先')
  if (reason === null) return
  try {
    await api.post('/tickets/' + props.ticketId + '/jump', { reason })
    toast('已调整排队位置，风控告警已生成')
    await refreshAll()
  } catch (e) { toast(e.message, 'err') }
}

// 设备故障/离线：走设备上报流程，自动生成处置单与维修单
async function reportDeviceFault() {
  if (!t.value.counterId) { toast('该单据未绑定台位，请在台位卡片上上报设备状态', 'err'); return }
  try {
    await api.post('/devices/' + t.value.counterId + '/status', {
      status: 'FAULT', reportKey: crypto.randomUUID(), note: '现场上报设备故障'
    })
    toast('设备故障已上报：在队客户已冻结权重并生成处置单')
    await refreshAll()
  } catch (e) { toast(e.message, 'err') }
}

async function doCall() {
  try {
    const r = await api.post('/tickets/' + props.ticketId + '/call')
    toast('已叫号 ' + r.ticketNo + (r.fraudStatus === 'PENDING' ? '，触发反诈核验！' : ''))
    await refreshAll()
  } catch (e) { toast(e.message, 'err') }
}

async function doComplete() {
  complete.complaint = !!complete.complaintNote
  try {
    await api.post('/tickets/' + props.ticketId + '/complete', complete)
    toast('已办结并进入运营档案')
    await refreshAll()
  } catch (e) { toast(e.message, 'err') }
}

async function doFraud(status) {
  try {
    await api.post('/tickets/' + props.ticketId + '/fraud', { fraudStatus: status, fraudNote: fraudNote.value })
    toast(status === 'PASSED' ? '核验通过，恢复办理' : '已拦截并记录')
    await refreshAll()
  } catch (e) { toast(e.message, 'err') }
}

async function quickEvent(type, content) {
  try {
    await api.post('/events', { ticketId: props.ticketId, type, content, counterId: t.value.counterId })
    toast('事件已上报，相关岗位同步可见')
    await refreshAll()
  } catch (e) { toast(e.message, 'err') }
}

async function resolveEvent(id) {
  try {
    await api.post('/events/' + id + '/resolve', { resolution: '现场已处置完毕', resumeTicket: t.value.status === 'PENDING' })
    toast('事件已闭环')
    await refreshAll()
  } catch (e) { toast(e.message, 'err') }
}

async function doCallback() {
  try {
    await api.post('/tickets/' + props.ticketId + '/callback', { callbackNote: callbackNote.value })
    toast('回访已归档')
    await refreshAll()
  } catch (e) { toast(e.message, 'err') }
}

async function doCancel() {
  try {
    await api.post('/tickets/' + props.ticketId + '/cancel', { reason: '客户主动取消' })
    toast('已取消排队')
    await refreshAll()
  } catch (e) { toast(e.message, 'err') }
}
</script>
