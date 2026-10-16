# 企业级多模型 RAG 知识库与智能客服平台 —— 前端（Vue 3）

本目录是平台的 Web 前端，对接 Java 后端（`http://localhost:8080`）与 Python AI 服务。
技术栈：**Vue 3 + Vite 5 + JavaScript + Element Plus + Pinia + Vue Router 4 + axios**，
未使用 TypeScript 与 TailwindCSS，保证开箱即跑、构建简单。

---

## 一、为什么流式对话没有用原生 EventSource

浏览器的 `EventSource` **只支持 GET 请求**，无法携带 JSON 请求体，而本项目的
`POST /api/chat/stream`、`POST /api/cs/stream` 都需要提交
`{sessionUuid, question, kbId, providerCode}`。

因此流式对话统一采用 **`fetch`/axios + `ReadableStream` + `TextDecoder`** 手动解析
`text/event-stream`。核心实现在 `src/utils/sse.js`：

- `SseParser`：增量式解析器，按「空行」切分事件块，**维护跨 chunk 缓冲**，
  保证被网络分片切开的半截事件能正确拼接（这是最容易出错的地方）；
- `iterateSseStream`：把 `ReadableStream` 包装成逐事件的异步迭代器，
  使用 `TextDecoder({ stream: true })` 避免中文等 **多字节 UTF-8 字符跨分片变成乱码**；
- `streamSse`：串联「请求 → 解析 → 事件分发」，支持 `AbortSignal` 中断，
  并在中断时主动 `cancel()` 底层流以释放连接。

事件处理顺序严格按契约：`meta`（渲染引用卡片）→ `delta`（逐字追加正文）→ `done`（显示耗时/模型）。

---

## 二、目录结构

```
frontend/
├── index.html                  # 入口 HTML
├── vite.config.js              # 端口 5173，/api 代理到 localhost:8080，@ 别名，分包配置
├── package.json                # 依赖与脚本（dev / build / preview / test）
├── .npmrc                      # 固定国内镜像 registry.npmmirror.com
└── src/
    ├── main.js                 # 应用入口：注册 Element Plus（中文语言包）与全部图标
    ├── App.vue                 # 根组件
    ├── api/                    # 接口层（按后端模块拆分）
    │   ├── request.js          # axios 统一封装：注入 token、解包 data、code!=0 抛错、401 跳登录
    │   ├── auth.js             # 登录 / 当前用户
    │   ├── kb.js               # 知识库
    │   ├── doc.js              # 文档（含 multipart 上传）
    │   ├── chat.js             # RAG 会话与流式问答
    │   ├── cs.js               # 智能客服（流式 + 订单 + 工单 + 转人工）
    │   └── admin.js            # 模型配置 / 审计 / 成本 / 提示词
    ├── stores/                 # Pinia 状态管理
    │   ├── user.js             # 登录态、token 持久化（localStorage）
    │   └── chat.js             # 会话、消息、流式生成状态机
    ├── router/index.js         # 路由表 + 登录守卫 + 动态标题
    ├── layout/BasicLayout.vue  # 主布局：左侧菜单 + 顶部用户信息/租户/退出登录
    ├── components/
    │   ├── MessageBubble.vue   # 消息气泡（含引用卡片、打字光标、耗时统计）
    │   ├── CitationCard.vue    # 引用溯源卡片（可展开原文片段、相关度进度条）
    │   └── MarkdownContent.vue # 零依赖 Markdown 渲染（标题/列表/表格/代码块/行内样式）
    ├── utils/
    │   ├── sse.js              # SSE 流式解析（见第一节）
    │   ├── format.js           # 时间、文件体积、金额、数字格式化
    │   └── constants.js        # 文档状态等字典（中文标签 + 颜色）
    ├── views/                  # 7 个页面
    └── styles/index.css        # 全局样式
```

---

## 三、页面清单

| 路由 | 页面 | 说明 |
| --- | --- | --- |
| `/login` | 登录页 | 租户编码 / 用户名 / 密码，默认演示账号 `demo / admin / admin123`，支持一键填充 |
| `/chat` | 对话中心 | 左侧会话列表 + 新建会话；中间流式打字机消息流；右侧**引用溯源面板**；顶部切换知识库与模型提供方 |
| `/kb` | 知识库管理 | 卡片列表展示文档数/分块数/TopK/向量模型，支持新建与删除，可跳转文档中心 |
| `/doc?kbId=` | 文档中心 | 拖拽上传（el-upload 自定义 http-request）、状态中文标签配色、分块预览弹窗、重建索引、删除；**未完成状态每 3 秒轮询**，全部进入终态自动停止 |
| `/cs` | 智能客服 | 流式客服对话 + 右侧订单查询 / 工单列表 / 转人工 |
| `/audit` | 审计与成本 | 调用日志表格（后端分页）+ 成本汇总卡片 + 纯 CSS 柱状图 + 按模型汇总表 |
| `/model` | 模型配置 | 提供方列表，启用/停用、优先级、对话模型名、API Key（密码框，留空表示不修改） |

---

## 四、本地运行

> 本机没有全局 Node，使用项目自带的便携版 Node（v20.20.2 / npm 10.8.2）。

```powershell
# 1. 让便携版 Node 生效（PowerShell 5.1）
$env:PATH = "C:\Users\fanye\Desktop\项目\enterprise-rag-platform\tools\node;" + $env:PATH

# 2. 进入前端目录（路径含中文，务必用 -LiteralPath）
Set-Location -LiteralPath "C:\Users\fanye\Desktop\项目\enterprise-rag-platform\frontend"

# 3. 安装依赖（已通过 .npmrc 固定国内镜像）
npm install

# 4. 启动开发服务器 -> http://127.0.0.1:5173
npm run dev

# 5. 生产构建 -> 产出 dist/
npm run build
```

### 自测脚本（不依赖后端）

```powershell
npm test            # 等价于下面两条
npm run test:sse        # SSE 解析器单测(24) + 流式集成测试(21)
npm run test:template   # 12 个 Vue 组件「模板标识符 vs script 定义」静态交叉检查
```

`test/mock-backend.mjs` 是一个临时 Mock 后端，用于在后端未启动时验证 vite 代理是否连通：

```powershell
node test/mock-backend.mjs   # 另开一个终端；随后访问 http://127.0.0.1:5173/api/kb/list
```

---

## 五、后端契约对接说明

- 统一响应包装 `{code, message, data}`，`code === 0` 视为成功；`request.js`
  在响应拦截器中直接**解包 `data`**，因此业务代码拿到的就是业务数据本身。
- 除登录外所有请求自动注入 `Authorization: Bearer <token>`（与后端
  `app.jwt.prefix: "Bearer "` 一致），token 持久化在 `localStorage`。
- `401` 统一清除登录态并跳转 `/login`。
- 流式接口返回原始 `Response`，交由 `utils/sse.js` 解析，不走 JSON 解包。

---

## 六、已知限制

1. **路由使用 history 模式**，部署到生产环境时需由 Nginx / 网关配置
   `try_files $uri $uri/ /index.html`，否则直接刷新 `/kb` 这类深层路由会 404
   （开发环境由 Vite 处理，不受影响）。
2. **前端未做权限级菜单控制**：菜单按登录态显示，未根据 `permissions` 字段做按钮级鉴权，
   权限校验以后端为准。用户信息中的 `roles` / `permissions` 已保存在 store，预留了扩展点。
3. **成本统计柱状图为纯 CSS 实现**（未引入 echarts），只展示 Token 趋势；
   如需堆叠/缩放等复杂图表，需另行引入图表库。
4. **智能客服的 `sessionUuid` 由前端生成**（后端无客服建会话接口），
   多轮对话依赖该 UUID 串联；若后端改用自有会话表，需要相应调整。
5. **文档状态轮询为 3 秒固定间隔**，未做指数退避；文档量很大时建议后端改为推送或加长间隔。
6. **`MarkdownContent` 是精简子集**，支持标题、列表、引用、分割线、表格、围栏代码块与
   行内样式；不支持嵌套列表、任务列表、脚注等高级语法（已做 HTML 转义防 XSS）。
7. **Element Plus 采用全量引入**，产物 JS 约 1.17 MB（gzip 约 370 KB）。
   若需进一步优化体积，可改为 `unplugin-vue-components` 按需引入。
