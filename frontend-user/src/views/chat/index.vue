<template>
  <div class="chat-page">
    <SessionList
      :sessions="chat.sessions"
      :active-id="chat.activeId"
      :connection="chat.connection"
      @select="onSelect"
      @new-chat="openNewChat"
      @new-group="openNewGroup"
      @logout="handleLogout"
    />

    <section class="chat-pane">
      <template v-if="activeSession">
        <header class="chat-head">
          <div class="title-wrap">
            <div class="title">
              {{ title }}
              <el-tag v-if="isGroup" size="small" type="info" effect="plain">
                {{ activeSession.memberCount }} 人
              </el-tag>
            </div>
            <div class="sub">
              <template v-if="isGroup">
                <span v-for="(m, i) in membersPreview" :key="m.userId">
                  {{ m.nickname }}<span v-if="i < membersPreview.length - 1">、</span>
                </span>
                <span v-if="chat.members.length > membersPreview.length"> 等</span>
              </template>
              <template v-else>
                <span :class="{ online: activeSession.peerOnline }">
                  {{ activeSession.peerOnline ? '在线' : '离线' }}
                </span>
                <span class="sep">·</span>
                <span>单聊</span>
              </template>
            </div>
          </div>

          <div class="head-actions">
            <el-button v-if="isGroup" text @click="memberDrawer = true">
              <el-icon><User /></el-icon> 成员
            </el-button>
            <el-button v-if="isGroup" text type="danger" @click="handleLeave">退群</el-button>
            <el-tag v-if="!chat.isConnected" size="small" type="warning" effect="dark">
              {{ chat.connection === 'reconnecting' ? '重连中…' : '未连接' }}
            </el-tag>
          </div>
        </header>

        <MessageList
          ref="messageListRef"
          :messages="chat.activeMessages"
          :members="chat.members"
          :session-type="Number(activeSession.sessionType)"
          :has-more="!!chat.hasMore[chat.activeId]"
          :loading-more="loadingMore"
          @load-more="onLoadMore"
          @retry="onRetry"
        />

        <Composer
          :disabled="!chat.isConnected"
          :placeholder="isGroup ? `发消息到「${title}」` : `发消息给 ${title}`"
          @send="onSend"
        />
      </template>

      <div v-else class="placeholder">
        <el-icon class="placeholder-icon"><ChatDotRound /></el-icon>
        <h2>还没有打开任何会话</h2>
        <p>从左侧选一个会话，或者找人聊一句</p>
        <el-button type="primary" @click="openNewChat">发起聊天</el-button>
      </div>
    </section>

    <!-- 发起单聊 -->
    <el-dialog v-model="newChatVisible" title="发起聊天" width="420px" @closed="resetPicker">
      <PeoplePicker ref="pickerRef" v-model="pickedIds" :multiple="false" />
      <template #footer>
        <el-button @click="newChatVisible = false">取消</el-button>
        <el-button type="primary" :disabled="pickedIds.length === 0" @click="confirmNewChat">
          开始聊天
        </el-button>
      </template>
    </el-dialog>

    <!-- 建群 -->
    <el-dialog v-model="newGroupVisible" title="创建群聊" width="460px" @closed="resetPicker">
      <el-form label-position="top">
        <el-form-item label="群名称">
          <el-input v-model="groupName" maxlength="64" show-word-limit placeholder="给这个群起个名字" />
        </el-form-item>
        <el-form-item label="邀请成员">
          <PeoplePicker ref="groupPickerRef" v-model="pickedIds" :multiple="true" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newGroupVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!groupName.trim() || pickedIds.length === 0"
          @click="confirmNewGroup"
        >
          创建（{{ pickedIds.length }} 人）
        </el-button>
      </template>
    </el-dialog>

    <!-- 群成员 -->
    <el-drawer v-model="memberDrawer" :title="`群成员（${chat.members.length}）`" size="320px">
      <div class="drawer-actions">
        <el-button type="primary" plain size="small" @click="openAddMember">邀请成员</el-button>
      </div>
      <div class="member-list">
        <div v-for="m in chat.members" :key="m.userId" class="member">
          <el-avatar :size="30" :style="{ background: avatarColor(m.nickname) }">
            {{ avatarText(m.nickname) }}
          </el-avatar>
          <div class="member-info">
            <span class="member-name">{{ m.nickname }}</span>
            <span v-if="String(m.userId) === userStore.userId" class="self-tag">我</span>
          </div>
          <span class="online-dot" :class="{ online: m.online }" />
        </div>
      </div>
    </el-drawer>

    <!-- 邀请成员 -->
    <el-dialog v-model="addMemberVisible" title="邀请成员" width="420px" @closed="resetPicker">
      <PeoplePicker ref="addPickerRef" v-model="pickedIds" :multiple="true" />
      <template #footer>
        <el-button @click="addMemberVisible = false">取消</el-button>
        <el-button type="primary" :disabled="pickedIds.length === 0" @click="confirmAddMember">
          邀请 {{ pickedIds.length }} 人
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import SessionList from '@/components/SessionList.vue'
import MessageList from '@/components/MessageList.vue'
import Composer from '@/components/Composer.vue'
import PeoplePicker from '@/components/PeoplePicker.vue'
import { useChatStore } from '@/store/chat'
import { useUserStore } from '@/store/user'
import { avatarColor, avatarText } from '@/utils/format'

const router = useRouter()
const chat = useChatStore()
const userStore = useUserStore()

const messageListRef = ref(null)
const pickerRef = ref(null)
const groupPickerRef = ref(null)
const addPickerRef = ref(null)

const newChatVisible = ref(false)
const newGroupVisible = ref(false)
const addMemberVisible = ref(false)
const memberDrawer = ref(false)
const groupName = ref('')
const pickedIds = ref([])
const loadingMore = ref(false)

const activeSession = computed(() => chat.activeSession)
const isGroup = computed(() => Number(activeSession.value?.sessionType) === 2)
const title = computed(() => {
  if (!activeSession.value) {
    return ''
  }
  return isGroup.value
    ? activeSession.value.name || '未命名群聊'
    : activeSession.value.peerNickname || '未知用户'
})
const membersPreview = computed(() => chat.members.slice(0, 3))

onMounted(() => {
  chat.init().then(() => {
    if (chat.sessions.length > 0 && !chat.activeId) {
      onSelect(chat.sessions[0].sessionId)
    }
  })
})

// 切换会话后滚到底部：用户点开会话时想看的是最新消息
watch(
  () => chat.activeId,
  async () => {
    await nextTick()
    messageListRef.value?.scrollToBottom(false)
  }
)

async function onSelect(sessionId) {
  try {
    await chat.select(sessionId)
  } catch (e) {
    ElMessage.error('打开会话失败')
  }
}

async function onLoadMore() {
  if (loadingMore.value) {
    return
  }
  loadingMore.value = true
  try {
    await chat.loadMore(chat.activeId)
  } finally {
    loadingMore.value = false
  }
}

function onSend(text) {
  chat.send(text)
}

function onRetry(message) {
  chat.retry(message)
}

function resetPicker() {
  pickedIds.value = []
  pickerRef.value?.reset?.()
  groupPickerRef.value?.reset?.()
  addPickerRef.value?.reset?.()
}

function openNewChat() {
  pickedIds.value = []
  newChatVisible.value = true
}

function openNewGroup() {
  pickedIds.value = []
  groupName.value = ''
  newGroupVisible.value = true
}

function openAddMember() {
  pickedIds.value = []
  addMemberVisible.value = true
}

async function confirmNewChat() {
  try {
    await chat.openWith(pickedIds.value[0])
    newChatVisible.value = false
  } catch (e) {
    // 拦截器已提示
  }
}

async function confirmNewGroup() {
  try {
    await chat.createGroup(groupName.value.trim(), pickedIds.value)
    newGroupVisible.value = false
    ElMessage.success('群聊已创建')
  } catch (e) {
    // 拦截器已提示
  }
}

async function confirmAddMember() {
  try {
    const added = await chat.addMembers(chat.activeId, pickedIds.value)
    addMemberVisible.value = false
    ElMessage.success(added > 0 ? `已邀请 ${added} 人` : '他们都已经在群里了')
  } catch (e) {
    // 拦截器已提示
  }
}

async function handleLeave() {
  try {
    await ElMessageBox.confirm(
      '退出后将不再收到该群的消息，历史消息也不再可见。确定退出吗？',
      '退出群聊',
      { type: 'warning', confirmButtonText: '退出', cancelButtonText: '再想想' }
    )
  } catch (e) {
    return
  }
  try {
    await chat.leaveGroup(chat.activeId)
    ElMessage.success('已退出群聊')
    memberDrawer.value = false
  } catch (e) {
    // 拦截器已提示
  }
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定退出登录吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  chat.reset()
  userStore.clear()
  router.replace('/login')
}
</script>

<style scoped>
.chat-page {
  height: 100%;
  display: flex;
  background: #fff;
}

.chat-pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
}

.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  border-bottom: 1px solid var(--im-border);
  flex: 0 0 auto;
}

.title-wrap {
  min-width: 0;
}

.title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
}

.sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--im-text-weak);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 520px;
}

.sub .online {
  color: var(--im-success);
}

.sub .sep {
  margin: 0 5px;
  color: #d3d6db;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
}

.placeholder {
  margin: auto;
  text-align: center;
  color: var(--im-text-weak);
}

.placeholder-icon {
  font-size: 46px;
  color: #e4e6ea;
  margin-bottom: 12px;
}

.placeholder h2 {
  font-size: 16px;
  font-weight: 600;
  color: var(--im-text-sub);
  margin-bottom: 6px;
}

.placeholder p {
  font-size: 13px;
  margin-bottom: 16px;
}

.drawer-actions {
  padding-bottom: 10px;
  border-bottom: 1px solid var(--im-border);
  margin-bottom: 8px;
}

.member-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.member {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 6px;
  border-radius: 8px;
}

.member:hover {
  background: #f7f8fa;
}

.member-info {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.member-name {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.self-tag {
  font-size: 10px;
  color: var(--im-text-weak);
  border: 1px solid var(--im-border);
  border-radius: 4px;
  padding: 0 3px;
}

.online-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #d3d6db;
}

.online-dot.online {
  background: var(--im-success);
}
</style>
