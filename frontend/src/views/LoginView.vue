<template>
  <div class="login-wrap">
    <div class="login-card">
      <h1>网点排队叫号与适老服务预约平台</h1>
      <div class="sub">中国建设银行 · 智慧网点综合服务系统（演示环境）</div>
      <form @submit.prevent="login">
        <div class="form-grid" style="grid-template-columns:1fr">
          <label class="field"><b>用户名</b>
            <input v-model="form.username" placeholder="如 manager / teller01" autofocus />
          </label>
          <label class="field"><b>密码</b>
            <input v-model="form.password" type="password" placeholder="统一演示密码 ccb@123456" />
          </label>
        </div>
        <div class="btn-row">
          <button class="btn" type="submit" :disabled="loading" style="width:100%">
            {{ loading ? '登录中…' : '登 录' }}
          </button>
        </div>
        <div v-if="error" class="banner red" style="margin-top:14px">{{ error }}</div>
      </form>

      <div class="login-accounts">
        <div style="font-weight:600;color:#1f2a3d;margin-bottom:4px">演示账号（点击自动填充，密码均为 ccb@123456）：</div>
        <div v-for="a in accounts" :key="a.u" class="role-line" @click="fill(a.u)">
          <b>{{ a.name }}</b> · {{ a.u }} — {{ a.desc }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { api, saveAuth } from '../api.js'
import { toast } from '../toast.js'

const emit = defineEmits(['logged-in'])
const form = reactive({ username: '', password: '' })
const error = ref('')
const loading = ref(false)

const accounts = [
  { u: 'customer01', name: '客户·陈晨', desc: '手机取号/适老预约/进度查询' },
  { u: 'manager', name: '大堂经理·林芳', desc: '分流调度、异常协调、恢复复核（主管）' },
  { u: 'frontdesk01', name: '前台·王芸', desc: '异常处置单：迁移/转人工/暂缓、统一查询' },
  { u: 'teller01', name: '柜员·周敏', desc: '1号综合柜台叫号办理' },
  { u: 'teller02', name: '柜员·吴涛', desc: '2号现金柜台（大额现金/挂失）' },
  { u: 'teller03', name: '柜员·郑洁', desc: '爱心优先窗口（老人/手语）' },
  { u: 'advisor01', name: '理财经理·孙睿', desc: '理财室咨询、风险双录' },
  { u: 'repair01', name: '设备维修·赵工', desc: '设备状态上报、维修闭环、恢复建议' },
  { u: 'risk01', name: '风控·刘洋', desc: '重复取号/频繁转台/插队告警确认' },
  { u: 'security01', name: '安保·马强', desc: '反诈核验、无障碍通道、维序' },
  { u: 'service01', name: '客服·何丽', desc: '投诉受理、回访、远程客服' }
]

function fill(u) { form.username = u; form.password = 'ccb@123456'; error.value = '' }

async function login() {
  if (!form.username || !form.password) { error.value = '请输入用户名和密码'; return }
  loading.value = true
  error.value = ''
  try {
    const res = await api.post('/auth/login', form)
    saveAuth(res.token, res)
    toast('欢迎，' + res.displayName)
    emit('logged-in', res)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>
