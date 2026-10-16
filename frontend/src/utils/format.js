/**
 * 日期、体积、百分比等展示格式化工具
 */

/**
 * 格式化时间字符串为「YYYY-MM-DD HH:mm」
 * @param {string|number|Date} value 时间
 * @returns {string} 格式化结果，空值返回 '-'
 */
export function formatDateTime (value) {
  if (!value) return '-'
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * 只保留日期部分「YYYY-MM-DD」
 * @param {string|number|Date} value 时间
 */
export function formatDate (value) {
  if (!value) return '-'
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/**
 * 格式化文件体积（B / KB / MB / GB）
 * @param {number} bytes 字节数
 */
export function formatFileSize (bytes) {
  if (bytes === null || bytes === undefined || bytes === '') return '-'
  const size = Number(bytes)
  if (Number.isNaN(size)) return '-'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  if (size < 1024 * 1024 * 1024) return `${(size / 1024 / 1024).toFixed(2)} MB`
  return `${(size / 1024 / 1024 / 1024).toFixed(2)} GB`
}

/**
 * 格式化耗时（毫秒 -> 可读文本）
 * @param {number} ms 毫秒
 */
export function formatDuration (ms) {
  if (ms === null || ms === undefined || ms === '') return '-'
  const value = Number(ms)
  if (Number.isNaN(value)) return '-'
  if (value < 1000) return `${Math.round(value)} ms`
  return `${(value / 1000).toFixed(2)} s`
}

/**
 * 格式化金额（默认保留 4 位小数，用于 token 成本这类很小的数值）
 * @param {number} amount 金额
 * @param {number} digits 小数位
 */
export function formatMoney (amount, digits = 4) {
  if (amount === null || amount === undefined || amount === '') return '0.0000'
  const value = Number(amount)
  if (Number.isNaN(value)) return '0.0000'
  return value.toFixed(digits)
}

/**
 * 大数字千分位格式化
 * @param {number} num 数字
 */
export function formatNumber (num) {
  if (num === null || num === undefined || num === '') return '0'
  const value = Number(num)
  if (Number.isNaN(value)) return '0'
  return value.toLocaleString('zh-CN')
}

/**
 * 截断过长文本，超出部分用省略号代替
 * @param {string} text 原始文本
 * @param {number} maxLength 最大长度
 */
export function truncate (text, maxLength = 80) {
  if (!text) return ''
  const str = String(text)
  return str.length > maxLength ? `${str.slice(0, maxLength)}…` : str
}
