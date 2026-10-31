import { Client } from '@stomp/stompjs'

/**
 * STOMP 客户端。全应用只有一个连接，多标签页会各自建立自己的连接
 * （服务端的在线状态是 Set，天然支持一个用户多条连接）。
 *
 * <p>四个必须做对的地方，任何一处漏掉都会表现为「偶发收不到消息」：</p>
 * <ol>
 *   <li><b>重连后重新订阅。</b>SimpleBroker 不记忆订阅关系：连接一断，
 *       服务端那边的订阅就没了。stompjs 的自动重连只负责重建 TCP 连接，
 *       不会帮你重放 SUBSCRIBE 帧，必须自己在 onConnect 里重新登记一遍。</li>
 *   <li><b>token 同时放在 query 和 CONNECT 头里。</b>query 给握手拦截器，
 *       头给 ChannelInterceptor。缺一个的表现是「握手成功但 CONNECT 被拒」，
 *       客户端只能看到连接被关闭，原因藏在服务端日志里。</li>
 *   <li><b>心跳要和服务端一致。</b>服务端配的是 10s/10s，客户端也声明 10s。
 *       客户端声明得比服务端大，服务端会按客户端的值算超时，探活会变慢。</li>
 *   <li><b>错误帧要能被看到。</b>服务端拒绝订阅或拒绝发送时会回 ERROR 帧并关连接，
 *       不在 onStompError 里记录就等于「莫名其妙掉线」。</li>
 * </ol>
 */

const RECONNECT_DELAY = 3000

let client = null
let state = 'idle'

/** 业务订阅登记表：目的地 → 回调。断线重连后按它重建订阅。 */
const registry = new Map()
/** 当前活着的订阅：目的地 → StompSubscription */
const active = new Map()

const stateListeners = new Set()
const messageListeners = new Set()
const ackListeners = new Set()
const readListeners = new Set()
const errorListeners = new Set()

function emit(listeners, payload) {
  listeners.forEach((fn) => {
    try {
      fn(payload)
    } catch (e) {
      // 一个回调抛错不应该影响其他回调，否则一个组件的 bug 会静默吞掉所有消息
      console.error('[stomp] 回调执行失败', e)
    }
  })
}

function setState(next) {
  if (state === next) {
    return
  }
  state = next
  emit(stateListeners, state)
}

/** 与页面同源：开发时由 vite proxy 转发到 8083，生产由 nginx 反代。 */
function brokerUrl(token) {
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${proto}://${window.location.host}/ws?token=${encodeURIComponent(token)}`
}

export function onState(fn) {
  stateListeners.add(fn)
  return () => stateListeners.delete(fn)
}

export function onMessage(fn) {
  messageListeners.add(fn)
  return () => messageListeners.delete(fn)
}

export function onAck(fn) {
  ackListeners.add(fn)
  return () => ackListeners.delete(fn)
}

export function onRead(fn) {
  readListeners.add(fn)
  return () => readListeners.delete(fn)
}

export function onError(fn) {
  errorListeners.add(fn)
  return () => errorListeners.delete(fn)
}

export function currentState() {
  return state
}

export function isConnected() {
  return !!client && client.connected
}

export function connect(token) {
  if (client) {
    client.deactivate()
    client = null
  }

  client = new Client({
    brokerURL: brokerUrl(token),
    // 握手拦截器读 query 上的 token，ChannelInterceptor 读 CONNECT 头。
    // 两处都带，是因为这两道闸是独立实现、互不共享状态。
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: RECONNECT_DELAY,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,

    onConnect: () => {
      setState('connected')

      // 系统队列：每次（重）连都要重新订阅
      client.subscribe('/user/queue/message', (frame) => emit(messageListeners, parse(frame)))
      client.subscribe('/user/queue/ack', (frame) => emit(ackListeners, parse(frame)))
      client.subscribe('/user/queue/read', (frame) => emit(readListeners, parse(frame)))

      // 业务订阅（群主题）
      registry.forEach((_, destination) => subscribeNow(destination))
    },

    onDisconnect: () => setState('idle'),

    onWebSocketClose: () => {
      // 清掉本地订阅句柄：重连后旧的句柄已经失效，
      // 不清会让 subscribeNow 误以为「已经订阅过」而跳过重建。
      active.clear()
      setState(client && client.active ? 'reconnecting' : 'idle')
    },

    onStompError: (frame) => {
      emit(errorListeners, frame.headers?.message || '实时连接出错')
    }
  })

  setState('connecting')
  client.activate()
}

export function disconnect() {
  registry.clear()
  active.clear()
  if (client) {
    client.deactivate()
    client = null
  }
  setState('idle')
}

function parse(frame) {
  try {
    return JSON.parse(frame.body)
  } catch (e) {
    console.warn('[stomp] 收到非 JSON 消息', frame.body)
    return null
  }
}

function subscribeNow(destination) {
  const handler = registry.get(destination)
  if (!handler || !client || !client.connected) {
    return
  }
  if (active.has(destination)) {
    return
  }
  active.set(destination, client.subscribe(destination, (frame) => {
    const payload = parse(frame)
    if (payload) {
      handler(payload)
    }
  }))
}

/**
 * 订阅一个目的地。可以重复调用（同一个目的地只保留最后一次的回调）。
 * 连接尚未建立时只登记，连接建立后由 onConnect 统一订阅。
 */
export function subscribe(destination, handler) {
  registry.set(destination, handler)
  subscribeNow(destination)
}

export function unsubscribe(destination) {
  registry.delete(destination)
  const sub = active.get(destination)
  if (sub) {
    try {
      sub.unsubscribe()
    } catch (e) {
      // 连接已断时 unsubscribe 会抛，忽略即可——重连后本来也不会恢复这个订阅
    }
    active.delete(destination)
  }
}

/**
 * 发送消息。未连接时返回 false，由调用方决定怎么提示用户。
 *
 * 这里不做本地队列缓存：消息有 seq 幂等键，用户点重试是安全的；
 * 而「断线时静默排队、恢复后自动补发」会让用户以为发出去了，
 * 实际可能永远发不出去（比如会话已被踢出）。
 */
export function sendMessage(payload) {
  if (!client || !client.connected) {
    return false
  }
  client.publish({
    destination: '/app/chat.send',
    body: JSON.stringify(payload)
  })
  return true
}

export function wsDestination(sessionId) {
  return `/topic/session.${sessionId}`
}
