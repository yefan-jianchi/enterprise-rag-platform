/**
 * SSE 集成自测脚本（Node 20 原生支持 ReadableStream / TextDecoder / AbortController）
 * 运行：node test/sse-stream.test.mjs
 *
 * 验证 utils/sse.js 的 streamSse 在真实流式场景下的行为：
 *   1. 跨 chunk 的半截事件能否正确拼接
 *   2. 多字节中文字符被按字节切开时是否出现乱码
 *   3. HTTP 错误是否回落到后端 message
 *   4. 用户中断（AbortError）是否被正确识别
 *
 * 注意：streamSse 的第一个参数在「非函数」时会被当作 URL 交给 fetch，
 * 因此这里统一传「返回假 Response 的工厂函数」，与生产代码里 axios 的调用形态一致。
 */
import { streamSse, isAbortError } from '../src/utils/sse.js'

let passed = 0
let failed = 0

function assert (name, actual, expected) {
  const a = JSON.stringify(actual)
  const e = JSON.stringify(expected)
  if (a === e) {
    passed++
    console.log(`  [PASS] ${name}`)
  } else {
    failed++
    console.log(`  [FAIL] ${name}\n         期望: ${e}\n         实际: ${a}`)
  }
}

/**
 * 构造一个按给定分片顺序输出的假 Response
 * @param {string[]} chunks 文本分片
 * @param {object} [init] 覆盖 status / body
 */
function fakeResponse (chunks, init = {}) {
  const encoder = new TextEncoder()
  const stream = new ReadableStream({
    start (controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk))
      }
      controller.close()
    }
  })
  return {
    ok: init.ok !== undefined ? init.ok : true,
    status: init.status || 200,
    body: init.body === null ? null : stream,
    text: async () => chunks.join('')
  }
}

/** 构造按字节切分的假 Response（模拟最极端的网络分片） */
function fakeResponseFromBytes (byteChunks) {
  const stream = new ReadableStream({
    start (controller) {
      for (const chunk of byteChunks) controller.enqueue(chunk)
      controller.close()
    }
  })
  return { ok: true, status: 200, body: stream }
}

/** 把文本拆成单个字节的 Uint8Array 列表 */
function byBytes (text) {
  return [...new TextEncoder().encode(text)].map((b) => new Uint8Array([b]))
}

// ---------- 用例 1：完整流式流程 ----------
{
  console.log('用例 1：meta -> delta -> done 完整流程')
  const response = fakeResponse([
    'event: meta\ndata: {"traceId":"t1","citations":[{"index":1,"docName":"校园服务指南.md","pageNo":2,"chunkIndex":0,"score":0.87,"snippet":"..."}]}\n\n',
    'event: delta\ndata: {"content":"你好"}\n\n',
    'event: delta\ndata: {"content":"，世界"}\n\n',
    'event: done\ndata: {"elapsedMs":1234,"provider":"mock","model":"mock-chat","totalTokens":100}\n\n'
  ])

  let meta = null
  let text = ''
  let done = null
  await streamSse(() => response, {
    onMeta: (d) => { meta = d },
    onDelta: (d) => { text += d.content },
    onDone: (d) => { done = d }
  })

  assert('meta 被解析', meta && meta.traceId, 't1')
  assert('引用数量', meta.citations.length, 1)
  assert('引用相关度', meta.citations[0].score, 0.87)
  assert('正文逐字拼接', text, '你好，世界')
  assert('done 事件耗时', done.elapsedMs, 1234)
  assert('done 事件模型', done.model, 'mock-chat')
}

// ---------- 用例 2：按字节切分 + 中文多字节字符 ----------
{
  console.log('用例 2：按单字节切分，中文（3 字节 UTF-8）跨 chunk 不出现乱码')
  const raw = 'event: meta\ndata: {"citations":[{"index":1,"docName":"测试文档.md","score":0.5}]}\n\n' +
    'event: delta\ndata: {"content":"校园卡补办流程"}\n\n' +
    'event: delta\ndata: {"content":"：请携带身份证"}\n\n' +
    'event: done\ndata: {"elapsedMs":88,"model":"mock"}\n\n'

  let text = ''
  let citationName = ''
  let done = null
  await streamSse(() => fakeResponseFromBytes(byBytes(raw)), {
    onMeta: (d) => { citationName = d.citations[0].docName },
    onDelta: (d) => { text += d.content },
    onDone: (d) => { done = d }
  })

  assert('中文文档名无乱码', citationName, '测试文档.md')
  assert('中文正文无乱码', text, '校园卡补办流程：请携带身份证')
  assert('done 仍被解析', done && done.elapsedMs, 88)
}

// ---------- 用例 3：HTTP 错误回落到后端 message ----------
{
  console.log('用例 3：HTTP 401 时读取后端 message')
  const response = fakeResponse(['{"code":401,"message":"登录已过期，请重新登录","data":null}'], {
    ok: false,
    status: 401
  })
  let err = null
  await streamSse(() => response, { onError: (e) => { err = e } })
  assert('错误码', err && err.code, 401)
  assert('错误信息取自后端', err && err.message, '登录已过期，请重新登录')
}

// ---------- 用例 4：无 response.body 时降级解析 ----------
{
  console.log('用例 4：不支持 ReadableStream 时降级为一次性解析')
  const response = fakeResponse(
    ['event: delta\ndata: {"content":"降级"}\n\nevent: done\ndata: {"elapsedMs":1}\n\n'],
    { body: null }
  )
  let text = ''
  let done = null
  await streamSse(() => response, {
    onDelta: (d) => { text += d.content },
    onDone: (d) => { done = d }
  })
  assert('降级路径仍能取到正文', text, '降级')
  assert('降级路径仍能取到 done', done && done.elapsedMs, 1)
}

// ---------- 用例 5：中断识别 ----------
{
  console.log('用例 5：中断错误识别')
  const abortError = new Error('canceled')
  abortError.name = 'AbortError'
  const axiosCancel = new Error('canceled')
  axiosCancel.code = 'ERR_CANCELED'

  assert('识别 DOMException AbortError', isAbortError(abortError), true)
  assert('识别 axios ERR_CANCELED', isAbortError(axiosCancel), true)
  assert('普通错误不误判', isAbortError(new Error('network')), false)
}

// ---------- 用例 6：真实中断流 ----------
{
  console.log('用例 6：流读到一半被中断，中断前内容保留且错误可识别')
  const encoder = new TextEncoder()
  const stream = new ReadableStream({
    async start (c) {
      c.enqueue(encoder.encode('event: delta\ndata: {"content":"第一段"}\n\n'))
      // 稍后主动让流报错，模拟用户点击「停止生成」
      await new Promise((resolve) => setTimeout(resolve, 20))
      c.error(Object.assign(new Error('canceled'), { name: 'AbortError' }))
    }
  })

  let text = ''
  let caughtIsAbort = false
  try {
    await streamSse(() => ({ ok: true, status: 200, body: stream }), {
      onDelta: (d) => { text += d.content }
    })
  } catch (e) {
    caughtIsAbort = isAbortError(e)
  }
  assert('中断前的内容已渲染', text, '第一段')
  assert('中断被识别为 AbortError', caughtIsAbort, true)
}

// ---------- 用例 7：signal 透传给请求工厂 ----------
{
  console.log('用例 7：handlers.signal 会透传给请求工厂函数（保证「停止生成」真正中断底层请求）')
  const controller = new AbortController()
  const response = fakeResponse(['event: delta\ndata: {"content":"ok"}\n\n'])
  let receivedSignal = null

  await streamSse(
    (signal) => {
      receivedSignal = signal
      return Promise.resolve(response)
    },
    { signal: controller.signal, onDelta: () => {} }
  )

  assert('工厂函数收到的是同一个 signal', receivedSignal === controller.signal, true)
  assert('未中断时 signal.aborted 为 false', receivedSignal.aborted, false)

  // 中断后 signal 状态应变为 true
  controller.abort()
  assert('abort() 后 signal.aborted 为 true', receivedSignal.aborted, true)
}

console.log(`\n测试结果：通过 ${passed} 项，失败 ${failed} 项`)
process.exit(failed === 0 ? 0 : 1)
