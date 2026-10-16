import { get, put } from './request'

/**
 * 管理后台接口：模型配置、审计日志、成本统计、提示词模板
 */

/** 模型提供方列表 -> [{id,providerCode,providerName,baseUrl,chatModel,embedModel,priority,enabled,hasApiKey}] */
export function listModels () {
  return get('/admin/models')
}

/** 更新模型配置：{enabled, priority, chatModel, apiKey} */
export function updateModel (id, data) {
  return put(`/admin/models/${id}`, data)
}

/** 审计日志（分页）-> {total, records:[...]} */
export function listAuditLogs (page = 1, size = 10) {
  return get('/admin/audit', { page, size })
}

/** 成本统计 -> {totalTokens,totalCost,byDay:[...],byModel:[...]} */
export function getCostStatistics (days = 7) {
  return get('/admin/cost', { days })
}

/** 提示词模板列表 -> [{id,templateCode,templateName,content,enabled}] */
export function listPrompts () {
  return get('/admin/prompts')
}

/** 更新提示词模板：{content, enabled} */
export function updatePrompt (id, data) {
  return put(`/admin/prompts/${id}`, data)
}
