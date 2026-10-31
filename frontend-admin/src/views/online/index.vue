<template>
  <div class="page">
    <div class="card">
      <div class="toolbar">
        <el-tag type="success" effect="plain">
          当前在线 {{ rows.length }} 人 / {{ totalConnections }} 条连接
        </el-tag>
        <span class="hint">数据来自 Redis 的 <code>im:online:*</code> 键，5 秒自动刷新</span>
        <div class="grow" />
        <el-switch v-model="autoRefresh" active-text="自动刷新" />
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="userId" label="用户 ID" width="220">
          <template #default="{ row }">
            <span class="mono">{{ row.userId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="用户名" width="160" />
        <el-table-column prop="nickname" label="昵称" width="160" />
        <el-table-column label="连接数" width="120" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.connectionCount }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="200">
          <template #default="{ row }">
            <span class="muted">
              {{ row.connectionCount > 1 ? '多端在线（手机 / 电脑各一条连接）' : '单端在线' }}
            </span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="rows.length === 0 && !loading" class="empty">
        当前没有在线用户。打开用户端（5175）登录后，这里应该能看到自己。
      </div>
    </div>

    <div class="tips">
      <p>
        <strong>为什么在线人数和连接数不一样：</strong>一个人手机 + 电脑同时在线是 1 个人、2 条连接。
        概览页统计的是「人」，这一页两个数字都给。
      </p>
      <p>
        <strong>为什么数据可能短暂不准：</strong>在线状态靠「连接事件 + 5 分钟 TTL」维护。
        进程被强杀时来不及删键，要等 TTL 过期才会消失；应用启动时也会主动清空一次。
        这是单机方案，多实例部署时「启动清空」会误删其他实例的记录。
      </p>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { onlineUsers } from '@/api'

const rows = ref([])
const loading = ref(false)
const autoRefresh = ref(true)
let timer = null

const totalConnections = computed(() =>
  rows.value.reduce((sum, r) => sum + Number(r.connectionCount || 0), 0))

async function load() {
  loading.value = true
  try {
    const res = await onlineUsers()
    rows.value = res.data || []
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function startTimer() {
  stopTimer()
  if (autoRefresh.value) {
    // 5 秒而不是 1 秒：在线状态本身有 TTL 与心跳延迟，更密的轮询只会增加负载，
    // 不会让数字更「实时」。
    timer = setInterval(load, 5000)
  }
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

watch(autoRefresh, startTimer)

onMounted(() => {
  load()
  startTimer()
})

onUnmounted(stopTimer)
</script>

<style scoped>
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--ad-text-sub);
}

.hint {
  font-size: 12px;
  color: var(--ad-text-weak);
}

.muted {
  color: var(--ad-text-sub);
  font-size: 12.5px;
}

code {
  background: #f2f3f5;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 11.5px;
}

.empty {
  padding: 40px;
  text-align: center;
  color: var(--ad-text-weak);
  font-size: 13px;
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
