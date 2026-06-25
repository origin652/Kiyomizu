# Plan — 话题功能 (Topics)

状态: 设计已与用户对齐 (grilled 2026-06-25)。待实现。
关联: `memory-plan-dream-maintenance.md`、`docs/plan-per-node-stability.md`

## 0. 设计原则: 上游 AI 不操作

> 用户硬约束: "理论上这个项目不应该让上游 AI 有任何的'操作'。"

本功能严格遵守:

- 话题的 **生成、抽取、选取、消耗、删除** 全部由代理侧 (Ktor) 完成。
- 上游 AI 只接收 **只读的提示文本** (注入 `MessagePatcher` 动态尾),不能写回、不能选择、不能调用工具。
- 所有的 LLM 调用都是 **代理侧调摘要模型** (`callSummaryModel`),产出 JSON 由代理解析落库,与现有 dream operations 模式一致。

## 1. 数据模型

新建 `topics` 表 (在 `DatabaseService.kt` 添加 schema + 迁移)。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | int pk | |
| `title` | text | 话题单题 |
| `lead_in` | text | 引子 (一句开场话/AI 可承接的句子) |
| `source_uris` | text(JSON array) | 生成时所依据的源节点 uri 列表 |
| `status` | text | `unused` / `used` |
| `generated_at` | long | 生成时间戳 |
| `used_at` | long null | 消耗/注入时间戳 |
| `created_at` / `updated_at` | long | |

- 使用独立表 (`topics`) 而非 `kind=topic` 节点: 话题是周期性产物、有 FIFO 选取与状态流转,与记忆图的 decay/recall 生命周期解耦更干净。
- 已用话题不并入记忆图,避免污染召回。

## 2. 生成 (搭现有每小时 dream/job 便车)

挂在 `MemoryService.startConsolidationJob`。每小时检查,dream/maintenance **跑完后** 顺便触发一次话题生成,**不替代** dream。复用 idle 门槛与每日上限。

### 2.1 触发条件

```
每小时检查时应满足:
- existing dream/maintenance has run or was skipped (话题紧随其后)
- 未用话题数 < memoryTopicUnusedSlotCap (默认 5)   # 池满不生成
- 其他沿用 dream 的 idle 门与 daily caps (或话题单独的每日上限)
```

### 2.2 候选抽取 (collector)

两拌各取一半:

- **高份批 (N/2):** active 节点按 `strength`(合成 stability/FSRS 权重)降序取前 N/2。
- **新鲜批 (N/2):** 近期生成且**未被注入对话**的节点,降序取前 N/2。
- 合并去重喂模型,默认 N=20 (各 10)。
- **LRU 排除:** 维护一个"近 20 次被抽创作话题"的节点 uri 集合,候选前先排除这些 uri,防止反复抽同一批节点。
- **disclosure:** 不设限,所有节点均可入候选。

> 注: "未被注入对话" 的判定需引入一个轻量追踪 — 近 K 轮被 `MessagePatcher` 注入/出现在 recall 中的节点 uri。新增 `recently_injected_uris` (LRU 窗口,内存或一张薄表均可)。

### 2.3 摘要模型调用

复用 `callSummaryModel` (现有 `MEMORY_SUMMARY_URL`/key/model)。新 prompt (`buildTopicPrompt`),要求模型返回:

```json
{
  "topics": [
    { "title": "...", "lead_in": "...", "source_uris": ["...","..."] }
  ]
}
```

约束:

- 模型只能基于传入的候选节点产出话题;不允许其引用源节点之外的内容。
- 移除已有未用话题的标题(传入作"避免重复"提示 — 见取舍说明 decision: 生成端**不**注入已有话题标题,仅靠节点 LRU 排除去重;若实测雷同,再补"注入已有话题"双保险)。-> 本 plan 采用 **仅节点 LRU 排除**,不注入已有话题标题 (用户决策)。
- 单次产出的话题数 = `min(模型建议数, slotCap - 当前未用数)`,只补到上限。

### 2.4 落库

每条插入 `topics` (status=unused, generated_at=now),并把用到的源 uri 加入 LRU 排除集合。

## 3. 消耗 (代理按信号补)

### 3.1 信号

**唯一触发信号:** 用户明说想换话题。

### 3.2 检测 (双重疑似门 + 轻量模型判)

1. **疑似门 (零模型成本):**
   - 关键词/正则表 (`memoryTopicSwitchKeywords`,中英文)."聊点什么""换话题""有什么聊""无聊了"等。
   - 对话冷度信号: 近 K 轮的情绪/情绪/响应趋冷启发式 (新增 `conversationCold` 指标)。
   - 二者 OR 命中即视为"疑似"。
   - **不**以输入长度作疑似依据 (用户明确)。
2. **疑似才跑轻量模型判:** 调 `callSummaryModel` (或同趟摘要) 返回布尔 `want_topic_switch`。
3. 判中且 `未用池非空` → 取一条注入。

频率/成本: 非疑似请求零模型成本;疑似才花一次小调用。

### 3.3 选取

最老生成优先 (FIFO): 未用池按 `generated_at` 升序取第一个。

### 3.4 注入 (动态尾只读文本)

`MessagePatcher` 动态尾追加 (不入稳定前缀,不破缓存):

```
[可选话题供参考,不强制采用]
话题: <title>
引子: <lead_in>
```

**供参考不指令** — 上游 AI 看见但可自行决定是否承接。

### 3.5 消耗时刻

注入即标已用 (`status=used`, `used_at=now`),把该话题移出未用池。**不**做事后采用检测,接受"注入了上游 AI 却没用"的浪费(代价可接受)。

### 3.6 已用处理

已用话题保留 `memoryTopicUsedRetentionDays = 30` 天后软删进 recycle bin (复用现有回收机制)。保留期内可查历史。

## 4. 配置项 (`Config.kt`,皆可热改/默认)

| key | 默认 | 说明 |
|---|---|---|
| `memoryTopicEnabled` | `1` | 功能总开关 |
| `memoryTopicUnusedSlotCap` | `5` | 未用槽位上限 |
| `memoryTopicCandidatePool` | `20` | 候选节点数 (高/低各半) |
| `memoryTopicLruWindow` | `20` | 节点 LRU 排除窗口 |
| `memoryTopicUsedRetentionDays` | `30` | 已用保留期后软删 |
| `memoryTopicSwitchKeywords` | (词表) | 疑似门关键词/正则 |
| `memoryTopicDailyLimit` | (与 dream 对齐) | 每日话题生成上限 |

## 5. UI / API

- **Companion pane** (`companion.js` / `ui.html`): 新增话题区 — 未用池预览 + 已用历史(+ 倒计时软删) + recycle bin 复用。
- **API** (`Main.kt`,config-auth): `GET /api/companion/topics`(列池,含 status/generated_at),"Dream now" 已有;手动触发可加 `POST /api/companion/topics/generate` (dry-run 可选)。
- **只读**: 上游 AI 无相关接口 (它不操作)。

## 6. 落点清单

| 文件 | 改动 |
|---|---|
| `DatabaseService.kt` | `topics` 表 schema + 迁移 + CRUD; `recently_injected_uris` 追踪 (薄表或内存) |
| `MemoryService.kt` | `startConsolidationJob` 跑完梦后触发话题生成;新 collector (高/低各半 + LRU 排除);`buildTopicPrompt`;解析+落库;`conversationCold` 启发式;注入选取 FIFO;`MessagePatcher` 调用接话题注入 |
| `MessagePatcher.kt` | 动态尾追加话题只读文本 (注入即将被标已用的那一条) |
| `Main.kt` | `/api/companion/topics*` 路由 |
| `Config.kt` / `ConfigApi.kt` | 第 4 节配置项默认 + 热改 |
| `static/js/companion.js`, `ui.html`, `static/ui.css` | 话题区 UI |
| 测试 `MemoryManagementTest.kt` / `CompanionTest.kt` | 生成/选取/消耗/软删 单测 |

## 7. 取舍记录 (grill 中已决策)

1. **形态:** 独立实体表 (非节点 kind/非一次性提示)。
2. **用途:** AI 聊天提示材 (非用户点选)。
3. **消耗触发:** 仅"用户明说想换"。
4. **检测:** 词表+对话冷度双门疑似 → 轻量模型判;不用长度。
5. **生成触发:** 跑完梦后顺便 (不独立 schedule、不塞进梦 prompt 输出)。
6. **槽位语义:** 只限未用仓 (已用不占槽位)。
7. **已用处理:** 30 天后软删进 recycle bin (非全量保留、非消耗即删)。
8. **抽取范围:** 高强度 + 近生成未注入 两拌各半。
9. **去重:** 仅节点 LRU 排除 (不注入已有话题标题)。
10. **选取:** 最老生成优先 (FIFO)。
11. **注入:** 供参考不指令。
12. **消耗时刻:** 注入即标已用 (不事后检测采用)。
13. **粒度:** 单题 + 引子。
14. **disclosure:** 不设限。

## 8. 风险 / 待验证

- **同源去重能力:** 仅 LRU 排除可能仍产同方向话题。v1 落地后若雷同明显,补"注入已有未用话题标题进 prompt"作双保险 (已在 scrape 预留 prompt 形态)。
- **对话冷度启发式尚无现成指标:** 需新增,接近 `emotion_*`/响应长度/连续应答相似度的合成。先上简单版,迭代。
- **"未被注入对话"追踪** 是新增的跨轮状态,需确认生命周期与重启恢复。
- 成本: 生成搭便车不增定时器;消耗疑似才跑轻量调用 — 但疑似门太宽会放大调用量,词表 + 冷度需调参。