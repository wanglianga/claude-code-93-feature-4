<template>
  <div class="page">
    <BranchBanners />
    <div class="kpi-row">
      <div class="kpi red"><div class="v">{{ openCount }}</div><div class="l">待接单</div></div>
      <div class="kpi orange"><div class="v">{{ repairingCount }}</div><div class="l">维修中</div></div>
      <div class="kpi green"><div class="v">{{ doneCount }}</div><div class="l">今日已闭环</div></div>
    </div>

    <div class="card">
      <h3>设备维修工单 <span class="hint">设备离线/故障自动生成；维修完成后系统按权重快照生成公平恢复建议，交主管复核</span></h3>
      <table>
        <thead>
          <tr>
            <th>维修单号</th><th>台位</th><th>故障类型</th><th>故障描述</th><th>上报人</th>
            <th>状态</th><th>上报时间</th><th>完成时间</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in store.maintenance" :key="m.id">
            <td class="mono">{{ m.orderNo }}</td>
            <td>{{ m.counterName }}</td>
            <td><span class="tag red">{{ m.failureStatus === 'OFFLINE' ? '设备离线' : '设备故障' }}</span></td>
            <td class="small">{{ m.faultDesc || '—' }}</td>
            <td class="small">{{ m.reportedBy }}</td>
            <td><span class="tag" :class="tag(L.MaintenanceStatus, m.status).cls">{{ tag(L.MaintenanceStatus, m.status).text }}</span></td>
            <td class="small">{{ fmtTime(m.createdAt) }}</td>
            <td class="small">{{ fmtTime(m.finishedAt) }}</td>
            <td class="pill-actions">
              <button v-if="m.status === 'OPEN'" class="btn btn-sm" @click="start(m.id)">接单维修</button>
              <button v-if="m.status === 'REPAIRING'" class="btn btn-sm green" @click="finish(m.id)">维修完成</button>
              <span v-if="m.status === 'DONE'" class="small muted">已闭环</span>
            </td>
          </tr>
          <tr v-if="!store.maintenance.length"><td colspan="9"><div class="empty">暂无维修工单</div></td></tr>
        </tbody>
      </table>
    </div>

    <div class="card" v-if="lastSuggestions.length">
      <h3>本次维修完成生成的公平恢复建议 <span class="hint">未自动插回队首，待主管复核后应用</span></h3>
      <table>
        <thead>
          <tr><th>业务单号</th><th>建议台位</th><th>建议排位</th><th>计算依据</th><th>状态</th></tr>
        </thead>
        <tbody>
          <tr v-for="s in lastSuggestions" :key="s.id">
            <td><span class="ticket-no">{{ s.bizOrderNo }}</span></td>
            <td>{{ s.suggestedCounterName }}</td>
            <td>第 {{ s.suggestedPosition }} 位</td>
            <td class="small"><ReasonView :json="s.reasonJson" /></td>
            <td><span class="tag" :class="tag(L.SuggestionStatus, s.status).cls">{{ tag(L.SuggestionStatus, s.status).text }}</span></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, h } from 'vue'
import { api } from '../api.js'
import { store } from '../store.js'
import { L, tag, fmtTime } from '../labels.js'
import { toast } from '../toast.js'
import BranchBanners from '../components/BranchBanners.vue'

const lastSuggestions = ref([])

const openCount = computed(() => store.maintenance.filter(m => m.status === 'OPEN').length)
const repairingCount = computed(() => store.maintenance.filter(m => m.status === 'REPAIRING').length)
const doneCount = computed(() => store.maintenance.filter(m => m.status === 'DONE').length)

async function start(id) {
  try {
    await api.post('/maintenance/' + id + '/start')
    toast('已接单，开始维修')
    await store.refresh()
  } catch (e) { toast(e.message, 'err') }
}

async function finish(id) {
  try {
    const list = await api.post('/maintenance/' + id + '/finish')
    lastSuggestions.value = list
    toast(list.length ? `维修完成，已生成 ${list.length} 条公平恢复建议（待主管复核）` : '维修完成，设备恢复开放')
    await store.refresh()
  } catch (e) { toast(e.message, 'err') }
}

/* 恢复建议计算依据的简要渲染 */
const ReasonView = (props) => {
  try {
    const r = JSON.parse(props.json)
    return h('div', {}, [
      h('div', `权重快照 ${r.weightSnapshot} · 故障时已等待 ${r.waitedSecondsAtFailure}s`),
      h('div', { class: 'muted' }, `候选 ${ (r.candidates || []).map(c => c.name + '(' + c.loadPct + '%)').join(' / ') } → 选中：${r.chosenReason}`)
    ])
  } catch { return h('span', {}, props.json) }
}
ReasonView.props = { json: String }
</script>

<style scoped>
.mono { font-family: ui-monospace, monospace; }
</style>
