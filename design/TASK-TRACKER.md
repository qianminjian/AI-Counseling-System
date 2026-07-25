# AI 小学生心理辅导系统 - 任务跟踪表

> 创建：2026-07-23 | 更新：2026-07-23（内容安全审查体系已实现 + 设计文档全面同步 + 新增认证试用准入设计 + 立文档代码一致底线规则）
> 
> 本表用于跟踪项目各阶段任务的进度和责任人。

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
| DOC-023 | Git commit 文档整合变更 | ⏳ 待开始 | Agent | - | - | 需用户授权 |
| DOC-024 | 目录结构纠偏：md 拍平至 design/、docx 迁至 doc/ | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 对齐钱敏健原意，STRUCTURE/BEACON/OVERVIEW 同步 |

---

## 二、待确认决策（需钱敏健拍板）

| 决策ID | 决策描述 | 选项 | 推荐 | 状态 | 截止日期 |
|--------|----------|------|------|------|----------|
| DEC-001 | MVP 范围最终确认 | 以 design/08 为准 / 调整 | 以 design/08 为准 | ✅ 已确认 | 2026-07-23 |
| DEC-002 | Java 构建工具 | Maven / Gradle | Maven（信创/银行通用） | ✅ 已确认 | 2026-07-23 |
| DEC-003 | Java ORM 框架 | MyBatis-Plus / Spring Data JPA | MyBatis-Plus（政企/信创主流） | ✅ 已确认 | 2026-07-23 |
| DEC-004 | 3 版建设方案主版本 | 时间戳后缀版 / 整合版 | 需人工比对 md5 | ⏳ 待确认 | 随时 |
| DEC-005 | 首个 LLM Provider | DeepSeek / 通义 / GLM | DeepSeek（性价比高） | ⏳ 待确认 | M1 联调前 |
| DEC-006 | 信创数据库选型 | 达梦 / 人大金仓 / 其他 | MVP 用 PG，M3+ 评估 | ⏳ 待确认 | M3 前 |

---

## 三、MVP 开发任务（M1，1-2 个月）

> 注：决策已确认（DEC-001~003），开发规范已制定（STRUCTURE.md §2.7-2.13），可启动。

| 任务ID | 任务描述 | 模块 | 优先级 | 状态 | 负责人 | 预估工时 |
|--------|----------|------|--------|------|--------|----------|
| M1-001 | Maven 多模块骨架搭建 | backend/ | P0 | ⏳ 待开始 | Agent | 1d |
| M1-002 | PostgreSQL + pgvector 初始化 | backend/ | P0 | ⏳ 待开始 | Agent | 0.5d |
| M1-003 | Schema 级多租户路由实现 | counseling-tenant/ | P0 | ⏳ 待开始 | Agent | 3d |
| M1-004 | 用户与权限模型（RBAC） | counseling-domain/ | P0 | ⏳ 待开始 | Agent | 2d |
| M1-005 | Spring AI LLM Provider 抽象 | counseling-ai/ | P0 | ⏳ 待开始 | Agent | 2d |
| M1-006 | Safety Agent 实现 | counseling-ai/ | P0 | ⏳ 待开始 | Agent | 3d |
| M1-007 | Emotion Agent 实现 | counseling-ai/ | P0 | ⏳ 待开始 | Agent | 2d |
| M1-008 | CBT Agent 实现 | counseling-ai/ | P0 | ⏳ 待开始 | Agent | 3d |
| M1-009 | 风险关键词识别规则库 | counseling-ai/ | P0 | ⏳ 待开始 | Agent | 2d |
| M1-010 | 对话 API（学生端） | counseling-api/ | P0 | ⏳ 待开始 | Agent | 3d |
| M1-011 | 预警通知服务（站内） | counseling-service/ | P0 | ⏳ 待开始 | Agent | 2d |
| M1-012 | 学生端 H5（React + Tailwind） | apps/student/ | P0 | ⏳ 待开始 | Agent | 5d |
| M1-013 | 教师端 Web（Ant Design） | apps/teacher/ | P0 | ⏳ 待开始 | Agent | 4d |
| M1-014 | Docker Compose 部署配置 | backend/ | P1 | ⏳ 待开始 | Agent | 1d |
| M1-015 | 单元测试（JUnit 5 + Mockito） | 各模块 src/test/ | P1 | ⏳ 待开始 | Agent | 3d |
| M1-016 | 集成测试（Testcontainers） | 各模块 src/test/ | P1 | ⏳ 待开始 | Agent | 2d |

**M1 总预估**：约 38 人天（单人 Agent 辅助开发，实际日历时间约 4-6 周）

---

## 四、M2 任务预规划（3-4 个月）

| 任务ID | 任务描述 | 模块 | 优先级 | 状态 |
|--------|----------|------|--------|------|
| M2-001 | 放松呼吸练习（学生端） | apps/student/ | P1 | ⏳ 待规划 |
| M2-002 | 高风险学生列表（教师端） | apps/teacher/ | P1 | ⏳ 待规划 |
| M2-003 | 学生档案查看（教师端） | apps/teacher/ | P1 | ⏳ 待规划 |
| M2-004 | 使用量/预警量统计 | counseling-service/ | P1 | ⏳ 待规划 |
| M2-005 | 满意度评价功能 | apps/student/ | P2 | ⏳ 待规划 |

---

## 五、M3 任务预规划（5-6 个月）

| 任务ID | 任务描述 | 模块 | 优先级 | 状态 |
|--------|----------|------|--------|------|
| M3-001 | 基础测评量表（PHQ-9、GAD-7） | counseling-ai/ | P1 | ⏳ 待规划 |
| M3-002 | 学生情绪趋势分析 | counseling-service/ | P1 | ⏳ 待规划 |
| M3-003 | 语音输入/输出（ASR/TTS） | counseling-ai/ | P1 | ⏳ 待规划 |
| M3-004 | 教师工作台优化 | apps/teacher/ | P2 | ⏳ 待规划 |
| M3-005 | 信创数据库评估（达梦/人大金仓） | backend/ | P1 | ⏳ 待规划 |

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
| **M1：核心对话+风险识别** | 最小闭环验证（100 真实用户） | 2026-09-23 | - | ⏳ 待开始 |
| **M2：放松训练+教师后台** | 功能体验完善 | 2026-11-23 | - | ⏳ 待开始 |
| **M3：测评+趋势分析** | 心理测量能力 | 2027-01-23 | - | ⏳ 待开始 |

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
| AUTH-001 | 实施试用准入 P0（告知同意门控+试用注册+ChatController 接 SecurityContext） | ⏳ 待开始 | 依赖 D1-D4 决策 |
| AUTH-002 | chat 端点收紧鉴权 + 前端接入 JWT | ⏳ 待开始 | 依赖 AUTH-001 |
| AUTH-003 | consent_records / trial_invite_codes 表迁移脚本 | ⏳ 待开始 | ⚠️ 命中红线 #3（schema 变更需用户确认） |

---

_本表由 Agent 维护，每次任务变更时更新。_
