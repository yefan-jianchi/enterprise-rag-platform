# 企业级多模型 RAG 知识库与智能客服平台

> Spring Boot 3 + Spring AI/LangChain4j + Python LangChain/FAISS + Vue3 + MySQL/Redis/MinIO + Docker Compose
>
> 一个可以直接写进简历、也能在面试里被追问到底的 **Java + AI 全栈项目**

---

## 一、这是什么

把「网页 AI 小助手」升级成**企业级系统**：多租户、RBAC 权限、文档流水线、混合检索、
引用溯源、Tool Calling 智能客服、成本审计，以及 Docker 一键部署。

一句话卖点：

> **不是调个 API 套个壳** —— 检索链路（查询改写 → 向量 + BM25 双路召回 → RRF 融合 → 重排 → 引用溯源）
> 全部自己实现，并且有一套可复现的**检索命中率评测**来证明有效。

---

## 二、架构

```
                        ┌──────────────────────────────────────────┐
   浏览器  ──────────▶  │  Nginx  (统一入口, 反代 + SSE 不缓冲)      │
                        └───────┬───────────────┬──────────────────┘
                                │ /             │ /api
                        ┌───────▼──────┐  ┌─────▼─────────────────────────┐
                        │ 前端 Vue3     │  │ 后端 Spring Boot 3 (Java 21)   │
                        │ Element Plus  │  │  · 认证 JWT + RBAC + 多租户     │
                        │ SSE 流式对话  │  │  · 文档中心（异步流水线）        │
                        │ 引用溯源面板  │  │  · 多模型适配层 + Fallback      │
                        └──────────────┘  │  · Tool Calling 智能客服        │
                                          │  · 审计 / 成本统计              │
                                          └───┬─────────┬─────────┬───────┘
                                              │         │         │
                                   RestClient │         │         │ JDBC
                                              ▼         ▼         ▼
                        ┌──────────────────────┐  ┌────────┐ ┌────────┐
                        │ AI 服务 Python        │  │ Redis  │ │ MySQL  │
                        │ · 解析 PDF/Word/MD    │  │ 会话缓存│ │ 19 张表 │
                        │ · 分块(带页码/偏移)   │  └────────┘ └────────┘
                        │ · 向量化(FAISS)       │
                        │ · BM25 + 向量混合检索 │  ┌────────┐
                        │ · RRF 融合 + 重排     │  │ MinIO  │  原始文档对象存储
                        │ · 查询改写 + HyDE     │  └────────┘
                        └──────────────────────┘
```

**为什么要拆成 Java + Python 两个服务？**
Java 擅长企业级业务（事务、权限、多租户、审计），Python 的 AI 生态最全（FAISS、LangChain、解析库）。
两者职责边界清晰：**Java 管业务与数据，Python 管算法与索引**，通过 REST 通信。

---

## 三、技术栈

| 层 | 技术 | 说明 |
| --- | --- | --- |
| 后端 | Spring Boot **3.4.5** · Java 21 · Spring Security · Spring Data JPA · SseEmitter | JWT 认证、RBAC 权限、多租户隔离、SSE 流式 |
| 模型接入 | **Spring AI 1.0** + 自研 OpenAI 兼容适配层（**并存，可切换**） | `app.ai.engine=legacy\|spring-ai`；含离线 Mock 与失败 Fallback |
| AI 服务 | Python 3.12 · FastAPI · **LangChain 1.4** · FAISS · jieba | 解析/分块/向量化/混合检索/重排 |
| 前端 | Vue3 · Vite · Element Plus · Pinia · 原生 SSE 解析 | 流式对话、引用溯源、知识库管理 |
| 存储 | MySQL 8（业务）· Redis（会话缓存）· MinIO（文档对象） | 19 张表，含审计与成本 |
| 部署 | Docker Compose · Nginx · Linux | 7 个容器一键起 |

---

## 四、核心功能

### 1. 文档中心
上传 PDF / Word / Markdown → 异步解析 → 分块 → 向量化 → 建索引，状态机
`PENDING → PARSING → CHUNKING → EMBEDDING → INDEXED`（失败置 `FAILED` 并记录原因）。

**关键设计**：解析时保留**页码与字符偏移**，否则引用溯源无从谈起。

### 2. RAG 引擎
```
问题 ──┬─ 查询改写（3 条同义查询）
       ├─ HyDE（先生成假设答案，再拿它去检索）
       ▼
   ┌─────────┐        ┌──────────┐
   │ 向量检索 │        │ BM25 检索 │
   └────┬────┘        └─────┬────┘
        └──── RRF 融合 ─────┘
                 │
           重排（IDF 加权 / LLM 打分）
                 │
          Top-K + 引用溯源（文档 + 页码 + 片段 + 相关度）
```

### 3. 对话中心
多轮会话、SSE 流式输出、多模型切换、Prompt 模板管理（DB 可改，不重启）。

### 4. 智能客服（Tool Calling）
三个工具：`queryOrder` 查订单、`createTicket` 建工单、`transferToHuman` 转人工。
- 真实模型：模型自己决定调哪个工具
- Mock 模式：规则意图识别（正则抓订单号 `SO\d+` + 关键词），**离线也能演示完整链路**

### 5. 权限与审计
RBAC（用户-角色-权限三级）、多租户行级隔离、操作审计日志、
每次模型调用都记录 token 与成本，可按租户/模型/日期统计。

### 6. LangChain 双实现（同一套索引，两条路径）

检索链路我写了两遍，**共用同一套索引与检索器**：

| 路径 | 接口 | 特点 |
| --- | --- | --- |
| 手写链路 | `POST /api/v1/rag/ask` | RRF、阈值、分数归一化全部可控，便于调参 |
| LangChain 编排 | `POST /api/v1/langchain/rag/ask` | LCEL 组装 `retriever \| prompt \| llm \| parser`，接入生态组件快 |

- `RagRetriever(BaseRetriever)`：把自研混合检索暴露成 LangChain 标准 Retriever
  （返回的 `Document` 带 `doc_name / page_no / chunk_id / score` 元数据，引用溯源照样能做）
- `MockChatModel(BaseChatModel)`：把离线 Mock 模型包装成 LangChain ChatModel，**没有 Key 也能跑 LCEL 链**
- 有真实 Key 时自动切 `langchain_openai.ChatOpenAI`（DeepSeek / 通义都是 OpenAI 兼容协议）

```bash
cd ai-service && python tests/test_langchain_bridge.py
```

测试里有条硬断言：**两条链路必须召回完全相同的 chunk**——
这反过来证明我清楚 LangChain 在底层做了什么，而不是只会调包。

---

## 五、实测效果（可复现）

```bash
cd ai-service && python tests/test_pipeline.py
```

8 条评测问题（校园服务 + 产品售后，两份真实文档，共 11 个分块）：

| 策略 | Hit@1 | Hit@3 | Hit@5 | MRR |
| --- | --- | --- | --- | --- |
| VECTOR（纯向量） | 50.0% | 100% | 100% | 0.729 |
| BM25（纯关键词） | 100% | 100% | 100% | 1.000 |
| HYBRID_RRF（混合 + RRF） | 87.5% | 100% | 100% | 0.938 |
| **HYBRID_RERANK（+ 重排）** | **87.5%** | **100%** | **100%** | **0.938** |

**怎么诚实解读这组数字**（面试会被追问，先想清楚）：

- **纯向量 50% → 混合检索 87.5%**，说明单路向量召回确实不够，混合是必要的。
- **但这个语料下 BM25 单独就是 100%**：总共才 11 个块，词面重叠信号非常充足，
  语义检索的增量体现不出来。**混合检索的收益要等语料变大、问题变口语化时才明显**，
  这是真实工程结论，不为了好看去编。
- **Hit@3 / Hit@5 全部 100%**，而 RAG 真正关心的是这个：
  答案只要进了 Top-K，LLM 就能基于上下文回答，用户感知不到它排第几。
- 唯一的 Hit@1 未命中是「**耳机退款多久到账**」——它跨越两个小节
  （耳机说明在节 3、退款政策在节 4），任何单块都装不下完整答案。
  正确做法是取 Top-K 让两块一起进上下文，**而不是调参把这个 case 刷上去**
  （那样只会过拟合到评测集）。
- 评测集只有 8 条、单条权重 12.5%，**统计意义有限**，扩充到 100+ 条是下一步重点。

> 跑测试时会打印上述解读，避免只看一个数字就下结论。

---

## 五之二、全链路验收证据（真机跑出来的，不是"设计如此"）

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1
```

**结果：24 项全部通过。** 这条链路是：前端 → 后端 → AI 服务 → MySQL，全部真实调用。

| 环节 | 实测证据 |
| --- | --- |
| 认证与权限 | `/api/auth/login` 拿到 token；用户 `admin`、租户 `demo(id=1)`、角色 `ADMIN`、**19 项权限** |
| 知识库 | 2 个知识库，文档数与分块数正确回显 |
| 文档流水线 | 上传 `.docx` → `PENDING → INDEXED`，分块落库**带页码** |
| 向量索引 | AI 服务 `vectors=7 bm25=7 dim=384`（确认索引真的建起来了） |
| RAG 流式对话 | SSE 收到 **meta 1 个 + delta 30 个 + done 1 个**；**2 条引用溯源**；对话落库 2 条消息 |
| 智能客服 | 4 条订单 / 4 条工单；SSE 8 个事件；**Tool Calling 在回答里命中订单号 `SO20261014004`**；转人工成功 |
| 审计 | 29 条操作审计日志（LOGIN / DOC_UPLOAD / CHAT_STREAM / CS_HANDOFF ...） |
| 成本记账 | **448 tokens / ￥0.000874 / 11 次调用**，byDay / byModel / byProvider 均有数据 |
| 部署配置 | `scripts/check-deploy.py` 静态校验通过（7 服务、healthcheck、依赖条件、Nginx SSE 三件套） |

**排查过程中修掉的真实 bug（都是这次验收逼出来的）**：

1. **成本报表恒返回 0**：`ai_call_log` 里明明有 7 条记录（322 tokens / ￥0.000628），
   但 JPQL 多列查询用 `Object[]` 接收时被**双重包装**，`summary.length >= 3` 守卫不成立，
   于是静默返回 0 —— 不报错，最难发现。改成原生查询 + `List<Object[]>` 后正常。
2. **`dev.ps1` 启动脚本被 mysql 的 stderr 警告中断**：PowerShell 5.1 在
   `$ErrorActionPreference='Stop'` 下会把原生命令的 stderr 包装成 `NativeCommandError`，
   **光加 `2>$null` 挡不住**，必须临时把 EAP 降成 Continue。
3. **验收脚本自身的两个坑**：`Invoke-RestMethod` 对没带 charset 的 UTF-8 响应按 Latin-1 解码（中文全乱）；
   上传接口返回字段是 `id` 而不是 `docId`。都已修，并在脚本头部写明注释避免重犯。

> 这三个 bug 都有个共同点：**不报错，只是结果不对**。所以验收断言必须写成
> 「有真实数据」而不是「接口没报错」——这也是我把成本断言从 `code==0` 加严到
> `totalCalls>0 && totalTokens>0` 的原因。

---

## 六、快速开始

### 方式 A：Docker 一键部署（推荐，7 个容器）

```bash
cd deploy
cp .env.example .env          # 按需填模型 API Key
docker compose up -d --build
```

| 入口 | 地址 |
| --- | --- |
| 前端 | http://localhost |
| AI 服务接口文档 | http://localhost:8001/docs |
| MinIO 控制台 | http://localhost:9001 |

默认账号：租户 `demo` / 用户 `admin` / 密码 `admin123`（后端首次启动自动创建）

### 方式 B：本地开发（不需要 Docker / Redis / MinIO）

本机只要有一个 MySQL 8：

```powershell
# 1. 初始化数据库（建 19 张表 + 种子数据）
powershell -ExecutionPolicy Bypass -File .\scripts\init-db.ps1

# 2. 一键起 AI 服务 + 后端 + 前端
powershell -ExecutionPolicy Bypass -File .\scripts\dev.ps1

# 3. 停止
powershell -ExecutionPolicy Bypass -File .\scripts\stop-dev.ps1
```

> 本地没有 Redis / MinIO 时会自动降级为**内存缓存 + 本地文件存储**
> （见 `app.cache.type` / `app.storage.type`，Docker 里用环境变量切回 Redis / MinIO）。

### 不填任何 API Key 也能跑
默认 `LLM_PROVIDER=mock` + `EMBED_PROVIDER=mock`：
- **向量**：jieba 分词 + 字符二元组 → crc32 哈希到 384 维 → L2 归一化（确定性，跨进程一致）
- **模型**：按 Prompt 语义从检索到的上下文里挑句子组装回答

想看真实模型效果，把 `DEEPSEEK_API_KEY` 填进 `ai-service/.env`，`LLM_PROVIDER` 改成 `deepseek` 即可。

> **关于 `tools/` 目录**：克隆下来后你不会看到它——里面是便携版 JDK 21 / Node 20 / Git，
> 总共约 250 MB，已在 `.gitignore` 里排除。
> 那是我在**没有管理员权限、系统又没装这些工具**的机器上为了把项目跑起来而下载的，
> 你自己按上面的「环境要求」装标准版本即可，脚本会自动优先使用 `tools/` 下的便携版。

---

## 七、目录结构

```
enterprise-rag-platform/
├── ai-service/                  # Python AI 服务
│   ├── app/
│   │   ├── config.py            # 配置（含 Windows 中文路径降级）
│   │   ├── schemas.py           # Pydantic 请求/响应模型
│   │   ├── main.py              # FastAPI 入口 + SSE
│   │   └── core/
│   │       ├── parser.py        # PDF/Word/MD 解析（保留页码）
│   │       ├── chunker.py       # 结构化分块 + 重叠
│   │       ├── tokenizer.py     # jieba 分词 + 字符二元组
│   │       ├── embedding.py     # 多提供方向量化（含 Mock）
│   │       ├── vector_store.py  # FAISS IndexIDMap2 封装
│   │       ├── bm25.py          # 自实现 Okapi BM25
│   │       ├── retrieval.py     # 混合检索 + RRF + 重排
│   │       └── llm.py           # DeepSeek/通义/OpenAI 统一客户端
│   │   └── integrations/
│   │       └── langchain_bridge.py  # 自研检索器 → LangChain Retriever + LCEL 链
│   ├── data/samples/            # 演示文档（校园服务 / 产品售后 / PDF / Word）
│   └── tests/
│       ├── test_pipeline.py           # RAG 全链路 + 四策略命中率对比
│       ├── test_parsers.py            # PDF / Word / Markdown 解析与页码溯源
│       ├── test_langchain_bridge.py   # LangChain 与手写链路召回一致性
│       └── make_fixtures.py           # 生成 PDF/Word 测试样本
├── backend/                     # Spring Boot 3 后端（Java 21）
├── frontend/                    # Vue3 + Vite 前端
├── db/                          # schema.sql / seed.sql
├── deploy/                      # docker-compose + nginx + 三个 Dockerfile
├── scripts/                     # init-db / dev / stop-dev（一键启停）
│   ├── smoke-test.ps1           # 全链路冒烟验收（24 项，配置好即为回归测试）
│   └── check-deploy.py          # 部署配置静态校验（无 Docker 环境下也能查）
└── docs/                        # 架构设计、API、部署手册、简历话术、技术栈偏差说明
```

---

## 八、开发中踩到的坑（面试常被追问）

1. **FAISS 在 Windows 中文路径下写不了索引**
   `faiss.write_index` 底层 C++ 用 `fopen`（ANSI），路径里有中文直接报
   `could not open ... No such file or directory`。
   → 解决方案：`config.py` 里检测项目路径是否含非 ASCII 字符，自动把数据目录降级到
   `%LOCALAPPDATA%`（容器里是纯英文路径，不受影响）。

2. **分块重叠导致页码溯源全体漂移**
   带 overlap 时，如果只更新缓冲区内容不更新起始偏移，后续所有块的
   `char_start / page_no` 都会停在第一块。
   → 在 `flush()` 里用 `content_end - len(tail)` 反推重叠起点，页码才准。

3. **Nginx 反代下 SSE 变成「憋到最后一次性返回」**
   少一个 `proxy_buffering off` 就会出现。
   → 同时还要 `proxy_cache off` + `proxy_read_timeout 300s`，三件套缺一不可。

4. **`$D:s=640x360` 在 PowerShell 里被当成命名空间变量**（视频项目踩的，同款教训）
   → 变量插值要用 `${D}`。

---

## 九、待完善

- [ ] **检索评测集扩充到 100+ 条**，并引入 RAGAS 风格的答案质量评估（当前 8 条统计意义不足）
- [ ] 跨小节复合问题（如「耳机退款多久到账」）引入 **MMR 多样性选择**，让 Top-K 覆盖不同语义切面
- [ ] 向量库支持 Milvus（当前 FAISS 为单机内存索引，适合中小规模）
- [ ] 文档解析增加 OCR（扫描版 PDF）
- [ ] 引入消息队列（RocketMQ/Kafka）替代进程内异步，支持多实例并行消费
- [ ] 前端增加引用高亮定位到原文

---

## 十、作者

范晔 · 烟台理工学院 · 计算机相关专业
（本项目的架构设计、检索算法、部署编排均为独立实现）
