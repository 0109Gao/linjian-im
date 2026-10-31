import { defineStore } from 'pinia'
import { TOKEN_KEY, USER_KEY } from '../api/request'

function readUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch (e) {
    return null
  }
}

export const useUserStore = defineStore('user', {
  state: () => ({
    // token 放进 state 而不是只在 localStorage：Pinia 的 getter 底层是 computed，
    // 直接读 localStorage 会把结果永久缓存，登出后界面不会变。
    token: localStorage.getItem(TOKEN_KEY),
    user: readUser()
  }),
  getters: {
    isLogin: (state) => !!state.token,
    userId: (state) => state.user?.userId || null,
    displayName: (state) => state.user?.nickname || state.user?.username || '未登录',
    avatarText: (state) => {
      const name = state.user?.nickname || state.user?.username || ''
      return name ? name.charAt(0).toUpperCase() : 'U'
    }
  },
  actions: {
    setLogin(data) {
      const user = {
        // userId 在后端是字符串，前端一律按字符串保存与比较。
        // 雪花 ID 超出 JS 安全整数范围，任何一次 Number() 都会静默丢精度。
        userId: String(data.userId),
        username: data.username,
        nickname: data.nickname,
        role: data.role
      }
      localStorage.setItem(TOKEN_KEY, data.accessToken)
      localStorage.setItem(USER_KEY, JSON.stringify(user))
      this.token = data.accessToken
      this.user = user
    },
    clear() {
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
      this.token = null
      this.user = null
    }
  }
})
