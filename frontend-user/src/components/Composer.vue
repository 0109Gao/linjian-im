<template>
  <div class="composer">
    <el-input
      ref="inputRef"
      v-model="text"
      type="textarea"
      resize="none"
      :autosize="{ minRows: 2, maxRows: 5 }"
      :disabled="disabled"
      :placeholder="placeholder"
      @keydown="onKeydown"
    />
    <div class="footer">
      <span class="tip">
        <template v-if="disabled">当前不可发送</template>
        <template v-else>Enter 发送 · Shift + Enter 换行</template>
      </span>
      <span class="counter" :class="{ over: overLimit }">{{ length }} / 2000</span>
      <el-button type="primary" size="small" :disabled="!canSend" @click="submit">发送</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { codePointLength } from '@/utils/format'

const props = defineProps({
  disabled: { type: Boolean, default: false },
  placeholder: { type: String, default: '说点什么…' }
})

const emit = defineEmits(['send'])

const text = ref('')
const inputRef = ref(null)

/** 按码点计数，与后端校验口径一致：emoji 算 1 个字符，不是 2 个。 */
const length = computed(() => codePointLength(text.value))
const overLimit = computed(() => length.value > 2000)
const canSend = computed(() => !props.disabled && !overLimit.value && text.value.trim().length > 0)

function submit() {
  if (!canSend.value) {
    return
  }
  emit('send', text.value)
  text.value = ''
  // 发完清空后把焦点留在输入框，否则用户每发一条都要再点一次
  inputRef.value?.focus?.()
}

function onKeydown(event) {
  if (event.key !== 'Enter') {
    return
  }
  // 输入法选词时的回车是「确认候选词」，不是「发送」。
  // 不做这个判断，中文输入会变成「打一个字发一条」。
  if (event.isComposing || event.keyCode === 229) {
    return
  }
  if (event.shiftKey) {
    return
  }
  event.preventDefault()
  submit()
}

defineExpose({ focus: () => inputRef.value?.focus?.() })
</script>

<style scoped>
.composer {
  border-top: 1px solid var(--im-border);
  padding: 10px 16px 12px;
  background: #fff;
}

:deep(.el-textarea__inner) {
  border: none;
  box-shadow: none;
  padding: 6px 2px;
  font-size: 14px;
  line-height: 1.6;
}

:deep(.el-textarea__inner:focus) {
  box-shadow: none;
}

.footer {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 4px;
}

.tip {
  flex: 1;
  font-size: 11px;
  color: var(--im-text-weak);
}

.counter {
  font-size: 11px;
  color: var(--im-text-weak);
}

.counter.over {
  color: var(--im-danger);
  font-weight: 600;
}
</style>
