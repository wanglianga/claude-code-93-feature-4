<template>
  <div class="page">
    <BranchBanners />
    <div class="card">
      <h3>恢复建议复核 <span class="hint">设备维修闭环后生成，客户不会自动回队；复核通过才按权重快照公平插入</span></h3>
      <div v-if="!pending.length" class="empty">暂无待复核的恢复建议</div>
      <div v-for="d in pending" :key="d.id" class="rv-card">
        <div class="disp-head">
          <span class="ticket-no">{{ d.orderNo }}</span>
          <span class="tag" :class="tag(L.FailureType, d.failureType).cls">{{ tag(L.FailureType, d.failureType).text }}</span>
          <span class="small muted">业务单号 {{ d.ticketNo }} · {{ d.customerName }} · {{ L.BusinessType[d.businessType] }}</span>
        </div>
        <div class="desc-list">
          <dt>故障时权重快照</dt><dd>{{ d.weightSnapshot }}（优先级 P{{ d.priority }}，故障时已等待 {{ waitText(d.waitedSeconds) }}）</dd>
          <dt>原台位</dt><dd>{{ d.originalCounterName || '—' }}</dd>
          <dt>恢复建议</dt><dd>{{ suggestionText(d.recoverySuggestion) }}</dd>
        </div>
        <div class="form-grid">
          <label class="field"><b>恢复台位（默认按系统建议，可改派）</b>
            <select v-model="choice[d.id]">
              <option :value="null">按系统建议（{{ suggestedName(d) }}）</option>
              <option v-for="c in openCounters" :key="c.id" :value="c.id">
                改派：{{ c.name }}（负载 {{ loadOf(c.id) }}/{{ c.capacity }}）
              </option>
            </select>
          </label>
          <label class="field"><b>复核意见</b><input v-model="note[d.id]" placeholder="复核说明（可选）" /></label>
        </div>
        <div class="btn-row">
          <button class="btn green" :disabled="busy[d.id]" @click="review(d, true)">复核通过·公平回队</button>
          <button class="btn orange" :disabled="busy[d.id]" @click="review(d, false)">退回前台重新处置</button>
        </div>
      </div>
    </div>

    <div class="card">
      <h3>已闭环处置单 <span class="hint">含主管复核结论，全程可追溯</span></h3>
      <table>
        <thead><tr><th>处置单</th><th>业务单号</th><th>失败原因</th><th>闭环结论</th><th>闭环时间</th></tr></thead>
        <tbody>
          <tr v-for="d in resolved" :key="d.id">
            <td><span class="ticket-no">{{ d.orderNo }}</span></td>
            <td>{{ d.ticketNo }}</td>
            <td class="small">{{ d.failureReason }}</td>
            <td class="small">{{ d.resolutionNote || '—' }}</td>
            <td class="small">{{ fmtTime(d.resolvedAt) }}</td>
          </tr>
          <tr v-if="!resolved.length"><td colspan="5"><div class="empty">暂无闭环记录</div></td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive } from 'vue'
import { api } from '../api.js'
import { store } from '../store.js'
import { L, tag, fmtTime, waitText } from '../labels.js'
import { toast } from '../toast.js'
import BranchBanners from '../components/BranchBanners.vue'

defineProps({ user: Object })

const choice = reactive({})
const note = reactive({})
const busy = reactive({})

const pending = computed(() => store.dispositions.filter(d => d.status === 'RECOVERY_PROPOSED'))
const resolved = computed(() => store.dispositions.filter(d => d.status === 'RESOLVED'))
const openCounters = computed(() => store.counters.filter(c => c.status === 'OPEN'))

function loadOf(counterId) {
  return store.tickets.filter(t => t.counterId === counterId && ['ASSIGNED', 'SERVING'].includes(t.status)).length
}
function suggestionText(json) {
  try { return JSON.parse(json).reason || json } catch { return json || '—' }
}
function suggestedName(d) {
  try { return JSON.parse(d.recoverySuggestion).counterName || '—' } catch { return '—' }
}

async function review(d, approve) {
  if (busy[d.id]) return
  busy[d.id] = true
  try {
    const r = await api.post(`/dispositions/${d.id}/review`,
      { approve, counterId: choice[d.id] || null, note: note[d.id] || '' })
    if (approve) {
      toast(`复核通过：${d.ticketNo} 已恢复至队列第 ${r.position} 位`)
    } else {
      toast('已退回前台重新处置')
    }
    await store.refresh()
  } catch (e) { toast(e.message, 'err') } finally { busy[d.id] = false }
}
</script>

<style scoped>
.rv-card { border: 1px solid var(--border); border-radius: 10px; padding: 12px 14px; margin-bottom: 12px; background: #fbfcff; }
.disp-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
</style>
