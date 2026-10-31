import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const TOKEN_KEY = 'im_admin_token'
const USER_KEY = 'im_admin_user'

const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = 'Bearer ' + token
  }
  return config
})

function clearAndGoLogin(message) {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  if (router.currentRoute.value.path !== '/login') {
    ElMessage.error(message || '登录已失效，请重新登录')
    router.push('/login')
  }
}

/**
 * 管理端把三种失败分开处理，因为这个端最常遇到的恰恰是后两种：
 *   401 / 4010  未登录或 token 过期 → 跳登录
 *   403 / 4030  登录了但不是管理员 → 提示并跳登录，不能留在后台空转
 *   403 / 4113  账号被禁用 → 提示原因后跳登录
 */
request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code === 0) {
      return res
    }
    ElMessage.error(res.message || '请求失败')
    return Promise.reject(new Error(res.message || '请求失败'))
  },
  (error) => {
    const { response } = error
    if (!response) {
      ElMessage.error('网络异常，请检查后端服务是否已启动')
      return Promise.reject(error)
    }
    const body = response.data || {}
    if (response.status === 401) {
      clearAndGoLogin(body.message)
    } else if (response.status === 403) {
      if (body.code === 4030) {
        clearAndGoLogin('该账号没有管理员权限')
      } else if (body.code === 4113) {
        clearAndGoLogin(body.message || '账号已被禁用')
      } else {
        ElMessage.error(body.message || '无权限访问')
      }
    } else {
      ElMessage.error(body.message || '请求失败')
    }
    return Promise.reject(error)
  }
)

export { TOKEN_KEY, USER_KEY }
export default request
