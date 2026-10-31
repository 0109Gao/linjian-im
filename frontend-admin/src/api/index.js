import request from './request'

/**
 * 管理端接口。全部在 /api/admin 前缀下，后端对该前缀统一要求 role=1。
 * 前端这里的路由守卫只影响体验，真正的拦截在服务端。
 */

export const login = (data) => request.post('/auth/login', data)

export const stats = () => request.get('/admin/stats')

export const users = (params) => request.get('/admin/users', { params })

export const updateUserStatus = (userId, status) =>
  request.put(`/admin/users/${userId}/status`, { status })

export const sessions = (params) => request.get('/admin/sessions', { params })

export const sessionMessages = (sessionId, params) =>
  request.get(`/admin/sessions/${sessionId}/messages`, { params })

export const onlineUsers = () => request.get('/admin/online')
