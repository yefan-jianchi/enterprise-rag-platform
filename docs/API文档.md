# API 文档

> 后端基址：`http://localhost:8080` ｜ AI 服务基址：`http://localhost:8001`
> 后端在线 Swagger：`http://localhost:8080/swagger-ui.html`
> AI 服务在线文档：`http://localhost:8001/docs`

---

## 通用约定

**统一响应包装**

```json
{ "code": 0, "message": "ok", "data": { } }
```

- `code = 0` 成功，非 0 失败（`message` 为中文错误信息）
- 分页响应的 `data` 为 `{ "total": 100, "records": [] }`

**认证**：除登录接口外，所有请求需带

```
Authorization: Bearer <token>
```

**多租户**：租户信息编码在 JWT 中，服务端自动按 `tenant_id` 过滤，调用方无需传。

---

## 一、认证

### POST /api/auth/login

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"tenantCode":"demo","username":"admin","password":"admin123"}'
```

```json
{
  "code": 0, "message": "ok",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "user": {
      "id": 1, "username": "admin", "nickname": "系统管理员",
      "tenantId": 1, "tenantCode": "demo",
      "roles": ["ADMIN"],
      "permissions": ["kb:view", "kb:create", "kb:doc:upload", "chat:ask", "..."]
    }
  }
}
```

### GET /api/auth/me

返回当前登录用户信息（结构同上 `user`）。

---

## 二、知识库

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/kb/list` | 当前租户的知识库列表 |
| POST | `/api/kb` | 新建知识库 |
| DELETE | `/api/kb/{id}` | 删除知识库（含文档与索引） |

**GET /api/kb/list → data**

```json
[{
  "id": 1, "kbName": "校园服务知识库",
  "description": "校园卡、图书馆、宿舍、选课等常见问题",
  "embedModel": "mock-embed-384", "chunkSize": 500, "topK": 5,
  "docCount": 3, "chunkCount": 42,
  "createdAt": "2026-10-16 18:00:00"
}]
```

**POST /api/kb**

```json
{ "kbName": "新人手册", "description": "入职流程与制度" }
```

> 需要的权限：`kb:create`

---

## 三、文档中心

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/doc/upload` | 上传文档（multipart） |
| GET | `/api/doc/list?kbId=1` | 文档列表（含处理状态） |
| POST | `/api/doc/{id}/reindex` | 重建索引 |
| DELETE | `/api/doc/{id}` | 删除文档（连同分块与向量） |
| GET | `/api/doc/{id}/chunks` | 查看分块（调试与预览） |

**POST /api/doc/upload**

```bash
curl -X POST http://localhost:8080/api/doc/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@校园服务指南.md" -F "kbId=1"
```

**文档状态机**：`PENDING → PARSING → CHUNKING → EMBEDDING → INDEXED`，失败为 `FAILED`（原因见 `errorMsg`）。

```json
[{
  "id": 12, "kbId": 1, "docName": "校园服务指南.md", "docType": "md",
  "fileSize": 4210, "status": "INDEXED", "chunkCount": 4,
  "errorMsg": null,
  "createdAt": "2026-10-16 18:20:11", "indexedAt": "2026-10-16 18:20:14"
}]
```

> 需要的权限：`kb:doc:upload` / `kb:doc:delete` / `kb:doc:reindex`

---

## 四、对话中心（RAG）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/chat/sessions` | 会话列表 |
| POST | `/api/chat/session` | 新建会话 |
| GET | `/api/chat/messages?sessionUuid=` | 历史消息（含引用） |
| POST | `/api/chat/stream` | **SSE 流式问答** |

**POST /api/chat/stream**

```json
{ "sessionUuid": "5f2c...", "question": "校园卡丢了怎么补办？", "kbId": 1, "providerCode": "deepseek" }
```

响应 `Content-Type: text/event-stream`：

```
event: meta
data: {"traceId":"a1b2...","citations":[{"index":1,"docName":"校园服务指南.md","pageNo":2,"chunkIndex":0,"score":0.85,"snippet":"**补办流程**：本人携带身份证..."}]}

event: delta
data: {"content":"根据"}

event: delta
data: {"content":"知识库..."}

event: done
data: {"elapsedMs":1234,"provider":"mock","model":"mock-chat","promptTokens":160,"completionTokens":121,"totalTokens":281,"estimated":true}
```

> **关于 token 统计**：流式响应默认拿不到服务商的精确 usage，
> `done` 事件里的 token 是按字符数估算的（`estimated: true`），
> Java 后端用它写 `ai_call_log` 做成本审计。
> 要精确计费应开启服务商的 `stream_options.include_usage` 并改用返回的 usage。

前端处理要点：
1. 收到 `meta` 先渲染引用卡片；
2. `delta` 逐个追加到正文（打字机效果）；
3. `done` 收尾，展示耗时、模型与 token 统计。

---

## 五、智能客服（Tool Calling）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/cs/stream` | SSE 客服对话（可触发工具调用） |
| GET | `/api/cs/orders?keyword=` | 订单查询（订单号 / 手机号） |
| GET | `/api/cs/tickets` | 工单列表 |
| POST | `/api/cs/handoff` | 转人工 |

**可被模型调用的工具**

| 工具 | 参数 | 作用 |
| --- | --- | --- |
| `queryOrder` | `orderNo` 或 `phone` | 查 `cs_order`，返回订单状态、物流单号 |
| `createTicket` | `orderNo,title,content,priority` | 建工单，返回工单号 |
| `transferToHuman` | `sessionUuid,reason` | 写 `cs_handoff` 并回复「已转人工」 |

**Mock 模式下的降级策略**：没有真实模型时不做 tool calling，
改用规则意图识别（正则抓 `SO\d+` + 关键词「退款/工单/转人工」）触发同样的工具执行路径，
保证离线也能演示完整链路。

---

## 六、管理后台

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/api/admin/models` | 模型提供方列表 | `model:view` |
| PUT | `/api/admin/models/{id}` | 启停 / 调优先级 / 改模型名 / 填 Key | `model:edit` |
| GET | `/api/admin/audit?page=1&size=10` | 操作审计日志 | `audit:view` |
| GET | `/api/admin/cost?days=7` | 成本统计 | `cost:view` |
| GET | `/api/admin/prompts` | Prompt 模板列表 | `prompt:manage` |
| PUT | `/api/admin/prompts/{id}` | 修改模板内容 | `prompt:manage` |

**GET /api/admin/cost?days=7 → data**

```json
{
  "totalTokens": 128430, "totalCost": 1.86,
  "byDay":  [{ "date": "2026-10-16", "tokens": 20310, "cost": 0.29 }],
  "byModel":[{ "model": "deepseek-chat", "tokens": 98200, "cost": 1.42, "calls": 318 }]
}
```

---

## 七、AI 服务接口（内部调用，也可直连调试）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 健康检查（含当前模型提供方、FAISS 是否可用） |
| POST | `/api/v1/documents/parse` | 按服务端路径解析 |
| POST | `/api/v1/documents/upload` | 直接上传解析（独立演示用） |
| POST | `/api/v1/index/build` | 建 / 重建索引 |
| POST | `/api/v1/index/delete-doc` | 删除某文档的向量 |
| GET | `/api/v1/kb/{kb_id}/stats` | 索引统计（向量数、BM25 文档数） |
| POST | `/api/v1/retrieve` | 混合检索（4 种策略） |
| POST | `/api/v1/rag/ask` | RAG 问答（非流式） |
| POST | `/api/v1/rag/stream` | RAG 问答（SSE 流式） |
| POST | `/api/v1/langchain/rag/ask` | **LangChain LCEL 链**版本的 RAG 问答 |

**POST /api/v1/retrieve**

```json
{
  "kb_id": 1, "query": "校园卡丢了怎么补办？",
  "strategy": "HYBRID_RERANK", "top_k": 5, "candidate_k": 20
}
```

`strategy` 可选：`VECTOR` / `BM25` / `HYBRID_RRF` / `HYBRID_RERANK`

```json
{
  "kb_id": 1, "query": "校园卡丢了怎么补办？",
  "rewritten_queries": ["校园卡 补办 规定 说明 流程"],
  "hyde_document": "关于校园卡、补办，学校相关规定如下：...",
  "strategy": "HYBRID_RERANK", "total_candidates": 4,
  "results": [{
    "chunk_id": 90010001, "doc_id": 1, "doc_name": "校园服务指南.md",
    "chunk_index": 0, "page_no": 1,
    "content": "**补办流程**：本人携带身份证或学生证...",
    "score": 0.85, "vector_score": 0.31, "bm25_score": 10.58,
    "rrf_score": 0.114, "rerank_score": 0.85, "rank": 1
  }],
  "elapsed_ms": 10
}
```

> 响应里同时给出 `vector_score / bm25_score / rrf_score / rerank_score`，
> 便于调试「这一条到底是哪一路召回的」——排查召回问题时非常有用。

**POST /api/v1/langchain/rag/ask**

与 `/api/v1/rag/ask` 请求体完全一致，返回结构也一致（`answer` + `citations`），
区别是内部走 LangChain 的 LCEL 链：

```
RagRetriever(自研混合检索) | format_docs | ChatPromptTemplate | ChatModel | StrOutputParser
```

两条链路**共用同一套 FAISS + BM25 索引**，所以引用结果完全一致（有测试断言保证）。
`provider` 字段返回 `langchain` 以示区分。

```bash
curl -X POST http://localhost:8001/api/v1/langchain/rag/ask \
  -H "Content-Type: application/json" \
  -d '{"kb_id":1,"question":"校园卡丢了怎么补办？","top_k":3,"strategy":"HYBRID_RERANK"}'
```

---

## 八、错误码

| code | 含义 | 处理 |
| --- | --- | --- |
| 0 | 成功 | — |
| 401 | 未认证 / token 过期 | 前端跳登录 |
| 403 | 无权限 | 提示缺少权限码 |
| 404 | 资源不存在 | — |
| 1001 | 参数校验失败 | 检查请求体 |
| 2001 | 文档解析失败 | 看 `errorMsg` |
| 2002 | 索引构建失败 | 看 AI 服务日志 |
| 3001 | 模型调用失败（已尝试 Fallback） | 检查 API Key 与配额 |
| 5000 | 系统异常 | 看后端日志 |
