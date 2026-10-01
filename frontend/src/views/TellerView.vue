<template>
  <div class="page">
    <BranchBanners />
    <div class="grid-2">
      <!-- 我的窗口 -->
      <div class="card" v-for="c in myCounters" :key="c.id">
        <h3>{{ c.name }}
          <span class="tag" :class="tag(L.CounterStatus, c.status).cls">{{ tag(L.CounterStatus, c.status).text }}</span>
        </h3>
        <div class="desc-list">
          <dt>窗口类型</dt><dd>{{ L.CounterType[c.type] }}</dd>
          <dt>今日已办</dt><dd>{{ c.servedCount }} 件</dd>
          <dt>均件时长</dt><dd>{{ Math.round(c.avgServeSeconds / 60) }} 分钟</dd>
          <dt>当前号码</dt><dd>{{ currentTicketNo(c) }}</dd>
        </div>
        <div class="btn-row">
          <button class="btn green" :disabled="c.status !== 'OPEN'" @click="callNext(c.id)">下一位叫号</button>
          <button class="btn orange" :disabled="c.status === 'TEMP_LEAVE' || c.status === 'FAULT' || c.status === 'OFFLINE'"
                  @click="setStatus(c.id, 'TEMP_LEAVE')">临时离岗</button>
          <button class="btn" :disabled="c.status === 'OPEN' || c.status === 'FAULT' || c.status === 'OFFLINE'"
                  @click="setStatus(c.id, 'OPEN')">回岗开放</button>
          <button class="btn red" :disabled="c.status === 'OFFLINE'" @click="reportDevice(c.id, 'OFFLINE')">设备离线</button>
          <button class="btn red" :disabled="c.status === 'FAULT'" @click="reportDevice(c.id, 'FAULT')">设备故障</button>
        </div>
        <div v-if="c.status === 'FAULT' || c.status === 'OFFLINE'" class="small muted" style="margin-top:6px">
          ⚠ 设备已离线/故障：在队客户已冻结权重并生成处置单，等待维修工单闭环后恢复开放
        </div>
        <div v-if="c.note" class="small muted" style="margin-top:6px">备注：{{ c.note }}</div>
      </div>

      <!-- 叫号队列 -->
      <div class="card">
        <h3>待叫号队列 <span class="hint">爱心优先号码置顶，点击行查看详情/办理</span></h3>
        <TicketTable :tickets="callQueue" @open="openId = $event" />
      </div>
    </div>

    <div class="card">
      <h3>我正在办理 / 我窗口的挂起单</h3>
      <TicketTable :tickets="myServing" @open="openId = $event" />
    </div>

    <TicketDetailModal v-if="openId" :ticket-id="openId" :user="user"
                       @close="openId = null" @changed="store.refresh()" />
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { api } from '../api.js'
import { store } from '../store.js'
import { L, tag } from '../labels.js'
import { toast } from '../toast.js'
import BranchBanners from '../components/BranchBanners.vue'
import TicketTable from '../components/TicketTable.vue'
import TicketDetailModal from '../components/TicketDetailModal.vue'

const props = defineProps({ user: Object })
const openId = ref(null)

const myCounters = computed(() => store.counters.filter(c => c.tellerId === props.user.id))
const callQueue = computed(() =>
  store.tickets
    .filter(t => t.status === 'ASSIGNED')
    .sort((a, b) => (b.priorityWindow - a.priorityWindow) ||
      new Date(a.assignedAt || a.createdAt) - new Date(b.assignedAt || b.createdAt)))
const myServing = computed(() =>
  store.tickets.filter(t => (t.assigneeId === props.user.id ||
    myCounters.value.some(c => c.id === t.counterId)) &&
    ['SERVING', 'PENDING'].includes(t.status)))

function currentTicketNo(c) {
  const t = store.tickets.find(x => x.id === c.currentTicketId)
  return t ? t.ticketNo : '—'
}

async function callNext(counterId) {
  try {
    const r = await api.post('/counters/' + counterId + '/call-next', {})
    toast('请 ' + r.ticketNo + ' 号到' + (store.counters.find(c => c.id === counterId)?.name || '窗口'))
    openId.value = r.id
    await store.refresh()
  } catch (e) { toast(e.message, 'err') }
}

async function setStatus(counterId, status) {
  const notes = { TEMP_LEAVE: '柜员临时离岗，预计10分钟', OPEN: '柜员回岗' }
  try {
    await api.post('/counters/' + counterId + '/status', { status, note: notes[status] })
    toast('窗口状态已更新：' + tag(L.CounterStatus, status).text)
    await store.refresh()
  } catch (e) { toast(e.message, 'err') }
}

// 设备离线/故障：走设备状态上报流程（幂等键防重复提交与状态抖动），自动生成处置单与维修单
async function reportDevice(counterId, status) {
  try {
    const r = await api.post('/devices/' + counterId + '/status', {
      status,
      reportKey: crypto.randomUUID(),
      note: status === 'OFFLINE' ? '柜台上报设备离线' : '柜台上报设备故障'
    })
    const resultText = { APPLIED: '已生效', DUPLICATE: '重复上报已忽略', NOOP: '状态未变化' }
    toast(`设备状态上报${resultText[r.result] || ''}：在队客户已冻结排队权重并生成处置单`)
    await store.refresh()
  } catch (e) { toast(e.message, 'err') }
}
</script>
