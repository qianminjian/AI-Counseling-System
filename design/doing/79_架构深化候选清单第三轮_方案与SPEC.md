# doing/79 架构深化候选清单（第三轮·未覆盖区）方案与 SPEC

> 状态：✅ 已实施（2026-08-08，全部 23 候选完成；实施记录见 §32）；剩余 BA-06 随 frozen/38 议决、DA-04/DA-05 独立议决（待议决项，非本清单实施缺口）
> 登记编号：DOC-074（2026-08-08，接续 DOC-073）
> 触发方式：/improve-codebase-architecture（参数：已经审计过的部分排除在外，剩余部分审计）
> 基线去重：DC-001~012（his/72）、B1~B6/F1~F6/D1~D6（his/77，13 候选在 doing/78 待议决）、AUD-001~071（his/71）、DOC-072 T1-T5（his/76）、ARCH-001~010（his/61~70）——以下 22 候选全部为**未覆盖的新摩擦点**

---

## §1 审查概述

### 1.1 方法与范围

- **探索**：3 路并行 agent（后端 Java 六模块 / 前端三端+shared / Python 双服务+部署链路），git 历史热区定位（近 45 条提交集中在 DOC-072 T1-T5、parent-h5 Taro、doing/75 风格统一）
- **深度验证**：全部 Strong（4 项）与关键 Worth 候选经**代码级证据复核**（grep 行号锚点 + 文件直读），0 处 agent 报告失实；DA-01 经 Dockerfile/文件清单/import 三方确认，BA-02 经 Controller/SecurityConfig 直读确认
- **评估词汇**：module / interface / depth / seam / adapter / leverage / locality + 删除测试（deletion test）
- **约束**：只读审计，未修改任何代码；未跑测试（全量回归需串行且昂贵）

### 1.2 候选总览

| # | 候选 | 强度 | 一句话 |
|---|------|------|--------|
| BA-01 | TTS 播放/降级策略「API 化」暴露 | 🟢 Strong | 前端本地职责被包成 2 个 HTTP 端点，300+ 行纯函数生产零调用 |
| BA-02 | 教师导出/周报绕过班级范围隔离 | 🟢 Strong | exportStudents/weeklyReport 传 null=全校，绕过 P1 resolveClassScope 修复（越权面） |
| BA-03 | 工具箱 mood-check 假功能 + 徽章双实现 | 🟡 Worth | 前端已调、后端只 log 不落库（S3 数据丢失）；rewardBadge 零消费点 |
| BA-04 | MessageSummary 实体固化摘要策略 | 🟡 Worth | entity/service split-brain + 手工拼 JSON 绕过 TypeHandler + topicTags 恒 "[]" |
| BA-05 | RAG groundedness 指标语义失真 | 🟡 Worth | 把「请求条数/返回条数」当「检索数/引用数」，score 恒 {0,0.33,0.67,1} 伪信号 |
| BA-06 | EntitlementFilter 权益映射漂移 | 🟡 Worth | FEAT 映射到不存在的 /admin/export 路径，BASIC/PREMIUM 分支恒不可达 |
| BA-07 | ToolboxService 浅模块 | ⚪ Speculative | 26 行仅包 selectById，与 AuthUserService 同构 |
| BA-08 | 告警体系闲置 + DataAnalytics 未接线 | ⚪ Speculative | outbox 兜底只 log；报告三端点前端零消费；双 trend 方法重复 |
| FA-01 | 风险等级常量 6 处重复定义 | 🟢 Strong | 0 级缺失渲染 undefined / 1-2 级同色 / 0='安全' vs '绿色' 语义分歧 |
| FA-02 | 沉浸式主题色板 THEME_STYLES 双副本 | 🟢 Strong | 14 字段逐字重复 + isDark 散落三处，新主题改漏即白字白底 |
| FA-03 | teacher-web ECharts 集成双实现 | 🟢 Strong | 全量导入抵消按需注册（~1MB 包体），生命周期两套 |
| FA-04 | 会话回放抽屉重复 + 竞态 | 🟡 Worth | QualityPanel 版无 cancelled 守卫，旧响应可覆盖新会话 |
| FA-05 | muted 静音设置状态碎片化 | 🟡 Worth | 两页各持一份 state 无持久化，「设置了不生效」 |
| FA-06 | ChatRoom 神组件 595 行 | 🟡 Worth | 7+ UI 面板/弹窗状态内联，测试 mock 面大 |
| FA-07 | 三端 ErrorBoundary 语义分歧 | ⚪ Speculative | student 有 retry，teacher/parent 仅刷新，日志逐字重复 |
| FA-08 | OverviewPanel 双 load 函数 | ⚪ Speculative | 逐字重复，重试路径无 cancelled 守卫 |
| DA-01 | tts Dockerfile 漏拷引擎模块 | 🟢 Strong | 下次 TTS 发布必然 crash-loop（确定性生产事故） |
| DA-02 | voice 就绪指标零消费方 | 🟢 Strong | ready gauge 无告警、/health 恒 UP、SER 静默降级不可见 |
| DA-03 | tts/voice 指标暴露 + CORS + lifespan 重复 | 🟡 Worth | ~90 行逐字重复，指标契约无单源 |
| DA-04 | CI 重构静默移除 E2E 冒烟门禁 | 🟡 Worth | smoke-test.sh 31 断言零执行点，Playwright CI 分支死配置 |
| DA-05 | tests/unit/scripts 六件套无运行入口 | 🟡 Worth | 覆盖运维关键路径的测试存在但永远不执行；integration 空目录误导 |
| DA-06 | prepare-models.sh --verify 自称「CI 门禁用」 | ⚪ Speculative | 唯一校验零自动消费方，模型 404 静默失效 |
| DA-07 | CI 对 4 份 Dockerfile 零构建验证 | ⚪ Speculative | 镜像错误只能部署期暴露（DA-01 即活证据） |

---

## §2 BA-01 TTS 播放/降级策略「API 化」暴露 🟢 Strong

### 2.1 深度代码分析（已验证）

- `TtsController.java` L205-221（`/pipeline/schedule`）、L228-246（`/effectiveness/evaluate`）：L212 构造 task 时 `sentenceIndex == 0` 由**前端自己传入**（回显前端已知结论）；L231-236 指标（totalSessions/avgCompletionRate/manualSwitchCount）全由前端自报；L239 `VoiceMetrics` 6 字段中 2 个恒传 0
- `TtsPipelineScheduler.java` L64-66：`fromCache` 恒 false，「预合成缓存命中」分支死路径；L16 注释自证「纯函数实现，接线时由前端帧率检测 + TTS 调度消费」
- `VoiceEffectivenessTracker.java` L107/L138/L167：`canSwitchInSession`/`canEvolveAcrossSessions`/`suggestRuleEvolution` 生产零调用
- `VoiceDegradationPolicy.java` L78/L89：`nextFallback`/`usePreSynthesized` 生产零调用
- 交叉验证：grep 前端 `pipeline/schedule|effectiveness/evaluate` 仅命中 `__contract__/openapi.json` 契约登记，**无任何真实调用**

### 2.2 Problem / Solution / Benefits

- **Problem**：前端本地应执行的播放调度（首句即播、帧率采样降级）被错误放到后端并包成 HTTP 端点——cross-seam leak + 假功能（测试绿、生产死）
- **删除测试**：删掉后复杂度**消失而非移动**——前端本就掌握首句/帧率信息，后端无不可替代状态
- **Solution**：删除两个端点 + 三个 tts 类；调度/降级以约 50 行前端逻辑实现；若需效果回收由后端会话结束链路自行统计（session 表有真实数据）
- **Benefits**：局域性↑（消除无意义 HTTP 往返与假数据源）；杠杆↑（前端播放体验修复不再依赖后端发版）；删 3 个只测纯函数的类

---

## §3 BA-02 教师导出/周报绕过班级范围隔离 🟢 Strong

### 3.1 深度代码分析（已验证）

- `TeacherController.java`：`exportAlerts`（L263）正确走 `teacherService.resolveClassScope(...)`（P1 审计修复注释「班主任仅导出本班」）；**`exportStudents`（L287）传 `listActiveStudents(ctx.tenantId(), null)`**；**`weeklyReport`（L353）传 `getStats(ctx.tenantId(), null)`**——null = 全校
- `SecurityConfig.java` L103：`/api/v1/teacher/**` 允许 `CLASS_TEACHER`（班主任）访问
- `TeacherService.java` L91：`resolveClassScope` 注释「P1 审计修复：班主任未绑定班级 → 返回空范围，不再全校可见兜底（防数据越权）」

### 3.2 Problem / Solution / Benefits

- **Problem**：同一 Controller 内「班级范围」互相矛盾——刚做过越权修复的域，两个导出/周报端点以 `classScope=null` 重新打开越权面：**班主任可导出全校学生名单 + 全校统计周报**
- **Solution**：导出/周报端点统一走 `resolveClassScope`；TeacherService 改为方法内强制解析班级范围（签名不再接收可空 classScope），null 语义收敛为显式 `ClassScope.ALL` 配合方法级权限注解
- **Benefits**：局域性↑（范围判定单点）；测试——TeacherClassScopeTest 扩展到导出端点；一次修复闭合 2 个越权面
- 深度 SPEC 见 §22

---

## §4 BA-03 工具箱 mood-check 假功能 + 徽章双实现 🟡 Worth exploring

### 4.1 深度代码分析（已验证）

- `ToolboxController.java` L84-119：前端已调用 `POST /toolbox/mood-check`（student api.ts L378 + 契约测试），端点只 `log.info`（L103）后返回——**零持久化**；L81 注释声称「效果数据为 S3 级（进画像 + design/39 实验指标）」，L106 注释自证「后续接 MEM-103」
- `ToolboxRegistry.java` L51-60：5 工具声明 `rewardBadge`（breathing_star 等），grep 全库**无任何消费点**
- 对比：`EmotionDiaryService.getAchievements`（L91-108）已接线、有单测——成就/徽章概念双实现

### 4.2 Problem / Solution / Benefits

- **Problem**：S3 级数据只有日志一条落点（学生练习前后情绪记录丢失）；徽章概念两处各自实现
- **Solution**：二选一——接会话结束链路把 mood-check 落库（复用 RelaxationSession 或新表），或删端点并入放松训练计数；徽章统一为单一 `BadgeService`（diary + 工具同一评估入口）
- **Benefits**：局域性↑（成就判定单点）；假功能删除后不再需要「mock 纯函数 + verify 只调 log」的伪测试

---

## §5 BA-04 MessageSummary 实体固化摘要策略 🟡 Worth exploring

### 5.1 深度代码分析（已验证）

- `MessageSummary.java` L79-99：`studentMessage` 静态工厂内嵌「riskLevel ≥ 2 原文保真 / < 2 语义提炼」分支 + `truncate` + **手工字符串拼 JSON**（L94 `"[\"" + emotionLabel + "\"]"`、L95 `"[{\"level\":" + riskLevel + "}]"`，绕过 JsonbTypeHandler）
- 假功能字段：`topicTags` 恒 `"[]"`（L96/L117）；`suggestedNextAction`（L45）/`cbtFields`（L69）生产从未赋值
- 对比：ARCH-007 已在 service 层收敛两级摘要（MessageSummaryService/SessionSummaryUpdater）

### 5.2 Problem / Solution / Benefits

- **Problem**：摘要策略 split-brain（entity 一份、service 一份）；手工拼 JSON 绕过类型处理器；三个字段恒 null/空——删除测试：entity 内策略删除后复杂度上移 service 层**集中**（当前是分散）
- **Solution**：策略判断收敛到 service 层单一入口；JSON 拼串改用注入的 ObjectMapper/JsonbTypeHandler；未接线字段从实体删除或显式赋值（删列需走 migration）
- **Benefits**：改提炼规则不再同时改两个层；实体工厂逻辑被 service 单测取代

---

## §6 BA-05 RAG groundedness 指标语义失真 🟡 Worth exploring

### 6.1 深度代码分析（已验证）

- `HybridRetrievalService.java` L133-152：`evaluateGroundedness(sessionId, retrievedChunks, citedChunks)` 纯函数
- `RagAdvisorService.java` L148-149：唯一生产调用点传 `retrievedChunks=FINAL_TOP_K(=3)`、`citedChunks=chunks.size()`——把「请求条数」当检索数、「返回条数」当引用数；因 `chunks.size() ≤ 3` 恒成立，score 只可能 {0, 0.33, 0.67, 1.0}，低分日志（L151-152）是**伪信号**
- `identifyContentGaps`（L170-183）生产零调用（注释「由会话结束异步任务消费」无接线对象）——「未接线纯函数 @Component」模式第三次出现（同 BA-01）

### 6.2 Problem / Solution / Benefits

- **Problem**：groundedness 指标数值无意义（实际测的是「检索返回了几条」而非「回复用了多少」），运营内容补全决策基于失真指标
- **Solution**：由会话结束链路真正计算引用比例（需回复文本分析），或删除评估并移除伪信号日志；`identifyContentGaps` 无消费方则删
- **Benefits**：删除测试通过（删掉复杂度消失）；看板不再基于失真指标

---

## §7 BA-06 EntitlementFilter 权益映射漂移 🟡 Worth exploring

### 7.1 深度代码分析（agent 证据）

- `EntitlementFilter.java` L102-107：`mapPathToFeature` 将 FEAT_EXPORT 映射到 `/api/v1/admin/export`、FEAT_DATA_DASHBOARD 映射到 `/api/v1/admin/dashboard`——**全库真实端点是 `/api/v1/teacher/export/*`、`/teacher/report/weekly`，映射永不命中**；FEAT_ASSESSMENT 无受控路径
- L142-151：`mapStatusToPlan` 只产出 STANDARD/TRIAL 两档，PLAN_FEATURES 中 BASIC/PREMIUM 分支（含 FEAT_DATA_DASHBOARD/FEAT_VOICE_INPUT）**恒不可达**
- L127：每请求 `tenantMapper.selectById` 一次（filter 自动注册 + SecurityConfig 角色授权两套并行拦截）

### 7.2 Problem / Solution / Benefits

- **Problem**：权益键映射到不存在的路径（权益体系死配置）；订阅矩阵一半不可达；每请求一次 DB 开销
- **Solution**：对齐真实端点重写映射，或明确声明权益体系为「冻结远期」并删除 filter；PLAN_FEATURES 裁剪为可达档位（注意：frozen/38 计费配额专题已冻结，本候选仅对齐现状不扩大范围）
- **Benefits**：局域性↑（权益判定与真实 API 面一致）；上订阅功能时不再踩「映射漂移」陷阱

---

## §8 BA-07 ToolboxService 浅模块 ⚪ Speculative

- `ToolboxService.java` L13-25（26 行仅 `findUserById`）与 `AuthUserService.findById`（L59-61）都是 `userMapper.selectById(userId)` 完全同构
- **删除测试**：删掉 ToolboxService 复杂度只是被移动没有集中——典型浅模块
- **Solution**：合并进 AuthUserService（用户查询收敛单点），ToolboxController 保持分层纪律但依赖已有服务

---

## §9 BA-08 告警体系闲置 + DataAnalytics 未接线 ⚪ Speculative

- `AlertService` 生产仅 `SlaEscalationScanner`（L116/L119）一个调用者；`RiskNotifyOutboxService.markDead`（L81-83）本应发告警却只 `log.error`
- `WeComAlertService`（L35-39）用 @Value 私有字段 + 测试反射 setField（可测性缺陷，构造器注入即可消除）
- `DataAnalyticsController` L24 注释自证「前端当前未接线（仅 OpenAPI 快照登记）」——报告生成域三端点整体「服务端就绪、消费端缺席」
- `DataAnalyticsService` L396-408/L410-422：`buildSessionFrequency`/`buildWeeklySessionTrend` 仅 key 名不同的重复实现
- **Solution**：outbox markDead 接入 AlertService；WeComAlertService 改构造器注入；DataAnalytics 三端点声明冻结（同 RecurrenceCalculator 显式冻结 + 去 CI 门禁）或由 teacher-web 排期接线（二态明确）

---

## §10 FA-01 风险等级常量 6 处重复定义 🟢 Strong

### 10.1 深度代码分析（已验证）

| 文件 | 定义 | 语义分歧 |
|------|------|---------|
| AlertQueue.tsx L8-9 | `{3:red, 2:orange, 1:gold, 0:default}` + 标签 0='绿色' | 基线 |
| StudentPanel.tsx L10-11 | 同 AlertQueue | 基线 |
| TodayTodoPanel.tsx L11-12 | 同 AlertQueue | 基线 |
| OverviewPanel.tsx L100-101 | `{3,2,1}` **缺 0 级** | `RISK_COLORS[0]===undefined` 渲染异常 |
| BigScreen.tsx L164-165 | hex 版 `0:'#4caf50'` + 标签 0='**安全**' | 与其余 0='绿色' 标签语义不一致 |
| StatsCharts.tsx L49 | `{1:warning, 2:warning, 3:danger}` | **1/2 级同色**（预警等级视觉语义丢失） |

### 10.2 Problem / Solution / Benefits

- **Problem**：风险等级是核心领域概念（预警/干预/合规依赖），6 个文件各自维护必然漂移，已产生实际渲染 bug 风险
- **Solution**：抽 `teacher-web/src/utils/riskLevel.ts` 单一导出 {0-3 → antdColor/hex/label}，6 处替换；StatsCharts 1/2 级按领域语义修正为两色
- **Benefits**：等级新增/重命名只改 1 处；纯模块可单测；现有测试断言基于最终渲染可平移
- 深度 SPEC 见 §24

---

## §11 FA-02 沉浸式主题色板 THEME_STYLES 双副本 🟢 Strong

### 11.1 深度代码分析（已验证）

- `EmotionDiary.tsx` L24-85 与 `RelaxationExercises.tsx` L31-95：三主题色板前 14 字段逐字相同（含同一色值，如 `muted: 'rgba(186,230,253,0.62)'`）
- `Achievements.tsx` L16：硬编码 `const isDark = themeId === 'ocean' || themeId === 'rainbow'`，与两份 `THEME_STYLES.*.dark` 字段冗余

### 11.2 Problem / Solution / Benefits

- **Problem**：新增主题需同步改 3 个文件，改漏一处即产生深/浅色渲染漂移（白字白底）
- **Solution**：提取 `student-h5/src/theme/immersiveStyles.ts`：THEME_STYLES 单例 + `isDarkTheme(themeId)` helper
- **Benefits**：isDark 判断语义统一；纯函数可单测（三主题字段完整性）

---

## §12 FA-03 teacher-web ECharts 集成双实现 🟢 Strong

### 12.1 深度代码分析（agent 证据）

- `StatsCharts.tsx` L11-37：`echarts/core` 按需注册 + ResizeObserver
- `ProfileRadarChart.tsx` L3：`import * as echarts from 'echarts'` **全量导入**（约 1MB 包体）+ L25-74 `window.resize` 监听 + 手动 dispose

### 12.2 Problem / Solution / Benefits

- **Problem**：同一「init → setOption → resize → dispose」生命周期实现两遍，全量导入抵消 tree-shaking；新图表加入无单一路径
- **Solution**：抽 `useECharts(ref)` hook（统一生命周期），ProfileRadarChart 改 `echarts/core` 按需注册（radar 图）
- **Benefits**：包体收益明确；hook 可独立单测（mount/unmount 清理）

---

## §13 FA-04 会话回放抽屉重复 + 竞态 🟡 Worth exploring

- `QualityPanel.tsx` L24-34/L84-106 与 `StudentPanel.tsx` L14-75（SessionMessagesDrawer）各自实现「getSessionMessages + 消息列表渲染」
- 差异有风险：StudentPanel 版有 `cancelled` 守卫，**QualityPanel 版无守卫**——快速切换会话时旧响应可覆盖新会话内容
- **Solution**：以 SessionMessagesDrawer 为基线抽共享组件（含 SessionSummaryCard + 消息列表），QualityPanel 复用并补守卫

---

## §14 FA-05 muted 静音设置状态碎片化 🟡 Worth exploring

- `EmotionSelect.tsx` L30：`useState(false)` 选择页专用副本；`ChatRoom.tsx` L67-72：`useTtsPlayer` 内部 state 另一份——两页互不感知：选择页开静音 → 进对话仍朗读；对话中静音 → 回选择页 UI 显示未静音
- persona/dialect 因 `useVoicePersona` 持久化到 localStorage 才「间接同步」，**muted 无持久化完全丢失**
- **Solution**：设置偏好（muted/persona/dialect）提升为共享 store（轻量 Context 或 zustand），或最小方案：muted 走 `readLocalStorageSafe/writeLocalStorageSafe` 持久化 + useTtsPlayer 初始值读取

---

## §15 FA-06 ChatRoom 神组件 595 行 🟡 Worth exploring

- 尽管已多次抽离（DC-012 computeBoboState、useChatSession、useSilenceNudge 等），仍集中管理 settingsOpen/toolboxOpen/sosOpen/speakingMsgIdx/voiceNotice/cancelArmed/confirmSwitch/showSatisfaction **7 个 UI 状态** + TTS/唤醒/录音/声纹/满意度/设置/百宝箱/SOS 全链路编排
- 测试需 mock 全部子模块（ChatRoom.test.tsx L73），单测成本与碎片风险高
- **Solution**：面板开合 + 弹窗状态收敛为 `useChatRoomPanels()` hook（或拆 ChatRoomFooter/ChatRoomHeader 子组件），目标压到 <300 行

---

## §16 FA-07 三端 ErrorBoundary 语义分歧 ⚪ Speculative

- student（73 行，含 reset-retry 能力）/ teacher（38 行，仅刷新）/ parent（40 行，Taro 版）三端各写一套；`componentDidCatch` 日志逐字相同（`console.error('[ErrorBoundary]', ...)`）
- **Solution**：shared 提供 ErrorBoundaryBase（getDerivedStateFromError + componentDidCatch + reset），三端仅以 fallback 适配各自 UI（parent 用 Taro 组件）；student 的 reset 能力可下沉三端

---

## §17 FA-08 OverviewPanel 双 load 函数 ⚪ Speculative

- `OverviewPanel.tsx` L45-59（load，供错误重试）与 L62-80（loadOnce，供初次挂载）：同一 `Promise.all([getDashboard(), getHighRiskStudents(), getStats()])` + 三 setState 逐字重复，仅差异是 loadOnce 有 `cancelled` 守卫
- **Solution**：保留单一 load（useCallback）+ mountedRef 守卫，重试复用同函数（删 ~18 行，两路径行为一致）

---

## §18 DA-01 tts Dockerfile 漏拷引擎模块 🟢 Strong

### 18.1 深度代码分析（已验证，三方确认）

- `backend/tts-service/Dockerfile` L20：`COPY app.py config_loader.py config.yaml ./`（仅 3 文件）
- 目录实况：`tts_engines.py`、`tts_policy.py` **存在**（另有 5 个测试文件）
- `app.py` L25-26：`from tts_engines import DashScopeBackend, EdgeBackend` / `from tts_policy import DegradationPolicy, TTSSynthesisFailed`（模块级 import）
- git 追溯：DC-011 模块拆分（5416dd29）与 D1 深合并（d3d2e5e）两次改动均未修正 COPY 行；CI 从不构建镜像（DA-07），3 周未被发现
- `deploy.sh` L83：每次发布 TTS 必经 `docker compose build tts-service` → **下一次 TTS 发布必然 ModuleNotFoundError → crash-loop → TTS 整链 502**

### 18.2 Problem / Solution / Benefits

- **Problem**：确定性生产事故，无任何测试/CI 能拦住
- **Solution**：`COPY *.py .`（或补两文件）；与 DA-07 同批形成「修复 + 结构性拦截」闭环
- 深度 SPEC 见 §23

---

## §19 DA-02 voice 就绪指标零消费方 🟢 Strong

### 19.1 深度代码分析（已验证）

- `voice-service/app.py` L101-108：产出 `voice_asr_ready`/`voice_ser_ready` 两个 gauge；L149-158：SER 模型加载失败 → `emotion_model = None` 静默降级「中性情绪」；L206-210：`/health` **无条件返回 UP**（不反映模型就绪）
- `alert-rules.yml`：voice 仅 `up{job}` 与失败率两条规则，**零规则引用两个 ready gauge**（grep 验证；对比 tts 有 `tts_engine_available == 0` + MindsafeTtsAllEnginesDown 告警 L69-77）
- `service-manager.sh` L88-90：voice 健康检查仅探 /health → 恒绿
- Grafana：仅 `llm-performance.json` 一个仪表盘，backend/tts/voice 业务指标全部无可视化消费方

### 19.2 Problem / Solution / Benefits

- **Problem**：与 D5（tts /health DEGRADED 无下游）同一反模式的 voice 侧镜像——D5 仅登记 tts 侧，voice 整条链路看不见就绪状态，**SER 降级（情绪识别功能全损）零可见性**
- **Solution**：补 `voice_ser_ready == 0` / `voice_asr_ready == 0` 告警规则；/health 将 model readiness 纳入判定；补服务总览 dashboard 承接现有指标

---

## §20 DA-03 tts/voice 指标暴露 + CORS + lifespan 重复 🟡 Worth exploring

- `tts app.py` L48-100 vs `voice app.py` L69-120：`_METRICS_LOCK` + dict 计数 + `_sum/_count` summary + 手写文本拼装逐行同构（约 90 行结构性重复）；CORS 8 行白名单块逐字相同；lifespan 重复
- 指标文本格式是 `alert-rules.yml` 的**隐式契约**（`tts_engine_available`/`tts_synthesize_requests_total` 等名字被规则硬依赖），两份实现各自演进即静默漂移
- **Solution**：沿用 config_loader 复制共享先例（D1 已示范「单文件复制共享」），抽 `metrics_common.py` 复制进两目录（或将来入公共 wheel）

---

## §21 DA-04 CI 重构静默移除 E2E 冒烟门禁 🟡 Worth exploring

- git 证据：`309909ed` 引入 e2e-smoke job（构建 jar → 起服务 → 真实 LLM 跑 smoke-test.sh → 失败上传日志，曾真实抓过 API 变更）；`8983862a` 审计二轮 CI 重写为 services 结构时**整段移除且无替代**
- `tests/e2e/smoke-test.sh`：31 断言，仓库内零调用者；`playwright.config.ts` L8-10 的 CI 分支（forbidOnly/retries/workers）永不生效（死配置）；DEPLOY-GUIDE 仅登记「冒烟测试（可选）：手动跑」
- **Solution**：二选一并明确登记——恢复轻量 CI e2e job（成本高需全栈），或收敛为 deploy.sh 发布后自动执行 smoke-test.sh + service-manager health 后置校验

---

## §22 DA-05 tests/unit/scripts 六件套无运行入口 🟡 Worth exploring

- `tests/unit/scripts/`：check-commit-test.sh / db-rollback-drill-test.sh / gen-changelog-test.sh / gen-openapi-snapshot-test.sh / prepare-funasr-test.sh / verify-doc-numbers-test.sh（+ tests/unit/backup-common.sh）——覆盖运维关键路径（DB 回滚演练/模型投放/提交纪律），写得能跑（自带 temp repo/mock docker）却**零自动/文档入口**（grep 全库零引用；ci.yml 只有 mvn/pytest/npm 三类 job）——「测试存在但永远不执行，等于没测」
- `tests/integration/` 仅 `.gitkeep`（空壳目录；真正集成测试在 backend *IT.java，目录名误导）
- **Solution**：CI 加 `shell-tools-test` job（`bash tests/unit/scripts/*-test.sh tests/unit/backup-common.sh`，<2min）；删除 tests/integration 空目录

---

## §23 DA-06 prepare-models.sh --verify 自称「CI 门禁用」 ⚪ Speculative

- `prepare-models.sh` L13（注释「CI 门禁用」）、L62-95（--verify 完整实现：manifest 校验 + 关键文件冒烟 fail-closed）；唯一入口 `prepare-models.yml` 是 `workflow_dispatch` 手动，注释自认「实际投放以服务器侧为准」
- `deploy.sh` L260：dist rsync 显式 `--exclude 'models/'`；wakeWord/voiceprint 配置 SAME_ORIGIN——模型 404 → 唤醒/声纹**静默失效**，而整条链路唯一校验零自动执行（「门禁」措辞与事实不符）
- **Solution**：deploy.sh 发布前 ssh 执行 prepare-models.sh --verify，或删除「CI 门禁用」措辞登记为手动 SOP

---

## §24 DA-07 CI 对 4 份 Dockerfile 零构建验证 ⚪ Speculative

- ci.yml（mvn/npm/pytest/trivy 四类 job）无任何 `docker build`；镜像全部在服务器侧 build（deploy.sh L79-87）——验证链与发布产物脱节，DA-01 的 COPY 缺模块 bug 正是因此存活 3 周
- **Solution**：CI 加轻量 docker build 冒烟 job（只 build 不推送；tts/voice 复用 pip cache；frontend 与现 matrix build 二选一）
- 与 DA-01 同批：修复 + 结构性拦截闭环

---

## §25 Top recommendation

1. **BA-02 教师导出/周报越权**（安全零容错）——唯一同时满足「真实生产风险（数据越权面）+ 修复成本极低 + 与团队既有 P1 修复意图直接冲突」：resolveClassScope 的 P1 注释证明该域刚做过越权修复，exportStudents/weeklyReport 以 classScope=null 绕过它。安全合规 > 正确性，两行改动闭合越权面。
2. **DA-01 tts 镜像漏拷模块**（确定性事故）——下一次 TTS 发布必然 crash-loop，影响整条语音链路，当前无任何测试/CI 能拦住；修复成本一行，与 DA-07 同批形成「修复 + 结构性拦截」。
3. **FA-01 风险等级常量 6 处漂移**（核心概念 + 实际 bug）——6 个文件各自维护必然漂移，已产生渲染 bug 风险（0 级 undefined / 1-2 级同色）；一个 riskLevel.ts + 6 处替换，也是 teacher-web 图表层重构（FA-03/FA-06）的前置基础。

三者互不依赖可并行立项。DA-02 与 D5（doing/78）同属「降级/就绪可见性」主题，建议随 DA 线批次处理。

---

## §26 附注与后续

- 本清单登记编号 **DOC-074**；议决后实施跟踪按项目惯例落本文件 §27+ SPEC 章节或 TASK-TRACKER
- 候选证据锚点行号为 2026-08-08 审计时快照，实施前以代码现状为准（参照 his/76 §3.3 教训：审计行号会随重构失效）
- Speculative 项建议随对应主线候选顺带处理（BA-07 随 BA-03、FA-07/FA-08 随 teacher-web 重构、DA-06/DA-07 随 DA-01 批次）
- HTML 可视化报告：`tmp/architecture-review-20260808-090132.html`（已 git 忽略，不入版本库）
- 未列入的探索线索（预算限制，下轮可深挖）：GuardianConsentService/ConsentWithdrawalService（consent 域）、CaseLifecycleService（个案域）、PromptEvalGovernance/RedTeamRegressionRunner（prompt 评测域）

---

# SPEC 章节（2026-08-08 深度设计定稿）

## §27 BA-02 SPEC · 教师导出/周报班级范围强制收敛

**目标**：教师端导出/周报的班级范围判定单点化，消灭 `classScope=null` 全校兜底路径，班主任仅可见本班数据。

**改动文件**：
- 修改 `TeacherController.java`：
  - `exportStudents`（L287）：`listActiveStudents(ctx.tenantId(), null)` → `listActiveStudents(ctx.tenantId(), teacherService.resolveClassScope(...))`（与 exportAlerts L263 同构）
  - `weeklyReport`（L353）：`getStats(ctx.tenantId(), null)` → `getStats(ctx.tenantId(), resolveClassScope(...))`
- 修改 `TeacherService.java`：`listActiveStudents`/`getStats` 签名保留 classScope 参数但删除可空语义——Controller 层统一先解析（或下沉：service 方法内部强制调用 resolveClassScope，签名不再接收可空值）；null 语义收敛为显式 `ClassScope.ALL`（仅 ADMIN/PSYCH_TEACHER 可达，走方法级 `@PreAuthorize` 或 Controller 分支）
- 不新增表结构、不改变现有端点 URL/响应契约

**测试**：
- 扩展 `TeacherClassScopeTest`：新增「班主任导出学生仅本班」「班主任周报仅本班统计」「班主任未绑定班级 → 空结果」三个用例
- 回归：exportAlerts 既有断言保留；getStudents/exportAlerts 行为不变

**验收**：
- CLASS_TEACHER 角色调 `/api/v1/teacher/export/students` → CSV 仅含本班学生；调 `/teacher/report/weekly` → 仅本班统计
- `grep -n "getStats(ctx.tenantId(), null)\|listActiveStudents(ctx.tenantId(), null)" backend` → 无 teacher 域残留（null 仅存在于显式 ClassScope.ALL 语义处）

## §28 DA-01+DA-07 SPEC · tts 镜像漏拷修复 + CI 镜像构建冒烟

**目标**：消除确定性发布事故 + 建立结构性拦截（镜像错误在 CI 暴露而非部署现场）。

**改动文件**：
- 修改 `backend/tts-service/Dockerfile` L20：`COPY app.py config_loader.py config.yaml ./` → `COPY *.py config.yaml ./`（覆盖 tts_engines.py/tts_policy.py 及未来新增模块）
- 修改 `.github/workflows/ci.yml`：新增 `docker-build-smoke` job（构建 tts-service/voice-service 镜像，只 build 不推送；复用 pip cache `--mount=type=cache`；前端/backend 镜像如成本可控一并纳入，或先覆盖 Python 双服务——两者至少其一）
- 顺带核对 `voice-service/Dockerfile` 的 COPY 清单与 voice 目录实况一致

**测试**：
- 本地先验：`docker compose -f deploy/docker-compose.prod.yml build tts-service` 成功且 `docker run --rm` 启动后 `/health` 200（或 CI job 即验证）
- 新增 job 在 PR 上自动执行，失败标红

**验收**：
- `docker build tts-service` 通过；镜像内 `python -c "import tts_engines, tts_policy"` 成功
- ci.yml 绿（含新 job）；部署现场 `deploy.sh` 全链路不受影响

## §29 FA-01 SPEC · 风险等级单一源收敛

**目标**：teacher-web 风险等级 → 颜色/标签映射单一源，消除 0 级渲染缺陷与 1-2 级同色语义丢失。

**改动文件**：
- 新增 `frontend/teacher-web/src/utils/riskLevel.ts`：`RISK_LEVEL_META = { 0: {antdColor:'default', hex:'#52c41a', label:'绿色'}, 1: {antdColor:'gold', hex:'#ffd54f', label:'黄色'}, 2: {antdColor:'orange', hex:'#ff9800', label:'橙色'}, 3: {antdColor:'red', hex:'#f44336', label:'红色'} }` + `riskColor(level)`/`riskLabel(level)` helper
- 修改 6 处消费：AlertQueue.tsx / StudentPanel.tsx / TodayTodoPanel.tsx / OverviewPanel.tsx（补 0 级）/ BigScreen.tsx（标签 0='安全'→'绿色'，与领域语义对齐）/ StatsCharts.tsx（1/2 级拆两色）

**测试**：
- 新增 `riskLevel.test.ts`：四等级全字段断言 + 越界回退（undefined level → default 灰）
- 修改 `StatsCharts.test`：1/2 级颜色断言同步为两色

**验收**：
- `grep -rn "RISK_COLORS\|RISK_LABELS" teacher-web/src` → 仅 riskLevel.ts 单源（及测试）
- teacher-web vitest 全绿；maxRiskLevel=0 的学生卡片渲染「绿色」Tag

## §30 其余候选实施要点（议决后展开）

| 候选 | 实施要点 | 建议批次 |
|------|---------|---------|
| BA-01 TTS 假 API | 删 /pipeline/schedule + /effectiveness/evaluate 两端点 + TtsPipelineScheduler/VoiceEffectivenessTracker/VoiceDegradationPolicy 三类；openapi 契约快照同步 | 独立小批（配合前端播放逻辑前先评估 FE 现状） |
| BA-03 mood-check | 落库（复用 RelaxationSession 或新表）+ 会话结束链路回收；或删端点并入放松计数；徽章抽 BadgeService | 独立小批（产品数据回收价值先确认） |
| BA-04 MessageSummary | 策略上移 service 层；JSON 改 ObjectMapper；topicTags/suggestedNextAction/cbtFields 清理（删列走 migration） | 独立小批 |
| BA-05 groundedness | 真计算或删评估 + 删 identifyContentGaps | 独立小批 |
| BA-06 EntitlementFilter | 对齐真实端点重映射；或声明冻结删 filter（省每请求 selectById） | 随 frozen/38 议决 |
| BA-07 ToolboxService | 合并 AuthUserService | 随 BA-03 |
| BA-08 告警/分析 | outbox 接入 AlertService；WeComAlertService 构造器注入；DataAnalytics 冻结或接线；双 trend 去重 | 独立小批 |
| FA-02 主题色板 | immersiveStyles.ts + 三页面替换 | 独立小批（与 doing/75 风格体系关联，先核对 token 现状） |
| FA-03 ECharts | useECharts hook + ProfileRadarChart 按需注册 | 随 FA-01（teacher-web 图表层） |
| FA-04 回放抽屉 | SessionMessagesDrawer 共享 + QualityPanel 补守卫 | 随 FA-01 |
| FA-05 muted | 偏好共享 store 或 localStorage 持久化 | 独立小批 |
| FA-06 ChatRoom | useChatRoomPanels hook 抽离 | 独立小批（神组件治理延续） |
| FA-07 ErrorBoundary | shared ErrorBoundaryBase + 三端 fallback | Speculative，随 shared 迭代 |
| FA-08 OverviewPanel | 单一 load + mountedRef | 随 FA-01 |
| DA-02 voice 就绪 | 告警规则 + /health 就绪判定 + dashboard | 随 D5（doing/78）同批（可见性主题） |
| DA-03 metrics 共享 | metrics_common.py 复制共享 + 告警规则对齐 | 随 DA-02 |
| DA-04 E2E 门禁 | 恢复 CI e2e 或 deploy.sh 后置 smoke | 独立议决（成本/收益权衡） |
| DA-05 shell 测试 | CI shell-tools-test job + 删空目录 | 随 DA-04 |
| DA-06 prepare-models | deploy.sh 前置 --verify 或改述登记 | 随 DA-01 批次 |
| DA-07 CI 构建冒烟 | docker-build-smoke job | 随 DA-01 同批（§28） |

## §31 Speculative 探索备忘

- **BA-07**：收益最小但成本最低，随 BA-03 顺带；不单独立项
- **FA-07**：依赖 shared 模块迭代节奏，student reset 能力下沉三端需逐端适配（parent 为 Taro 组件）
- **DA-06**：需现场确认生产模型投放现状（是否已手动 --verify 过），「门禁」措辞改述成本最低
- **DA-07**：frontend/backend 镜像构建成本需评估（frontend 与现 matrix build 二选一，避免 CI 时长膨胀）

---

## §32 实施记录（2026-08-08，/implement 批次A/B/C）

> 范围：23 候选全部闭环（批次A/B/C + 批次D 收尾 FA-06/DA-02/DA-03）；BA-06 随 frozen/38 议决、DA-04/DA-05 独立议决（议决项不阻塞清单闭环）。全部闭环后并入主文档并归档 his。

| 批次 | 候选 | 结果 | 证据 |
|---|---|---|---|
| A | BA-02 导出/周报越权 | ✅ | TeacherController exportStudents/weeklyReport 接入 resolveClassScope（null=全校路径消除）；测试扩展覆盖班级范围收敛 |
| A | DA-01 tts Dockerfile | ✅ | COPY 补拷 tts_engines/tts_policy 引擎模块；voice Dockerfile 核对无同类遗漏 |
| A | DA-06 prepare-models | ✅ | deploy.sh 部署前置 --verify 模型投放校验 + 失败门禁 |
| A | DA-07 CI 构建冒烟 | ✅ | ci.yml 新增 docker-build-smoke job（Dockerfile 构建验证） |
| B | FA-01 风险等级单源 | ✅ | teacher-web utils/riskLevel.ts 单一导出（0-3 级色/label）+ 6 处替换；OverviewPanel 补 0 级、BigScreen 标签统一、StatsCharts 1/2 级拆两色；riskLevel.test.ts 新增 |
| B | FA-08 OverviewPanel | ✅ | 双 load 合并单一 load + mountedRef 守卫（重试路径） |
| B | FA-03 ECharts 集成 | ✅ | useECharts hook 统一生命周期；ProfileRadarChart 改 echarts/core 按需注册（radar） |
| B | FA-04 回放抽屉 | ✅ | SessionMessagesDrawer 共享（含 SessionSummaryCard）；QualityPanel 复用并补 cancelled 守卫 |
| B | FA-02 主题色板 | ✅ | student-h5 theme/immersiveStyles.ts 单源（THEME_STYLES + isDarkTheme）；EmotionDiary/RelaxationExercises/Achievements 替换 |
| B | FA-05 muted 持久化 | ✅ | muted 走 readLocalStorageSafe/writeLocalStorageSafe；useTtsPlayer 初始值读取，跨页生效 |
| C | BA-01 TTS 假 API | ✅ | 删 /pipeline/schedule + /effectiveness/evaluate 端点及 TtsPipelineScheduler/VoiceEffectivenessTracker/VoiceDegradationPolicy；openapi 契约快照同步 |
| C | BA-03 mood-check 落库 | ✅ | mood-check 落库 RelaxationSession（ToolboxController）+ 徽章评估收敛 BadgeService 统一入口（日记徽章从 EmotionDiaryService 移入，grounding_master 零消费 badge 清理） |
| C | BA-04 MessageSummary | ✅ | 策略上移 MessageSummaryService 单一入口（D-7 两级：riskLevel≥2 原文 1024 / <2 语义提炼 ≤200）；JSON 改注入 ObjectMapper；删实体工厂与零消费字段（suggestedNextAction/cbtFields，DB 列保留无迁移）；测试 6 用例平移 |
| C | BA-05 groundedness | ✅ | 删 evaluateGroundedness 伪信号（请求/返回条数 ≠ 检索/引用数，score 恒 {0,0.33,0.67,1}）与阈值常量；**identifyContentGaps 保留**——代码核实有真实消费（EditorialWorkflowService.operationalReport → GET /editorial/report），§6.1「零调用」分析过时 |
| C | BA-07 ToolboxService | ✅ | 浅模块删除，用户查询收敛 AuthUserService.findById（ToolboxController 依赖既有服务，分层纪律保持） |
| C | BA-08 告警/分析 | ✅ | RiskNotifyOutboxService.markDead 接入 AlertService（WARNING，企微 webhook 或日志降级，外呼失败不影响状态标记）；WeComAlertService 构造器注入（去测试反射 setField，删零消费 mentionedList + 3 处配置）；DataAnalytics 三端点 javadoc 冻结声明；双 trend 合并 buildWeeklySessionTrend（统一 key sessions） |
| 待议决 | BA-06 EntitlementFilter | ⏳ | 随 frozen/38 议决（映射对齐或冻结删 filter） |
| 待议决 | DA-04/DA-05 | ⏳ | 独立议决（E2E 门禁成本/收益权衡） |

### 32.1 批次 D（2026-08-08，/implement 收尾：FA-06/DA-02/DA-03）

| 候选 | 结果 | 证据 |
|---|---|---|
| FA-06 ChatRoom 神组件 | ✅ | useChatRoomPanels hook 抽离（7 个面板/弹窗状态收敛：settings/toolbox/sos/speakingMsgIdx/voiceNotice/cancelArmed/confirmSwitch）+ ChatRoomHeader 子组件拆分；ChatRoom 595→529 行；useChatRoomPanels.test.ts 6 用例（含定时器清理 AUD-017） |
| DA-02 voice 就绪消费 | ✅ | /health 纳入模型就绪判定（UP/DEGRADED/DOWN 三态 + asr_ready/ser_ready 字段，与 tts D5 语义同构）；/metrics 新增 voice_ser_enabled gauge（与 ready 解耦——显式禁用不告警）；alert-rules.yml 新增 MindsafeVoiceAsrNotReady（critical）/MindsafeVoiceSerNotReady（warning，表达式排除显式禁用）；service-manager voice 分支读 /health body 消费 status（SER 降级告警但健康、ASR 未就绪判不健康）；Grafana 新增 services-overview.json 服务总览（up/引擎就绪/速率/耗时 7 面板）；voice test_app.py 新建（7 用例：三态 + ser_enabled 契约） |
| DA-03 metrics 共享 | ✅ | metrics_common.py 复制共享（Metrics：线程安全 counter+summary + render；gauge 附加行保留各服务语义），tts/voice 两处替换（~60 行去重）；**voice Dockerfile 显式 COPY 清单补 metrics_common.py**（新增模块漏拷 = DA-01 同款隐患，DA-07 构建冒烟 job 结构性拦截）；tts test_app.py 新增 /metrics 契约测试（MindsafeTtsAllEnginesDown/HighFailureRate 硬依赖指标名回归） |
| 批次 D 验证 | ✅ | Python：voice 40+1skip / tts 78 全过；前端 student 884 全过 + tsc 0 错误 + oxlint 0 errors；bash -n service-manager；alert-rules.yml YAML + dashboard JSON 语法校验通过 |
