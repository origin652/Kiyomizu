# Kiyomizu 无 Embedding 图记忆与深度回忆改造完整计划

## 1. 背景与目标

本计划解决的问题是：Kiyomizu 当前的伴侣记忆功能依赖独立 embedding 模型来写入、去重、召回和离线巩固记忆。目标是在不依赖 embedding 模型的前提下，保留并增强“长期记忆”和“深度回忆”能力。

新的记忆系统不应退化成简单聊天日志。它应该接近人类记忆的工作方式：平时通过线索快速联想，明确被要求“回想”时进入更慢、更深的回忆过程；记忆会因为重复、确认、情绪显著和使用频率而加强，也会随时间衰减。

参考方向是 Dataojitori/nocturne_memory 的一些核心思想：可读路径、触发词、全文搜索、搜索词派生、披露规则和记忆之间的边。但 Kiyomizu 不引入 MCP 架构，也不照搬 Nocturne 的实现形态；实现要适配当前 Kotlin、Ktor、SQLite、代理透传和缓存注入结构。

## 2. 当前代码事实

当前项目中 embedding 是伴侣记忆的硬依赖：

- `MemoryService.fetchEmbedding()` 通过外部 HTTP API 获取 embedding。
- `MemoryService.extractAndSaveMemoriesAsync()` 在总结模型抽取 memory 后调用 `fetchEmbedding(content)`，只有拿到 vector 才会写入旧 `memories` 表。
- `MemoryService.recallMemories()` 对用户 query 获取 embedding，再和所有记忆向量做 cosine similarity。
- `MemoryService.consolidateOnce()` 用向量相似度聚类 episodic memories，并为语义抽象结果再次获取 embedding。
- `DatabaseService.memories.vector` 是 `BLOB NOT NULL`。
- 前端 UI 暴露了 embedding endpoint、key、model 和语义去重阈值。
- README 将记忆流程描述为“抽取记忆、embedding、向量相似度召回”。

当前也已有可复用能力：

- 总结模型已经用于抽取原子记忆、关系状态变化和情绪信息。
- SQLite 已经保存关系状态、记忆、反思和请求日志。
- `MessagePatcher` 已经把伴侣上下文注入到 dynamic tail，避免破坏 stable prefix 和 prompt cache。
- 现有配置 API、UI 和 companion state 面板可以承载新记忆系统状态。

当前本地验证环境存在两个执行问题：

- `gradlew` 是 CRLF 换行，Linux shell 直接执行失败。
- 当前环境缺少 `JAVA_HOME` 或 `java` 命令，无法实际运行 `./gradlew test`。

## 3. 总体设计

重构后的记忆系统由三层组成：

1. 图记忆存储层。
   - 每条记忆变成可搜索、可寻址、可关联的节点。
   - 记忆之间通过边表达支持、派生、相关、冲突、更新和人物关系。

2. 本地召回层。
   - 普通召回不调用 embedding，也默认不调用额外模型。
   - 使用全文搜索、关键词、别名、实体、主题、路径前缀、情绪强度、时间、访问次数和图边展开进行评分。

3. 深度回忆层。
   - 只在用户明确要求助手回忆过去互动时触发。
   - 先用本地图搜索扩大候选，再可选调用总结模型做一次回忆重构和压缩。
   - 模型失败时降级为本地图搜索结果，不阻断代理请求。

## 4. 数据库设计

保留旧 `memories` 表，但新系统默认不再读取它，也不迁移旧数据。旧表存在的目的只是兼容已有数据库，不破坏启动。

新增表如下。

### 4.1 `memory_nodes`

用于保存图记忆节点。

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
- `created_at INTEGER NOT NULL`
- `updated_at INTEGER NOT NULL`
- `last_accessed_at INTEGER NOT NULL`
- `access_count INTEGER NOT NULL DEFAULT 0`
- `source TEXT NOT NULL DEFAULT 'conversation'`
- `raw_evidence TEXT`

`kind` 第一版支持：

- `identity`
- `preference`
- `relationship`
- `project_fact`
- `episodic_event`
- `working_memory`
- `reflection`

`disclosure` 第一版支持：

- `private`: 只作为私有上下文，不建议直接说出口。
- `hint`: 可自然使用，但不要像读数据库一样复述。
- `quote_allowed`: 可以直接引用或明确说“我记得你说过……”。
- `sensitive`: 只有强线索命中时才可作为私有上下文，默认不直接表达。

### 4.2 `memory_edges`

用于保存记忆节点之间的关系。

字段：

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `from_node_id INTEGER NOT NULL`
- `to_node_id INTEGER NOT NULL`
- `relation TEXT NOT NULL`
- `weight REAL NOT NULL DEFAULT 1.0`
- `created_at INTEGER NOT NULL`
- `updated_at INTEGER NOT NULL`

`relation` 第一版支持：

- `related_to`
- `derived_from`
- `reinforces`
- `contradicts`
- `supersedes`
- `mentions`
- `about_person`
- `about_project`
- `triggered_by`

### 4.3 `memory_search_terms`

用于保存派生搜索词。

字段：

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `node_id INTEGER NOT NULL`
- `term TEXT NOT NULL`
- `kind TEXT NOT NULL`
- `weight REAL NOT NULL DEFAULT 1.0`

`kind` 第一版支持：

- `keyword`
- `alias`
- `entity`
- `topic`
- `trigger`
- `path_segment`

### 4.4 FTS 表

优先使用 SQLite FTS5：

- `memory_nodes_fts(node_id UNINDEXED, content, searchable_text, keywords, aliases, entities, topics, trigger_phrases)`

如果运行环境不支持 FTS5，则降级到：

- `LIKE` 查询。
- `memory_search_terms` 词项匹配。
- 本地 token overlap 评分。

## 5. URI 与路径体系

第一版使用伴侣域名空间：

- `user://...`
- `relationship://...`
- `person://...`
- `episode://...`
- `preference://...`
- `project://...`
- `emotion://...`
- `working://...`

URI 不是展示文本，而是召回和组织的稳定锚点。

示例：

- `preference://food/drink/tea`
- `relationship://trust/current`
- `episode://2026-06/work-pressure-night-chat`
- `project://kiyomizu/memory/no-embedding`
- `working://current/task/cache-sensitive-memory-design`

URI 生成原则：

- 由总结模型给出候选 URI。
- 后端做规范化：小写、去危险字符、路径段长度限制、空格转 `-`。
- 如果 URI 冲突，优先合并或更新现有节点，而不是盲目新建。
- 如果模型无法给出可靠 URI，后端生成 `working://auto/<timestamp>-<hash>` 或 `episode://auto/<date>/<hash>`。

## 6. 记忆写入流程

写入仍发生在模型响应返回后，由 `MemoryService.extractAndSaveMemoriesAsync()` 异步执行。

新流程：

1. 提取最新 user text 和 assistant response text。
2. 调用总结模型，要求返回图记忆 JSON。
3. 校验 JSON 格式和字段范围。
4. 对每个节点生成本地搜索线索：
   - 规范化文本。
   - 本地 token。
   - URI path segments。
   - 模型返回的关键词、别名、实体、主题、触发词。
5. 进行去重和合并：
   - URI 完全相同。
   - normalized_text 高重叠。
   - keyword/entity/topic 高重叠。
   - 同 kind 且同路径前缀。
6. 写入或更新 `memory_nodes`。
7. 写入 `memory_search_terms`。
8. 写入 `memory_edges`。
9. 更新 FTS 表。

总结模型输出格式应包含：

```json
{
  "nodes": [
    {
      "uri": "preference://food/drink/tea",
      "kind": "preference",
      "content": "User prefers tea.",
      "keywords": ["tea", "drink"],
      "aliases": ["ocha"],
      "entities": ["user"],
      "topics": ["food", "preference"],
      "trigger_phrases": ["tea", "drink preference"],
      "disclosure": "hint",
      "priority": 0.7,
      "confidence": 0.8,
      "emotion_valence": 0.6,
      "emotion_arousal": 0.2,
      "source": "conversation"
    }
  ],
  "edges": [
    {
      "from_uri": "preference://food/drink/tea",
      "to_uri": "person://user/primary",
      "relation": "about_person",
      "weight": 1.0
    }
  ],
  "intimacy_delta": 0.5,
  "trust_delta": 0.0,
  "mood": "neutral"
}
```

## 7. 普通召回流程

普通召回用于每轮伴侣上下文注入。

输入：

- 最新用户消息。
- 当前请求中的系统提示和最近对话文本。
- 当前 model、path 等弱上下文。

输出：

- 少量高质量记忆线索。
- 默认上限通过 `memory_recall_max_nodes` 配置。

候选生成：

1. 从最新用户消息提取本地 tokens。
2. 根据 tokens 查询 FTS 或降级搜索。
3. 匹配 URI path segments。
4. 匹配 trigger_phrases、aliases、entities、topics。
5. 加入近期高强度且长期类型的少量节点。
6. 沿高权重 `related_to`、`reinforces`、`about_person` 边展开一跳。

评分因素：

- 文本匹配分。
- trigger phrase 命中分。
- entity/topic 命中分。
- URI path 前缀命中分。
- `strength`。
- `priority`。
- `confidence`。
- 情绪显著性。
- 最近访问时间。
- 记忆类型。
- 披露规则。

普通召回不调用额外模型。它必须快，并且不能阻塞代理主路径太久。

## 8. 深度回忆流程

深度回忆不是后台整理，也不是数据库维护任务。它是当前对话中“用户明确要求助手回忆过去互动”时触发的慢速回想过程。

严格触发条件：

- 用户明确要求助手回忆过去互动或过去记忆。
- 典型表达包括：
  - “你还记得……吗”
  - “帮我回忆一下我们之前……”
  - “之前我跟你说过什么……”
  - “你能不能想起来……”

不触发的情况：

- 用户只是说“我记得……”
- 用户在普通问题里提到“之前”但没有要求助手回忆。
- 用户讨论记忆系统本身，但不是让角色回忆过去互动。

流程：

1. 检测深度回忆触发。
2. 从用户问题生成回忆线索：
   - 关键词。
   - 人物。
   - 主题。
   - 时间表达。
   - 情绪线索。
3. 本地图搜索扩大候选。
4. 沿图边做 1 到 2 跳展开。
5. 取约 30 到 60 个候选，数量由 `memory_deep_recall_max_candidates` 控制。
6. 如果总结模型可用，调用一次总结模型进行回忆重构和压缩。
7. 如果总结模型不可用或失败，使用本地评分结果降级。
8. 最终注入最多约 10 条线索，数量由 `memory_deep_recall_max_clues` 控制。

深度回忆输出分级：

- `direct`: 可用于本轮回答。
- `weak`: 可能相关，但表达时必须保留不确定性。
- `conflict`: 与其他记忆冲突，只用于提醒模型谨慎。

深度回忆默认不写回数据库。它服务当前回复。只有后续晋升机制明确触发时，才会写入长期节点。

## 9. 记忆晋升与遗忘

临时工作记忆不依赖 session 边界，也不在请求结束时立即删除。

临时记忆按强度衰减。满足以下多条件之一或多个时可晋升：

- 重复命中。
- 用户语义确认。
- 情绪显著。
- 深度回忆中被命中。
- 总结模型判断为长期事实。

晋升策略：

- 保留原临时节点。
- 新建更长期节点。
- 用 `derived_from` 或 `reinforces` 边连接。

这样可以保留“这个稳定记忆是如何形成的”的来源链。

## 10. 冲突处理

不同时间、不同任务或不同情绪状态下可能产生冲突记忆。

策略：

- 不覆盖旧记忆。
- 不直接删除冲突记忆。
- 用 `contradicts` 或 `supersedes` 边表达冲突或更新关系。
- 召回时按近期性、确认状态、当前线索、记忆类型和披露规则裁剪。

如果冲突不可判断，注入时应作为不确定线索，而不是确定事实。

## 11. 缓存友好约束

这是硬约束。

所有普通召回、深度回忆、人物关系线索和伴侣状态都只能注入 dynamic tail。

实现要求：

- 不修改 stable prefix。
- 不修改缓存断点前消息。
- 如果 dynamic tail 里没有可用 user message，则追加新的尾部 user message。
- 不把记忆注入 system prompt。
- 不把深度回忆结果插入历史稳定消息。

当前 `MessagePatcher.injectCompanionIntoConversation()` 已有类似逻辑，新的记忆注入必须沿用并加强测试。

## 12. 配置和 API

移除公开 embedding 配置：

- `memory_embedding_url`
- `memory_embedding_key`
- `memory_embedding_model`
- `memory_semantic_dedup_threshold`

旧持久化配置中如果存在这些字段，读取时忽略，不报错。

新增配置项：

- `memory_recall_max_nodes`
- `memory_deep_recall_enabled`
- `memory_deep_recall_max_candidates`
- `memory_deep_recall_max_clues`

`/api/companion/state` 增加：

- `graph_node_count`
- `graph_edge_count`
- `search_term_count`
- `working_memory_count`
- `last_deep_recall_at`
- `last_deep_recall_candidates`
- `last_deep_recall_clues`

`/api/companion/memories` 改为图记忆浏览与搜索：

- 支持 `q`
- 支持 `uri_prefix`
- 支持 `kind`
- 支持 `disclosure`
- 支持分页或 limit

UI 范围：

- 只读浏览和搜索。
- 显示节点、路径、关键词、触发词、强度、披露规则和关联边。
- 第一版不做手工编辑。

## 13. 实施顺序

1. 新增图记忆 schema 和 DAO 方法。
2. 添加 FTS 初始化和降级搜索。
3. 新增图记忆数据类。
4. 修改总结 prompt 和解析逻辑，让写入产生图节点和边。
5. 移除 embedding 写入路径。
6. 实现本地图召回。
7. 接入 `MessagePatcher`，保持 dynamic tail 注入。
8. 实现严格深度回忆触发检测。
9. 实现深度回忆候选展开、模型重构和降级。
10. 移除公开 embedding 配置。
11. 更新 UI 和 README。
12. 补测试。

## 14. Test Plan

数据库测试：

- 新表创建成功。
- 旧 `memories` 表保留。
- FTS 可用时能搜索。
- FTS 不可用时降级可搜索。
- 写入节点、搜索词和边后可正确读取。

写入测试：

- 总结模型返回合法图 JSON 时能落库。
- 缺字段或非法字段不会导致崩溃。
- URI 重复时更新或合并。
- normalized_text 近似重复时不盲目新增。

召回测试：

- keyword 命中排序更高。
- alias 命中可召回。
- entity/topic 命中可召回。
- URI path 前缀命中可召回。
- 高强度和高置信记忆排序更高。
- sensitive disclosure 不会弱线索召回。

深度回忆测试：

- 明确“你还记得”触发。
- “我记得”不触发。
- 模型重构成功时使用 direct/weak/conflict 分级。
- 模型失败时降级到本地结果。
- 候选和最终线索数量受配置限制。

缓存测试：

- stable prefix 字节级不变。
- dynamic tail 被修改或追加。
- deep recall 不进入 system prompt。

配置和 UI 测试：

- 公开配置不返回 embedding key/url/model。
- 旧配置含 embedding 字段时加载不失败。
- 新配置项校验范围。
- companion state 返回图记忆状态。

## 15. Assumptions And Non-Goals

Assumptions:

- Kiyomizu 默认是本地单用户伴侣代理。
- 去掉的是 embedding 模型，不是总结模型。
- 总结模型仍用于抽取、关系状态更新和深度回忆重构。
- 旧记忆不迁移，保留但默认不召回。

Non-goals:

- 不引入外部向量库。
- 不引入 Nocturne 的 MCP 架构。
- 不做多人账号系统。
- 不做图记忆手工编辑 UI。
- 不在第一版做旧记忆批量迁移。
