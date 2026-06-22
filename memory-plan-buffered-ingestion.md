# Kiyomizu 缓冲后晋升记忆写入计划

## 1. 背景与目标

当前无 embedding 图记忆系统已经把召回改成本地搜索、词项匹配、URI、图边扩展和规则打分。普通调用时，上游聊天模型并不自己取记忆；Kiyomizu 后端先在本地召回，再把结果注入动态尾部。

新的问题是写入侧膨胀过快。每轮对话结束后，记忆总结模型只要返回 `nodes`，后端基本都会写入 `memory_nodes`。现有去重也偏严格：只有 URI 完全相同，或者同类节点 token 重叠很高，才会被合并。因此同一个项目里的临时讨论、换句话说的偏好、当天事件、普通上下文，都可能变成新的长期节点。

本计划目标是让系统更接近人类记忆：每句话可以留下短暂痕迹，但不是每句话都永久保存。记忆应先进入短期观察缓冲区，只有被重复、确认、情绪显著、与身份/关系/项目关键决策有关，或者用户明确要求“记住”时，才晋升为长期图记忆节点。

## 2. 当前代码事实

当前写入流程在 `MemoryService.extractAndSaveMemoriesAsync()`：

- 从最新 user message 和 assistant response 生成 `history`。
- 调用 `fetchSummarizationAndStateUpdate(history)`。
- 总结模型按 `Config.memorySummaryPrompt` 返回 JSON，主要字段为 `nodes`、`edges`、`intimacy_delta`、`trust_delta`、`mood`。
- `parseSummaryNodes()` 将模型返回的每个 node 转成 `SummaryNodePayload`。
- `summaryNodeToDraft()` 将 payload 转成 `DatabaseService.MemoryNodeDraft`。
- `findDuplicateNode()` 只按 URI、同 kind、文本/关键词/topic/alias 的 Jaccard 重叠、person/project 轻量加分判断重复。
- 找到重复则 `updateMemoryNode()` 合并；找不到就 `insertMemoryNode()` 新增。
- `working_memory`、`episodic_event` 等类型没有独立写入门槛。
- 每轮总结默认可能新增多个长期节点。

当前 prompt 也鼓励模型直接构造图节点：

- “Build a compact graph-memory update instead of a chat log.”
- “Prefer durable identity, preference, relationship, project_fact, episodic_event, and working_memory nodes.”
- 输出结构直接是 `nodes` 和 `edges`。

因此膨胀主因不是召回，而是写入路径偏 append-only。

## 3. 总体设计

把记忆写入拆成两级：

1. 观察层。
   - 每轮总结模型先产出观察项，不直接等同长期记忆。
   - 观察项可以保存短期证据、关键词、人物、项目、情绪显著性和候选 URI。
   - 观察项默认有过期时间和较低写入成本。

2. 晋升层。
   - 后端本地规则先判断观察项是否应该忽略、合并到已有节点、留在缓冲区，或晋升为长期记忆节点。
   - 后台自动整理和做梦可以从观察层提炼长期节点。
   - 用户明确要求“记住”时允许快速晋升。

核心原则：

- 每轮可以观察，不代表每轮新增长期节点。
- 长期图节点应该表达稳定事实、身份、偏好、关系边界、项目关键决策、反复出现的事件模式。
- 临时对话、普通解释、一次性上下文、模型回复内容，不应默认进入长期图。
- `working_memory` 默认应更新当前 project/topic 槽位，而不是频繁新建。

## 4. 新增数据结构

### 4.1 `memory_observations`

新增短期观察表，用于保存尚未晋升的记忆痕迹。

字段：

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `candidate_uri TEXT`
- `kind TEXT NOT NULL`
- `content TEXT NOT NULL`
- `normalized_text TEXT NOT NULL`
- `searchable_text TEXT NOT NULL`
- `keywords TEXT NOT NULL DEFAULT '[]'`
- `aliases TEXT NOT NULL DEFAULT '[]'`
- `entities TEXT NOT NULL DEFAULT '[]'`
- `topics TEXT NOT NULL DEFAULT '[]'`
- `trigger_phrases TEXT NOT NULL DEFAULT '[]'`
- `person_uri TEXT`
- `project_uri TEXT`
- `scope_hint TEXT`
- `priority REAL NOT NULL DEFAULT 0.5`
- `confidence REAL NOT NULL DEFAULT 0.5`
- `emotion_valence REAL NOT NULL DEFAULT 0.5`
- `emotion_arousal REAL NOT NULL DEFAULT 0.3`
- `novelty REAL NOT NULL DEFAULT 0.5`
- `source TEXT NOT NULL DEFAULT 'conversation'`
- `raw_evidence TEXT`
- `status TEXT NOT NULL DEFAULT 'buffered'`
- `matched_node_id INTEGER`
- `seen_count INTEGER NOT NULL DEFAULT 1`
- `first_seen_at INTEGER NOT NULL`
- `last_seen_at INTEGER NOT NULL`
- `expires_at INTEGER NOT NULL`

`status` 第一版支持：

- `buffered`: 暂存观察，未晋升。
- `merged`: 已合并到已有长期节点。
- `promoted`: 已晋升为长期节点。
- `ignored`: 判定为噪声或不值得保存。
- `expired`: 过期清理。

索引：

- `idx_memory_observations_status_expires(status, expires_at)`
- `idx_memory_observations_kind(kind)`
- `idx_memory_observations_person_uri(person_uri)`
- `idx_memory_observations_project_uri(project_uri)`
- `idx_memory_observations_candidate_uri(candidate_uri)`
- `idx_memory_observations_last_seen(last_seen_at DESC)`

### 4.2 `memory_observation_terms`

用于无 embedding 搜索观察项。

字段：

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `observation_id INTEGER NOT NULL`
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
- `scope_term`
- `path_segment`

### 4.3 可选 FTS 表

优先使用 SQLite FTS5：

- `memory_observations_fts(observation_id UNINDEXED, content, searchable_text, keywords, aliases, entities, topics, trigger_phrases)`

FTS 不可用时降级到：

- `memory_observation_terms`
- `LIKE`
- 本地 token overlap

## 5. 总结模型输出调整

将默认 prompt 从“直接产出 nodes”调整为“产出 observations 和少量 fast_promote candidates”。

第一版仍可兼容旧 `nodes` 字段：

- 如果模型返回 `observations`，按新流程处理。
- 如果模型只返回旧 `nodes`，后端将其当作观察项处理，不直接插入长期节点。

推荐新 JSON 形态：

```json
{
  "observations": [
    {
      "candidate_uri": "project://kiyomizu/memory/buffered-ingestion",
      "kind": "project_fact",
      "content": "The user wants memory writes to use a buffer-before-promotion policy to reduce node growth.",
      "keywords": ["memory", "buffer", "promotion", "node growth"],
      "aliases": [],
      "entities": ["person://user/primary"],
      "topics": ["kiyomizu", "memory", "ingestion"],
      "trigger_phrases": ["memory node growth", "buffered promotion"],
      "person_uri": "person://user/primary",
      "project_uri": "project://kiyomizu",
      "scope_hint": "project",
      "priority": 0.8,
      "confidence": 0.9,
      "emotion_valence": 0.5,
      "emotion_arousal": 0.3,
      "novelty": 0.8,
      "source": "conversation",
      "raw_evidence": "每次都有新增节点感觉膨胀的太快了"
    }
  ],
  "edges": [
    {
      "from_uri": "project://kiyomizu/memory/buffered-ingestion",
      "to_uri": "project://kiyomizu",
      "relation": "about_project",
      "weight": 1.0
    }
  ],
  "intimacy_delta": 0.0,
  "trust_delta": 0.0,
  "mood": "neutral"
}
```

Prompt 规则：

- 不要把每轮普通上下文都写成长期记忆。
- 优先提取可重复、可验证、长期有用的观察。
- 对一次性执行细节、临时推理、普通闲聊，允许返回空 observations。
- 明确要求“记住”的内容应标注高 priority 和高 confidence。
- `working_memory` 只用于当前任务状态，不应无限新增。
- 不确定内容应降低 confidence，并留在缓冲区。

## 6. 本地写入决策

每条 observation 进入 `classifyObservation()`，输出一个动作：

- `ignore`
- `merge_existing`
- `buffer`
- `promote_to_memory`

### 6.1 直接忽略

满足任一条件时忽略：

- 内容太短或信息密度低。
- 只是 assistant 自己的普通解释。
- 只是临时过渡语、感谢、寒暄、重复确认。
- confidence 低于阈值，且没有情绪显著性。
- 与已有 observation 或 memory 高度重复，但不增加新证据。
- `kind` 不在允许集合中。

默认阈值：

- `memory_observation_min_confidence = 0.35`
- `memory_observation_min_content_length = 12`

### 6.2 合并已有长期节点

满足任一条件时合并：

- `candidate_uri` 与已有 `memory_nodes.uri` 相同。
- 同 kind、同 person/project、token overlap 达到阈值。
- 观察项是对已有节点的强化、改写或补充。
- 用户重复提到已有偏好、身份、边界或项目事实。

合并动作：

- 不新增节点。
- 更新已有节点的 strength、priority、confidence、last_accessed_at、search terms。
- 将 observation 标记为 `merged`，写入 `matched_node_id`。
- 可选保留 raw evidence 在 observation 中，而不是膨胀长期节点正文。

默认阈值：

- identity/preference/relationship: overlap >= 0.55 且 person match。
- project_fact/working_memory: overlap >= 0.45 且 project match 或 topic match。
- episodic_event: overlap >= 0.65，避免误合并不同事件。

### 6.3 留在缓冲区

默认动作是 `buffer`。

适合：

- 可能有用但还不稳定。
- 单次出现的 project detail。
- 不确定的人物关系。
- 一次性的情绪事件。
- 与现有记忆弱相关但证据不足。

默认保留时间：

- identity/preference/relationship: 30 天。
- project_fact/working_memory: 14 天。
- episodic_event: 7 天。
- low confidence observations: 3 天。

同类观察重复出现时：

- 增加 `seen_count`。
- 更新 `last_seen_at`。
- 合并关键词和证据。
- 重新计算晋升分数。

### 6.4 晋升为长期节点

满足任一强条件时晋升：

- 用户明确要求“记住”“别忘了”“以后要按这个来”。
- identity、长期 preference、relationship boundary、sensitive boundary，confidence >= 0.75。
- project_fact 是明确决策、约束、架构选择或用户明确要保存的工作约定。
- 同一 observation 或同义观察重复出现达到次数阈值。
- emotion_arousal 高且内容与关系、边界、长期偏好有关。

默认晋升条件：

- explicit_remember = true: 立即晋升。
- identity/preference/relationship: confidence >= 0.75 且 priority >= 0.55。
- project_fact: confidence >= 0.80 且 priority >= 0.65，或 seen_count >= 2。
- working_memory: 默认不晋升为新增节点，优先更新 current project/topic 槽位。
- episodic_event: 需要 emotion_arousal >= 0.65 或用户明确要求保存。

晋升动作：

- 创建或更新 `memory_nodes`。
- 写入 `memory_search_terms` 和 FTS。
- 写入相关 `memory_edges`。
- observation 标记为 `promoted`，记录 `matched_node_id`。

## 7. Working Memory 收敛策略

`working_memory` 是最容易膨胀的类型，第一版需要强限制。

规则：

- 按 `project_uri + scope_hint/topic` 维护槽位。
- 同一项目同一主题默认更新已有 working node，不新增。
- 每个 project 默认最多保留 3 个 active working_memory 槽位。
- 超过上限时，低强度或旧槽位进入 observation 或 archived，而不是继续增加 active node。
- `working_memory` 超过 14 天未访问时降低召回权重；超过 30 天且没有重复强化时可墓碑化或转为 observation summary。

写入策略：

- 如果当前观察能匹配已有 working node，合并到已有节点。
- 如果没有匹配，但 project 下槽位未满，创建新 working node。
- 如果槽位已满，优先更新最相近槽位，或把 observation 留在缓冲区等待后台整理。

## 8. 去重与合并增强

现有 `findDuplicateNode()` 只看同 kind 的高 Jaccard 重叠，需要增强但不能依赖 embedding。

新增评分维度：

- URI exact match。
- candidate_uri 与现有 URI 的 path segment overlap。
- normalized content token overlap。
- keywords/topics/aliases overlap。
- person_uri soft match。
- project_uri soft match。
- scope_hint match。
- trigger phrase match。
- raw_evidence 是否来自同一轮或近邻轮次。
- kind-specific threshold。

合并结果：

- 不盲目选择更长 content。
- 对长期节点保留稳定概括正文。
- 新证据主要留在 observation 层。
- search terms 可以合并。
- confidence 只在新证据更强时提升。
- sensitive disclosure 不能被低敏输入降级。

## 9. 自动整理与做梦的关系

缓冲区是自动整理和做梦的主要输入来源之一。

自动整理：

- 从 buffered observations 中提取重复模式。
- 合并同义观察。
- 将长期有价值内容晋升。
- 将过期、低价值观察标记 expired。

做梦：

- 可以读取 observations 作为梦的材料。
- 可以从 observations 生成情绪反思或轻度破碎梦片段。
- 不应把梦境叙事直接变成长期事实。
- 如果梦任务发现观察项应晋升，仍需走同一套晋升规则。

## 10. 配置项

新增配置：

- `memory_buffered_ingestion_enabled`，默认 `true`。
- `memory_observation_retention_days`，默认 `14`。
- `memory_low_confidence_observation_retention_days`，默认 `3`。
- `memory_observation_min_confidence`，默认 `0.35`。
- `memory_promote_repeat_threshold`，默认 `2`。
- `memory_project_fact_promote_repeat_threshold`，默认 `2`。
- `memory_working_memory_slots_per_project`，默认 `3`。
- `memory_observation_daily_cap`，默认 `200`。
- `memory_promoted_nodes_daily_cap`，默认 `20`。

兼容策略：

- `memory_enabled=false` 时，召回、观察写入、长期写入都关闭。
- `memory_buffered_ingestion_enabled=false` 时，临时回退旧写入流程，但仍可保留增强去重。
- 后台自动整理开关不影响每轮 observation 写入。

## 11. API 与 UI

配置 API：

- 读写上述新配置项。
- 验证阈值范围和每日上限。

伴侣状态 API：

- 返回 active memory node count。
- 返回 buffered observation count。
- 返回今日晋升数。
- 返回今日忽略数。
- 返回最近一次自动整理摘要。

管理 UI：

- 显示“长期记忆节点数”和“观察缓冲区数量”。
- 显示今日新增长期节点数，帮助判断是否继续膨胀。
- 可配置缓冲晋升开关、重复晋升阈值、working slot 数量。
- 可查看最近 observation 样本，但默认不把所有观察都展示成长期记忆。

## 12. 测试计划

数据库迁移：

- 旧数据库启动后创建 `memory_observations`、`memory_observation_terms` 和可选 FTS 表。
- 不破坏现有 `memory_nodes`、`memory_edges`、`memory_search_terms`。

写入收敛：

- 普通寒暄不新增长期节点。
- 普通一次性工作细节进入 observation，不新增长期节点。
- 用户明确说“记住”时晋升长期节点。
- 同一偏好重复两次后晋升。
- 同一 project fact 重复两次或被明确确认后晋升。
- `working_memory` 同 project/topic 更新已有槽位，不无限新增。

合并去重：

- 同 URI 观察合并已有节点。
- 同 person/project/topic 的改写合并已有节点。
- 不同事件但相似词汇不会误合并。
- sensitive 节点不会被普通输入降级。

召回：

- 普通召回默认只读 active memory nodes。
- observation 不进入普通提示词，除非未来显式设计临时上下文注入。
- 深度回忆可以选择性查看 observation，但必须标注为未晋升观察。

成本与膨胀：

- 每轮总结仍可运行，但长期节点新增受每日上限限制。
- observation 超过保留期会过期。
- 今日长期节点新增数量可观测。

## 13. 默认决策

- 采用“缓冲后晋升”，不是“直接写但限流”。
- 每轮总结模型先产出 observations，旧 nodes 输出也按 observation 处理。
- 普通新记忆抽取仍由现有 `memory_enabled` 控制。
- 长期图节点默认只保存稳定事实，不保存每轮临时痕迹。
- `working_memory` 必须按项目/主题槽位收敛。
- 自动整理和做梦可以从缓冲区提炼，但不能绕过晋升规则。
