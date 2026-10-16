import { get, post, postStream } from './request'
import { streamSse } from '@/utils/sse'

/**
 * 对话（RAG 流式问答）接口
 */

/** 会话列表 -> [{sessionUuid,title,kbId,providerCode,scene,messageCount,updatedAt}] */
export function listSessions () {
  return get('/chat/sessions')
}

/** 新建会话 -> { sessionUuid, ... } */
export function createSession (data) {
  // data: { kbId, title, providerCode }
  return post('/chat/session', data)
}

/** 历史消息 -> [{id,role,content,citations:[...],model,latencyMs,createdAt}] */
export function listMessages (sessionUuid) {
  return get('/chat/messages', { sessionUuid })
}

/**
 * 流式对话（SSE over POST）
 * @param {object} payload { sessionUuid, question, kbId, providerCode }
 * @param {object} handlers 事件回调，见 utils/sse.js
 * @returns {Promise<void>} 生成结束后 resolve；用户中断时 reject AbortError
 */
export function streamChat (payload, handlers) {
  return streamSse(
    // signal 由 streamSse 透传，保证点击「停止生成」时底层请求真正被中断
    (signal) => postStream('/chat/stream', payload, { signal }),
    handlers
  )
}
