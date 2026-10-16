<script setup>
/**
 * 文档中心：拖拽上传 + 文档列表 + 重建索引 + 删除 + 分块预览
 *
 * 状态轮询：文档处于 PENDING/PARSING/CHUNKING/EMBEDDING 时，每 3 秒自动刷新一次列表，
 * 直到所有文档进入 INDEXED 或 FAILED 终态后停止轮询；组件卸载时清理定时器。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listDocuments,
  uploadDocument,
  reindexDocument,
  deleteDocument,
  listDocumentChunks
} from '@/api/doc'
import { listKnowledgeBases } from '@/api/kb'
import { docStatusLabel, docStatusType, isDocPending } from '@/utils/constants'
import { formatDateTime, formatFileSize, formatNumber } from '@/utils/format'

const route = useRoute()
const router = useRouter()

// 知识库选项与当前选中项
const kbOptions = ref([])
const currentKbId = ref(null)

// 列表状态
const loading = ref(false)
const docList = ref([])

// 上传相关
const uploadRef = ref(null)
const uploadVisible = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)
const uploadForm = reactive({ kbId: null, file: null })

// 分块预览弹窗
const chunkVisible = ref(false)
const chunkLoading = ref(false)
const chunkList = ref([])
const chunkDocName = ref('')

// 轮询定时器
let pollTimer = null

/** 当前知识库对象 */
const currentKb = computed(() => kbOptions.value.find((kb) => kb.id === currentKbId.value) || null)

/** 是否存在处理中的文档 */
const hasPendingDoc = computed(() => docList.value.some((doc) => isDocPending(doc.status)))

onMounted(async () => {
  await loadKbOptions()
  // 优先使用地址栏传入的 kbId
  const queryKbId = Number(route.query.kbId)
  if (queryKbId && kbOptions.value.some((kb) => kb.id === queryKbId)) {
    currentKbId.value = queryKbId
  } else if (kbOptions.value.length) {
    currentKbId.value = kbOptions.value[0].id
  }
  if (currentKbId.value) {
    await loadDocuments()
  }
})

onBeforeUnmount(() => {
  stopPolling()
})

// 切换知识库时同步到地址栏，方便分享与刷新
watch(currentKbId, (val) => {
  stopPolling()
  docList.value = []
  if (val) {
    router.replace({ path: '/doc', query: { kbId: val } })
    loadDocuments()
  }
})

/** 加载知识库下拉选项 */
async function loadKbOptions () {
  try {
    const data = await listKnowledgeBases()
    kbOptions.value = Array.isArray(data) ? data : []
  } catch (e) {
    kbOptions.value = []
  }
}

/** 加载当前知识库的文档列表 */
async function loadDocuments () {
  if (!currentKbId.value) return
  loading.value = true
  try {
    const data = await listDocuments(currentKbId.value)
    docList.value = Array.isArray(data) ? data : []
    // 根据最新状态决定是否继续轮询
    if (hasPendingDoc.value) {
      startPolling()
    } else {
      stopPolling()
    }
  } catch (e) {
    docList.value = []
  } finally {
    loading.value = false
  }
}

/** 启动状态轮询 */
function startPolling () {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    try {
      const data = await listDocuments(currentKbId.value)
      docList.value = Array.isArray(data) ? data : []
      // 全部处理完成后自动停止轮询
      if (!hasPendingDoc.value) {
        stopPolling()
      }
    } catch (e) {
      // 轮询失败时保持静默，避免频繁弹窗打扰
    }
  }, 3000)
}

/** 停止状态轮询 */
function stopPolling () {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/** 打开上传弹窗 */
function openUpload () {
  if (!currentKbId.value) {
    ElMessage.warning('请先选择知识库')
    return
  }
  uploadForm.kbId = currentKbId.value
  uploadForm.file = null
  uploadProgress.value = 0
  uploadVisible.value = true
  // 清空上一次的选择记录
  if (uploadRef.value) uploadRef.value.clearFiles()
}

/**
 * el-upload 的 http-request 自定义上传
 * 使用接口层封装的 uploadDocument，便于统一处理鉴权与错误提示
 * @param {object} options el-upload 提供的上下文
 */
async function handleUploadRequest (options) {
  const { file } = options
  const kbId = uploadForm.kbId || currentKbId.value
  uploading.value = true
  uploadProgress.value = 0
  try {
    const data = await uploadDocument(file, kbId, (percent) => {
      uploadProgress.value = percent
    })
    ElMessage.success(`《${(data && data.docName) || file.name}》上传成功，正在后台解析`)
    uploadVisible.value = false
    await loadDocuments()
  } catch (e) {
    // 错误提示已统一处理
  } finally {
    uploading.value = false
  }
}

/** 上传前校验文件大小 */
function beforeUpload (file) {
  const maxSize = 50 * 1024 * 1024
  if (file.size > maxSize) {
    ElMessage.error('文件大小不能超过 50MB')
    return false
  }
  return true
}

/**
 * 重建索引
 * @param {object} doc 文档对象
 */
async function handleReindex (doc) {
  try {
    await ElMessageBox.confirm(
      `确定要重建《${doc.docName}》的向量索引吗？重建期间该文档暂时不可被检索。`,
      '重建索引',
      { confirmButtonText: '开始重建', cancelButtonText: '取消', type: 'info' }
    )
  } catch (e) {
    return
  }

  try {
    await reindexDocument(doc.id)
    ElMessage.success('已提交重建任务')
    await loadDocuments()
  } catch (e) {
    // 错误提示已统一处理
  }
}

/**
 * 删除文档
 * @param {object} doc 文档对象
 */
async function handleDelete (doc) {
  try {
    await ElMessageBox.confirm(
      `确定要删除《${doc.docName}》吗？其分块与向量数据将一并删除。`,
      '删除确认',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) {
    return
  }

  try {
    await deleteDocument(doc.id)
    ElMessage.success('删除成功')
    await loadDocuments()
  } catch (e) {
    // 错误提示已统一处理
  }
}

/**
 * 查看分块详情
 * @param {object} doc 文档对象
 */
async function handleViewChunks (doc) {
  chunkDocName.value = doc.docName
  chunkList.value = []
  chunkVisible.value = true
  chunkLoading.value = true
  try {
    const data = await listDocumentChunks(doc.id)
    chunkList.value = Array.isArray(data) ? data : []
  } catch (e) {
    chunkList.value = []
  } finally {
    chunkLoading.value = false
  }
}

/** 手动刷新 */
function handleRefresh () {
  loadDocuments()
}
</script>

<template>
  <div class="doc-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">文档中心</h2>
        <p class="page-desc">
          上传文档后系统会自动完成解析、分块与向量化；处理中的文档每 3 秒自动刷新状态
        </p>
      </div>
      <div class="header-actions">
        <el-select
          v-model="currentKbId"
          placeholder="请选择知识库"
          class="kb-select"
          filterable
        >
          <el-option v-for="kb in kbOptions" :key="kb.id" :label="kb.kbName" :value="kb.id" />
        </el-select>
        <el-button :icon="'Refresh'" @click="handleRefresh">刷新</el-button>
        <el-button type="primary" :disabled="!currentKbId" @click="openUpload">
          <el-icon><UploadFilled /></el-icon>
          上传文档
        </el-button>
      </div>
    </div>

    <!-- 知识库信息条 -->
    <el-alert v-if="currentKb" type="info" :closable="false" class="kb-alert">
      <template #default>
        当前知识库：<b>{{ currentKb.kbName }}</b>
        <span class="alert-sub">
          文档 {{ formatNumber(currentKb.docCount) }} 个 · 分块 {{ formatNumber(currentKb.chunkCount) }} 个 ·
          向量模型 {{ currentKb.embedModel || '-' }}
        </span>
        <el-tag v-if="hasPendingDoc" size="small" type="warning" effect="plain" class="poll-tag">
          有文档正在处理中，列表自动刷新
        </el-tag>
      </template>
    </el-alert>

    <div class="table-card">
      <el-table
        v-loading="loading"
        :data="docList"
        border
        stripe
        empty-text="该知识库下还没有文档"
        style="width: 100%"
      >
        <el-table-column type="index" label="#" width="55" align="center" />
        <el-table-column prop="docName" label="文档名称" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <el-icon class="doc-icon"><Document /></el-icon>
            <span>{{ row.docName }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="docType" label="类型" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ (row.docType || '-').toUpperCase() }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="fileSize" label="大小" width="100" align="right">
          <template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column prop="status" label="处理状态" width="130" align="center">
          <template #default="{ row }">
            <el-tooltip
              :disabled="!row.errorMsg"
              :content="row.errorMsg"
              placement="top"
            >
              <el-tag :type="docStatusType(row.status)" size="small">
                {{ docStatusLabel(row.status) }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="chunkCount" label="分块数" width="90" align="center">
          <template #default="{ row }">{{ formatNumber(row.chunkCount) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="上传时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="indexedAt" label="索引完成时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.indexedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="handleViewChunks(row)">
              查看分块
            </el-button>
            <el-button link type="warning" size="small" @click="handleReindex(row)">
              重建索引
            </el-button>
            <el-button link type="danger" size="small" @click="handleDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 上传弹窗 -->
    <el-dialog v-model="uploadVisible" title="上传文档" width="560px">
      <el-upload
        ref="uploadRef"
        drag
        :limit="1"
        :auto-upload="true"
        :show-file-list="false"
        :http-request="handleUploadRequest"
        :before-upload="beforeUpload"
        accept=".txt,.md,.pdf,.docx,.doc,.html,.csv,.json"
      >
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="upload-text">将文件拖到此处，或<em>点击选择文件</em></div>
        <template #tip>
          <div class="upload-tip">
            支持 txt / md / pdf / docx / html / csv / json，单个文件不超过 50MB
          </div>
        </template>
      </el-upload>

      <el-progress
        v-if="uploading"
        :percentage="uploadProgress"
        :stroke-width="12"
        text-inside
        class="upload-progress"
      />

      <el-alert
        v-if="uploading"
        type="info"
        :closable="false"
        class="mt-8"
        title="文件上传完成后，后端会在后台异步完成解析、分块与向量化，请稍候查看状态"
        show-icon
      />
    </el-dialog>

    <!-- 分块预览弹窗 -->
    <el-dialog v-model="chunkVisible" :title="`分块预览 - ${chunkDocName}`" width="760px">
      <div v-loading="chunkLoading" class="chunk-container">
        <el-empty v-if="!chunkList.length && !chunkLoading" description="暂无分块数据" />
        <div v-for="chunk in chunkList" :key="chunk.chunkIndex" class="chunk-item">
          <div class="chunk-header">
            <el-tag size="small" type="primary" effect="plain">#{{ chunk.chunkIndex }}</el-tag>
            <el-tag v-if="chunk.pageNo !== null && chunk.pageNo !== undefined" size="small" type="info" effect="plain">
              第 {{ chunk.pageNo }} 页
            </el-tag>
            <el-tag size="small" type="warning" effect="plain">
              {{ chunk.tokenCount || 0 }} tokens
            </el-tag>
          </div>
          <div class="chunk-content">{{ chunk.content }}</div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.kb-select {
  width: 200px;
}

.kb-alert {
  margin-bottom: 12px;
}

.alert-sub {
  margin-left: 12px;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.poll-tag {
  margin-left: 12px;
}

.table-card {
  padding: 12px;
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 6px;
}

.doc-icon {
  margin-right: 4px;
  color: #409eff;
  vertical-align: -2px;
}

.upload-icon {
  font-size: 48px;
  color: #c0c4cc;
}

.upload-text {
  font-size: 14px;
  color: #606266;
}

.upload-text em {
  color: #409eff;
  font-style: normal;
}

.upload-tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.upload-progress {
  margin-top: 16px;
}

.chunk-container {
  max-height: 520px;
  overflow-y: auto;
}

.chunk-item {
  padding: 10px 12px;
  margin-bottom: 10px;
  border: 1px solid var(--app-border);
  border-radius: 4px;
  background: #fafbfc;
}

.chunk-header {
  display: flex;
  gap: 6px;
  margin-bottom: 8px;
}

.chunk-content {
  font-size: 13px;
  line-height: 1.7;
  color: #606266;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
