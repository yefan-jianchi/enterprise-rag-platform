-- ============================================================================
--  企业级多模型 RAG 知识库与智能客服平台 —— 种子数据（演示/开发用）
--  执行： mysql -u root -p rag_platform < db/seed.sql
--  说明：
--    * 用户表不在这里插：后端启动时会用 BCrypt 现场生成密码哈希并引导管理员
--      （默认账号 admin / admin123），避免把写死的哈希塞进 SQL。
--    * 模型提供方里 api_key_cipher 留空，真实 Key 走 .env / 环境变量注入。
-- ============================================================================

USE rag_platform;

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 1. 租户（演示多租户隔离）
-- ---------------------------------------------------------------------------
DELETE FROM sys_tenant WHERE tenant_code IN ('demo', 'acme');
INSERT INTO sys_tenant (tenant_code, tenant_name, status, quota_tokens) VALUES
  ('demo', '演示租户（烟台理工）', 1, 2000000),
  ('acme', 'ACME 科技（客户演示）', 1, 5000000);

-- ---------------------------------------------------------------------------
-- 2. 角色（tenant_id=0 表示系统内置，所有租户共用）
-- ---------------------------------------------------------------------------
DELETE FROM sys_role WHERE tenant_id = 0;
INSERT INTO sys_role (tenant_id, role_code, role_name, description) VALUES
  (0, 'ADMIN',             '系统管理员',   '租户内全部权限，含用户与模型配置'),
  (0, 'KNOWLEDGE_MANAGER', '知识库管理员', '知识库与文档的上传、解析、删除'),
  (0, 'AGENT',             '客服坐席',     '对话、检索、工单处理'),
  (0, 'VIEWER',            '只读用户',     '仅可提问与查看引用');

-- ---------------------------------------------------------------------------
-- 3. 权限点
-- ---------------------------------------------------------------------------
DELETE FROM sys_permission;
INSERT INTO sys_permission (perm_code, perm_name, module) VALUES
  ('kb:view',        '查看知识库',       'kb'),
  ('kb:create',      '新建知识库',       'kb'),
  ('kb:update',      '编辑知识库',       'kb'),
  ('kb:delete',      '删除知识库',       'kb'),
  ('kb:doc:view',    '查看文档',         'kb'),
  ('kb:doc:upload',  '上传文档',         'kb'),
  ('kb:doc:delete',  '删除文档',         'kb'),
  ('kb:doc:reindex', '重建索引',         'kb'),
  ('chat:ask',       '发起对话',         'chat'),
  ('chat:history',   '查看历史会话',     'chat'),
  ('cs:ticket:view', '查看工单',         'cs'),
  ('cs:ticket:edit', '处理工单',         'cs'),
  ('cs:handoff',     '转人工',           'cs'),
  ('model:view',     '查看模型配置',     'model'),
  ('model:edit',     '编辑模型配置',     'model'),
  ('audit:view',     '查看审计日志',     'audit'),
  ('cost:view',      '查看成本统计',     'audit'),
  ('user:manage',    '用户与角色管理',   'system'),
  ('prompt:manage',  'Prompt 模板管理',  'system');

-- 角色-权限：ADMIN 拿全部，其余按职责给
DELETE FROM sys_role_permission;
INSERT INTO sys_role_permission (role_id, perm_id)
SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p WHERE r.role_code = 'ADMIN';

INSERT INTO sys_role_permission (role_id, perm_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.perm_code IN ('kb:view','kb:create','kb:update','kb:doc:view','kb:doc:upload','kb:doc:delete','kb:doc:reindex','chat:ask','chat:history')
WHERE r.role_code = 'KNOWLEDGE_MANAGER';

INSERT INTO sys_role_permission (role_id, perm_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.perm_code IN ('kb:view','kb:doc:view','chat:ask','chat:history','cs:ticket:view','cs:ticket:edit','cs:handoff')
WHERE r.role_code = 'AGENT';

INSERT INTO sys_role_permission (role_id, perm_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.perm_code IN ('kb:view','kb:doc:view','chat:ask')
WHERE r.role_code = 'VIEWER';

-- ---------------------------------------------------------------------------
-- 4. 模型提供方（多模型适配层 + Fallback 顺序）
--    priority 越小越优先：DeepSeek → 通义 → OpenAI → 本地 Mock
-- ---------------------------------------------------------------------------
DELETE FROM ai_model_provider WHERE tenant_id = 0;
INSERT INTO ai_model_provider
  (tenant_id, provider_code, provider_name, base_url, api_key_cipher, chat_model, embed_model, embed_dim, priority, enabled, extra_json) VALUES
  (0, 'deepseek',  'DeepSeek（深度求索）', 'https://api.deepseek.com/v1',                          '', 'deepseek-chat',     'mock-embed-384', 384,  10, 1, JSON_OBJECT('temperature', 0.3, 'maxTokens', 2048)),
  (0, 'dashscope', '通义千问（阿里百炼）', 'https://dashscope.aliyuncs.com/compatible-mode/v1',     '', 'qwen-plus',         'text-embedding-v3', 1024, 20, 1, JSON_OBJECT('temperature', 0.3, 'maxTokens', 2048)),
  (0, 'openai',    'OpenAI',              'https://api.openai.com/v1',                              '', 'gpt-4o-mini',       'text-embedding-3-small', 1536, 30, 0, JSON_OBJECT('temperature', 0.3, 'maxTokens', 2048)),
  (0, 'mock',      '本地模拟（离线可用）', 'http://localhost:0/v1',                                  '', 'mock-chat',         'mock-embed-384', 384, 99, 1, JSON_OBJECT('note', '无 Key 时兜底，确定性向量 + 模板化回答，便于离线演示与 CI'));

-- ---------------------------------------------------------------------------
-- 5. Prompt 模板
-- ---------------------------------------------------------------------------
DELETE FROM ai_prompt_template WHERE tenant_id = 0;
INSERT INTO ai_prompt_template (tenant_id, template_code, template_name, content, variables) VALUES
  (0, 'RAG_QA', '知识库问答',
   '你是企业知识库助手。请严格依据【参考资料】回答用户问题，不要编造。\n如果资料不足以回答，请明确说明「知识库中没有相关内容」，并建议转人工。\n回答末尾用 [1][2] 标注引用来源编号。\n\n【参考资料】\n{{context}}\n\n【用户问题】\n{{question}}',
   JSON_ARRAY('question','context')),
  (0, 'CS_REPLY', '智能客服回复',
   '你是电商客服助手，语气礼貌、简洁。可以调用工具查询订单、创建工单、转人工。\n查到订单后请复述关键字段（订单号、商品、状态、物流单号）再给结论。\n\n【知识库参考】\n{{context}}\n\n【用户问题】\n{{question}}',
   JSON_ARRAY('question','context')),
  (0, 'REWRITE', '查询改写',
   '把用户口语化的问题改写成 3 条适合向量检索的查询，覆盖同义词与专业术语。\n只输出 3 行，不要编号，不要解释。\n\n用户问题：{{question}}',
   JSON_ARRAY('question')),
  (0, 'HYDE', 'HyDE 假设文档',
   '请针对下面的问题，写一段 100 字左右的「假设性答案」，就像它已经是知识库里的原文片段。\n不要分点，不要标题。\n\n问题：{{question}}',
   JSON_ARRAY('question')),
  (0, 'RERANK', '重排打分',
   '请给下面每个「候选片段」与「问题」的相关性打 0-10 分。\n只输出 JSON 数组，形如 [{"id":1,"score":8}]，不要任何多余文字。\n\n问题：{{question}}\n\n候选片段：\n{{candidates}}',
   JSON_ARRAY('question','candidates'));

-- ---------------------------------------------------------------------------
-- 6. 演示知识库
-- ---------------------------------------------------------------------------
DELETE FROM kb_knowledge_base WHERE tenant_id = 1;
INSERT INTO kb_knowledge_base (tenant_id, kb_name, description, embed_model, chunk_size, chunk_overlap, top_k, score_threshold) VALUES
  (1, '校园服务知识库', '烟台理工学院校园卡、图书馆、宿舍、选课等常见问题', 'mock-embed-384', 500, 80, 5, 0.2000),
  (1, '产品与售后手册', 'ACME 智能硬件产品说明书与售后政策，供智能客服使用',   'mock-embed-384', 500, 80, 5, 0.2000);

-- ---------------------------------------------------------------------------
-- 7. 智能客服演示数据：订单 + 工单
-- ---------------------------------------------------------------------------
DELETE FROM cs_order WHERE tenant_id = 1;
INSERT INTO cs_order (tenant_id, order_no, customer_name, phone, product_name, amount, order_status, logistics_no) VALUES
  (1, 'SO20261016001', '范晔', '13800000001', 'ACME 智能音箱 Pro',   399.00, 'SHIPPED',   'SF1234567890'),
  (1, 'SO20261016002', '范晔', '13800000001', 'ACME 无线降噪耳机',   699.00, 'REFUNDING', NULL),
  (1, 'SO20261015003', '张三', '13900000002', 'ACME 智能门锁',      1299.00, 'DELIVERED', 'JD9876543210'),
  (1, 'SO20261014004', '李四', '13700000003', 'ACME 空气净化器',     899.00, 'PAID',      NULL);

DELETE FROM cs_ticket WHERE tenant_id = 1;
INSERT INTO cs_ticket (tenant_id, ticket_no, order_no, title, content, priority, status, assignee) VALUES
  (1, 'TK20261016001', 'SO20261016002', '耳机申请退款进度查询', '用户 10-14 申请退款，至今未到账', 'HIGH',   'PROCESSING', '客服小王'),
  (1, 'TK20261016002', NULL,            'App 无法绑定设备',     '智能音箱配网失败，提示超时',      'NORMAL', 'OPEN',       NULL),
  (1, 'TK20261015003', 'SO20261015003', '门锁指纹识别不灵敏',   '使用一周后指纹识别率下降',        'NORMAL', 'RESOLVED',   '客服小李');

-- ---------------------------------------------------------------------------
-- 8. 检索评测集（用于对比 向量 / BM25 / 混合 / 重排 的命中率）
-- ---------------------------------------------------------------------------
DELETE FROM eval_retrieval_case WHERE tenant_id = 1;
INSERT INTO eval_retrieval_case (tenant_id, kb_id, question, expect_keyword, strategy) VALUES
  (1, 1, '校园卡丢了怎么补办？',       '校园卡',   'HYBRID_RRF'),
  (1, 1, '图书馆开放时间是几点？',     '图书馆',   'HYBRID_RRF'),
  (1, 1, '宿舍晚上几点断网？',         '断网',     'HYBRID_RRF'),
  (1, 2, '智能音箱怎么连 WiFi？',      '配网',     'HYBRID_RRF'),
  (1, 2, '耳机退款多久到账？',         '退款',     'HYBRID_RRF');

-- ---------------------------------------------------------------------------
-- 校验
-- ---------------------------------------------------------------------------
SELECT '租户' AS 表, COUNT(*) AS 条数 FROM sys_tenant
UNION ALL SELECT '角色', COUNT(*) FROM sys_role
UNION ALL SELECT '权限', COUNT(*) FROM sys_permission
UNION ALL SELECT '角色权限', COUNT(*) FROM sys_role_permission
UNION ALL SELECT '模型提供方', COUNT(*) FROM ai_model_provider
UNION ALL SELECT 'Prompt模板', COUNT(*) FROM ai_prompt_template
UNION ALL SELECT '知识库', COUNT(*) FROM kb_knowledge_base
UNION ALL SELECT '订单', COUNT(*) FROM cs_order
UNION ALL SELECT '工单', COUNT(*) FROM cs_ticket
UNION ALL SELECT '评测集', COUNT(*) FROM eval_retrieval_case;
