# 61 对话主链路架构深化（ARCH-001）方案与 SPEC

> 关联任务：ARCH-001（对话主链路架构深化，C1~C5 候选，待登记 TASK-TRACKER）
> 状态：📝 方案定稿 → 待决策（架构审查 2026-08-05，选候选后进入决策循环）
> 依据：improve-codebase-architecture 第 2 轮审查报告（tmp/architecture-review-20260805-232048.html，git 忽略不落库）、git 热点分析（近 50 提交）、后端/前端只读代码扫描
> 审计修正（2026-08-05 深度审计）：C4 裸 fetch 实为 5 处（非 4 处，含 useSilenceNudge 且 2 处实走 authFetch）；C5 测试 mock 实为 18 个（非 15 个）；toolboxApi.test.ts 非空壳（不删除）。详见 §7/§8 修订文字。
> 词汇：模块 / 接口 / 深度 / 接缝 / 局域性 / 删除测试——见 [13 领域词汇表](../13_领域词汇表.md) 架构词汇表

---

## 1. 背景与问题

架构审查（第 2 轮）以最近变更热土为范围（后端 AI 对话编排链 + 前端 student-h5），以「深度」与「局域性」为判据扫描，发现 5 组结构摩擦：

1. **对话编排上帝类**：`ConversationServiceImpl` 826 行 / 22 构造器依赖，混入风险融合、Prompt 组装、CBT 注入、正则提取、JSON 序列化 8 种职责；私有方法的关键词表与 `ThemeEvolutionEngine.THEME_KEYWORDS` 重叠却不可测试。
2. **风险领域知识无单一源**：同一风险概念（"想死""割腕""遗书"）在 **≥5 份静态词典**重复（审计修正：初版记 4 份，实测含 TemplateMatrixRegistry/SafetyKeywordLibrary 等 ≥5 处）；负面情绪集合 **6 处**成员不一致（审计修正：初版记 5 处，实测 6 处含情绪判定消费点；同一 `anxious` 在会话内/冷场计数/会话结束分析得出不同结论）；评分魔法数 85/60/35/30 与权重 `10,0,0,0,0.8` 散落三处。
3. **假功能与死代码并存**：ORCH-006 消费端为 `ProfileSignals` 6 字段写了微调逻辑（单测通过），生产端 `getProfileSignals` 只填 4 字段、其余恒 null——「测试绿、生产死」；另有 6 处零调用死代码与 1 个僵尸参数。
4. **SSE/API 接缝泄漏（前端）**：同一 SSE 流式协议实现 3 遍（useSseStream / useSilenceNudge / 测试内联），nudge 的 emotion/risk 事件被静默丢弃；**5 处**绕过 api.ts 的裸 fetch（审计修正：初版记 4 处，实测 5 处，其中 2 处实走 authFetch、1 处绕过 401 刷新）；契约测试只覆盖 15/23+ 端点，最核心的消息/TTS/声纹路径反而最不受保护。
5. **ChatRoom 神组件（前端）**：715 行 / 13+ hooks / 15 state·ref；语音输入编排（录音→分析→自动发送）整链内联；测试 135 行 mock + 白盒回调捕获，成为重构阻力；`useWakeEnabled` 被第二消费者绕过。

## 2. 目标

- 消除「测试绿、生产死」的假功能与不可测试的私有规则（局域性）
- 让安全红线领域的规则（风险词典、情绪判定）有唯一收敛点，行为漂移可被测试拦截
- 收敛重复协议解析与散落 API 调用，补全契约防线最核心链路
- 全程遵循删除测试：只做「集中复杂度」的收敛，不做「移动复杂度」的转发层堆叠

## 3. 候选方案总览

| 候选 | 主题 | 强度 | 域 | 删除测试结论 |
|------|------|------|----|-------------|
| C1 | 对话编排「上帝类」拆分 | Strong | 后端·编排 | 正向集中复杂度 |
| C2 | 风险领域知识单一规则源 | Strong | 后端·安全红线 | 集中复杂度（现为移动后仍重复） |
| C3 | 假功能与死代码清理（ORCH-006 缺口） | Worth exploring | 后端·画像 | 全部集中、零行为损失 |
| C4 | SSE/API 接缝收敛 | Strong | 前端·协议 | 删重复份=集中（正方向）；删收敛点=移动（错方向） |
| C5 | ChatRoom 语音编排抽取 | Worth exploring | 前端·组件 | 抽 hook=集中复杂度 |

## 4. C1 · 对话编排「上帝类」拆分

**强度**：🟥 Strong

**涉及文件**：
- `backend/counseling-service/src/main/java/com/mindsafe/service/conversation/ConversationServiceImpl.java`（826 行 / 22 依赖）
- `backend/counseling-ai/src/main/java/com/mindsafe/ai/chat/AiChatServiceImpl.java`（482 行）
- 私有方法：`extractTopicHint`（L703-732）/ `extractPersonalInfo`（L754-790）/ `appendStatePath`（L799-824）

**问题**：单类混入 8 种职责，22 个构造器依赖是全项目最宽接口；私有方法不可独立测试，`extractTopicHint` 的 20 行主题关键词表与 `ThemeEvolutionEngine.THEME_KEYWORDS`（L33-40）功能重叠——规则散落 = 领域知识不可测试。另：`AiChatServiceImpl.chatProactive` 不走 `PromptVersionService` 的 DB 优先/A-B 路由，同一模板体系两条加载路径。

**方案**：按职责边界拆出：
- `PromptAssemblyService`（深模块：接收会话/画像/策略上下文，输出最终 Prompt，封装 3 次 resolve 与 CBT 注入）
- `PersonalInfoExtractor`（深模块：4 组正则 + 测试面）
- 主题关键词收敛到 `ThemeEvolutionEngine` 单一源
- 顺带：`chatProactive` 的 SYS_001 渲染改走版本路由；删除 `AiChatService.profilePrompt` 僵尸参数

**收益**：局域性（Prompt 组装与风险判定独立演化）；杠杆（22 依赖 → ~12）；测试（私有正则与关键词表变为公有接口）。不新增转发层，是「把目前不集中的逻辑集中到正确的模块」。

**风险**：拆分涉及 826 行核心路径，需全量回归（当前 1529 用例基线）+ SSE 集成验证；建议按「先抽纯函数模块（PersonalInfoExtractor）→ 再抽 PromptAssemblyService → 最后瘦身编排类」渐进。

## 5. C2 · 风险领域知识单一规则源

**强度**：🟥 Strong（Top 推荐）

**涉及文件**：
- `backend/counseling-ai/src/main/java/com/mindsafe/ai/risk/RiskDetectorServiceImpl.java`（2 份词典：L38-63 分级词、L76-117 类别表）
- `backend/counseling-service/src/main/java/com/mindsafe/service/conversation/ConversationRiskProcessor.java`（L225-240 意图/方法/准备词 + L85 评分 + L162-175 权重）
- 负面情绪集合 5 处：`SessionState` L125 / `ConversationRiskProcessor` L302-305 / `SessionEndAnalyticsService` L146-149 / `ConversationContextAgent` L285-290 / `LongTermMemoryService` L390-397（成员不一致）
- `ThemeEvolutionEngine.THEME_KEYWORDS`（主题词，与 C1 联动）

**问题**：儿童安全红线领域，同一信号在不同管线得到不同结论 = 漏判危机信号的隐患；词典改动一处不同步另三处即行为漂移；无任何测试能断言「集合一致」。

**方案**：收敛为两个只读规则模块（深模块，无状态、无副作用）：
- `RiskKeywordRegistry`：四级词典（RED/ORANGE/YELLOW + 意图/方法/准备词）+ 评分因子 + 唯一增删入口
- `EmotionVocabulary`：负面/正面情绪集合 + 中英别名 + 唯一判定入口
- 各消费点改为引用注册表；魔法分数值（85/60/35/30、权重）收编为命名常量

**收益**：局域性（安全规则改动只动一个文件，跨管线自动传导）；测试（新增「注册表全量断言」单测：四级互斥、情绪判定一致，把漂移挡在 CI）；杠杆（未来新增情感维度/方言变体只改注册表）。

**风险**：极低——规则模块只读、纯静态数据 + 查找方法，TDD 与全量回归可完整覆盖。注意与 design/04 风险识别规则库（主文档单一事实源）保持一致，实施前需同步规则清单。

## 6. C3 · 假功能与死代码清理（ORCH-006 缺口）

**强度**：🟨 Worth exploring

**涉及文件**：
- `backend/counseling-ai/src/main/java/com/mindsafe/ai/orchestrator/PromptOrchestrationService.java`（L117-137 消费分支；`resolve()` L52-54 向后兼容死路径）
- `backend/counseling-service/src/main/java/com/mindsafe/service/profile/StudentProfileService.java`（`getProfileSignals` L296-298 恒 null）
- `backend/counseling-domain/src/main/java/com/mindsafe/domain/entity/CounselingSession.java`（`end()` L82-86 / `upgradeRiskLevel()` L88-93 零调用，`end()` 硬编码 "completed" 不用自己的 `STATUS_COMPLETED`）
- `backend/counseling-service/src/main/java/com/mindsafe/service/conversation/SessionEndAnalyticsService.java`（`fuseEmotions()` L128-130 薄转发零调用）
- `backend/counseling-ai/src/main/java/com/mindsafe/ai/chat/AiChatService.java`（`profilePrompt` 僵尸参数，生产唯一调用点恒传 null）
- 仅测试使用的构造：`OrchestrationContext` 6 参构造 / `ProfileSignals` 4 参构造

**问题**：「测试绿、生产死」的认知税：ORCH-006 的画像微调（高敏感降速、探索式引导、技能唤起）在消费端实现且单测通过，但生产信号恒 null——最典型的「为可测试性抽出纯函数、bug 藏在调用处」；另有 6 处死代码与 1 个僵尸参数。

**方案**：二选一并显式化（**待决策 D-1**）：
- 路径 A（补全）：`getProfileSignals` 提取 P2 字段（sensitivity/curiosity/copingSkills 从画像 JSONB 读取）→ 兑现 ORCH-006 设计意图，性格微调成为真实对话杠杆
- 路径 B（删除）：删消费端死分支 + 收缩 `ProfileSignals` 到实际字段 → YAGNI 承认
- 无论哪条路：删除 6 处死代码与僵尸参数，`ChatRoom` 中实体更新改用领域方法

**收益**：接口与实际行为对齐；删除测试全部为集中复杂度、零行为损失；暴露的 ORCH-006 缺口成为明确 backlog 而非隐性债务。

## 7. C4 · SSE/API 接缝收敛（前端）

**强度**：🟥 Strong

**涉及文件**：
- `frontend/student-h5/src/hooks/useSseStream.ts`（协议解析唯一实现，L47-99）
- `frontend/student-h5/src/hooks/useSilenceNudge.ts`（第 2 遍内联重写 L72-102 + 裸 fetch + 手拼 Authorization，绕过 401 刷新；emotion/risk 事件被静默丢弃）
- 裸调用 5 处（**审计修正 2026-08-05**）：`useSilenceNudge.ts` L72（nudge，绕过 401 刷新）/ `ChatRoom.tsx` L149（voice/analyze）/ `config/remote.ts` L54（system/config）/ `VoiceLoginOverlay.tsx` L82（tts/login-prompt）/ `useTtsPlayer.ts` L166（tts/synthesize）；其中 2 处实走 authFetch
- `frontend/student-h5/src/test/apiContract.test.ts`（FRONTEND_ENDPOINTS 仅 15 个，漏消息/nudge/voice/tts/config/close 8+ 端点；mock 样例三处手写重复）
- 残留：`toolboxApi.test.ts`（S3 合并后经深度审计核实**非空壳**，保留并纳入统一 mock source）

**问题**：协议接缝没有收敛点——解析实现 3 遍、测试 reader 工厂 3 份；最核心的消息/TTS/声纹数据路径游离在契约保护之外。

**方案**：`useSilenceNudge` 改为复用 `useSseStream().streamMessage()`（本就只消费 token 类型）；5 个散落端点收进 api.ts 具名函数；`FRONTEND_ENDPOINTS` 扩到 23+；mock 样例单一 source（从 openapi.json 派生或集中定义）；`toolboxApi.test.ts` 非空壳保留，纳入统一 mock source（审计修正：不删除）。

**收益**：局域性（协议解析收敛为单点，reader 工厂可合并）；杠杆（契约防线补上最核心链路，后端改契约 = 前端 23 端点全量报警）；测试（mock 单一源消灭三处漂移）。

**风险**：低——行为不变，纯收敛重构；`useSseStream` 现有 8-9KB 独立测试可保底。

## 8. C5 · ChatRoom 语音编排抽取（神组件）

**强度**：🟨 Worth exploring

**涉及文件**：
- `frontend/student-h5/src/components/ChatRoom.tsx`（715 行 / 13+ hooks / 15 state·ref）
- `frontend/student-h5/src/test/ChatRoom.test.tsx`（548 行 / **18 个 vi.mock**（审计修正 2026-08-05：初版记 15 个，实测 18 个）/ 白盒回调捕获）
- `frontend/student-h5/src/hooks/useWakeEnabled.ts`（34 行半接入）+ `frontend/student-h5/src/components/EmotionSelect.tsx`（L21/L32/L114-118 重新声明同一 localStorage key，无失败安全）
- `frontend/student-h5/src/hooks/useChatSession.ts`（与 ChatRoom 存在 sendMessageRef/recordInteractionRef/stopRecordingRef 跨 hook 接线）

**问题**：UX-006 只拆了 hook 层，编排复杂度全部留在组件：SpeechRecognition 状态机 + 按住说话三指针 + bobo 状态优先级派生 + boBoPet 双实例化 + 录音→分析→自动发送链内联。测试白盒耦合（capturedRecordingCallback 捕获组件内部回调），每加一个 hook 就加一块 mock。

**方案**：把「录音 → voice/analyze → 自动发送」整链抽成 `useVoiceInputPipeline` hook（状态机 + 三指针 + 去重归内），ChatRoom 退化为装配层；EmotionSelect 接入 `useWakeEnabled` 消除第二份偏好读写；测试从白盒回调捕获改为 hook 级黑盒。

**收益**：局域性（语音输入一条链一个文件）；测试（hook 黑盒测试替代白盒 mock，ChatRoom.test 体积可减半）；杠杆（风险最高的录音-发送链路获得独立测试面）。

**风险**：中——ChatRoom 是主路由核心组件，且重构会破坏现有白盒测试（需同步重写）；建议在 C4 之后做（先收敛接缝再抽编排，减少变动面）。

## 9. Top 推荐与实施路线

**Top：C2 · 风险领域知识单一规则源**。理由：
1. 儿童安全红线领域——风险词典与情绪判定的漂移不是维护成本，是漏判危机信号的真实风险（anxious 5 处判定不一致今天就可能发生）
2. 杠杆最大——一个改动全管线传导，一致性断言测试把漂移挡在 CI
3. 风险最低——只读静态数据 + 查找方法，无状态无副作用，TDD 与全量回归可完整覆盖

**建议实施顺序**（依赖关系与成本）：
1. **C3**（快速清扫，零行为损失，可与 C2 并行）
2. **C2**（Top，安全红线）
3. **C4**（前端接缝收敛，独立于后端）
4. **C1**（后端核心路径拆分，回归成本最高，排后）
5. **C5**（依赖 C4，最后）

## 10. 待决策项

| 决策 | 内容 | 选项 | 建议 |
|------|------|------|------|
| D-1 | ORCH-006 缺口：补全画像 P2 字段提取 vs 删除消费端死分支 | 补全 / 删除 | 待议（补全 = 兑现设计意图；删除 = YAGNI） |
| D-2 | C1 拆分范围：是否包含 chatProactive 版本路由对齐 | 含 / 不含 | 含（同一模板体系两条路径是同类摩擦） |
| D-3 | C2 收编范围：4 份词典全收 vs 先收风险分级+情绪集合（主题词随 C1） | 全收 / 分步 | 分步（主题词随 C1 收敛，避免跨候选依赖） |
| D-4 | C5 抽取边界：仅录音→发送链 vs 含 bobo 表情状态机 | 仅链 / 含状态机 | 仅链（表情状态机与动效预算 TTSFX-004 相关，另议） |
| D-5 | 契约 mock 单一 source 形式：从 openapi.json 派生 vs 集中手写 | 派生 / 集中 | 集中定义（派生依赖解析器，KISS 优先） |

## 11. 验收标准（EARS 风格）

**C2**：
- 当 `RiskKeywordRegistry` 与 `EmotionVocabulary` 建立后，所有风险判定/情绪判定消费点必须引用注册表（grep 断言零散词典残留）
- 当新增「注册表全量断言」测试后，四级风险词典互斥、情绪集合判定一致（正面/负面/未知三分类）必须通过
- 当全量回归运行时，后端测试与前端测试必须与合并基线一致（1529 用例全绿，不新增失败）
- 当词典增删发生时，所有消费管线必须无需改动即传导（接口不变原则）

**C3**：
- 当清理完成后，`CounselingSession.end()`/`upgradeRiskLevel()`、`PromptOrchestrationService.resolve()`、`SessionEndAnalyticsService.fuseEmotions()` 必须为零调用
- 当 `ProfileSignals` 收缩或补全后，接口字段必须与生产实际赋值一一对应（无恒 null 字段）
- 当僵尸参数 `profilePrompt` 删除后，`AiChatService` 接口签名必须减少 1 参

**C4**：
- 当 `useSilenceNudge` 改为复用 `streamMessage()` 后，协议解析必须为单点实现（grep 无第二份 `line.slice(5)`）
- 当 4 个端点收进 api.ts 后，`FRONTEND_ENDPOINTS` 必须 ≥23，且裸 fetch 归零
- 当契约测试运行后，消息/nudge/voice/tts/config/close 端点必须全部在案

## 12. Out of Scope

- 主文档（design/01-12）任何修改——开发期冻结，本方案合并时统一并入
- 领域行为变更（风险分级标准、情绪定义本身不调整，只收敛存放位置）
- 世界 B 编排（DEC-CBT 已决删除，不复活）
- design/52 已映射任务（ORCH/PEVAL/KB/MEM 系列已实施完成，不重复登记）
- 前端 UI 视觉/交互重构（非结构摩擦）
- 13 领域词汇表（原 CONTEXT.md）不在本方案合并范围（常驻设计目录）

## 13. 关联与落点

- 关联 TASK-TRACKER：待登记 ARCH-001（本方案实施时按 §9 顺序拆分子任务）
- 关联设计：design/04 风险识别规则库（C2 规则清单同步源）、design/44 个性化提示词动态编排（C3 ORCH-006）、design/16 API 接口设计 + design/05 §8.6 契约防线（C4）、design/37 情感化 TTS（C5 表情状态机边界）
- 词汇表：[13 领域词汇表](../13_领域词汇表.md)
