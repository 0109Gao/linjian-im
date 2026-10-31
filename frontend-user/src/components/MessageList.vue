<template>
  <div ref="container" class="message-list" @scroll="onScroll">
    <div v-if="hasMore" class="load-more">
      <el-button text size="small" :loading="loadingMore" @click="$emit('load-more')">
        查看更早的消息
      </el-button>
    </div>
    <div v-else-if="messages.length > 0" class="load-more">
      <span class="no-more">已经到最开始了</span>
    </div>

    <template v-for="(msg, index) in messages" :key="msg.clientMsgId || msg.id || index">
      <div v-if="shouldShowTime(messages[index - 1], msg)" class="time-divider">
        {{ formatMessageTime(msg.createdAt) }}
      </div>

      <div class="bubble-row" :class="{ mine: msg.mine }">
        <el-avatar
          v-if="!msg.mine"
          :size="34"
          class="bubble-avatar"
          :style="{ background: avatarColor(senderName(msg)) }"
        >
          {{ avatarText(senderName(msg)) }}
        </el-avatar>

        <div class="bubble-main">
          <div v-if="!msg.mine && showSender" class="sender">{{ senderName(msg) }}</div>
          <div class="bubble" :class="{ failed: msg.status === 'failed' }">
            <span class="text">{{ msg.content }}</span>
          </div>
          <div class="meta">
            <template v-if="msg.status === 'sending'">
              <el-icon class="spin"><Loading /></el-icon>
              <span>发送中</span>
            </template>
            <template v-else-if="msg.status === 'failed'">
              <span class="fail-text" @click="$emit('retry', msg)">
                {{ msg.error || '发送失败' }}，点击重试
              </span>
            </template>
            <template v-else-if="msg.seq !== null && msg.seq !== undefined">
              <span class="seq">#{{ msg.seq }}</span>
            </template>
          </div>
        </div>

        <el-avatar
          v-if="msg.mine"
          :size="34"
          class="bubble-avatar"
          :style="{ background: avatarColor(userStore.displayName) }"
        >
          {{ avatarText(userStore.displayName) }}
        </el-avatar>
      </div>
    </template>

    <div v-if="messages.length === 0" class="empty">
      <el-icon class="empty-icon"><ChatLineRound /></el-icon>
      <p>还没有消息，说点什么吧</p>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useUserStore } from '@/store/user'
import {
  avatarColor,
  avatarText,
  formatMessageTime,
  shouldShowTime
} from '@/utils/format'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  members: { type: Array, default: () => [] },
  sessionType: { type: Number, default: 1 },
  hasMore: { type: Boolean, default: false },
  loadingMore: { type: Boolean, default: false }
})

const emit = defineEmits(['load-more', 'retry'])

const userStore = useUserStore()
const container = ref(null)

const showSender = computed(() => props.sessionType === 2)

const memberMap = computed(() => {
  const map = new Map()
  props.members.forEach((m) => map.set(String(m.userId), m.nickname))
  return map
})

function senderName(msg) {
  const name = memberMap.value.get(String(msg.senderId))
  if (name) {
    return name
  }
  if (msg.mine) {
    return userStore.displayName
  }
  // 群成员列表可能还没加载完，退化成 ID 尾号，总比显示「未知用户」有用
  return `用户${String(msg.senderId).slice(-4)}`
}

/** 距离底部是否足够近。只有近的时候新消息才自动滚动，否则会打断正在看历史的人。 */
function nearBottom() {
  const el = container.value
  if (!el) {
    return true
  }
  return el.scrollHeight - el.scrollTop - el.clientHeight < 120
}

function scrollToBottom(smooth = false) {
  nextTick(() => {
    const el = container.value
    if (!el) {
      return
    }
    if (smooth && typeof el.scrollTo === 'function') {
      el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
    } else {
      el.scrollTop = el.scrollHeight
    }
  })
}

// 新消息到达：原本在底部就跟随，不在底部就不动（用户正在翻历史）
watch(
  () => props.messages.length,
  (next, prev) => {
    if (next > prev && nearBottom()) {
      scrollToBottom(true)
    }
  }
)

// 切换会话：直接跳到底部，不做平滑动画
watch(
  () => props.messages[0]?.sessionId,
  () => scrollToBottom(false)
)

/** 加载更多时的滚动锚点：加载前的高度与位置，加载完成后按差值还原。 */
let anchor = null

function onScroll() {
  const el = container.value
  if (!el || props.loadingMore || !props.hasMore) {
    return
  }
  if (el.scrollTop < 60) {
    // 记录锚点而不是「加载完直接跳到底」：用户是在往上翻历史，
    // 加载完之后视线应该停在他刚才看的那条消息上，而不是被弹回最新消息。
    anchor = { height: el.scrollHeight, top: el.scrollTop }
    emit('load-more')
  }
}

watch(
  () => props.loadingMore,
  (loading, previous) => {
    if (previous && !loading && anchor) {
      nextTick(() => {
        const el = container.value
        if (el) {
          el.scrollTop = anchor.top + (el.scrollHeight - anchor.height)
        }
        anchor = null
      })
    }
  }
)

defineExpose({ scrollToBottom })
</script>

<style scoped>
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px 22px 8px;
  background: #fff;
  display: flex;
  flex-direction: column;
}

.load-more {
  text-align: center;
  padding: 4px 0 10px;
}

.no-more {
  font-size: 11px;
  color: var(--im-text-weak);
}

.time-divider {
  text-align: center;
  font-size: 11px;
  color: var(--im-text-weak);
  margin: 12px 0 10px;
}

.bubble-row {
  display: flex;
  gap: 9px;
  margin-bottom: 14px;
  align-items: flex-start;
}

.bubble-row.mine {
  flex-direction: row-reverse;
}

.bubble-avatar {
  flex: 0 0 auto;
  color: #fff;
  font-size: 13px;
}

.bubble-main {
  max-width: min(62%, 560px);
  display: flex;
  flex-direction: column;
}

.bubble-row.mine .bubble-main {
  align-items: flex-end;
}

.sender {
  font-size: 11px;
  color: var(--im-text-weak);
  margin-bottom: 4px;
  padding-left: 2px;
}

.bubble {
  padding: 9px 13px;
  border-radius: 10px;
  background: #f2f3f5;
  font-size: 14px;
  line-height: 1.55;
  word-break: break-word;
  white-space: pre-wrap;
}

.mine .bubble {
  background: var(--im-primary);
  color: #fff;
}

.bubble.failed {
  outline: 1px solid var(--im-danger);
}

/*
 * 这两个属性的实际归属是上面的 .bubble（white-space 与 word-break 都是可继承属性），
 * 这里显式重复一遍是**防御**，不是修复：
 *
 *   一旦有人重构 .bubble（比如把它拆成 .bubble + .bubble-plain 两个类），
 *   继承链一断，多行消息就会静默折叠成一行、长串就会把气泡顶出容器，
 *   这两种失效都不报错、不掉测试，只能靠人眼发现。
 *
 * 重复写两个属性换来「改 .bubble 不会悄悄破坏换行」，值得。
 */
.text {
  display: block;
  white-space: pre-wrap;
  word-break: break-word;
}

.meta {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: var(--im-text-weak);
  margin-top: 3px;
  padding: 0 2px;
  min-height: 14px;
}

.fail-text {
  color: var(--im-danger);
  cursor: pointer;
}

.seq {
  color: #c2c5cc;
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.empty {
  margin: auto;
  text-align: center;
  color: var(--im-text-weak);
}

.empty-icon {
  font-size: 38px;
  color: #e4e6ea;
  margin-bottom: 8px;
}

.empty p {
  font-size: 13px;
}
</style>
