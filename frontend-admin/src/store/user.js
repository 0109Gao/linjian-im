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
    token: localStorage.getItem(TOKEN_KEY),
    user: readUser()
  }),
  getters: {
    isLogin: (state) => !!state.token,
    isAdmin: (state) => state.user?.role === 1,
    displayName: (state) => state.user?.nickname || state.user?.username || '未登录'
  },
  actions: {
    setLogin(data) {
      const user = {
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
