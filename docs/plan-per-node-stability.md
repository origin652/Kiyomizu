# Per-node memory stability(FSRS-inspired,自动调衰减速度)

## Context

Kiyomizu 的图记忆用**一个全局** `memoryDecayTauHours=360`(再乘 salience)套到所有节点上。identity(常被想起)和 working_memory(一次性)共用同一条衰减曲线,只能靠手调 scoreNode 权重补偿——这是用户"调参很痛苦"的根源。

研究上(Tabibian PNAS 2019 MEMORIZE;FSRS 系列;LECTOR 2025),现代自适应记忆系统的**衰减速度应按节点个体学**:经常被成功召回的记忆,稳定性上升、半衰期变长;长期不被召回的,衰减回到基线。用户决定做这一档(A 档)。

**关键事实(探索已确认,不重新发现):**
- 当前**没有** per-node 半衰期/回顾历史列。`memory_nodes` 只有 `strength`、`last_accessed_at`、`access_count`。
- 衰减是"读时惰性" `lazyStrength = strength * exp(-Δt/τ)`,周期性由 `applyLazyStrengthDecay` 落库。**用落库后的衰减曲线反推 τ 是自指、无效的**——所以不能"拟合",只能"用召回事件驱动 stability 增长"(FSRS 式)。
- 成功召回的加分钩子是 `DatabaseService.updateMemoryNodeAccess`(`DatabaseService.kt:2970`)——所有 boost 路径都过它,是 stability 增长的唯一注入点。
- 真正的 HLR/MEMORIZE 需要"打分(成败)"信号,系统目前没有;故本轮**不做拟合,做 stability 增长+用进废退**,信号来自现有的召回 boost 事件。打分信号是更往后的 B 档。

## 已确认的决策(用户已选,不重访)

1. **模型召回也纳入加分+stability 增长**。`tryModelRecall` 成功注入的 ordinary/dream/self 节点**也**走 `updateMemoryNodeAccess`(加分 + access_count + stability bump)。这**改变**了 #2"模型召回不加分"的决定——明确承认行为变化:模型召回现在会给注入节点加分。**仅加分/stability/timing,不引入 fusion boost、不传任何模型 hint 给本地召回。**
2. **stability 用进废退**。每个 decay 周期,本周期未被访问的 active 节点,`stability` 按 EMA 向 `memoryStabilityMin`(1.0)回落;常被召回的维持高,被遗忘的回到基线、最终被阈值归档。
3. **新调参 env-only**(同 #3)。env + AtomicReference + 访问器,默认值 = 当前行为(stability=1.0 → τ 不变)。**不进** Snapshot/ConfigApi/UI,持久化面零变化。

## Schema 变更(DatabaseService.kt)

- **新列** `memory_nodes.stability REAL NOT NULL DEFAULT 1.0`。在 `ensureMemoryNodesSchema`(`:672-685`,逐字复制 `status` 的 `PRAGMA table_info` + `ALTER TABLE ADD COLUMN` 模式)里加 `if ("stability" !in columns) { ... ADD COLUMN stability REAL NOT NULL DEFAULT 1.0 }`。存量行自动得 1.0 → 首轮行为与今天逐位相同。
- **`MemoryNodeRecord`**(`:1115-1143`)加 `val stability: Double`。
- **`readMemoryNode`**(`:1409-1438`)加 `stability = rs.getDouble("stability")`。
- **`insertMemoryNode`**(`:1574-1595`)INSERT 列加 `stability`,值取 `draft.stability`(NodeDraft 加 `stability: Double = 1.0` 默认,保持现有构造点不破)。
- **`updateMemoryNode`**(`:1626-1646`)UPDATE SET 加 `stability = ?`(全量更新路径,merge/promotion 保留 stability)。

## 新 env-only 调参(Config.kt,同 #3 模式)

`private val xRef = AtomicReference(System.getenv("X")?.toDoubleOrNull() ?: DEFAULT)` 在 `:140` 后;访问器 `var x: Double get()=xRef.get() set(value){xRef.set(value)}` 在 `:324` 后。**不碰** Snapshot(`:518`)、toJson(`:581`)、snapshot()(`:647`)、applySnapshot(`:711`)、fromJson(`:786`)、ConfigApi。

| 字段 | env | 默认 | 进哪个公式 |
|---|---|---|---|
| `memoryStabilityGrowthK` | `MEMORY_STABILITY_GROWTH_K` | `0.6` | stability 增长:`stability *= (1 + k * boostFactor)` |
| `memoryStabilityMin` | `MEMORY_STABILITY_MIN` | `1.0` | stability 下限 clamp(用进废退目标) |
| `memoryStabilityMax` | `MEMORY_STABILITY_MAX` | `8.0` | stability 上限,防近不朽;max τ = base*(1+salienceK*salience)*8 |
| `memoryStabilityRegressRate` | `MEMORY_STABILITY_REGRESS_RATE` | `0.05` | 每周期未访问:`stability *= (1 - rate)`,clamp 到 min |

**boostFactor 设计**:用 `strengthDelta / Config.memoryRecoveryAmount` 归一化,使"按路径系数"自然体现——normalRecall(0.35)→factor 0.35、deepRecall(0.5)→0.5、self(0.25)→0.25、ingestion merge(0.5+priority)更大。单次 normalRecall:`stability *= (1 + 0.6*0.35) ≈ 1.21`;深召回 `≈ 1.30`。多次召回累积,clamp 到 8。默认 k=0.6 给"温和但可累积"的曲线;用户后续可在 env 微调。**防御**:`recoveryAmount <= 0` 时 boostFactor 取 1.0,并 `coerceIn(0.0, 4.0)` 防极端。

## MemoryService.kt 变更

### `effectiveTauHours`(`:670-674`)——stability 乘在 salience 之后

```kotlin
private fun effectiveTauHours(memory): Double {
    val base = Config.memoryDecayTauHours.coerceAtLeast(1.0)
    val salience = emotionalSalience(memory.emotionValence, memory.emotionArousal)
    val stability = memory.stability.coerceIn(Config.memoryStabilityMin, Config.memoryStabilityMax)
    return base * (1.0 + Config.memorySalienceK * salience) * stability
}
```
乘在 salience 之后:identity 高 stability + 高 salience 两者叠加;working_memory stability≈1 走基线。`lazyStrength`(`:676`)与 `applyLazyStrengthDecay`(`:690`)无需改,它们读 `effectiveTauHours`,自动受益。

### 新 `regressStability(now)`——用进废退,插在 `applyLazyStrengthDecay` 前

```kotlin
fun regressStability(now: Long = Instant.now().epochSecond) {
    val nodes = DatabaseService.getActiveGraphMemoryNodesForDecay()
    val rate = Config.memoryStabilityRegressRate
    val min = Config.memoryStabilityMin
    val cycleStart = now - (Config.memoryDecayIntervalHours * 3600L)
    for (node in nodes) {
        if (node.lastAccessedAt >= cycleStart) continue  // 本周期被访问过,不回落
        val regressed = (node.stability * (1.0 - rate)).coerceAtLeast(min)
        if (regressed < node.stability) DatabaseService.updateMemoryNodeStability(node.id, regressed)
    }
}
```
**排序**:`startDecayJob`(`:284-307`)try 块内,`applyLazyStrengthDecay()` **之前**插入 `regressStability()`。理由:本周期先回落 stability,再用(可能变小后的)τ 落库衰减 strength——让"被遗忘"在本周期就加快衰减。`DatabaseService.getActiveGraphMemoryNodesForDecay()`(#1 已加)复用,无需新查询。"本周期起点"= `now - memoryDecayIntervalHours*3600`,`last_accessed_at >= cycleStart` 即视为本周期访问过。

**新 `DatabaseService.updateMemoryNodeStability(nodeId, stability)`**:`UPDATE memory_nodes SET stability = ? WHERE id = ?`,插在 `updateMemoryNodeStrength`(`:2995`)后。不动 strength/last_accessed_at(回落只动 stability)。

### `updateMemoryNodeAccess` 加 stability bump(DatabaseService.kt `:2970`)——唯一增长注入点

纯 SQL 原子表达,避免读-改-写:

```sql
UPDATE memory_nodes
SET strength = MIN(strength + ?, ?),
    last_accessed_at = ?,
    access_count = access_count + 1,
    stability = MIN(stability * (1.0 + ? * ?), ?)
WHERE id = ?
```
参数:`strengthDelta, maxStrength, now, growthK, boostFactor, maxStability, nodeId`。给 `updateMemoryNodeAccess` 加参数 `boostFactor: Double`(由调用点算好传),内部取 `Config.memoryStabilityGrowthK` 与 `Config.memoryStabilityMax`。调用点全在 MemoryService.kt(`:2945/3082/3523/3731/4221`)——逐个补 `boostFactor` 实参:
- `:2945` ingestion merge:`0.5 + payload.priority`
- `:3082` buffered ingestion merge:同上或 `0.5`
- `:3523` normalRecall:`0.35`
- `:3731` deepRecall:`0.5`
- `:4221` selfMemories:`0.25`

### `tryModelRecall` 成功路径加分(决策 #1,行为变更重点)

`tryModelRecall`(`:4091-4161`)算出 `recalled/selfMemories/dreamTraces`(`:4135-4138`)后、`insertModelRecallTrace`(`:4148`)之前,对注入节点调 `updateMemoryNodeAccess`:
- `recalled`(ordinary):`memoryRecoveryAmount * 0.35`,boostFactor 0.35(与 normalRecall 对齐)。
- `dreamTraces`:`memoryRecoveryAmount * 0.35`,boostFactor 0.35。
- `selfMemories`:**不在此处加**——`buildCompanionMemoryContext:4221` 已对 selfMemories 加 `*0.25`,模型成功路径 self 会流到那里统一加分,这里再加会双重加分。本分支只 boost `recalled + dreamTraces`。
- 本地兜底路径(`normalRecall`)行为不变(已在 `:3523` 加分)。

**行为变更声明**:此前模型召回成功的普通/dream 注入节点**不加分**(#2 决定);本计划后**会加分 + access_count + stability 增长**。这是 stability 学衰减速度所需的最小信号,且只动 strength/stability/timing,不碰 fusion/boost-to-score、不给本地召回传模型 hint。#2 的 trace 观测不变。

## 数据库函数清单(DatabaseService.kt)

- 改 `ensureMemoryNodesSchema`(`:672`):加 `stability` 列迁移。
- 改 `MemoryNodeRecord`(`:1115`)+ `readMemoryNode`(`:1409`)+ `insertMemoryNode`(`:1574`)+ `updateMemoryNode`(`:1626`):`stability` 字段读写。NodeDraft 加 `stability: Double = 1.0`。
- 改 `updateMemoryNodeAccess`(`:2970`):UPDATE 加 stability bump(参数加 `boostFactor`)。
- 新 `updateMemoryNodeStability(nodeId, stability)`:插 `:2995` 后。
- 复用 `getActiveGraphMemoryNodesForDecay()`(#1 已有)给 `regressStability`。

## 测试(CompanionTest.kt)

- `resetConfig()`(`:48-57` 区)扩 4 个新字段重置为默认(0.6/1.0/8.0/0.05),防测试间泄漏。
- **新 `stabilityGrowsOnRecallAndSlowsDecay`**:插两个相同 strength=1.0、相同 emotion 的节点;对 A 调 `updateMemoryNodeAccess` k 次(boostFactor 0.35),B 不动;断言 `A.stability > 1.0`、`effectiveTauHours(A) > effectiveTauHours(B)`、且未来某时刻 `lazyStrength(A, future) > lazyStrength(B, future)`。`effectiveTauHours` 是 private——通过 `lazyStrength` 间接断言,或临时把 `effectiveTauHours` 提为 internal 供测试。
- **新 `regressStabilityPullsUntouchedTowardMin`**:插节点,`updateMemoryNodeStability` 手动设 4.0,`last_accessed_at` 回退到周期前;调 `regressStability`;断言 stability 下降但仍 `>= memoryStabilityMin`;另插一个 `last_accessed_at` 近(now)的节点,断言其 stability 不变。
- **新 `defaultStabilityReproducesPriorLazyStrength`**:stability=1.0(default)时 `lazyStrength` 与改造前逐位相等——插节点(emotion 中性使 salience 项可算),断言 `lazyStrength == strength*exp(-elapsed/(base*(1+salienceK*salience)*1.0))`,守零行为变化。
- **模型成功加分**(决策 #1):`callRecallModel` 难以在单测里 mock 成"成功"。**降级为冒烟项**:在计划里注明"模型成功路径加分由 `updateMemoryNodeAccess` 的 stability bump 单测覆盖(已含);端到端模型成功加分留作手动冒烟",不强行 hack 模型层。
- **审计现有测试**:`emotionalSalienceAndLazyDecayUseGraphNodes`、`applyLazyStrengthDecayPersistsDecayedStrength`、`graphMemoryCrudAndSearchWork`、`localRecallFallbackRecordsInjectedUris`、`scoreNodeWeightDefaultsReproducePriorHardcodedValues`——default stability=1.0 下应全部仍通过;`updateMemoryNodeAccess` 加 stability 列但默认对 strength/last_accessed/access_count 不变。逐个确认无 exact-value 断言因新列/新参数破裂。`localRecallFallbackRecordsInjectedUris` 断言 trace count,模型失败路径不加分,不受影响。

## 验证

- 本机 JDK 17:`C:\tools\openjdk17\jdk-17.0.13+11`;`export JAVA_HOME=... && export PATH=$JAVA_HOME/bin:$PATH && ./gradlew test --tests "hifumi.kiyomizu.CompanionTest"`(proxy:`-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897`)。全绿(原有 26 + 新增 ~3)。
- **迁移冒烟**:在已有 DB 上启动,确认 `ensureMemoryNodesSchema` 给 `memory_nodes` 加了 `stability` 列、存量行 = 1.0、首次 decay 周期行为与升级前一致。
- **行为对比**:default env(stability=1.0、k=0.6、max=8、regress=0.05)下,`effectiveTauHours`/`lazyStrength` 与改造前逐位相等(由 `defaultStabilityReproducesPriorLazyStrength` 守)。
- 不动 ConfigApi → `ConfigApiTest` 不受影响。

## 风险 / 行为变更清单

1. **模型召回现在给注入节点加分**(决策 #1)。此前 #2 明确"不加分"。本计划恢复加分,但**仅** strength/access/stability,**不**改 fusion、不传模型 hint。需用户知情:这等于部分回退 #2 的"不加分"约束(动机是 stability 需要成功信号)。
2. **stability 上限 8.0** → 最慢衰减 τ 放大 8 倍(360h→2880h≈120 天半衰期,再叠 salience)。若觉得太"长寿",调小 `MEMORY_STABILITY_MAX`。clamp 保证不会无限。
3. **用进废退 rate 0.05/周期**:decay 周期默认 `memoryDecayIntervalHours`(小时级),每周期回落 5%。长期不被想起的高 stability 节点会渐进回基线——符合预期,但若周期很密回落偏快,调 `MEMORY_STABILITY_REGRESS_RATE`。
4. **`boostFactor` 归一化依赖 `memoryRecoveryAmount`**:`recoveryAmount <= 0` 时 factor=1.0,并 `coerceIn(0.0, 4.0)` 防极端/除零。
5. **scoreNode 权重(17 个)本轮不动**——它们仍 env-only 手调。stability 减掉的是"调 τ 适配不同 kind"的痛苦;scoreNode 排序权重要等 B 档 reward 信号。

## 关键文件

- `src/main/kotlin/hifumi/kiyomizu/Config.kt` — 4 个 env-only 字段 + 访问器(同 #3)
- `src/main/kotlin/hifumi/kiyomizu/MemoryService.kt` — `effectiveTauHours` 乘 stability;新 `regressStability` + `startDecayJob` 排序;`tryModelRecall` 成功路径给 ordinary/dream 加分;各 `updateMemoryNodeAccess` 调用点补 `boostFactor`
- `src/main/kotlin/hifumi/kiyomizu/DatabaseService.kt` — `stability` 列迁移 + Record/read/insert/update;`updateMemoryNodeAccess` 加 stability bump;新 `updateMemoryNodeStability`
- `src/test/kotlin/hifumi/kiyomizu/CompanionTest.kt` — `resetConfig()` 扩 4 字段;新增 ~3 测试;审计现有衰减/召回测试
