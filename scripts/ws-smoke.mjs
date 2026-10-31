#!/usr/bin/env node
/**
 * W6 手工验收用的 STOMP 冒烟脚本。
 *
 * 刻意不引入 @stomp/stompjs 或 ws 依赖：Node 22 已内置全局 WebSocket，
 * 而 STOMP 帧本身就是纯文本，手写四个帧就能验完「握手放行 / CONNECT 鉴权 / 回显内容」
 * 三件事。少一层库，出问题时少一层需要怀疑的地方。
 *
 * 用法：
 *   node ws-smoke.mjs <token>              正常链路：应收到 pong，退出码 0
 *   node ws-smoke.mjs --no-token           不带 token 握手：应被拒绝，退出码 0
 *   node ws-smoke.mjs --forged-connect <token>
 *                                          握手用真 token、CONNECT 用假 token：应被服务端断开
 *
 * 环境变量：IM_PORT（默认 8083）、IM_HOST（默认 localhost）
 */

import crypto from 'node:crypto';

const HOST = process.env.IM_HOST ?? 'localhost';
const PORT = process.env.IM_PORT ?? '8083';
const NULL = '\u0000';

/**
 * 构造一个「结构合法但签名错误」的 JWT。
 *
 * 为什么不用 `forged.token.value` 这种字面量：那样只会命中「JSON 解析失败」这一分支，
 * 证明的是「格式不对会被拒」，而真正的风险是「格式完全正确、签名不对」——
 * 那正是密钥泄漏或换成占位密钥之后会发生的情况，必须单独验证。
 */
function forgeJwt(wrongSecret) {
  const now = Math.floor(Date.now() / 1000);
  const enc = (obj) => Buffer.from(JSON.stringify(obj)).toString('base64url');
  const head = enc({ alg: 'HS384', typ: 'JWT' });
  const body = enc({ sub: '109859320094527488', username: 'linjian', iat: now, exp: now + 3600 });
  const sig = crypto.createHmac('sha384', wrongSecret).update(`${head}.${body}`).digest('base64url');
  return `${head}.${body}.${sig}`;
}

const mode = process.argv[2] === '--no-token' ? 'no-token'
  : process.argv[2] === '--forged-connect' ? 'forged-connect'
  : 'normal';
const token = mode === 'normal' ? process.argv[2] : (mode === 'forged-connect' ? process.argv[3] : null);

function frame(command, headers = {}, body = '') {
  const lines = [command, ...Object.entries(headers).map(([k, v]) => `${k}:${v}`)];
  return lines.join('\n') + '\n\n' + body + NULL;
}

/** 按 STOMP 的 NULL 终止符切帧，跳过心跳（纯换行）。 */
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

function fail(message) {
  console.error(`✗ ${message}`);
  process.exit(1);
}

if (mode === 'no-token') {
  const ws = new WebSocket(`ws://${HOST}:${PORT}/ws`);
  const timer = setTimeout(() => fail('未带 token 的握手既没有被拒绝也没有超时，行为不明确'), 8000);
  ws.addEventListener('open', () => {
    clearTimeout(timer);
    fail('未带 token 竟然握手成功 —— 握手鉴权没有生效');
  });
  ws.addEventListener('error', () => {
    clearTimeout(timer);
    console.log('✓ 未带 token 的握手被拒绝（连接未建立）');
    process.exit(0);
  });
} else if (mode === 'forged-connect') {
  const ws = new WebSocket(`ws://${HOST}:${PORT}/ws?token=${encodeURIComponent(token)}`);
  const timer = setTimeout(() => fail('CONNECT 用伪造 token 后连接既未被断开也未被放行'), 8000);
  ws.addEventListener('open', () => {
    ws.send(frame('CONNECT', {
      'accept-version': '1.2',
      host: HOST,
      'heart-beat': '10000,10000',
      Authorization: `Bearer ${forgeJwt('wrong-secret-used-by-attacker-0123456789abcdefghijkl')}`
    }));
  });
  ws.addEventListener('message', (event) => {
    const frames = parseFrames(String(event.data));
    for (const f of frames) {
      if (f.command === 'CONNECTED') {
        clearTimeout(timer);
        fail('CONNECT 携带伪造 token 却被接受 —— 第二道闸没有生效');
      }
    }
  });
  ws.addEventListener('close', () => {
    clearTimeout(timer);
    console.log('✓ CONNECT 用伪造 token 后被服务端断开');
    process.exit(0);
  });
  ws.addEventListener('error', () => { /* close 会随后触发 */ });
} else {
  if (!token) fail('缺少 token 参数');

  const ws = new WebSocket(`ws://${HOST}:${PORT}/ws?token=${encodeURIComponent(token)}`);
  let step = 'connect';
  const timer = setTimeout(() => fail(`超时，卡在 ${step} 阶段`), 12000);

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
      if (f.command === 'CONNECTED' && step === 'connect') {
        step = 'subscribe';
        ws.send(frame('SUBSCRIBE', { id: 'sub-0', destination: '/user/queue/pong', ack: 'auto' }));
        setTimeout(() => {
          step = 'ping';
          ws.send(frame('SEND', {
            destination: '/app/ping',
            'content-type': 'application/json'
          }, JSON.stringify({ echo: 'hello-w6' })));
        }, 300);
      } else if (f.command === 'MESSAGE') {
        clearTimeout(timer);
        const pong = JSON.parse(f.body);
        console.log('✓ 收到回显：' + JSON.stringify(pong));
        if (pong.type !== 'pong') return fail(`回显类型不是 pong：${pong.type}`);
        if (!pong.userId) return fail('回显里没有 userId');
        if (pong.echo !== 'hello-w6') return fail(`echo 被篡改：${pong.echo}`);
        ws.close();
        process.exit(0);
      } else if (f.command === 'ERROR') {
        clearTimeout(timer);
        fail(`收到 ERROR 帧：${f.body || f.headers.message}`);
      }
    }
  });

  ws.addEventListener('close', (event) => {
    if (step !== 'done') {
      clearTimeout(timer);
      fail(`连接被提前关闭（阶段=${step}, code=${event.code}）`);
    }
  });
  ws.addEventListener('error', () => { /* close 会随后触发 */ });
}
