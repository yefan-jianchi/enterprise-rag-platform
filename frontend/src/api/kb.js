import { get, post, del } from './request'

/**
 * 知识库接口
 */

/** 知识库列表 -> [{id,kbName,description,embedModel,chunkSize,topK,docCount,chunkCount,createdAt}] */
export function listKnowledgeBases () {
  return get('/kb/list')
}

/** 新建知识库 -> 新建后的知识库对象 */
export function createKnowledgeBase (data) {
  // data: { kbName, description }
  return post('/kb', data)
}

/** 删除知识库 */
export function deleteKnowledgeBase (id) {
  return del(`/kb/${id}`)
}
