import request from './request'

/**
 * REST 接口封装。
 *
 * 实时收发走 STOMP（见 store/chat.js），这里只放「初始化 / 补偿」性质的调用：
 * 登录、找人、拉历史、报已读、群成员管理。这些操作都必须能被重试，
 * 因此用 HTTP 状态码表达结果，而不是发出去就不管。
 */

export const login = (data) => request.post('/auth/login', data)

export const register = (data) => request.post('/auth/register', data)

/** 按用户名或昵称找人。没有这个接口就无法发起第一个会话。 */
export const searchUsers = (q) => request.get('/users/search', { params: { q } })

/** 单聊懒创建：不存在就建，已存在返回同一个 sessionId。 */
export const openSession = (peerUserId) => request.post('/sessions/open', { peerUserId })

export const listSessions = (page = 1, size = 50) =>
  request.get('/sessions', { params: { page, size } })

/**
 * 拉消息。三种方向互斥：
 *   { afterSeq }  增量补齐（断线重连后用）
 *   { beforeSeq } 向上翻历史
 *   {}            最近一页
 */
export const listMessages = (sessionId, params = {}) =>
  request.get(`/sessions/${sessionId}/messages`, { params })

export const markRead = (sessionId, lastReadSeq) =>
  request.post(`/sessions/${sessionId}/read`, { lastReadSeq })

export const createGroup = (name, memberIds) =>
  request.post('/sessions/group', { name, memberIds })

export const addMembers = (sessionId, userIds) =>
  request.post(`/sessions/${sessionId}/members`, { userIds })

export const leaveGroup = (sessionId) =>
  request.delete(`/sessions/${sessionId}/members/me`)

export const listMembers = (sessionId) =>
  request.get(`/sessions/${sessionId}/members`)
