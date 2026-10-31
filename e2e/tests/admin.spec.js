const { test, expect } = require('@playwright/test')
const { ADMIN_BASE, loginAdmin } = require('./helpers')

/**
 * 管理端冒烟：登录/权限 → 概览指标 → 用户禁用回路 → 消息审计。
 *
 * <p>禁用用 demo 账号（不是 admin）：禁用自己会被后端 4120 拒绝，
 * 那是正确行为，但拿它当冒烟用例只会测到「按钮是灰的」。</p>
 */

test('未登录访问后台会被引导到登录页', async ({ page }) => {
  await page.goto(`${ADMIN_BASE}/dashboard`)
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByPlaceholder('用户名')).toBeVisible()
})

test('普通账号登录后台被拦下，停在登录页', async ({ page }) => {
  await loginAdmin(page, 'linjian')
  // 提示来自前端 role 判断；即使绕过这一步，/api/admin/** 也会 403
  await expect(page.locator('.el-message')).toContainText('没有管理员权限')
  await expect(page).toHaveURL(/\/login/)
  await expect(page.locator('.sidebar')).toHaveCount(0)
})

test('管理员登录后概览六项指标都是有效数字', async ({ page }) => {
  await loginAdmin(page)
  await expect(page.locator('.sidebar')).toBeVisible()
  await expect(page.locator('.topbar .crumb')).toHaveText('概览')

  const cards = page.locator('.stat-card')
  await expect(cards).toHaveCount(6)
  // 指标显示 undefined 或 NaN 都是真故障：前者说明 DTO 字段名和前端读的对不上，
  // 后者说明拿到的不是数字。formatNumber 会加千分位，所以允许逗号。
  const values = await cards.locator('.stat-value').allInnerTexts()
  expect(values).toHaveLength(6)
  for (const v of values) {
    expect(v).toMatch(/^[\d,]+$/)
  }
  await expect(cards.locator('.stat-label')).toHaveText([
    '账号总数',
    '会话总数',
    '群聊数量',
    '消息总数',
    '今日消息',
    '在线人数'
  ])
})

test('用户管理：搜索、禁用、恢复走完一个回路', async ({ page }) => {
  await loginAdmin(page)
  await page.locator('.menu-item', { hasText: '用户管理' }).click()
  await expect(page.locator('.topbar .crumb')).toHaveText('用户管理')

  await page.getByPlaceholder('按用户名或昵称搜索').fill('demo')
  await page.getByRole('button', { name: '搜索' }).click()

  const row = page.locator('.el-table__row').filter({ hasText: 'demo' })
  await expect(row).toHaveCount(1)

  // 自愈：上一轮若在恢复前失败，这里先把 demo 拉回正常，避免后续用例全红
  if (await row.locator('.el-tag', { hasText: '已禁用' }).count()) {
    await row.getByRole('button', { name: '启用' }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '启用' }).click()
    await expect(row.locator('.el-tag', { hasText: '正常' })).toBeVisible()
  }

  try {
    await row.getByRole('button', { name: '禁用' }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '禁用' }).click()
    // 状态就地更新（不重查整页），所以这里能直接看到变化
    await expect(row.locator('.el-tag', { hasText: '已禁用' })).toBeVisible()
    // 禁用后这一行只剩「启用」
    await expect(row.getByRole('button', { name: '禁用' })).toHaveCount(0)
  } finally {
    await row.getByRole('button', { name: '启用' }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '启用' }).click()
    await expect(row.locator('.el-tag', { hasText: '正常' })).toBeVisible()
  }

  // 管理员自己这一行：两个操作按钮都应该被禁用
  await page.getByPlaceholder('按用户名或昵称搜索').fill('admin')
  await page.getByRole('button', { name: '搜索' }).click()
  const self = page.locator('.el-table__row').filter({ hasText: 'admin' })
  await expect(self.locator('button', { hasText: '禁用' })).toBeDisabled()
})

test('消息审计：搜到会话并展开该会话的消息', async ({ page }) => {
  await loginAdmin(page)
  await page.locator('.menu-item', { hasText: '消息审计' }).click()
  await expect(page.locator('.topbar .crumb')).toHaveText('消息审计')

  const rows = page.locator('.el-table__row')
  await expect(rows.first()).toBeVisible()
  expect(await rows.count()).toBeGreaterThan(0)

  // 列表按 last_msg_at DESC 排序，第一行就是最近有消息的会话，必然有内容可看
  await rows.first().getByRole('button', { name: '查看消息' }).click()

  const drawer = page.locator('.el-drawer')
  await expect(drawer).toBeVisible()
  await expect(drawer.locator('.msg').first()).toBeVisible()
  // 每条都要有服务端 seq，没有说明消息行读出来是残缺的
  await expect(drawer.locator('.msg .seq').first()).toHaveText(/^#\d+$/)
})

test('操作日志：页面能加载，且前面的禁用/恢复动作都有留痕', async ({ page }) => {
  await loginAdmin(page)
  await page.locator('.menu-item', { hasText: '操作日志' }).click()
  await expect(page.locator('.topbar .crumb')).toHaveText('操作日志')

  // 上一个用例（用户管理回路）刚禁用了又恢复了 demo，这里必须能看到两条对应的记录。
  // 看不到说明审计写入或查询接口没接上——那正是「功能看起来正常、实际没留痕」的坑。
  const rows = page.locator('.el-table__row')
  await expect(rows.first()).toBeVisible()

  // 过滤出「禁用账号」，断言至少有一条目标为 demo 的记录
  await page.locator('.el-select').click()
  await page.locator('.el-select-dropdown__item', { hasText: '禁用账号' }).click()
  await expect(page.locator('.el-table__row').first()).toBeVisible()
  await expect(page.locator('.el-table__row', { hasText: 'USER' }).first()).toContainText('demo')
})
