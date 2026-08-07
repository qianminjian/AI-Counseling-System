# doing/76 全项目深度审计整改方案与 SPEC（架构审计 + 代码实现深度分析 + 整改规格）

> 编号：DOC-072 | 创建：2026-08-07 | 状态：✅ 已实施（T1-T5 全部完成，2026-08-08 后端全量回归通过）
> 来源：独立审计 agent 全项目深度审计报告（2026-08-07 23:05），本文档为审计结果落档 + 代码级深度分析 + 整改 SPEC 细化
> 前置文档：design/BEACON.md（决策基线）、design/DESIGN-OVERVIEW.md、design/TASK-TRACKER.md

---

## 1. 审计概述

| 维度 | 数据 |
|---|---|
| 审计范围 | design/ 基线 3 份 + 后端 6 模块（Java 380 文件，主代码 237 + 测试 143，@Test 1573 个）+ 前端 3 端（227 文件，测试文件 104）+ Python 2 服务（11 文件）+ CI/部署链路 |
| 审计方法 | 设计基线比对 → 逐模块代码核查 → 调用链 grep/LSP 验证（死代码判定全部经调用链验证）→ 设计文档四态（🟩已落地/🟧部分/🟫受控/⬜未实现）与代码逐项对照 |
| 审计深度 | 核心链路（对话/安全/多租户/声纹/WebSocket）全量深读；12 个 Controller 抽查；BEACON 决策 #5-#27 逐条对照 |
| 判定原则 | 满足设计需求 > 实打实可用 > 拒绝过度设计；冻结任务（frozen/）不扣分；"设计已完成而代码未落地"必扣分 |

**核心结论**：经多轮治理（AUD-001~071）的高质量项目，核心链路全部真实落地、无虚化项；问题集中在：**文档数字失实（违反底线规则）**、**Controller 层贫血模型（分层渗透）**、**危机热线硬编码（SAFE-203 未闭环）**、**SessionState 并发非原子**、**僵尸目录残留**。

---

## 2. 审计评分（苛刻悲观口径）

| 维度 | 评分 | 评语 |
|---|---|---|
| 架构合理性 | 72 | 模块依赖严格线性无环、行级隔离 fail-fast 设计精良；12 个 Controller 直接注入 Mapper 写 SQL 的贫血模型是硬伤——安全边界退化为"靠 Controller 手写租户条件自觉" |
| 代码质量 | 82 | 错误处理/降级/资源管理/命名上乘、零 TODO；危机热线硬编码、话术模板硬编码于 Controller，魔法值治理不彻底 |
| 工程化规范 | 88 | CI 四道门禁完备（单测+覆盖率 45%/60% 强制+Trivy CRITICAL/HIGH 阻断+冒烟）；jacoco 未按模块差异化、Python 服务无覆盖率门禁 |
| 团队协作友好度 | 85 | 文档四态体系诚实（未实现即标⬜）；BEACON 三处数字失实直接违反自身最高优先级底线规则 #12 |
| 代码逻辑虚化度（反向） | 90 | 核心链路全部真实落地且经调用链验证；唯一未落地项（SAFE-203 热线租户化）文档如实标注；扣分项为热线硬编码与"文档滞后于代码"的反向失实 |

**生产就绪结论：有条件上线**（已在生产运行）。上线前必须完成：P0×2 + P1×3。

---

## 3. 深度代码分析（P0/P1 逐项代码证据）

### 3.1 P0-1 危机热线硬编码，SAFE-203 未落地

**代码证据**（已核实）：

- `backend/counseling-ai/src/main/java/com/mindsafe/ai/safety/OutputContentFilter.java`
  - L19 注释：`发射安全话术替换后续内容（自伤/伤人类追加危机热线 400-161-9995，硬编码不交给 LLM）`
  - L36：`static final String CRISIS_HOTLINE = "400-161-9995";`
  - L109：危机话术拼接 `+ CRISIS_HOTLINE + "，会有专业的老师帮助你。你不是一个人。💙"`
- design/09 SAFE-203（热线租户化）标注 ⬜ 未实现

**根因分析**：热线在安全组件内为编译期常量，绕过配置体系。SaaS 多租户下不同地区/学校帮扶渠道不同，硬编码热线可能非学生所在地有效求助渠道——安全响应链路在**最关键落点失效**。

**生产影响**：危机场景向未成年人展示错误/无效求助热线 = 内容安全体系唯一未闭环环节。

### 3.2 P0-2 BEACON 三处数据失实（违反决策 #12 底线规则）

**代码证据**（已核实）：

| BEACON 声称 | 代码实际 |
|---|---|
| L36 决策 #5：`Spring Boot 3.4` | `backend/pom.xml` parent = **3.5.12** |
| L66：`7 Maven 模块` | 父 pom `<modules>` 仅注册 **6 模块**（common/domain/ai/service/api/app，无 counseling-tenant） |
| L66：`32 个 DB 迁移（V1-V26 + V28-V32，V27 跳号）` | 迁移目录实际 **33 个**：`V27__cleanup_seed_data.sql` 与 `V33__risk_event_structured_score.sql` 均存在，**无跳号** |

**根因分析**：版本升级/模块合并/迁移新增后未回写 BEACON（"文档与代码一致"底线规则的执行漏洞）。BEACON 是交接/运维排障第一入口，数字失实导致 Flyway 排查误判、新成员找不到 tenant 模块、依赖安全评估（3.4 vs 3.5.x 安全公告不同）误判。

### 3.3 P1-1 Controller 层直接操作 Mapper（分层渗透/贫血模型）

**代码证据**（抽查核实）：`TeacherController.java` L51-54 注入 4 个 Mapper，L177 会话归属校验、L210 `updateById` 接管状态、L290 `selectPage` 直接分页。同类 12 个 Controller：SessionController:29、ParentController:40-42、AdminController:44-48、AdminPromptController:35-37、EmotionDiaryController:24、ToolboxController:37、RelaxationController:29、VoiceprintController:46、AuthController:47、WeComOAuthController:48、AdminUserController:30。

**根因分析**：租户隔离安全条件（`eq(User::getTenantId, ctx.tenantId())`）靠每个 Controller 手工复写 `LambdaQueryWrapper`，**无 Service 层强制**——一旦某端点漏写租户条件即产生 IDOR。AUD-001（声纹 verify 全库比对）正是此模式的历史教训。

### 3.4 P1-2 SessionState Redis read-modify-write 非原子

**代码证据**（已核实）：

- `RedisSessionStateStore.java` L54-57 `save`：整对象 Jackson JSON `opsForValue().set`；L71-73 `get`：JSON 反序列化
- `SessionState.java` L22-23 注释：`线程安全说明：同一会话同一时刻仅一个 SSE 流写入（学生端单连接），nudge 与 message 互斥（前端保证），无需分布式锁`

**根因分析**：get→改→save 整对象读改写非原子。nudge（定时触发）与 message（用户触发）是**两条独立请求路径**，前端互斥无法保证服务端两请求时序；多实例部署时丢失更新概率显著上升（nudge 计数/topicHints/情绪历史并发丢失 → 冷场决策模型输入失真）。

### 3.5 P1-3 counseling-tenant 僵尸目录残留

**代码证据**（已核实）：`backend/counseling-tenant/` 仅剩空 `src/`（0 文件）+ `target/`（jar/class/jacoco 报表残留，含 `MindSafeTenantLineHandler.class`）；未注册父 pom modules；git 历史 f02a0fb4 已删源码（源码已迁 counseling-domain）。

**生产影响**：无运行影响（不参与构建），但污染搜索/审计/交接，旧 jar 有被误打包误部署风险。

---

## 4. 设计 vs 实现差距表（审计抽样结论）

| 设计承诺（引用） | 代码实际 | 判定 |
|---|---|---|
| BEACON #5 Spring Boot 3.4 | pom = 3.5.12 | ❌ 文档失实（P0-2） |
| BEACON #5 三端 React19+Vite8+TS | student/teacher 落地；parent 为 Taro（迁移已知项） | ✅ 已落地 |
| BEACON #6 Schema 级隔离 | 行级隔离（MindSafeTenantLineHandler + TenantContextHolder + fail-fast） | ✅ 已落地（定稿演进） |
| BEACON #7 LLM 多供应商主备 | ResilientChatModel + LlmStreamEnhancer 重试 | ✅ 已落地 |
| BEACON #8 模块化单体 7 模块 | 父 pom 仅 6 模块 | ❌ 文档失实（P0-2） |
| BEACON #10 双层输出审查 + PII 脱敏 | OutputContentFilter(Layer1) → OutputReviewService(Layer2 四决策) → applyLayer2Recall（召回替换落 ChatMemory + risk_events 留痕）；PiiDesensitizer 真实调用 | ✅ 已落地（全链路验证） |
| BEACON #16 Whisper 本地唤醒 + nudge 决策模型 | wakeWord.ts + NudgeDecisionModel 6 信号 + SessionState.canNudge 护栏 | ✅ 已落地 |
| BEACON #22 CTX-Agent 4 段简报 | ConversationContextAgent.buildContextBrief（零 LLM、占位符检测） | ✅ 已落地 |
| BEACON #23 声纹双模式 | VoiceprintVerifyService（租户维度 + 阈值 0.70/0.55） | ✅ 已落地（AUD-001 已修复） |
| BEACON #24 TTS 7 音色×8 方言 | config.yaml 矩阵 + emotion_instruct_map + 三级降级 | ✅ 已落地 |
| design/09 SAFE-203 热线租户化 | OutputContentFilter 全局硬编码 | ❌ 虚化（文档如实标⬜，P0-1） |
| design/09 PEVAL-003 评估回归集进 CI | 未见 LLM-as-Judge 回归集 | ⬜ 未落地（文档如实标） |
| design/09 ALLY-201 连续性开场 | 代码已实现 | 🟧 部分（**文档滞后于代码**，需回写 🟩） |
| design/34 量表暂缓施测 | AssessmentScoringEngine 已开发、RecurrenceCalculator 显式冻结（AUD-063） | ✅ 符合决策（受控保留） |
| design/10/11 教师订正/标注回流/预约域/测评管理 | 无对应生产代码 | ⬜ 未落地（文档如实标，60+ 项） |

**判定结论**：无虚假完成项；虚化 1 项（SAFE-203）；反向失实 1 项（ALLY-201 文档滞后）；失实 3 处（BEACON 数字）。

---

## 5. 死代码与过度设计分析

### 5.1 死代码清单（全部经调用链验证）

| 位置 | 符号 | 证据 | 处置 |
|---|---|---|---|
| `backend/counseling-tenant/`（整目录） | target 内 MindSafeTenantLineHandler.class 等 | 空 src、未注册 pom、git f02a0fb4 已删源码 | **删除整目录**（T2） |
| RecurrenceCalculator | @Component 零生产调用 | 注释自证 AUD-063 显式冻结、design/03 登记 | **受控冻结，保留**（量表接线时恢复，不扣分） |
| 3 个 tracker（Profile/PromptEval/VoiceEffectiveness） | 曾疑死代码 | 均有真实调用链（SessionEndAnalyticsService / AdminPromptController / TtsController） | 非死代码，保留 |

### 5.2 过度设计质疑

| 设计点 | 质疑 | 结论 |
|---|---|---|
| SessionState 279 行 + 每轮 Redis 整对象 JSON | 单实例下全量读改写双倍成本 | 保持现状，但并发修复（T5）时 nudge 计数改独立键 |
| Nudge 6 信号评分卡 + 策略层 + 双层护栏 | MVP 冷场配四层结构 | 评分卡+硬规则可合并纯函数；策略层留作 A/B 扩展位（YAGNI 边缘，可保留） |
| 双 ThreadLocal（CURRENT+SYSTEM_SCOPE） | 复杂度高 | **不简化**——行级隔离 fail-fast 必需，有 A1/A3 修复记录 |
| LLM 主备+重试+TTS 三级降级+ASR 双引擎 | 多层降级看似重 | **不简化**——生产事故实战沉淀，加分项 |
| 世界 B（双世界编排） | 职责重叠 | **已被决策 #19 正确删除**（c9af478），治理闭环正面案例 ✅ |

---

## 6. 整改 SPEC（任务分解）

### T1（P0-2）BEACON 数字失实修正 —— 文档同步，低风险，5 分钟 ✅ 已完成（2026-08-08）

- **目标**：BEACON 三处数字与代码实际一致，恢复底线规则 #12
- **改动文件**：`design/BEACON.md`（L36 决策 #5、L66 概要数字）
- **方案**：
  1. L36：`Spring Boot 3.4` → `Spring Boot 3.5.12`（与 backend/pom.xml parent 一致）
  2. L66：`7 Maven 模块` → `6 Maven 模块`（common/domain/ai/service/api/app）
  3. L66：`32 个 DB 迁移（V1-V26 + V28-V32，V27 跳号）` → `33 个 DB 迁移（V1-V33）`，更新括号内注释（V27 清理种子数据 / V28 声纹 / V29 dialect / V30 知识库审核 / V31 risk_notify_outbox / V32 加密扩容 / V33 risk_event_structured_score）
  4. 同步检查 DESIGN-OVERVIEW / TASK-TRACKER 中同类数字（若有）
- **验收**：BEACON 数字与 pom.xml、迁移目录实际一致；git grep 无残留"7 Maven 模块"旧值
- **测试**：无需（纯文档）
- **工作量**：0.1 人日

### T2（P1-3）删除 counseling-tenant 僵尸目录 —— 红线（删目录需项目负责人确认） ✅ 已完成（2026-08-08，获授权后执行）

- **目标**：清除僵尸目录与编译产物，消除搜索/审计/交接污染
- **改动**：`rm -rf backend/counseling-tenant/`（源码已在 counseling-domain，无历史价值）
- **前置确认**：①git 历史 f02a0fb4 确认源码已迁 domain ②全库无 `com.mindsafe.tenant` 引用（grep 验证）③.gitignore 已含 `target/`（防重建）
- **验收**：目录消失；`git grep "counseling-tenant"` 无构建引用；`mvn -q compile` 通过（父 pom 本就不含此模块）
- **测试**：后端全量单测（1573 个）回归
- **风险**：极低（不参与构建）
- **工作量**：0.1 人日

### T3（P0-1）危机热线租户化 —— 安全组件，中风险，需产品决策 ✅ 已完成（2026-08-08）

- **目标**：危机话术热线按租户配置，缺省回退全国心理援助热线；关闭 SAFE-203 台账
- **方案**：
  1. **配置链路**（优先复用现有 CFG-001）：`SystemConfigProperties` 增加 `safety.crisis-hotline`（默认 `400-161-9995` 全国热线）；确认 tenant 维度配置能力（system-config.yml 或租户表扩展字段，按现有配置体系选型，避免新造轮子）
  2. **安全组件注入**：`OutputContentFilter` 的 `CRISIS_HOTLINE` 常量改为从配置属性读取；保留编译期常量作为缺省兜底（配置缺失/加载失败时仍能出话术——安全组件不允许 fail-fast 阻断危机响应，**降级方向必须兜底可用**）
  3. **话术拼接点**：L109 使用注入值
  4. **关闭台账**：design/09 SAFE-203 状态 ⬜ → 🟩
- **验收**：单测覆盖（缺省热线/租户配置热线/配置缺失兜底三场景）；危机拦截话术输出租户热线；生产配置默认全国热线
- **测试**：OutputContentFilter 单测扩展；配置链路 IT
- **风险**：安全组件改动需回归全链路（Layer1 硬过滤 + 召回替换）
- **工作量**：0.5-1 人日（含配置体系确认）

### T4（P1-1）Controller Mapper 下沉 Service —— 重构工程，高工作量，分批 ✅ 已完成（批次 A/B/C，2026-08-08）

- **目标**：消灭 Controller 层直接 SQL；租户隔离条件收敛至 Service 层强制
- **方案**（分批，先高危后低危）：
  - **批次 A（先行）** ✅：会话归属校验类（TeacherController、SessionController）抽公共 Service 方法 `sessionBelongsToTenant(sessionId, tenantId)`（已有 SecurityContext 校验者合并）；涉及金额/隐私/跨租户风险端点为最高优先
  - **批次 B** ✅：状态更新类（TeacherController 干预状态接管、AdminController 等 11 个）下沉至对应领域 Service（14 个 Controller、58 处 Mapper 调用清理）
  - **批次 C** ✅：查询分页类（selectPage 直查）下沉（getSessionHistory/pageRiskEvents/abComparison 等）
  - **新端点纪律** ✅ 已落地：Controller 禁止注入 Mapper（code-engineering.md §3.5 补充规则 + commit-msg hook grep 拦截，正/反向测试验证）
- **验收**：`grep -r "private final .*Mapper" counseling-api/**/controller/` 为 0（已验收通过，仅剩 Jackson ObjectMapper 例外）；租户条件单点实现（Service 层公共方法）；全量单测回归 + Controller 层测试适配（10 个测试文件改造，282 个 Controller 测试全绿）
- **风险**：重构面大（12 个 Controller），需分批评审；行为保持（重构不改语义）
- **工作量**：3-5 人日（分批交付）

### T5（P1-2）SessionState 并发原子化 —— 中风险，水平扩展前置 ✅ 已完成（2026-08-08）

- **目标**：消除 get→改→save 非原子丢失更新
- **方案**：
  1. nudge 计数改 Redis 独立计数器键（`INCR` 原子）或 Lua 脚本原子读改写
  2. topicHints/情绪历史等复合字段：版本号 CAS（save 时带 version，`WATCH/MULTI` 或 Lua 比较后写）——优先最小改动：仅对**并发敏感字段**（nudgeCount、lastNudgeAt）原子化，其余字段保留整对象读写（单实例语义不变）
  3. 更新 SessionState.java L22-23 注释（删除"前端保证互斥"的前提声明，改为"计数原子化，复合字段接受单实例语义"）
- **验收**：并发单测（模拟 nudge+message 并发写，验证计数不丢失）；注释与实际一致
- **测试**：RedisSessionStateStore 并发测试（可用 embedded redis 或 mock）
- **风险**：低（改动面收敛至 store 层）
- **工作量**：1-1.5 人日

### T6（P2/P3 登记项）—— 可选，不阻塞发布

| # | 级别 | 项 | 建议 |
|---|---|---|---|
| 6-1 | P2 | SecurityConfig:84 `/api/v1/parent/**` permitAll | 家长端稳定后统一进 JWT 过滤器链 |
| 6-2 | P2 | TeacherController 话术模板 7 条硬编码 | 迁移 DB 表 + 管理端 CRUD（design/16 已规划） |
| 6-3 | P3 | OutputReviewService:39 模板加载失败仅 error 不 fail-fast | 启动告警或 fail-fast |
| 6-4 | P3 | SessionState THINKING_CUES 中文关键词硬编码 | 收敛配置/词库 |
| 6-5 | P3 | ConversationServiceImpl 24 依赖注入 | 按聚合边界拆 2-3 门面（参考 SessionEndAnalyticsService） |
| 6-6 | P3 | ci.yml Python 服务无覆盖率门禁 | pytest-cov + 阈值 50% 起步 |

### T7 补审专项（审计受限区域）—— 建议下一轮专项

- 前端 90+ 组件逐页对照 design/17/19 页面规格（需运行时环境验证）
- Python 服务（tts-service/voice-service）源码深读（11 文件）
- 33 个迁移 SQL 与实体映射全量比对
- deploy.sh 后半段（回滚/健康检查执行逻辑）全读

---

## 7. 生产就绪结论

**结论：有条件上线**（已在 yun.gxjugu.com 生产运行，评估基于"继续演进"视角）。

**已达标**：核心业务链路全真实无虚化；安全体系（双层审查+召回替换+PII 脱敏+行级隔离 fail-fast）超同类 MVP；CI 门禁与 1573 后端测试；部署通道唯一化。

**上线前必须完成（按序）**：T1（BEACON 修正）→ T2（僵尸目录）→ T3（热线租户化）→ T4（Controller 下沉，分批）→ T5（SessionState 原子化）。

---

## 8. 附录：审计可信度声明

**深入审计区域**（证据充分）：后端核心链路全读（ConversationServiceImpl 751 行 / AiChatServiceImpl 486 行 / OutputContentFilter / OutputReviewService / OutputSafetyReporterImpl / MindSafeTenantLineHandler / SessionState / VoiceprintVerifyService / SecurityConfig / WebSocket 三件套 / 3 tracker 调用链）；BEACON 全文 + DESIGN-OVERVIEW + TASK-TRACKER + design/09/10/11 四态统计；ci.yml 全读；测试规模精确计数。

**探索受限区域**（置信度较低）：前端仅抽查组件接线（useChatSession/ChatRoom/useAlertWebSocket）；Python 服务仅见配置与 CI；迁移 SQL 未逐条核对；deploy.sh 后半段未全读。建议按 T7 补审。

---

_文档编号 DOC-072（接续 DOC-071/frozen74）| 审计落档：2026-08-07 | 整改实施需项目负责人确认立项（T2 删除目录为红线操作）_

---

## 9. 实施记录（2026-08-07 ~ 2026-08-08）

| 任务 | 结果 | 证据 |
|---|---|---|
| T1 BEACON 修正 | ✅ | 三处数字与 pom.xml / 迁移目录一致，git grep 无旧值残留 |
| T2 删 counseling-tenant 僵尸目录 | ✅ | 目录删除 + 全库引用验证无残留，构建不受影响 |
| T3 危机热线租户化 | ✅ | 配置化（缺省兜底）+ 三场景测试 + design/09 SAFE-203 → 🟩 |
| T4a 会话归属校验下沉 | ✅ | 公共 Service 方法 sessionBelongsToTenant |
| T4b 状态更新类下沉 | ✅ | 14 Controller 改造（58 处 Mapper 清理）+ 10 个测试文件适配 |
| T4c 查询分页下沉 + 新端点纪律 | ✅ | grep 验收为 0（ObjectMapper 例外）；code-engineering.md §3.5；commit-msg hook 正/反向验证通过 |
| T5 SessionState 原子化 | ✅ | Lua INCR 原子计数 + RedisSessionStateStoreTest 18 个通过；ConversationServiceImplTest 补 tryNudge stub |
| 全量单测回归 | ✅ | 6 模块 1783 个单测全绿（`mvn test` EXIT=0；Controller 层 282 个全绿） |
| git 提交 | ✅ | 9 个原子提交（bb8d16c..cf4d203，按 T1/T3/T4×5/T5/文档拆分，全部 ≤15 文件） |
| git push | ✅ | origin/main 已同步（cf4d203，2026-08-08） |
| 纪律钩子自检 | ✅ | commit-msg hook 拦截生效；--staged 三处 bug（缺 shift 死循环/位置参数误判/哨兵值双冒号）已修复并正反向验证 |
