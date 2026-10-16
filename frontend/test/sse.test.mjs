/**
 * SSE 解析器自测脚本（不依赖浏览器环境）
 * 运行方式：node test/sse.test.mjs
 *
 * 重点验证：网络分片不会按事件边界切分，解析器必须能正确处理跨 chunk 的半截事件。
 */
import { SseParser } from '../src/utils/sse.js'

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

// ---------- 用例 1：一次拿到完整事件 ----------
{
  console.log('用例 1：单个完整 chunk 包含两个事件')
  const parser = new SseParser()
  const events = parser.feed('event: meta\ndata: {"traceId":"t1"}\n\nevent: delta\ndata: {"content":"你好"}\n\n')
  assert('事件数量', events.length, 2)
  assert('第一个事件名', events[0].event, 'meta')
  assert('第一个事件数据', events[0].data, '{"traceId":"t1"}')
  assert('第二个事件名', events[1].event, 'delta')
}

// ---------- 用例 2：事件被切成两半 ----------
{
  console.log('用例 2：一个事件跨两个 chunk（在 data 中途断开）')
  const parser = new SseParser()
  const first = parser.feed('event: delta\ndata: {"cont')
  assert('半截事件不应被解析', first.length, 0)
  const second = parser.feed('ent":"增量文字"}\n\n')
  assert('补齐后解析出 1 个事件', second.length, 1)
  assert('事件名', second[0].event, 'delta')
  assert('数据完整', second[0].data, '{"content":"增量文字"}')
}

// ---------- 用例 3：空行本身被拆分（\n 与 \n 分属两个 chunk）----------
{
  console.log('用例 3：事件分隔空行被拆到两个 chunk')
  const parser = new SseParser()
  assert('第一个 chunk 无完整事件', parser.feed('event: done\ndata: {"elapsedMs":12}\n').length, 0)
  const events = parser.feed('\nevent: delta\ndata: {"content":"a"}\n\n')
  assert('第二个 chunk 解析出 2 个事件', events.length, 2)
  assert('第一个事件名', events[0].event, 'done')
  assert('第二个事件名', events[1].event, 'delta')
}

// ---------- 用例 4：逐字节切割（最极端的分片）----------
{
  console.log('用例 4：把整个流按单字节逐段送入')
  const raw = 'event: meta\ndata: {"citations":[{"index":1}]}\n\n' +
    'event: delta\ndata: {"content":"分片测试"}\n\n' +
    'event: done\ndata: {"elapsedMs":1234,"provider":"mock","model":"mock-chat","totalTokens":100}\n\n'
  const parser = new SseParser()
  const collected = []
  for (const ch of raw) {
    collected.push(...parser.feed(ch))
  }
  assert('共解析出 3 个事件', collected.length, 3)
  assert('事件顺序', collected.map((e) => e.event), ['meta', 'delta', 'done'])
  assert('done 数据完整', JSON.parse(collected[2].data).elapsedMs, 1234)
}

// ---------- 用例 5：多行 data 与注释行 ----------
{
  console.log('用例 5：多行 data 拼接、注释心跳行被忽略')
  const parser = new SseParser()
  const events = parser.feed(': keep-alive\n\nevent: delta\ndata: 第一行\ndata: 第二行\n\n')
  assert('注释块不产生事件', events.length, 1)
  assert('多行 data 用换行拼接', events[0].data, '第一行\n第二行')
}

// ---------- 用例 6：CRLF 换行 ----------
{
  console.log('用例 6：CRLF 换行兼容')
  const parser = new SseParser()
  const events = parser.feed('event: delta\r\ndata: {"content":"crlf"}\r\n\r\n')
  assert('CRLF 事件被正确解析', events.length, 1)
  assert('数据正确', JSON.parse(events[0].data).content, 'crlf')
}

// ---------- 用例 7：冒号后无空格 ----------
{
  console.log('用例 7：data: 后没有空格')
  const parser = new SseParser()
  const events = parser.feed('event:delta\ndata:{"content":"nospace"}\n\n')
  assert('事件名解析正确', events[0].event, 'delta')
  assert('数据解析正确', JSON.parse(events[0].data).content, 'nospace')
}

// ---------- 用例 8：flush 处理未闭合的尾部事件 ----------
{
  console.log('用例 8：流结束时冲刷未闭合事件')
  const parser = new SseParser()
  parser.feed('event: done\ndata: {"elapsedMs":9}')
  const last = parser.flush()
  assert('flush 返回最后一个事件', last && last.event, 'done')
  assert('flush 数据完整', JSON.parse(last.data).elapsedMs, 9)
}

// ---------- 用例 9：中途出现 unknown 事件与空 data ----------
{
  console.log('用例 9：无 data 字段的事件被忽略')
  const parser = new SseParser()
  const events = parser.feed('event: ping\n\n')
  assert('无 data 不产生事件', events.length, 0)
}

console.log(`\n测试结果：通过 ${passed} 项，失败 ${failed} 项`)
process.exit(failed === 0 ? 0 : 1)
