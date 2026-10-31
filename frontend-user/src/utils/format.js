/**
 * 时间与文本格式化。
 */

/** 会话列表右上角的时间：今天显示 HH:mm，昨天显示「昨天」，同年显示 MM-DD。 */
export function formatListTime(value) {
  if (!value) {
    return ''
  }
  const date = toDate(value)
  if (!date) {
    return ''
  }
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const diffDays = Math.floor((startOfToday.getTime() - startOfDay(date).getTime()) / 86400000)

  if (diffDays <= 0) {
    return hhmm(date)
  }
  if (diffDays === 1) {
    return '昨天'
  }
  if (date.getFullYear() === now.getFullYear()) {
    return `${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
  }
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** 消息气泡上方的时间分隔：今天只显示时分，跨天带上日期。 */
export function formatMessageTime(value) {
  if (!value) {
    return ''
  }
  const date = toDate(value)
  if (!date) {
    return ''
  }
  const now = new Date()
  if (isSameDay(date, now)) {
    return hhmm(date)
  }
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const diffDays = Math.floor((startOfToday.getTime() - startOfDay(date).getTime()) / 86400000)
  if (diffDays === 1) {
    return `昨天 ${hhmm(date)}`
  }
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${hhmm(date)}`
}

/** 两条消息间隔超过 5 分钟才显示时间分隔，否则整屏都是时间戳。 */
export function shouldShowTime(prev, current) {
  if (!current?.createdAt) {
    return false
  }
  if (!prev?.createdAt) {
    return true
  }
  const a = toDate(prev.createdAt)
  const b = toDate(current.createdAt)
  if (!a || !b) {
    return true
  }
  return b.getTime() - a.getTime() > 5 * 60 * 1000
}

/**
 * 后端返回的是 LocalDateTime（形如 2026-10-31T14:00:00，没有时区）。
 * 直接 new Date() 在 Safari 上会得到 Invalid Date，所以先归一化成带 T 的格式。
 */
function toDate(value) {
  if (value instanceof Date) {
    return value
  }
  if (typeof value !== 'string') {
    return null
  }
  const normalized = value.includes('T') ? value : value.replace(' ', 'T')
  const date = new Date(normalized)
  return Number.isNaN(date.getTime()) ? null : date
}

function isSameDay(a, b) {
  return a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth()
    && a.getDate() === b.getDate()
}

function startOfDay(d) {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate())
}

function pad(n) {
  return n < 10 ? `0${n}` : String(n)
}

function hhmm(d) {
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 按码点算长度：emoji 算 1 个字符，与后端的校验口径一致。 */
export function codePointLength(text) {
  return [...(text || '')].length
}

/**
 * 头像占位色：同一个名字永远得到同一个颜色，避免列表每次刷新颜色乱跳。
 */
const AVATAR_COLORS = ['#3370ff', '#7b61ff', '#00b2b2', '#f5a623', '#f54a45', '#34c724', '#8f959e']

export function avatarColor(name) {
  const text = name || ''
  let hash = 0
  for (let i = 0; i < text.length; i += 1) {
    hash = (hash * 31 + text.charCodeAt(i)) % 100000
  }
  return AVATAR_COLORS[hash % AVATAR_COLORS.length]
}

export function avatarText(name) {
  const text = (name || '').trim()
  return text ? [...text][0].toUpperCase() : 'U'
}
