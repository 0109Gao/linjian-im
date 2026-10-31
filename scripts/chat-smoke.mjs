#!/usr/bin/env node
/**
 * W7 手工验收：两个真实用户、两条真实 WebSocket 连接，走完单聊全链路。
 *
 * 与 ws-smoke.mjs 一脉相承——不引入 @stomp/stompjs，用 Node 22 内置的 WebSocket
 * 手写 STOMP 帧。区别是它验的不再只是连通性，而是「消息到底有没有到对的人手上」。
 *
 * 覆盖顺序：
 *   1. 两个账号登录拿 token
 *   2. alice 发起会话（懒创建）
 *   3. 两条连接分别订阅 ack 与 message
 *   4. alice 发一条消息 → alice 收到 ack、bob 收到推送
 *   5. bob 拉历史，确认能读到
 *   6. bob 上报已读 → 未读归零
 *   7. 不存在的会话 → 4103
 *
 * 用法：node chat-smoke.mjs
 * 环境变量：IM_HOST（默认 localhost）、IM_PORT（默认 8083）
 */

const HOST = process.env.IM_HOST ?? 'localhost';
const PORT = process.env.IM_PORT ?? '8083';
const BASE = `http://${HOST}:${PORT}`;
const NULL = '\u0000';

const A = { username: 'linjian', password: '123456' };
const B = { username: 'demo', password: '123456' };

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

async function login(credential) {
  const { json } = await api('/api/auth/login', { method: 'POST', body: credential });
  if (!json || json.code !== 0) {
    fail(`登录失败 ${credential.username}: ${JSON.stringify(json)}`);
  }
  return {
    token: json.data.accessToken,
    userId: json.data.userId,
    nickname: json.data.nickname
  };
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

async function main() {
  const alice = await login(A);
  const bob = await login(B);
  ok(`两个账号登录成功：${alice.nickname}(id=${alice.userId}) / ${bob.nickname}(id=${bob.userId})`);

  const opened = await api('/api/sessions/open', {
    method: 'POST',
    token: alice.token,
    body: { peerUserId: bob.userId }
  });
  if (opened.json.code !== 0) {
    fail(`创建会话失败：${JSON.stringify(opened.json)}`);
  }
  const sessionId = opened.json.data.sessionId;
  ok(`会话就绪 sessionId=${sessionId}`);

  const clientA = await connectStomp(alice.token);
  const clientB = await connectStomp(bob.token);
  clientA.subscribe('/user/queue/ack', 'sub-ack');
  clientB.subscribe('/user/queue/message', 'sub-msg');
  await new Promise((r) => setTimeout(r, 300));

  const content = `W7 手工验收 ${new Date().toISOString()}`;
  const clientMsgId = `manual-${Date.now()}`;
  clientA.send('/app/chat.send', { sessionId, clientMsgId, content });

  const ack = JSON.parse((await expectFrame(clientA, 'MESSAGE', 'ack')).body);
  if (ack.success !== true) {
    fail(`发送失败：${ack.error}`);
  }
  ok(`发送方收到 ack：seq=${ack.seq} messageId=${ack.messageId} duplicated=${ack.duplicated}`);

  const received = JSON.parse((await expectFrame(clientB, 'MESSAGE', '推送')).body);
  if (received.content !== content) {
    fail(`接收内容被篡改：${received.content}`);
  }
  if (String(received.seq) !== String(ack.seq)) {
    fail(`两端的 seq 不一致：ack=${ack.seq} 推送=${received.seq}`);
  }
  ok(`接收方实时收到：seq=${received.seq} senderId=${received.senderId}`);

  const history = await api(`/api/sessions/${sessionId}/messages`, { token: bob.token });
  const items = history.json.data.items ?? [];
  const hit = items.find((m) => m.content === content);
  if (!hit) {
    fail(`历史消息里找不到刚发的那条，共 ${items.length} 条`);
  }
  ok(`历史拉取命中：${items.length} 条，最后一条 seq=${hit.seq}`);

  const before = await api('/api/sessions', { token: bob.token });
  const beforeUnread = before.json.data.items.find((s) => String(s.sessionId) === String(sessionId))?.unreadCount;
  if (beforeUnread === 0) {
    fail('未读数为 0 —— 在线收到消息后仍然没有落未读，红点逻辑不成立');
  }
  ok(`未读数正确：${beforeUnread}`);

  const read = await api(`/api/sessions/${sessionId}/read`, {
    method: 'POST',
    token: bob.token,
    body: { lastReadSeq: Number(hit.seq) }
  });
  if (read.json.code !== 0 || read.json.data.unreadCount !== 0) {
    fail(`已读上报后未读未归零：${JSON.stringify(read.json)}`);
  }
  ok('已读上报后未读归零');

  const missing = await api('/api/sessions/123456789/messages', { token: alice.token });
  if (missing.json.code !== 4103) {
    fail(`不存在的会话应返回 4103，实际 ${missing.json.code}`);
  }
  ok('不存在的会话返回 4103');

  clientA.close();
  clientB.close();
  console.log(`\n全部通过，共 ${passed} 项。`);
  process.exit(0);
}

main().catch((e) => fail(e.message));
