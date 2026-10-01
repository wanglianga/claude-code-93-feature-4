<template>
  <div v-if="!user">
    <LoginView @logged-in="onLogin" />
  </div>
  <template v-else>
    <header class="app-header">
      <div class="logo"><span>CCB</span> 网点排队叫号与适老服务预约平台</div>
      <div class="spacer"></div>
      <div class="who">
        <span class="role-tag">{{ L.Role[user.role] }}</span>
        {{ user.displayName }}<span v-if="user.skill" class="muted" style="opacity:.75"> · {{ user.skill }}</span>
      </div>
      <button class="btn-logout" @click="logout">退出</button>
    </header>

    <nav class="tabs">
      <button v-for="t in visibleTabs" :key="t.key"
              :class="{ active: tab === t.key }" @click="tab = t.key">{{ t.label }}</button>
    </nav>

    <component :is="currentView" :user="user" :initial-biz-no="traceBizNo" @trace="goTrace" />

    <ToastHost />
  </template>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { getUser, clearAuth } from './api.js'
import { L } from './labels.js'
import { startPolling, stopPolling } from './store.js'
import ToastHost from './ToastHost.vue'
import LoginView from './views/LoginView.vue'
import CustomerView from './views/CustomerView.vue'
import ManagerView from './views/ManagerView.vue'
import TellerView from './views/TellerView.vue'
import AdvisorView from './views/AdvisorView.vue'
import SecurityView from './views/SecurityView.vue'
import ServiceView from './views/ServiceView.vue'
import ArchiveView from './views/ArchiveView.vue'
import DispositionView from './views/DispositionView.vue'
import MaintenanceView from './views/MaintenanceView.vue'
import RiskView from './views/RiskView.vue'
import SupervisorView from './views/SupervisorView.vue'
import TraceView from './views/TraceView.vue'

const user = ref(getUser())
const tab = ref('')
const traceBizNo = ref('')

const STAFF = ['MANAGER', 'TELLER', 'ADVISOR', 'SECURITY', 'SERVICE', 'MAINTENANCE', 'RISK', 'SUPERVISOR']
const TABS = [
  { key: 'queue', label: '取号/预约', roles: ['CUSTOMER'], comp: CustomerView },
  { key: 'manager', label: '分流调度', roles: ['MANAGER'], comp: ManagerView },
  { key: 'disposition', label: '前台处置', roles: ['MANAGER'], comp: DispositionView },
  { key: 'teller', label: '叫号办理', roles: ['TELLER'], comp: TellerView },
  { key: 'advisor', label: '理财室', roles: ['ADVISOR'], comp: AdvisorView },
  { key: 'security', label: '反诈/安保', roles: ['SECURITY'], comp: SecurityView },
  { key: 'service', label: '投诉/回访', roles: ['SERVICE'], comp: ServiceView },
  { key: 'maintenance', label: '设备维修', roles: ['MAINTENANCE'], comp: MaintenanceView },
  { key: 'risk', label: '风控告警', roles: ['RISK'], comp: RiskView },
  { key: 'review', label: '主管复核', roles: ['SUPERVISOR'], comp: SupervisorView },
  { key: 'trace', label: '统一查询', roles: STAFF, comp: TraceView },
  { key: 'archive', label: '运营档案', roles: ['MANAGER', 'SERVICE', 'SECURITY', 'ADVISOR', 'TELLER'], comp: ArchiveView }
]

const visibleTabs = computed(() => TABS.filter(t => t.roles.includes(user.value?.role)))
const currentTab = computed(() => visibleTabs.value.find(t => t.key === tab.value) || visibleTabs.value[0])
const currentView = computed(() => currentTab.value.comp)

watch(user, (u) => {
  if (u) { tab.value = ''; startPolling() } else { stopPolling() }
}, { immediate: true })

function onLogin() { user.value = getUser() }
function logout() { clearAuth(); user.value = null }
function goTrace(bizNo) {
  traceBizNo.value = bizNo
  tab.value = 'trace'
}
</script>
