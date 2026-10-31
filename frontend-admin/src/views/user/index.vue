<template>
  <div class="page">
    <div class="card">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="按用户名或昵称搜索"
          clearable
          style="width: 260px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button type="primary" @click="search">搜索</el-button>
        <div class="grow" />
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>

      <div class="table-wrap">
        <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="id" label="用户 ID" width="165">
          <template #default="{ row }">
            <span class="mono">{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="用户名" width="140" />
        <el-table-column prop="nickname" label="昵称" width="140" />
        <el-table-column label="角色" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.role === 1" size="small" type="warning" effect="plain">管理员</el-tag>
            <span v-else class="muted">普通</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.status === 1" size="small" type="success" effect="plain">正常</el-tag>
            <el-tag v-else size="small" type="danger" effect="plain">已禁用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-tooltip :disabled="!disabledReason(row)" :content="disabledReason(row) || ''">
              <span>
                <el-button
                  v-if="row.status === 1"
                  type="danger"
                  link
                  :disabled="!!disabledReason(row)"
                  @click="toggle(row, 0)"
                >
                  禁用
                </el-button>
                <el-button v-else type="primary" link @click="toggle(row, 1)">启用</el-button>
              </span>
            </el-tooltip>
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

    <div class="tips">
      <p>
        <strong>禁用后会发生什么：</strong>三件事同时发生——下一次 REST 请求立即返回 403
        （错误码 4113）；已建立的 WebSocket 被<b>立刻断开</b>（关闭码 1008，
        客户端据此停止重连并提示原因）；之后的重连在 CONNECT 阶段被拒。
        三条路径缺任何一条，这个功能都是半成品：只做「下次发帧被拒」的话，
        被禁用的人只要不操作就能继续收消息，而界面上显示的是「已禁用」。
      </p>
      <p>
        <strong>为什么不能禁用自己和其他管理员：</strong>这两个限制的目的都是不把后台锁死。
        管理员互相禁用会让所有人进不来，而恢复需要直接改数据库。
      </p>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { updateUserStatus, users } from '@/api'
import { useUserStore } from '@/store/user'
import { formatDateTime } from '@/utils/format'

const userStore = useUserStore()

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const loading = ref(false)

/** 与后端规则保持一致，前端先拦住以减少一次必然失败的请求（后端仍然是最终裁决）。 */
function disabledReason(row) {
  if (String(row.id) === userStore.userId) {
    return '不能禁用自己的账号'
  }
  if (row.role === 1) {
    return '不能修改管理员账号的状态'
  }
  return ''
}

async function load() {
  loading.value = true
  try {
    const res = await users({ keyword: keyword.value.trim() || undefined, page: page.value, size: size.value })
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

async function toggle(row, status) {
  const action = status === 0 ? '禁用' : '启用'
  try {
    await ElMessageBox.confirm(
      status === 0
        ? `禁用后「${row.nickname || row.username}」将无法登录、也无法收发消息。确定吗？`
        : `确定恢复「${row.nickname || row.username}」的使用权限吗？`,
      `${action}账号`,
      { type: status === 0 ? 'warning' : 'info', confirmButtonText: action, cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    const res = await updateUserStatus(row.id, status)
    // 用返回值就地更新这一行，不重查整页：分页时重查会让人以为「跳页了」
    Object.assign(row, res.data)
    ElMessage.success(`${action}成功`)
  } catch (e) {
    // 拦截器已提示
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

.muted {
  color: var(--ad-text-weak);
  font-size: 12.5px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.tips {
  margin-top: 16px;
  padding: 14px 16px;
  background: #fbfcfd;
  border: 1px dashed var(--ad-border);
  border-radius: 10px;
}

.tips p {
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--ad-text-sub);
}

.tips p + p {
  margin-top: 6px;
}
</style>
