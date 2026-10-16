-- ============================================================================
--  企业级多模型 RAG 知识库与智能客服平台 —— 数据库结构
--  数据库：MySQL 8.0+   字符集：utf8mb4   引擎：InnoDB
--  设计要点：
--    1. 多租户：所有业务表都带 tenant_id，配合行级过滤实现租户隔离
--    2. RBAC：用户-角色-权限三级，权限码形如 kb:doc:upload
--    3. 文档流水线：kb_document.status 表达 PENDING→PARSING→CHUNKING→EMBEDDING→INDEXED 状态机
--    4. 引用溯源：kb_chunk 保存 page_no / char_start / char_end，回答可精确回到原文页码
--    5. 成本审计：ai_call_log 记录每次模型调用的 token 与费用，可做租户级成本统计
-- ============================================================================

CREATE DATABASE IF NOT EXISTS rag_platform
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE rag_platform;

-- ---------------------------------------------------------------------------
-- 1. 租户
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS sys_tenant;
CREATE TABLE sys_tenant (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '租户ID',
  tenant_code   VARCHAR(64)  NOT NULL COMMENT '租户编码（登录时使用）',
  tenant_name   VARCHAR(128) NOT NULL COMMENT '租户名称',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  expire_at     DATETIME     NULL COMMENT '到期时间，NULL 表示永久',
  quota_tokens  BIGINT       NOT NULL DEFAULT 0 COMMENT '月度 token 配额，0 表示不限',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_code (tenant_code)
) ENGINE=InnoDB COMMENT='租户表';

-- ---------------------------------------------------------------------------
-- 2. RBAC：用户 / 角色 / 权限
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id     BIGINT       NOT NULL COMMENT '所属租户',
  username      VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt 哈希，绝不存明文',
  nickname      VARCHAR(64)  NULL,
  email         VARCHAR(128) NULL,
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  last_login_at DATETIME     NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_username (tenant_id, username),
  KEY idx_tenant (tenant_id)
) ENGINE=InnoDB COMMENT='用户表';

DROP TABLE IF EXISTS sys_role;
CREATE TABLE sys_role (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '0 表示系统内置角色，对所有租户可见',
  role_code   VARCHAR(64) NOT NULL COMMENT 'ADMIN / KNOWLEDGE_MANAGER / AGENT / VIEWER',
  role_name   VARCHAR(64) NOT NULL,
  description VARCHAR(255) NULL,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_role (tenant_id, role_code)
) ENGINE=InnoDB COMMENT='角色表';

DROP TABLE IF EXISTS sys_permission;
CREATE TABLE sys_permission (
  id        BIGINT       NOT NULL AUTO_INCREMENT,
  perm_code VARCHAR(96)  NOT NULL COMMENT '权限码，如 kb:doc:upload',
  perm_name VARCHAR(96)  NOT NULL,
  module    VARCHAR(48)  NOT NULL COMMENT '所属模块',
  PRIMARY KEY (id),
  UNIQUE KEY uk_perm_code (perm_code)
) ENGINE=InnoDB COMMENT='权限表';

DROP TABLE IF EXISTS sys_user_role;
CREATE TABLE sys_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id),
  KEY idx_role (role_id)
) ENGINE=InnoDB COMMENT='用户-角色';

DROP TABLE IF EXISTS sys_role_permission;
CREATE TABLE sys_role_permission (
  role_id BIGINT NOT NULL,
  perm_id BIGINT NOT NULL,
  PRIMARY KEY (role_id, perm_id)
) ENGINE=InnoDB COMMENT='角色-权限';

-- ---------------------------------------------------------------------------
-- 3. 模型提供方（多模型统一适配层的数据来源）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS ai_model_provider;
CREATE TABLE ai_model_provider (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '0 表示平台级公共模型',
  provider_code  VARCHAR(48)  NOT NULL COMMENT 'openai / deepseek / dashscope / mock',
  provider_name  VARCHAR(64)  NOT NULL,
  base_url       VARCHAR(255) NOT NULL,
  api_key_cipher VARCHAR(512) NULL COMMENT '加密后的 API Key，禁止明文落库',
  chat_model     VARCHAR(64)  NOT NULL COMMENT '对话模型名，如 deepseek-chat',
  embed_model    VARCHAR(64)  NULL COMMENT '向量模型名',
  embed_dim      INT          NULL COMMENT '向量维度',
  priority       INT          NOT NULL DEFAULT 100 COMMENT '数值越小越优先，用于 Fallback 排序',
  enabled        TINYINT      NOT NULL DEFAULT 1,
  extra_json     JSON         NULL COMMENT '温度、max_tokens 等扩展参数',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_provider (tenant_id, provider_code),
  KEY idx_enabled_priority (enabled, priority)
) ENGINE=InnoDB COMMENT='模型提供方配置（多模型适配层）';

-- ---------------------------------------------------------------------------
-- 4. 知识库与文档
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS kb_knowledge_base;
CREATE TABLE kb_knowledge_base (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id     BIGINT       NOT NULL,
  kb_name       VARCHAR(128) NOT NULL,
  description   VARCHAR(512) NULL,
  embed_model   VARCHAR(64)  NOT NULL DEFAULT 'mock-embed-384' COMMENT 'Embedding 模型',
  chunk_size    INT          NOT NULL DEFAULT 500 COMMENT '分块目标长度（token 近似用字符数）',
  chunk_overlap INT          NOT NULL DEFAULT 80  COMMENT '分块重叠',
  top_k         INT          NOT NULL DEFAULT 5   COMMENT '召回条数',
  score_threshold DECIMAL(5,4) NOT NULL DEFAULT 0.2000 COMMENT '相似度阈值',
  created_by    BIGINT       NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_tenant (tenant_id)
) ENGINE=InnoDB COMMENT='知识库';

DROP TABLE IF EXISTS kb_document;
CREATE TABLE kb_document (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id     BIGINT       NOT NULL,
  kb_id         BIGINT       NOT NULL,
  doc_name      VARCHAR(255) NOT NULL COMMENT '原始文件名',
  doc_type      VARCHAR(16)  NOT NULL COMMENT 'pdf / docx / md / txt',
  file_key      VARCHAR(512) NOT NULL COMMENT 'MinIO 对象键',
  file_size     BIGINT       NOT NULL DEFAULT 0,
  file_hash     CHAR(64)     NULL COMMENT 'SHA-256，用于秒传与去重',
  status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                COMMENT 'PENDING/PARSING/CHUNKING/EMBEDDING/INDEXED/FAILED',
  chunk_count   INT          NOT NULL DEFAULT 0,
  error_msg     VARCHAR(1000) NULL,
  parsed_at     DATETIME     NULL,
  indexed_at    DATETIME     NULL,
  created_by    BIGINT       NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_tenant_kb (tenant_id, kb_id),
  KEY idx_status (status),
  KEY idx_hash (file_hash)
) ENGINE=InnoDB COMMENT='知识库文档（含处理状态机）';

DROP TABLE IF EXISTS kb_chunk;
CREATE TABLE kb_chunk (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT       NOT NULL,
  kb_id       BIGINT       NOT NULL,
  doc_id      BIGINT       NOT NULL,
  chunk_index INT          NOT NULL COMMENT '文档内序号，从 0 开始',
  content     MEDIUMTEXT   NOT NULL COMMENT '分块正文',
  token_count INT          NOT NULL DEFAULT 0,
  page_no     INT          NULL COMMENT '所在页码（PDF/Word），用于引用溯源',
  char_start  INT          NULL COMMENT '在原文中的起始偏移',
  char_end    INT          NULL COMMENT '在原文中的结束偏移',
  vector_id   BIGINT       NULL COMMENT 'FAISS 中的向量主键（IndexIDMap 的 id）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_doc (doc_id, chunk_index),
  KEY idx_tenant_kb (tenant_id, kb_id),
  FULLTEXT KEY ft_content (content) WITH PARSER ngram COMMENT 'MySQL 原生全文索引（ngram 中文分词）'
) ENGINE=InnoDB COMMENT='文档分块（RAG 检索的最小单元）';

-- ---------------------------------------------------------------------------
-- 5. 对话中心
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS chat_session;
CREATE TABLE chat_session (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id    BIGINT       NOT NULL,
  user_id      BIGINT       NOT NULL,
  session_uuid CHAR(36)     NOT NULL COMMENT '对外暴露的会话标识',
  title        VARCHAR(128) NULL,
  kb_id        BIGINT       NULL COMMENT '绑定的知识库，NULL 表示纯模型对话',
  provider_code VARCHAR(48) NOT NULL DEFAULT 'deepseek' COMMENT '本会话使用的模型提供方',
  scene        VARCHAR(24)  NOT NULL DEFAULT 'CHAT' COMMENT 'CHAT 通用问答 / CS 智能客服',
  message_count INT         NOT NULL DEFAULT 0,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_session_uuid (session_uuid),
  KEY idx_tenant_user (tenant_id, user_id)
) ENGINE=InnoDB COMMENT='会话';

DROP TABLE IF EXISTS chat_message;
CREATE TABLE chat_message (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT      NOT NULL,
  session_id  BIGINT      NOT NULL,
  role        VARCHAR(16) NOT NULL COMMENT 'system/user/assistant/tool',
  content     MEDIUMTEXT  NOT NULL,
  citations   JSON        NULL COMMENT '引用溯源：[{docId,docName,pageNo,chunkIndex,score,snippet}]',
  model       VARCHAR(64) NULL,
  prompt_tokens     INT   NOT NULL DEFAULT 0,
  completion_tokens INT   NOT NULL DEFAULT 0,
  latency_ms  INT         NOT NULL DEFAULT 0,
  trace_id    CHAR(32)    NULL COMMENT '链路追踪 ID，串联网关/AI服务/模型调用',
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_session (session_id, id)
) ENGINE=InnoDB COMMENT='消息';

DROP TABLE IF EXISTS ai_prompt_template;
CREATE TABLE ai_prompt_template (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT       NOT NULL DEFAULT 0,
  template_code VARCHAR(64) NOT NULL COMMENT 'RAG_QA / CS_REPLY / REWRITE / HYDE / RERANK',
  template_name VARCHAR(96) NOT NULL,
  content     TEXT         NOT NULL COMMENT '支持 {{question}} {{context}} 占位符',
  variables   JSON         NULL,
  enabled     TINYINT      NOT NULL DEFAULT 1,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_tpl (tenant_id, template_code)
) ENGINE=InnoDB COMMENT='Prompt 模板管理';

-- ---------------------------------------------------------------------------
-- 6. 智能客服：订单 / 工单 / 转人工（供 Tool Calling 调用）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS cs_order;
CREATE TABLE cs_order (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id    BIGINT       NOT NULL,
  order_no     VARCHAR(32)  NOT NULL,
  customer_name VARCHAR(64) NOT NULL,
  phone        VARCHAR(20)  NULL,
  product_name VARCHAR(128) NOT NULL,
  amount       DECIMAL(10,2) NOT NULL,
  order_status VARCHAR(24)  NOT NULL COMMENT 'PAID/SHIPPED/DELIVERED/REFUNDING/CLOSED',
  logistics_no VARCHAR(64)  NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_tenant_phone (tenant_id, phone)
) ENGINE=InnoDB COMMENT='订单（智能客服查询用）';

DROP TABLE IF EXISTS cs_ticket;
CREATE TABLE cs_ticket (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT       NOT NULL,
  ticket_no   VARCHAR(32)  NOT NULL,
  user_id     BIGINT       NULL,
  order_no    VARCHAR(32)  NULL,
  title       VARCHAR(200) NOT NULL,
  content     TEXT         NULL,
  priority    VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT 'LOW/NORMAL/HIGH/URGENT',
  status      VARCHAR(24)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/PROCESSING/RESOLVED/CLOSED',
  assignee    VARCHAR(64)  NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ticket_no (ticket_no),
  KEY idx_tenant_status (tenant_id, status)
) ENGINE=InnoDB COMMENT='工单';

DROP TABLE IF EXISTS cs_handoff;
CREATE TABLE cs_handoff (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT       NOT NULL,
  session_id  BIGINT       NOT NULL,
  reason      VARCHAR(255) NULL COMMENT '转人工原因（如模型置信度低、用户明确要求）',
  status      VARCHAR(24)  NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING/ACCEPTED/CLOSED',
  agent_name  VARCHAR(64)  NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  accepted_at DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_tenant_status (tenant_id, status)
) ENGINE=InnoDB COMMENT='转人工记录';

-- ---------------------------------------------------------------------------
-- 7. 审计与成本
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS ai_call_log;
CREATE TABLE ai_call_log (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  tenant_id      BIGINT      NOT NULL,
  user_id        BIGINT      NULL,
  session_id     BIGINT      NULL,
  trace_id       CHAR(32)    NULL,
  call_type      VARCHAR(24) NOT NULL COMMENT 'CHAT/EMBED/RERANK/REWRITE/TOOL',
  provider_code  VARCHAR(48) NOT NULL,
  model          VARCHAR(64) NOT NULL,
  prompt_tokens  INT         NOT NULL DEFAULT 0,
  completion_tokens INT      NOT NULL DEFAULT 0,
  total_tokens   INT         NOT NULL DEFAULT 0,
  cost_yuan      DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '按单价折算的人民币成本',
  latency_ms     INT         NOT NULL DEFAULT 0,
  success        TINYINT     NOT NULL DEFAULT 1,
  error_msg      VARCHAR(500) NULL,
  created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_tenant_time (tenant_id, created_at),
  KEY idx_trace (trace_id)
) ENGINE=InnoDB COMMENT='模型调用日志（审计 + 成本统计）';

DROP TABLE IF EXISTS sys_audit_log;
CREATE TABLE sys_audit_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT       NOT NULL DEFAULT 0,
  user_id     BIGINT       NULL,
  username    VARCHAR(64)  NULL,
  action      VARCHAR(64)  NOT NULL COMMENT 'LOGIN / DOC_UPLOAD / DOC_DELETE / KB_CREATE ...',
  resource    VARCHAR(128) NULL,
  detail      VARCHAR(1000) NULL,
  ip          VARCHAR(64)  NULL,
  success     TINYINT      NOT NULL DEFAULT 1,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_tenant_time (tenant_id, created_at)
) ENGINE=InnoDB COMMENT='操作审计日志';

-- ---------------------------------------------------------------------------
-- 8. 检索质量评估（讲得出「命中率提升」的证据）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS eval_retrieval_case;
CREATE TABLE eval_retrieval_case (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id      BIGINT       NOT NULL,
  kb_id          BIGINT       NOT NULL,
  question       VARCHAR(500) NOT NULL,
  expect_doc_id  BIGINT       NULL,
  expect_keyword VARCHAR(200) NULL,
  hit_at_1       TINYINT      NULL COMMENT 'Top1 是否命中',
  hit_at_5       TINYINT      NULL COMMENT 'Top5 是否命中',
  mrr            DECIMAL(6,4) NULL COMMENT '倒数排名',
  strategy       VARCHAR(32)  NULL COMMENT 'VECTOR / BM25 / HYBRID_RRF / HYBRID_RERANK',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_tenant_kb (tenant_id, kb_id)
) ENGINE=InnoDB COMMENT='检索评测集（向量/BM25/混合/重排 对比）';
