import { defineStore } from 'pinia'
import { login as loginApi, getCurrentUser } from '@/api/auth'
import { TOKEN_KEY, USER_KEY } from '@/api/request'

/**
 * 从 localStorage 安全读取用户信息（避免脏数据导致 JSON 解析异常）
 */
function readCachedUser () {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? JSON.parse(raw) : null
  } catch (e) {
    localStorage.removeItem(USER_KEY)
    return null
  }
}

/**
 * 用户状态：token 持久化到 localStorage，刷新页面后仍保持登录态
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    // 登录令牌
    token: localStorage.getItem(TOKEN_KEY) || '',
    // 当前用户信息 {id,username,nickname,tenantId,tenantCode,roles,permissions}
    userInfo: readCachedUser()
  }),

  getters: {
    /** 是否已登录 */
    isLogin: (state) => !!state.token,
    /** 展示名称：优先昵称，其次用户名 */
    displayName: (state) => {
      const u = state.userInfo || {}
      return u.nickname || u.username || '未登录'
    },
    /** 租户编码 */
    tenantCode: (state) => (state.userInfo && state.userInfo.tenantCode) || '-',
    /** 角色列表 */
    roles: (state) => (state.userInfo && state.userInfo.roles) || [],
    /** 权限列表 */
    permissions: (state) => (state.userInfo && state.userInfo.permissions) || []
  },

  actions: {
    /**
     * 登录：成功后保存 token 与用户信息
     * @param {{tenantCode:string, username:string, password:string}} payload 登录表单
     */
    async login (payload) {
      const data = await loginApi(payload)
      this.token = data.token
      this.userInfo = data.user || null
      localStorage.setItem(TOKEN_KEY, this.token)
      localStorage.setItem(USER_KEY, JSON.stringify(this.userInfo || {}))
      return data
    },

    /**
     * 拉取当前登录用户信息（用于刷新页面后校验 token 是否仍然有效）
     */
    async fetchUserInfo () {
      const user = await getCurrentUser()
      this.userInfo = user || null
      localStorage.setItem(USER_KEY, JSON.stringify(user || {}))
      return user
    },

    /** 退出登录：清空内存与本地存储 */
    logout () {
      this.token = ''
      this.userInfo = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    }
  }
})
