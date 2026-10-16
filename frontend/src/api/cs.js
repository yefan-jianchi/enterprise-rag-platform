import { get, post, postStream } from './request'
import { streamSse } from '@/utils/sse'

/**
 * 智能客服接口（会走后端 Tool Calling）
 */

/**
 * 客服流式对话（SSE over POST）
 * @param {object} payload { sessionUuid, question, providerCode }
 * @param {object} handlers 事件回调，见 utils/sse.js
 * @returns {Promise<void>}
 */
export function streamCustomerService (payload, handlers) {
  return streamSse(
    (signal) => postStream('/cs/stream', payload, { signal }),
    handlers
  )
}

/** 订单查询 -> [{orderNo,customerName,productName,amount,orderStatus,logisticsNo,createdAt}] */
export function listOrders (keyword) {
  return get('/cs/orders', { keyword })
}

/** 工单列表 -> [{ticketNo,title,content,priority,status,assignee,createdAt}] */
export function listTickets () {
  return get('/cs/tickets')
}

/** 转人工 */
export function handoffToHuman (sessionUuid, reason) {
  return post('/cs/handoff', { sessionUuid, reason })
}
