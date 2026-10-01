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

    <component :is="currentView" :user="user" />
  </template>

  <ToastHost />
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
import FrontDeskView from './views/FrontDeskView.vue'
import RepairView from './views/RepairView.vue'
import RiskView from './views/RiskView.vue'
import RecoveryView from './views/RecoveryView.vue'

const user = ref(getUser())
const tab = ref('')

const TABS = [
  { key: 'queue', label: '取号/预约', roles: ['CUSTOMER'], comp: CustomerView },
  { key: 'manager', label: '分流调度', roles: ['MANAGER'], comp: ManagerView },
  { key: 'recovery', label: '恢复复核', roles: ['MANAGER'], comp: RecoveryView },
  { key: 'frontdesk', label: '前台处置', roles: ['FRONT_DESK'], comp: FrontDeskView },
  { key: 'teller', label: '叫号办理', roles: ['TELLER'], comp: TellerView },
  { key: 'advisor', label: '理财室', roles: ['ADVISOR'], comp: AdvisorView },
  { key: 'repair', label: '设备维修', roles: ['REPAIR'], comp: RepairView },
  { key: 'risk', label: '风控告警', roles: ['RISK'], comp: RiskView },
  { key: 'security', label: '反诈/安保', roles: ['SECURITY'], comp: SecurityView },
  { key: 'service', label: '投诉/回访', roles: ['SERVICE'], comp: ServiceView },
  { key: 'archive', label: '运营档案', roles: ['MANAGER', 'SERVICE', 'SECURITY', 'ADVISOR', 'TELLER', 'FRONT_DESK', 'REPAIR', 'RISK'], comp: ArchiveView }
]

const visibleTabs = computed(() => TABS.filter(t => t.roles.includes(user.value?.role)))
const currentTab = computed(() => visibleTabs.value.find(t => t.key === tab.value) || visibleTabs.value[0])
const currentView = computed(() => currentTab.value.comp)

watch(user, (u) => {
  if (u) { tab.value = ''; startPolling() } else { stopPolling() }
}, { immediate: true })

function onLogin() { user.value = getUser() }
function logout() { clearAuth(); user.value = null }
</script>
