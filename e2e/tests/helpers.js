const { expect } = require('@playwright/test')

/**
 * 冒烟测试共用常量与登录动作。
 *
 * <p>端口是 vite dev server 的默认分配（用户端 5175、管理端 5176）。
 * 换端口时用环境变量覆盖，不要改代码。</p>
 */

const PASSWORD = '123456'
const USER_BASE = process.env.E2E_USER_BASE || 'http://localhost:5175'
const ADMIN_BASE = process.env.E2E_ADMIN_BASE || 'http://localhost:5176'

/** 每次跑都换一个新文本，避免命中上一次留下的消息/会话。 */
function unique(tag) {
  return `E2E${tag}${Date.now().toString(36)}`
}

/**
 * 登录用户端并等到 WebSocket 真正连上。
 *
 * <p>等 <code>.me-status</code> 变成「在线」而不是等某个 DOM 出现：
 * 「在线」是 <code>connection === 'connected'</code> 的渲染结果，
 * 意味着 WebSocket 握手与 STOMP CONNECT 都完成了。只等页面出现的话，
 * 后面发消息可能赶上连接还没建立，测试会随机失败。</p>
 */
async function loginUser(page, username) {
  await page.goto(`${USER_BASE}/login`)
  await page.getByPlaceholder('4-20 位字母数字下划线').fill(username)
  await page.getByPlaceholder('至少 6 位').fill(PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('.session-list')).toBeVisible()
  await expect(page.locator('.me-status')).toHaveText('在线')
}

async function loginAdmin(page, username = 'admin') {
  await page.goto(`${ADMIN_BASE}/login`)
  await page.getByPlaceholder('用户名').fill(username)
  await page.getByPlaceholder('密码').fill(PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
}

module.exports = { PASSWORD, USER_BASE, ADMIN_BASE, unique, loginUser, loginAdmin }
