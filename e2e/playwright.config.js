// @ts-check
const { defineConfig } = require('@playwright/test')

/**
 * 林间 IM 冒烟测试配置。
 *
 * <p>为什么默认用系统 Edge 而不是 Playwright 自带的 Chromium：本地
 * <code>npx playwright install chromium</code> 要额外下 130MB，而 Windows 10/11
 * 一定自带 Edge。没有 Edge 的环境（典型是 CI 的 ubuntu 镜像）先
 * <code>npx playwright install chromium</code>，再设 <code>E2E_CHANNEL=bundled</code>。</p>
 *
 * <p>workers 固定 1：用例之间共用同一套演示数据（linjian / demo / admin 三个账号），
 * 并行跑会互相抢会话、抢未读计数，失败信息会指向错误的地方。</p>
 */

const channel = process.env.E2E_CHANNEL || 'msedge'
const channelOption = channel === 'bundled' ? {} : { channel }

module.exports = defineConfig({
  testDir: './tests',
  timeout: 45_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }]
  ],
  use: {
    headless: true,
    viewport: { width: 1280, height: 800 },
    actionTimeout: 10_000,
    navigationTimeout: 20_000,
    // 只在失败时留证据：成功的用例留 trace 会让报告目录迅速膨胀
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    ...channelOption
  }
})
