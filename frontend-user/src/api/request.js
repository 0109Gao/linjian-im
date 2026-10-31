import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const TOKEN_KEY = 'im_token'
const USER_KEY = 'im_user'

const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = 'Bearer ' + token
  }
  return config
})

/**
 * 统一处理 401 / 403。
 *
 * 后端把两种 403 分开了，前端必须跟着分：
 *   - 4113 账号被禁用：清凭证并提示原因，跳登录页
 *   - 4030 无权限：只提示，不清凭证（管理员权限不足不代表登录失效）
 *
 * 如果这里把 403 一律当成登录失效，管理员登录后访问一次普通接口就会
 * 被踢出去，表现为「登录成功、立刻掉线」。
 */
function clearAndGoLogin(message) {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  if (router.currentRoute.value.path !== '/login') {
    ElMessage.error(message || '登录已失效，请重新登录')
    router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  }
}

request.interceptors.response.use(
  (response) => {
    const res = response.data
    // 业务错误码统一走这里：HTTP 200 但 code != 0
    if (res.code === 0) {
      return res
    }
    ElMessage.error(res.message || '请求失败')
    return Promise.reject(new Error(res.message || '请求失败'))
  },
  (error) => {
    const { response } = error
    if (!response) {
      ElMessage.error('网络异常，请检查服务是否已启动')
      return Promise.reject(error)
    }

    const body = response.data || {}
    if (response.status === 401) {
      clearAndGoLogin(body.message)
    } else if (response.status === 403) {
      if (body.code === 4113) {
        clearAndGoLogin(body.message || '账号已被禁用')
      } else {
        ElMessage.error(body.message || '无权限访问')
      }
    } else if (response.status === 429) {
      ElMessage.warning('操作过于频繁，请稍后再试')
    } else {
      ElMessage.error(body.message || '请求失败')
    }
    return Promise.reject(error)
  }
)

export { TOKEN_KEY, USER_KEY }
export default request
