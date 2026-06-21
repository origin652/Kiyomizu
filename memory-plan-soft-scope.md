# Kiyomizu 无 Embedding 图记忆、软作用域、人物关系图与身份解析完整计划

## 1. 背景与目标

本计划是在“无 embedding 图记忆”计划基础上的完整扩展版。它额外解决一个更深的边界问题：Kiyomizu 是透传代理，不能依赖请求体元信息、终端、客户端标题或 header 来判断“当前是谁”或“当前属于哪个 session”。

人类记忆没有严格 session 隔离。一个人可以同时处理多个任务、和多个人保持关系、跨场景联想，但不会随意把 A 的私密信息说给 B，也不会把某个项目的临时细节误认为长期事实。

因此新设计采用：

- 全局记忆池。
- 软作用域。
- 轻量人物关系图。
- 当前任务框架。
- 分层跨任务召回。
- 严格动态尾部注入，保护 prompt cache。

## 2. 当前代码事实

当前 Kiyomizu 没有会话概念：

- 代理入口只知道 HTTP request path、query、headers 和 body。
- 请求日志只记录 method、pathname、model、message count、cache blocks 等。
- 没有 user id。
- 没有 session id。
- 没有 conversation id。
- CORS 允许 `http-referer` 和 `x-title`，但这些不应作为身份来源。
- 多个终端发来的请求，如果内容上都是“我”，仍应被理解为同一个主用户。

这意味着新记忆系统不能设计成“按请求来源隔离”。它需要从对话视角和文本内容中理解人物与关系。

## 3. 总体设计

记忆系统由四个层次组成：

1. 全局图记忆。
   - 所有记忆进入同一个图。
   - 不按 session 硬切开。

2. 当前任务框架。
   - 每轮请求临时推断当前任务、主题、人物、实体和意图。
   - 默认不保存。
   - 只有有长期价值时才沉淀为项目/任务记忆。

3. 轻量人物关系图。
   - 用人物节点和关系边控制“这是谁的事”“这件事能不能拿来对当前对象说”。
   - 不把完整关系图注入提示词，只注入少量直接相关线索。

4. 分层跨任务召回。
   - 长期身份、偏好、关系记忆低门槛跨任务出现。
   - 项目事实、情景事件、临时工作记忆需要更强线索。
   - 敏感记忆必须满足强线索和披露规则。

## 4. 数据库设计

本计划使用与基础计划相同的图记忆结构，但增加人物、作用域和关系相关字段。

### 4.1 `memory_nodes`

字段：

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `uri TEXT NOT NULL UNIQUE`
- `kind TEXT NOT NULL`
- `content TEXT NOT NULL`
- `normalized_text TEXT NOT NULL`
- `searchable_text TEXT NOT NULL`
- `keywords TEXT NOT NULL DEFAULT '[]'`
- `aliases TEXT NOT NULL DEFAULT '[]'`
- `entities TEXT NOT NULL DEFAULT '[]'`
- `topics TEXT NOT NULL DEFAULT '[]'`
- `trigger_phrases TEXT NOT NULL DEFAULT '[]'`
- `disclosure TEXT NOT NULL DEFAULT 'private'`
- `priority REAL NOT NULL DEFAULT 0.5`
- `confidence REAL NOT NULL DEFAULT 0.5`
- `strength REAL NOT NULL DEFAULT 1.0`
- `emotion_valence REAL NOT NULL DEFAULT 0.5`
- `emotion_arousal REAL NOT NULL DEFAULT 0.3`
- `scope_hint TEXT`
- `person_uri TEXT`
- `project_uri TEXT`
- `created_at INTEGER NOT NULL`
- `updated_at INTEGER NOT NULL`
- `last_accessed_at INTEGER NOT NULL`
- `access_count INTEGER NOT NULL DEFAULT 0`
- `source TEXT NOT NULL DEFAULT 'conversation'`
- `raw_evidence TEXT`

### 4.2 `memory_edges`

字段：

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `from_node_id INTEGER NOT NULL`
- `to_node_id INTEGER NOT NULL`
- `relation TEXT NOT NULL`
- `weight REAL NOT NULL DEFAULT 1.0`
- `created_at INTEGER NOT NULL`
- `updated_at INTEGER NOT NULL`

关系类型：

- `related_to`
- `derived_from`
- `reinforces`
- `contradicts`
- `supersedes`
- `mentions`
- `about_person`
- `about_project`
- `belongs_to_scope`
- `relationship_to`
- `triggered_by`

### 4.3 `memory_search_terms`

字段：

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `node_id INTEGER NOT NULL`
- `term TEXT NOT NULL`
- `kind TEXT NOT NULL`
- `weight REAL NOT NULL DEFAULT 1.0`

`kind` 支持：

- `keyword`
- `alias`
- `entity`
- `topic`
- `trigger`
- `person_alias`
- `relationship_label`
- `path_segment`
- `scope_term`

### 4.4 FTS

建立 FTS 表：

- `memory_nodes_fts(node_id UNINDEXED, content, searchable_text, keywords, aliases, entities, topics, trigger_phrases)`

FTS 不可用时降级到本地 LIKE 和 token overlap。

## 5. URI 体系

使用伴侣域名空间：

- `user://...`
- `relationship://...`
- `person://...`
- `episode://...`
- `preference://...`
- `project://...`
- `emotion://...`
- `working://...`

新增人物相关 URI：

- `person://user/primary`
- `person://self/kiyomizu`
- `person://mentioned/<normalized-name>`
- `person://user/primary/mother`
- `person://user/primary/friend/<normalized-name>`

任务相关 URI：

- `project://<normalized-project-name>`
- `working://current/<topic-or-task>`
- `episode://<date>/<short-topic>`

## 6. 身份解析规则

这是硬边界。

不能从以下信息判断人是谁：

- 终端。
- 请求来源。
- HTTP path。
- 客户端标题。
- header。
- IP。
- 上游模型。

第一版采用单实例主用户规则：

- `person://user/primary` 是默认主用户。
- 用户消息里的“我、我的、I、me、my”都归到 `person://user/primary`。
- 多个终端发来的第一人称内容仍属于同一个 primary user。
- `person://self/kiyomizu` 是助手自己。
- 助手回复里的“我”归到 `person://self/kiyomizu`。

其他人物节点只从文本内容建立：

- “我朋友小王”建立或合并到 `person://user/primary/friend/xiao-wang`。
- “我妈妈”建立或合并到 `person://user/primary/mother`。
- “Alice”建立或合并到 `person://mentioned/alice`，如果上下文说明 Alice 是朋友，再加 `relationship_to` 边。

模糊代词处理：

- “他、她、they”如果缺少可靠上下文，不新建人物节点。
- 只作为当前记忆的弱实体线索。
- 不应把弱代词强绑定到最近任意人物。

多人共用同一个 Kiyomizu 实例：

- 在无显式身份输入下不可安全区分。
- 默认仍按 primary user 处理。
- 如果需要硬隔离，应分实例。
- 显式身份切换可以作为未来功能，但不在第一版做。

## 7. 五层记忆类型

跨任务召回按五层类型控制。

### 7.1 身份/长期偏好

例子：

- 用户喜欢某种称呼。
- 用户长期偏好某种语言风格。
- 用户长期使用某种工具链。

召回策略：

- 可低门槛跨任务。
- 但仍受披露规则控制。

### 7.2 关系记忆

例子：

- 用户和 Kiyomizu 的关系状态。
- 用户曾表达信任、失望、亲近、边界。
- Kiyomizu 应如何称呼或陪伴用户。

召回策略：

- 可低门槛跨任务。
- 注入量必须小。
- 避免每轮重复输出。

### 7.3 项目/任务事实

例子：

- Kiyomizu 当前记忆系统设计。
- 某个代码库的架构决策。
- 某个论文或项目的约束。

召回策略：

- 需要当前任务框架或实体强命中。
- 不应随便进入闲聊或无关任务。

### 7.4 情景事件

例子：

- 某晚用户说过工作压力很大。
- 某次讨论里用户对一个设计做出选择。

召回策略：

- 普通召回需要强线索。
- 深度回忆可以更开放地找。

### 7.5 临时工作记忆

例子：

- 当前几轮正在比较两个设计。
- 当前任务里用户刚刚定了一个临时偏好。

召回策略：

- 按强度衰减。
- 不靠 session 结束删除。
- 可晋升。

## 8. 当前任务框架

每轮请求都临时形成当前任务框架。

来源：

- 最新用户消息。
- 当前请求中可见的系统提示。
- 最近对话文本。
- 明确项目名、文件名、工具名、人物名。

任务框架包含：

- `topics`
- `entities`
- `project_terms`
- `people`
- `intent`
- `language`
- `time_references`

默认不保存任务框架。

只有满足以下条件时才保存为图节点：

- 多轮重复出现。
- 用户确认。
- 总结模型判断有长期价值。
- 深度回忆中被反复命中。

保存时可写成：

- `project_fact`
- `working_memory`
- `episodic_event`

## 9. 轻量人物关系图

第一版做轻量人物图，不做完整社交网络。

存储内容：

- 人物别名。
- 人物和主用户的关系。
- 人物相关记忆边。
- 披露规则。
- 少量置信度信息。

不做：

- 每个人独立完整画像。
- 多用户账号系统。
- 每轮注入完整关系图。

召回使用：

- 当前文本提到某人时，优先召回该人物相关记忆。
- 当前文本是第一人称时，默认绑定 primary user。
- 关系记忆可以帮助判断哪些记忆适合本轮使用。

注入限制：

- 每轮最多注入 1 到 3 条人物关系线索。
- 数量由 `memory_person_context_max_clues` 控制。
- 只注入直接相关关系，不注入整张关系图。

## 10. 普通跨任务召回

普通召回开放跨任务，但不无条件开放。

候选分三类：

1. 当前任务强相关。
   - 主题、实体、项目名或人物强命中。

2. 全局长期背景。
   - 身份、长期偏好、关系记忆。

3. 弱相关联想。
   - 通过图边、情绪或近似主题找到，但线索弱。

普通召回注入规则：

- 当前任务强相关优先。
- 全局长期背景少量进入。
- 弱相关联想默认不进入，除非预算还有空间且披露规则允许。
- 项目事实、情景事件和临时工作记忆需要强线索。
- 敏感记忆必须强线索加披露规则允许。

## 11. 深度回忆跨任务策略

深度回忆允许跨任务，但最终结果必须分级。

流程：

1. 从明确回忆请求中提取线索。
2. 搜索直接相关节点。
3. 沿人物、项目、情绪、时间和关系边展开。
4. 收集跨任务候选。
5. 分成：
   - `direct`
   - `weak`
   - `conflict`
6. 只默认注入 `direct`。
7. `weak` 作为不确定背景。
8. `conflict` 只用于提醒模型不要武断。

这样可以模拟人类“从一个线索联想到其他场景”，但不会把所有联想到的东西都说出口。

## 12. 晋升机制

临时工作记忆可以晋升。

晋升触发：

- 重复命中。
- 用户语义确认。
- 情绪显著。
- 深度回忆命中。
- 总结模型判断为长期事实。

用户确认识别：

- 通过对话语义自动识别。
- “是的、没错、我确实、对，就是这样”等表达提升确认状态。
- 不要求用户使用命令。

晋升写回：

- 保留原临时节点。
- 新建长期节点。
- 建立 `derived_from` 或 `reinforces` 边。

## 13. 冲突处理

冲突记忆并存。

不做：

- 新记忆覆盖旧记忆。
- 自动删除旧状态。
- 强行合并矛盾事实。

做：

- 建立 `contradicts` 或 `supersedes` 边。
- 召回时根据近期性、确认程度、任务线索、人物线索、披露规则裁剪。
- 不确定时给模型低置信提示。

示例：

- 用户过去喜欢咖啡，现在说改喝茶。
- 新旧偏好都保留。
- 当前回复优先使用近期且被确认的茶偏好。
- 如果用户问“我以前喜欢什么”，深度回忆可以找出旧咖啡偏好。

## 14. 披露规则

披露规则控制“能不能说出口”和“怎样说出口”。

类型：

- `private`: 只做内部上下文。
- `hint`: 可自然表达，不应机械引用。
- `quote_allowed`: 可明确说“我记得你说过……”。
- `sensitive`: 只有强线索命中才可作为私有上下文，默认不直接表达。

人物关系图中的敏感记忆尤其要受披露规则限制。系统可以记得，但不一定能说。

## 15. 缓存友好约束

这是硬约束。

所有内容都只能注入 dynamic tail：

- 普通召回。
- 深度回忆。
- 人物关系线索。
- 当前任务框架摘要。
- 关系状态。

不能：

- 修改 stable prefix。
- 修改缓存断点前内容。
- 把记忆塞进 system prompt。
- 把深度回忆结果插入旧历史消息。

如果 dynamic tail 没有可用 user message，则追加新的尾部 user message。

## 16. 配置和 API

移除公开 embedding 配置：

- `memory_embedding_url`
- `memory_embedding_key`
- `memory_embedding_model`
- `memory_semantic_dedup_threshold`

旧持久化字段读取时忽略。

新增配置项：

- `memory_recall_max_nodes`
- `memory_deep_recall_enabled`
- `memory_deep_recall_max_candidates`
- `memory_deep_recall_max_clues`
- `memory_person_context_max_clues`

UI 暴露核心项：

- 普通召回条数。
- 深度回忆开关。
- 深度回忆线索数。
- 人物关系线索数。

`/api/companion/state` 增加：

- `graph_node_count`
- `graph_edge_count`
- `search_term_count`
- `working_memory_count`
- `promoted_memory_count`
- `person_node_count`
- `last_deep_recall_at`
- `last_deep_recall_candidates`
- `last_deep_recall_clues`

`/api/companion/memories` 支持只读图记忆搜索：

- `q`
- `uri_prefix`
- `kind`
- `person_uri`
- `project_uri`
- `disclosure`
- `limit`

## 17. 实施顺序

1. 新增图记忆 schema。
2. 新增人物节点和关系边支持。
3. 新增 FTS 和降级搜索。
4. 新增身份解析工具：
   - primary user。
   - self。
   - mentioned person。
   - relation label。
5. 新增当前任务框架推断。
6. 修改总结 prompt，让自动抽取输出图节点、人物线索、披露规则和边。
7. 实现写入、去重、合并。
8. 实现普通召回分层门控。
9. 实现深度回忆严格触发。
10. 实现深度回忆跨任务展开和结果分级。
11. 实现晋升和冲突边。
12. 接入 dynamic tail 注入。
13. 移除公开 embedding 配置。
14. 更新 UI。
15. 更新 README。
16. 补测试。

## 18. Test Plan

数据库测试：

- 图表创建。
- 人物节点创建。
- 关系边创建。
- FTS 与降级搜索。
- 旧 `memories` 表不参与新召回。

身份解析测试：

- 用户第一人称归 `person://user/primary`。
- 助手第一人称归 `person://self/kiyomizu`。
- 多终端不产生不同用户节点。
- “我朋友小王”产生人物关系。
- 模糊“他/她”缺上下文时不新建强人物节点。

任务框架测试：

- 从当前用户消息提取主题、实体、项目和人物。
- 默认不持久化任务框架。
- 重复或确认后可保存为任务记忆。

召回测试：

- 长期偏好可低门槛跨任务召回。
- 项目事实需要项目线索。
- 情景事件需要强线索或深度回忆。
- 临时工作记忆按强度和线索控制。
- 敏感记忆弱线索不注入。

人物关系测试：

- 当前文本提到某人时召回其关系记忆。
- 不把 A 的记忆无故说给 B。
- 每轮人物关系注入数量受配置限制。

深度回忆测试：

- 严格触发。
- 不误触发。
- 可跨任务展开。
- 结果分 direct、weak、conflict。
- 模型失败时本地降级。

晋升测试：

- 重复命中晋升。
- 用户确认晋升。
- 深度回忆命中可晋升。
- 晋升创建新节点和 `derived_from` 边。

冲突测试：

- 冲突记忆并存。
- 召回优先近期和确认记忆。
- 深度回忆能找回旧状态。

缓存测试：

- stable prefix 字节级不变。
- dynamic tail 注入。
- 深度回忆不进入 system prompt。

配置/UI 测试：

- embedding 配置不再公开。
- 旧配置可读取。
- 新配置项校验。
- companion state 返回图和人物状态。
- 图记忆搜索 UI 只读。

## 19. Assumptions

- Kiyomizu 默认是单实例主用户伴侣代理。
- 多终端仍是同一个主用户。
- 不根据请求来源推断身份。
- 多人共享一个实例在无显式身份输入下不可安全区分。
- 需要多人硬隔离时，应分实例；显式身份切换是未来功能。
- 不做旧记忆迁移。
- 去掉 embedding，保留总结模型。
- 深度回忆默认服务当前回复，不默认写回。

## 20. Non-Goals

- 不做多用户账号系统。
- 不做完整社交网络画像。
- 不做图记忆手工编辑。
- 不做旧向量记忆迁移。
- 不引入外部向量库。
- 不把 Nocturne MCP 架构搬进 Kiyomizu。
