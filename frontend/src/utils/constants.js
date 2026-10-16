/**
 * 全局常量与字典：文档状态、工单优先级等的中文标签与颜色映射
 */

/**
 * 文档处理状态字典
 * 后端取值：PENDING/PARSING/CHUNKING/EMBEDDING/INDEXED/FAILED
 */
export const DOC_STATUS = {
  PENDING: { label: '待处理', type: 'info' },
  PARSING: { label: '解析中', type: 'warning' },
  CHUNKING: { label: '分块中', type: 'warning' },
  EMBEDDING: { label: '向量化中', type: 'warning' },
  INDEXED: { label: '已索引', type: 'success' },
  FAILED: { label: '失败', type: 'danger' }
}

/** 未完成（处理中或待处理）的状态集合，用于决定是否继续轮询刷新 */
export const PENDING_DOC_STATUS = ['PENDING', 'PARSING', 'CHUNKING', 'EMBEDDING']

/** 获取文档状态的中文标签 */
export function docStatusLabel (status) {
  return (DOC_STATUS[status] && DOC_STATUS[status].label) || status || '未知'
}

/** 获取文档状态对应的 el-tag 类型（颜色） */
export function docStatusType (status) {
  return (DOC_STATUS[status] && DOC_STATUS[status].type) || 'info'
}

/** 判断文档是否仍在处理中 */
export function isDocPending (status) {
  return PENDING_DOC_STATUS.includes(status)
}

/** 工单优先级字典 */
export const TICKET_PRIORITY = {
  LOW: { label: '低', type: 'info' },
  MEDIUM: { label: '中', type: 'primary' },
  HIGH: { label: '高', type: 'warning' },
  URGENT: { label: '紧急', type: 'danger' }
}

/** 工单状态字典 */
export const TICKET_STATUS = {
  OPEN: { label: '待处理', type: 'warning' },
  PROCESSING: { label: '处理中', type: 'primary' },
  RESOLVED: { label: '已解决', type: 'success' },
  CLOSED: { label: '已关闭', type: 'info' }
}

/** 订单状态字典 */
export const ORDER_STATUS = {
  CREATED: { label: '已创建', type: 'info' },
  PAID: { label: '已支付', type: 'primary' },
  SHIPPED: { label: '已发货', type: 'warning' },
  DELIVERED: { label: '已签收', type: 'success' },
  CANCELLED: { label: '已取消', type: 'info' },
  REFUNDING: { label: '退款中', type: 'warning' },
  REFUNDED: { label: '已退款', type: 'danger' }
}

/** 聊天角色字典 */
export const CHAT_ROLE = {
  user: { label: '我' },
  assistant: { label: 'AI 助手' },
  system: { label: '系统' }
}

/** 提示词模板编码的中文名称映射（后端返回 templateCode 时用于友好展示） */
export const PROMPT_TEMPLATE_NAMES = {
  RAG_ANSWER: 'RAG 问答提示词',
  CS_SYSTEM: '客服系统提示词',
  QUERY_REWRITE: '查询改写提示词',
  SUMMARY: '会话摘要提示词'
}

export default {
  DOC_STATUS,
  PENDING_DOC_STATUS,
  TICKET_PRIORITY,
  TICKET_STATUS,
  ORDER_STATUS,
  CHAT_ROLE,
  PROMPT_TEMPLATE_NAMES,
  docStatusLabel,
  docStatusType,
  isDocPending
}
