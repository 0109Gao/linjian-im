import test from 'node:test'
import assert from 'node:assert/strict'
import { byIdAsc, byIdDesc } from './idSort.js'

/*
 * 用 node --test 而不是引入 vitest：这个文件只测纯函数，不需要 DOM、
 * 不需要转换、不需要 mock。为 10 行逻辑拉一整套框架不划算。
 * 跑法：cd frontend-user && npm test
 */

const CONSECUTIVE = [
  '109903710661906433',
  '109903710661906431',
  '109903710661906432'
]

test('连续雪花 ID 降序：精度不丢失，顺序严格正确', () => {
  assert.deepEqual([...CONSECUTIVE].sort(byIdDesc), [
    '109903710661906433',
    '109903710661906432',
    '109903710661906431'
  ])
})

test('旧的 Number 比较器确实会错，这条用例是它的墓碑', () => {
  // 复刻旧实现，证明它在这组数据上确实判不出顺序（全部返回 0）
  const oldComparator = (a, b) => Number(b) - Number(a)
  const oldOrder = [...CONSECUTIVE].sort(oldComparator)
  assert.notDeepEqual(oldOrder, [...CONSECUTIVE].sort(byIdDesc))
  // 三个不同的 ID 被转换成同一个数
  const distinct = new Set(CONSECUTIVE.map(Number))
  assert.equal(distinct.size, 1)
})

test('位数不同时，位数多的一定更大（不做补零也对）', () => {
  const ids = ['99999999999999999', '109859320094527488', '109903710661906432']
  assert.deepEqual([...ids].sort(byIdDesc), [
    '109903710661906432',
    '109859320094527488',
    '99999999999999999'
  ])
})

test('相等返回 0，保证 sort 稳定', () => {
  assert.equal(byIdDesc('109903710661906432', '109903710661906432'), 0)
})

test('null / undefined 不抛异常，排在最后', () => {
  const input = [null, '109903710661906432', undefined, '109859320094527488']
  const sorted = [...input].sort(byIdDesc)
  assert.deepEqual(sorted.slice(0, 2), ['109903710661906432', '109859320094527488'])
  assert.equal(sorted.length, 4)
})

test('数字与字符串混入也按字符串比较，不会退化成数值比较', () => {
  // 小 ID 以数字形式到达（后端对安全范围内的值不转字符串），不能因此丢顺序
  assert.deepEqual([1001, '1002'].sort(byIdDesc), ['1002', 1001])
})

test('byIdAsc 与 byIdDesc 严格互补', () => {
  for (const a of CONSECUTIVE) {
    for (const b of CONSECUTIVE) {
      assert.equal(byIdAsc(a, b), -byIdDesc(a, b))
    }
  }
})
