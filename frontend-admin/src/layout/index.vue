<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">林</div>
        <div class="brand-text">
          <strong>林间 IM</strong>
          <span>管理后台</span>
        </div>
      </div>

      <nav class="menu">
        <router-link
          v-for="item in menus"
          :key="item.path"
          :to="item.path"
          class="menu-item"
          active-class="active"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </router-link>
      </nav>

      <div class="sidebar-footer">
        <span>v1.0 · 单机部署</span>
      </div>
    </aside>

    <section class="main">
      <header class="topbar">
        <div class="crumb">{{ currentTitle }}</div>
        <div class="right">
          <el-tag size="small" type="success" effect="plain">管理员</el-tag>
          <span class="user-name">{{ userStore.displayName }}</span>
          <el-button text size="small" @click="handleLogout">退出</el-button>
        </div>
      </header>

      <main class="content">
        <router-view />
      </main>
    </section>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const menus = [
  { path: '/dashboard', label: '概览', icon: 'DataBoard' },
  { path: '/users', label: '用户管理', icon: 'User' },
  { path: '/sessions', label: '消息审计', icon: 'ChatLineSquare' },
  { path: '/online', label: '在线监控', icon: 'Connection' },
  { path: '/logs', label: '操作日志', icon: 'Tickets' }
]

const currentTitle = computed(() => route.meta.title || '')

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定退出登录吗？', '退出', {
      confirmButtonText: '退出',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  userStore.clear()
  router.replace('/login')
}
</script>

<style scoped>
.layout {
  height: 100%;
  display: flex;
}

.sidebar {
  width: 208px;
  flex: 0 0 208px;
  background: var(--ad-sidebar);
  color: #c9d1db;
  display: flex;
  flex-direction: column;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 16px;
}

.brand-mark {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: var(--ad-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}

.brand-text strong {
  color: #fff;
  font-size: 14px;
}

.brand-text span {
  font-size: 11px;
  color: #7b8798;
}

.menu {
  flex: 1;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13.5px;
  color: #c9d1db;
  transition: all 0.15s;
}

.menu-item:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}

.menu-item.active {
  background: var(--ad-primary);
  color: #fff;
  font-weight: 500;
}

.sidebar-footer {
  padding: 14px 16px;
  font-size: 11px;
  color: #66707e;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  height: 56px;
  flex: 0 0 56px;
  background: #fff;
  border-bottom: 1px solid var(--ad-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}

.crumb {
  font-size: 15px;
  font-weight: 600;
}

.right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.user-name {
  font-size: 13px;
  color: var(--ad-text-sub);
}

.content {
  flex: 1;
  /* 两个方向都要能滚：表格最小宽度约 1000px（七列），窗口窄于这个宽度时
     只允许纵向滚动会把「角色 / 状态 / 操作」直接裁掉且滚不到，看起来像功能缺失。 */
  overflow: auto;
}
</style>
