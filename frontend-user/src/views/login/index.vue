<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <div class="brand-mark">林</div>
        <div>
          <h1>林间 IM</h1>
          <p>Spring WebSocket · STOMP · 单机即时通讯</p>
        </div>
      </div>

      <el-tabs v-model="mode" class="tabs">
        <el-tab-pane label="登录" name="login" />
        <el-tab-pane label="注册" name="register" />
      </el-tabs>

      <el-form :model="form" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="4-20 位字母数字下划线" maxlength="20" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="至少 6 位"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-form-item v-if="mode === 'register'" label="昵称（可选）">
          <el-input v-model="form.nickname" placeholder="不填则用用户名" maxlength="20" />
        </el-form-item>

        <el-button type="primary" class="submit" :loading="loading" @click="submit">
          {{ mode === 'login' ? '登录' : '注册并登录' }}
        </el-button>
      </el-form>

      <div class="hint">
        <p>演示账号（密码均为 123456）</p>
        <div class="accounts">
          <el-tag v-for="a in demoAccounts" :key="a.username" class="account" @click="fill(a)">
            {{ a.username }} · {{ a.label }}
          </el-tag>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login, register } from '@/api'
import { useUserStore } from '@/store/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const mode = ref('login')
const loading = ref(false)
const form = reactive({ username: '', password: '', nickname: '' })

const demoAccounts = [
  { username: 'linjian', password: '123456', label: '普通用户' },
  { username: 'demo', password: '123456', label: '普通用户' },
  { username: 'admin', password: '123456', label: '管理员' }
]

function fill(account) {
  mode.value = 'login'
  form.username = account.username
  form.password = account.password
}

async function submit() {
  if (!form.username.trim() || !form.password) {
    ElMessage.warning('请填写用户名与密码')
    return
  }
  loading.value = true
  try {
    const payload = mode.value === 'login'
      ? { username: form.username.trim(), password: form.password }
      : {
          username: form.username.trim(),
          password: form.password,
          nickname: form.nickname.trim() || undefined
        }
    const res = mode.value === 'login' ? await login(payload) : await register(payload)
    userStore.setLogin(res.data)
    ElMessage.success(mode.value === 'login' ? '登录成功' : '注册成功')
    const redirect = route.query.redirect
    router.replace(typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/')
  } catch (e) {
    // 错误提示已由拦截器统一处理，这里只需要恢复按钮状态
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(circle at 15% 20%, #e3ecff 0, transparent 45%),
    radial-gradient(circle at 85% 80%, #eaf7ef 0, transparent 45%),
    #f7f8fa;
}

.login-card {
  width: 380px;
  padding: 32px;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 18px 48px rgba(31, 35, 41, 0.08);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 22px;
}

.brand-mark {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: var(--im-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  font-weight: 700;
}

h1 {
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 1px;
}

.brand p {
  margin-top: 4px;
  font-size: 12px;
  color: var(--im-text-weak);
}

.tabs {
  margin-bottom: 6px;
}

.submit {
  width: 100%;
  margin-top: 4px;
}

.hint {
  margin-top: 22px;
  padding-top: 16px;
  border-top: 1px dashed var(--im-border);
  font-size: 12px;
  color: var(--im-text-weak);
}

.accounts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.account {
  cursor: pointer;
}
</style>
