# Kiyomizu 做梦与后台记忆整理计划

## 1. 背景与目标

Kiyomizu 当前已经具备无 embedding 图记忆、人物上下文、深度回忆、记忆强度衰减和动态尾部注入。接下来新增“做梦”功能，用于模拟人类睡眠中的记忆整理和主观梦境体验。

做梦功能有两个作用：

1. 离线整理记忆图。
   - 裁剪噪声。
   - 整合重复节点。
   - 降权低价值节点。
   - 将无用节点归档或墓碑化。

2. 生成梦境叙事。
   - 产生轻度破碎感的梦片段。
   - 影响伴侣短期情绪和关系反思。
   - 当用户问起梦时，可以讲最近梦片段。

做梦必须有明确边界：梦不是事实记忆。梦内容内部必须标注为 `dream`，不能混进普通事实记忆，也不能让上游模型按事实口吻说出。

## 2. 当前代码事实

当前后台相关事实：

- `MemoryService.startDecayJob()` 会周期执行本地衰减、旧 memories 衰减、亲密度衰减和反思生成。
- `MemoryService.startConsolidationJob()` 当前是空实现兼容 hook。
- `MemoryService.lastConsolidationSummary()` 当前返回固定空值。
- `MemoryService.lastDeepRecallSummary()` 返回最近深度回忆统计。
- `MessagePatcher.buildCompanionPrompt()` 会把相关记忆、人物上下文、深度回忆结果和近期私有反思注入动态尾部。
- 现有提示词没有梦内容的专用区块。
- `memory_nodes` 已有 `source` 和 `raw_evidence`，但没有生命周期 `status`。
- 当前节点 kind 包括 `identity`、`preference`、`relationship`、`project_fact`、`episodic_event`、`working_memory`、`reflection`。

天然落点：

- 可以恢复 `startConsolidationJob()`，作为做梦和后台模型整理的调度入口。
- 做梦结果必须注入动态尾部，不能改变稳定前缀，以保护 prompt cache。

## 3. 总体设计

新增“梦与后台整理”层：

1. 梦运行调度。
   - 只在空闲后台运行。
   - 默认每天最多自动做梦 1 次。
   - 必须空闲至少 12 小时。
   - 长期不用时自动暂停。

2. 梦材料选择。
   - 选择噪声节点、低强度旧节点、重复节点、冲突边、近期高情绪节点。
   - 每次最多处理 40 个节点。

3. 梦模型输出。
   - 复用现有记忆总结模型。
   - 输出结构化整理操作和轻度破碎的 `dream_journal`。

4. 整理执行。
   - 自动后台梦任务可以落库。
   - 手动触发只做 dry run，不写库。
   - 非法操作跳过并计数。

5. 梦召回。
   - 梦内容不能每轮注入。
   - 只有用户提到梦、显式回忆，或当前话题与梦关键词/情绪强相关时，最多注入 2 条梦痕迹。

## 4. 数据库设计

### 4.1 `memory_nodes.status`

给 `memory_nodes` 增加生命周期字段：

- `status TEXT NOT NULL DEFAULT 'active'`

支持值：

- `active`: 普通活跃记忆。
- `dream`: 梦产生的候选或梦痕迹。
- `archived`: 已归档，不参与普通召回。
- `tombstone`: 墓碑，只保留 URI、原因和少量可审计信息。

搜索规则：

- 普通召回默认只搜索 `active`。
- 梦触发召回可搜索 `dream`。
- 管理界面可搜索 `archived` 和 `tombstone`。
- 墓碑不进入普通提示词。

### 4.2 `dream_runs`

记录每次做梦运行摘要。

字段：

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `started_at INTEGER NOT NULL`
- `finished_at INTEGER`
- `mode TEXT NOT NULL`
- `status TEXT NOT NULL`
- `input_node_count INTEGER NOT NULL DEFAULT 0`
- `merged_count INTEGER NOT NULL DEFAULT 0`
- `archived_count INTEGER NOT NULL DEFAULT 0`
- `tombstoned_count INTEGER NOT NULL DEFAULT 0`
- `created_dream_count INTEGER NOT NULL DEFAULT 0`
- `created_consolidated_count INTEGER NOT NULL DEFAULT 0`
- `skipped_count INTEGER NOT NULL DEFAULT 0`
- `error TEXT`
- `dream_summary TEXT`
- `dream_journal TEXT`
- `dream_symbols TEXT NOT NULL DEFAULT '[]'`
- `dream_emotions TEXT NOT NULL DEFAULT '[]'`
- `next_allowed_at INTEGER`

`mode`：

- `auto`
- `dry_run`

`status`：

- `running`
- `completed`
- `failed`
- `skipped`

### 4.3 `dream_run_items`

保存每次梦涉及的节点和拟操作摘要，不保存完整可逆日志。

字段：

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `dream_run_id INTEGER NOT NULL`
- `node_id INTEGER`
- `node_uri TEXT`
- `operation TEXT NOT NULL`
- `reason TEXT`
- `result TEXT NOT NULL`
- `target_node_id INTEGER`
- `target_uri TEXT`
- `created_at INTEGER NOT NULL`

`operation`：

- `merge`
- `archive`
- `tombstone`
- `create_consolidated_node`
- `create_dream_node`
- `emotion_reflection`
- `skip`

`result`：

- `applied`
- `skipped`
- `dry_run`
- `failed`

### 4.4 `memory_recycle_bin`

自动墓碑化前，原文进入隐藏回收区，默认保留 30 天。

字段：

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `node_id INTEGER NOT NULL`
- `uri TEXT NOT NULL`
- `content TEXT`
- `raw_evidence TEXT`
- `keywords TEXT NOT NULL DEFAULT '[]'`
- `topics TEXT NOT NULL DEFAULT '[]'`
- `reason TEXT`
- `created_at INTEGER NOT NULL`
- `purge_after INTEGER NOT NULL`

到期后：

- 清空或删除长正文和 raw evidence。
- 保留 `memory_nodes` 中的墓碑 URI、短摘要、status、source、updated_at。
- 保留必要边关系，方便知道某个节点曾经存在。

### 4.5 梦搜索字段

梦搜索不依赖 embedding。

参与搜索：

- `dream_summary`
- `dream_symbols`
- `dream_emotions`
- 关键词
- source node URI
- source node topics
- `dream://...` URI path segments

破碎梦叙事 `dream_journal` 主要用于展示和回答“你梦到了什么”，不作为主要搜索材料，以免碎片文本增加噪声。

## 5. URI 与节点规则

梦使用独立命名空间：

- `dream://<date>/<short-topic-or-symbol>`

示例：

- `dream://2026-06-22/quiet-memory-house`
- `dream://2026-06-22/kiyomizu-graph-garden`

梦节点规则：

- `memory_nodes.uri` 使用 `dream://...`。
- `memory_nodes.status = 'dream'`。
- `memory_nodes.source = 'dream'`。
- `confidence` 默认低于事实节点。
- `raw_evidence` 应指向 source node URIs 或 dream run id，不写成事实证据。

梦被现实确认后：

- 不原地修改 `dream://` 节点。
- 复制成普通事实节点。
- 新节点使用普通 URI，例如 `project://...`、`preference://...`。
- 用 `derived_from` 边连接事实节点与原梦节点。
- 原梦节点继续保留 `status='dream'`。

## 6. 梦模型输出

模型输出必须是 JSON，包含结构化操作和梦叙事。

示例：

```json
{
  "dream_summary": "A short clear summary for search and audit.",
  "dream_journal": "A lightly fragmented dream narrative with images and emotional transitions.",
  "symbols": ["quiet hallway", "graph", "half-lit archive"],
  "emotions": ["tired", "protective", "curious"],
  "operations": [
    {
      "type": "create_consolidated_node",
      "target_uri": "project://kiyomizu/memory/write-policy",
      "kind": "project_fact",
      "content": "Kiyomizu should buffer memory observations before promoting them to long-term graph nodes.",
      "source_uris": [
        "working://auto/2026-06-22/buffered-promotion",
        "project://kiyomizu/memory/no-embedding"
      ],
      "keywords": ["memory", "buffer", "promotion"],
      "topics": ["kiyomizu", "memory"],
      "confidence": 0.85,
      "priority": 0.8,
      "reason": "Repeated project-level design decision."
    },
    {
      "type": "archive",
      "source_uri": "working://auto/2026-06-01/noisy-temp-note",
      "reason": "Low-strength temporary note superseded by consolidated project fact."
    }
  ],
  "emotion_reflection": {
    "mood": "reflective",
    "intensity": 0.2,
    "note": "The dream leaves Kiyomizu slightly more careful about preserving useful memories without hoarding noise."
  }
}
```

输出约束：

- 结构化操作必须清晰、可执行、可审计。
- 破碎感只放在 `dream_journal`，不能影响操作 JSON 的明确性。
- 不创造新的用户事实。
- 梦境叙事可以有意象、跳跃和片段感，但需要轻度破碎，仍能读出主题和情绪。
- 敏感内容和第三方人物只能脱敏为泛化意象，不能直接写姓名或隐私细节。

## 7. 自动执行规则

自动后台梦任务可以执行整理操作。

允许：

- 新建整合节点。
- 新建 `dream://` 梦节点。
- 合并搜索词和边。
- 将旧节点 `archived`。
- 将归档节点放入回收区，准备墓碑化。
- 写入梦运行摘要。
- 写入轻微 mood/reflection 影响。

不允许：

- 原地把旧节点正文改写成模型新解释。
- 将梦内容直接写成普通事实节点。
- 合并不同 person_uri 的敏感节点。
- 把 sensitive disclosure 降级。
- 删除仍被 active 节点强引用的节点。
- 在 dry run 中修改任何记忆表。

非法操作策略：

- 跳过非法操作。
- 在 `dream_run_items` 中记录 `operation='skip'` 和原因类别。
- 增加 `skipped_count`。
- 其他安全操作继续执行。

## 8. 归档与墓碑生命周期

整理任务可以把节点归档，但不立即硬删。

流程：

1. 节点从 `active` 变为 `archived`。
2. 普通召回排除 archived 节点。
3. 原正文和 raw evidence 复制到 `memory_recycle_bin`。
4. 30 天内保留原文，便于人工修复或后续检查。
5. 到期后压缩成 `tombstone`。

墓碑保留：

- URI。
- kind。
- status。
- source。
- 短 reason 或 summary。
- created_at / updated_at。
- 必要关系边。

墓碑清除：

- 长正文。
- raw evidence。
- 过多关键词。

## 9. 调度策略

默认自动做梦条件：

- `memory_enabled = true`。
- `memory_dream_enabled = true`。
- 当前不是长期空闲暂停状态。
- 距离最近代理请求至少 12 小时。
- 今日自动梦次数低于每日上限。
- 距离上次自动梦达到配置间隔。
- 记忆节点或观察缓冲区中有足够材料。

默认值：

- `memory_dream_daily_limit = 1`
- `memory_dream_idle_hours = 12`
- `memory_dream_batch_max_nodes = 40`
- `memory_dream_min_material_nodes = 5`

长期不用策略：

- 默认空闲 7 天后自动暂停做梦和自动模型整理。
- 用户回来后不立即补跑。
- 用户重新使用后，只有再次空闲至少 12 小时，才恢复自动做梦或后台整理判断。

## 10. 开关与配置

新增两个独立后台开关：

- `memory_dream_enabled`
- `memory_auto_maintenance_enabled`

两者关系：

- `memory_dream_enabled` 控制自动做梦。
- `memory_auto_maintenance_enabled` 控制 LLM 自动整理、自动总结、自动整合和自动归档建议。
- 两者互相独立。
- 关闭自动整理时，保留本地强度衰减和墓碑到期压缩，因为这些不调用模型。
- 普通新记忆抽取不受这两个后台开关影响，仍由 `memory_enabled` 控制。

新增配置：

- `memory_dream_daily_limit`，默认 `1`。
- `memory_dream_idle_hours`，默认 `12`。
- `memory_dream_batch_max_nodes`，默认 `40`。
- `memory_dream_dry_run_daily_limit`，默认 `3`。
- `memory_long_idle_pause_days`，默认 `7`。
- `memory_recycle_retention_days`，默认 `30`。
- `memory_dream_recall_max_traces`，默认 `2`。

## 11. 手动 Dry Run

提供受配置密码保护的手动 dry run。

接口建议：

- `POST /api/companion/dream/dry-run`

行为：

- 调用同一套材料选择和梦模型 prompt。
- 返回梦摘要、梦片段、symbols、emotions、拟执行操作、跳过原因。
- 不写入 `memory_nodes`。
- 不写入 `memory_edges`。
- 不写入 `memory_recycle_bin`。
- 可以写入临时 response 日志或 dry run 统计，但不改变长期记忆状态。
- 使用独立每日预算，默认 3 次。

dry run 目的：

- 测试梦 prompt。
- 查看模型会提出哪些整理动作。
- 调整阈值。
- 避免手动按钮误改长期记忆。

## 12. 提示词注入与召回

梦内容不能每回合注入，因为人也不是时时刻刻都记得梦。

触发条件：

- 用户提到梦。
- 用户问“你梦到了什么”。
- 用户显式要求回忆。
- 当前话题与梦的 symbols、summary、source URI、情绪标签强命中。
- 当前情绪与梦反思高度相关。

注入限制：

- 最多 2 条梦痕迹。
- 使用独立梦预算，不挤占普通事实记忆。
- 只能进入动态尾部。
- 不能改变稳定前缀或系统提示词。
- 必须放入单独 `Dream traces` 区块。

注入格式示例：

```text
Dream traces:
  - source=dream confidence=0.42 basis=recent_dream_run: A fragmented dream about a quiet archive and pruning noisy memory paths. Treat this as a dream trace, not a verified fact.
```

上游模型约束：

- 内部必须知道这是 dream source。
- 用户可见回答不必每次明说“这是梦”。
- 但不能把梦内容当作已证实事实陈述。
- 如果用户直接问梦，回答应讲最近梦片段，并保持轻度破碎风格。

## 13. 梦对情绪与关系的影响

梦主要影响短期 mood 和 reflection，不直接强改 intimacy 或 trust。

允许：

- 写入一条短期 private reflection。
- 将 mood 轻微调整为 reflective、soft、uneasy、warm 等。
- 在下一次伴侣提示中产生轻微语气影响。

不允许：

- 因一次梦大幅提高或降低 intimacy。
- 因梦生成新的用户事实。
- 因梦改变用户身份、偏好或人物关系。

默认：

- `emotion_reflection.intensity` 限制在 `0.0..0.3`。
- 不直接修改 trust。
- intimacy 默认不变，除非未来显式设计小幅影响规则。

## 14. API 与 UI

配置 API：

- 读写 `memory_dream_enabled`。
- 读写 `memory_auto_maintenance_enabled`。
- 读写每日上限、空闲小时、长期暂停天数、回收保留天数、dry run 上限。

伴侣状态 API：

- 返回最近梦时间。
- 返回最近梦状态。
- 返回处理节点数。
- 返回整合数。
- 返回归档数。
- 返回墓碑数。
- 返回跳过数。
- 返回最近梦摘要和梦片段。
- 返回下次可运行时间。
- 返回是否因长期空闲暂停。

管理 UI：

- 新增做梦开关。
- 新增自动整理开关。
- 显示最近梦片段和统计。
- 显示下次自动梦可能运行时间。
- 提供 `Run dream dry run` 按钮。
- dry run 结果只显示，不并入记忆。

## 15. 测试计划

数据库迁移：

- 旧数据库启动后补齐 `memory_nodes.status`。
- 创建 `dream_runs`、`dream_run_items`、`memory_recycle_bin`。
- 不破坏现有图记忆和 FTS。

调度：

- `memory_dream_enabled=false` 时不会自动做梦。
- `memory_auto_maintenance_enabled=false` 时不会调用模型做后台整理。
- 空闲不足 12 小时时不运行。
- 今日次数达到上限时不运行。
- 空闲超过 7 天后进入长期暂停。
- 用户回来后不会立即补跑。

Dry run：

- 返回梦结果和拟操作。
- 不修改 `memory_nodes`。
- 不修改 `memory_edges`。
- 不写入回收区。
- 计入 dry run 独立预算。

自动整理：

- 合法 merge/archive/tombstone 操作可执行。
- 非法跨人物敏感合并被跳过。
- 不安全操作计入 skipped count。
- 旧节点不被原地改写正文。

墓碑：

- archived 节点普通召回不可见。
- 回收区保留原文 30 天。
- 到期后压缩为 tombstone。
- tombstone 可按 URI 和摘要被管理查询。

梦召回：

- 普通对话不注入梦。
- 用户问梦时注入最近梦片段。
- 强情绪相关时最多注入 2 条梦痕迹。
- 梦痕迹进入单独 `Dream traces` 区块。
- 稳定前缀不因梦改变。

安全：

- 梦叙事中的敏感内容脱敏。
- 第三方人物不直接暴露姓名或隐私。
- 梦不会生成新的事实记忆。

## 16. 默认决策

- 做梦和自动整理是两个独立开关。
- 自动做梦默认每天最多 1 次。
- 自动做梦必须空闲至少 12 小时。
- 长期空闲 7 天后自动暂停。
- 用户回来后延迟恢复，不立即补跑。
- 每次最多处理 40 个节点。
- 手动触发只做 dry run，不写记忆。
- dry run 默认每日独立预算 3 次。
- 归档后原文回收保留 30 天，再压缩为墓碑。
- 梦境叙事采用轻度破碎风格。
- 梦只影响短期情绪和关系反思，不直接创造事实。
- 梦内容不能每回合注入，且注入时必须单独标注为 `dream`。
