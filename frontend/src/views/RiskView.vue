<template>
  <div class="page">
    <BranchBanners />
    <div class="banner blue" style="margin-bottom:14px">
      风控告警仅记录与追溯，<b>不阻断业务办理</b>；对已完成身份核验的紧急业务，系统自动标记「紧急已核验·不阻断」。
      每条告警带全链路追溯号，可按业务单号在前台「统一查询」中回溯。
    </div>

    <div class="kpi-row">
      <div class="kpi red"><b>{{ openCount }}</b><span>待处理告警</span></div>
      <div class="kpi orange"><b>{{ countOf('REPEAT_TICKET') }}</b><span>重复取号</span></div>
      <div class="kpi purple"><b>{{ countOf('FREQUENT_TRANSFER') }}</b><span>频繁转台</span></div>
      <div class="kpi red"><b>{{ countOf('MANUAL_JUMP') }}</b><span>人工插队</span></div>
    </div>

    <div class="card">
      <h3>风险告警 <span class="hint">重复取号 / 跨窗口频繁转台 / 人工插队</span></h3>
      <table>
        <thead>
          <tr><th>告警编号</th><th>类型</th><th>业务单号</th><th>客户</th><th>详情</th><th>追溯号</th><th>状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="a in store.riskAlerts" :key="a.id">
            <td class="small mono">{{ a.alertNo }}</td>
            <td><span class="tag" :class="tag(L.RiskAlertType, a.alertType).cls">{{ tag(L.RiskAlertType, a.alertType).text }}</span></td>
            <td><span class="ticket-no">{{ a.ticketNo }}</span></td>
            <td class="small">{{ a.customerName }}</td>
            <td class="small">
              {{ a.detail }}
              <div v-if="a.urgentVerifiedBypass" class="tag green" style="margin-top:2px">紧急已核验·不阻断</div>
              <div class="muted" style="margin-top:2px">{{ a.bypassNote }}</div>
            </td>
            <td class="small mono">{{ a.traceId.substring(0, 13) }}…</td>
            <td><span class="tag" :class="tag(L.RiskAlertStatus, a.status).cls">{{ tag(L.RiskAlertStatus, a.status).text }}</span>
              <div v-if="a.ackBy" class="small muted">{{ a.ackBy }} {{ fmtTime(a.ackAt) }}</div>
            </td>
            <td>
              <button v-if="a.status === 'OPEN'" class="btn sm green" @click="ack(a)">确认</button>
              <span v-else class="small muted">已确认</span>
            </td>
          </tr>
          <tr v-if="!store.riskAlerts.length"><td colspan="8"><div class="empty">暂无告警</div></td></tr>
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

defineProps({ user: Object })

const openCount = computed(() => store.riskAlerts.filter(a => a.status === 'OPEN').length)
function countOf(type) { return store.riskAlerts.filter(a => a.alertType === type).length }

async function ack(a) {
  try {
    await api.post(`/risk-alerts/${a.id}/ack`, { note: '已核实，纳入风控台账' })
    toast('告警已确认')
    await store.refresh()
  } catch (e) { toast(e.message, 'err') }
}
</script>
