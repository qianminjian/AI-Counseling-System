# AI 小学生心理辅导系统 - 任务跟踪表

> 创建：2026-07-23 | 更新：2026-07-28（ESC-001 专题设计产出 frozen/58 并定级冻结；历史：2026-08-01 §二十五 配置统一纳管、2026-07-29 独立审计校正）
> 
> 本表用于跟踪项目各阶段任务的进度和责任人。

---

## ⚠️ 审计校正（2026-07-29，钱敏健授权全面修复）

> 独立架构审计（三路并行 agent 交叉印证）结论：**测试全绿 ≠ 功能存在**。91 组件中 43 个为孤儿（生产入口不可达），真实交付面约为台账声称的一半。综合评分 2.8/10，No-Go。本表就此校正。

**状态图例（新增）：**
> - ✅ 已完成 = 已实现**且已接入生产入口**（Controller/Filter/@Scheduled/装配链可达）
> - 🟧 **已编码未接线**（态③）= 代码与单测存在，但从生产入口不可达，线上不生效——**不得计为完成**
> - ❌ 名不副实 = 声称完成但核心机制缺失/失效

**本轮修复批次（fix-01~12）：**
> fix-01 台账重标（本节）→ fix-02 删世界B → fix-03 加密接线(R-01) → fix-04 监护人同意门禁(R-03) → fix-05 SLA兜底(P-05) → fix-06 多租户拦截器(P-02) → fix-07 弱口令fail-fast(R-04) → fix-08 TLS(R-02) → fix-09 种子数据V27清理(R-05) → fix-10 CI修真(Q-01/Q-03) → fix-11 全量回归+文档同步 → fix-12 孤儿组件逐个裁决。

> 说明：§二十三 P0/P1/P2 backlog 中大量 ✅ 实为态③「已编码未接线」孤儿，**逐行裁决归口 fix-12**（与钱敏健逐项确认），本节仅先校正最高信号的失实条目，不在此重复逐行改标。

**fix-12 裁决结果（2026-07-28，钱敏健确认）：**
> 全量扫描（从 18 Controller + @Scheduled + Spring 自动装配出发追踪依赖链）实测 **39 个孤儿**（审计时 43 个含世界 B 已删组件）。
> - **删除 3 个**（YAGNI，零消费者基础设施）：`CacheService` + `CacheServiceTest` / `BusinessMetrics` / `PageResponse`
> - **~~保留·待接线 27 个~~** ✅ 全部接线完成（2026-07-28 P0+P1 批次）：ORCH/CBT/MEM/EMP/ALLY/WB/PEVAL/TOOL/TTSFX/AB/BILL/RISK-204/SAFE-204 全系列生产可达
> - **保留·远期 5 个** ✅ 已接线（2026-07-28）：SessionEndAnalyticsService 聚合调用（VoiceEmotionTrendAnalyzer/TrendAnomalySignaler/EmotionOrchestrationEvaluator/ProfileEffectivenessTracker）+ endSession 链
> - **保留·暂缓 3 个**：AssessmentScoringEngine / ScoringResult / BuiltinScales（量表施测暂缓，决策 #21）
> - **~~保留·待接线（数据层）2 个~~** ✅ 已接线（2026-07-28，AiChatServiceImpl 审计落库）
>
> 审计修复批次 fix-01~12 **全部收官**。P0+P1 接线批次 **全部收官**（2026-07-28）。剩余待实施项均为 🔭 远期或暂缓。

---

## 一、文档整合任务（当前阶段）

| 任务ID | 任务描述 | 状态 | 负责人 | 开始日期 | 完成日期 | 备注 |
|--------|----------|------|--------|----------|----------|------|
| DOC-001 | 提取 15 份 docx 内容为 txt | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | textutil 转换 |
| DOC-002 | 创建 design/ 与 doc/ 目录结构 | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 初版为 design/docs+design/his，后纠偏为 design/+doc/（见 DOC-024） |
| DOC-003 | 将原始 docx 归档至 doc/ | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | git mv 保留历史（中途从 design/his 迁至 doc） |
| DOC-004 | 创建 DESIGN-OVERVIEW.md 总览 | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 含目录、关键摘要 |
| DOC-005 | 创建 TASK-TRACKER.md 跟踪表 | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | |
| DOC-006 | 转换 01_产品架构图 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 338 行，关键内容已转换 |
| DOC-007 | 转换 02_Prompt体系设计 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 含安全 Advisor 链顺序 |
| DOC-008 | 转换 03_CBT对话流程树 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | |
| DOC-009 | 转换 04_风险识别规则库 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | risk_event 为实现基准 |
| DOC-010 | 转换 05_老师后台设计 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | S0-S3 映射红橙黄绿 |
| DOC-011 | 转换 06_数据库结构设计 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 622 行，DDL 核心 |
| DOC-012 | 转换 07_SaaS多学校隔离 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | Schema 级隔离 fail-fast |
| DOC-013 | 转换 08_MVP最小可行版本 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 431 行，完整转换 |
| DOC-014 | 转换 09_商业模式与采购 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 参考类 |
| DOC-015 | 转换 10_政策与合规风险 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 含合规硬约束映射 |
| DOC-016 | 转换 11_竞品深度分析 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 参考类 |
| DOC-017 | 转换 12_技术架构图 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 重大改写：微服务→模块化单体 |
| DOC-018 | 转换 13_Agent工作流 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | LangGraph→Spring AI 改写 |
| DOC-019 | 转换 14_儿童安全对话规范 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 危机热线固化铁律 |
| DOC-020 | 转换 15_心理知识库建设 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | RAG/Spring AI 适配 |
| DOC-021 | 更新 STRUCTURE.md 反映 docs/his 结构 | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | §2.2 拆分约定 |
| DOC-022 | 更新 BEACON.md/OVERVIEW 引用新结构 | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 修正 6 处断链接 |
| DOC-023 | Git commit 文档整合变更 | ✅ 完成 | Agent | 2026-07-23 | 2026-07-28 | 已在 0860320 提交，工作区干净 |
| DOC-024 | 目录结构纠偏：md 拍平至 design/、docx 迁至 doc/ | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 对齐钱敏健原意，STRUCTURE/BEACON/OVERVIEW 同步 |

---

## 二、待确认决策（需钱敏健拍板）

| 决策ID | 决策描述 | 选项 | 推荐 | 状态 | 截止日期 |
|--------|----------|------|------|------|----------|
| DEC-001 | MVP 范围最终确认 | 以 design/08 为准 / 调整 | 以 design/08 为准 | ✅ 已确认 | 2026-07-23 |
| DEC-002 | Java 构建工具 | Maven / Gradle | Maven（信创/银行通用） | ✅ 已确认 | 2026-07-23 |
| DEC-003 | Java ORM 框架 | MyBatis-Plus / Spring Data JPA | MyBatis-Plus（政企/信创主流） | ✅ 已确认 | 2026-07-23 |
| DEC-004 | 3 版建设方案主版本 | 时间戳后缀版 / 整合版 | 需人工比对 md5 | ⏳ 待确认 | 随时 |
| DEC-005 | 首个 LLM Provider | DeepSeek / 通义 / GLM | DeepSeek（性价比高） | ✅ 已确认（deepseek-v4-flash/pro） | 2026-07-23 |
| DEC-006 | 信创数据库选型 | 达梦 / 人大金仓 / 其他 | MVP 用 PG，M3+ 评估 | ⏳ 待确认 | M3 前 |

---

## 三、MVP 开发任务（M1，已完成）

> 注：M1 全部任务已在 Phase 1-10 中完成，含 Maven 多模块、多租户、AI 对话、风险识别、双前端、Docker 部署。

| 任务ID | 任务描述 | 模块 | 状态 |
|--------|----------|------|------|
| M1-001 | Maven 多模块骨架搭建（7 模块） | backend/ | ✅ 完成 |
| M1-002 | PostgreSQL 初始化 + Flyway 迁移 | backend/ | ✅ 完成 |
| M1-003 | Schema 级多租户路由实现 | counseling-tenant/ | 🟧 部分实现（名不副实已校正）——fix-06 落地**行级**隔离纵深防线：`TenantLineInnerInterceptor` + `TenantContextHolder` + `ParentAuthService` 去硬编码；2026-07-28 M1-003 fail-fast 已收紧：无上下文且非系统作用域的业务表 DAO 调用抛 `IllegalStateException`，合法跨租户链路（8 处）经 `runAsSystem`/`callAsSystem` 显式声明 + `TaskDecorator` 异步传播，单测+IT 全绿；**仍为共享 tenant_template schema 行级隔离，非 Schema 级物理隔离**（路线 A 已定稿，B 挂起），见审计 P-02/P-06 + design/07 §11 |
| M1-004 | 用户与权限模型（JWT + RBAC） | counseling-domain/ | ✅ 完成 |
| M1-005 | Spring AI LLM Provider 接入（DeepSeek） | counseling-ai/ | ✅ 完成 |
| M1-006 | Safety Agent 实现（双层输出审查 + PII） | counseling-ai/ | ✅ 完成 |
| M1-007 | Emotion Agent + CBT Agent 实现 | counseling-ai/ | ✅ 完成 |
| M1-008 | 风险关键词识别规则库（10 类信号） | counseling-ai/ | ✅ 完成 |
| M1-009 | 对话 API（学生端 SSE 流式） | counseling-api/ | ✅ 完成 |
| M1-010 | 预警通知服务（WebSocket 实时推送） | counseling-service/ | ✅ 完成 |
| M1-011 | 学生端 H5（React 19 + Tailwind + PWA） | frontend/student-h5/ | ✅ 完成 |
| M1-012 | 教师端 Web（React 19 + Ant Design 6） | frontend/teacher-web/ | ✅ 完成 |
| M1-013 | Docker Compose 部署配置 | deploy/ | ✅ 完成 |
| M1-014 | 单元测试（123 个，JUnit 5） | 各模块 src/test/ | ✅ 完成 |

---

## 四、M2 任务（已完成，在 Phase 11-15 中实现）

| 任务ID | 任务描述 | 状态 |
|--------|----------|------|
| M2-001 | 放松呼吸练习（学生端 CSS 动画） | ✅ 完成 |
| M2-002 | 高风险学生列表（教师端） | ✅ 完成 |
| M2-003 | 学生档案查看 + AI 摘要（教师端） | ✅ 完成 |
| M2-004 | 使用量/预警量统计（教师端图表） | ✅ 完成 |
| M2-005 | 满意度评价功能（学生端 + 教师端统计） | ✅ 完成 |
| M2-006 | 会话转人工（红色风险 → 教师接管） | ✅ 完成 |
| M2-007 | 家长端 H5 报告（JWT 链接访问） | ✅ 完成 |
| M2-008 | 周报导出（可打印 HTML） | ✅ 完成 |

---

## 五、商业化版本开发（Phase 1-20，已完成）

| Phase | 核心功能 | 状态 |
|-------|----------|------|
| 1-3 | 后端骨架 + 多租户 + AI 对话 + 风险识别 + 学生端 H5 | ✅ |
| 4-6 | 教师端工作台 + 预警队列 + WebSocket 实时通知 | ✅ |
| 7-8 | 语音输入/TTS + 会话历史 + 设置面板 | ✅ |
| 9-10 | 告知同意 + 试用注册 + 密码管理 | ✅ |
| 11-12 | PWA 离线 + CSV 导出 + Prometheus 监控 + 批量导入 | ✅ |
| 13-14 | 情绪趋势分析 + 班级统计 + 学生备注 | ✅ |
| 15 | 会话转人工 + 家长 H5 + 周报导出 | ✅ |
| 16 | 满意度分析 + 呼吸练习增强 + 移动端适配 | ✅ |
| 17 | 平台管理后台 + 质量监控 + 情绪日记 | ✅ |
| 18 | 企微 OAuth + 数据大屏 + 会话导出 PDF | ✅ |
| 19 | Docker Compose + 新手引导 + 话术模板 + 成就系统 | ✅ |
| 20 | .env.example + 暗色模式 + 学生端引导动画 | ✅ |

---

## 六、风险与问题跟踪

| 问题ID | 问题描述 | 影响 | 状态 | 责任人 | 解决方案 |
|--------|----------|------|------|--------|----------|
| RISK-001 | 15 份 docx 内容量大，md 转换耗时 | 文档整合进度 | ✅ 已解决 | Agent | 分批转换完毕，15/15 均已落 md |
| RISK-002 | 12 号技术架构图过度设计（微服务） | 技术选型理解 | ✅ 已解决 | - | 已裁决：模块化单体，12 号§10 保留对照 |
| RISK-003 | 13 号 Agent 工作流基于 LangGraph | Java 技术栈适配 | ✅ 已解决 | Agent | 已改写为 Spring AI（决策 #9） |
| RISK-004 | pgvector 在信创环境不可用 | 长远私有化部署 | 🟡 中 | 钱敏健 | M3+ 评估国产向量方案 |
| RISK-005 | 3 版建设方案内容有差异 | 需求理解一致性 | 🟡 中 | 钱敏健 | 需人工比对 md5 后确定主版本 |
| RISK-006 | 首次目录层级理解偏差（多套一层 docs/his） | 文档导航/引用一致性 | ✅ 已解决 | Agent | 2026-07-23 纠偏为 design/+doc/，同步 5 份状态文件 |

---

## 七、里程碑计划

| 里程碑 | 目标 | 计划日期 | 实际日期 | 状态 |
|--------|------|----------|----------|------|
| **M0：文档整合完成** | 15 份 docx 转为 md，总览/跟踪表就绪 | 2026-07-23 | 2026-07-23 | ✅ 完成 |
| **M0.5：决策确认 + 开发规范** | MVP 范围、Java 子选型确认；开发规范制定 | 2026-07-23 | 2026-07-23 | ✅ 完成 |
| **M1：核心对话+风险识别** | 最小闭环验证 | 2026-09-23 | 2026-07-23 | ✅ 完成（Phase 1-10） |
| **M2：功能体验完善** | 放松训练+教师后台+家长端 | 2026-11-23 | 2026-07-23 | ✅ 完成（Phase 11-15） |
| **M3：商业化版本** | 企微/大屏/导出/成就/部署 | 2027-01-23 | 2026-07-23 | ✅ 完成（Phase 16-20） |
| **M4：部署上线** | 云资源采购 + 生产部署 + 真实用户试用 | 待定 | - | ⏳ 待启动 |

---

## 八、内容安全审查体系（M1 已实现，2026-07-23 提交 commit 9ec5278）

| 任务ID | 任务描述 | 模块 | 状态 | 备注 |
|--------|----------|------|------|------|
| SAF-001 | 输入侧风险识别（10 类信号关键词硬规则） | counseling-ai/risk/ | ✅ 完成 | `RiskDetectorServiceImpl` |
| SAF-002 | 输入侧 PII 服务端脱敏（手机/身份证/邮箱） | counseling-ai/safety/ | ✅ 完成 | `PiiDesensitizer` |
| SAF-003 | 输出 Layer1 流式关键词硬过滤（滑动窗口） | counseling-ai/safety/ | ✅ 完成 | `OutputContentFilter` + `SafetyKeywordLibrary` |
| SAF-004 | 输出 Layer2 异步 SAF-002 语义审查 | counseling-ai/safety/ | ✅ 完成 | `OutputReviewService`（fire-and-forget） |
| SAF-005 | 违规上报（依赖倒置，写 risk_events + 通知） | counseling-service/safety/ | ✅ 完成 | `OutputSafetyReporter` 端口 + `OutputSafetyReporterImpl` 适配器 |
| SAF-006 | 单元测试（5 个测试类） | 各模块 src/test/ | ✅ 完成 | 见 design/04 §17.6 |

---

## 九、设计文档维护与试用准入（2026-07-23）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| DOC-025 | 新增 design/21 认证与试用准入设计（兼容认证+告知同意） | ✅ 完成 | 4 个决策点（D1-D4）待钱敏健拍板 |
| DOC-026 | 同步内容安全审查到 design/04 §十七（已实现，真实类名） | ✅ 完成 | 输入风险/PII/输出双层/上报/测试 |
| DOC-027 | 对齐 design/14 §11 实际类名（目标设计 vs M1 实现） | ✅ 完成 | 补输出审查/PII 行 |
| DOC-028 | 对齐 design/18 §3 SAF-002 + §13 Advisor 链实现状态 | ✅ 完成 | 实际占位符 {candidate_reply}/{context} |
| DOC-029 | 立"设计文档与代码一致"底线规则 | ✅ 完成 | core-red-lines §4.5 / AGENTS §3.5 / design-persistence §4.2 |
| DOC-030 | 更新 BEACON / DESIGN-OVERVIEW / TASK-TRACKER | ✅ 完成 | 决策 #10-12、导航 21、版本 v1.5 |
| AUTH-001 | 实施试用准入 P0（告知同意门控+试用注册+ChatController 接 SecurityContext） | ✅ 完成 | Phase 9-10 实现 |
| AUTH-002 | chat 端点收紧鉴权 + 前端接入 JWT | ✅ 完成 | Phase 9-10 实现 |
| AUTH-003 | consent_records / trial_invite_codes 表迁移脚本 | ✅ 完成 | V6__trial_access.sql |

---

## 十、学生心理画像（design/23 + design/29）

### 基础画像（已完成）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| PROF-001 | P0：student_profiles 表 + 情绪基线 + 风险轨迹（SQL 聚合） | ✅ 完成 | V12 迁移 + StudentProfileService |
| PROF-002 | P0：对话时注入画像到 System Prompt | ✅ 完成 | AiChatServiceImpl 3.6 段 |
| PROF-003 | P1：沟通偏好 + 技巧有效性（LLM 提炼） | ✅ 完成 | ProfileExtractorService 异步提炼 + Prompt 注入增强 |
| PROF-004 | P2：成长轨迹 + 里程碑 + 教师端雷达图 | ✅ 完成 | ProfileRadarService + ECharts 雷达图 + 里程碑检测 |

### 年龄适配与画像增强（design/29，P0/P1 已实施）

> 核心问题：grade_level 硬编码 "5-6"、语言模板从未调用、User.gradeCode 未传入 AI 层、画像无年龄/性格维度。
> 核对结论（2026-07-27）：P0+P1（PROF-010~015 + PROF-019）已实现，与 [BEACON.md](BEACON.md) L63 一致；P2/P3 保持待开始/远期。

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| PROF-010 | P0：接通年级——SessionState 新增 grade + AiChatService 接口新增 grade 参数 + gradeCode 解析 | ✅ 完成 | `User.gradeCode` 字段 + `AiChatService.chat` 接 grade 参数 + `ConversationServiceImpl.parseGradeCode`，design/29 §3.1-3.3 |
| PROF-011 | P0：调用 languageTemplateForGrade(grade) 加载语言模板追加到 System Prompt | ✅ 完成 | `PromptTemplateService.languageTemplateForGrade` + `AiChatServiceImpl` L70/L128 调用，design/29 §3.4 |
| PROF-012 | P0：buildProfilePrompt 追加「基础属性」段（年级/年龄/性别/表达深度） | ✅ 完成 | `StudentProfileService.buildProfilePrompt` 含 `## 基础属性` 段，design/29 §3.5 |
| PROF-013 | P1：语言模板升级（三维度：认知水平+比喻库+互动模式+CBT 深度） | ✅ 完成 | LANG_001/002/003 已重写为五段（认知水平/语言约束/比喻库/互动模式/禁止），design/29 §3.9 |
| PROF-014 | P1：性别 × 年龄交叉策略（buildGenderStyle 按年级段分化） | ✅ 完成 | `AiChatServiceImpl.buildGenderStyle` 6 分支矩阵（男/女 × low/mid/high），design/29 §3.10 |
| PROF-015 | P1：动态降级机制（expressionDepth < 0.3 → 降低语言复杂度） | ✅ 完成 | `ConversationServiceImpl.computeEffectiveGrade`，风险场景不降级，design/29 §3.11 |
| PROF-016 | P2：V18 迁移 student_profiles 新增 personality_traits JSONB 列 | ✅ 完成 | V18 迁移 + StudentProfile 实体扩展，design/29 §4.2 |
| PROF-017 | P2：LLM 提炼扩展（PROFILE_EXTRACTOR 新增性格维度：内向/敏感/好奇/兴趣） | ✅ 完成 | ProfileExtractorService.mergePersonalityTraits + EMA 合并，design/29 §3.6 |
| PROF-018 | P2：性格 → Prompt 策略映射 + dominant_interests 暖场取材 | ✅ 完成 | StudentProfileService.appendPersonalityStrategy，design/29 §3.8 |
| PROF-019 | P0-P1 集成测试（1 年级 vs 6 年级 System Prompt 差异 + 降级 + 风险不降级回归） | ✅ 完成 | `ConversationServiceImplTest.GradeComputation` 6 用例（含风险不降级回归），design/29 §七 |
| PROF-020 | P3：画像效果量化（A/B 适配 vs 不适配的满意度/会话深度对比） | 📝 设计完成，待实施 | design/39（工程化设计），design/29 §八 |
| PROF-022 | 初高中学段适配缺口评估（话术/量表/UI 全维度，K12 口径定稿配套挂账，09 §10.1/11 §9.3；PROF-021 已被 design/44 占用故跳号） | ⏳ 待开始（挂账，初高中版本启动时激活） | 2026-07-28 钱敏健定稿维持 K12 表述的风险缓释项 |

---

## 十一、身份认证优化（design/24）

### 阶段一：M1 试用加固（近期开发）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| AUTH-010 | 邀请码一人一码（教师批量生成 + 用后作废） | ✅ 完成 | P0，V13 迁移 + AdminController 批量生成 |
| AUTH-011 | 学生 PIN 码（4-6位数字 + BCrypt） | ✅ 完成 | P0，set-pin/pin-login 端点 |
| AUTH-012 | 登录失败锁定（Redis 计数器 5次/15min） | ✅ 完成 | P0，LoginLockoutService |
| AUTH-013 | 家长 Token 绑定手机 + 短信验证 | ✅ 完成 | P1，SmsService + PhoneVerificationService + ParentController 端点 |
| AUTH-014 | 密码策略（8位+复杂度 + 90天过期） | ✅ 完成 | P1，PasswordPolicyService + V14 迁移 |

### 阶段二：M2 学校正式部署（后续待办）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| AUTH-020 | 学校 Excel 批量导入学生名单 | ✅ 完成 | CSV 导入（AdminController + AdminPanel UI） |
| AUTH-021 | 企微/钉钉 OAuth 配置上线 | ⏳ 待开始 | 代码已就绪，需配置 corpId/secret |
| AUTH-022 | 家长微信小程序 + 微信 OAuth 登录 | 📝 设计完成，待实施 | design/43（Taro 迁移），见 PARENT-WX 系列任务 |
| AUTH-023 | 监护人同意闭环（短信确认链接）+ 对话入口门禁 | ✅ 完成（门禁 fix-04） | GuardianConsentService（发起/确认/hasGuardianConsent）+ AuthController 端点；ChatController.createSession/sendMessage/sendNudge 前置 `hasGuardianConsent` 校验，未同意抛 CONSENT_REQUIRED(20003)，endSession 不门禁。审计 R-03：此前仅有闭环无运行时门禁，学生可绕过同意直接对话，现已接线，ChatControllerTest 7 用例守卫 |

### 阶段三：合规加固（后续待办）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| AUTH-030 | 使用时长限制（每日≤30min） | ✅ 完成 | 未保法，UsageTimeLimitService + Redis 每日累计 |
| AUTH-031 | 数据最小化审计 + 定期清理 | ✅ 完成 | DataRetentionCleanupJob + @EnableScheduling，普通30天/高风险365天 |
| AUTH-032 | 家长撤回同意 → 冻结+删除 | ✅ 完成 | PIPL §47，ConsentWithdrawalService + ParentController 端点 |
| AUTH-033 | 年度合规审计报送 | ⏳ 待开始 | 未保条例 §37，流程性报送（非代码） |
| AUTH-034 | WebAuthn 设备端指纹/Face ID（可选） | ⏳ 待开始 | 不采集生物数据，需真机测试 |
| AUTH-040 | 监护人同意 SMS 闭环（替代试运行自动写入） | ✅ 代码侧完成（2026-07-31） | PIPL §31 完整闭环已落地：配置开关 `mindsafe.consent.trial-auto-grant`（默认 true=试运行自动写入；prod compose 默认 false）→ 注册响应 `guardianConsentPending` → 前端收集监护人手机号 + 验证码确认页（student-h5）→ `/guardian-consent/request+confirm` 写入 guardian_consent；age≥14 本人同意注册即写入（修复 14+ 被门禁卡死 bug）。测试：单测 7 用例 + GuardianConsentFlowIT 6 用例全绿。剩余前置：DEPLOY-010 阿里云 SMS 签名/模板（生产开启 `SMS_PROVIDER=aliyun`） |

---

## 十二、家长端 H5（design/26）

### P1：H5 移动网页（本期）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| PARENT-001 | Vite+React 工程初始化 + 基础架构 | ✅ 完成 | frontend/parent-h5 |
| PARENT-002 | 手机验证页（send-code → verify-phone） | ✅ 完成 | |
| PARENT-003 | 情绪周报页（/parent/report） | ✅ 完成 | |
| PARENT-004 | 同意管理页（撤回同意 + 二次确认） | ✅ 完成 | |
| PARENT-005 | 构建验证 + nginx 部署配置 | ✅ 完成 | vite build 成功，base=/parent/ |

### P2：微信小程序（远期规划，不丢失）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| PARENT-WX-001 | 微信小程序工程注册 + AppID 配置 | 📝 设计完成，待实施 | design/43 W-1，需企业主体认证 |
| PARENT-WX-002 | wx.login → openid → parent_bindings 绑定 | 📝 设计完成，待实施 | design/43 §3.3/W-4，后端补绑定端点 |
| PARENT-WX-003 | 微信 OAuth 授权页（获取手机号） | 📝 设计完成，待实施 | design/43 §3.3，getPhoneNumber 需企业认证 |
| PARENT-WX-004 | taro build --type weapp + 真机调试 | 📝 设计完成，待实施 | design/43 W-5 |
| PARENT-WX-005 | 小程序提审 + 上线 | 📝 设计完成，待实施 | design/43 W-7，隐私协议/类目审核 |
| PARENT-WX-006 | 订阅消息推送（周报通知） | 📝 设计完成，待实施 | design/43 §3.4，微信订阅消息 API |

---

## 十三、商用部署就绪（M4 前置）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| DEPLOY-001 | nginx /parent 路由修复（alias + SPA fallback） | ✅ 完成 | deploy/nginx/default.conf |
| DEPLOY-002 | docker-compose.prod.yml 挂载 parent-h5 + 路径对齐 | ✅ 完成 | /app/student, /app/teacher, /app/parent |
| DEPLOY-003 | deploy.sh 加入 parent-h5 构建+上传 | ✅ 完成 | |
| DEPLOY-004 | 环境变量修复（Redis密码/JWT/SMS/CORS） | ✅ 完成 | docker-compose.prod.yml + .env.example |
| DEPLOY-005 | SMS 可配置化（logging/aliyun 切换） | ✅ 完成 | AliyunSmsService + @ConditionalOnProperty |
| DEPLOY-006 | CORS + 安全头（X-Frame-Options/XSS） | ✅ 完成 | SecurityConfig 加固 |
| DEPLOY-007 | 学校初始化工具（init-school.sh） | ✅ 完成 | 租户+学校+管理员+邀请码 |
| DEPLOY-008 | pgcrypto 扩展（V15 迁移） | ✅ 完成 | crypt/gen_salt 依赖 |
| DEPLOY-009 | 生产部署执行 | ✅ | 三端上线 + nginx /parent 路由 |
| DEPLOY-010 | 阿里云 SMS 签名/模板申请 | ⏳ 待用户操作 | 需企业主体 + 审核 |

---

## 十四、家庭码认证关联体系（FAM）

> 设计思路：教师是信任锚点。学生注册获得家庭码，家长用家庭码+手机号+密码绑定孩子。
> MVP 阶段无需短信，正式版加短信验证只需一步。

| 编号 | 任务 | 状态 | 备注 |
|------|------|------|------|
| FAM-001 | V16 迁移：users.family_code + parent_accounts + parent_student_links | ✅ | |
| FAM-002 | 学生注册时生成家庭码 + /me 返回 familyCode | ✅ | |
| FAM-003 | 家长注册 API（家庭码+手机号+密码+关系） | ✅ | |
| FAM-004 | 家长登录 API（手机号+密码） | ✅ | |
| FAM-005 | 家长端 H5 改造（家庭码注册页 + 密码登录） | ✅ | |
| FAM-006 | 学生端注册成功页显示家庭码 | ✅ | |
| FAM-007 | 家长查看周报改用正式 JWT（复用现有 /parent/report） | ✅ | |

---

## 十五、波波小精灵品牌与宠物交互（design/27）

> 设计思路：品牌固化（波波小精灵）+ 伙伴宠物化（四态动画）+ 语音输入圆球化（宠物与圆球合一），纯前端改动，后端零改动。

| 编号 | 任务 | 状态 | 备注 |
|------|------|------|------|
| BOBO-001 | 品牌固化：PWA 名/title 改「波波小精灵」+ 海豚 favicon/PWA 图标 + 三主题角色固定波波 | ✅ 完成 | index.html / vite.config.js / public/* / ThemeProvider.jsx |
| BOBO-002 | 波波 SVG 角色组件（BoBoPet.jsx，逐部件可动画 + 四态动画基础） | ✅ 完成 | 纯 SVG，随主题变色 |
| BOBO-003 | 圆球变形 + 触感反馈（按住蜷成发光圆球 + vibrate + iOS 视觉补偿） | ✅ 完成 | navigator.vibrate 降级策略 |
| BOBO-004 | 话语气泡组件（SpeechBubble.jsx）+ useTtsPlayer 暴露 currentSentenceText | ✅ 完成 | 逐句滚动与 TTS 同步 |
| BOBO-005 | 接入 ChatRoom（手机悬浮输入栏右上角 + Pad 左栏合并，删除旧麦克风按钮） | ✅ 完成 | 状态映射：recording/streaming/tts.playing；手机气泡右对齐防溢出 |
| BOBO-006 | WelcomeGuide 增加「按住波波说话」引导 + AI 人设改波波（design/18 + 后端 prompt）+ 构建验证 | ✅ 完成 | vite build 通过；后端问候语同步改波波（待部署生效） |

---

## 十六、语音唤醒与冷场引导（design/28）

> 设计思路：三个叠加式能力——唤醒词"哈喽波波"（Transformers.js + Whisper 本地引擎，监听严格限定在对话会话内）+ 冷场决策模型（多信号加权+画像关联，先判断该留白还是该暖场）+ 音色人设（中性角色+男女/温柔音色）；后端 nudge SSE 接口 + 前端沉默检测，不改动现有按住说话主路径。

| 编号 | 任务 | 状态 | 备注 |
|------|------|------|------|
| WAKE-001 | 设计文档 design/28 + design/18 登记 TSK-004 + OVERVIEW/TRACKER/BEACON 同步 | ✅ 完成 | 阶段 0 设计先行 |
| WAKE-002 | 问候语加昵称"哈喽，[昵称]！"（buildGreeting + user.pseudonym） | ✅ 完成 | 阶段 1 后端，唤醒词 onboarding |
| WAKE-003 | 冷场决策模型（信号 A-F 加权评分卡 + 硬规则覆盖）+ SessionState 字段（nudgeCount/lastNudgeAt/expressionDepth/最后消息类型）+ createSession 画像加载 | ✅ 完成 | 阶段 1 后端，信号 F=画像沟通偏好 |
| WAKE-004 | TSK-004 prompt 文件 proactive_nudge_zh-CN_v1.0.0.md + PromptTemplateService.TSK_004 常量 | ✅ 完成 | 阶段 1 后端 |
| WAKE-005 | AiChatService.chatProactive（不写伪造学生消息、nudge 指令追加 system 层、复用双层安全管线） | ✅ 完成 | 阶段 1 后端，不污染记忆 |
| WAKE-006 | ConversationService.sendNudgeStream + ChatController POST /nudge SSE 端点 + 护栏（2 次上限/间隔/escalated 拒绝） | ✅ 完成 | 阶段 1 后端 |
| WAKE-007 | tts-service 补 xiaotaiyang 人设（zh-CN-YunxiNeural）+ CosyVoice2 persona→speaker 映射（去硬编码"中文女"） | ✅ 完成 | 阶段 1 后端，功能三缺陷修复 |
| WAKE-008 | 后端单元/集成测试（问候含昵称、决策模型用例含画像信号 F、护栏、记忆不污染、xiaotaiyang 男声） | ✅ 完成 | 阶段 1 后端，161 个测试全绿 |
| WAKE-009 | ChatRoom 沉默检测计时器 + nudge 调用 + TTS 朗读 + 护栏（2 次上限/说话重置/与录音互斥） | ✅ 完成 | 阶段 2 前端 |
| WAKE-010 | useWakeWord（Whisper）+ useVoiceCallMode 状态机 + VoiceCallConsentDialog 单独授权 + BoBoPet waitingWake 态 + ChatRoom 集成 | ✅ 完成 | 阶段 3，**已从 Porcupine 切换为 Transformers.js + Whisper**（零外部账号），构建通过 |
| WAKE-011 | ~~.env 增加 AccessKey + Picovoice Console 训练唤醒词~~ | ❌ 已取消 | Whisper 开源方案无需任何外部账号/密钥/训练 |
| WAKE-012 | 集成回归（按住说话主路径/红色风险流程不受影响）+ 真机测试（唤醒率/防自听回声/冷却关窗/iOS 兼容） | 🟡 代码回归完成 | 阶段 4：224 单测全绿 + 三端构建通过 + 主路径/风险流程代码完整性验证；**真机测试待执行**（需物理设备） |
| WAKE-013 | 登录页三主题风格落地（design/demo 三 HTML → LoginPage.jsx 实施） | ✅ 完成 | 2026-07-28；ocean/garden/rainbow 动画背景 + 彩虹键盘(0占两格/无✓) + 一体化语音唤醒勾选框 + 主题切换浮标 + Pad 横屏左品牌右表单；三端构建通过 |

---

## 十七、产品全景优化规划（design/30）

> 来源：项目全面审计 + 业界对标（Woebot/Wysa/心潮），覆盖 10 大方向。
> 详细设计见 `design/30_产品全景优化规划.md`，此处仅列任务 ID 与状态。

### AI 对话质量与智能化（P0）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| AI-001 | 对话质量评估指标体系（共情度/CBT 完成度/安全/满意度） | ✅ 完成 | B |
| AI-002 | LLM-as-Judge 自动评估管线（异步抽样 + 低分标记） | ✅ 完成 | B |
| AI-003 | 教师端质量监控增强（AI 评分可视化 + 抽检回放） | ✅ 完成 | C |
| AI-004 | 多模型路由（DeepSeek 主 + 通义/GLM 备，故障自动切换） | ✅ 完成 | B |
| AI-005 | Prompt 版本管理与 A/B 测试框架 | ✅ 完成 | C |
| AI-006 | RAG 心理知识库（Spring AI VectorStore + pgvector） | ✅ 完成 | E |
| AI-007 | 语音情感分析 SER（emotion2vec+，风险辅助信号） | 🟡 基础已实现 | voice-service 已完整实现 ASR(SenseVoiceSmall)+SER(emotion2vec_plus_large 9类)+风险融合；**数据闭环深化见 design/47**（映射 44 currentEmotion/回注画像/趋势追踪/标注回流待实施） |
| AI-008 | 长期记忆增强（跨会话摘要 + 关键事件 + 画像回注） | 🟡 部分实现 | 关键事件提取+top5 回注+淘汰+**画像回注(MEM-101,2026-07-28)**+**主题演化+相关性召回(MEM-102,2026-07-28)**已实现；风险纵向关联/遗忘策略未实现。深化见 design/50 |
| AI-009 | 心理量表数字化（PHQ-A/GAD-7/SDQ 嵌入式） | 🟡 部分实现（M1 计分引擎完成，不接线施测） | design/34；SCALE-001 开发完成(2026-07-28)；SDQ/MHT 版权许可为发布门禁 |

### 安全合规与信任体系（P0）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| COMP-005 | 敏感数据加密存储（AES-256 + 密钥轮换） | 🟩 已接线（fix-03） | D — FieldEncryptionService 已注入 ConversationServiceImpl：学生/AI 消息 contentSummary 落库前 AES-256-GCM 加密，教师端读取（getSessionMessages/replaySession/export）与摘要生成（generateSummaryAsync）解密；明文数据兼容透传；带密钥回归守卫测试 FieldEncryptionWiring 断言落库密文可还原。未配密钥时降级明文（dev），prod fail-fast |
| COMP-006 | 操作审计日志（管理员/教师敏感操作留痕） | ✅ 完成 | D |
| COMP-007 | 年度合规审计报送（未保条例 §37） | ⏳ 待开始 | 远期 |
| COMP-008 | WebAuthn 设备认证（可选） | ⏳ 待开始 | 远期 |
| COMP-009 | voice-service 音频「转写即删」清理逻辑核实/补齐（22 §6.3 定稿承诺兑现：ASR/SER 完成后立即删除原始音频，仅留文本与情感特征值） | ✅ 完成（2026-07-28，见 design/22 §6.3 落地记录：voice-service finally 必删+删除日志留痕+mkstemp；Java 侧补 file-size-threshold 12MB 音频全程内存处理；日志不记音频/转写全文） | 近期（商用前） |
| COMP-010 | doc/ 历史物料违规表述扫描（非诊断表述底线：排查"诊断/治疗/心理咨询"等越界表述，出违规清单交钱敏健，25 §十 第 6 条） | ✅ 完成（2026-07-29，报告见 reports/COMP-010-doc物料违规表述扫描报告.md；真违规 7 类 24 处全在归档层，design/13 传导已修复；处置建议钱敏健 2026-07-29 全部确认：不改归档、封禁外发、doc/README 警示已加） | 近期（商用前） |

> COMP-001~004 为商务/法务流程，已移至「十八、商务与法务待办」。

### 工程质量与测试体系（P0）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| TEST-001 | 后端单测覆盖率 → 80%（JaCoCo 门禁） | 🟡 门禁已修真，基线 46%（目标 80%） | A — fix-10 已完成（2026-07-29）：counseling-app report-aggregate verify 阶段生成聚合报告；CI 门禁报告缺失即失败+行覆盖≥40%（当前 46%）；目标随迭代逐步升至 80% |
| TEST-002 | 前端组件测试（Vitest + Testing Library） | ✅ 已完成 | C |
| TEST-003 | E2E 扩展（12 → 30+ 用例） | ✅ 已完成 | C |
| TEST-004 | 性能压测基线（k6，100 并发 SSE） | ✅ 完成 | E |
| TEST-005 | CI 增强（覆盖率门禁 + 依赖扫描 + 缓存） | ✅ 完成（fix-10，2026-07-29） | A — fix-10 已修真：mvn verify（surefire+failsafe）替代 mvn test；Trivy exit-code=1 阻断 CRITICAL/HIGH；AuthFlowIT 正常执行（CI Docker）/本地 disabledWithoutDocker 优雅跳过；CI 触发分支加入 develop |
| TEST-006 | 前后端契约测试（OpenAPI + mock 校验） | ⏳ 待开始 | 远期 |

### DevOps 与运维能力（P1）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| OPS-001 | CD 自动化（CI → 镜像 → Registry → 部署） | ✅ 完成 | E |
| OPS-002 | Docker 镜像版本化（Git SHA tag + ACR） | ✅ 完成 | E |
| OPS-003 | 结构化日志 + 链路追踪（JSON + traceId） | ✅ 完成 | B |
| OPS-004 | 告警体系（AlertManager → 企微 webhook） | ✅ 完成（fix-05 接线） | B — SlaEscalationScanner @Scheduled 每分钟扫描 open/claimed 且超 SLA 的风险事件，AlertSlaPolicy 判定 escalate→CRITICAL / remind→WARNING，经 AlertService 出口（企微 webhook / 日志降级）发出，内存去重防风暴；SlaEscalationScannerTest 6 用例守卫。审计 P-05：此前红色风险无在线教师时仅 WARN 日志静默丢弃，现已接兜底告警。备注：教师端「自动改派备份老师」的改派动作仍归 WB-001 |
| OPS-005 | 数据库自动备份（pg_dump + 异地 + 恢复演练） | ✅ 完成 | A |
| OPS-006 | 蓝绿/滚动部署 | 📝 设计完成，待实施 | design/42（滚动+蓝绿+expand-contract） |
| OPS-007 | 多环境管理（dev/staging/prod） | ✅ 完成（fix-07 修真） | E — 审计 R-04：docker-compose.prod.yml 此前**从未设置 SPRING_PROFILES_ACTIVE=prod**，application-prod.yml 为死配置，JWT/加密 fail-fast 守卫全部沉默、Swagger 生产开放。fix-07 已修：compose 激活 prod profile + 补 ENCRYPTION_KEY/告警 webhook 映射；application-prod.yml 修 OPENAI→DeepSeek 漂移、删除非 root 不可写的 /var/log 文件日志（logback prod 本为 JSON stdout）；.env.example 全占位化；AdminTenantController 默认密码改 SecureRandom 随机；AliyunSmsService @PostConstruct 凭证 fail-fast |
| OPS-008 | 种子数据生产清理（V27） | ✅ 完成（fix-09） | 审计 R-05：V6 迁移注释明文泄露 minjianq 临时密码、MINDSAFE-TRIAL-001/002/003 硬编码邀请码存活。V27：minjianq password_hash 置无效哈希（限定原泄露哈希，已改密不覆盖）+ 三 TRIAL 码 disabled。裁决（钱敏健 2026-07-28）：DEMO2026 保留（V26 已延期，且 TrialAuthService 按固定试用租户查码，禁租户会断演示链路）；DEV/TRIAL 租户保留 active。V4 测试账号已由 V25 禁用；V8 演示学生因插入条件与 V4 冲突从未生效 |

### 数据智能与效果验证（P1）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| DATA-001 | 干预效果量化（前后量表对比 + 统计显著性） | ✅ 完成 | C |
| DATA-002 | 学生成长轨迹（学期情绪曲线 + 里程碑） | ✅ 完成 | C |
| DATA-003 | 校级报告自动生成（月度/学期 PDF） | ✅ 完成 | C |
| DATA-004 | 预警追踪闭环（预警→处置→回访→评估） | ✅ 完成 | C |
| DATA-005 | 研究数据脱敏导出（IRB 兼容） | ⏳ 待开始 | 远期 |

### 商业化与规模化（P2）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| BIZ-001 | 多租户生产化（独立 Schema + 自动迁移） | ✅ 完成 | D |
| BIZ-002 | 企微/钉钉 OAuth 配置上线 | ⏳ 待开始 | D |
| BIZ-003 | 阿里云 SMS 签名/模板申请 | ⏳ 待开始 | D |
| BIZ-004 | 计费与配额（按学校/学生数） | 📝 设计完成，待实施 | design/38（订阅-权益-计量-配额） |
| BIZ-005 | 信创适配评估（达梦/人大金仓） | 📝 设计完成，待实施 | design/41（迁移风险清单+方言层+向量三路径） |
| BIZ-006 | 运营后台（平台级学校管理/收入/SLA） | 📝 设计完成，待实施 | design/38 §六 |

### 性能与可扩展性（P2）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| PERF-001 | LLM 响应优化（首 token < 1s + 超时降级 + 重试 + 主备模型 + 监控埋点） | ✅ 完成 | E |
| PERF-002 | 数据库优化（慢查询 + 索引 + 连接池） | ✅ 完成 | E |
| PERF-003 | CDN + 前端代码分割 | 📝 设计完成，待实施 | design/42 §四（CDN 缓存分层+manualChunks） |
| PERF-004 | Redis 缓存策略（画像/状态/配置） | ✅ 完成 | E |
| PERF-005 | 水平扩展（无状态 Session + LB + SSE 广播） | 📝 设计完成，待实施 | design/40（12-Factor 无状态化+Redis Pub/Sub） |
| PERF-006 | TTS 流式透传 + 前端切句优化（首句更快出声） | ✅ 完成 | E |

### 用户体验与交互升级（P2）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| UX-001 | 学生端 onboarding 优化 | ✅ 已完成 | E |
| UX-002 | 教师端工作台改版 | ✅ 实质完成（WB-001/002/003 + F-3 余量补全，余量见 design/35） | design/35（Sprint E） |
| UX-003 | 多语言支持（繁体/英文） | ⏳ 待开始 | 远期 |
| UX-004 | 无障碍增强（WCAG 2.1 AA） | ⏳ 待开始 | 远期 |
| UX-005 | 动效与微交互（Lottie + 粒子） | 📝 设计完成，待实施 | design/37 §四 |

---

## 十八、商务与法务待办（非开发任务）

> 以下事项为商业化发布的前置合规/行政流程，责任人为钱敏健，需外部机构配合，不涉及代码开发。

| 编号 | 事项 | 负责方 | 状态 | 备注 |
|------|------|--------|------|------|
| COMP-001 | 等保二级测评（差距评估 + 整改 + 测评机构出报告） | 钱敏健 + 测评机构 | ⏳ 待启动 | 教育系统采购硬门槛，审核周期 1-3 月；差距评估已完成（design/31） |
| COMP-002 | 算法备案（生成式 AI，网信办） | 钱敏健 + 法务 | ⏳ 待启动 | 需企业主体 + 算法说明文档 + 安全评估报告 |
| COMP-003 | 教育 App 备案（教育部） | 钱敏健 + 学校 | ⏳ 待启动 | 进校前提，需学校配合提供办学资质 |
| COMP-004 | 告知同意条款法务审定 | 法务律师 | ⏳ 待启动 | 需出具法律意见书，覆盖 PIPL + 未保法 |
| BIZ-002 | 企微/钉钉 OAuth 配置上线 | 钱敏健 + 学校 IT | ⏳ 待配置 | 代码已就绪，需学校提供 corpId/corpSecret |
| BIZ-003 | 阿里云 SMS 签名/模板申请 | 钱敏健 | ⏳ 待申请 | 需企业营业执照 + 审核 3-7 天 |
| AUTH-033 | 年度合规审计报送（未保条例 §37） | 钱敏健 + 法务 | ⏳ 上线后 1 年内 | 流程性报送，非代码 |
| DEPLOY-010 | 阿里云 SMS 签名/模板（同 BIZ-003） | 钱敏健 | ⏳ 待申请 | 与 BIZ-003 同一事项 |

---

## 十九、文档全面更新（2026-07-28）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| DOC-031 | 新增 design/33 系统测试培训手册（面向测试人员） | ✅ 完成 | 446 行，含功能测试点/API 速查/关键场景/安全机制/测试工具 |
| DOC-032 | design/25 产品功能说明升级 v2.0 | ✅ 完成 | 补充声纹登录/唤醒词/波波宠物/家庭码/RAG/年龄适配/冷场暖场/回访闭环/知识库等 |
| DOC-033 | DESIGN-OVERVIEW v3.0（+31/32/33 目录） | ✅ 完成 | 33 份设计文档导航 |
| DOC-034 | BEACON.md 当前状态同步 | ✅ 完成 | 反映 Sprint A-E 完成/安全加固/声纹/CI/CD/RAG |
| DOC-035 | TASK-TRACKER 同步 | ✅ 完成 | 本章节 |
| DOC-036 | 逐份核对 design/01~32 与代码一致性 | ✅ 完成 | 见下方核对记录 |

### DOC-036 核对记录

| 文档 | 核对结论 | 处理 |
|------|----------|------|
| 01 产品架构图 | 产品定位/分层/权限矩阵与实现一致 | 无需修改 |
| 02 Prompt 体系 | Advisor 链顺序/安全策略与实现一致 | 无需修改 |
| 03 CBT 流程树 | 状态机流程与实现一致 | 无需修改 |
| 04 风险识别规则库 | 2026-07-23 已对齐（§十七实现类名） | 无需修改 |
| 05 老师后台设计 | 核心模块一致，实际简化了部分（测评/预约未做） | 属 YAGNI 裁剪，无需修改 |
| 06 数据库结构设计 | 核心表一致，新增 12+ 扩展表 | ✅ 已补实现扩展说明 |
| 07 SaaS 多学校隔离 | Schema 级隔离/JWT 鉴权与实现一致 | 无需修改 |
| 08 MVP 最小可行版本 | M1-M4 全部完成，与实现一致 | 无需修改 |
| 09 商业模式 | 参考类文档，无代码对应 | 无需修改 |
| 10 政策与合规 | 参考类文档，合规机制已实现 | 无需修改 |
| 11 竞品分析 | 参考类文档，无代码对应 | 无需修改 |
| 12 技术架构 | 前端/网关/状态管理有偏差 | ✅ 已补实现偏差说明 |
| 13 Agent 工作流 | 2026-07-23 已改写为 Spring AI | 无需修改 |
| 14 儿童安全对话规范 | 2026-07-23 已对齐类名 | 无需修改 |
| 15 心理知识库建设 | RAG 已实现（AI-006），与方案一致 | 无需修改 |
| 16 API 接口设计 | 新增 15+ 端点未覆盖 | ✅ 已补实现偏差说明+新增端点清单 |
| 17 前端架构设计 | 技术栈/结构/状态管理有偏差 | ✅ 已补实现偏差说明 |
| 18 Prompt 模板库 | 2026-07-23 已对齐+登记 TSK-004 | 无需修改 |
| 19 界面详细设计 | 目标设计，实际简化实现 | 保留参考 |
| 20 端到端流程设计 | 核心流程与实现一致 | 无需修改 |
| 21 认证与试用准入 | 已实现（Phase 9-10） | 无需修改 |
| 22 告知同意条款 | 草稿待法务审定，非代码文档 | 无需修改 |
| 23 学生心理画像 | 已实现（PROF-001~004） | 无需修改 |
| 24 身份认证优化 | 已实现（AUTH-010~034） | 无需修改 |
| 25 产品功能说明 | 本次升级 v2.0 | ✅ 已更新 |
| 26 家长端 H5 | P1 已实现，P2 远期 | 无需修改 |
| 27 波波小精灵 | 已实现（BOBO-001~006） | 无需修改 |
| 28 语音唤醒与冷场引导 | 阶段 1-3 已实现 | 无需修改 |
| 29 学生画像与年龄适配 | P0+P1+P2 已实现 | 无需修改 |
| 30 产品全景优化规划 | Sprint A-E 核心完成 | 无需修改 |
| 31 等保二级差距评估 | 合规文档，非代码 | 无需修改 |
| 32 商用发布前置待办 | 行政文档，非代码 | 无需修改 |

---

## 二十、设计补充完善专题（2026-07-28）

> 背景：design/30 §十六要求“具体功能实施前需编写对应详细设计”。本专题基于业界最佳实践调研（MBC/SAMHSA、Wysa/Woebot、Lago/Stripe Billing、offline-first PWA、整群随机实验）一次性补齐 6 份远期任务设计文档，均为设计期、未实施。

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| DOC-037 | 输出 design/34 心理量表数字化设计方案（AI-009） | ✅ 完成 | MBC 闭环/计分引擎/S0 熔断/版权门禁 |
| DOC-038 | 输出 design/35 教师端工作台改版设计（UX-002） | ✅ 完成 | Today View/预警工作流/降噪/个案落地 |
| DOC-039 | 输出 design/36 心理工具箱与离线缓存设计（PROD-006/007） | ✅ 完成 | SOS/安全小岛/离线队列幂等重放 |
| DOC-040 | 输出 design/37 情感化TTS与动效微交互设计（PROD-003/UX-005，docs/17 补白） | ✅ 完成 | 情绪信号源统一/风险语音降级/动效预算 |
| DOC-041 | 输出 design/38 计费配额与运营后台设计（BIZ-004/006） | ✅ 完成 | 订阅-权益-计量-配额/危机链路豁免红线 |
| DOC-042 | 输出 design/39 画像效果量化与A/B实验设计（PROF-020） | ✅ 完成 | 班级整群随机/两层指标/护栏自动停 |
| DOC-043 | 同步 DESIGN-OVERVIEW v3.1 / TASK-TRACKER / BEACON | ✅ 完成 | 本章节 |

关联任务状态联动：AI-009 / UX-002 / BIZ-004 / BIZ-006 / UX-005 / PROF-020 均由“待开始/远期”更新为“📝 设计完成，待实施”；PROD-003/006/007 在 design/30 登记，对应设计见 design/36、37。

---

## 二十一、架构重构级规划（2026-07-28）

> 背景：design/30 将“无状态化水平扩展、信创适配、部署升级、家长端小程序化”列为**远期、规模化触发**的架构重构级任务。本专题基于业界最佳实践调研（The Twelve-Factor App 无状态化/Redis Pub/Sub 广播、信创 KingbaseES/达梦 DM8 迁移工具链、蓝绿·滚动·金丝雀发布 + expand-contract 兼容迁移、CDN 缓存分层 + Vite manualChunks 代码分割、Taro 一码多端）一次性补齐 4 份架构级设计文档，均为设计期、未实施、不含实现代码。

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| DOC-044 | 输出 design/40 水平扩展与无状态化架构设计（PERF-005） | ✅ 完成 | 12-Factor/WebSocket Redis Pub/Sub/会话状态外置/Prompt 缓存迁 Redis/nginx upstream |
| DOC-045 | 输出 design/41 信创数据库适配设计（BIZ-005/DEC-006/RISK-004） | ✅ 完成 | PG→金仓/达梦迁移风险清单 R1~R9/可插拔方言层/向量检索三路径/评估→转换→迁移→回归 |
| DOC-046 | 输出 design/42 部署架构升级设计（OPS-006/PERF-003） | ✅ 完成 | 滚动+蓝绿零停机/expand-contract 兼容迁移/CDN 缓存分层/manualChunks 代码分割 |
| DOC-047 | 输出 design/43 家长端小程序化设计（PROD-008/PARENT-WX-001~006） | ✅ 完成 | Taro React→weapp+H5 迁移，修正 design/26 “已是 Taro”表述不一致（实为 Vite+React SPA） |
| DOC-048 | 同步 DESIGN-OVERVIEW v3.2 / TASK-TRACKER / BEACON | ✅ 完成 | 本章节 |

关联任务状态联动：OPS-006 / PERF-003 / PERF-005 / BIZ-005 / AUTH-022 / PARENT-WX-001~006 均由“待开始/远期”更新为“📝 设计完成，待实施”，分别指向 design/40~43。

> ✅ 已修正（2026-07-28）：design/26 升级 v1.1，§2/§2.1/§2.2/§3.1/§7 的 “Taro 4 工程”表述已统一修正为“P1=Vite+React SPA（当前）、P2=迁 Taro（远期）”，与实际代码及 §3/§5 一致，并指向 design/43。

---

## 二十二、提示词个性化动态编排专题（2026-07-28）

> 背景：钱敏健提出“深度结合年龄/心理画像/进入心情状态 + 合规底线的个性化提示词设计，要动态变化”。调研现状代码后定位最大缺口：“进入心情状态”仅在 SYS-001 占位不驱动策略，且四维为静态拼接无编排层。基于业界最佳实践（Woebot/Wysa mood check-in、SAMHSA/MBC）与心理专业理论（皮亚杰/容纳之窗/WHO PFA/情绪ABC/MI/SEL/依恋理论）输出设计，设计期、未实施、不含实现代码。

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| DOC-049 | 输出 design/44 个性化提示词动态编排引擎设计（PROF-021） | ✅ 完成 | PromptOrchestrationService/StrategyProfile、情绪→开场与回应策略映射、情绪门控 CBT、情绪状态机会话内漂移切换、合规优先级裁决、EMO-001 模板 |
| PROF-021 | 提示词个性化动态编排引擎实施（情绪驱动策略 + 四维编排 + 情绪状态机） | 📝 设计完成，待实施 | design/44；依赖 design/29 年龄接通 P0；效果入 design/39 A/B |
| DOC-050 | 同步 DESIGN-OVERVIEW v3.3 / TASK-TRACKER | ✅ 完成 | 本章节 |

关联任务状态联动：本专题与 design/29（年龄/性格适配）、design/28（冷场）、design/39（A/B 量化）互补；PROF-021 为新增任务，统筹四维个性化编排，与 PROF-010~014（design/29）/PROF-020（design/39）同属提示词工程个性化能力线。

---

## 二十三、设计驱动开发任务总表（按优先级，2026-07-28）

> 背景：钱敏健要求将 design/34~44 设计补充/提升衍生的开发任务，按优先级统一登记为可执行 backlog（**暂不开发**）；近期与远期均登记，远期显式标注。
> 定位：本表是全部“待实施”开发任务的**优先级排序单一视图**。各行拆分自对应设计文档自带的实施里程碑（M1/M2/M3 或 P/W/D/M-x 阶段），各设计的单行主任务（AI-009 / UX-002 / PROF-021 / PERF-005 / BIZ-005 等）仍保留在对应功能分区，本表是其阶段级细化与统一排序。
> 状态统一为 ⏳ 待实施（未开发）。优先级判据：**安全/合规 > 对话产品力 > 教师效率 > 学生体验 > 商业化 > 规模化架构**。近期=M4 上线前后应做；远期=规模化/采购/版权/企业认证触发。

### P0 · 近期（安全兜底 + 对话力核心）

| 任务ID | 阶段任务 | 期段 | 来源设计 | 关联主任务 | 状态 |
|--------|----------|------|----------|-----------|------|
| ORCH-001 | 提示词编排引擎骨架 + StrategyProfile + EntryMoodStrategyResolver（情绪→开场/回应策略）+ EMO-001 模板 + 接入 chat() 组装链 | 近期 | design/44 P0 | PROF-021 | ✅ 已完成（2026-07-28） |
| ORCH-002 | 情绪门控 allowCbt（ACTIVATED/CRISIS 禁认知重构） | 近期 | design/44 P0 | PROF-021 | ✅ 已完成（2026-07-28） |
| SCALE-001 | 量表计分引擎 + PHQ-A/GAD-7（免费量表先行，✅ 钱敏健 2026-07-28 确认）+ 关键条目即时熔断（S0 预警）；**施测已定稿暂缓（2026-07-28）：完成开发不接线，待首校共定施测方案（34 头部），退出商用门禁** | 近期 | design/34 M1 | AI-009 | ✅ 开发完成（2026-07-28，不接线施测） |

### P1 · 近期（对话力延展 + 教师效率 + 学生体验）

| 任务ID | 阶段任务 | 期段 | 来源设计 | 关联主任务 | 状态 |
|--------|----------|------|----------|-----------|------|
| ORCH-003 | 情绪状态机 + 会话内情绪漂移切换（sad→crisis 升级 / anxious→calm 缓解） | 近期 | design/44 P1 | PROF-021 | ✅ 已完成（2026-07-28） |
| ORCH-004 | 情绪镜映话术库（情绪×年龄，纳入模板） | 近期 | design/44 P1 | PROF-021 | ✅ 已完成（2026-07-28） |
| ORCH-005 | 优先级裁决合并 + 冷场(28)/降级(29) 统一入编排 | 近期 | design/44 P1 | PROF-021 | ✅ 已完成（2026-07-28） |
| WB-001 | 教师工作台首屏（待办+时间线+概况条）+ 预警工作流（认领/处理/关闭 + SLA 逾期提醒） | 近期 | design/35 M1 | UX-002 | ✅ 已完成（2026-07-28：后端 AlertSlaPolicy + 前端 TodayTodoPanel/SLA倒计时列/预警时间线） |
| WB-002 | 学生详情页统一落地页 + 五角色字段裁剪 + 降噪（合并/聚合/静音） | 近期 | design/35 M2 | UX-002 | ✅ 已完成（2026-07-28：前端详情页 + 服务端角色裁剪；**F-3 补齐降噪静音规则 AlertTodoMutePolicy + 个案跟踪标志（免 schema 变更）TDD 全绿**） |
| TOOL-001 | 心理工具箱框架 + 情绪温度计 + 接地 + 正念（呼吸并入）+ 前后心情记录，内容包可离线打开 | 近期 | design/36 M1 | PROD-006 | ✅ 已完成（2026-07-28，后端 ToolboxRegistry+MoodCheckRecorder；前端 ToolboxPanel/ToolPractice/ChatRoom 入口 TDD 全绿，内容包余量见 design/36） |
| TOOL-002 | SOS 模式 + 安全小岛（断网可打开、热线可拨号，恢复网络 1min 内产 S2 事件） | 近期 | design/36 M2 | PROD-006/007 | ✅ 已完成（2026-07-28，后端 SOS 工具列表；前端 SosPanel 纯静态三段式+12355 拨号 TDD 全绿；S2 事件端点/安全小岛创建流程余量见 design/36） |
| TTSFX-001 | 情绪信号源统一 + 波波表情状态机 + 基础微交互（气泡/输入/思考中）+ 减弱动效降级 | 近期 | design/37 M1 | UX-005 | ✅ 已完成（2026-07-28，情绪信号源统一入编排；**✅ 以后端信号源为准，前端动效余量归 TTSFX-004**） |
| TTSFX-002 | 风险语音降级 + 预合成话术库 + 缓存（S1 用预合成、CosyVoice2 超时 2s 内切 edge-tts/纯文字） | 近期 | design/37 M2 | PROD-003 | ✅ 已完成（2026-07-28，VoiceDegradationPolicy） |
| SCALE-002 | 量表任务调度 + 复测 recurrence + 教师端趋势卡片；**施测已定稿暂缓同 SCALE-001（2026-07-28，完成开发不接线，34 头部）** | 近期 | design/34 M2 | AI-009 | ✅ 开发完成（2026-07-28，RecurrenceCalculator，不接线施测） |

### P2 · 近期偏后（编排精调 + 效果验证 + 商业化底座）

| 任务ID | 阶段任务 | 期段 | 来源设计 | 关联主任务 | 状态 |
|--------|----------|------|----------|-----------|------|
| ORCH-006 | 性格层微调并入编排（衔接 design/29 personality_traits） | 近期 | design/44 P2 | PROF-021 | ✅ 已完成（2026-07-28） |
| ORCH-007 | EMO-001 A/B 版本（不同开场策略）经 PromptVersionService 灰度 | 近期 | design/44 P2 | PROF-021 | ✅ 已完成（2026-07-28） |
| WB-003 | 个案管理 + 测评管理入口（依赖 SCALE-002） | 近期 | design/35 M3 | UX-002 | ✅ 已完成（**F-3 补齐预警转派端点 + 五角色默认落地页差异化路由，teacher-web 新增 vitest 测试基建 TDD 全绿**） |
| TOOL-003 | 离线检测 UI + 消息队列 + 重放幂等 + 本地对话缓存 | 近期 | design/36 M3 | PROD-007 | ⛔ 已回退（2026-07-28 审计：OfflineMessageReplayService 为假接线已清除，目标态保留 design/36） |
| TTSFX-003 | 延迟流水线 + 成就粒子 + 触觉 + 帧率性能自动降级 | 近期 | design/37 M3 | UX-005 | ✅ 已完成（**后端信号源为准；前端动效余量归 TTSFX-004**） |
| TTSFX-004 | 37 前端动效余量：BoBoPet 表情状态机接编排信号 + Lottie 动效层（微交互/成就粒子/触觉）+ 帧率降级与减弱动效的前端实现 + 低端机延迟播放 | 近期 | design/37 审计余量（design/37 落地记录） | UX-005/TTSFX-001 | ✅ 已完成（2026-07-28，见 design/37 TTSFX-004 实施记录，TDD 覆盖 ≥80%） |
| AB-001 | 实验模型 + 班级整群分桶 + 变体注入点 + 曝光日志 + 个案豁免 | 近期 | design/39 M1 | PROF-020 | ⛔ 已清除（2026-07-28 审计判死：注入后零业务消费纯装饰日志，目标态保留 design/39） |
| AB-002 | 指标采集（三表情满意度反馈 + 风险属实勾选 + 各聚合任务） | 近期 | design/39 M2 | PROF-020 | ⛔ 已清除（2026-07-28 随 AB-001 一并清除，目标态保留 design/39） |
| BILL-001 | plans/entitlements/subscriptions 模型 + EntitlementFilter（bool 权益）+ 危机链路豁免注解 | 近期 | design/38 M1 | BIZ-004 | ✅ 已完成（2026-07-28） |
| BILL-002 | 计量事件流 + quota 执行 + 429 头 + 阈值告警 + 学校用量视图 | 近期 | design/38 M2 | BIZ-004 | ⛔ 未实现（2026-07-28：quota 代码按 YAGNI 清除，仅 bool 权益保留于 EntitlementChecker；接入计量后按 design/38 重建） |

### 远期（规模化 / 采购 / 版权 / 企业认证触发）

> 触发条件未到前不启动；均为设计期、未实施。数据库迁移类含红线操作（AGENTS §5 红线 3），实际执行须单独授权。

| 任务ID | 阶段任务 | 期段 | 来源设计 | 关联主任务 | 状态 |
|--------|----------|------|----------|-----------|------|
| SCALE-003 | SDQ 三版本 + MHT + 家长版 H5（**版权 license 校验为发布门禁**） | 🔭 远期 | design/34 M3 | AI-009 | ⏳ 待实施 |
| ORCH-008 | 情绪编排效果量化并入 design/39 A/B（稳定回落速度/会话深度/满意 度） | 🔭 远期 | design/44 P3 | PROF-021 | ✅ 已完成 |
| AB-003 | 月度分析任务 + 平台实验报告页（含置信区间）+ 护栏指标越界自动停 | 🔭 远期 | design/39 M3 | PROF-020 | ⏳ 待实施 |
| BILL-003 | 订阅生命周期自动流转（grace/expired）+ 平台运营后台六模块 | 🔭 远期 | design/38 M3 | BIZ-006 | ⏳ 待实施 |
| STATE-001 | Prompt 缓存迁 Redis（改造面小、无长连接） | 🔭 远期 | design/40 P5-1 | PERF-005 | ⏳ 待实施 |
| STATE-002 | 会话状态外置 ConversationStateManager（双写灰度→切换） | 🔭 远期 | design/40 P5-2 | PERF-005 | ⏳ 待实施 |
| STATE-003 | WebSocket 预警 Redis Pub/Sub 广播 | 🔭 远期 | design/40 P5-3 | PERF-005 | ⏳ 待实施 |
| STATE-004 | nginx upstream + 后端多副本（与 DEP-011 共用） | 🔭 远期 | design/40 P5-4 | PERF-005 | ⏳ 待实施 |
| STATE-005 | 多实例压测（500 并发 SSE + 预警广播送达率 ≥99%） | 🔭 远期 | design/40 P5-5 | PERF-005 | ⏳ 待实施 |
| DBAD-001 | 信创兼容性评估（KDMS/DTS 扫描 + R1~R9 逐项实测结论） | 🔭 远期 | design/41 M-0 | BIZ-005/DEC-006 | ⏳ 待实施 |
| DBAD-002 | 可插拔方言层（JsonTypeHandler/数据源路由/SQL 方言 + db-* profile，PG 仍默认） | 🔭 远期 | design/41 M-1 | BIZ-005 | ⏳ 待实施 |
| DBAD-003 | Schema 转换（目标库 DDL + 类型人工修正 JSONB/vector/序列） | 🔭 远期 | design/41 M-2 | BIZ-005 | ⏳ 待实施 |
| DBAD-004 | 数据迁移 + 行数/校验和/抽样一致性校验（**红线：须授权**） | 🔭 远期 | design/41 M-3 | BIZ-005 | ⏳ 待实施 |
| DBAD-005 | 向量方案落地（按 design/41 §四选定路径迁移 RAG） | 🔭 远期 | design/41 M-4 | RISK-004 | ⏳ 待实施 |
| DBAD-006 | 应用回归 + PG↔信创双跑对比 + 运维工具链适配 | 🔭 远期 | design/41 M-5/M-6 | BIZ-005 | ⏳ 待实施 |
| DEP-011 | nginx 单点 → upstream 池（与 STATE-004 共用，先落地） | 🔭 远期 | design/42 D-1 | OPS-006 | ⏳ 待实施 |
| DEP-012 | 多副本 + start-first 滚动发布（强依赖无状态化 STATE-*） | 🔭 远期 | design/42 D-2 | OPS-006 | ⏳ 待实施 |
| DEP-013 | 优雅关闭 + LB 摘除/draining 协同 | 🔭 远期 | design/42 D-3 | OPS-006 | ⏳ 待实施 |
| DEP-014 | 蓝绿双环境 + upstream 切换 + 冒烟门禁（秒级回滚） | 🔭 远期 | design/42 D-4 | OPS-006 | ⏳ 待实施 |
| DEP-015 | 前端代码分割（学生端优先，manualChunks + 路由懒加载） | 🔭 远期 | design/42 D-5 | PERF-003 | ⏳ 待实施 |
| DEP-016 | CDN 接入 + 缓存策略（仅公共静态资源，绝不缓存含 PII 响应） | 🔭 远期 | design/42 D-6 | PERF-003 | ⏳ 待实施 |
| PARENT-WX-001~006 | 家长端小程序化（Taro 迁移 W-1~W-7，企业主体认证为门禁）——**详见「十二、家长端 H5」P2 分表** | 🔭 远期 | design/43 | AUTH-022 | ⏳ 待实施 |

### design/45~50 深化设计衍生任务（2026-07-28 新增，闭环化专题）

> 背景：钱敏健要求对 6 大专题（提示词工程/画像/语音情感/多音色/知识库/长期记忆）全面深化，产出 design/45~50 独立文档。以下为其衍生开发任务，**暂不开发**，仅登记排序。优先级判据同上。
> 关键更正：AI-008「长期记忆」原「✅完成」与代码不符（画像回注/主题演化/风险关联未做），已在「十七·AI 对话质量」更正为 🟡 部分实现；AI-007「语音情感 SER」基础已实现，更正为 🟡。

| 任务ID | 阶段任务 | 优先级 | 来源设计 | 关联主任务 | 状态 |
|--------|----------|--------|----------|-----------|------|
| PEVAL-001 | 接线未调用的 evaluateConversationQuality 到会话结束异步流程 + 落库 prompt_eval_result（四维分+版本+人群维度） | P0 近期 | design/45 P0 | AI-002/PROF-021 | ✅ 已完成（2026-07-28） |
| PEVAL-002 | 补全 EMO-001 模板正文并纳入 PromptVersionService（配合 ORCH-001） | P0 近期 | design/45 P0 | PROF-021 | ✅ 已完成（2026-07-28） |
| PEVAL-003 | 模板矩阵登记+版本命名规范 + 红队护栏用例集资产化 + 改版三门禁（红队/审校/eval 不回退） | P1 近期 | design/45 P1 | AI-005 | ✅ 已完成（2026-07-28；G-1 补齐门禁接线：RedTeamRegressionRunner 静态回归+activateVersion 三门禁+audit_logs 审批留痕，护栏 14 条/6 类，测试 55 用例全绿） |
| PEVAL-004 | 评估人群下钻看板 + 提示词 metrics + 灰度分阶段放量/自动回滚 + LLM-as-Judge κ 校准 | P2 近期偏后 | design/45 P2 | AI-003 | ✅ 已完成（2026-07-28） |
| PROF-025 | 画像字段加 provenance/confidence/updated_at/decay 元数据 + 画像→StrategyProfile 结构化决策接线（低置信不参与）；**原名 PROF-022，2026-07-28 审计发现与 L220 初高中学段适配挂账同号，改 PROF-025 消歧** | P0 近期 | design/46 P0 | PROF-021/AI-008 | ✅ 已完成（2026-07-28） |
| PROF-023 | 画像合并门控（置信加权+冲突检测+时效衰减）+ 量表结果回写画像 + 质量评估四维 | P1 近期 | design/46 P1 | AI-009 | ✅ 已完成（2026-07-28） |
| PROF-024 | 画像效果回收（有/无画像会话质量对比接 39/45）+ 无效维度降权自校准 + 教师侧脱敏摘要与订正回流 | P2 近期偏后 | design/46 P2 | PROF-020 | ✅ 已完成（2026-07-28） |
| VCL-001 | 语音情绪映射进 44 currentEmotion 驱动共情策略 + 会话结束聚合回注画像 emotionBaseline | P0 近期 | design/47 P0 | AI-007/PROF-021 | ✅ 已完成（2026-07-28） |
| VCL-002 | voice_emotion_trend 跨会话趋势 + 文本×语音融合与不一致(掩饰)检测 | P1 近期 | design/47 P1 | AI-007 | ✅ 已完成（2026-07-28） |
| VCL-003 | 趋势异常→教师关注信号+量表复测 + SER 标注回流评估儿童 domain 准确度 + 分类阈值自适应 | P2/远期 | design/47 P2/P3 | AI-007 | ✅ 已完成（2026-07-28） |
| TMATCH-001 | VoicePersonaResolver 冷启动默认匹配（性别认同/年龄）+ emotionState→prosody 基调联动（非仅 instruct） | P0 近期 | design/48 P0 | UX-005 | ✅ 已完成（2026-07-28） |
| TMATCH-002 | 画像匹配微调+手动偏好记忆回写46 + 安全/危机稳定基调锁定+预合成矩阵（统一 TTSFX-002）+ 三方同源接线 | P1 近期 | design/48 P1 | UX-005/PROD-003 | ✅ 已完成（2026-07-28） |
| TMATCH-003 | 音色效果回收（完成率/切换/参与度）+ 会话内稳定性 + 匹配规则 A/B 进化 | P2/远期 | design/48 P2/P3 | PROF-020 | ✅ 已完成（2026-07-28） |
| KB-101 | 知识内容首批生产（CBT/SEL/PFA/危机/工具箱，结构化 02/03/36）+ RAG Advisor 接入对话主线（场景触发+年龄过滤+不覆盖安全） | P0 近期 | design/49 P0 | AI-006 | ✅ 已完成（2026-07-28）：62 条语料解析+幂等入库机制（`POST /api/v1/knowledge/corpus`，危机类 10 条缓入待 KB-102）+ `RagAdvisorService` 接主线（场景触发/grade_band 近似过滤/危机双保险/失败安全），解 AI-006 门禁，见 design/49 §6.5 |
| KB-102 | 审核工作流状态机+门禁 + 知识条目元数据增强 + 危机内容与 04/14 单一事实源打通 | P1 近期 | design/49 P1 | AI-006 | ✅ 已完成（2026-07-28，ReviewWorkflowStateMachine+ReviewGateValidator+KnowledgeMetadata） |
| KB-103 | 混合检索 RRF（向量0.6+关键词0.4，落地 15 未实现项）+ groundedness 回收+未命中查询补全 + 语义分块优化 | P2/远期 | design/49 P2/P3 | AI-006 | ✅ 已完成（2026-07-28） |
| MEM-101 | **更正 AI-008 状态**（已在十七完成）+ 记忆→画像回注（growthTrack/socialGraph，provenance=memory） | P0 近期 | design/50 P0 | AI-008 | ✅ 已完成（2026-07-28） |
| MEM-102 | recurring_theme 主题演化（聚类+反思）+ 相关性召回升级（向量+重要性+时效+recurring）+ MEM-CTX+continuity 接 45 | P1 近期 | design/50 P1 | AI-008 | ✅ 已完成（2026-07-28，MemoryRelevanceScorer+ThemeEvolutionEngine） |
| MEM-103 | 记忆与风险纵向关联（负面主题→关注信号，非实时报警）+ 遗忘策略升级（时效/敏感/被遗忘权）+ 双向互哺权重调优 | P2/远期 | design/50 P2/P3 | AI-008 | ✅ 已完成（2026-07-28） |

### design/51~53 分析文档衍生（2026-07-28 新增，✅ 钱敏健 2026-07-28 全部确认）

> 背景：design/51（横向断链）/52（核心板块心理深化）/53（全板块设计-实现脱节）为分析型文档。其优化方向**绝大多数映射到上方已登记 ID**（ORCH-001~004/PEVAL-001/KB-101/PROF-025/WB-001/MEM-101~102/TMATCH-001/STATE-002~003/BILL-*/SCALE-*/AB-*），不重复登记。下表仅登记 design/52 衍生的**真正新增**项，**2026-07-28 钱敏健全部确认**，状态统一为 ⏳ 待实施（未开发）。总前提：“双世界架构”（世界A线上单 prompt vs 世界B 孤儿 Agent 编排）——见 design/52 〇。**DEC-CBT 重新决策：删除世界 B**（钱敏健 2026-07-29，推翻原「路径1激活」）——世界B 整链死代码、与 SSE 流式冲突、无接线价值，fix-02 删除，线上保留世界A 单 prompt 主线。

| 任务ID | 阶段任务 | 优先级 | 来源设计 | 依赖 | 状态 |
|--------|----------|--------|----------|------|------|
| DEC-CBT | 双世界编排收敛决策——**✅ 重新决策：删除世界 B（钱敏健 2026-07-29）**，推翻原「路径1激活」；世界B（ConversationOrchestrator+7 Agent+CbtStateMachine+ConversationStateManager）整链零调用死代码，无接线价值且与 SSE 流式体验冲突；线上保留世界A（PromptOrchestrationService 单 prompt 主线） | P0 决策 | design/52 〇/一/四；design/13 改写 | 无（最先） | ✅ 已决策删除（2026-07-29），fix-02 执行删除 |
| RISK-201 | RED 硬短路跳过 LLM，复用 CrisisResources（**儿童安全红线级，可独立立即做**） | **P0 安全最高** | design/52 二 | 无 | ✅ 已完成（2026-07-28）：ConversationServiceImpl 4.2 段 RED 硬短路 + 分年级预审核文案 + 安全响应模式，见 design/04 §18.2 落地记录 |
| RISK-202 | M2 语义风险分类（SAF_001）上线补隐性表达 | P0 安全 | design/52 二 | DEC-CBT（已决） | ✅ 已完成（2026-07-28）：SemanticRiskClassifier 非流式前置调用（800ms 门禁可配）+ 主线 1.6 段只升不降 + SafetyAgent 委托复用，见 design/04 §18.3 落地记录 |
| RISK-203 | RiskScoreCalculator + C-SSRS 儿童分级（落地 04 §十） | P1 | design/52 二 | RISK-202 | ✅ 已完成（2026-07-28） |
| RISK-204 | TrendAnalyzer 纵向趋势（汇入 BL-08 通道，合并 VCL-003/MEM-103） | P2 | design/52 二 | — | ✅ 已完成（2026-07-28）：SessionEndAnalyticsService+LongTermMemoryService 持久化 attention 信号到 risk_events（source_type=attention, risk_level=YELLOW），复用 BL-08 通道 |
| SAFE-201 | 保密边界儿童化告知话术（接 design/22，落到对话首触点） | P0 安全 | design/52 三 | 无 | ✅ 已完成（2026-07-28）：ConfidentialityNotice 分年级话术 + createSession 注入 + turn=0 审计落库，见 design/14 §12.3 落地记录 |
| SAFE-202 | Layer2 高敏场景前置化/触发关注 | P1 | design/52 三 | 无 | ✅ 已完成（2026-07-28） |
| SAFE-203 | 危机热线多租户可配置（去硬编码） | P1 | design/52 三 | 无 | ✅ 已完成（2026-07-28） |
| SAFE-204 | 姓名/地址脱敏扩展 | P2 | design/52 三 | 无 | ✅ 已完成（2026-07-28）：PiiDesensitizer 扩展百家姓词典+上下文句式姓名正则+地址三层正则，maskName/maskAddress 方法，26 用例全绿 |
| CBT-201 | CBT 阶段标记结构化输出+落库供评估（接 45） | P1 | design/52 一 | DEC-CBT（已决） | ✅ 已完成（2026-07-28，CbtStageRouter） |
| CBT-202 | 年龄分层 CBT 技能路由（低龄行为激活） | P1 | design/52 一 | CBT-201 | ✅ 已完成（2026-07-28，CbtStageRouter AgeStrategy） |
| EMP-201 | 共情“命名-确认-容纳”评估维度（接 45） | P1 | design/52 四 | PEVAL-001 | ✅ 已完成（2026-07-28，EmpathyStructureEvaluator 三段式+反模式检测） |
| ALLY-201 | 连续性开场（记忆回注生成续接话术） | P1 | design/52 五 | MEM-102 | ✅ 已完成（2026-07-28，AllianceEnhancer） |
| ALLY-202 | 收束“巩固-希望-桥接”结构化 | P1 | design/52 五 | PEVAL-001 | ✅ 已完成（2026-07-28，AllianceEnhancer） |
| ALLY-203 | 中断-回归照护信号（汇入 BL-08） | P2 | design/52 五 | — | ✅ 已完成（2026-07-28，AllianceEnhancer） |

> 量表决策（钱敏健 2026-07-28）：**免费量表 PHQ-A/GAD-7 先行**（与 SCALE-001 一致）；开发可先实现，**施测接线上线前须钱敏健再决策**（未成年人测评合规门禁，见 SCALE-001/002 上线门禁备注）。

> design/53 补充：其 P0~P2 优化方向均映射到上表或已登记 ID，未引入新 ID；核心贡献是**四态判定法**与态③“已建未接线”资产盘点（世界B编排/ConversationStateManager/evaluateSessionAsync/buildRagContext 均零调用），提示“接线性价比远高于重写”。design/51 BL-01~BL-08 断链均已在上方 ORCH/PEVAL/KB/MEM/VCL/TMATCH 各行映射。

### 远期散项（已在既有分区登记，此处汇总排序）

| 任务ID | 任务 | 来源分区 | 状态 |
|--------|------|----------|------|
| AI-007 | 语音情感分析 SER（emotion2vec+，风险辅助信号） | 十七·AI 对话质量 | ⏳ 待开始（远期） |
| COMP-007 | 年度合规审计报送（未保条例 §37，流程性） | 十七·安全合规 / 十八 | ⏳ 待启动（远期） |
| COMP-008 | WebAuthn 设备认证（可选，不采集生物数据） | 十七·安全合规 | ⏳ 待开始（远期） |
| TEST-006 | 前后端契约测试（OpenAPI + mock 校验） | 十七·工程质量 | ⏳ 待开始（远期） |
| DATA-005 | 研究数据脱敏导出（IRB 兼容） | 十七·数据智能 | ⏳ 待开始（远期） |
| UX-003 | 多语言支持（繁体/英文） | 十七·用户体验 | ⏳ 待开始（远期） |
| UX-004 | 无障碍增强（WCAG 2.1 AA） | 十七·用户体验 | ⏳ 待开始（远期） |

> 说明：
> 1. 本表为**排序视图**，不改变既有分区中各主任务的“📝 设计完成，待实施”标记；开发启动时以本表 P0→P1→P2→远期 顺序推进。
> 2. **依赖约束**：ORCH-* 依赖 design/29 年级接通（已完成 P0）；STATE-*（无状态化）是 DEP-012（滚动发布）的强前置——未完成无状态化不得开多副本滚动；SCALE-003 受版权门禁、PARENT-WX 受企业认证门禁、DBAD-004 属数据迁移红线。
> 3. 本次仅登记任务与排序，**未进行任何开发、未做 git 提交**。

---

## 二十四、设计深化总追踪表（2026-07-28，逐篇 triage）

> 背景：钱敏健要求以 `design/` 全部 55 篇为清单，逐篇结合代码现状 + 业界最佳实践 + 目标用户（小学生）+ 落地场景，全面深化设计、补衔接、清「虚假设计未落地」，**本次不写代码，只做设计提升与任务定级**。本表是 triage 单一视图；具体开发任务仍归 §二十三 backlog。
> 责任人约定：**决策/合规/监管红线 → 钱敏健（AI 只提供信息不决策）；法务条款 → 法务；设计深化/文档编辑 → Agent**。
> 现状评级：🟢 近期已深化（34-53 系列，作为本轮基准/仅维护对齐）｜🟡 待深化（基础文档，缺口/断链明确）｜🔵 维护对齐（随批次微调）。
> 深化优先级判据同 §二十三：**安全/合规 > 对话产品力 > 教师效率 > 学生体验 > 商业化 > 规模化架构**。

**深化批次划分（执行顺序）：**
- **P0 深化**（对话治疗与安全核心，DEC-CBT 双世界落地锚）：02 / 03 / 04 / 13 / 14（+44 关联对齐）
- **P1 深化**（对话产品力 / 画像记忆 / 语音交互 / 接口流程 / 合规）：05 / 10 / 15 / 16 / 18 / 20 / 22 / 23 / 27 / 28 / 29 / 54 / 55（+34 施测门禁）
- **P2 深化**（平台运营 / 架构部署 / 商业化 / 基础文档）：01 / 06 / 07 / 08 / 09 / 11 / 12 / 17 / 19 / 21 / 24 / 25 / 26 / 30 / 31 / 32 / 33
- **🟢 维护批次**（34-53 近期专题）：随对应上游批次微调，不单独深化

| 编号 | 文档 | 现状 | 深化重点（缺口 / 衔接 / 虚假未落地） | 优先级 | 责任人 | 状态 |
|------|------|------|--------------------------------------|--------|--------|------|
| 01 | 产品架构图 | 🟡 | 补双世界架构现状（世界A线上单prompt / 世界B未接线）；Agent 协同图对齐 DEC-CBT 决策；数据流补画像/记忆/语音回注汇聚点 | P2 | Agent | ✅ 已深化（§9，2026-07-28，愿景 Agent 层多为等价实现；三条回注环已补；测评倒挂已登记） |
| 02 | Prompt 体系设计 | 🟡 | 与 44/45 编排对齐（静态拼接→先算策略再拼）；情绪门控/合规裁决入组装链；补版本治理引用 | **P0** | Agent | ✅ 已深化（§19，2026-07-28） |
| 03 | CBT 对话流程树 | 🟡 | 标注 CbtStateMachine 属世界B未接线；容纳之窗情绪门控（CRISIS/激活态禁认知重构）；年龄分层 CBT 技能路由；阶段标记结构化落库 | **P0** | Agent | ✅ 已深化（§十一，2026-07-28） |
| 04 | 风险识别规则库 | 🟡 | RED 硬短路跳过 LLM（现状仅留痕不短路）；M2 语义分类补隐性表达；RiskScoreCalculator+C-SSRS 儿童分级；纵向趋势通道 | **P0** | Agent | ✅ 已深化（§十八，2026-07-28） |
| 05 | 老师后台设计 | 🔵 | 与 35 改版对齐（Today View / 预警工作流状态机 / 降噪），正文补状态机，避免与 35 重复叙述 | P1 | Agent | ✅ 已深化（§20，2026-07-28） |
| 06 | 数据库结构设计 | 🟡 | 汇总新增表 DDL：画像元数据(provenance/confidence/decay)、量表 jsonb、分层记忆、voice_emotion_trend、prompt_eval_result、计费；向量索引策略 | P2 | Agent | ✅ 已深化（§10，2026-07-28，待实施 DDL 单一登记处已建；HNSW 统一策略；DDL 执行前须钱敏健确认） |
| 07 | SaaS 多学校隔离 | 🔵 | ✅ 已深化（§11，2026-07-28，**架构级偏差：实际为行级隔离非 Schema 级**，路线 A 已定稿；fix-06 已落地行级 TenantLineInnerInterceptor（策略 B）+ ParentAuthService 去硬编码，fail-fast 待收紧；热线配置 SAFE-203 已补） | P2 | Agent | ✅ 已深化 |
| 08 | MVP 最小可行版本 | 🔵 | ✅ 已深化（§十，2026-07-28，M4/M5 边界已定待确认；人脸识别撤销；测评滞后为风险项） | P2 | 钱敏健+Agent | ✅ 已深化 |
| 09 | 商业模式与采购 | 🔵 | ✅ 已深化（§10，2026-07-28，**客户画像错位：中学→小学主打待确认**；基础版筛查能力未落地不得先行承诺） | P2 | 钱敏健 | ✅ 已深化 |
| 10 | 政策与合规风险 | 🟡 | 补未成年人测评合规门禁(量表施测)、生成式AI 备案、语音本地处理表述诚实性；合规硬约束→任务映射 | P1 | 钱敏健+法务 | ✅ 已深化（§10，2026-07-28，三项待钱敏健确认） |
| 11 | 竞品深度分析 | 🔵 | ✅ 已深化（§9，2026-07-28，Woebot 停运/Wysa LLM 化已更新；差异化重定位=小学真空带+家校闭环+安全工程化；§2 旧表禁用于对外物料） | P2 | Agent | ✅ 已深化 |
| 12 | 技术架构 | 🔵 | 补双世界编排收敛后目标架构；Spring AI Advisor 链现状 vs 目标 | P2 | Agent | ✅ 已深化（§14，2026-07-28，架构事实=Java单体+2 Python边车；Advisor 链定稿：Service 层显式管线为正式路线；网关以 Nginx 为准） |
| 13 | Agent 工作流 | 🟡 | **DEC-CBT 落地锚文档**：显式标注 7 Agent(世界B)已实现零调用；接线方案(SSE 流式兼容：分诊非流式前置+回复流式)、延迟/成本评估、与 44 ORCH 收敛 | **P0** | Agent | ✅ 已深化（§十三，2026-07-28） |
| 14 | 儿童安全对话规范 | 🟡 | Layer2 从「仅留痕」→召回改写；保密边界儿童化告知话术落首触点(SAFE-201)；热线配置化去硬编码 | **P0** | Agent | ✅ 已深化（§十二，2026-07-28） |
| 15 | 心理知识库建设 | 🔵 | 与 49 深化对齐（内容生产/审核工作流/RAG 接主线/RRF）；正文标注未实现项已由 49 承接 | P1 | Agent | ✅ 已深化（§12，2026-07-28） |
| 16 | API 接口设计 | 🟡 | 补新增端点：量表施测/工具箱/编排内部API/画像元数据/记忆；错误码补全；WebSocket 广播(40) | P1 | Agent | ✅ 已深化（§12，2026-07-28，含错误码冲突修复方案） |
| 17 | 前端架构设计 | 🔵 | 补代码分割(42)、离线缓存(36)、全感官交互(55)组件；状态管理对齐 | P2 | Agent | ✅ 已深化（§10，2026-07-28，PWA/代码分割/全感官组件均已落地；学生端 sessionStorage 策略定稿采纳；Zustand/React Query 不引入定阈值） |
| 18 | Prompt 模板库 | 🟡 | 补全 EMO-001 情绪模板；与 45 模板矩阵/红队护栏对齐；Advisor 链组装顺序更新(编排后) | P1 | Agent | ✅ 已深化（§16，2026-07-28） |
| 19 | 界面详细设计 | 🔵 | 补波波宠物四态(27)、工具箱(36)、量表施测 UX(34)、教师工作台(35)界面规格 | P2 | Agent | ✅ 已深化（§9，2026-07-28，教师端单页面板式定稿；个案/预约/量表 UX ⬜ 归口既有任务；预警等级标记统一以 04 为准；§8.4 性别配色降为默认建议） |
| 20 | 端到端流程设计 | 🟡 | 时序图更新为编排后(分诊→策略→回复流式)；补量表施测/工具箱/连续性开场流程；危机短路时序 | P1 | Agent | ✅ 已深化（§10，2026-07-28） |
| 21 | 认证与试用准入 | 🔵 | 4 决策已定(决策#13)正文回填；与 24 认证优化去重 | P2 | 钱敏健+Agent | ✅ 已深化（§十一，2026-07-28，P0/P1 全落地含 WebSocket/PIN/改密；permitAll 兜底收紧列 P2；与 24 职责边界定稿；Schema 表述随 07 改写） |
| 22 | 告知同意条款 | 🔵 | 补量表测评同意项、语音本地处理表述；待法务审定（合规红线，不替决策） | P1 | 钱敏健+法务 | ✅ 已深化（§六，2026-07-28，量表/语音条款缺口已标注待法务审定） |
| 23 | 学生心理画像设计 | 🟡 | 与 46 闭环对齐：字段加 provenance/confidence/decay；画像→StrategyProfile 决策接线；效果回收自校准 | P1 | Agent | ✅ 已深化（§9，2026-07-28） |
| 24 | 身份认证优化方案 | 🔵 | 与 21 去重；PIN/锁定/监护人同意落地状态标注 | P2 | 钱敏健+Agent | ✅ 已深化（§七，2026-07-28，三阶段大面积落地；家长登录改道手机号+密码定稿；parent_bindings DDL 冻结；密码复杂度/企微配置列 P2） |
| 25 | 产品功能说明 | 🔵 | v2.0 已补，随功能深化同步；面向学校/家长表述核对 | P2 | Agent | ✅ 已深化（§十，2026-07-28，修正 Schema 隔离/密码策略两处超前承诺；对外禁用物理隔离表述；测试账号商用前删除归 32） |
| 26 | 家长端 H5 与小程序 | 🔵 | 与 43 小程序化对齐、修正 Taro 表述；周报/同意管理已实现核对 | P2 | Agent | ✅ 已深化（§9，2026-07-28，家庭码双模式定稿采纳实态；parent_bindings 随 24 冻结；§4/§6 重写列 P3） |
| 27 | 波波品牌与宠物交互 | 🔵 | 与 37 情感化TTS/动效三方同源对齐；表情状态机引用；与 55 全感官去重 | P1 | Agent | ✅ 已深化（§十，2026-07-28，实现领先于文档：五态+实时转写已登记） |
| 28 | 语音唤醒与冷场引导 | 🔵 | 冷场决策(NudgeDecisionModel)已生效核对；与 47/48 语音闭环对齐；唤醒授权措辞（合规） | P1 | 钱敏健+Agent | ✅ 已深化（§十二，2026-07-28，三功能全部落地 🟩，xiaotaiyang 已修复；授权措辞+唤醒词入待确认清单） |
| 29 | 学生画像与年龄适配 | 🔵 | 核心竞争力（近期）：与 46 画像闭环、44 编排 personality 层衔接 | P1 | Agent | ✅ 已深化（§十，2026-07-28，实现领先于文档：五断裂点已全部修复 🟩） |
| 30 | 产品全景优化规划 | 🔵 | 路线图纳入 DEC-CBT 落地 + 本设计深化批次；Sprint 节奏对齐 | P2 | 钱敏健+Agent | ✅ 已深化（§十七，2026-07-28，Sprint A-E 已被超越不再按表执行；上线门禁=量表合规+RAG 空库；BIZ-001 挂起待 07） |
| 31 | 等保二级差距评估 | 🔵 | 合规路径随部署推进（非开发） | P2 | 钱敏健 | ✅ 代码侧差距全部修复（2026-07-29）：fix-03 加密接线 / fix-07 prod profile 激活 / fix-08 TLS+wss+origin 收敛 / fix-10 CI 门禁修真 / SecurityConfig anyRequest().authenticated() 收紧（commit 701a3a0，白名单仅 auth 入口/wecom/guardian-consent confirm/parent/health/ws，教师端点已核实全落 /teacher/**+/alerts/** 匹配器）；剩余为运维/文档项（云安全组审计、异地备份、WAF、管理制度 3 份），钱敏健牵头 |
| 32 | 商用发布前置待办 | 🔵 | 补量表施测合规门禁项；与本追踪表联动 | P1 | 钱敏健 | ⬜ 待深化 |
| 33 | 系统测试培训手册 | 🔵 | 编排/量表/工具箱上线后补测试点 | P2 | Agent | ⬜ 待深化 |
| 34 | 心理量表数字化 | 🟢 | 近期已深化；施测接线**上线门禁**(SCALE-001/002)待钱敏健决策 | P1 | 钱敏健+Agent | 🟢 维护 |
| 35 | 教师端工作台改版 | 🟢 | 近期已深化；随 05 对齐维护 | P1 | Agent | 🟢 维护 |
| 36 | 心理工具箱与离线缓存 | 🟢 | 近期已深化；随 17/19 对齐维护 | P1 | Agent | 🟢 维护 |
| 37 | 情感化TTS与动效 | 🟢 | 近期已深化；随 27/48/55 对齐维护 | P1 | Agent | 🟢 维护 |
| 38 | 计费配额与运营后台 | 🟢 | 近期已深化；随 09 对齐维护 | P2 | Agent | 🟢 维护 |
| 39 | 画像效果量化与A/B | 🟢 | 近期已深化；维护 | P2 | Agent | 🟢 维护 |
| 40 | 水平扩展与无状态化 | 🟢 | 近期已深化（架构远期）；随 07/42 维护 | P2 | Agent | 🟢 维护 |
| 41 | 信创数据库适配 | 🟢 | 近期已深化（远期）；维护 | P2 | 钱敏健+Agent | 🟢 维护 |
| 42 | 部署架构升级 | 🟢 | 近期已深化（远期）；维护 | P2 | Agent | 🟢 维护 |
| 43 | 家长端小程序化 | 🟢 | 近期已深化（远期）；随 26 维护 | P2 | Agent | 🟢 维护 |
| 44 | 个性化提示词动态编排引擎 | 🟢 | 编排核心；DEC-CBT 世界B收敛对齐（ORCH 策略层并入世界B链） | **P0 关联** | Agent | 🟢 维护 |
| 45 | 提示词工程体系深化 | 🟢 | 近期已深化；随 02/18 维护 | P1 | Agent | 🟢 维护（G-1 落地 P1：模板矩阵/红队门禁/三门禁审批留痕，2026-07-28） |
| 46 | 学生画像自动化迭代闭环 | 🟢 | 近期已深化；随 23/29 维护 | P1 | Agent | 🟢 维护 |
| 47 | 语音情感分析数据闭环 | 🟢 | 近期已深化；与 54 去重对齐 | P1 | Agent | 🟢 维护 |
| 48 | 多音色音调自适应匹配 | 🟢 | 近期已深化；随 37 维护 | P1 | Agent | 🟢 维护 |
| 49 | 心理知识库建设深化 | 🟢 | 近期已深化；与 15 对齐 | P1 | Agent | 🟢 维护（G-2 落地运营侧采编工作流：EditorialWorkflowService 四动作编排+缺口报表+editorial 端点，2026-07-28） |
| 50 | 长期记忆增强系统 | 🟢 | 近期已深化；维护 | P1 | Agent | 🟢 维护 |
| 51 | 横向断链分析 | 🟢 | 分析型，本轮横向基准；维护 | 基准 | Agent | 🟢 维护 |
| 52 | 核心功能板块心理深化 | 🟢 | 分析型，DEC-CBT 已决策；P0 深化直接落 02/03/04/13/14 | 基准 | Agent | 🟢 维护 |
| 53 | 全板块设计实现脱节 | 🟢 | 分析型，四态判定基准；维护 | 基准 | Agent | 🟢 维护 |
| 54 | 语音情感分析设计方案 | 🟡 | 新迁入(原docs/16)：与 47 去重合并——明确 54=基础分类方案、47=闭环增强；风险辅助信号定位 | P1 | Agent | ✅ 已深化（§10，2026-07-28，核心链路已落地 🟩，SenseVoice+emotion2vec 定稿选型已登记） |
| 55 | 学生端全感官交互设计方案 | 🟡 | 新迁入(原docs/17)：与 37/27 去重；视觉主题系统落地状态核对 | P1 | Agent | ✅ 已深化（§十，2026-07-28，主体已落地 🟩：三主题+TTS链路+情绪映射；音色路线偏差已定稿登记） |

> 说明：
> 1. 本表为 triage 单一视图，`⬜ 待深化` = 本轮需编辑正文，`🟢 维护` = 近期已深化仅随批次微调；深化产生的新开发任务并入 §二十三 对应 ID，不在此重复登记。
> 2. **虚假设计未落地重点**（承 51-53）：世界B Agent 编排 / ConversationStateManager / evaluateSessionAsync / buildRagContext / 语言模板路由均「已建零调用」——深化时须在对应文档（13/03/04/40/49/29）显式标注「已实现未接线」，避免误读为已生效。
> 3. 本次仅深化设计与定级，**未进行任何开发、未做 git 提交**。

## 二十五、配置统一纳管（design/57）

> 背景：配置分散于环境变量、application.yml、Python 硬编码、前端 TypeScript 四处，存在前后端阈值不同步、TTS 音色矩阵改参数需改代码发版、引导脚本运营不可调等痛点。本专题统一纳管，实现“改配置不改代码”。
> 设计文档：`design/57_配置统一纳管设计.md`（2026-08-01 创建，2026-07-28 v2 更新）
> v2 更新要点：对齐 TTS v4 方言重构（native/instruct 双模式）、ASR-SER 解耦、声纹 remote 模式、TTS 模型 v3-flash、Dockerfile 影响分析

| 任务ID | 任务描述 | 优先级 | 状态 | 备注 |
|--------|----------|--------|------|------|
| CFG-001 | **M1 后端 API**：application.yml 新增 `mindsafe.system-config` 节点（voiceprint/wakeWord/tts/guideScripts）+ 新增 SystemConfigController（GET /api/v1/system/config，permitAll，Cache-Control 5min） | P0 | ✅ 已完成 | design/57 M1①②；TDD 7 个测试全绿 |
| CFG-002 | **M1 前端注入**：新建 `config/remote.ts`（initRemoteConfig + getConfigValue）+ main.tsx 启动加载（3s AbortController 超时静默降级）+ Security 白名单 | P0 | ✅ 已完成 | design/57 M1③；12 个测试全绿 |
| CFG-003 | **M1 声纹阈值统一管控**：useVoiceprint.ts 改从 getConfigValue() 读取 local 阈值（0.70），保留 voiceprint.ts 为 fallback；引导脚本改从远程读取 | P0 | ✅ 已完成 | design/57 M1④；影响 VoiceLoginOverlay + voiceprintStore；578 个前端测试全绿 |
| CFG-004 | **M2 TTS 配置外置**：新建 `tts-service/config.yaml`（7 音色 + 8 方言 native/instruct + 10 情感 + native_dialect_voices）+ app.py 加载改造（保留硬编码 fallback） | P1 | ✅ 已完成 | design/57 M2①②；11 个配置测试全绿 |
| CFG-005 | **M2 部署链路**：Dockerfile 增加 `COPY config.yaml` + docker-compose 透传 DASHSCOPE_TTS_MODEL + .env.example 补变量 | P1 | ✅ 已完成 | design/57 M2③④ |
| CFG-006 | **M2 验证**：35 个 Python TTS 测试全绿（test_app 24 + test_config 11） | P1 | ✅ 已完成 | design/57 M2⑤ |
| CFG-007 | **M3 Voice 配置外置**：新建 `voice-service/config.yaml` + `config.py`（独立模块，解耦重量级依赖）+ app.py 加载改造 + Dockerfile COPY | P2 | ✅ 已完成 | design/57 M3；8 个配置测试全绿 |
| CFG-008 | **M4 文档同步**：.env.example 补 DASHSCOPE_TTS_MODEL + DEPLOY-GUIDE 配置变更流程 + design/57 状态更新 | P2 | ✅ 已完成 | design/57 M4 |

---

## 二十六、审计缺口登记（design/13 + design/20 篇审计，2026-07-28）

> 背景：13/20 两篇深度审计（设计完善度/代码达标度/测试覆盖三维度）修复完成后，遗留 3 项功能/测试缺口 + 2 项历史对标审计残留项，按优先级登记如下。优先级判据同 §二十三：**安全/合规 > 对话产品力 > 教师效率 > 学生体验 > 商业化 > 规模化架构**。

| 任务ID | 任务描述 | 优先级 | 来源 | 状态 | 备注 |
|--------|----------|--------|------|------|------|
| ESC-001 | **/escalate 转人工端点 + 学生端 safety_mode 交互**：学生主动求助（"我想找老师"）触发升级端点（写 risk_event + 通知教师 + 会话置 escalated）+ 前端安全模式界面（热线/找老师入口）。当前仅系统侧 RED 自动升级（RISK-201），学生**主动**求助通道缺失 | **P0 安全**（危机兜底通道） | design/20 §10.1 F-03 / §10.2 升级时序（文档标 ⬜ 属实）；**专题设计见 frozen/58** | 🔒 冻结（远期任务规划，2026-07-28 钱敏健定级；设计文档已产出并移入 frozen/，解冻实施前须确认） | 与 M2-006（红色风险教师接管，✅ 已完成）互补：M2-006 为系统自动升级，本项为主动求助入口 |
| TEST-007 | TeacherService 测试覆盖 33.2% → ≥80%（属教师管理域） | P2 | design/05 责任范围 | ⏳ 待实施 | 13/20 审计按范围纪律未扩入；design/05 篇审计时激活 |
| TEST-008 | 量表发放/答题/结果流程（Service+Controller）补齐——评分引擎（AssessmentScoringEngine/PHQ-A·GAD-7/RecurrenceCalculator）已完备 | —（不单独排期） | design/20 §10.1 F-06 | 🔒 挂起 | **与 SCALE-001/002 施测接线暂缓决策同一门禁**（未成年人测评合规，待钱敏健再决策），不重复立项 |
| DOC-051 | QuickStart 快速启动指南（新人 5 分钟跑通：docker compose up + 前端 dev） | P3 远期 | 全代码库对标审计 P2-6 | ⏳ 待实施 | 残留项核实：CONTRIBUTING/QUICKSTART 均不存在 |
| UX-006 | ChatRoom.tsx 拆分（827 行 → useSseStream / useChatSession hooks 抽离） | P2 | 全代码库对标审计 P2-3 | ⏳ 待实施 | 残留项核实：869→827 行小幅改善，SSE/TTS/情绪/满意度仍混杂单组件 |

> 说明：
> 1. 上一轮全代码库对标审计（"明天上线 1 所试点校"标准）其余 P1/P2 项已核实**均已修复**：ErrorBoundary 三端 ✅ / db-backup 定时容器 ✅ / api.ts 类型化（0 处 any）✅ / DEPLOY-GUIDE §十监控启动说明 ✅ / application.yml DEBUG→INFO ✅ / nginx client_max_body_size ✅ / parent-h5 vitest ✅ / ConversationServiceImpl 813→777 行（改善中，随迭代继续拆分）。
> 2. 本次仅登记任务与排序，**未进行任何开发、未做 git 提交**。

---

_本表由 Agent 维护，每次任务变更时更新。_
