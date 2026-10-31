/** 后端 LocalDateTime 形如 2026-10-31T14:00:00，没有时区，Safari 直接解析会失败。 */
export function formatDateTime(value) {
  if (!value) {
    return '-'
  }
  const normalized = typeof value === 'string' && !value.includes('T')
    ? value.replace(' ', 'T')
    : value
  const date = new Date(normalized)
  if (Number.isNaN(date.getTime())) {
    return String(value)
  }
  const pad = (n) => (n < 10 ? `0${n}` : n)
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function formatNumber(value) {
  const num = Number(value || 0)
  return num.toLocaleString('zh-CN')
}
