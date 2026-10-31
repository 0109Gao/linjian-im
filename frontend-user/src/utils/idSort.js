/**
 * 雪花 ID 的比较与排序。
 *
 * <p>为什么单独抽一个文件：这个比较逻辑被真实 bug 咬过一次，而且是那种
 * 「看起来完全正常、只在特定数据量下错」的 bug——两个 ID 必须恰好落在同一个
 * 浮点数上才会出问题，靠手工点页面是发现不了的。抽出来才写得了单测。</p>
 */

/**
 * 雪花 ID 倒序比较（大的在前）。
 *
 * <p>不能写成 <code>Number(b) - Number(a)</code>：雪花 ID 是 19 位，
 * 超出 <code>Number.MAX_SAFE_INTEGER</code>（9007199254740991，16 位），
 * <code>Number()</code> 会静默丢精度——<code>...432</code> 变成 <code>...430</code>。
 * 两个只差 1~2 的 ID 转完就相等，比较器返回 0，顺序退化成「输入顺序」。</p>
 *
 * <p>改为字符串比较：位数多的一定更大，位数相同则字典序等于数值序。
 * 等价于补零对齐后比较，但不用真的拼字符串。</p>
 */
export function byIdDesc(a, b) {
  const sa = a === null || a === undefined ? '' : String(a)
  const sb = b === null || b === undefined ? '' : String(b)
  if (sa.length !== sb.length) return sb.length - sa.length
  if (sa === sb) return 0
  return sa > sb ? -1 : 1
}

/** 正序（小 ID 在前），与 byIdDesc 互补。 */
export function byIdAsc(a, b) {
  return -byIdDesc(a, b)
}
