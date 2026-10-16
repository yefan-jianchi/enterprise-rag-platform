import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一 axios 封装
 * 约定：后端统一响应包装 { code, message, data }，code === 0 表示成功
 * 职责：
 *   1. 请求拦截：注入 Authorization: Bearer <token>
 *   2. 响应拦截：统一解包 data 字段，code != 0 时抛错并弹出 ElMessage
 *   3. 401 处理：清除本地登录态并跳转登录页
 */

// 登录态在 localStorage 中的键名（与 stores/user.js 保持一致）
export const TOKEN_KEY = 'rag_token'
export const USER_KEY = 'rag_user'

const service = axios.create({
  // 走 vite 代理：/api -> http://localhost:8080
  baseURL: '/api',
  timeout: 30000
})

/**
 * 跳转登录页（避免直接依赖 router 实例造成循环引用）
 */
function redirectToLogin () {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  // 已在登录页时不做重复跳转，防止死循环
  if (!window.location.hash.includes('/login') && !window.location.pathname.includes('/login')) {
    window.location.replace('/login')
  }
}

// ---------------- 请求拦截器 ----------------
service.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem(TOKEN_KEY)
    if (token) {
      config.headers = config.headers || {}
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// ---------------- 响应拦截器 ----------------
service.interceptors.response.use(
  (response) => {
    // 流式接口（SSE）需要拿到原始 Response 交给 sse.js 解析，不做解包
    if (response.config && response.config.streamResponse) {
      return response
    }

    const body = response.data
    // 兼容非包装结构（例如后端直接返回数组）
    if (body === null || body === undefined || typeof body !== 'object' || !('code' in body)) {
      return body
    }

    if (body.code === 0) {
      return body.data
    }

    // 业务失败：401 已在下面统一拦截，这里处理其他错误码
    const msg = body.message || '请求失败'
    ElMessage.error(msg)
    return Promise.reject(new Error(msg))
  },
  (error) => {
    const status = error.response && error.response.status
    const body = error.response && error.response.data
    const bizMsg = body && body.message

    if (status === 401) {
      ElMessage.error(bizMsg || '登录已过期，请重新登录')
      redirectToLogin()
      return Promise.reject(error)
    }

    let msg = bizMsg || error.message || '网络异常'
    if (!bizMsg) {
      if (status === 403) msg = '没有权限访问该资源'
      else if (status === 404) msg = '请求的接口不存在'
      else if (status >= 500) msg = '服务器内部错误，请稍后重试'
      else if (error.code === 'ECONNABORTED') msg = '请求超时，请稍后重试'
    }
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

/** GET 请求 */
export function get (url, params, config = {}) {
  return service.get(url, { params, ...config })
}

/** POST 请求（JSON） */
export function post (url, data, config = {}) {
  return service.post(url, data, config)
}

/** PUT 请求（JSON） */
export function put (url, data, config = {}) {
  return service.put(url, data, config)
}

/** DELETE 请求 */
export function del (url, config = {}) {
  return service.delete(url, config)
}

/**
 * 文件上传（multipart/form-data）
 * @param {string} url 接口地址
 * @param {FormData} formData 表单数据
 * @param {Function} [onProgress] 上传进度回调，参数为 0-100 的百分比
 */
export function upload (url, formData, onProgress) {
  return service.post(url, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000, // 大文件解析较慢，单独放宽超时
    onUploadProgress (e) {
      if (onProgress && e.total) {
        onProgress(Math.round((e.loaded * 100) / e.total))
      }
    }
  })
}

/**
 * 发起需要流式读取（SSE）的 POST 请求，返回原始 Response
 * 之所以不用原生 EventSource：EventSource 只支持 GET，无法携带 JSON body
 * @param {string} url 接口地址（不含 /api 前缀）
 * @param {object} data 请求体
 * @param {object} [options] 透传 axios 配置
 */
export function postStream (url, data, options = {}) {
  return service.post(url, data, {
    ...options,
    streamResponse: true,
    // 流式响应可能持续很久，交给 AbortController 控制，不设 axios 超时
    timeout: 0,
    headers: {
      Accept: 'text/event-stream',
      ...(options.headers || {})
    }
  })
}

export default service
