<template>
  <table>
    <thead>
      <tr>
        <th>号码</th><th>客户</th><th>业务</th><th>来源</th><th>风险</th>
        <th>适老/特殊</th><th>材料</th><th>渠道/窗口</th><th>状态</th><th>等待</th><th></th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="t in tickets" :key="t.id" @click="$emit('open', t.id)" style="cursor:pointer">
        <td><span class="ticket-no">{{ t.ticketNo }}</span></td>
        <td>{{ t.customerName }}<div class="small muted">{{ tag(L.CustomerType, t.customerType).text }}</div></td>
        <td>{{ L.BusinessType[t.businessType] }}<span v-if="t.amount" class="small muted"> ¥{{ t.amount }}</span></td>
        <td class="small">{{ L.Source[t.source] }}</td>
        <td><span class="tag" :class="tag(L.RiskLevel, t.riskLevel).cls">{{ tag(L.RiskLevel, t.riskLevel).text }}</span></td>
        <td class="small">
          <span v-if="t.elderly" class="tag orange">老人</span>
          <span v-if="t.wheelchair" class="tag purple">轮椅</span>
          <span v-if="t.hearingAssist" class="tag purple">听力</span>
          <span v-if="t.escort" class="tag blue">陪同</span>
          <span v-if="t.familyConfirm" class="tag blue">家属确认</span>
          <span v-if="t.fraudStatus==='PENDING'" class="tag red">反诈待核</span>
          <span v-if="!hasTag(t)" class="muted">—</span>
        </td>
        <td><span class="tag" :class="tag(L.Material, t.material).cls">{{ tag(L.Material, t.material).text }}</span></td>
        <td class="small">
          {{ t.channel ? L.Channel[t.channel] : '—' }}
          <div class="muted">{{ counterName(t.counterId) }}</div>
        </td>
        <td><span class="tag" :class="tag(L.TicketStatus, t.status).cls">{{ tag(L.TicketStatus, t.status).text }}</span></td>
        <td class="small">{{ waitText(waitOf(t)) }}</td>
        <td class="pill-actions" @click.stop>
          <slot name="actions" :t="t" />
        </td>
      </tr>
      <tr v-if="!tickets.length"><td colspan="11"><div class="empty">暂无记录</div></td></tr>
    </tbody>
  </table>
</template>

<script setup>
import { L, tag, waitText } from '../labels.js'
import { store } from '../store.js'

defineProps({ tickets: { type: Array, default: () => [] } })
defineEmits(['open'])

function hasTag(t) {
  return t.elderly || t.wheelchair || t.hearingAssist || t.escort || t.familyConfirm || t.fraudStatus === 'PENDING'
}
function counterName(id) {
  return store.counters.find(c => c.id === id)?.name || ''
}
function waitOf(t) {
  if (t.waitSeconds != null) return t.waitSeconds
  if (['DONE', 'CANCELLED'].includes(t.status)) return null
  // 与后端口径一致：历史冻结段 + 当前等待段
  const acc = t.accumulatedWaitSeconds || 0
  if (!t.waitStartedAt) return acc
  return acc + Math.max(0, Math.floor((Date.now() - new Date(t.waitStartedAt.replace(' ', 'T')).getTime()) / 1000))
}
</script>
