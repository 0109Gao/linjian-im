<template>
  <div class="people-picker">
    <el-input
      v-model="keyword"
      placeholder="输入用户名或昵称搜索"
      clearable
      @input="onInput"
    >
      <template #prefix>
        <el-icon><Search /></el-icon>
      </template>
    </el-input>

    <div v-if="selectedUsers.length > 0" class="selected">
      <el-tag
        v-for="u in selectedUsers"
        :key="u.userId"
        closable
        size="small"
        @close="toggle(u)"
      >
        {{ u.nickname || u.username }}
      </el-tag>
    </div>

    <div class="results">
      <div v-if="loading" class="state">搜索中…</div>
      <div v-else-if="results.length === 0" class="state">
        {{ keyword.trim() ? '没有找到匹配的用户' : '输入关键字开始搜索' }}
      </div>
      <div
        v-for="u in results"
        :key="u.userId"
        class="person"
        :class="{ picked: isPicked(u) }"
        @click="toggle(u)"
      >
        <el-avatar :size="32" :style="{ background: avatarColor(u.nickname || u.username) }">
          {{ avatarText(u.nickname || u.username) }}
        </el-avatar>
        <div class="info">
          <div class="name">{{ u.nickname || u.username }}</div>
          <div class="username">@{{ u.username }}</div>
        </div>
        <el-icon v-if="isPicked(u)" class="check"><CircleCheckFilled /></el-icon>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { searchUsers } from '@/api'
import { avatarColor, avatarText } from '@/utils/format'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  multiple: { type: Boolean, default: true }
})

const emit = defineEmits(['update:modelValue'])

const keyword = ref('')
const results = ref([])
const loading = ref(false)
/** 选中的用户对象，而不只是 ID：父组件要拿昵称做展示，只有 ID 时还得再查一次 */
const picked = ref([])

let timer = null
/** 请求序号：防止慢请求覆盖后到的快请求结果 */
let seq = 0

const selectedUsers = computed(() => (props.multiple ? picked.value : picked.value.slice(0, 1)))

function onInput() {
  clearTimeout(timer)
  timer = setTimeout(doSearch, 300)
}

async function doSearch() {
  const q = keyword.value.trim()
  if (!q) {
    results.value = []
    return
  }
  const current = ++seq
  loading.value = true
  try {
    const res = await searchUsers(q)
    // 只接受最后一次请求的结果：连续输入时前几次请求可能后到，
    // 直接覆盖会让列表在「输入完成」之后又跳回旧结果。
    if (current === seq) {
      // 接口返回的字段叫 id（后端 UserBrief），应用其余部分统一叫 userId。
      // 不在这里转一次，选中后 emit 出去的就是 undefined，建会话接口会拿到空 body。
      results.value = (res.data || []).map((u) => ({
        userId: String(u.id),
        username: u.username,
        nickname: u.nickname
      }))
    }
  } catch (e) {
    results.value = []
  } finally {
    if (current === seq) {
      loading.value = false
    }
  }
}

function isPicked(user) {
  return picked.value.some((u) => String(u.userId) === String(user.userId))
}

function toggle(user) {
  const exists = isPicked(user)
  if (exists) {
    picked.value = picked.value.filter((u) => String(u.userId) !== String(user.userId))
  } else if (props.multiple) {
    picked.value = [...picked.value, user]
  } else {
    picked.value = [user]
  }
  emit('update:modelValue', picked.value.map((u) => String(u.userId)))
}

defineExpose({ selected: selectedUsers, reset: () => { picked.value = []; keyword.value = ''; results.value = [] } })
</script>

<style scoped>
.people-picker {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.selected {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 4px;
}

.results {
  max-height: 280px;
  overflow-y: auto;
  border: 1px solid var(--im-border);
  border-radius: 8px;
  padding: 4px;
}

.state {
  padding: 28px 12px;
  text-align: center;
  color: var(--im-text-weak);
  font-size: 12px;
}

.person {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
}

.person:hover {
  background: #f5f6f7;
}

.person.picked {
  background: var(--im-primary-light);
}

.info {
  flex: 1;
  min-width: 0;
}

.name {
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.username {
  font-size: 11px;
  color: var(--im-text-weak);
}

.check {
  color: var(--im-primary);
}
</style>
