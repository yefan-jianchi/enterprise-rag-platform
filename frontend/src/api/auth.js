import { get, post } from './request'

/**
 * 认证相关接口
 */

/** 登录：{tenantCode, username, password} -> {token, user} */
export function login (data) {
  return post('/auth/login', data)
}

/** 获取当前登录用户信息 -> user 对象 */
export function getCurrentUser () {
  return get('/auth/me')
}
