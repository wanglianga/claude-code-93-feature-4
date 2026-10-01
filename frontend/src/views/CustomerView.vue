<template>
  <div class="page">
    <BranchBanners />
    <div class="grid-2">
      <!-- 取号 / 适老预约 -->
      <div class="card">
        <h3>线上取号 / 适老服务预约</h3>
        <div class="form-grid">
          <label class="field"><b>客户姓名</b><input v-model="f.customerName" placeholder="可留空（现场客户）" /></label>
          <label class="field"><b>客户类型</b>
            <select v-model="f.customerType" @change="onElderlyType">
              <option value="NORMAL">普通客户</option>
              <option value="VIP">VIP 客户</option>
              <option value="ELDERLY">老年客户</option>
              <option value="CORPORATE">对公客户</option>
            </select>
          </label>
          <label class="field"><b>预约来源</b>
            <select v-model="f.source">
              <option value="WALK_IN">现场取号</option>
              <option value="MOBILE_APP">手机银行预约</option>
              <option value="PHONE">电话预约</option>
              <option value="HOTLINE_ELDERLY">老年服务热线预约</option>
            </select>
          </label>
          <label class="field"><b>业务类型</b>
            <select v-model="f.businessType">
              <option v-for="(v, k) in L.BusinessType" :key="k" :value="k">{{ v }}</option>
            </select>
          </label>
          <label class="field"><b>风险等级</b>
            <select v-model="f.riskLevel">
              <option value="LOW">低</option><option value="MEDIUM">中</option><option value="HIGH">高</option>
            </select>
          </label>
          <label class="field"><b>预计办理时长（分钟）</b>
            <input type="number" min="1" v-model.number="f.estimatedMinutes" />
          </label>
          <label class="field full"><b>涉及金额（转账/大额现金时填写，元）</b>
            <input type="number" min="0" v-model.number="f.amount" placeholder="≥ 50,000 元将触发反诈核验/库存校验" />
          </label>
        </div>

        <div style="margin-top:12px" class="small muted">特殊需求与适老服务：</div>
        <div class="check-row">
          <label><input type="checkbox" v-model="f.elderly" />老人（60+）</label>
          <label><input type="checkbox" v-model="f.wheelchair" />轮椅·无障碍通道</label>
          <label><input type="checkbox" v-model="f.hearingAssist" />听力引导（助听设备/大字指引）</label>
        </div>
        <div class="check-row" style="margin-top:8px">
          <label><input type="checkbox" v-model="f.escort" />需要陪同引导</label>
          <label><input type="checkbox" v-model="f.priorityWindow" />优先（爱心）窗口</label>
          <label><input type="checkbox" v-model="f.preReview" />到店前材料预审</label>
          <label><input type="checkbox" v-model="f.familyConfirm" />家属远程确认</label>
        </div>
        <div class="check-row" style="margin-top:8px">
          <label><input type="checkbox" v-model="f.urgent" />紧急业务（如紧急挂失/医疗缴费）</label>
          <label><input type="checkbox" v-model="f.identityVerified" />已完成身份核验</label>
        </div>
        <div class="small muted" style="margin-top:4px">紧急且已完成身份核验的业务，风控告警不阻断办理。</div>
        <label class="field full" style="margin-top:8px" v-if="f.familyConfirm">
          <b>家属联系方式（用于远程确认）</b>
          <input v-model="f.familyContact" placeholder="如：女儿 王女士 138****1234" />
        </label>
        <div class="btn-row">
          <button class="btn" :disabled="submitting" @click="submit">{{ submitting ? '提交中…' : '提交取号 / 预约' }}</button>
          <button class="btn gray" @click="reset">重置</button>
        </div>
      </div>

      <!-- 我的排队 -->
      <div class="card">
        <h3>我的排队进度 <span class="hint">可查看分流渠道与协同处理时间线</span></h3>
        <TicketTable :tickets="myTickets" @open="openId = $event">
          <template #actions="{ t }">
            <button v-if="['WAITING','ASSIGNED','PENDING'].includes(t.status)" class="btn btn-sm red"
                    @click.stop="cancel(t.id)">取消</button>
          </template>
        </TicketTable>
      </div>
    </div>

    <TicketDetailModal v-if="openId" :ticket-id="openId" :user="user"
                       @close="openId = null" @changed="store.refresh()" />
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { api, newIdemKey } from '../api.js'
import { store } from '../store.js'
import { L } from '../labels.js'
import { toast } from '../toast.js'
import BranchBanners from '../components/BranchBanners.vue'
import TicketTable from '../components/TicketTable.vue'
import TicketDetailModal from '../components/TicketDetailModal.vue'

const props = defineProps({ user: Object })
const openId = ref(null)
const submitting = ref(false)
let idemKey = newIdemKey()

const f = reactive({
  customerName: '', customerType: 'NORMAL', source: 'MOBILE_APP',
  businessType: 'ACCOUNT_OPEN', riskLevel: 'LOW', estimatedMinutes: 15, amount: null,
  elderly: false, wheelchair: false, hearingAssist: false,
  escort: false, priorityWindow: false, preReview: false,
  familyConfirm: false, familyContact: '', urgent: false, identityVerified: false
})

function onElderlyType() {
  f.elderly = f.customerType === 'ELDERLY'
  if (f.elderly) f.priorityWindow = true
}

const myTickets = computed(() =>
  store.tickets.filter(t => t.customerId === props.user.id ||
    t.customerName === props.user.displayName.replace(/^客户·/, '') ||
    t.customerName.includes('陈晨')))

async function submit() {
  if (submitting.value) return
  submitting.value = true
  try {
    const payload = { ...f, customerId: props.user.id }
    if (!payload.amount) payload.amount = null
    // 幂等提交：双击/重试共用同一幂等键，服务端只创建一张号
    const r = await api.postIdem('/tickets', payload, idemKey)
    toast('取号成功：' + r.ticketNo)
    reset()
    await store.refresh()
  } catch (e) { toast(e.message, 'err') } finally { submitting.value = false }
}

function reset() {
  Object.assign(f, {
    customerName: '', customerType: 'NORMAL', source: 'MOBILE_APP', businessType: 'ACCOUNT_OPEN',
    riskLevel: 'LOW', estimatedMinutes: 15, amount: null,
    elderly: false, wheelchair: false, hearingAssist: false,
    escort: false, priorityWindow: false, preReview: false, familyConfirm: false, familyContact: '',
    urgent: false, identityVerified: false
  })
  idemKey = newIdemKey()
}

async function cancel(id) {
  await api.post('/tickets/' + id + '/cancel', { reason: '客户线上取消' })
  toast('已取消')
  await store.refresh()
}
</script>
