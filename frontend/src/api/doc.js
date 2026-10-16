import { get, post, del, upload } from './request'

/**
 * 文档接口
 */

/**
 * 上传文档（multipart/form-data）
 * @param {File} file 文件对象
 * @param {number|string} kbId 所属知识库 ID
 * @param {Function} [onProgress] 上传进度回调 0-100
 * @returns -> { docId, docName, status }
 */
export function uploadDocument (file, kbId, onProgress) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('kbId', kbId)
  return upload('/doc/upload', formData, onProgress)
}

/** 文档列表 -> [{id,kbId,docName,docType,fileSize,status,chunkCount,errorMsg,createdAt,indexedAt}] */
export function listDocuments (kbId) {
  return get('/doc/list', { kbId })
}

/** 重建索引 */
export function reindexDocument (id) {
  return post(`/doc/${id}/reindex`)
}

/** 删除文档 */
export function deleteDocument (id) {
  return del(`/doc/${id}`)
}

/** 查看文档分块 -> [{chunkIndex,content,pageNo,tokenCount}] */
export function listDocumentChunks (id) {
  return get(`/doc/${id}/chunks`)
}
