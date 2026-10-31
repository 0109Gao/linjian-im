<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <div class="brand-mark">林</div>
        <div>
          <h1>管理后台</h1>
          <p>林间 IM · 用户 / 会话 / 消息审计</p>
        </div>
      </div>

      <el-form :model="form" label-position="top" @submit.prevent="submit">
        <el-form-item label="管理员账号">
          <el-input v-model="form.username" placeholder="用户名" @keyup.enter="submit" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="密码"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" class="submit" :loading="loading" @click="submit">
          登录
        </el-button>
      </el-form>

      <div class="hint">
        演示账号 <code>admin</code> / <code>123456</code>（需先执行
        <code>sql/migrate-w9.sql</code> 授予管理员角色）
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '@/api'
import { useUserStore } from '@/store/user'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function submit() {
  if (!form.username.trim() || !form.password) {
    ElMessage.warning('请填写账号与密码')
    return
  }
  loading.value = true
  try {
    const res = await login({ username: form.username.trim(), password: form.password })
    // 前端这一步只是体验优化：普通用户即使绕过这里，后端 /api/admin/**
    // 也会返回 403。真正的边界在服务端。
    if (Number(res.data.role) !== 1) {
      ElMessage.error('该账号没有管理员权限')
      return
    }
    userStore.setLogin(res.data)
    ElMessage.success('登录成功')
    router.replace('/dashboard')
  } catch (e) {
    // 拦截器已提示
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
  background: #1f2733;
}

.login-card {
  width: 360px;
  padding: 32px;
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.28);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.brand-mark {
  width: 42px;
  height: 42px;
  border-radius: 11px;
  background: var(--ad-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 21px;
  font-weight: 700;
}

h1 {
  font-size: 18px;
  font-weight: 700;
}

.brand p {
  margin-top: 3px;
  font-size: 12px;
  color: var(--ad-text-weak);
}

.submit {
  width: 100%;
  margin-top: 4px;
}

.hint {
  margin-top: 20px;
  padding-top: 14px;
  border-top: 1px dashed var(--ad-border);
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ad-text-weak);
}

code {
  background: #f2f3f5;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 11px;
}
</style>
