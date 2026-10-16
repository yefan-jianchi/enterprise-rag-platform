/**
 * SSE（Server-Sent Events）流式解析工具
 *
 * 背景：浏览器原生 EventSource 只支持 GET 请求，无法携带 JSON body，
 * 因此本项目使用 fetch/axios 拿到 response.body 后，手动按 SSE 协议解析
 * text/event-stream 数据流。
 *
 * SSE 事件以「空行」分隔，单个事件由若干字段行组成：
 *   event: delta
 *   data: {"content":"你好"}
 *   (空行)
 *
 * 关键难点：网络分片（chunk）不会按事件边界切分，
 * 一个事件可能被拆到两个 chunk 里，因此必须维护跨 chunk 的缓冲区，
 * 只处理「已经遇到空行」的完整事件块，剩余半截留在 buffer 中等下一个 chunk。
 */

/**
 * 增量式 SSE 解析器
 * 每次 feed 一段文本，返回本次能够完整解析出来的事件数组
 */
export class SseParser {
  constructor () {
    // 跨 chunk 的残留缓冲
    this.buffer = ''
  }

  /**
   * 送入一段新文本（可能是任意位置截断的）
   * @param {string} text 本次读到的文本片段
   * @returns {Array<{event:string, data:string}>} 本次解析出的完整事件
   */
  feed (text) {
    if (!text) return []
    this.buffer += text

    // 注意：这里统一把 \r\n / \r 归一化为 \n，避免不同服务端换行符差异
    const normalized = this.buffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
    const events = []
    // 按空行切分事件块，最后一段可能是不完整事件，继续留在缓冲中
    const blocks = normalized.split('\n\n')
    this.buffer = blocks.pop() ?? ''

    for (const block of blocks) {
      const parsed = SseParser.parseEventBlock(block)
      if (parsed) events.push(parsed)
    }
    return events
  }

  /**
   * 解析单个事件块（不含结尾空行）
   * @param {string} block 事件块文本
   * @returns {{event:string, data:string}|null} 解析结果，无有效内容时返回 null
   */
  static parseEventBlock (block) {
    if (!block) return null

    let eventName = 'message'
    const dataLines = []

    for (const rawLine of block.split('\n')) {
      // 以冒号开头的是注释（部分服务端用作心跳），直接忽略
      if (!rawLine || rawLine.startsWith(':')) continue

      const colonIndex = rawLine.indexOf(':')
      let field
      let value
      if (colonIndex === -1) {
        // 形如 "data"（无冒号）的行，字段名即整行，值为空字符串
        field = rawLine
        value = ''
      } else {
        field = rawLine.slice(0, colonIndex)
        value = rawLine.slice(colonIndex + 1)
        // 规范要求：冒号后若紧跟一个空格，需要去掉该空格
        if (value.startsWith(' ')) value = value.slice(1)
      }

      if (field === 'event') {
        eventName = value
      } else if (field === 'data') {
        // 多行 data 用换行拼接（SSE 规范）
        dataLines.push(value)
      }
      // id / retry 字段本项目暂不使用
    }

    if (dataLines.length === 0) return null
    return { event: eventName, data: dataLines.join('\n') }
  }

  /** 流结束时调用：尝试把缓冲区中最后一个未闭合事件也解析出来 */
  flush () {
    const rest = this.buffer.trim()
    this.buffer = ''
    return rest ? SseParser.parseEventBlock(rest) : null
  }
}

/**
 * 将 fetch 的 ReadableStream 包装为「逐事件」的异步迭代器
 * @param {ReadableStream<Uint8Array>} stream 响应体流
 * @yields {{event:string, data:string}} SSE 事件
 */
export async function* iterateSseStream (stream) {
  const reader = stream.getReader()
  const decoder = new TextDecoder('utf-8')
  const parser = new SseParser()

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      // stream: true 保证多字节 UTF-8 字符（中文）跨 chunk 时不被截断成乱码
      const text = decoder.decode(value, { stream: true })
      for (const evt of parser.feed(text)) {
        yield evt
      }
    }
    // 冲刷解码器与解析器中的残留数据
    const tail = decoder.decode()
    if (tail) {
      for (const evt of parser.feed(tail)) {
        yield evt
      }
    }
    const last = parser.flush()
    if (last) yield last
  } finally {
    reader.releaseLock()
  }
}

/**
 * 把事件里的 data 字符串解析成 JSON 对象（解析失败时返回原始字符串）
 * @param {string} data 事件 data 内容
 */
function safeParseJson (data) {
  if (data === undefined || data === null) return null
  const text = String(data).trim()
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch (e) {
    // 后端可能存在非 JSON 的心跳/纯文本数据，降级为字符串返回
    return text
  }
}

/**
 * 统一解析行文本（兼容不支持 ReadableStream 的环境，退化为一次性解析）
 * @param {string} text 完整响应文本
 * @param {object} handlers 事件回调集合
 */
function dispatchText (text, handlers) {
  const parser = new SseParser()
  const events = parser.feed(text)
  const last = parser.flush()
  if (last) events.push(last)

  for (const evt of events) {
    const payload = safeParseJson(evt.data)
    if (evt.event === 'meta' && handlers.onMeta) {
      handlers.onMeta(payload)
    } else if (evt.event === 'delta' && handlers.onDelta) {
      handlers.onDelta(payload)
    } else if (evt.event === 'done' && handlers.onDone) {
      handlers.onDone(payload)
    } else if (evt.event === 'error' && handlers.onError) {
      handlers.onError(payload)
    } else if (handlers.onOther) {
      // 未识别事件交给调用方兜底处理
      handlers.onOther(evt.event, payload)
    }
  }
}

/**
 * 发起流式请求并解析 SSE（使用 fetch + ReadableStream）
 * @param {object|Function} input fetch 参数对象，或返回 Promise<Response> 的工厂函数
 *   传入工厂函数时会被调用为 input(signal)，便于把中断信号透传给底层请求
 * @param {object} handlers 事件回调
 *   - onMeta(data)  meta 事件：引用溯源信息
 *   - onDelta(data) delta 事件：正文增量
 *   - onDone(data)  done 事件：耗时/模型等统计
 *   - onError(err)  错误回调（HTTP 错误或 error 事件）
 *   - onOpen()      连接建立回调
 *   - signal        AbortSignal，用于中断生成
 * @returns {Promise<void>}
 */
export async function streamSse (input, handlers = {}) {
  const signal = handlers.signal
  // 工厂函数形态（本项目使用）：把 signal 透传下去，让 axios/fetch 能真正中断请求
  const response = typeof input === 'function'
    ? await input(signal)
    : await fetch(input, signal ? { signal } : undefined)

  // HTTP 层错误：读取响应体里的错误信息（后端仍返回 JSON 包装）
  if (!response.ok) {
    let message = `请求失败（HTTP ${response.status}）`
    try {
      const text = await response.text()
      const body = safeParseJson(text)
      if (body && typeof body === 'object' && body.message) {
        message = body.message
      } else if (typeof body === 'string' && body) {
        message = body
      }
    } catch (e) {
      // 忽略读取错误，使用默认提示
    }
    if (handlers.onError) handlers.onError({ code: response.status, message })
    return
  }

  if (handlers.onOpen) handlers.onOpen()

  // 优先使用标准 ReadableStream（现代浏览器均支持），实现真正的流式增量渲染
  if (response.body && typeof response.body.getReader === 'function') {
    try {
      for await (const evt of iterateSseStream(response.body)) {
        const payload = safeParseJson(evt.data)
        if (evt.event === 'meta' && handlers.onMeta) handlers.onMeta(payload)
        else if (evt.event === 'delta' && handlers.onDelta) handlers.onDelta(payload)
        else if (evt.event === 'done' && handlers.onDone) handlers.onDone(payload)
        else if (evt.event === 'error' && handlers.onError) handlers.onError(payload)
        else if (handlers.onOther) handlers.onOther(evt.event, payload)
      }
    } finally {
      // 用户中断时流可能未读完，这里主动取消以释放底层网络连接
      if (typeof response.body.cancel === 'function') {
        try {
          await response.body.cancel()
        } catch (e) {
          // 流已正常结束或已关闭时 cancel 会抛错，忽略即可
        }
      }
    }
    return
  }

  // 降级方案：环境不支持 response.body 时一次性读取（失去逐字效果但内容完整）
  dispatchText(await response.text(), handlers)
}

/**
 * 判断错误是否由「用户主动中断」引起（axios / fetch 的 AbortError）
 * @param {any} error 捕获到的错误对象
 */
export function isAbortError (error) {
  if (!error) return false
  // axios 取消请求时抛出 CanceledError（code = ERR_CANCELED）
  // fetch 取消请求时抛出 DOMException（name = AbortError）
  return error.name === 'AbortError' || error.code === 'ERR_CANCELED'
}

/**
 * 封装流式请求的中断控制器
 * 返回对象包含 { signal, abort }，方便页面在组件卸载或点击「停止生成」时调用
 */
export function createAbortable () {
  const controller = new AbortController()
  return {
    signal: controller.signal,
    abort: () => controller.abort()
  }
}
