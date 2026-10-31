<template>
  <div class="page">
    <div class="card">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="按群名或会话 ID 搜索"
          clearable
          style="width: 280px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button type="primary" @click="search">搜索</el-button>
        <div class="grow" />
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>

      <div class="table-wrap">
        <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="id" label="会话 ID" width="165">
          <template #default="{ row }">
            <span class="mono">{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.sessionType === 2 ? 'warning' : 'info'" effect="plain">
              {{ row.sessionType === 2 ? '群聊' : '单聊' }}
            </el-tag>
          </template>
        </el-table-column>
        <!--
          群名最长 64 个「字符」，而中文/emoji 的宽度是拉丁字母的两倍。
          64 个 emoji 会把这一行撑到半屏高，整张表的节奏全乱。
          show-overflow-tooltip 让超长名字截断成一行，鼠标悬停再看全文。
        -->
        <el-table-column label="名称" width="160" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.name || '—' }}
          </template>
        </el-table-column>
        <el-table-column prop="memberCount" label="成员数" width="90" align="center" />
        <el-table-column prop="lastMsgSeq" label="最后序号" width="100" align="center">
          <template #default="{ row }">{{ row.lastMsgSeq ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="最后消息时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.lastMsgAt) }}</template>
        </el-table-column>
        <el-table-column label="摘要" min-width="180">
          <template #default="{ row }">
            <span class="preview">{{ row.lastMsgPreview || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button type="primary" link @click="openMessages(row)">查看消息</el-button>
          </template>
        </el-table-column>
      </el-table>
      </div>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </div>

    <el-drawer v-model="drawer" :title="drawerTitle" size="560px">
      <div class="drawer-head">
        <span class="drawer-sub">共显示 {{ messages.length }} 条</span>
        <el-button
          v-if="hasMore"
          size="small"
          text
          :loading="loadingMore"
          @click="loadMore"
        >
          加载更早的消息
        </el-button>
      </div>

      <div v-loading="loadingMessages" class="msg-list">
        <div v-if="messages.length === 0 && !loadingMessages" class="empty">这个会话还没有消息</div>
        <div v-for="m in messages" :key="m.id" class="msg">
          <div class="msg-head">
            <span class="sender">发送者 {{ m.senderId }}</span>
            <span class="seq">#{{ m.seq }}</span>
            <span class="time">{{ formatDateTime(m.createdAt) }}</span>
          </div>
          <div class="msg-body">{{ m.content }}</div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { sessionMessages, sessions } from '@/api'
import { formatDateTime } from '@/utils/format'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const loading = ref(false)

const drawer = ref(false)
const drawerSession = ref(null)
const messages = ref([])
const hasMore = ref(false)
const loadingMessages = ref(false)
const loadingMore = ref(false)

const drawerTitle = computed(() => {
  if (!drawerSession.value) {
    return '消息审计'
  }
  const s = drawerSession.value
  const name = s.sessionType === 2 ? (s.name || '未命名群聊') : '单聊'
  return `${name} · ${s.id}`
})

async function load() {
  loading.value = true
  try {
    const res = await sessions({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: size.value
    })
    rows.value = res.data.items || []
    total.value = Number(res.data.total || 0)
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

async function openMessages(row) {
  drawerSession.value = row
  drawer.value = true
  messages.value = []
  hasMore.value = false
  loadingMessages.value = true
  try {
    const res = await sessionMessages(row.id, { limit: 30 })
    messages.value = res.data.items || []
    hasMore.value = !!res.data.hasMore
  } catch (e) {
    ElMessage.error('加载消息失败')
  } finally {
    loadingMessages.value = false
  }
}

async function loadMore() {
  if (!drawerSession.value || messages.value.length === 0) {
    return
  }
  const first = messages.value[0]
  if (!first || first.seq === null) {
    return
  }
  loadingMore.value = true
  try {
    const res = await sessionMessages(drawerSession.value.id, {
      beforeSeq: first.seq,
      limit: 30
    })
    messages.value = [...(res.data.items || []), ...messages.value]
    hasMore.value = !!res.data.hasMore
  } catch (e) {
    ElMessage.error('加载失败')
  } finally {
    loadingMore.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--ad-text-sub);
}

.preview {
  color: var(--ad-text-sub);
  font-size: 12.5px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--ad-border);
  margin-bottom: 10px;
}

.drawer-sub {
  font-size: 12px;
  color: var(--ad-text-weak);
}

.msg-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 120px;
}

.empty {
  padding: 40px 0;
  text-align: center;
  color: var(--ad-text-weak);
  font-size: 13px;
}

.msg {
  padding: 10px 12px;
  background: #f8f9fb;
  border-radius: 8px;
  border: 1px solid #eef0f3;
}

.msg-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
  color: var(--ad-text-weak);
  margin-bottom: 6px;
}

.sender {
  color: var(--ad-text-sub);
}

.seq {
  color: #b6bac0;
}

.time {
  margin-left: auto;
}

.msg-body {
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
