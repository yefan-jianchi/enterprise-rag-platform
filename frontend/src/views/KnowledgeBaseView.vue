<script setup>
/**
 * 知识库管理：卡片列表 + 新建 + 删除
 * 点击「管理文档」跳转到文档中心并携带 kbId
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listKnowledgeBases, createKnowledgeBase, deleteKnowledgeBase } from '@/api/kb'
import { formatDateTime, formatNumber } from '@/utils/format'

const router = useRouter()

const loading = ref(false)
const kbList = ref([])

// 新建知识库弹窗
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref(null)
const form = reactive({
  kbName: '',
  description: ''
})

// 表单校验规则
const rules = {
  kbName: [
    { required: true, message: '请输入知识库名称', trigger: 'blur' },
    { min: 2, max: 50, message: '名称长度需在 2 到 50 个字符之间', trigger: 'blur' }
  ],
  description: [{ max: 200, message: '描述长度不能超过 200 个字符', trigger: 'blur' }]
}

onMounted(loadList)

/** 加载知识库列表 */
async function loadList () {
  loading.value = true
  try {
    const data = await listKnowledgeBases()
    kbList.value = Array.isArray(data) ? data : []
  } catch (e) {
    kbList.value = []
  } finally {
    loading.value = false
  }
}

/** 打开新建弹窗并重置表单 */
function openDialog () {
  form.kbName = ''
  form.description = ''
  dialogVisible.value = true
}

/** 提交新建 */
async function handleSubmit () {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch (e) {
    return
  }

  submitting.value = true
  try {
    await createKnowledgeBase({
      kbName: form.kbName.trim(),
      description: form.description.trim()
    })
    ElMessage.success('知识库创建成功')
    dialogVisible.value = false
    await loadList()
  } catch (e) {
    // 错误提示已统一处理
  } finally {
    submitting.value = false
  }
}

/**
 * 删除知识库
 * @param {object} kb 知识库对象
 */
async function handleDelete (kb) {
  try {
    await ElMessageBox.confirm(
      `确定要删除知识库「${kb.kbName}」吗？其下的文档与向量索引将一并删除，且不可恢复。`,
      '删除确认',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) {
    return
  }

  try {
    await deleteKnowledgeBase(kb.id)
    ElMessage.success('删除成功')
    await loadList()
  } catch (e) {
    // 错误提示已统一处理
  }
}

/**
 * 跳转到文档中心
 * @param {object} kb 知识库对象
 */
function goDocuments (kb) {
  router.push({ path: '/doc', query: { kbId: kb.id } })
}
</script>

<template>
  <div class="kb-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">知识库管理</h2>
        <p class="page-desc">管理企业知识库，配置向量化模型与检索参数，并为每个知识库维护文档</p>
      </div>
      <el-button type="primary" @click="openDialog">
        <el-icon><Plus /></el-icon>
        新建知识库
      </el-button>
    </div>

    <div v-loading="loading" class="kb-content">
      <el-empty
        v-if="!kbList.length && !loading"
        description="还没有知识库，点击右上角新建一个吧"
      />

      <div v-else class="kb-grid">
        <el-card v-for="kb in kbList" :key="kb.id" shadow="hover" class="kb-card">
          <div class="kb-card-header">
            <el-icon class="kb-icon"><Collection /></el-icon>
            <span class="kb-name text-ellipsis" :title="kb.kbName">{{ kb.kbName }}</span>
            <el-dropdown trigger="click" @command="(cmd) => cmd === 'delete' && handleDelete(kb)">
              <el-icon class="kb-more"><MoreFilled /></el-icon>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="delete">
                    <el-icon><Delete /></el-icon>删除知识库
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>

          <p class="kb-desc" :title="kb.description">
            {{ kb.description || '暂无描述' }}
          </p>

          <div class="kb-stats">
            <div class="stat-item">
              <div class="stat-value">{{ formatNumber(kb.docCount) }}</div>
              <div class="stat-label">文档数</div>
            </div>
            <div class="stat-item">
              <div class="stat-value">{{ formatNumber(kb.chunkCount) }}</div>
              <div class="stat-label">分块数</div>
            </div>
            <div class="stat-item">
              <div class="stat-value">{{ kb.topK || '-' }}</div>
              <div class="stat-label">TopK</div>
            </div>
            <div class="stat-item">
              <div class="stat-value">{{ kb.chunkSize || '-' }}</div>
              <div class="stat-label">分块大小</div>
            </div>
          </div>

          <div class="kb-info">
            <el-tag size="small" type="info" effect="plain">
              向量模型：{{ kb.embedModel || '-' }}
            </el-tag>
            <span class="kb-time">{{ formatDateTime(kb.createdAt) }}</span>
          </div>

          <div class="kb-actions">
            <el-button type="primary" plain size="small" @click="goDocuments(kb)">
              <el-icon><Document /></el-icon>
              管理文档
            </el-button>
            <el-button type="danger" plain size="small" @click="handleDelete(kb)">
              <el-icon><Delete /></el-icon>
              删除
            </el-button>
          </div>
        </el-card>
      </div>
    </div>

    <!-- 新建知识库弹窗 -->
    <el-dialog v-model="dialogVisible" title="新建知识库" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="知识库名称" prop="kbName">
          <el-input v-model="form.kbName" placeholder="例如：校园服务指南" maxlength="50" show-word-limit />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            resize="none"
            maxlength="200"
            show-word-limit
            placeholder="简要说明该知识库的用途与数据范围"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
}

.kb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}

.kb-card {
  border-radius: 6px;
}

.kb-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.kb-icon {
  font-size: 18px;
  color: #409eff;
  flex-shrink: 0;
}

.kb-name {
  flex: 1;
  min-width: 0;
  font-size: 15px;
  font-weight: 600;
}

.kb-more {
  cursor: pointer;
  color: #909399;
  outline: none;
}

.kb-more:hover {
  color: #409eff;
}

.kb-desc {
  height: 38px;
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--app-text-secondary);
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.kb-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  padding: 10px 0;
  margin-bottom: 10px;
  background: #fafbfc;
  border-radius: 4px;
  text-align: center;
}

.stat-value {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.stat-label {
  margin-top: 2px;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.kb-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}

.kb-time {
  font-size: 12px;
  color: var(--app-text-secondary);
  white-space: nowrap;
}

.kb-actions {
  display: flex;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--app-border);
}

.kb-actions .el-button {
  flex: 1;
}

.kb-content {
  min-height: 200px;
}
</style>
