#!/usr/bin/env node
/**
 * W8 手工验收：三个人、四条真实 WebSocket 连接，走完群聊全链路。
 *
 * 与 chat-smoke.mjs 同一套做法——不引入 @stomp/stompjs，用 Node 22 内置 WebSocket
 * 手写 STOMP 帧。它验的是 W8 新增的四件事：
 *
 *   1. 建群 → 群消息一次广播，两个成员都收到（/topic/session.{id}）
 *   2. 非成员订阅同一个主题 → 收不到，且连接不被断开
 *   3. 未读各算各的，上报已读只影响自己
 *   4. 退群后拉不到消息（4101）
 *
 * 用法：node group-smoke.mjs
 * 环境变量：IM_HOST（默认 localhost）、IM_PORT（默认 8083）
 *
 * 前置：中间件已启动、应用已在运行、演示账号 linjian / demo 已存在。
 * 脚本会用 smoke_c / smoke_d 两个账号，第一次跑自动注册，之后直接登录。
 */

const HOST = process.env.IM_HOST ?? 'localhost';
const PORT = process.env.IM_PORT ?? '8083';
const BASE = `http://${HOST}:${PORT}`;
const NULL = '\u0000';

const ALICE = { username: 'linjian', password: '123456' };
const BOB = { username: 'demo', password: '123456' };
const CAROL = { username: 'smoke_c', password: '123456', nickname: '冒烟丙' };
const DAVE = { username: 'smoke_d', password: '123456', nickname: '冒烟丁' };

let passed = 0;

function ok(message) {
  passed++;
  console.log(`✓ ${message}`);
}

function fail(message) {
  console.error(`✗ ${message}`);
  process.exit(1);
}

function frame(command, headers = {}, body = '') {
  const lines = [command, ...Object.entries(headers).map(([k, v]) => `${k}:${v}`)];
  return lines.join('\n') + '\n\n' + body + NULL;
}

function parseFrames(buffer) {
  return buffer
    .split(NULL)
    .map((chunk) => chunk.replace(/^\n+/, ''))
    .filter((chunk) => chunk.trim().length > 0)
    .map((chunk) => {
      const sep = chunk.indexOf('\n\n');
      const head = sep === -1 ? chunk : chunk.slice(0, sep);
      const body = sep === -1 ? '' : chunk.slice(sep + 2);
      const [command, ...headerLines] = head.split('\n');
      const headers = {};
      for (const line of headerLines) {
        const i = line.indexOf(':');
        if (i > 0) headers[line.slice(0, i)] = line.slice(i + 1);
      }
      return { command, headers, body };
    });
}

async function api(path, { method = 'GET', token, body } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    fail(`接口 ${path} 返回的不是 JSON：${text.slice(0, 200)}`);
  }
  return { status: response.status, json };
}

/** 注册一个账号并登录。已存在时直接登录——脚本要能反复跑。 */
async function ensureUser(credential) {
  const registered = await api('/api/auth/register', { method: 'POST', body: credential });
  if (registered.json && registered.json.code === 0) {
    const d = registered.json.data;
    return { token: d.accessToken, userId: d.userId, nickname: d.nickname };
  }
  if (registered.json && registered.json.code !== 4112) {
    fail(`注册 ${credential.username} 失败：${JSON.stringify(registered.json)}`);
  }
  const { json } = await api('/api/auth/login', { method: 'POST', body: credential });
  if (!json || json.code !== 0) {
    fail(`登录 ${credential.username} 失败：${JSON.stringify(json)}`);
  }
  return { token: json.data.accessToken, userId: json.data.userId, nickname: json.data.nickname };
}

async function login(credential) {
  const { json } = await api('/api/auth/login', { method: 'POST', body: credential });
  if (!json || json.code !== 0) {
    fail(`登录失败 ${credential.username}: ${JSON.stringify(json)}`);
  }
  return { token: json.data.accessToken, userId: json.data.userId, nickname: json.data.nickname };
}

/** 极简 STOMP 客户端：只做「订阅、发送、取下一帧」三件事。 */
function connectStomp(token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://${HOST}:${PORT}/ws?token=${encodeURIComponent(token)}`);
    const queue = [];
    let waiter = null;
    let settled = false;

    const timer = setTimeout(() => {
      if (!settled) {
        settled = true;
        reject(new Error('STOMP 连接 10 秒内未建立'));
      }
    }, 10000);

    const push = (f) => {
      if (waiter) {
        const w = waiter;
        waiter = null;
        w(f);
      } else {
        queue.push(f);
      }
    };

    ws.addEventListener('open', () => {
      ws.send(frame('CONNECT', {
        'accept-version': '1.2',
        host: HOST,
        'heart-beat': '10000,10000',
        Authorization: `Bearer ${token}`
      }));
    });

    ws.addEventListener('message', (event) => {
      for (const f of parseFrames(String(event.data))) {
        if (f.command === 'CONNECTED' && !settled) {
          settled = true;
          clearTimeout(timer);
          resolve({
            subscribe: (destination, id) => ws.send(frame('SUBSCRIBE', {
              id,
              destination,
              ack: 'auto'
            })),
            send: (destination, obj) => ws.send(frame('SEND', {
              destination,
              'content-type': 'application/json'
            }, JSON.stringify(obj))),
            next: (timeoutMs = 8000) => new Promise((res, rej) => {
              if (queue.length) {
                return res(queue.shift());
              }
              const t = setTimeout(() => {
                waiter = null;
                rej(new Error(`等待帧超时（${timeoutMs}ms）`));
              }, timeoutMs);
              waiter = (f) => {
                clearTimeout(t);
                res(f);
              };
            }),
            /** 等一条消息帧；超时返回 null（用来断言「不该收到」）。 */
            tryNext: async (timeoutMs) => {
              try {
                return await new Promise((res, rej) => {
                  if (queue.length) {
                    return res(queue.shift());
                  }
                  const t = setTimeout(() => {
                    waiter = null;
                    rej(new Error('timeout'));
                  }, timeoutMs);
                  waiter = (f) => {
                    clearTimeout(t);
                    res(f);
                  };
                });
              } catch {
                return null;
              }
            },
            close: () => ws.close()
          });
        } else {
          push(f);
        }
      }
    });

    ws.addEventListener('error', () => {
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        reject(new Error('WebSocket 连接出错'));
      }
    });
  });
}

/** 从帧队列里取出指定 command 的帧，忽略中间的心跳与无关帧。 */
async function expectFrame(client, command, what) {
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    const f = await client.next(10000);
    if (f.command === command) {
      return f;
    }
    if (f.command === 'ERROR') {
      fail(`${what} 收到 ERROR 帧：${f.body || f.headers.message}`);
    }
  }
  fail(`${what} 未在 10 秒内收到 ${command} 帧`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function parse(body) {
  try {
    return JSON.parse(body);
  } catch {
    fail(`帧体不是合法 JSON：${body.slice(0, 200)}`);
  }
}

async function main() {
  // clientMsgId 必须每次都不同。写死的话第二轮起会命中服务端的幂等分支：
  // 不落新消息、不回放广播，但 ack 依然成功（duplicated=true）——
  // 表现为「发送成功，但谁都收不到」。这个坑在第一次跑通、第二次跑挂时极难定位。
  const runId = `${Date.now()}`;

  const alice = await login(ALICE);
  const bob = await login(BOB);
  const carol = await ensureUser(CAROL);
  const dave = await ensureUser(DAVE);
  ok(`四个账号就绪：${alice.nickname} / ${bob.nickname} / ${carol.nickname} / ${dave.nickname}`);

  // ---- 1. 建群 ----
  const created = await api('/api/sessions/group', {
    method: 'POST',
    token: alice.token,
    body: { name: '冒烟测试群', memberIds: [bob.userId, carol.userId] }
  });
  if (!created.json || created.json.code !== 0) {
    fail(`建群失败：${JSON.stringify(created.json)}`);
  }
  const groupId = created.json.data.sessionId;
  ok(`建群成功 sessionId=${groupId}`);

  // ---- 2. 会话列表：群类型 / 群名 / 成员数 ----
  const list = await api('/api/sessions', { token: bob.token });
  const item = (list.json?.data?.items ?? []).find((s) => String(s.sessionId) === String(groupId));
  if (!item) fail('群会话没有出现在 bob 的会话列表里');
  if (item.sessionType !== 2) fail(`sessionType 应为 2，实际 ${item.sessionType}`);
  if (item.name !== '冒烟测试群') fail(`群名不对：${item.name}`);
  if (item.memberCount !== 3) fail(`成员数应为 3，实际 ${item.memberCount}`);
  ok(`会话列表正确：sessionType=2 群名=${item.name} 成员数=${item.memberCount}`);

  // ---- 3. 三条连接：bob/carol 订阅群主题，alice 收 ack ----
  const aliceWs = await connectStomp(alice.token);
  const bobWs = await connectStomp(bob.token);
  const carolWs = await connectStomp(carol.token);
  const daveWs = await connectStomp(dave.token);
  await sleep(300);

  bobWs.subscribe(`/topic/session.${groupId}`, 'sub-bob');
  carolWs.subscribe(`/topic/session.${groupId}`, 'sub-carol');
  // dave 不在群里，这条订阅应当被服务端丢弃
  daveWs.subscribe(`/topic/session.${groupId}`, 'sub-dave');
  aliceWs.subscribe('/user/queue/ack', 'sub-ack');
  await sleep(400);

  // ---- 4. 群消息：一次广播，两个成员都收到 ----
  aliceWs.send('/app/chat.send', { sessionId: groupId, clientMsgId: `smoke-${runId}-1`, content: '群里好' });

  const ack = parse((await expectFrame(aliceWs, 'MESSAGE', '等待 ack')).body);
  if (!ack.success) fail(`发送被拒绝：${ack.error}`);
  if (ack.duplicated) fail('服务端判定为重发（clientMsgId 与历史消息重复），脚本没有造出真实的新消息');
  const seq = ack.seq;
  ok(`发送成功 seq=${seq}`);

  const bobMsg = parse((await expectFrame(bobWs, 'MESSAGE', '等待 bob 收到群消息')).body);
  const carolMsg = parse((await expectFrame(carolWs, 'MESSAGE', '等待 carol 收到群消息')).body);
  if (bobMsg.content !== '群里好' || carolMsg.content !== '群里好') fail('群消息内容不对');
  if (bobMsg.seq !== seq || carolMsg.seq !== seq) fail('两端收到的 seq 与 ack 不一致');
  ok(`广播到达两个成员，seq 一致=${seq}`);

  // ---- 5. 非成员收不到（订阅已被拦下，连接仍活着） ----
  const daveGot = await daveWs.tryNext(1500);
  if (daveGot) fail(`非成员 dave 收到了群消息：${daveGot.body.slice(0, 120)}`);
  ok('非成员订阅被拦下，收不到群消息');

  aliceWs.send('/app/chat.send', { sessionId: groupId, clientMsgId: `smoke-${runId}-2`, content: '连接还在吗' });
  const ack2 = parse((await expectFrame(aliceWs, 'MESSAGE', '等待第二次 ack')).body);
  if (!ack2.success) fail('越权订阅把别的连接带坏了');
  const bobAgain = parse((await expectFrame(bobWs, 'MESSAGE', '等待 bob 第二条')).body);
  if (bobAgain.content !== '连接还在吗') fail('第二条群消息内容不对');
  ok('越权订阅不影响正常投递，连接未断开');

  // ---- 6. 未读各算各的 ----
  const bobList = await api('/api/sessions', { token: bob.token });
  const bobItem = bobList.json.data.items.find((s) => String(s.sessionId) === String(groupId));
  if (bobItem.unreadCount !== 2) fail(`bob 未读应为 2，实际 ${bobItem.unreadCount}`);
  const carolList = await api('/api/sessions', { token: carol.token });
  const carolItem = carolList.json.data.items.find((s) => String(s.sessionId) === String(groupId));
  if (carolItem.unreadCount !== 2) fail(`carol 未读应为 2，实际 ${carolItem.unreadCount}`);
  ok('两个成员各 2 条未读');

  const read = await api(`/api/sessions/${groupId}/read`, {
    method: 'POST',
    token: bob.token,
    // 用最后一条的 seq（不是第一条）：上报到 seq 1 只会清掉 1 条，还剩 1 条未读
    body: { lastReadSeq: ack2.seq }
  });
  if (read.json?.data?.unreadCount !== 0) fail(`bob 已读后未读应为 0，实际 ${read.json?.data?.unreadCount}`);
  const carolAfter = await api('/api/sessions', { token: carol.token });
  const carolItem2 = carolAfter.json.data.items.find((s) => String(s.sessionId) === String(groupId));
  if (carolItem2.unreadCount !== 2) fail('bob 的已读影响了 carol 的未读');
  ok('已读只清自己的红点，不影响他人');

  // ---- 7. 退群后拉不到消息 ----
  const left = await api(`/api/sessions/${groupId}/members/me`, { method: 'DELETE', token: carol.token });
  if (left.json?.data?.remaining !== 2) fail(`退群后剩余应为 2，实际 ${left.json?.data?.remaining}`);
  const denied = await api(`/api/sessions/${groupId}/messages`, { token: carol.token });
  if (denied.json?.code !== 4101) fail(`退群后拉消息应返回 4101，实际 ${denied.json?.code}`);
  ok('退群成功，之后拉消息被拒 4101');

  for (const ws of [aliceWs, bobWs, carolWs, daveWs]) {
    ws.close();
  }
  console.log(`\n全部通过：${passed} 项`);
}

main().catch((e) => fail(e.stack || e.message));
