const { test, expect } = require('@playwright/test')
const { USER_BASE, unique, loginUser } = require('./helpers')

/**
 * 用户端冒烟：登录 → 连接 → 发消息 → 收实时推送。
 *
 * <p>这一层不追求覆盖所有交互，只锁住「坏了就整站不可用」的四条链路。
 * 逐点交互的细节靠人工验收（见 本地运行说明.md 第 6 章），两者分工不同。</p>
 */

test('未登录访问聊天页会被引导到登录页', async ({ page }) => {
  await page.goto(`${USER_BASE}/`)
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible()
})

test('登录后落到聊天页，会话列表有内容且 WebSocket 已连上', async ({ page }) => {
  await loginUser(page, 'linjian')

  // 有会话说明 loadSessions 与 DTO 字段都对得上；空列表通常意味着字段名错了
  await expect(page.locator('.session-list .item').first()).toBeVisible()
  // 进入页面会自动选中第一个会话，右侧面板出得来才说明 select() 走通了
  await expect(page.locator('.chat-head')).toBeVisible()
  await expect(page.locator('.composer textarea')).toBeEnabled()
})

test('发送多行消息：气泡保留换行，状态从「发送中」变为已确认', async ({ page }) => {
  await loginUser(page, 'linjian')
  await expect(page.locator('.chat-head')).toBeVisible()

  const firstLine = unique('换行')
  const body = `${firstLine}\n第二行\n第三行`
  const textarea = page.locator('.composer textarea')
  await textarea.fill(body)
  await page.getByRole('button', { name: '发送', exact: true }).click()

  const bubble = page.locator('.bubble-row.mine .bubble').last()
  const text = bubble.locator('.text')

  // 1) 文本原样到达：textContent 里必须有真的换行符（没被后端或前端吞掉）
  await expect(text).toContainText(firstLine)
  expect(await text.evaluate((el) => el.textContent)).toBe(body)

  // 2) 样式必须允许换行。断言打在 .bubble 上而不是 .text 上：
  //    white-space / word-break 都是可继承属性，真正决定排版的是拥有这条规则的
  //    那个元素。断在子元素上会被继承“蒙对”——把 .bubble 的规则删掉，.text 的
  //    计算值仍然可能是 pre-wrap，用例就成了摆设。
  const styles = await bubble.evaluate((el) => {
    const s = getComputedStyle(el)
    return { whiteSpace: s.whiteSpace, wordBreak: s.wordBreak }
  })
  expect(styles.whiteSpace).toBe('pre-wrap')
  expect(styles.wordBreak).toBe('break-word')

  // 3) 排版结果也要对：三行文本必须真的占三行的高度。
  //    这一条不看任何 CSS 声明，只看渲染结果——折叠成一行时高度会掉到 1/3，
  //    所以无论样式写在哪、被谁覆盖，它都能抓住。
  const box = await bubble.boundingBox()
  expect(box.height).toBeGreaterThan(60)

  // 4) 状态收敛：ack 回来后才会有 #seq，一直停在「发送中」就是超时兜底没生效
  await expect(page.locator('.bubble-row.mine .meta').last()).toHaveText(/#\d+/, {
    timeout: 15_000
  })
})

test('对方发来的消息能实时推到未打开的会话，并亮起未读红点', async ({ browser }) => {
  const ctxA = await browser.newContext()
  const ctxB = await browser.newContext()
  const alice = await ctxA.newPage()
  const bob = await ctxB.newPage()

  try {
    await loginUser(alice, 'linjian')
    await loginUser(bob, 'demo')

    // A 先切到一个群会话：如果当前打开的恰好是和 demo 的单聊，
    // 新消息会直接进当前会话，红点就不会亮，这条用例会假失败。
    const groupItem = alice
      .locator('.session-list .item')
      .filter({ has: alice.locator('.group-badge') })
      .first()
    await expect(groupItem).toBeVisible()
    await groupItem.click()
    await expect(alice.locator('.chat-head .title')).toBeVisible()

    // B 打开与「林间」的单聊（该会话已由演示数据建立）
    const peerItem = bob.locator('.session-list .item').filter({ hasText: '林间' }).first()
    await expect(peerItem).toBeVisible()
    await peerItem.click()

    const text = unique('实时')
    await bob.locator('.composer textarea').fill(text)
    await bob.getByRole('button', { name: '发送', exact: true }).click()

    // A 端不做任何操作，只靠 STOMP 推送：会话预览变成新消息，且带未读红点
    const arrived = alice.locator('.session-list .item').filter({ hasText: text })
    await expect(arrived).toBeVisible({ timeout: 15_000 })
    await expect(arrived.locator('.badge')).toBeVisible()

    // B 端自己的消息应当拿到服务端的 seq，而不是停在发送中
    await expect(bob.locator('.bubble-row.mine .meta').last()).toHaveText(/#\d+/, {
      timeout: 15_000
    })
  } finally {
    await ctxA.close()
    await ctxB.close()
  }
})
