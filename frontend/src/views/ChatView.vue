<script setup>
/**
 * 对话中心：左侧会话列表 + 中间流式消息流 + 右侧引用溯源面板
 *
 * 流式交互说明：
 *   1. 用户提问后先插入一条空的 AI 消息占位
 *   2. 收到 meta 事件 -> 立即渲染引用卡片（引用先于正文出现）
 *   3. 收到 delta 事件 -> 逐字追加正文，形成打字机效果
 *   4. 收到 done 事件 -> 显示耗时、模型、token 数
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import MessageBubble from '@/components/MessageBubble.vue'
import CitationCard from '@/components/CitationCard.vue'
import { useChatStore } from '@/stores/chat'
import { listKnowledgeBases } from '@/api/kb'
import { listModels } from '@/api/admin'
import { formatDateTime, formatDuration } from '@/utils/format'

const chatStore = useChatStore()

// 知识库与模型提供方选项
const kbOptions = ref([])
const modelOptions = ref([])
const selectedKbId = ref(null)
const selectedProvider = ref('')

// 输入框内容
const question = ref('')
const inputRef = ref(null)
// 消息滚动容器
const scrollRef = ref(null)

// 新建会话弹窗
const createVisible = ref(false)
const createForm = ref({ title: '', kbId: null, providerCode: '' })
const creating = ref(false)

/** 会话列表 */
const sessions = computed(() => chatStore.sessions)
/** 当前会话消息 */
const messages = computed(() => chatStore.messages)
/** 当前引用的来源 */
const citations = computed(() => chatStore.activeCitations)

/** 输入框是否可发送 */
const canSend = computed(
  () => !!question.value.trim() && !chatStore.isStreaming && !!selectedProvider.value
)

/** 是否开启了 RAG（选择了知识库） */
const ragEnabled = computed(() => !!selectedKbId.value)

/**
 * 滚动消息区到底部
 */
async function scrollToBottom () {
  await nextTick()
  const el = scrollRef.value
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

// 消息变化时自动滚动（流式追加时同样生效）
watch(() => chatStore.messages.map((m) => m.content).join(''), scrollToBottom)

onMounted(async () => {
  await Promise.all([loadKbOptions(), loadModelOptions()])
  await chatStore.loadSessions()

  // 默认选中最近一次会话
  if (sessions.value.length) {
    await chatStore.selectSession(sessions.value[0].sessionUuid)
    // 用会话自身的配置回填顶部选择器
    const current = sessions.value[0]
    if (current.kbId) selectedKbId.value = current.kbId
    if (current.providerCode) selectedProvider.value = current.providerCode
  }
  scrollToBottom()
})

// 组件卸载时中断未完成的流式请求，避免内存泄漏
onBeforeUnmount(() => {
  chatStore.abortStreaming()
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

/** 加载模型提供方下拉选项（只展示已启用的） */
async function loadModelOptions () {
  try {
    const data = await listModels()
    modelOptions.value = (Array.isArray(data) ? data : []).filter((m) => m.enabled)
    if (modelOptions.value.length) {
      // 默认选中优先级数值最大的提供方（优先级越高越靠前）
      const sorted = [...modelOptions.value].sort((a, b) => (b.priority || 0) - (a.priority || 0))
      if (!selectedProvider.value) selectedProvider.value = sorted[0].providerCode
    }
  } catch (e) {
    modelOptions.value = []
  }
}

/** 打开新建会话弹窗 */
function openCreateDialog () {
  createForm.value = {
    title: '',
    kbId: selectedKbId.value,
    providerCode: selectedProvider.value
  }
  createVisible.value = true
}

/** 确认新建会话 */
async function handleCreateSession () {
  if (!createForm.value.providerCode) {
    ElMessage.warning('请选择模型提供方')
    return
  }
  creating.value = true
  try {
    const title = createForm.value.title.trim() || '新会话'
    await chatStore.openSession({
      kbId: createForm.value.kbId,
      title,
      providerCode: createForm.value.providerCode
    })
    if (createForm.value.kbId) selectedKbId.value = createForm.value.kbId
    selectedProvider.value = createForm.value.providerCode
    createVisible.value = false
    ElMessage.success('会话已创建')
    scrollToBottom()
  } catch (e) {
    // 错误提示已统一处理
  } finally {
    creating.value = false
  }
}

/**
 * 切换会话
 * @param {string} sessionUuid 会话 UUID
 */
async function handleSelectSession (sessionUuid) {
  try {
    await chatStore.selectSession(sessionUuid)
    const session = chatStore.sessions.find((s) => s.sessionUuid === sessionUuid)
    if (session) {
      selectedKbId.value = session.kbId || null
      selectedProvider.value = session.providerCode || selectedProvider.value
    }
    scrollToBottom()
  } catch (e) {
    // 错误提示已统一处理
  }
}

/**
 * 发送问题
 */
async function handleSend () {
  if (!canSend.value) return
  const text = question.value.trim()

  // 没有会话时自动创建一个
  if (!chatStore.currentSessionUuid) {
    try {
      await chatStore.openSession({
        kbId: selectedKbId.value,
        title: text.slice(0, 20),
        providerCode: selectedProvider.value
      })
    } catch (e) {
      return
    }
  }

  question.value = ''
  await chatStore.sendQuestion({
    sessionUuid: chatStore.currentSessionUuid,
    question: text,
    kbId: selectedKbId.value,
    providerCode: selectedProvider.value
  })
  // 会话标题在首轮问答后可能变化，刷新列表保证展示同步
  chatStore.loadSessions()
}

/** 停止生成 */
function handleStop () {
  chatStore.abortStreaming()
  ElMessage.info('已停止生成')
}

/** 回车发送，Shift+回车换行 */
function handleKeydown (e) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    handleSend()
  }
}

/** 点击消息里的引用，右侧面板定位展示 */
function handleSelectCitation () {
  // 引用已经写入 activeCitations，这里仅做提示，滚动面板到顶部
  nextTick(() => {
    const panel = document.querySelector('.citation-panel')
    if (panel) panel.scrollTop = 0
  })
}

/** 清空当前会话展示（仅前端视角的重新开始） */
async function handleRestart () {
  try {
    await ElMessageBox.confirm('确定要新建一个空白会话吗？', '提示', {
      confirmButtonText: '新建',
      cancelButtonText: '取消',
      type: 'info'
    })
  } catch (e) {
    return
  }
  openCreateDialog()
}

/** 常用问题示例 */
const exampleQuestions = [
  '校园卡丢失后如何补办？',
  '图书馆的开馆时间是几点？',
  '宿舍报修流程是什么？'
]

/**
 * 点击示例问题直接填入输入框
 * @param {string} text 示例问题
 */
function useExample (text) {
  question.value = text
}
</script>

<template>
  <div class="chat-page">
    <!-- 左侧：会话列表 -->
    <aside class="session-panel">
      <div class="panel-header">
        <span class="panel-title">会话列表</span>
        <el-button type="primary" size="small" :icon="'Plus'" @click="openCreateDialog">
          新建
        </el-button>
      </div>

      <div v-loading="chatStore.loadingSessions" class="session-list">
        <el-empty
          v-if="!sessions.length && !chatStore.loadingSessions"
          description="暂无会话"
          :image-size="60"
        />
        <div
          v-for="item in sessions"
          :key="item.sessionUuid"
          class="session-item"
          :class="{ active: item.sessionUuid === chatStore.currentSessionUuid }"
          @click="handleSelectSession(item.sessionUuid)"
        >
          <div class="session-title text-ellipsis">{{ item.title || '未命名会话' }}</div>
          <div class="session-meta">
            <span>{{ item.messageCount || 0 }} 条</span>
            <span>{{ formatDateTime(item.updatedAt) }}</span>
          </div>
          <div class="session-tags">
            <el-tag size="small" type="info" effect="plain">{{ item.providerCode || '-' }}</el-tag>
          </div>
        </div>
      </div>
    </aside>

    <!-- 中间：消息流 -->
    <section class="chat-panel">
      <!-- 顶部：知识库与模型切换 -->
      <div class="chat-toolbar">
        <div class="toolbar-left">
          <span class="toolbar-label">知识库</span>
          <el-select
            v-model="selectedKbId"
            placeholder="不启用 RAG"
            clearable
            size="default"
            class="kb-select"
          >
            <el-option
              v-for="kb in kbOptions"
              :key="kb.id"
              :label="kb.kbName"
              :value="kb.id"
            />
          </el-select>

          <span class="toolbar-label">模型提供方</span>
          <el-select v-model="selectedProvider" placeholder="请选择" size="default" class="provider-select">
            <el-option
              v-for="m in modelOptions"
              :key="m.providerCode"
              :label="m.providerName + ' / ' + m.chatModel"
              :value="m.providerCode"
            />
          </el-select>

          <el-tag :type="ragEnabled ? 'success' : 'info'" size="small" effect="plain">
            {{ ragEnabled ? 'RAG 检索已开启' : '未启用知识库' }}
          </el-tag>
        </div>

        <el-button link type="primary" @click="handleRestart">重新开始</el-button>
      </div>

      <!-- 消息列表 -->
      <div ref="scrollRef" v-loading="chatStore.loadingMessages" class="chat-messages">
        <div v-if="!messages.length" class="chat-empty">
          <el-icon class="empty-icon"><ChatDotRound /></el-icon>
          <p class="empty-title">开始你的第一次知识库问答</p>
          <p class="empty-desc">
            选择知识库后提问，系统会检索相关文档片段并生成带引用溯源的回答
          </p>
          <div class="example-list">
            <el-tag
              v-for="q in exampleQuestions"
              :key="q"
              class="example-tag"
              effect="plain"
              @click="useExample(q)"
            >
              {{ q }}
            </el-tag>
          </div>
        </div>

        <MessageBubble
          v-for="(msg, index) in messages"
          :key="msg.id || index"
          :message="msg"
          @select-citation="handleSelectCitation"
        />
      </div>

      <!-- 底部输入区 -->
      <div class="chat-input">
        <el-input
          ref="inputRef"
          v-model="question"
          type="textarea"
          :rows="3"
          resize="none"
          maxlength="1000"
          show-word-limit
          placeholder="请输入问题，Enter 发送，Shift + Enter 换行"
          @keydown="handleKeydown"
        />
        <div class="input-actions">
          <span class="input-tip">
            当前模型：{{ selectedProvider || '未选择' }}
            <template v-if="ragEnabled"> · 知识库已关联</template>
          </span>
          <div>
            <el-button v-if="chatStore.isStreaming" type="danger" plain @click="handleStop">
              停止生成
            </el-button>
            <el-button type="primary" :disabled="!canSend" @click="handleSend">
              {{ chatStore.isStreaming ? '生成中…' : '发送' }}
            </el-button>
          </div>
        </div>
      </div>
    </section>

    <!-- 右侧：引用溯源面板 -->
    <aside class="citation-panel">
      <div class="panel-header">
        <span class="panel-title">引用溯源</span>
        <el-tag size="small" type="info" effect="plain">{{ citations.length }} 条来源</el-tag>
      </div>

      <div class="citation-body">
        <el-empty
          v-if="!citations.length"
          description="本次回答暂无引用来源"
          :image-size="60"
        />
        <CitationCard
          v-for="(item, index) in citations"
          :key="item.index || index"
          :citation="item"
          :default-expanded="index === 0"
        />
      </div>

      <!-- 生成统计 -->
      <div v-if="chatStore.lastStats" class="citation-footer">
        <div class="footer-row">
          <span class="text-secondary">耗时</span>
          <span>{{ formatDuration(chatStore.lastStats.elapsedMs) }}</span>
        </div>
        <div class="footer-row">
          <span class="text-secondary">模型</span>
          <span>{{ chatStore.lastStats.provider }} / {{ chatStore.lastStats.model }}</span>
        </div>
        <div class="footer-row">
          <span class="text-secondary">Token</span>
          <span>{{ chatStore.lastStats.totalTokens || '-' }}</span>
        </div>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  gap: 12px;
  height: calc(100vh - 88px);
}

/* ---------- 通用面板 ---------- */
.session-panel,
.citation-panel {
  display: flex;
  flex-direction: column;
  width: 250px;
  flex-shrink: 0;
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  overflow: hidden;
}

.citation-panel {
  width: 320px;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px;
  border-bottom: 1px solid var(--app-border);
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
}

/* ---------- 会话列表 ---------- */
.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.session-item {
  padding: 8px 10px;
  margin-bottom: 6px;
  border: 1px solid var(--app-border);
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;
}

.session-item:hover {
  border-color: #b3d8ff;
  background: #f7fbff;
}

.session-item.active {
  border-color: #409eff;
  background: #ecf5ff;
}

.session-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 4px;
}

.session-meta {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--app-text-secondary);
  margin-bottom: 4px;
}

.session-tags {
  display: flex;
  gap: 4px;
}

/* ---------- 中间对话区 ---------- */
.chat-panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  overflow: hidden;
}

.chat-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--app-border);
  flex-wrap: wrap;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.toolbar-label {
  font-size: 13px;
  color: var(--app-text-secondary);
}

.kb-select {
  width: 170px;
}

.provider-select {
  width: 200px;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 18px 20px;
  background: #fafbfc;
}

.chat-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  text-align: center;
}

.empty-icon {
  font-size: 48px;
  color: #c0c4cc;
}

.empty-title {
  margin: 12px 0 4px;
  font-size: 15px;
  font-weight: 600;
  color: #606266;
}

.empty-desc {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--app-text-secondary);
}

.example-list {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: center;
}

.example-tag {
  cursor: pointer;
}

.example-tag:hover {
  background: #ecf5ff;
}

/* ---------- 输入区 ---------- */
.chat-input {
  padding: 12px 14px;
  border-top: 1px solid var(--app-border);
  background: #fff;
}

.input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.input-tip {
  font-size: 12px;
  color: var(--app-text-secondary);
}

/* ---------- 引用面板 ---------- */
.citation-body {
  flex: 1;
  overflow-y: auto;
  padding: 10px;
}

.citation-footer {
  padding: 10px 12px;
  border-top: 1px solid var(--app-border);
  background: #fafafa;
}

.footer-row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  line-height: 1.9;
}
</style>
