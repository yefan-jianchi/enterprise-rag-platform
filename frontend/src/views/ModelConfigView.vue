<script setup>
/**
 * 模型配置：多模型提供方管理
 * 支持启用/停用、调整优先级、修改对话模型名、填写 API Key
 *
 * 说明：出于安全考虑后端不会回传明文 API Key，只返回 hasApiKey 标识；
 * 因此 API Key 输入框留空表示「不修改」，输入新值才会覆盖。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listModels, updateModel } from '@/api/admin'

const loading = ref(false)
const saving = ref(false)
const modelList = ref([])

// 编辑弹窗
const dialogVisible = ref(false)
const editingId = ref(null)
const form = reactive({
  providerCode: '',
  providerName: '',
  baseUrl: '',
  enabled: true,
  priority: 0,
  chatModel: '',
  apiKey: ''
})

/** 已启用的模型数量 */
const enabledCount = computed(() => modelList.value.filter((m) => m.enabled).length)

/** 已配置密钥的数量 */
const keyCount = computed(() => modelList.value.filter((m) => m.hasApiKey).length)

onMounted(loadList)

/** 加载模型列表 */
async function loadList () {
  loading.value = true
  try {
    const data = await listModels()
    modelList.value = Array.isArray(data) ? data : []
  } catch (e) {
    modelList.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 直接切换启用状态
 * @param {object} row 模型行数据
 */
async function handleToggleEnabled (row) {
  try {
    await updateModel(row.id, {
      enabled: row.enabled,
      priority: row.priority,
      chatModel: row.chatModel,
      // 不传 apiKey 表示不修改密钥
      apiKey: ''
    })
    ElMessage.success(row.enabled ? `${row.providerName} 已启用` : `${row.providerName} 已停用`)
  } catch (e) {
    // 保存失败时回滚开关状态
    row.enabled = !row.enabled
  }
}

/**
 * 打开编辑弹窗
 * @param {object} row 模型行数据
 */
function openEdit (row) {
  editingId.value = row.id
  form.providerCode = row.providerCode
  form.providerName = row.providerName
  form.baseUrl = row.baseUrl
  form.enabled = row.enabled
  form.priority = row.priority
  form.chatModel = row.chatModel
  form.apiKey = ''
  dialogVisible.value = true
}

/** 保存编辑 */
async function handleSave () {
  if (!form.chatModel || !form.chatModel.trim()) {
    ElMessage.warning('请填写对话模型名称')
    return
  }
  saving.value = true
  try {
    await updateModel(editingId.value, {
      enabled: form.enabled,
      priority: Number(form.priority) || 0,
      chatModel: form.chatModel.trim(),
      apiKey: form.apiKey.trim()
    })
    ElMessage.success('保存成功')
    dialogVisible.value = false
    await loadList()
  } catch (e) {
    // 错误提示已统一处理
  } finally {
    saving.value = false
  }
}

/** 刷新列表 */
function handleRefresh () {
  loadList()
}
</script>

<template>
  <div class="model-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">模型配置</h2>
        <p class="page-desc">
          管理多模型提供方的接入参数。优先级数值越大越优先被选用；启用状态决定该提供方是否参与调度
        </p>
      </div>
      <el-button :icon="'Refresh'" @click="handleRefresh">刷新</el-button>
    </div>

    <el-row :gutter="16" class="summary-row">
      <el-col :span="8">
        <el-card shadow="hover" class="summary-card">
          <div class="summary-label">接入提供方</div>
          <div class="summary-value">{{ modelList.length }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="summary-card">
          <div class="summary-label">已启用</div>
          <div class="summary-value enabled">{{ enabledCount }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="summary-card">
          <div class="summary-label">已配置密钥</div>
          <div class="summary-value">{{ keyCount }}</div>
        </el-card>
      </el-col>
    </el-row>

    <div class="table-card">
      <el-table
        v-loading="loading"
        :data="modelList"
        border
        stripe
        empty-text="暂无模型提供方"
        style="width: 100%"
      >
        <el-table-column prop="providerCode" label="提供方编码" width="130" />
        <el-table-column prop="providerName" label="提供方名称" width="140" show-overflow-tooltip />
        <el-table-column prop="baseUrl" label="接口地址" min-width="220" show-overflow-tooltip />
        <el-table-column prop="chatModel" label="对话模型" width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag size="small" type="primary" effect="plain">{{ row.chatModel || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="embedModel" label="向量模型" width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag size="small" type="info" effect="plain">{{ row.embedModel || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.priority > 0 ? 'success' : 'info'" effect="plain">
              {{ row.priority }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="密钥状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.hasApiKey ? 'success' : 'warning'" effect="plain">
              {{ row.hasApiKey ? '已配置' : '未配置' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" @change="handleToggleEnabled(row)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">配置</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 编辑弹窗 -->
    <el-dialog v-model="dialogVisible" title="模型配置" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="提供方">
          <el-input v-model="form.providerName" disabled />
        </el-form-item>
        <el-form-item label="接口地址">
          <el-input v-model="form.baseUrl" disabled />
        </el-form-item>
        <el-form-item label="对话模型">
          <el-input v-model="form.chatModel" placeholder="例如：deepseek-chat" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="0" :max="999" />
          <span class="form-tip">数值越大越优先被调度</span>
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            placeholder="留空表示不修改已保存的密钥"
          />
          <div class="form-tip">出于安全考虑，后端不会回传明文密钥，留空即保持原值</div>
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
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

.summary-row {
  margin-bottom: 16px;
}

.summary-card {
  border-radius: 6px;
}

.summary-label {
  font-size: 13px;
  color: var(--app-text-secondary);
}

.summary-value {
  margin-top: 6px;
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}

.summary-value.enabled {
  color: #67c23a;
}

.table-card {
  padding: 12px;
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 6px;
}

.form-tip {
  margin-left: 8px;
  font-size: 12px;
  color: var(--app-text-secondary);
}
</style>
