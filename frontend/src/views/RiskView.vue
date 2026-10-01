<template>
  <div class="page">
    <BranchBanners />
    <div class="kpi-row">
      <div class="kpi red"><div class="v">{{ openCount }}</div><div class="l">待确认告警</div></div>
      <div class="kpi orange"><div class="v">{{ blockingCount }}</div><div class="l">阻断中（待放行）</div></div>
      <div class="kpi green"><div class="v">{{ ackCount }}</div><div class="l">已确认</div></div>
    </div>

    <div class="card">
      <h3>排队风控告警 <span class="hint">重复取号 / 跨窗口频繁转台 / 人工插队；身份已核验的紧急业务告警不阻断</span></h3>
      <table>
        <thead>
          <tr>
            <th>告警编号</th><th>类型</th><th>客户</th><th>业务单号</th><th>详情</th>
            <th>阻断</th><th>追溯引用</th><th>状态</th><th>时间</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in store.riskAlerts" :key="a.id">
            <td class="mono">{{ a.alertNo }}</td>
            <td><span class="tag" :class="a.alertType === 'MANUAL_JUMP' ? 'purple' : 'orange'">
              {{ L.RiskAlertType[a.alertType] }}</span></td>
            <td>{{ a.customerName || '—' }}</td>
            <td>
              <span class="ticket-no" style="cursor:pointer" @click="$emit('trace', a.bizOrderNo)">{{ a.bizOrderNo }}</span>
            </td>
            <td class="small" style="max-width:340px">{{ a.detail }}</td>
            <td>
              <span v-if="a.blocking" class="tag red">阻断中</span>
              <span v-else class="tag green">不阻断</span>
            </td>
            <td class="mono small muted">{{ a.traceRef || '—' }}</td>
            <td>
              <span class="tag" :class="tag(L.RiskAlertStatus, a.status).cls">{{ tag(L.RiskAlertStatus, a.status).text }}</span>
              <div class="small muted" v-if="a.acknowledgedBy">{{ a.acknowledgedBy }} · {{ fmtTime(a.acknowledgedAt) }}</div>
            </td>
            <td class="small">{{ fmtTime(a.createdAt) }}</td>
            <td class="pill-actions">
              <button v-if="a.status === 'OPEN'" class="btn btn-sm green" @click="ack(a.id)">
                确认{{ a.blocking ? '并放行' : '' }}
              </button>
            </td>
          </tr>
          <tr v-if="!store.riskAlerts.length"><td colspan="10"><div class="empty">暂无风控告警</div></td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { api } from '../api.js'
import { store } from '../store.js'
import { L, tag, fmtTime } from '../labels.js'
import { toast } from '../toast.js'
import BranchBanners from '../components/BranchBanners.vue'

defineEmits(['trace'])

const openCount = computed(() => store.riskAlerts.filter(a => a.status === 'OPEN').length)
const blockingCount = computed(() => store.riskAlerts.filter(a => a.status === 'OPEN' && a.blocking).length)
const ackCount = computed(() => store.riskAlerts.filter(a => a.status === 'ACKNOWLEDGED').length)

async function ack(id) {
  try {
    await api.post('/risk-alerts/' + id + '/ack')
    toast('告警已确认' + '，相关单据已放行')
    await store.refresh()
  } catch (e) { toast(e.message, 'err') }
}
</script>

<style scoped>
.mono { font-family: ui-monospace, monospace; }
</style>
