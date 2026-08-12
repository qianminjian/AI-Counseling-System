# 审计报告 04 - 服务层·对话与内容域

- **审计时间**：2026-08-12（全项目分块架构审计批次2，板块04）
- **审计范围**：`backend/counseling-service/src/main/java/com/mindsafe/service` 下 conversation（16）、knowledge（9）、prompt（5）、memory（4）、risk（3）、safety（2）、relaxation（1）、diary（1）、toolbox（3）、tts（2）、voice（4）共 11 包约 50 个 main 文件 + `src/test` 对应 40 个测试文件（仅评估覆盖与可测试性）
- **审计方法**：git log 只读走查（40 条本板块提交）→ 全量 Read 走读 50 个 main 文件 → Grep 验证死代码/未接线/调用方 → 对照 design/09、10、13、BEACON、TASK-TRACKER 与 .qoder/rules/code-engineering.md
- **约束**：只读审计，未修改任何文件；已冻结决策（frozen/58 ESC-001、BEACON #10/#19/#22、SAFE-202、DOC-073）仅核对实现一致性，不作为整改对象

---

## 1. 板块概况（结构、依赖关系、规模统计）

### 1.1 包结构与规模

| 包 | main 文件 | 行数参考 | 测试文件 | 角色 |
|---|---|---|---|---|
| conversation | 16（含 strategy/ 2） | ConversationServiceImpl 772、MessageSummaryService 439、ConversationContextAgent 332 | 15 | 对话主链路 hub |
| knowledge | 9 | KnowledgeBaseService 357、RagAdvisorService 180 | 6 | RAG 混合检索 + 知识库审核流 |
| prompt | 5 | PromptVersionService 553、PromptEvalGovernance 236 | 5 | 模板版本路由 + 三门禁 + eval |
| memory | 4 | LongTermMemoryService 472 | 4 | 长期记忆 + 主题演化 + 风险关联 |
| risk | 3 | RiskOverviewService 224 | 3 | 风险事件统一入口 + SLA 统计 |
| safety | 2 | OutputSafetyReporterImpl 184 | 2 | 输出安全上报（依赖倒置 adapter） |
| voice | 4 | VoicePersonaResolver 160、TrendAnomalySignaler 218 | 1 | 音色决策 + 情绪趋势/异常信号 |
| tts | 2 | VoicePersonaMatcher 137 | 1 | 语音降级策略 + 音色匹配 |
| toolbox | 3 | MoodCheckService 80 | 2 | 工具练习情绪对比编排 |
| diary | 1 | EmotionDiaryService 84 | 1 | 情绪日记打卡/streak |
| relaxation | 1 | RelaxationService | —（未确认独立测试） | 放松练习落库 |

### 1.2 依赖关系（对话链路跨包协作）

```
conversation（hub，扇出型）
 ├─→ risk（RiskEventWriter 写入、风险事件）
 ├─→ knowledge（RagAdvisorService RAG 检索）
 ├─→ memory（LongTermMemoryService 记忆回注）
 ├─→ prompt（PromptOrchestrationService/PromptVersionService 编排与模板）
 ├─→ voice（TrendAnomalySignaler/VoiceEmotionTrendAnalyzer，会话结束分析）
 ├─→ safety（经 counseling-ai 的 OutputSafetyReporter 接口回调，依赖倒置）
 ├─→ profile/notification/achievement（画像/通知/徽章）
 └─ 入边极少（无包反向依赖 conversation 的编排入口）
```

**locality 评估**：conversation 是明显扇出 hub 而非双向蜘蛛网——依赖方向基本单向，跨包协作通过构造注入完成，理解一条"消息入库→摘要→记忆→工具调用→安全过滤"链路需在 conversation 内部 + 6 个协作包间跳转，但各协作点（S-002 buildRoundContext 单点、BA-10 readSessionTranscript 单点、RiskEventWriter 单点）已明显收敛。**残留在两处**：风险事件写入仍有 3 处绕道（见 P0-1）；会话结束分析（SessionEndAnalyticsService）同时挂 memory/voice/risk 三个包，是第二密集点。

---

## 2. 热点与风险初判（git log 40 条）

| 热点 | 提交 | 风险解读 |
|---|---|---|
| **摘要补偿任务 5 次修复** | d99ba1c9/f0e4ac08/a5a1cf7c/fe30c826/e7a2eba1 | 高变更频次 × **零测试**（grep 全仓 `*Test.java` 无 SummaryCompensationJob）→ 最高回归风险点 |
| doing/93 S 系列 | S-013 风险词典注入化(252686d3)、S-009 统一入口(f8de06ea)、S-002 单点(c8afea48) | **S-009 只收口了 conversation 内组件，memory/safety 未收敛**（本次审计重点验证项） |
| doing/92 R 系列 | R-010 日界收敛(ba2db799 三服务)、R-011 打卡原子化+R-015 审查留痕(375b4ece) | R-010 收敛范围不含 risk 包（RiskOverviewService 仍硬编码时区） |
| ARCH-001 C1 上帝类拆分 | a89a1359/a0b4a52da 等 | ConversationServiceImpl 仍 772 行/24 依赖（拆分未完） |
| BUG-KB-01/02/03 | b9155147/d0874074/f3feb579 | 知识库链路近期集中修 bug，documentExists 查重口径仍陈旧 |
| BUG-TENANT-01/01b | e1e53a04/fde18d58 | @Async 租户上下文丢失，摘要链路兜底——摘要域异步化有历史坑 |

---

## 3. 发现清单（分级表）

### P0 架构级

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试判断 |
|---|---|---|---|---|---|
| **P0-1** | [LongTermMemoryService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/memory/LongTermMemoryService.java) :449-470（:463 insert、:465 markSent）；[OutputSafetyReporterImpl.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/safety/OutputSafetyReporterImpl.java) :76/:112/:162；[SosEventService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/safety/SosEventService.java) :83 | **S-009 风险事件统一写入入口迁移不完整**。doing/93 S-009（f8de06ea）建立了 RiskEventWriter 统一入口，conversation 内 ConversationRiskProcessor:206、SessionEndAnalyticsService:172 已收敛；但 memory 包（persistMemoryRiskSignal）、safety 包（输出 Layer1/Layer2/召回三处 + SOS 事件）仍直连 `riskEventMapper.insert` + `riskNotifyOutboxService.markSent/markFailed`。统一入口的语义（needsNotify=false 防补偿误重试、通知失败标记一致性）在这三处被绕过，**同一风险事件域存在 4 套写入语义**，后续补偿/通知逻辑改动需同时维护四处 | 三处改用 `riskEventWriter.write(event, needsNotify)`；SosEventService 的 fail-fast 语义若需保留，在 writer 增加相应参数或独立方法 | 删除 3 处直连后写入语义单点，补偿任务改动传导到所有风险事件（leverage）；已冻结决策实施一致性 | 删除三处直连后复杂度**集中**到 writer（writer 有测试 RiskEventWriterTest），是深化而非转发层 |
| **P0-2** | [KnowledgeCorpusIngestService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/knowledge/KnowledgeCorpusIngestService.java) :84 | **documentExists 用陈旧 `status='active'` 查重**。V30 后 active 已迁移为 published（KnowledgeBaseService.search :113 仅 published 可检索；ReviewWorkflowStateMachine.fromDbStatus :96 已兼容 active/published），摄入幂等查重查不到已 published 文档 → **重复摄入可绕过幂等** | 查询改为 `status IN ('active','published')`，或按 V30 口径只用 published（与检索/审核口径单源对齐） | 幂等恢复，摄入链路数据一致性 | 修复查重谓词，无模块增删 |
| **P0-3** | [MessageSummaryService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/conversation/MessageSummaryService.java) :60（`FULL_FIDELITY_RISK_LEVEL = 2`）vs design/02 §7.2.1:324、design/08 §2.5:282（D-7 冻结决策） | **两级摘要保真阈值与冻结决策漂移**。D-7 拍板：riskLevel ≥ L3（仅 RED）原文保真；代码+测试（MessageSummaryServiceTest:161-162）为 risk≥2（ORANGE 也保真）。扩大保真范围 = 更多敏感原文以完整形态落库（风险消息字段级加密前的 contentSummary），与 PIPL 数据最小化权衡不符；且设计未提 1024 截断兜底，代码 :169 截断 1024 | 二选一：代码对齐文档（收紧为仅 RED 保真）或文档采纳代码口径（ORANGE 保真为有意为之），须项目负责人确认方向后同步 design/02、08 与测试 DisplayName | 冻结决策实现一致性；控制敏感原文留存范围 | 修复阈值常量 + 测试断言，无模块增删 |

### P1 模块级

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试判断 |
|---|---|---|---|---|---|
| **P1-1** | [ConversationServiceImplTest.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/test/java/com/mindsafe/service/conversation/ConversationServiceImplTest.java)（全文件，grep 归属关键词 0 命中） | **SEC-001 会话归属校验（历史 P0 缺陷修复）无负面测试**。代码已实施 isSessionOwner 于 sendMessageStream:206/sendNudgeStream:458/updateClientSettings:559/endSession:583/rateSession:638 五触点，但测试仅覆盖"Redis 会话不存在→空流/报错"（:437-444），**无"非 owner 调用被拒绝"用例**。design/09:2257 声称"测试：ConversationServiceImplTest 归属校验用例"——台账失实 | 补 4 个负面用例：跨租户/跨学生调用 sendMessageStream、endSession、updateClientSettings、sendNudgeStream 断言拒绝语义（error/空流/FORBIDDEN/静默） | 防跨会话劫持回归（安全红线）；design/09 台账实化 | 纯补测试，无模块增删 |
| **P1-2** | [ConversationServiceImpl.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/conversation/ConversationServiceImpl.java)（772 行/24 构造依赖） | **上帝类拆分未完**。ARCH-001 C1 已拆出 riskProcessor/contextAgent/sessionStore 等组件，但主类仍 772 行、24 依赖，超出 code-engineering §3.1（Java 400 行上限）；sendMessageStream 单方法约 300 行（:198-520）承载风险→脱敏→语义→融合→短路→编排→RAG→落库全链 | 继续按职责抽离：①流式响应组装（SSE 事件构造）；②消息持久化（消息+摘要+state_path）合并到 MessageSummaryService 既有单点；③会话生命周期收尾（endSession+rateSession+analytics 触发）。目标 24→≤16 依赖 | 单方法可测性提升；主链路阅读 locality 改善 | 已拆出的组件是有深度模块，继续拆是深化；残留编排逻辑属合理编排器职责，不追求过拆 |
| **P1-3** | [PromptEvalGovernance.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/prompt/PromptEvalGovernance.java)（236 行，grep 全仓仅定义处 4 匹配，无 main 调用方） | **未接线死代码**。灰度放量/κ 校准/下钻评估逻辑自述"接线时由 PromptVersionService 灰度流程 + 定时任务消费"，但当前无消费方（AdminPromptControllerTest:57 仅 mock）——design/45 PEVAL P2 未落地，代码空转 | 接线（PromptVersionService 灰度放量处调用）或删除并登记台账；不宜长期留无消费方的"设计先行"代码 | 消除死代码认知负担 | 删除后无复杂度移动（纯死代码），删除测试通过；若接线则删除测试不适用 |
| **P1-4** | [RiskMetricsJob.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/risk/RiskMetricsJob.java) :39-40、[RiskOverviewService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/risk/RiskOverviewService.java) :35-40、:58-59、:71 | **SLA 常量两处重复 + R-010 时区收敛不完整**。SLA_DISPOSE_MINUTES 映射（3→15/2→60/1→480/0→1440）在 RiskMetricsJob 与 RiskOverviewService 逐字重复；RiskOverviewService 仍硬编码 `ZoneId.of("Asia/Shanghai")`，未用 CounselingTimeZone（doing/92 R-010 仅收敛三服务 ba2db799；对比 EmotionDiaryService:36、RelaxationService:39 已收敛） | SLA 映射收敛到单一常量源（如 risk 包内公共类）；时区统一改 CounselingTimeZone.startOfDay/truncateToDay | SLA 口径单源（改 SLA 只动一处）；R-010 冻结决策收敛完整 | 收敛后删除重复常量，复杂度集中，深化 |
| **P1-5** | [KnowledgeMetadata.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/knowledge/KnowledgeMetadata.java)（matchesGrade/isSearchableForGrade 无调用方）；[RagAdvisorService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/knowledge/RagAdvisorService.java) :146-158 | **两套年级段判定并存（KB-102 过渡态未清）**。KnowledgeMetadata 的年级匹配逻辑死代码；实际生效的是 RagAdvisorService.gradeBandOf/matchesGradeBand（正文首行 grade_band 标注正则近似过滤） | 删除 KnowledgeMetadata 年级判定，或将其接线替换正文正则（倾向后者：结构化解耦正文格式）；登记 KB-102 收尾 | 年级段判定单一实现，RAG 年级过滤行为可测 | 删除 KnowledgeMetadata 判定后复杂度不移动（当前无消费）→ 删除测试通过；接线路径则需迁移测试 |
| **P1-6** | [MessageSummaryService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/conversation/MessageSummaryService.java) :413-416 | **truncate 用 UTF-16 substring，与 R-021 code point 截断口径不一致**。MessageSummarySummarizer（domain 包，R-021 已修）按 code point 截断，service 层 BA-04 单一入口反而用 `s.substring(0, maxLen)`——消息 >1024 字符且恰含代理对（emoji/生僻字）时劈开代理项，字段级加密后解密可能损坏摘要 | truncate 改为按 code point 截断（与 domain 包共用工具，可收 TextUtils） | 截断口径全链路一致，加密摘要稳健 | 修改截断实现，无模块增删 |
| **P1-7** | [ConversationServiceImpl.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/conversation/ConversationServiceImpl.java) :201-204、endSession 内 get 为 null 跳过 | **Redis 会话状态无 DB 兜底重建**。SessionState TTL 2h，Redis 过期/重启后 sendMessageStream 直接返回"会话不存在"（:203），即使 counseling_sessions 表仍有会话；endSession 对 Redis 缺失会话直接跳过。对话连续性（心理辅导场景中断伤害）与 DB/Redis 一致性缺兜底 | 复用 CounselingSessionStore.findOwned（已具 DB 读取+归属三重条件）做 Redis 缺失时的重建路径（重建 SessionState 并写回 Redis），或在设计层面明确"过期即不可续"并同步文档 | 会话中断面收敛；与 SEC-001 的 DB 侧兜底语义对齐 | 增加重建路径是深化（复用既有仓储能力）；删除测试不适用 |
| **P1-8** | [SummaryCompensationJob.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/conversation/SummaryCompensationJob.java) :37（直连 CounselingSessionMapper，绕过 BA-11 收口的 CounselingSessionStore） | **摘要补偿任务绕过仓储直连 Mapper + 零测试**。BA-11 已抽 CounselingSessionStore（DB 会话读写仓储），补偿任务仍直连 Mapper（:37），绕过仓储封装；该任务为 git 热点（5 次修复：终态覆盖/分页 total/通知标题等），却无任何测试文件 | ①收敛到 CounselingSessionStore；②补测试：三终态（completed/taken_over/escalated）扫描、SCAN_LIMIT 上限、risk≥2 保真/提炼两分支、幂等不重补 | 变更热点 × 零测试组合的回归风险收敛 | 收敛后删除直连，复杂度集中到 Store（深化） |
| **P1-9** | [ConversationRiskProcessor.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/conversation/ConversationRiskProcessor.java)（riskEventMapper/riskNotifyOutboxService 字段）、[SessionEndAnalyticsService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/conversation/SessionEndAnalyticsService.java)（同） | **死字段**：两组件构造注入 riskEventMapper/riskNotifyOutboxService 但业务写入已走 riskEventWriter（S-009 后），字段仅声明+构造+赋值（grep 6 处均非业务使用） | 删除死字段与对应构造参数（连带更新测试装配） | 构造依赖瘦身；S-009 收口痕迹清理 | 删除后无行为变化，删除测试通过 |

### P2 局部

| 编号 | 位置 | 问题描述 | 建议方案 |
|---|---|---|---|
| **P2-1** | [PromptVersionService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/prompt/PromptVersionService.java) :227 | invalidateCache 注释声称"SCAN 防阻塞"，实现却是 `redisTemplate.keys("prompt:*")`——keys 恰是 O(N) 阻塞命令，注释与实现矛盾 | 注释改述或实现改 SCAN 游标 |
| **P2-2** | design/09 §3.x:933 | 引用已删除的 `SessionSummaryUpdater`（BA-10 已收编至 MessageSummaryService，代码无此类） | 文档改述为 MessageSummaryService 滚动摘要（SUMMARY_INTERVAL=4） |
| **P2-3** | design/09:2257 vs 代码 | SEC-001 设计记录"四触点"，代码实际五触点（多 rateSession:638，更安全） | 文档补 rateSession 触点 |
| **P2-4** | [RiskMetricsJob.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/risk/RiskMetricsJob.java) :137-143 | 直连 RiskEventMapper 全表拉取在内存过滤，数据量增长后慢查询/OOM 风险 | SQL 下推过滤条件 |
| **P2-5** | SessionState.safetyMode（:56）vs design/08:32/196 | 术语同名异义：design/08 的 safety_mode 三按钮属 ESC-001（冻结未实施），代码 safetyMode 是 RED 短路后的陪伴模式（RISK-201） | 代码注释/文档明确区分两概念，防后续实施 ESC-001 时混淆 |
| **P2-6** | [MoodCheckService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/toolbox/MoodCheckService.java) :73-75 | 练习后情绪恶化仅 log.warn，注释自认"后续接 MEM-103"未接线——恶化信号未汇入 BL-08 预警通道 | 登记 MEM-103 挂账；当前保留返回值供消费 |
| **P2-7** | [EmotionDiaryService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/diary/EmotionDiaryService.java) :53/:59 vs :36 | 同文件内 CounselingTimeZone 引用方式混用（import 与全限定名） | 统一为 import |
| **P2-8** | tts/VoiceDegradationPolicy vs voice/VoicePersonaResolver | 两条"安全场景语音锁定"规则并存：tts 按风险等级 S0/S1（decide :49-63）、voice 按场景 scene=safety/crisis（resolve :80-82），均被 TtsController 注入（:43-44） | 补充注释明确双轨边界（TTSFX-002 vs TMATCH-001），避免后续合并误伤 |

**未发现项**（如实说明）：voice/tts 包音频数据链路未见服务端存储/脱敏缺陷（音频不出后端：前端 WASM 本地处理 + 转写即删 COMP-009 属 AI/Python 层，本板块仅纯决策组件）；输出安全 Layer1/Layer2 回调的"会话不存在优雅降级"均有测试覆盖（OutputSafetyReporterImplTest:103/149/226）。

---

## 4. 改进候选排序

### Strong（低成本高杠杆，推荐首批）

1. **P0-1 S-009 收口**：三处直连改 RiskEventWriter——冻结决策一致性 + 风险事件语义单点，改动面小（各 3-6 行）
2. **P1-1 SEC-001 负面测试**：补 4 个归属拒绝用例——安全红线回归保护，纯测试零实现风险
3. **P0-2 documentExists 查重口径**：一行谓词修复，幂等恢复
4. **P1-4 SLA/时区收敛**：常量单源 + CounselingTimeZone——R-010 冻结决策补全
5. **P1-6 truncate 口径对齐**：与 domain 包共用 code point 截断——加密摘要稳健性
6. **P1-8 补偿任务**：收敛 Store + 补测试——当前最高变更热点 × 零测试组合

### Worth exploring

7. **P0-3 两级摘要阈值**：需项目负责人裁决方向（对齐文档或采纳代码），随后同步 design/02、08
8. **P1-2 ConversationServiceImpl 再拆分**：流式响应组装/持久化收尾两处抽离，24→≤16 依赖
9. **P1-7 Redis 会话 DB 兜底重建**：复用 CounselingSessionStore.findOwned，需确认产品语义（过期是否可续）
10. **P1-3 PromptEvalGovernance 去留**：接线或删除二选一，避免长期空转
11. **P1-5 KnowledgeMetadata 年级判定**：接线替换正文正则（结构化收益）或删除
12. **P1-9 死字段清理**：随 P0-1 收口顺带删除

### Speculative

13. **P2-1~P2-8**：注释/台账/性能细节，随主修复批次顺带处理

---

## 5. 设计一致性核对（与 design/*.md）

| # | 设计决策 | 出处 | 代码实态 | 结论 |
|---|---|---|---|---|
| 1 | D-7 两级摘要：risk≥L3 保真/提炼 ≤200 字 | design/02:324、08:282（冻结已拍板） | FULL_FIDELITY_RISK_LEVEL=2，risk≥2 保真截断 1024（MessageSummaryService:60/168-170） | ⚠️ **不一致**（P0-3）：保真阈值与截断语义漂移 |
| 2 | BA-04 摘要策略单一入口 | design/08（DOC-074 收口） | MessageSummaryService.persistStudentMessageSummary 单一入口 ✓ | ✅ 一致 |
| 3 | CTX-Agent 主 Agent 上下文简报（BEACON #22，冻结） | BEACON #22、design/09:933 | ConversationContextAgent.buildContextBrief 已实现，零 LLM ✓；但 design/09:933 仍引用已删除的 SessionSummaryUpdater | ⚠️ 主体一致，文档引用过时（P2-2） |
| 4 | DEC-CBT 删除世界 B（BEACON #19，冻结） | design/09 §1.2-1.3 | 主链路单 prompt（ConversationServiceImpl + PromptOrchestrationService）✓ | ✅ 一致 |
| 5 | RISK-201 RED 硬短路 + 分年级文案 | design/09:1539 | sendMessageStream:323-335 硬短路、CrisisResources 分年级、测试 6 例 ✓ | ✅ 一致 |
| 6 | RISK-202 语义分类只升不降 | design/09 §1.3、词汇表 §2 | applySemanticRisk 只升不降（ConversationRiskProcessor:85-108）✓ | ✅ 一致 |
| 7 | SEC-001 会话归属校验（历史 P0 修复） | design/09:2257 | isSessionOwner 五触点实施 ✓；**负面测试缺失**（P1-1）；文档四触点 vs 代码五触点（P2-3） | ⚠️ 实现已修，测试与台账缺口 |
| 8 | SAFE-202 高敏场景前置标记 | design/09、词汇表 §2 | session.setHighSensitivity（:244-247）✓ | ✅ 一致 |
| 9 | 输出安全四决策留痕（R-015） | doing/92 R-015 | OutputSafetyReporterImpl reviewJson 落库（:110-111/160-161）+ 补丁语义正确 ✓ | ✅ 一致 |
| 10 | 转人工升级 escalated/taken_over/completed | 词汇表 §1、frozen/58（冻结） | 会话状态三态 + takeoverSession 接线（TeacherControllerFullTest:251）✓；ESC-001 前端三按钮/端点冻结未实施（符合冻结态） | ✅ 冻结排除项，仅符合性确认 |
| 11 | 学生安全模式（safety_mode） | frozen/58（冻结） | RISK-201 陪伴模式已实现（SessionState.safetyMode:56 + 测试:693）；ESC-001 三按钮未实施 | ✅ 冻结排除项；⚠️ 术语同名异义（P2-5） |
| 12 | S-009 风险事件统一入口 | doing/93（f8de06ea） | conversation 已收敛，memory/safety 未收敛（3 处绕道） | ❌ **不一致**（P0-1） |
| 13 | S-002 上下文组装单点 | doing/93（c8afea48） | buildRoundContext 单点（ConversationServiceImpl:673-689）✓ | ✅ 一致 |
| 14 | BA-10 消息读取单点 | design/08（DOC-084） | MessageSummaryService.readSessionTranscript ✓ | ✅ 一致 |
| 15 | R-010 业务日界收敛 | doing/92（ba2db799） | EmotionDiaryService/RelaxationService 已用 CounselingTimeZone；RiskOverviewService 硬编码 | ⚠️ 部分收敛（P1-4） |
| 16 | R-011 打卡原子化 / R-015 留痕 | doing/92（375b4ece） | EmotionDiaryService.upsertCheckin ✓、审查 JSON 落库 ✓ | ✅ 一致 |
| 17 | D-8 PII 昵称置换 | design/02（ARCH-007） | ConversationContextAgent:78-92 置换 + 测试 greetingWithPseudonym ✓ | ✅ 一致 |
| 18 | 知识库 V30 审核状态迁移 | V30 迁移、ReviewWorkflowStateMachine:96 | 检索/审核已用 published；**摄入查重仍用 active**（P0-2） | ⚠️ 部分迁移（P0-2） |
| 19 | 词汇表术语使用（RiskKeywordRegistry/CrisisResources/BL-08） | design/13 §2 | S-013 词典注入化 ✓、CrisisHotlineProvider 五源收敛 ✓、attention 信号汇入 BL-08 ✓ | ✅ 一致 |
| 20 | 覆盖率门禁 | design/13 §10、TASK-TRACKER TEST-001/005 | CI 门禁核心 service ≥60%/整体 ≥45%（目标 80%，2026-08-05 后全量 84.3% 达标）；本板块主链路测试充分，**安全关键负面路径缺口**（P1-1）与**变更热点零测试**（P1-8） | ⚠️ 局部缺口 |

**冻结决策排除项**（仅符合性确认，不作为整改对象）：frozen/58（ESC-001 转人工升级、safety_mode 三按钮）、BEACON #10（双层审查）/ #19（DEC-CBT）/ #22（CTX-Agent）、SAFE-202 高敏模式、DOC-073 热线单源。

---

## 6. 修复建议

**值得进入集中修复**（理由：全部为已冻结决策实施一致性 / 安全红线回归保护 / 高变更热点加固，且多数改动面 3-6 行）：

- **P0 全修**：P0-1（S-009 收口）、P0-2（查重口径）、P0-3（阈值对齐，需项目负责人裁决方向后执行）
- **P1 按收益排序**：P1-1（SEC-001 负面测试）→ P1-8（补偿任务测试+收敛）→ P1-4（SLA/时区）→ P1-6（截断口径）→ P1-9（死字段，随 P0-1 顺带）→ P1-7（Redis 兜底，建议排期确认产品语义）→ P1-2（再拆分，可独立批次）→ P1-3/P1-5（去留裁决，二选一）
- **P2 可选**：P2-1~P2-8 随主批次顺带处理（注释/台账类成本极低，建议 P2-2/P2-3 台账修正随文档同步批次执行）

**不建议进入集中修复**：无（本板块未发现需废弃的设计；P1-3/P1-5 属"接线或删除"议决项，建议合并入汇总阶段的裁决清单）。

**回归注意事项**：P0-3 若收紧保真阈值，需同步 MessageSummaryServiceTest:161-162 断言与 MessageSummarySummarizer 契约；P1-1 补测试时注意 endSession 归属失败语义为抛 FORBIDDEN（:583 附近），与 sendMessageStream 的"会话不存在"错误语义区分。
