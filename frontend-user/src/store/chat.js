import { defineStore } from 'pinia'
import * as api from '../api'
import * as stomp from '../ws/stomp'
import { useUserStore } from './user'

/**
 * 聊天状态。
 *
 * <p>三条必须遵守的约定，违反任何一条都会出现「消息重复 / 消息卡在发送中 / 红点不准」：</p>
 *
 * <ol>
 *   <li><b>所有 ID 一律 String。</b>userId 与 sessionId 是雪花 ID，超出 JS 安全整数；
 *       后端虽然会把超限值序列化成字符串，但小值仍是数字。前端统一 String() 之后，
 *       比较与当作对象键都不会踩「1001 与 '1001' 是两个键」这类坑。</li>
 *   <li><b>消息列表按 seq 排序，且只信服务端的 seq。</b>本地乐观插入的那条一开始没有
 *       seq，它必须停在末尾；收到 ack 或自己的推送后才拿到真实 seq 并归位。</li>
 *   <li><b>本地消息与服务端消息靠 clientMsgId 认领，不靠内容。</b>用户连续发两条
 *       「好的」是完全正常的行为，按内容匹配会错删。</li>
 * </ol>
 */

function genClientMsgId() {
  if (window.crypto && typeof window.crypto.randomUUID === 'function') {
    return window.crypto.randomUUID()
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

function toStr(v) {
  return v === null || v === undefined ? null : String(v)
}

function bySeq(a, b) {
  // 没有 seq 的是本地待确认消息，永远排在最后
  if (a.seq === null && b.seq === null) return 0
  if (a.seq === null) return 1
  if (b.seq === null) return -1
  return a.seq - b.seq
}

/**
 * 发送超时。正常 ack 在 200ms 内回来，8 秒足够宽容；再长用户就会以为界面死了。
 *
 * 为什么必须有这个兜底：`stomp.sendMessage` 返回 true 只代表「帧交给了 WebSocket」，
 * 不代表服务端收到了。如果帧在传输途中丢掉、而连接本身还没被心跳判定为断开，
 * 这条消息就会永远停在 `sending`——转圈、点不了、也没有重试按钮。
 * 重连补齐也救不了它：补齐是按 `afterSeq` 拉的，服务端根本没有这条消息。
 */
const SEND_TIMEOUT_MS = 8000

/** clientMsgId → 定时器句柄。放模块作用域而不是 state：定时器句柄没有响应式的意义，
 *  而且放进 state 会被 Pinia 深度追踪，属于纯粹的噪声。 */
const sendTimers = new Map()

export const useChatStore = defineStore('chat', {  state: () => ({
    sessions: [],
    activeId: null,
    /** sessionId → 消息数组（按 seq 升序，本地待确认的排在最后） */
    messages: {},
    hasMore: {},
    members: [],
    connection: 'idle',
    initialized: false,
    sending: false,
    /** clientMsgId → { sessionId }。ack 里没有 sessionId，靠它把回执归位。 */
    outbox: {},
    /** 已订阅的群主题：目的地 → true。用于 loadSessions 后做增删对比 */
    groupTopics: {},
    /** 上一次连接状态，用于区分「首次连接」和「重连」 */
    lastConnection: 'idle'
  }),

  getters: {
    activeSession(state) {
      return state.sessions.find((s) => String(s.sessionId) === state.activeId) || null
    },
    activeMessages(state) {
      return state.activeId ? state.messages[state.activeId] || [] : []
    },
    totalUnread(state) {
      return state.sessions.reduce((sum, s) => sum + (Number(s.unreadCount) || 0), 0)
    },
    isConnected(state) {
      return state.connection === 'connected'
    }
  },

  actions: {
    // ---------------------------------------------------------------- 初始化

    async init() {
      if (this.initialized) {
        return
      }
      this.initialized = true

      stomp.onState((s) => this.handleConnectionState(s))
      stomp.onMessage((m) => this.handleIncoming(m))
      stomp.onAck((a) => this.handleAck(a))
      stomp.onRead((e) => this.handleReadEvent(e))
      stomp.onError((msg) => console.warn('[chat] 实时通道错误', msg))

      // 会话列表失败不阻断实时通道：能连上 WebSocket、能发消息，
      // 比「整个聊天页因为一次列表请求失败而卡住」有用得多。
      try {
        await this.loadSessions()
      } catch (e) {
        console.warn('[chat] 会话列表加载失败', e)
      }
      stomp.connect(useUserStore().token)
    },

    handleConnectionState(next) {
      const previous = this.lastConnection
      this.lastConnection = next
      this.connection = next

      // 只有「重连」才需要补齐。首次连接时 activeId 为空或消息还没加载，
      // 补一次会白跑一趟，还会把「刚刚加载的最近一页」又拉一遍。
      if (next === 'connected' && previous === 'reconnecting') {
        this.resyncAfterReconnect()
      }
    },

    /**
     * 断线重连后的补救：会话列表整体刷新（断线期间可能有新会话、未读数已变），
     * 当前会话按 seq 增量补拉（漏掉的消息一条不少地补回来）。
     */
    async resyncAfterReconnect() {
      await this.loadSessions()
      const sid = this.activeId
      if (!sid) {
        return
      }
      const list = this.messages[sid] || []
      const maxSeq = list.reduce((max, m) => Math.max(max, Number(m.seq) || 0), 0)
      try {
        const res = await api.listMessages(sid, { afterSeq: maxSeq, limit: 100 })
        const incoming = (res.data.items || []).map((m) => this.normalize(m))
        incoming.forEach((m) => this.mergeMessage(sid, m))
        if (incoming.length > 0) {
          this.markRead(sid)
        }
      } catch (e) {
        // 补齐失败不清空已有消息：用户至少还能看到断线前的内容
        console.warn('[chat] 重连补齐失败', e)
      }
    },

    // ---------------------------------------------------------------- 会话列表

    async loadSessions() {
      const res = await api.listSessions(1, 50)
      this.sessions = (res.data.items || []).map((s) => ({
        ...s,
        sessionId: String(s.sessionId),
        peerUserId: toStr(s.peerUserId)
      }))
      this.syncGroupSubscriptions()
    },

    /**
     * 群主题订阅同步：把「我所在的全部群」都订阅上，多退少补。
     *
     * <p>为什么不是只订阅当前打开的那个群：群消息在服务端只广播到
     * {@code /topic/session.{id}}，不走 {@code /user/queue/message}。只订阅当前群的话，
     * 用户停在单聊时，群里别人说话本机完全收不到——未读红点不会亮、会话摘要不更新，
     * 直到切会话或重连才补上，而 loadSessions 只在初始化/建会话/重连时调用，没有轮询兜底。</p>
     *
     * <p>代价是设备会收到所有群的群消息流量。这个量本来就是「一个 IM 用户应收到的量」，
     * 与「必须为每个群单独拉一次历史」相比反而更省。单聊仍然走 {@code /user/queue}，
     * 由 broker 的 Principal 隔离，不需要在客户端做任何事。</p>
     *
     * <p>订阅的合法性与安全边界在服务端 {@code GroupTopicAuthInterceptor}：它会逐个校验
     * 成员身份，非成员订阅会被拒绝。客户端这边多订一个群不会造成越权读取。</p>
     */
    syncGroupSubscriptions() {
      const wanted = new Set()
      this.sessions.forEach((s) => {
        if (Number(s.sessionType) === 2) {
          wanted.add(stomp.wsDestination(s.sessionId))
        }
      })
      // 退群、被移出、会话消失：把订阅撤掉，否则继续收一个已经看不到的群的消息
      Object.keys(this.groupTopics).forEach((dest) => {
        if (!wanted.has(dest)) {
          stomp.unsubscribe(dest)
          delete this.groupTopics[dest]
        }
      })
      wanted.forEach((dest) => {
        if (!this.groupTopics[dest]) {
          stomp.subscribe(dest, (m) => this.handleIncoming(m))
          this.groupTopics[dest] = true
        }
      })
    },

    async openWith(peerUserId) {
      const res = await api.openSession(peerUserId)
      const sid = String(res.data.sessionId)
      await this.loadSessions()
      await this.select(sid)
      return sid
    },

    async createGroup(name, memberIds) {
      const res = await api.createGroup(name, memberIds)
      const sid = String(res.data.sessionId)
      await this.loadSessions()
      await this.select(sid)
      return sid
    },

    async addMembers(sessionId, userIds) {
      const res = await api.addMembers(sessionId, userIds)
      await this.loadMembers(sessionId)
      const session = this.sessions.find((s) => s.sessionId === String(sessionId))
      if (session) {
        session.memberCount = Number(session.memberCount || 0) + Number(res.data.added || 0)
      }
      return res.data.added
    },

    async leaveGroup(sessionId) {
      const sid = String(sessionId)
      await api.leaveGroup(sid)
      if (this.activeId === sid) {
        this.activeId = null
      }
      delete this.messages[sid]
      // loadSessions 里的 syncGroupSubscriptions 会把这个群的订阅撤掉
      await this.loadSessions()
    },

    async loadMembers(sessionId) {
      const sid = String(sessionId)
      const res = await api.listMembers(sid)
      if (this.activeId === sid) {
        this.members = res.data || []
      }
    },

    // ---------------------------------------------------------------- 选中会话

    async select(sessionId) {
      const sid = String(sessionId)
      if (!sid) {
        return
      }
      this.activeId = sid

      const session = this.sessions.find((s) => s.sessionId === sid)
      const isGroup = session && Number(session.sessionType) === 2

      if (!this.messages[sid]) {
        await this.loadLatest(sid)
      }
      if (isGroup) {
        await this.loadMembers(sid)
      } else {
        this.members = []
      }
      await this.markRead(sid)
    },

    async loadLatest(sessionId) {
      const sid = String(sessionId)
      const res = await api.listMessages(sid, { limit: 30 })
      this.messages[sid] = (res.data.items || []).map((m) => this.normalize(m))
      this.hasMore[sid] = !!res.data.hasMore
    },

    async loadMore(sessionId) {
      const sid = String(sessionId)
      const list = this.messages[sid]
      if (!list || list.length === 0 || !this.hasMore[sid]) {
        return 0
      }
      const firstWithSeq = list.find((m) => m.seq !== null)
      if (!firstWithSeq) {
        return 0
      }
      const res = await api.listMessages(sid, { beforeSeq: firstWithSeq.seq, limit: 30 })
      const older = (res.data.items || []).map((m) => this.normalize(m))
      this.messages[sid] = [...older, ...list]
      this.hasMore[sid] = !!res.data.hasMore
      return older.length
    },

    // ---------------------------------------------------------------- 发送

    send(content) {
      const text = (content || '').trim()
      const sid = this.activeId
      if (!text || !sid) {
        return
      }
      const me = useUserStore()
      const clientMsgId = genClientMsgId()

      const local = {
        id: null,
        sessionId: sid,
        seq: null,
        senderId: me.userId,
        clientMsgId,
        content: text,
        createdAt: new Date().toISOString(),
        status: 'sending',
        mine: true
      }
      if (!this.messages[sid]) {
        this.messages[sid] = []
      }
      this.messages[sid].push(local)
      this.outbox[clientMsgId] = { sessionId: sid }


      const ok = stomp.sendMessage({ sessionId: sid, clientMsgId, content: text })
      if (!ok) {
        // 连接断了还让消息停在「发送中」，用户会一直等一个不会来的回执。
        // 直接标失败，并把重试按钮给他。
        local.status = 'failed'
        local.error = '连接已断开，请重试'
        delete this.outbox[clientMsgId]
      } else {
        // 帧交出去了，但「交出去」不等于「对方收到」，必须挂一个超时兜底
        this.armSendTimeout(clientMsgId)
      }
    },

    /** 重试一条发送失败的消息。沿用原 clientMsgId，服务端会按幂等规则处理。 */
    retry(message) {
      if (!message || message.status !== 'failed' || !message.clientMsgId) {
        return
      }
      message.status = 'sending'
      message.error = null
      this.outbox[message.clientMsgId] = { sessionId: message.sessionId }
      const ok = stomp.sendMessage({
        sessionId: message.sessionId,
        clientMsgId: message.clientMsgId,
        content: message.content
      })
      if (!ok) {
        message.status = 'failed'
        message.error = '连接已断开，请重试'
        delete this.outbox[message.clientMsgId]
      } else {
        this.armSendTimeout(message.clientMsgId)
      }
    },

    /**
     * 给一条「发送中」的消息挂上超时。ack 到了由 {@link disarmSendTimeout} 取消。
     *
     * 判定为超时后走 `markSendFailed` 而不是直接删消息：用户需要看到「这条没发出去」，
     * 并且能点重试。静默删除是最坏的选择——用户以为发出去了，对方永远收不到。
     */
    armSendTimeout(clientMsgId) {
      this.disarmSendTimeout(clientMsgId)
      sendTimers.set(clientMsgId, setTimeout(() => {
        sendTimers.delete(clientMsgId)
        this.markSendFailed(clientMsgId, '发送超时，请重试')
      }, SEND_TIMEOUT_MS))
    },

    disarmSendTimeout(clientMsgId) {
      const handle = sendTimers.get(clientMsgId)
      if (handle !== undefined) {
        clearTimeout(handle)
        sendTimers.delete(clientMsgId)
      }
    },

    /** 把一条仍在 `sending` 的消息标成失败。已经是 sent / failed 的不动，避免覆盖真实结果。 */
    markSendFailed(clientMsgId, reason) {
      const meta = this.outbox[clientMsgId]
      const sid = meta ? meta.sessionId : this.activeId
      const list = sid ? this.messages[sid] : null
      if (!list) {
        return
      }
      const message = list.find((m) => m.clientMsgId === clientMsgId)
      if (!message || message.status !== 'sending') {
        return
      }
      message.status = 'failed'
      message.error = reason
      delete this.outbox[clientMsgId]
    },

    // ---------------------------------------------------------------- 收消息

    handleIncoming(view) {
      if (!view || !view.sessionId) {
        return
      }
      const sid = String(view.sessionId)
      const message = this.normalize(view)
      const me = useUserStore()

      // 会话摘要与排序：无论消息是否属于当前打开的会话都要更新，
      // 否则左侧列表的最后一条摘要会停在旧内容上。
      const session = this.sessions.find((s) => s.sessionId === sid)
      if (session) {
        session.lastMsgSeq = message.seq
        session.lastMsgAt = message.createdAt
        session.lastMsgPreview = message.content
      } else {
        // 收到一个本地列表里没有的会话（别人刚建群并把我拉进来），补一次列表
        this.loadSessions().catch(() => {})
      }

      if (this.messages[sid]) {
        this.mergeMessage(sid, message)
      }

      const isMine = String(message.senderId) === me.userId
      if (sid === this.activeId) {
        // 正在看这个会话：直接置已读，不亮红点
        this.markRead(sid)
      } else if (!isMine && session) {
        session.unreadCount = (Number(session.unreadCount) || 0) + 1
      }

      this.sortSessions()
    },

    /**
     * 合并一条服务端消息。
     *
     * 三种情况：已经存在（按 id 命中）→ 忽略；本地有同 clientMsgId 的乐观消息 → 认领；
     * 都没有 → 追加。认领这一步是多端能对上的关键：自己发的消息会从服务端回推一次，
     * 那次推送带的 seq 才是真的。
     */
    mergeMessage(sessionId, message) {
      const list = this.messages[sessionId]
      if (!list) {
        return
      }
      if (message.id && list.some((m) => m.id && m.id === message.id)) {
        return
      }
      if (message.clientMsgId) {
        const local = list.find((m) => m.clientMsgId === message.clientMsgId && m.seq === null)
        if (local) {
          Object.assign(local, {
            id: message.id,
            seq: message.seq,
            createdAt: message.createdAt,
            status: 'sent',
            error: null
          })
          list.sort(bySeq)
          return
        }
      }
      // 按 seq 去重：重连补齐时可能拉到已经在列表里的那条（id 为 null 的老数据不会命中上面的判断）
      if (message.seq !== null && list.some((m) => m.seq === message.seq)) {
        return
      }
      list.push(message)
      list.sort(bySeq)
    },

    handleAck(ack) {
      if (!ack || !ack.clientMsgId) {
        return
      }
      // 回执一到就撤掉超时兜底：哪怕后面因为会话被切走而提前 return，
      // 也不该再让 8 秒后的定时器把一条已经成功的消息改成「失败」。
      this.disarmSendTimeout(ack.clientMsgId)
      const meta = this.outbox[ack.clientMsgId]
      const sid = meta ? meta.sessionId : this.activeId
      const list = sid ? this.messages[sid] : null
      if (!list) {
        return
      }
      const message = list.find((m) => m.clientMsgId === ack.clientMsgId)
      if (!message) {
        return
      }
      if (ack.success) {
        message.id = ack.messageId ? String(ack.messageId) : message.id
        message.seq = ack.seq === null || ack.seq === undefined ? null : Number(ack.seq)
        message.createdAt = ack.createdAt || message.createdAt
        message.status = 'sent'
        message.error = null
        list.sort(bySeq)
        // 已发送的消息如果属于当前会话，顺手把会话摘要更新掉
        const session = this.sessions.find((s) => s.sessionId === sid)
        if (session) {
          session.lastMsgSeq = message.seq
          session.lastMsgAt = message.createdAt
          session.lastMsgPreview = message.content
        }
      } else {
        message.status = 'failed'
        message.error = ack.error || '发送失败'
      }
      delete this.outbox[ack.clientMsgId]
    },

    /** 已读事件：同一账号的其他端上报已读后，本端一起清红点。 */
    handleReadEvent(event) {
      if (!event || !event.sessionId) {
        return
      }
      const sid = String(event.sessionId)
      const session = this.sessions.find((s) => s.sessionId === sid)
      if (session) {
        session.unreadCount = Number(event.unreadCount) || 0
      }
    },

    async markRead(sessionId) {
      const sid = String(sessionId)
      const list = this.messages[sid]
      if (!list || list.length === 0) {
        return
      }
      const last = [...list].reverse().find((m) => m.seq !== null && m.status === 'sent')
      if (!last) {
        return
      }
      const session = this.sessions.find((s) => s.sessionId === sid)
      // 已经读到最新且没有未读，就不必再打一次接口。
      // 这里不做「每秒一次」的自动上报：已读是用户动作的结果，不是定时任务的结果。
      if (session
        && Number(session.lastMsgSeq || 0) <= Number(last.seq)
        && Number(session.unreadCount || 0) === 0) {
        return
      }
      try {
        const res = await api.markRead(sid, last.seq)
        if (session) {
          session.unreadCount = Number(res.data.unreadCount) || 0
        }
      } catch (e) {
        // 已读上报失败不提示用户：它不影响消息收发，下次切会话会再报一次
        console.warn('[chat] 已读上报失败', e)
      }
    },

    sortSessions() {
      this.sessions.sort((a, b) => {
        const ta = a.lastMsgAt ? new Date(a.lastMsgAt).getTime() : 0
        const tb = b.lastMsgAt ? new Date(b.lastMsgAt).getTime() : 0
        if (ta !== tb) return tb - ta
        return Number(b.sessionId) - Number(a.sessionId)
      })
    },

    normalize(view) {
      const me = useUserStore()
      return {
        id: toStr(view.id),
        sessionId: String(view.sessionId),
        seq: view.seq === null || view.seq === undefined ? null : Number(view.seq),
        senderId: toStr(view.senderId),
        clientMsgId: view.clientMsgId || null,
        content: view.content,
        createdAt: view.createdAt,
        status: 'sent',
        mine: String(view.senderId) === me.userId
      }
    },

    reset() {
      this.sessions = []
      this.activeId = null
      this.messages = {}
      this.hasMore = {}
      this.members = []
      this.outbox = {}
      this.groupTopics = {}
      this.initialized = false
      this.connection = 'idle'
      this.lastConnection = 'idle'
      // 登出时把还在计时的超时全部撤掉：切账号后再触发，会把新账号的消息改错。
      sendTimers.forEach((handle) => clearTimeout(handle))
      sendTimers.clear()
      stomp.disconnect()
    }
  }
})
