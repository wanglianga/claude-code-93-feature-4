<template>
  <div class="page">
    <BranchBanners />

    <!-- 链完整性 -->
    <div class="banner" :class="chain.valid ? 'green' : 'red'" v-if="chain">
      处置单哈希链：共 {{ chain.total }} 张，
      <b>{{ chain.valid ? '校验通过，无篡改' : '校验失败！被篡改单号：' + (chain.brokenOrders || []).join('、') }}</b>
      <button class="btn btn-sm gray" style="margin-left:10px" @click="verifyChain">重新校验</button>
    </div>

    <!-- 待处置 -->
    <div class="card">
      <h3>待处置的故障单据 <span class="hint">设备离线/转台失败已生成不可篡改处置单，请选择处置方案（重复点击/网络重试不会产生双重效果）</span></h3>
      <table>
        <thead>
          <tr>
            <th>处置单号</th><th>业务单号</th><th>客户</th><th>业务类型</th><th>优先级分</th>
            <th>原台位</th><th>已等待</th><th>权重快照</th><th>失败原因</th><th>票据状态</th><th>处置方案</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="o in openOrders" :key="o.id">
            <td class="mono">{{ o.orderNo }}</td>
            <td><span class="ticket-no">{{ o.bizOrderNo }}</span></td>
            <td>{{ o.customerName }}</td>
            <td>{{ L.BusinessType[o.businessType] }}</td>
            <td>{{ o.priorityScore }}</td>
            <td>{{ o.originalCounterName || '—' }}</td>
            <td>{{ waitText(o.waitedSeconds) }}</td>
            <td class="mono small">{{ o.weightSnapshot }}</td>
            <td class="small">
              <span class="tag red">{{ L.FailureType[o.failureType] }}</span>
              <div class="muted">{{ o.failureReason }}</div>
            </td>
            <td><span class="tag" :class="tag(L.TicketStatus, ticketOf(o)?.status).cls">
              {{ tag(L.TicketStatus, ticketOf(o)?.status).text }}</span></td>
            <td class="pill-actions" style="min-width:280px">
              <div class="act-row">
                <select v-model="form[o.id].targetSame" class="sel">
                  <option :value="null">选择同类台位…</option>
                  <option v-for="c in sameTypeCounters(o)" :key="c.id" :value="c.id">
                    {{ c.name }}（负载 {{ loadOf(c) }}）
                  </option>
                </select>
                <button class="btn btn-sm" :disabled="!form[o.id].targetSame"
                        @click="act(o, 'migrate')">保留权重迁移</button>
              </div>
              <div class="act-row">
                <select v-model="form[o.id].targetManual" class="sel">
                  <option :value="null">选择人工窗口…</option>
                  <option v-for="c in manualCounters" :key="c.id" :value="c.id">
                    {{ c.name }}（负载 {{ loadOf(c) }}）
                  </option>
                </select>
                <button class="btn btn-sm orange" :disabled="!form[o.id].targetManual"
                        @click="act(o, 'manual')">转人工窗口</button>
                <button class="btn btn-sm purple" @click="act(o, 'defer')">客户暂缓</button>
              </div>
            </td>
          </tr>
          <tr v-if="!openOrders.length"><td colspan="11"><div class="empty">暂无待处置单据</div></td></tr>
        </tbody>
      </table>
    </div>

    <!-- 待分配（迁移回滚） -->
    <div class="card">
      <h3>待分配（迁移回滚） <span class="hint">目标台位被占用时自动回滚到此状态，排队权重与等待时长完整保留，可重新选择台位</span></h3>
      <TicketTable :tickets="pendingAssign" @open="openId = $event" />
    </div>

    <!-- 处置记录 -->
    <div class="card">
      <h3>处置单台账 <span class="hint">内容不可篡改，点击可查看统一追溯</span></h3>
      <table>
        <thead>
          <tr>
            <th>处置单号</th><th>业务单号</th><th>客户</th><th>失败原因</th><th>状态</th><th>生成时间</th><th>哈希</th><th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="o in store.dispositions" :key="o.id">
            <td class="mono">{{ o.orderNo }}</td>
            <td><span class="ticket-no">{{ o.bizOrderNo }}</span></td>
            <td>{{ o.customerName }}</td>
            <td class="small">{{ o.failureReason }}</td>
            <td><span class="tag" :class="tag(L.DispositionStatus, o.status).cls">{{ tag(L.DispositionStatus, o.status).text }}</span></td>
            <td class="small">{{ fmtTime(o.createdAt) }}</td>
            <td class="mono small muted" :title="o.hash">{{ (o.hash || '').substring(0, 12) }}…</td>
            <td><button class="btn btn-sm gray" @click="$emit('trace', o.bizOrderNo)">追溯</button></td>
          </tr>
          <tr v-if="!store.dispositions.length"><td colspan="8"><div class="empty">暂无处置单</div></td></tr>
        </tbody>
      </table>
    </div>

    <TicketDetailModal v-if="openId" :ticket-id="openId" :user="user"
                       @close="openId = null" @changed="store.refresh()" />
  </div>
</template>

<script setup>
import { computed, reactive, ref, watchEffect } from 'vue'
import { api } from '../api.js'
import { store } from '../store.js'
import { L, tag, fmtTime, waitText } from '../labels.js'
import { toast } from '../toast.js'
import BranchBanners from '../components/BranchBanners.vue'
import TicketTable from '../components/TicketTable.vue'
import TicketDetailModal from '../components/TicketDetailModal.vue'

const props = defineProps({ user: Object })
defineEmits(['trace'])

const openId = ref(null)
const chain = ref(null)
// 每张处置单的处置表单状态；idempotencyKey 在首次操作时生成并保持，重试复用同一键（防重复提交）
const form = reactive({})

const openOrders = computed(() => store.dispositions.filter(o => o.status === 'OPEN'))
const pendingAssign = computed(() => store.tickets.filter(t => t.status === 'PENDING_ASSIGN'))
const manualCounters = computed(() =>
  store.counters.filter(c => c.status === 'OPEN' && c.type !== 'SELF_SERVICE'))

function ensureForm(o) {
  if (!form[o.id]) form[o.id] = { targetSame: null, targetManual: null, keys: {} }
  return form[o.id]
}
// 渲染前为每张待处置单初始化表单状态
watchEffect(() => { for (const o of openOrders.value) ensureForm(o) })
function ticketOf(o) {
  return store.tickets.find(t => t.id === o.ticketId)
}
function sameTypeCounters(o) {
  const t = ticketOf(o)
  return store.counters.filter(c => {
    if (c.status !== 'OPEN') return false
    if (t?.channel === 'SELF_MACHINE') return c.type === 'SELF_SERVICE'
    return c.type !== 'SELF_SERVICE'
  })
}
function loadOf(c) {
  const n = store.tickets.filter(t => t.counterId === c.id && ['ASSIGNED', 'SERVING'].includes(t.status)).length
  return n + '/' + (c.capacity || '∞')
}

async function act(o, kind) {
  const f = ensureForm(o)
  // 同一处置动作复用同一幂等键：网络重试/重复点击安全
  f.keys[kind] = f.keys[kind] || crypto.randomUUID()
  try {
    let r
    if (kind === 'migrate') {
      r = await api.post(`/dispositions/${o.id}/migrate`, { targetCounterId: f.targetSame, idempotencyKey: f.keys[kind] })
    } else if (kind === 'manual') {
      r = await api.post(`/dispositions/${o.id}/to-manual`, { targetCounterId: f.targetManual, idempotencyKey: f.keys[kind] })
    } else {
      r = await api.post(`/dispositions/${o.id}/defer`, { note: '客户要求暂缓', idempotencyKey: f.keys[kind] })
    }
    if (r.result === 'ROLLED_BACK') {
      toast('目标台位容量已满，已回滚到待分配状态（排队权益完整保留）', 'err')
    } else {
      toast('处置完成：' + (L.DispositionActionType[r.actionType] || ''))
      delete form[o.id]
    }
    await store.refresh()
  } catch (e) { toast(e.message, 'err') }
}

async function verifyChain() {
  chain.value = await api.get('/dispositions-verify-chain')
}
verifyChain()
</script>

<style scoped>
.act-row { display: flex; gap: 6px; margin-bottom: 6px; align-items: center; }
.sel { max-width: 170px; padding: 5px 8px; font-size: 12.5px; }
.mono { font-family: ui-monospace, monospace; }
</style>
