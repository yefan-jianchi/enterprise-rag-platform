<script setup>
/**
 * 智能客服：客服对话（SSE 流式，后端会走 Tool Calling）
 * 右侧提供三类辅助能力：订单查询、工单列表、转人工
 *
 * 与对话中心的区别：客服场景不绑定知识库，回答可能来自工具调用（如查订单），
 * 因此单独维护一份消息列表，不污染 RAG 对话状态。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownContent from '@/components/MarkdownContent.vue'
import { streamCustomerService, listOrders, listTickets, handoffToHuman } from '@/api/cs'
import { listModels } from '@/api/admin'
import { isAbortError } from '@/utils/sse'
import { ORDER_STATUS, TICKET_PRIORITY, TICKET_STATUS } from '@/utils/constants'
import { formatDateTime, formatDuration, formatMoney } from '@/utils/format'

// ---------- 对话状态 ----------
const messages = ref([])
const inputText = ref('')
const streaming = ref(false)
const scrollRef = ref(null)
// 客服会话 UUID（首次发送时惰性生成）
const sessionUuid = ref('')
// 模型提供方
const providerOptions = ref([])
const selectedProvider = ref('')

// 最近一次生成统计
const lastStats = ref(null)

// ---------- 右侧面板状态 ----------
const activeTab = ref('orders')
const orderKeyword = ref('')
const orders = ref([])
const orderLoading = ref(false)
const tickets = ref([])
const ticketLoading = ref(false)

// 转人工弹窗
const handoffVisible = ref(false)
const handoffReason = ref('')
const handoffSubmitting = ref(false)

let abortController = null

/** 输入框是否可发送 */
const canSend = computed(() => !!inputText.value.trim() && !streaming.value)

onMounted(async () => {
  await Promise.all([loadProviders(), loadOrders(), loadTickets()])
  // 生成一个前端会话标识，保证多轮对话在同一会话中
  sessionUuid.value = generateUuid()
})

onBeforeUnmount(() => {
  stopStream()
})

/** 生成简易 UUID（优先使用浏览器原生实现） */
function generateUuid () {
  if (window.crypto && typeof window.crypto.randomUUID === 'function') {
    return window.crypto.randomUUID()
  }
  return `cs-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}

/** 加载模型提供方 */
async function loadProviders () {
  try {
    const data = await listModels()
    providerOptions.value = (Array.isArray(data) ? data : []).filter((m) => m.enabled)
    if (providerOptions.value.length && !selectedProvider.value) {
      const sorted = [...providerOptions.value].sort((a, b) => (b.priority || 0) - (a.priority || 0))
      selectedProvider.value = sorted[0].providerCode
    }
  } catch (e) {
    providerOptions.value = []
  }
}

/** 滚动到底部 */
async function scrollToBottom () {
  await nextTick()
  const el = scrollRef.value
  if (el) el.scrollTop = el.scrollHeight
}

/** 发送客服问题 */
async function handleSend () {
  const text = inputText.value.trim()
  if (!text || streaming.value) return

  messages.value.push({ role: 'user', content: text, createdAt: new Date().toISOString() })
  const reply = {
    role: 'assistant',
    content: '',
    streaming: true,
    createdAt: new Date().toISOString()
  }
  messages.value.push(reply)
  inputText.value = ''
  streaming.value = true
  lastStats.value = null
  scrollToBottom()

  abortController = new AbortController()
  try {
    await streamCustomerService(
      {
        sessionUuid: sessionUuid.value,
        question: text,
        providerCode: selectedProvider.value
      },
      {
        signal: abortController.signal,
        onMeta: (data) => {
          // 客服场景的 meta 事件可能携带工具调用信息，这里做兼容展示
          if (data && data.toolCalls && data.toolCalls.length) {
            reply.toolCalls = data.toolCalls
          }
        },
        onDelta: (data) => {
          const chunk = data && typeof data.content === 'string' ? data.content : ''
          if (chunk) reply.content += chunk
        },
        onDone: (data) => {
          reply.streaming = false
          reply.latencyMs = data && data.elapsedMs
          reply.model = data && data.model
          lastStats.value = data || null
          // 回答可能涉及订单变化，顺带刷新一次订单列表
          loadOrders()
        },
        onError: (err) => {
          reply.streaming = false
          reply.error = (err && err.message) || '生成失败'
          if (!reply.content) reply.content = `抱歉，客服助手暂时不可用：${reply.error}`
        }
      }
    )
  } catch (error) {
    if (!isAbortError(error)) {
      reply.error = (error && error.message) || '网络异常'
      if (!reply.content) reply.content = `抱歉，客服助手暂时不可用：${reply.error}`
    }
  } finally {
    reply.streaming = false
    streaming.value = false
    abortController = null
    scrollToBottom()
  }
}

/** 停止生成 */
function stopStream () {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  streaming.value = false
}

/** 回车发送 */
function handleKeydown (e) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    handleSend()
  }
}

/** 查询订单 */
async function loadOrders () {
  orderLoading.value = true
  try {
    const data = await listOrders(orderKeyword.value.trim())
    orders.value = Array.isArray(data) ? data : []
  } catch (e) {
    orders.value = []
  } finally {
    orderLoading.value = false
  }
}

/** 加载工单列表 */
async function loadTickets () {
  ticketLoading.value = true
  try {
    const data = await listTickets()
    tickets.value = Array.isArray(data) ? data : []
  } catch (e) {
    tickets.value = []
  } finally {
    ticketLoading.value = false
  }
}

/** 打开转人工弹窗 */
function openHandoff () {
  handoffReason.value = ''
  handoffVisible.value = true
}

/** 提交转人工 */
async function submitHandoff () {
  if (!handoffReason.value.trim()) {
    ElMessage.warning('请填写转人工原因')
    return
  }
  handoffSubmitting.value = true
  try {
    await handoffToHuman(sessionUuid.value, handoffReason.value.trim())
    ElMessage.success('已提交转人工工单，客服人员会尽快与您联系')
    handoffVisible.value = false
    // 转人工后追加一条系统提示，形成完整对话记录
    messages.value.push({
      role: 'assistant',
      content: `已为您转接人工客服。\n\n**转接原因**：${handoffReason.value.trim()}`,
      createdAt: new Date().toISOString(),
      system: true
    })
    await loadTickets()
    scrollToBottom()
  } catch (e) {
    // 错误提示已统一处理
  } finally {
    handoffSubmitting.value = false
  }
}

/** 清空当前会话重新开始 */
async function handleNewSession () {
  try {
    await ElMessageBox.confirm('确定要开始新的客服会话吗？当前对话记录将被清空。', '提示', {
      confirmButtonText: '重新开始',
      cancelButtonText: '取消',
      type: 'info'
    })
  } catch (e) {
    return
  }
  stopStream()
  messages.value = []
  lastStats.value = null
  sessionUuid.value = generateUuid()
  ElMessage.success('已开启新的客服会话')
}

/** 常用问题 */
function askExample (text) {
  inputText.value = text
}

/**
 * 以下三个小工具用于把状态字典安全地转换成中文标签与标签颜色
 * 直接写在模板里会出现多层属性访问，抽成函数更易读也避免空值报错
 */
function orderStatusLabel (status) {
  const item = ORDER_STATUS[status]
  return (item && item.label) || status || '-'
}

function orderStatusType (status) {
  const item = ORDER_STATUS[status]
  return (item && item.type) || 'info'
}

function ticketPriorityLabel (priority) {
  const item = TICKET_PRIORITY[priority]
  return (item && item.label) || priority || '-'
}

function ticketPriorityType (priority) {
  const item = TICKET_PRIORITY[priority]
  return (item && item.type) || 'info'
}

function ticketStatusLabel (status) {
  const item = TICKET_STATUS[status]
  return (item && item.label) || status || '-'
}

function ticketStatusType (status) {
  const item = TICKET_STATUS[status]
  return (item && item.type) || 'info'
}
</script>

<template>
  <div class="cs-page">
    <!-- 左侧：客服对话 -->
    <section class="cs-chat">
      <div class="cs-header">
        <div class="cs-title">
          <el-icon class="cs-icon"><Service /></el-icon>
          <div>
            <div class="title-text">智能客服助手</div>
            <div class="title-sub">支持订单查询、工单处理等工具调用能力</div>
          </div>
        </div>
        <div class="cs-actions">
          <el-select v-model="selectedProvider" size="default" class="provider-select" placeholder="模型提供方">
            <el-option
              v-for="m in providerOptions"
              :key="m.providerCode"
              :label="m.providerName + ' / ' + m.chatModel"
              :value="m.providerCode"
            />
          </el-select>
          <el-button :icon="'Refresh'" @click="handleNewSession">新会话</el-button>
        </div>
      </div>

      <div ref="scrollRef" class="cs-messages">
        <div v-if="!messages.length" class="cs-empty">
          <el-icon class="empty-icon"><Service /></el-icon>
          <p class="empty-title">您好，我是智能客服助手</p>
          <p class="empty-desc">可以帮您查询订单物流、处理售后工单，也可以随时转接人工客服</p>
          <div class="example-list">
            <el-tag effect="plain" class="example-tag" @click="askExample('帮我查一下订单 ORD2024001 的物流状态')">
              查询订单物流
            </el-tag>
            <el-tag effect="plain" class="example-tag" @click="askExample('我要申请退货，怎么操作？')">
              申请退货流程
            </el-tag>
            <el-tag effect="plain" class="example-tag" @click="askExample('我的工单处理到哪一步了？')">
              工单进度查询
            </el-tag>
          </div>
        </div>

        <div
          v-for="(msg, index) in messages"
          :key="index"
          class="cs-message"
          :class="msg.role === 'user' ? 'is-user' : 'is-assistant'"
        >
          <el-avatar :size="30" :class="msg.role === 'user' ? 'avatar-user' : 'avatar-ai'">
            {{ msg.role === 'user' ? '我' : '客服' }}
          </el-avatar>
          <div class="message-main">
            <div class="message-body" :class="msg.role === 'user' ? 'body-user' : 'body-ai'">
              <MarkdownContent v-if="msg.content" :content="msg.content" />
              <span v-if="msg.streaming" class="typing-cursor"></span>
              <span v-if="msg.streaming && !msg.content" class="thinking">正在为您处理…</span>
            </div>
            <div class="message-footer">
              <span class="message-time">{{ formatDateTime(msg.createdAt) }}</span>
              <el-tag v-if="msg.model" size="small" type="info" effect="plain">{{ msg.model }}</el-tag>
              <el-tag v-if="msg.latencyMs" size="small" type="success" effect="plain">
                {{ formatDuration(msg.latencyMs) }}
              </el-tag>
            </div>
          </div>
        </div>
      </div>

      <div class="cs-input">
        <el-input
          v-model="inputText"
          type="textarea"
          :rows="3"
          resize="none"
          maxlength="500"
          show-word-limit
          placeholder="请输入您的问题，Enter 发送，Shift + Enter 换行"
          @keydown="handleKeydown"
        />
        <div class="input-actions">
          <el-button type="warning" plain @click="openHandoff">
            <el-icon><Service /></el-icon>
            转人工
          </el-button>
          <div>
            <el-button v-if="streaming" type="danger" plain @click="stopStream">停止生成</el-button>
            <el-button type="primary" :disabled="!canSend" @click="handleSend">
              {{ streaming ? '处理中…' : '发送' }}
            </el-button>
          </div>
        </div>
        <div v-if="lastStats" class="stats-bar">
          本次耗时 {{ formatDuration(lastStats.elapsedMs) }} ·
          模型 {{ lastStats.provider }} / {{ lastStats.model }} ·
          Token {{ lastStats.totalTokens || '-' }}
        </div>
      </div>
    </section>

    <!-- 右侧：辅助面板 -->
    <aside class="cs-side">
      <el-tabs v-model="activeTab" class="cs-tabs">
        <!-- 订单查询 -->
        <el-tab-pane label="订单查询" name="orders">
          <div class="search-bar">
            <el-input
              v-model="orderKeyword"
              placeholder="订单号 / 客户姓名"
              clearable
              size="default"
              @keyup.enter="loadOrders"
            >
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <el-button type="primary" :loading="orderLoading" @click="loadOrders">查询</el-button>
          </div>

          <div v-loading="orderLoading" class="order-list">
            <el-empty v-if="!orders.length && !orderLoading" description="暂无订单数据" :image-size="60" />
            <div v-for="order in orders" :key="order.orderNo" class="order-card">
              <div class="order-head">
                <span class="order-no">{{ order.orderNo }}</span>
                <el-tag size="small" :type="orderStatusType(order.orderStatus)">
                  {{ orderStatusLabel(order.orderStatus) }}
                </el-tag>
              </div>
              <div class="order-row">
                <span class="label">客户</span>
                <span>{{ order.customerName || '-' }}</span>
              </div>
              <div class="order-row">
                <span class="label">商品</span>
                <span class="text-ellipsis" :title="order.productName">{{ order.productName || '-' }}</span>
              </div>
              <div class="order-row">
                <span class="label">金额</span>
                <span class="amount">￥{{ formatMoney(order.amount, 2) }}</span>
              </div>
              <div class="order-row">
                <span class="label">物流单号</span>
                <span>{{ order.logisticsNo || '暂无' }}</span>
              </div>
              <div class="order-row">
                <span class="label">下单时间</span>
                <span>{{ formatDateTime(order.createdAt) }}</span>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <!-- 工单列表 -->
        <el-tab-pane :label="`工单列表 (${tickets.length})`" name="tickets">
          <div class="tab-toolbar">
            <el-button size="small" :icon="'Refresh'" @click="loadTickets">刷新</el-button>
          </div>
          <div v-loading="ticketLoading" class="ticket-list">
            <el-empty v-if="!tickets.length && !ticketLoading" description="暂无工单" :image-size="60" />
            <div v-for="ticket in tickets" :key="ticket.ticketNo" class="ticket-card">
              <div class="ticket-head">
                <span class="ticket-no">{{ ticket.ticketNo }}</span>
                <div class="ticket-tags">
                  <el-tag size="small" :type="ticketPriorityType(ticket.priority)" effect="plain">
                    {{ ticketPriorityLabel(ticket.priority) }}
                  </el-tag>
                  <el-tag size="small" :type="ticketStatusType(ticket.status)">
                    {{ ticketStatusLabel(ticket.status) }}
                  </el-tag>
                </div>
              </div>
              <div class="ticket-title">{{ ticket.title }}</div>
              <div class="ticket-content">{{ ticket.content }}</div>
              <div class="ticket-foot">
                <span>处理人：{{ ticket.assignee || '未分配' }}</span>
                <span>{{ formatDateTime(ticket.createdAt) }}</span>
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </aside>

    <!-- 转人工弹窗 -->
    <el-dialog v-model="handoffVisible" title="转接人工客服" width="460px">
      <el-form label-position="top">
        <el-form-item label="转接原因">
          <el-input
            v-model="handoffReason"
            type="textarea"
            :rows="4"
            resize="none"
            maxlength="200"
            show-word-limit
            placeholder="请简要描述问题，便于人工客服快速跟进"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handoffVisible = false">取消</el-button>
        <el-button type="primary" :loading="handoffSubmitting" @click="submitHandoff">
          提交转接
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cs-page {
  display: flex;
  gap: 12px;
  height: calc(100vh - 88px);
}

/* ---------- 左侧对话 ---------- */
.cs-chat {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  overflow: hidden;
}

.cs-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--app-border);
  flex-wrap: wrap;
}

.cs-title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.cs-icon {
  font-size: 24px;
  color: #e6a23c;
}

.title-text {
  font-size: 15px;
  font-weight: 600;
}

.title-sub {
  font-size: 12px;
  color: var(--app-text-secondary);
}

.cs-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.provider-select {
  width: 200px;
}

.cs-messages {
  flex: 1;
  overflow-y: auto;
  padding: 18px 20px;
  background: #fafbfc;
}

.cs-empty {
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

.cs-message {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}

.cs-message.is-user {
  flex-direction: row-reverse;
}

.avatar-user {
  background: #409eff;
  color: #fff;
  font-size: 12px;
  flex-shrink: 0;
}

.avatar-ai {
  background: #e6a23c;
  color: #fff;
  font-size: 12px;
  flex-shrink: 0;
}

.message-main {
  max-width: 76%;
  min-width: 0;
}

.is-user .message-main {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.message-body {
  padding: 10px 14px;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  background: #fff;
}

.body-user {
  background: #ecf5ff;
  border-color: #d9ecff;
}

.message-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.is-user .message-footer {
  flex-direction: row-reverse;
}

.typing-cursor {
  display: inline-block;
  width: 6px;
  height: 14px;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: #e6a23c;
  animation: cursor-blink 1s steps(1) infinite;
}

@keyframes cursor-blink {
  0%,
  50% {
    opacity: 1;
  }
  50.01%,
  100% {
    opacity: 0;
  }
}

.thinking {
  font-size: 13px;
  color: var(--app-text-secondary);
}

.cs-input {
  padding: 12px 16px;
  border-top: 1px solid var(--app-border);
  background: #fff;
}

.input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.stats-bar {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--app-border);
  font-size: 12px;
  color: var(--app-text-secondary);
}

/* ---------- 右侧面板 ---------- */
.cs-side {
  display: flex;
  flex-direction: column;
  width: 360px;
  flex-shrink: 0;
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  overflow: hidden;
}

.cs-tabs {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 0 12px;
}

.cs-tabs :deep(.el-tabs__content) {
  flex: 1;
  overflow: hidden;
}

.cs-tabs :deep(.el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.search-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.tab-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 10px;
}

.order-list,
.ticket-list {
  flex: 1;
  overflow-y: auto;
  padding-bottom: 12px;
}

.order-card,
.ticket-card {
  padding: 10px 12px;
  margin-bottom: 10px;
  border: 1px solid var(--app-border);
  border-radius: 4px;
  background: #fafbfc;
}

.order-head,
.ticket-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.order-no,
.ticket-no {
  font-size: 13px;
  font-weight: 600;
  color: #409eff;
}

.ticket-tags {
  display: flex;
  gap: 4px;
}

.order-row {
  display: flex;
  gap: 8px;
  font-size: 12px;
  line-height: 1.9;
  color: #606266;
}

.order-row .label {
  width: 60px;
  flex-shrink: 0;
  color: var(--app-text-secondary);
}

.order-row .amount {
  font-weight: 600;
  color: #f56c6c;
}

.ticket-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 4px;
}

.ticket-content {
  font-size: 12px;
  line-height: 1.6;
  color: #606266;
  margin-bottom: 6px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
}

.ticket-foot {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--app-text-secondary);
}
</style>
