/**
 * 临时验证用 Mock 后端（仅用于验证 vite 代理转发，不参与正式交付）
 * 监听 8080，按后端契约返回统一响应包装结构
 * 运行：node test/mock-backend.mjs
 */
import { createServer } from 'node:http'

const server = createServer((req, res) => {
  // 模拟 Java 后端的统一响应包装
  const body = JSON.stringify({
    code: 0,
    message: 'ok',
    data: [
      {
        id: 1,
        kbName: '校园服务指南',
        description: '来自 mock 后端，用于验证 vite 代理转发',
        embedModel: 'mock-embed',
        chunkSize: 512,
        topK: 5,
        docCount: 3,
        chunkCount: 42,
        createdAt: '2026-01-01T10:00:00'
      }
    ]
  })
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'X-Mock-Backend': 'true',
    'X-Received-Path': req.url
  })
  res.end(body)
})

server.listen(8080, '127.0.0.1', () => {
  console.log('mock backend listening on http://127.0.0.1:8080')
})
