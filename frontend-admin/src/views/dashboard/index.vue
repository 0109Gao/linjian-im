<template>
  <div class="page">
    <div class="cards">
      <div v-for="item in cards" :key="item.key" class="stat-card">
        <div class="stat-icon" :style="{ background: item.bg, color: item.color }">
          <el-icon><component :is="item.icon" /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-value">{{ formatNumber(data[item.key]) }}</div>
          <div class="stat-label">{{ item.label }}</div>
        </div>
      </div>
    </div>

    <div class="card note">
      <h3>关于这几个数字</h3>
      <ul>
        <li>
          <strong>消息总数</strong> 是全表 <code>COUNT(*)</code>，随数据量线性变慢。
          30 万行时约几十毫秒（见 <code>docs/性能验证.md</code>），够用就不再优化——
          统计值本来也不需要精确到秒，真要提速应该改成定时任务预计算，而不是给查询加缓存。
        </li>
        <li>
          <strong>在线人数</strong> 按「有在线键的用户」算，不是连接数。
          同一个人手机 + 电脑同时在线算 1 人，连接数在「在线监控」页里单独看。
        </li>
        <li>
          <strong>今日消息</strong> 从本地时区的 00:00 开始算，不是 UTC。
          用 UTC 会让「今天的消息」在北京时间早上 8 点前显示成 0。
        </li>
      </ul>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { stats } from '@/api'
import { formatNumber } from '@/utils/format'

const loading = ref(false)
const data = reactive({
  userCount: 0,
  groupCount: 0,
  sessionCount: 0,
  messageCount: 0,
  todayMessageCount: 0,
  onlineCount: 0
})

const cards = [
  { key: 'userCount', label: '账号总数', icon: 'User', bg: '#eef3ff', color: '#3370ff' },
  { key: 'sessionCount', label: '会话总数', icon: 'ChatDotRound', bg: '#eefaf3', color: '#34c724' },
  { key: 'groupCount', label: '群聊数量', icon: 'UserFilled', bg: '#f3f0ff', color: '#7b61ff' },
  { key: 'messageCount', label: '消息总数', icon: 'ChatLineSquare', bg: '#fff5e9', color: '#f5a623' },
  { key: 'todayMessageCount', label: '今日消息', icon: 'Timer', bg: '#e9f8f8', color: '#00b2b2' },
  { key: 'onlineCount', label: '在线人数', icon: 'Connection', bg: '#fdeeee', color: '#f54a45' }
]

async function load() {
  loading.value = true
  try {
    const res = await stats()
    Object.assign(data, res.data)
  } catch (e) {
    ElMessage.error('加载概览失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: 14px;
  margin-bottom: 16px;
}

.stat-card {
  background: #fff;
  border: 1px solid var(--ad-border);
  border-radius: 10px;
  padding: 18px;
  display: flex;
  align-items: center;
  gap: 14px;
}

.stat-icon {
  width: 42px;
  height: 42px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 19px;
  flex: 0 0 auto;
}

.stat-value {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}

.stat-label {
  font-size: 12px;
  color: var(--ad-text-weak);
  margin-top: 3px;
}

.note h3 {
  font-size: 14px;
  margin-bottom: 10px;
}

.note ul {
  padding-left: 18px;
  margin-bottom: 14px;
}

.note li {
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--ad-text-sub);
}

code {
  background: #f2f3f5;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 11.5px;
}
</style>
