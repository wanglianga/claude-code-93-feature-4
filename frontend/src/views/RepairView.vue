<template>
  <div class="page">
    <BranchBanners />
    <div class="grid-2">
      <!-- 设备状态上报（幂等演示） -->
      <div class="card">
        <h3>设备状态上报 <span class="hint">eventId 全局唯一：反复上报同一事件只处理一次</span></h3>
        <div class="form-grid">
          <label class="field"><b>台位</b>
            <select v-model="report.counterId">
              <option v-for="c in store.counters" :key="c.id" :value="c.id">
                {{ c.name }}（{{ tag(L.CounterStatus, c.status).text }}）
              </option>
            </select>
          </label>
          <label class="field"><b>状态</b>
            <select v-model="report.status">
              <option value="OFFLINE">离线（触发处置单+维修单）</option>
              <option value="ONLINE">上线心跳</option>
            </select>
          </label>
          <label class="field full"><b>事件 ID（幂等键）</b>
            <div style="display:flex;gap:6px">
              <input v-model="report.eventId" style="flex:1" />
              <button class="btn gray sm" @click="regenEventId">重新生成</button>
            </div>
          </label>
          <label class="field full"><b>故障原因</b>
            <input v-model="report.reason" placeholder="如：叫号终端网络中断 / 凭条打印机故障" />
          </label>
        </div>
        <div class="btn-row">
          <button class="btn red" :disabled="reporting" @click="sendReport(false)">
            {{ reporting ? '上报中…' : '上报设备状态' }}
          </button>
          <button class="btn gray" :disabled="reporting || !lastEventId" @click="sendReport(true)">
            用同一事件 ID 重发（演示幂等）
          </button>
        </div>
        <div v-if="reportResult" class="banner" :class="reportResult.processed ? 'blue' : 'orange'" style="margin-top:10px">
          {{ reportResult.processed ? '已处理：' : '未重复处理：' }}{{ reportResult.resultNote }}
          <div class="small muted" style="margin-top:4px">事件 {{ reportResult.eventId }} ｜ 上报人 {{ reportResult.reportedBy }}</div>
        </div>
      </div>

      <!-- 台位状态总览 -->
      <div class="card">
        <h3>台位状态总览</h3>
        <table>
          <thead><tr><th>台位</th><th>类型</th><th>状态</th><th>负载/容量</th><th>可办业务</th></tr></thead>
          <tbody>
            <tr v-for="c in store.counters" :key="c.id">
              <td>{{ c.name }}</td>
              <td class="small">{{ L.CounterType[c.type] }}</td>
              <td><span class="tag" :class="tag(L.CounterStatus, c.status).cls">{{ tag(L.CounterStatus, c.status).text }}</span></td>
              <td class="small">{{ loadOf(c.id) }}/{{ c.capacity }}</td>
              <td class="small muted">{{ bizText(c.supportedBusiness) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 维修单 -->
    <div class="card">
      <h3>维修单 <span class="hint">维修完成后客户不会自动回队，系统按权重快照生成公平恢复建议，待主管复核</span></h3>
      <table>
        <thead><tr><th>维修单号</th><th>台位</th><th>故障</th><th>状态</th><th>时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="r in store.repairs" :key="r.id">
            <td><span class="ticket-no">{{ r.repairNo }}</span></td>
            <td>{{ r.counterName }}</td>
            <td class="small">{{ r.failureReason }}</td>
            <td><span class="tag" :class="tag(L.RepairStatus, r.status).cls">{{ tag(L.RepairStatus, r.status).text }}</span></td>
            <td class="small">接单 {{ fmtTime(r.createdAt) }}<br />完成 {{ fmtTime(r.completedAt) }}</td>
            <td>
              <div class="btn-row" style="margin-top:0">
                <button v-if="r.status === 'OPEN'" class="btn sm orange" @click="start(r)">开始维修</button>
                <button v-if="r.status !== 'DONE'" class="btn sm green" :disabled="completing[r.id]" @click="complete(r)">
                  {{ completing[r.id] ? '提交中…' : '维修完成' }}
                </button>
                <span v-if="r.status === 'DONE'" class="small muted">{{ r.resultNote }}</span>
              </div>
            </td>
          </tr>
          <tr v-if="!store.repairs.length"><td colspan="6"><div class="empty">暂无维修单（设备离线后自动生成）</div></td></tr>
        </tbody>
      </table>
      <div v-if="completeMsg" class="banner blue" style="margin-top:10px">{{ completeMsg }}</div>
    </div>

    <!-- 恢复建议（待主管复核） -->
    <div class="card">
      <h3>公平恢复建议（待主管复核） <span class="hint">按故障时权重快照 + 当前窗口负载 + 同类业务服务能力计算，不插队首</span></h3>
      <table>
        <thead><tr><th>处置单</th><th>业务单号</th><th>权重快照</th><th>建议内容</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="d in proposed" :key="d.id">
            <td><span class="ticket-no">{{ d.orderNo }}</span></td>
            <td>{{ d.ticketNo }}</td>
            <td>{{ d.weightSnapshot }}</td>
            <td class="small">{{ suggestionText(d.recoverySuggestion) }}</td>
            <td><span class="tag" :class="tag(L.DispositionStatus, d.status).cls">{{ tag(L.DispositionStatus, d.status).text }}</span></td>
          </tr>
          <tr v-if="!proposed.length"><td colspan="5"><div class="empty">暂无待复核的恢复建议</div></td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { api, newIdemKey } from '../api.js'
import { store } from '../store.js'
import { L, tag, fmtTime } from '../labels.js'
import { toast } from '../toast.js'
import BranchBanners from '../components/BranchBanners.vue'

defineProps({ user: Object })

const report = reactive({ counterId: 4, status: 'OFFLINE', eventId: newIdemKey(), reason: '' })
const reporting = ref(false)
const reportResult = ref(null)
const lastEventId = ref('')
const completing = reactive({})
const completeMsg = ref('')
const completeKeys = reactive({})

const proposed = computed(() => store.dispositions.filter(d => d.status === 'RECOVERY_PROPOSED'))

function regenEventId() { report.eventId = newIdemKey() }

async function sendReport(reuse) {
  if (reporting.value) return
  reporting.value = true
  try {
    const eventId = reuse ? lastEventId.value : report.eventId
    const r = await api.postIdem('/devices/events',
      { eventId, counterId: report.counterId, status: report.status, reason: report.reason }, eventId)
    reportResult.value = r
    lastEventId.value = eventId
    if (!reuse) { report.eventId = newIdemKey() }
    toast(r.processed ? '设备状态已处理' : '重复上报已幂等忽略')
    await store.refresh()
  } catch (e) { toast(e.message, 'err') } finally { reporting.value = false }
}

async function start(r) {
  try {
    await api.post(`/repairs/${r.id}/start`, {})
    toast('已接单，开始维修 ' + r.repairNo)
    await store.refresh()
  } catch (e) { toast(e.message, 'err') }
}

async function complete(r) {
  if (completing[r.id]) return
  completing[r.id] = true
  if (!completeKeys[r.id]) completeKeys[r.id] = newIdemKey()
  try {
    const res = await api.postIdem(`/repairs/${r.id}/complete`,
      { note: '设备修复，自检通过' }, completeKeys[r.id])
    completeMsg.value = res.message || '维修完成'
    toast(completeMsg.value)
    await store.refresh()
  } catch (e) { toast(e.message, 'err') } finally { completing[r.id] = false }
}

function loadOf(counterId) {
  return store.tickets.filter(t => t.counterId === counterId && ['ASSIGNED', 'SERVING'].includes(t.status)).length
}
function bizText(s) {
  if (!s) return '全部业务'
  return s.split(',').map(x => L.BusinessType[x.trim()] || x).join('、')
}
function suggestionText(json) {
  try {
    const s = JSON.parse(json)
    return s.reason || json
  } catch { return json || '—' }
}
</script>
