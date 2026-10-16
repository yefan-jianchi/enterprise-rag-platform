import { defineStore } from 'pinia'
import { listSessions, createSession, listMessages, streamChat } from '@/api/chat'
import { isAbortError } from '@/utils/sse'

/**
 * 生成一个前端临时消息 ID（后端返回真实 ID 后会覆盖）
 */
let tempIdSeed = 0
function nextTempId () {
  tempIdSeed += 1
  return `tmp-${Date.now()}-${tempIdSeed}`
}

/**
 * 把后端消息结构统一成前端渲染结构
 * @param {object} raw 后端消息对象
 */
function normalizeMessage (raw) {
  return {
    id: raw.id !== undefined && raw.id !== null ? raw.id : nextTempId(),
    role: (raw.role || 'assistant').toLowerCase(),
    content: raw.content || '',
    citations: raw.citations || [],
    model: raw.model || '',
    provider: raw.provider || '',
    latencyMs: raw.latencyMs,
    totalTokens: raw.totalTokens,
    createdAt: raw.createdAt || new Date().toISOString(),
    // 流式状态：streaming 表示正在逐字输出，error 表示本次生成失败
    streaming: false,
    error: ''
  }
}

/**
 * 对话状态：会话列表、消息列表、SSE 流式生成
 */
export const useChatStore = defineStore('chat', {
  state: () => ({
    // 会话列表
    sessions: [],
    // 当前选中的会话 UUID
    currentSessionUuid: '',
    // 当前会话的消息列表
    messages: [],
    // 当前会话最近一次命中的引用来源（右侧溯源面板使用）
    activeCitations: [],
    // 会话列表加载中
    loadingSessions: false,
    // 历史消息加载中
    loadingMessages: false,
    // 是否正在流式生成
    isStreaming: false,
    // 最近一次生成统计 {elapsedMs, provider, model, totalTokens}
    lastStats: null,
    // 中断控制器（不参与响应式渲染，仅用于 abort）
    _abort: null
  }),

  getters: {
    /** 是否已有会话 */
    hasSessions: (state) => state.sessions.length > 0,
    /** 当前会话对象 */
    currentSession: (state) =>
      state.sessions.find((s) => s.sessionUuid === state.currentSessionUuid) || null
  },

  actions: {
    /** 加载会话列表 */
    async loadSessions () {
      this.loadingSessions = true
      try {
        const data = await listSessions()
        this.sessions = Array.isArray(data) ? data : []
        return this.sessions
      } finally {
        this.loadingSessions = false
      }
    },

    /**
     * 新建会话并设为当前会话
     * @param {{kbId:number, title:string, providerCode:string}} payload 会话参数
     */
    async openSession (payload) {
      const data = await createSession(payload)
      if (data) {
        // 新建的会话插入列表头部，避免整表刷新
        this.sessions.unshift({
          sessionUuid: data.sessionUuid,
          title: data.title || payload.title || '新会话',
          kbId: data.kbId !== undefined ? data.kbId : payload.kbId,
          providerCode: data.providerCode || payload.providerCode,
          scene: data.scene || 'chat',
          messageCount: 0,
          updatedAt: data.updatedAt || new Date().toISOString()
        })
        this.currentSessionUuid = data.sessionUuid
        this.messages = []
        this.activeCitations = []
        this.lastStats = null
      }
      return data
    },

    /**
     * 切换当前会话并加载其历史消息
     * @param {string} sessionUuid 会话 UUID
     */
    async selectSession (sessionUuid) {
      if (!sessionUuid || sessionUuid === this.currentSessionUuid) return
      // 切换会话前先中断正在进行的生成，避免消息串到新会话里
      this.abortStreaming()
      this.currentSessionUuid = sessionUuid
      this.messages = []
      this.activeCitations = []
      this.lastStats = null
      await this.loadMessages(sessionUuid)
    },

    /**
     * 加载指定会话的历史消息
     * @param {string} sessionUuid 会话 UUID
     */
    async loadMessages (sessionUuid) {
      if (!sessionUuid) return []
      this.loadingMessages = true
      try {
        const data = await listMessages(sessionUuid)
        const list = Array.isArray(data) ? data : []
        this.messages = list.map(normalizeMessage)
        // 右侧溯源面板默认展示最后一条 assistant 消息的引用
        const lastAssistant = [...this.messages].reverse().find((m) => m.role === 'assistant')
        this.activeCitations = lastAssistant ? lastAssistant.citations : []
        return this.messages
      } finally {
        this.loadingMessages = false
      }
    },

    /**
     * 发送问题并流式接收回答
     * 流程：先渲染引用卡片(meta) -> 逐字追加正文(delta) -> 展示耗时/模型(done)
     * @param {{sessionUuid:string, question:string, kbId:number, providerCode:string}} payload 提问参数
     */
    async sendQuestion (payload) {
      const question = (payload.question || '').trim()
      if (!question || this.isStreaming) return

      // 1. 追加用户消息
      this.messages.push(
        normalizeMessage({ role: 'user', content: question, createdAt: new Date().toISOString() })
      )

      // 2. 追加一条空的助手消息，作为流式输出的落点
      const assistantMsg = normalizeMessage({
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString()
      })
      assistantMsg.streaming = true
      this.messages.push(assistantMsg)

      this.isStreaming = true
      this.activeCitations = []
      this.lastStats = null

      const controller = new AbortController()
      this._abort = controller

      try {
        await streamChat(
          {
            sessionUuid: payload.sessionUuid,
            question,
            kbId: payload.kbId,
            providerCode: payload.providerCode
          },
          {
            signal: controller.signal,
            // meta：拿到引用来源，先渲染引用卡片
            onMeta: (data) => {
              const citations = (data && data.citations) || []
              assistantMsg.citations = citations
              this.activeCitations = citations
            },
            // delta：逐字追加正文
            onDelta: (data) => {
              const chunk = data && typeof data.content === 'string' ? data.content : ''
              if (chunk) assistantMsg.content += chunk
            },
            // done：记录耗时与模型信息
            onDone: (data) => {
              const stats = data || {}
              assistantMsg.streaming = false
              assistantMsg.model = stats.model || ''
              assistantMsg.provider = stats.provider || ''
              assistantMsg.latencyMs = stats.elapsedMs
              assistantMsg.totalTokens = stats.totalTokens
              this.lastStats = stats
            },
            // 服务端主动下发的 error 事件
            onError: (err) => {
              assistantMsg.streaming = false
              assistantMsg.error = (err && err.message) || '生成失败'
              if (!assistantMsg.content) {
                assistantMsg.content = `生成失败：${assistantMsg.error}`
              }
            }
          }
        )
      } catch (error) {
        // 用户主动中断不算错误
        if (!isAbortError(error)) {
          assistantMsg.error = (error && error.message) || '网络异常'
          if (!assistantMsg.content) {
            assistantMsg.content = `生成失败：${assistantMsg.error}`
          }
        }
      } finally {
        assistantMsg.streaming = false
        this.isStreaming = false
        this._abort = null
      }
    },

    /** 中断当前流式生成 */
    abortStreaming () {
      if (this._abort) {
        this._abort.abort()
        this._abort = null
      }
      this.isStreaming = false
    },

    /** 切换当前展示的引用来源（点击历史消息的引用时调用） */
    setActiveCitations (citations) {
      this.activeCitations = citations || []
    },

    /** 清空会话相关状态（退出登录时调用） */
    reset () {
      this.abortStreaming()
      this.sessions = []
      this.currentSessionUuid = ''
      this.messages = []
      this.activeCitations = []
      this.lastStats = null
    }
  }
})
