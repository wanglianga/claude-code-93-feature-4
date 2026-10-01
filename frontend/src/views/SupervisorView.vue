<template>
  <div class="page">
    <BranchBanners />
    <div class="card">
      <h3>待复核的公平恢复建议 <span class="hint">维修完成后系统按“故障时权重快照 + 当前窗口负载 + 同类业务服务能力”计算，不自动插回队首</span></h3>
      <div v-for="s in pending" :key="s.id" class="sugg">
        <div class="sugg-head">
          <span class="ticket-no">{{ s.bizOrderNo }}</span>
          <span>建议恢复至 <b>{{ s.suggestedCounterName }}</b> 第 <b>{{ s.suggestedPosition }}</b> 位</span>
          <span class="small muted">{{ fmtTime(s.createdAt) }}</span>
        </div>
        <div class="reason" v-if="reasonOf(s)">
          <div>📌 {{ reasonOf(s).policy }}</div>
          <div>权重快照：<b>{{ reasonOf(s).weightSnapshot }}</b>（故障时已等待 {{ reasonOf(s).waitedSecondsAtFailure }} 秒）；
            前方 {{ reasonOf(s).ahead }} 人</div>
          <div>窗口负载：
            <span v-for="c in reasonOf(s).candidates" :key="c.counterId" class="tag"
                  :class="c.counterId === reasonOf(s).chosenCounterId ? 'green' : 'gray'">
              {{ c.name }} {{ c.active }}/{{ c.capacity }}（{{ c.loadPct }}%）
            </span>
          </div>
          <div>选择理由：{{ reasonOf(s).chosenReason }}</div>
        </div>
        <div class="btn-row">
          <input v-model="comments[s.id]" placeholder="复核意见（可选）" style="flex:1" />
          <button class="btn green" @click="review(s, 'APPROVE')">批准并恢复入队</button>
          <button class="btn red" @click="review(s, 'REJECT')">驳回（转待分配）</button>
        </div>
      </div>
      <div v-if="!pending.length" class="empty">暂无待复核建议</div>
    </div>

    <div class="card">
      <h3>已处理的恢复建议</h3>
      <table>
        <thead>
          <tr><th>业务单号</th><th>建议台位</th><th>建议排位</th><th>状态</th><th>生成时间</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="s in handled" :key="s.id">
            <td><span class="ticket-no">{{ s.bizOrderNo }}</span></td>
            <td>{{ s.suggestedCounterName }}</td>
            <td>第 {{ s.suggestedPosition }} 位</td>
            <td><span class="tag" :class="tag(L.SuggestionStatus, s.status).cls">{{ tag(L.SuggestionStatus, s.status).text }}</span></td>
            <td class="small">{{ fmtTime(s.createdAt) }}</td>
            <td><button class="btn btn-sm gray" @click="$emit('trace', s.bizOrderNo)">追溯</button></td>
          </tr>
          <tr v-if="!handled.length"><td colspan="6"><div class="empty">暂无记录</div></td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive } from 'vue'
import { api } from '../api.js'
import { store } from '../store.js'
import { L, tag, fmtTime } from '../labels.js'
import { toast } from '../toast.js'
import BranchBanners from '../components/BranchBanners.vue'

defineEmits(['trace'])

const comments = reactive({})

const pending = computed(() => store.suggestions.filter(s => s.status === 'PENDING'))
const handled = computed(() => store.suggestions.filter(s => s.status !== 'PENDING'))

function reasonOf(s) {
  try { return JSON.parse(s.reasonJson) } catch { return null }
}

async function review(s, decision) {
  try {
    await api.post('/recovery-suggestions/' + s.id + '/review',
      { decision, comment: comments[s.id] || '' })
    toast(decision === 'APPROVE' ? '已批准，客户按建议排位恢复入队' : '已驳回，客户转入待分配')
    await store.refresh()
  } catch (e) { toast(e.message, 'err') }
}
</script>

<style scoped>
.sugg { border: 1px solid var(--border); border-radius: 8px; padding: 12px 14px; margin-bottom: 12px; }
.sugg-head { display: flex; gap: 14px; align-items: center; margin-bottom: 8px; }
.reason { background: #f6f8fc; border-radius: 6px; padding: 8px 12px; font-size: 13px;
  line-height: 1.9; margin-bottom: 10px; color: var(--text); }
</style>
