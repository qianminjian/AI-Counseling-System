# O 专题：过度设计收敛改造 —— 设计方案与实施 SPEC

> 文档状态：**进行中（Doing）** | 创建：2026-08-05
> 关联：DOC-054 审计问题 O1-O5（过度设计含质疑）；关联待议项 OD-001 / OD-004 / OD-014；VCL-001（已完成）
> 目录约定：`design/doing/` 存放进行中设计文档；评审定稿后归档至 `design/` 根目录并登记 TASK-TRACKER

---

## 0. 执行摘要（结论先行）

深度研究后，O1-O5 的真实处置与初判有 3 处修正（O1 维持、O3b 新增死分支发现、O5-4 维持）。最终改造范围为 **4 项实施 + 5 项登记维持**：

| 编号 | 判定 | 处置 | 强度 | 关联 |
|---|---|---|---|---|
| S1（O3a） | **真冗余**：会话结束两次 LLM 提炼（画像+记忆）输入完全相同 | 合并为一次提炼调用 | 大 | PROF-003/AI-008 |
| S2（O3b） | **死分支**：`ProfileMergeGate.applyDecay/isExpired` 零调用方 | 删除衰减机制，保留 merge() 核心 | 小 | OD-004 |
| S3（O4） | **真过度抽象**：`src/api/` 单文件目录 + 两套导入路径并存 | 并入 `api.ts` 单文件，删除目录 | 中 | A4 |
| S4（O5-1/2） | **双源漂移**：声纹阈值 0.55/0.70、温度参数主备各一套 | 占位符派生，收敛单一事实源 | 小 | OD-001/OD-014 |
| O1 情绪层 | 非冗余：emotionBaseline 已是聚合派生（VCL-001 已完成） | **维持现状**，登记论证 | — | VCL-001 |
| O2 记忆层 | 非冗余：主题/风险引擎均有消费方且在同一链路内 | **维持现状**，登记论证 | — | MEM-102/103 |
| O5-3 变量命名双轨 | 形式问题 | **登记约定**，存量不改 | — | — |
| O5-4 guide-scripts 话术 | 配置化是运营可调功能，非冗余 | **维持现状**，登记论证 | — | — |

**总体判断**："三层架构可简化"成立的部分收敛为 **O3 画像层的双 LLM 提炼合并**；O1/O2 是业务域分层而非过度设计；O4/O5 是真问题但范围小于初判。

---

## 1. 背景与问题定义

DOC-054 深度审计将"过度设计（含质疑）"登记为 O1-O5：

- **O1-3**：三层情绪/记忆/画像架构可简化（质疑）
- **O4**：前端抽象层过度
- **O5**：配置项冗余

初判结论（2026-08-05 会话）："O1-3 有真实重叠但不宜整体删层，建议收敛接线；O4 是真过度抽象，建议合并；O5 冗余形态是'双源/双轨'而非'无人读取'，建议收敛单一事实源"。

本设计文档基于**全链路代码级证据**（§2），对每项做最终裁决并输出可执行的方案与 SPEC（§5/§6）。

---

## 2. 深度研究：证据库

### 2.1 会话结束链路全貌（S1/S2 的改造靶点）

`MessageSummaryService.generateSummaryAsync`（[MessageSummaryService.java](backend/counseling-service/src/main/java/com/mindsafe/service/conversation/MessageSummaryService.java) L58-106）是唯一异步编排点，会话结束后按序执行：

```
1. 查该会话全部 MessageSummary（逐轮摘要）
2. 拼接对话文本（R-01 字段级加密→解密）
3. ConversationQualityService.evaluateSessionAsync（PEVAL-001，抽样评估）
4. LLM 生成会话摘要（generateSessionSummary）             ← LLM 调用 ①
5. ProfileExtractorService.extractAndMerge                ← LLM 调用 ②（画像提炼）
6. LongTermMemoryService.extractAndStoreKeyEvents         ← LLM 调用 ③（记忆提炼）
```

另有 `ConversationServiceImpl`（L617）异步调 `StudentProfileService.updateProfile` 做 **SQL 规则聚合**（P0，无 LLM）。

**关键事实**：步骤 5 与步骤 6 的 LLM 输入完全相同（`conversationText` + `summary`），分别走 `AiChatServiceImpl.extractProfilePatch`（L254，输出画像增量 JSON）与 `extractKeyEvents`（L375，输出关键事件 JSON）。即**同一份对话文本被两个 prompt 各提炼一次**——这是 S1 合并的直接证据。

### 2.2 O1 情绪层——证据与裁决

情绪数据形态（深度研究后修正初判）：

| 载体 | 内容 | 落库 | 性质 |
|---|---|---|---|
| `EmotionDiary` | 学生手动日记（emotionLabel/intensity/note） | 是 | 独立产品功能，与 AI 链路无关 |
| `MessageSummary.emotionLabel` | 每轮 SER 语音情绪标签 | 是 | **落库事实源** |
| `SessionState.emotionHistory` | 会话内情绪轨迹 | 否（内存态） | 会话内临时态，进 Prompt"情绪旅程" |
| `StudentProfile.emotionBaseline.voice` | 近 20 次会话语音情绪聚合 | 是 | **聚合派生**（VCL-001，provenance=voice_ser） |

**证据**：`StudentProfileService.updateProfile`（[StudentProfileService.java](backend/counseling-service/src/main/java/com/mindsafe/service/profile/StudentProfileService.java) L61-95）从 `counseling_sessions` 聚合近 20 次会话构建 `emotionBaseline`，VCL-001 已登记完成（TASK-TRACKER L666）。`ConversationServiceImpl`（L369-372）以置信门控 >0.6 将 SER 情绪映射为轮级 `currentEmotion`。

**裁决**：**不改造，登记维持**。初判"同一事件多路径落库无对账"在 VCL-001 落地后已不存在——emotionBaseline 是读库聚合派生而非重复写。4 份副本各自职责明确（独立功能/事实源/会话临时态/聚合派生），符合 KISS 分层，硬合并反增耦合。

### 2.3 O2 记忆层——证据与裁决

组件与消费关系：

| 组件 | 行数 | 产出 | 消费方（代码证据） |
|---|---|---|---|
| `MessageSummaryService` 滚动摘要 | — | 会话内摘要 | 会话压缩（每 4 轮） |
| `LongTermMemoryService` | 468 | 关键事件 + top5 召回 + 50 条淘汰 | Prompt 注入（对话开始时） |
| `ThemeEvolutionEngine` | 194 | 主题列表 | `LongTermMemoryService` L58/L77 注入，`extractAndStoreKeyEvents` 内 `evolveThemes` 调用 |
| `MemoryRiskCorrelator` | 198 | 风险关注信号 | `LongTermMemoryService` L56/L75 注入，`correlateMemoryRisk` 调用 |

**证据**：`extractAndStoreKeyEvents`（[LongTermMemoryService.java](backend/counseling-service/src/main/java/com/mindsafe/service/memory/LongTermMemoryService.java) L89-146）内依次执行：落库 → `evictOldMemories`（50 条淘汰）→ `backfillService.backfill`（记忆回注）→ `correlateMemoryRisk` → `evolveThemes`。四个引擎在同一链路被真实消费。

**裁决**：**不改造，登记维持**。初判建议"主题提取与风险关联合并为一次计算"经评估放弃：两者职责不同（主题演化 vs 风险信号），合并省一次文本扫描属微优化，收益小于对两个已有测试引擎的改动风险（KISS > 微优化）。

### 2.4 O3 画像层——证据与裁决（S1 + S2）

**三路更新路径并存**（4 次异步写）：

| 路径 | 服务 | 方式 | 产出 | provenance |
|---|---|---|---|---|
| P0 规则聚合 | `StudentProfileService.updateProfile` | SQL 聚合近 20 次会话 | 基础画像 + emotionBaseline + riskTrajectory | rule_agg |
| LLM 提炼 | `ProfileExtractorService.extractAndMerge`（L62） | LLM 提炼沟通偏好/韧性/社交图谱/个性 | 5 维增量合并 | llm_extract |
| 记忆提炼 | `LongTermMemoryService.extractAndStoreKeyEvents` | LLM 提取关键事件落库 | 跨会话记忆 | — |
| 记忆回注 | `MemoryProfileBackfillService.backfill`（L58） | milestone/person 事件回注画像 | growthTrack/socialGraph 补充 | memory |

**S1 证据（双 LLM 提炼冗余）**：`AiChatServiceImpl.extractProfilePatch`（L254-262）与 `extractKeyEvents`（L375-383）prompt 输入段完全相同（`"会话摘要文本：\n" + conversationText + "\n\n结构化摘要：\n" + sessionSummary`），仅输出 schema 不同。合并为一次调用即省 **1 次 LLM 往返/会话**（约 30-60s 异步链路时延与 token 成本减半级）。

**S2 证据（合并门控死分支）**：`ProfileMergeGate`（[ProfileMergeGate.java](backend/counseling-service/src/main/java/com/mindsafe/service/profile/ProfileMergeGate.java) L88-104）的 `applyDecay`/`isExpired`（90 天衰减、60 天半衰期、180 天封顶）**全代码库无调用方**（grep 仅命中其他类同名方法）。属 YAGNI 违反：写了但从未接线的能力。`merge()` 核心（KEEP_EXISTING <0.3 / REPLACE / WEIGHTED_MERGE 冲突 >0.4 / EMA alpha≤0.5）被 `ProfileExtractorService`（L145-146）真实消费，保留。

**OD-004 关联**：冲突阈值 0.4、EMA 权重、置信递增 0.05 均无实证（小样本拍脑袋）。本专题裁决：**参数不改代码**（无数据可校准），但合并门控结构保留——它至少防止单次会话翻转画像（安全价值）。参数校准待真实数据回流（登记 OD-004 延续）。

### 2.5 O4 前端抽象层——证据与裁决（S3）

**证据**：
- `src/api/` 目录**仅 1 个文件** `toolboxApi.ts`（60 行），与根 `src/api.ts`（327 行）并存 → "目录 + 根文件"两套导入路径（`../api` vs `./api/toolboxApi`）并存，属为抽象而抽象的目录化。
- hooks 共 11 个 2177 行，语音域 8 个（useVoiceCallMode 232 / useVoiceprint 383 / useTtsPlayer 452 / useWakeWord 493 / useAudioRecorder 129 / useSilenceNudge 125 / useVoicePersona 155 / useWakeEnabled 33）。语音域本身复杂（唤醒/声纹/容灾三重矩阵），hook 拆分是职责分离，**不裁**。
- `useWakeEnabled`（A4 已抽取，33 行，含单测 `useWakeEnabled.test.ts`）为收敛产物，**保留**；与 `useVoiceCallMode` 边界重叠复查列为低优先，不强制合并。

**裁决**：S3 合并 API 层（删单文件目录）；hooks 维持。

### 2.6 O5 配置层——证据与裁决（S4）

**机器差集结果**：`mindsafe.*` 配置全部被 Java 读取，**无死配置**（springdoc 项由框架消费）。冗余真实形态为双源/双轨：

| 项 | 双源证据 | 裁决 |
|---|---|---|
| 声纹阈值双源 | `mindsafe.voiceprint.verify-threshold`=0.55（application.yml L154，remote 模式，注释自认"实测 0.70 过不了"）vs `mindsafe.system-config.voiceprint.verify-threshold`=0.70（L160，前端 local 模式）；前端 `config/remote.ts` getConfigValue('voiceprint.verifyThreshold', 0.55) 远程下发优先 | **S4-1**：system-config 项改为 `${mindsafe.voiceprint.verify-threshold}` 占位符派生 |
| 温度参数双路径 | `spring.ai.openai.chat.options.temperature/max-tokens`=0.7/2048（L54-55）vs `mindsafe.ai.fallback.temperature/max-tokens`=0.7/2048（L121-122），主/备供应商各一套，调主忘调备即漂移 | **S4-2**：fallback 项改为占位符派生 |
| 变量命名双轨 | `MINDSAFE_*` 松绑定前缀 vs 裸变量名（VOICE_SERVICE_URL 等，compose 内网注入，D 清理已删 .env.example 死项） | 登记约定：新配置一律 `MINDSAFE_` 前缀，存量不改 |
| guide-scripts 话术 | 引导话术硬编码 yaml（L171-178，verify/enroll 两组，前端远程拉取） | **维持**：改文案免发前端包是运营能力，属功能非冗余；补注释声明 |

**裁决**：S4-1/S4-2 占位符派生（各 1 行，消灭双源漂移）；O5-3 登记约定；O5-4 维持 + 注释。

---

## 3. 目标与成功标准（EARS）

| # | 目标 | 成功标准（可验证） |
|---|---|---|
| G1 | 消灭重复 LLM 提炼 | 会话结束链路 LLM 提炼调用次数由 2 → 1；画像增量与关键事件产出完整 |
| G2 | 消除未接线能力 | `applyDecay`/`isExpired` 从 ProfileMergeGate 删除，构建通过 |
| G3 | 收敛前端 API 导入 | `src/api/` 目录不存在；全仓无 `api/toolboxApi` 导入路径 |
| G4 | 配置单一事实源 | `voiceprint.verify-threshold` 与 `ai.fallback.*` 温度无第二手数值；改权威值一处生效 |
| G5 | 行为不变 | 全量回归通过（后端 711 / student-h5 661 / teacher-web 34 / parent-h5 23 / scripts 45）；无存储结构变更 |

---

## 4. 范围边界

**做：**
- S1：合并双 LLM 提炼（AiChatService 新方法 + 编排接线 + 删除旧方法）
- S2：删除 ProfileMergeGate 死分支（applyDecay/isExpired/相关常量）
- S3：合并前端 API 层（toolboxApi.ts 并入 api.ts，删目录）
- S4：配置双源占位符派生（2 处）
- 文档同步：design/46/47/50、TASK-TRACKER 登记、CHANGELOG

**不做：**
- O1 情绪层、O2 记忆层、O5-3/O5-4 改造（登记论证维持）
- `EmotionDiary` 与 AI 链路合并
- hooks 合并（含 useWakeEnabled 复查，仅登记）
- 删除 remote/local 声纹模式（OD-001 独立待议，S4-1 只收敛阈值来源）
- ProfileMergeGate 参数校准（OD-004 待数据，仅登记）
- 任何数据库 schema 变更与 API 契约变更

---

## 5. 方案设计

### 5.1 S1：合并双 LLM 提炼（O3a，主改点）

**目标设计**（区别于已实现）：

```
AiChatService
├── extractConversationInsights(String conversationText, String sessionSummary)  ← 新增
│   返回 JSON：{"profile_patch": {...}, "key_events": [...]}
├── generateSessionSummary(...)          （维持）
├── extractProfilePatch(...)             （删除，接线后）
└── extractKeyEvents(...)                （删除，接线后）
```

**Prompt 设计（目标设计）**：合并两 prompt 为一次请求，system 指令要求"同时输出画像增量（profile_patch schema 沿用现有 extractProfilePatch 契约）与关键事件（key_events schema 沿用现有 extractKeyEvents 契约），输出两个 JSON 节点"。

**数据流变更（目标设计）**：

```
MessageSummaryService.generateSummaryAsync（已实现 → 目标设计）
├── 1-4 不变（查摘要/拼文本/质量评估/生成会话摘要）
├── 5+6 合并：
│    insights = aiChatService.extractConversationInsights(conversationText, summary)
│    if insights.profile_patch 非空 → profileExtractorService.extractAndMerge(tenantId, userId, profilePatch)
│    if insights.key_events 非空 → longTermMemoryService.extractAndStoreKeyEvents(tenantId, userId, sessionId, keyEvents)
└── 容错：任一节点缺失/解析失败 → 该路静默跳过（对齐现有降级语义）
```

**改动清单**：

| 文件 | 改动 |
|---|---|
| `AiChatService` | 新增 `extractConversationInsights`；删除 `extractProfilePatch`/`extractKeyEvents` |
| `AiChatServiceImpl` | 实现新方法（合并 prompt）；删除两旧方法（先确认测试引用） |
| `ProfileExtractorService` | `extractAndMerge` 重载为接受已解析 `JsonNode profilePatch`（LLM 调用移出） |
| `LongTermMemoryService` | `extractAndStoreKeyEvents` 重载为接受已解析 `JsonNode keyEvents`（LLM 调用移出） |
| `MessageSummaryService` | 编排点单次调用 + 解析分发 |

**备选方案（否决）**：保留两方法 + LLM 层结果缓存——缓存生命周期与幂等复杂度高于直接合并，且仍保留双 prompt 维护成本。

### 5.2 S2：删除画像合并门控死分支（O3b）

**改动清单**：

| 文件 | 改动 |
|---|---|
| `ProfileMergeGate` | 删除 `applyDecay`、`isExpired`、`MAX_DECAY_DAYS`、`DECAY_HALF_LIFE_DAYS` 常量与类注释对应段落；保留 `merge()`、`MergeDecision`、`CONFLICT_THRESHOLD` |

无调用方需迁移（已全库确认）。

### 5.3 S3：前端 API 层收敛（O4）

**目标设计**：

```
src/
├── api.ts              （327 + 60 = 约 387 行：toolbox 段并入，按注释分区）
└── api/                （删除：toolboxApi.ts 及目录）
```

**步骤**：
1. 提取 `toolboxApi.ts` 导出符号与实现，并入 `api.ts` 同风格分区
2. 全仓替换导入：`./api/toolboxApi` / `../api/toolboxApi` → `../api` / `./api`
3. 删除 `src/api/toolboxApi.ts` 与空目录
4. `grep -r "api/toolboxApi"` 为零 + vitest 全量回归

**理由**：单文件目录无模块意义，且制造"从哪导入"的分裂（两套路径并存）。按域拆分（auth/session/toolbox）是合理抽象但当前 api.ts 仅 327 行，拆分是提前优化（YAGNI），待超 500 行再拆。

### 5.4 S4：配置双源收敛（O5-1/O5-2）

**目标设计**（application.yml，占位符派生）：

```yaml
mindsafe:
  voiceprint:
    verify-threshold: 0.55        # ← 单一权威（remote 模式实测值）
  system-config:
    voiceprint:
      verify-threshold: ${mindsafe.voiceprint.verify-threshold}   # 派生，消灭双源
  ai:
    fallback:
      temperature: ${spring.ai.openai.chat.options.temperature}   # 派生
      max-tokens: ${spring.ai.openai.chat.options.max-tokens}     # 派生
```

**验证点**：`application-prod.yml`/`application-dev.yml` 是否覆盖上述 key（覆盖则同步派生写法）；Spring 占位符解析在配置节顺序上无依赖（同 Environment）。

### 5.5 维持现状项登记

| 项 | 登记结论 |
|---|---|
| O1 情绪层 | 非冗余（VCL-001 聚合派生已落地），维持 |
| O2 记忆层 | 非冗余（引擎全部真实消费），维持；合并计算列为微优化不实施 |
| O5-3 变量命名 | 约定：新配置一律 `MINDSAFE_` 前缀（登记入本文件 + .env.example 注释），存量不改 |
| O5-4 guide-scripts | 配置化是运营可调能力（改文案免发版），维持；补 yaml 注释声明设计意图 |

---

## 6. 实施 SPEC（TDD，逐项验收）

### 6.1 S1 SPEC：合并双 LLM 提炼

**正常路径：**

- When 会话结束异步链路执行, the 系统 shall 以一次 `extractConversationInsights` LLM 调用同时产出画像增量与关键事件，且调用次数为 1。
- When 提炼结果包含 profile_patch, the 画像服务 shall 合并 5 维画像增量并盖 provenance=llm_extract 元数据。
- When 提炼结果包含 key_events, the 记忆服务 shall 落库关键事件、执行 50 条淘汰、记忆回注与主题/风险关联。

**异常路径：**

- If profile_patch 或 key_events 节点缺失或解析失败, then the 系统 shall 仅跳过对应一路，不阻断摘要落库与其他路径。
- If LLM 调用抛异常, then the 系统 shall 静默降级（对齐现有 catch 语义），画像与记忆两路均不写。

**测试用例（TDD Red→Green）：**

| 用例 | 断言 |
|---|---|
| `test_会话结束只调用一次提炼LLM` | mock AiChatService，断言 `extractConversationInsights` 恰 1 次，旧两方法 0 次 |
| `test_合并结果双节点分发` | mock 返回双节点 JSON，断言画像合并与关键事件落库各 1 次 |
| `test_profilePatch缺失跳过画像` | key_events 正常、profile_patch 为 null，断言记忆路执行、画像路 0 调用 |
| `test_LLM异常静默降级` | 抛异常，断言摘要已落库、无异常上抛 |
| `test_旧提炼方法已删除` | 编译期：无 `extractProfilePatch`/`extractKeyEvents` 引用（grep 兜底） |

### 6.2 S2 SPEC：删除 MergeGate 死分支

**正常路径：**

- When 画像合并被调用, the 画像合并门 shall 执行 merge() 的 KEEP/REPLACE/WEIGHTED_MERGE 三策略与 CONFLICT_THRESHOLD=0.4。
- The 画像合并门 shall 不再提供 applyDecay 与 isExpired 方法。

**异常路径：**

- If 任何代码引用 `applyDecay` 或 `isExpired`（ProfileMergeGate 域内）, then the 构建 shall 失败。

**测试用例：**

| 用例 | 断言 |
|---|---|
| `test_merge策略回归` | 现有 merge 单测全绿（KEEP <0.3 / REPLACE 首值 / WEIGHTED_MERGE 冲突 / EMA） |
| `test_衰减方法删除` | grep `ProfileMergeGate.*applyDecay\|ProfileMergeGate.*isExpired` 零命中 |
| `test_构建通过` | 后端模块编译通过（删除方法无残留引用） |

### 6.3 S3 SPEC：前端 API 合并

**正常路径：**

- When 前端任意模块调用工具盒 API, the 模块 shall 统一从 `src/api.ts` 导入。
- The 前端代码库 shall 不存在 `src/api/` 目录与 `api/toolboxApi` 导入路径。

**异常路径：**

- If 存在 `api/toolboxApi` 导入残留, then the 回归检查 shall 失败（grep 非零即红）。

**测试用例：**

| 用例 | 断言 |
|---|---|
| `test_导入路径归零` | `grep -r "api/toolboxApi" src/` 零命中 |
| `test_功能回归` | student-h5 vitest 全量通过（toolbox 相关用例沿用） |
| `test_类型检查` | `vue-tsc`/构建无类型错误 |

### 6.4 S4 SPEC：配置占位符派生

**正常路径：**

- The 系统 shall 仅以 `mindsafe.voiceprint.verify-threshold` 为声纹阈值单一事实源，`system-config` 项以占位符派生。
- The 系统 shall 以 `spring.ai.openai.chat.options.*` 为温度/最大 token 单一事实源，`ai.fallback.*` 以占位符派生。

**异常路径：**

- If 权威 key 缺失, then the 启动 shall 报配置解析错误（占位符未解析即 fail-fast），不得静默用默认值。

**测试用例：**

| 用例 | 断言 |
|---|---|
| `test_占位符派生值` | 加载 yml 断言 `system-config.voiceprint.verify-threshold == 0.55`、`ai.fallback.temperature == 0.7` |
| `test_单源修改生效` | 改权威值为 0.50，派生值同步 0.50（配置单测） |
| `test_前端下发不受影响` | remoteConfig.test.ts 全绿（前端仍读下发值，契约不变） |

### 6.5 测试矩阵总表

| 阶段 | 范围 | 命令 |
|---|---|---|
| 后端单测 | counseling 各模块 | `mvn test`（surefire 汇总 711 用例基线） |
| 前端单测 | student-h5 | `npm test`（661 用例基线） |
| 前端类型 | student-h5 | 构建/类型检查 |
| 脚本测试 | scripts | `tests/unit/scripts/` 4 个行为测试（45 用例基线） |
| 静态断言 | S1-S3 删除项 | grep 零命中断言（并入 S 系列测试） |

---

## 7. 实施顺序与里程碑

| 里程碑 | 内容 | 依赖 | 出口标准 |
|---|---|---|---|
| M1 | S1 合并双 LLM（Red：写"单次调用"测试→Green：新方法+编排→Refactor：删旧方法） | 无 | §6.1 用例全绿 + 后端回归 |
| M2 | S2 删死分支 | 无（可并行，顺序执行） | §6.2 用例全绿 |
| M3 | S3 前端 API 合并 | 无 | §6.3 用例全绿 + student-h5 回归 |
| M4 | S4 配置派生 | 无 | §6.4 用例全绿 |
| M5 | 全量回归 + 文档同步 + 登记 | M1-M4 | 1474 用例全绿；design/46/47/50 同步；TASK-TRACKER 登记 DOC-057；CHANGELOG 更新 |

**提交策略**：按 E5 提交粒度规范原子提交（S1 拆 domain/ai/service 若超 15 文件），全部 `check-commit.sh --last` 校验。

---

## 8. 风险与回滚

| 风险 | 等级 | 缓解 |
|---|---|---|
| S1 合并 prompt 后 LLM 输出格式漂移（双节点 JSON 解析失败） | 中 | 解析容错（任一节点缺失降级单路）；M1 内先新后删（旧方法保留至回归绿） |
| S1 双输出 prompt 质量下降（单次调用 vs 双次聚焦） | 中 | schema 契约与 prompt 指令完全沿用现有两 prompt 原文合并，仅合并请求载体 |
| S3 导入路径遗漏 | 低 | grep 兜底断言 + vitest 全量回归（引用断裂必红） |
| S4 占位符在 prod/dev 覆盖文件中行为不符 | 低 | M4 前确认 prod/dev 是否覆盖相关 key，覆盖则同写法派生 |
| S2 误删被反射/字符串引用 | 低 | 编译期保障 + 全库 grep（已确认零调用） |

**回滚**：所有改动为纯代码级重构（无 schema/契约变更），单提交回退即可恢复，无数据迁移。

---

## 9. 关联项与后续待议

| 关联 | 状态 | 本专题处置 |
|---|---|---|
| OD-001 声纹双模式 | 🟡 待议 | S4-1 仅收敛阈值来源；模式删减由 OD-001 独立议决 |
| OD-004 合并门控参数 | 🟡 待议 | S2 删死分支；参数校准待真实数据回流 |
| OD-014 声纹阈值双源 | 🟡 待议 | S4-1 占位符派生后随 OD-001 一并收口 |
| VCL-001 情绪回注 | ✅ 已完成 | O1 维持的依据 |
| A4 useWakeEnabled | ✅ 已完成 | hooks 维持的依据 |
| MEM-102/103 主题/风险 | ✅ 已接线 | O2 维持的依据 |

---

## 10. 文档一致性核对表

| 文档引用 | 代码锚点（已实现） | 核对结果 |
|---|---|---|
| `MessageSummaryService.generateSummaryAsync` | L62-106 | ✅ 与描述一致 |
| `AiChatServiceImpl.extractProfilePatch` | L254-262 | ✅ 与描述一致 |
| `AiChatServiceImpl.extractKeyEvents` | L375-383 | ✅ 与描述一致 |
| `ProfileExtractorService.extractAndMerge` | L62-106 | ✅ 与描述一致 |
| `LongTermMemoryService.extractAndStoreKeyEvents` | L89-146 | ✅ 与描述一致 |
| `MemoryProfileBackfillService.backfill` | L58-101 | ✅ 与描述一致 |
| `ProfileMergeGate.merge/applyDecay/isExpired` | L49/L88/L101 | ✅ 死分支证据一致（零调用方） |
| `StudentProfileService.updateProfile` | L61-95 | ✅ VCL-001 聚合派生证据一致 |
| `application.yml` 阈值/温度/话术 | L54/121/154/160/171 | ✅ 双源证据一致 |
| 前端 `api.ts`/`api/toolboxApi.ts`/`config/remote.ts` | 327/60 行/L96 | ✅ 与描述一致 |
| OD-001/004/014/VCL-001 登记 | TASK-TRACKER L848/851/861/666 | ✅ 引用一致 |
| S1/S2/S3/S4 目标设计 | 本专题改造 | ⏳ 未实施（本文件发布后按 M1-M5 执行） |

---

_设计文档 v1.0 | 状态：Doing | 创建：2026-08-05 | 评审定稿后归档至 design/ 根目录并同步 TASK-TRACKER_
