<template>
  <div class="page">
    <div class="card">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="按操作人或说明搜索"
          clearable
          style="width: 240px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-select v-model="action" placeholder="全部动作" clearable style="width: 150px" @change="search">
          <el-option v-for="opt in ACTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-button type="primary" @click="search">搜索</el-button>
        <div class="grow" />
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>

      <div class="table-wrap">
        <el-table :data="rows" v-loading="loading" stripe>
          <el-table-column label="时间" width="180">
            <template #default="{ row }">
              <span class="mono">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作人" width="170">
            <template #default="{ row }">
              <div class="actor">
                <span class="actor-name">{{ row.adminName || '—' }}</span>
                <span class="mono dim">{{ row.adminId }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="动作" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="tagType(row.action)" effect="plain">
                {{ row.actionText }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="对象" width="190">
            <template #default="{ row }">
              <span v-if="row.targetId === null || row.targetId === undefined" class="muted">—</span>
              <span v-else class="mono">{{ row.targetType }} {{ row.targetId }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="说明" min-width="260" show-overflow-tooltip />
          <el-table-column label="IP" width="150">
            <template #default="{ row }">
              <span class="mono dim">{{ row.ip || '—' }}</span>
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
        <strong>这张表记的是「管理员做了什么」，不是「用户说了什么」。</strong>
        消息审计页审的是聊天内容；谁在什么时候禁用了谁、谁看了哪个会话，
        只有这里查得到。两件事不能互相替代。
      </p>
      <p>
        <strong>「查看消息」在 60 秒内同一会话只记一条。</strong>
        打开抽屉和每次上拉加载更多都会调用同一个接口，不去重的话一小时的翻页
        就能把真正该被看到的禁用记录挤出第一页。禁用/恢复不去重——每次都要留痕。
      </p>
      <p>
        <strong>IP 一栏在反向代理后面可能是代理地址。</strong>
        取值优先看 <code>X-Forwarded-For</code>，而这个头客户端可以伪造，
        在这里只能当线索用，不能当证据。
      </p>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { adminLogs } from '@/api'
import { formatDateTime } from '@/utils/format'

/** 与后端 AdminActions 一一对应。value 落库，label 只用于显示。 */
const ACTIONS = [
  { value: 'USER_DISABLE', label: '禁用账号' },
  { value: 'USER_ENABLE', label: '恢复账号' },
  { value: 'MSG_AUDIT_VIEW', label: '查看消息' },
  { value: 'SEQ_REBUILD', label: '重建序号' }
]

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const action = ref('')
const keyword = ref('')
const loading = ref(false)

/** 破坏性动作用红色，读操作用灰色——扫一眼就能分出哪些是「改过东西」的行。 */
function tagType(value) {
  if (value === 'USER_DISABLE') {
    return 'danger'
  }
  if (value === 'USER_ENABLE') {
    return 'success'
  }
  if (value === 'SEQ_REBUILD') {
    return 'warning'
  }
  return 'info'
}

async function load() {
  loading.value = true
  try {
    const res = await adminLogs({
      action: action.value || undefined,
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

onMounted(load)
</script>

<style scoped>
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--ad-text-sub);
}

.dim {
  color: var(--ad-text-weak);
}

.muted {
  color: var(--ad-text-weak);
  font-size: 12.5px;
}

.actor {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
  min-width: 0;
}

.actor-name {
  font-size: 13px;
  word-break: break-all;
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

.tips code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  background: #f2f3f5;
  padding: 1px 4px;
  border-radius: 4px;
}
</style>
