<template>
  <aside class="session-list">
    <header class="panel-head">
      <div class="me">
        <el-avatar :size="34" :style="{ background: avatarColor(userStore.displayName) }">
          {{ avatarText(userStore.displayName) }}
        </el-avatar>
        <div class="me-info">
          <div class="me-name">{{ userStore.displayName }}</div>
          <div class="me-status" :class="statusClass">
            <i class="dot" />{{ statusText }}
          </div>
        </div>
      </div>

      <div class="actions">
        <el-tooltip content="发起聊天" placement="bottom">
          <el-button text circle @click="$emit('new-chat')">
            <el-icon><EditPen /></el-icon>
          </el-button>
        </el-tooltip>
        <el-tooltip content="建群" placement="bottom">
          <el-button text circle @click="$emit('new-group')">
            <el-icon><Plus /></el-icon>
          </el-button>
        </el-tooltip>
        <el-tooltip content="退出登录" placement="bottom">
          <el-button text circle @click="$emit('logout')">
            <el-icon><SwitchButton /></el-icon>
          </el-button>
        </el-tooltip>
      </div>
    </header>

    <div class="search">
      <el-input v-model="keyword" placeholder="搜索会话" clearable size="default">
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
    </div>

    <div class="list">
      <div v-if="filtered.length === 0" class="empty">
        <el-icon class="empty-icon"><ChatDotRound /></el-icon>
        <p>{{ sessions.length === 0 ? '还没有会话' : '没有匹配的会话' }}</p>
        <el-button v-if="sessions.length === 0" type="primary" text @click="$emit('new-chat')">
          找人聊一句
        </el-button>
      </div>

      <div
        v-for="item in filtered"
        :key="item.sessionId"
        class="item"
        :class="{ active: String(item.sessionId) === activeId }"
        @click="$emit('select', item.sessionId)"
      >
        <el-avatar :size="40" :style="{ background: avatarColor(title(item)) }">
          {{ avatarText(title(item)) }}
        </el-avatar>

        <div class="item-body">
          <div class="row">
            <span class="title">
              <span v-if="isGroup(item)" class="group-badge">
                <el-icon><UserFilled /></el-icon>
              </span>
              {{ title(item) }}
            </span>
            <span class="time">{{ formatListTime(item.lastMsgAt) }}</span>
          </div>
          <div class="row">
            <span class="preview">{{ item.lastMsgPreview || '暂无消息' }}</span>
            <span v-if="item.unreadCount > 0" class="badge">
              {{ item.unreadCount > 99 ? '99+' : item.unreadCount }}
            </span>
          </div>
        </div>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useUserStore } from '@/store/user'
import { avatarColor, avatarText, formatListTime } from '@/utils/format'

const props = defineProps({
  sessions: { type: Array, default: () => [] },
  activeId: { type: String, default: null },
  connection: { type: String, default: 'idle' }
})

defineEmits(['select', 'new-chat', 'new-group', 'logout'])

const userStore = useUserStore()
const keyword = ref('')

function isGroup(item) {
  return Number(item.sessionType) === 2
}

function title(item) {
  if (isGroup(item)) {
    return item.name || '未命名群聊'
  }
  return item.peerNickname || '未知用户'
}

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) {
    return props.sessions
  }
  return props.sessions.filter((s) => {
    const text = `${title(s)} ${s.lastMsgPreview || ''}`.toLowerCase()
    return text.includes(q)
  })
})

const statusText = computed(() => {
  switch (props.connection) {
    case 'connected':
      return '在线'
    case 'connecting':
      return '连接中…'
    case 'reconnecting':
      return '正在重连…'
    default:
      return '未连接'
  }
})

const statusClass = computed(() => ({ online: props.connection === 'connected' }))
</script>

<style scoped>
.session-list {
  width: 296px;
  flex: 0 0 296px;
  height: 100%;
  background: #fafbfc;
  border-right: 1px solid var(--im-border);
  display: flex;
  flex-direction: column;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 12px 10px;
}

.me {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.me-info {
  min-width: 0;
}

.me-name {
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 110px;
}

.me-status {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--im-text-weak);
  margin-top: 2px;
}

.me-status .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #c9ccd1;
}

.me-status.online .dot {
  background: var(--im-success);
}

.actions {
  display: flex;
  gap: 2px;
}

.search {
  padding: 0 12px 10px;
}

.list {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px 12px;
}

.item {
  display: flex;
  gap: 10px;
  padding: 10px;
  border-radius: var(--im-radius);
  cursor: pointer;
  transition: background 0.12s;
}

.item:hover {
  background: #f0f2f5;
}

.item.active {
  background: var(--im-primary-light);
}

.item-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 4px;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.title {
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: flex;
  align-items: center;
  gap: 4px;
}

.group-badge {
  color: var(--im-text-weak);
  font-size: 12px;
  display: inline-flex;
}

.time {
  font-size: 11px;
  color: var(--im-text-weak);
  flex: 0 0 auto;
}

.preview {
  font-size: 12px;
  color: var(--im-text-weak);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.badge {
  flex: 0 0 auto;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--im-danger);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}

.empty {
  padding: 60px 20px;
  text-align: center;
  color: var(--im-text-weak);
}

.empty-icon {
  font-size: 34px;
  margin-bottom: 10px;
  color: #d3d6db;
}

.empty p {
  font-size: 13px;
  margin-bottom: 6px;
}
</style>
