# AI 小学生心理辅导系统 - 任务跟踪表

> 创建：2026-07-23 | 更新：2026-07-23（商业化版本 Phase 1-20 全部完成，设计文档全面对齐）
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
| DEC-005 | 首个 LLM Provider | DeepSeek / 通义 / GLM | DeepSeek（性价比高） | ✅ 已确认（deepseek-v4-flash/pro） | 2026-07-23 |
| DEC-006 | 信创数据库选型 | 达梦 / 人大金仓 / 其他 | MVP 用 PG，M3+ 评估 | ⏳ 待确认 | M3 前 |

---

## 三、MVP 开发任务（M1，已完成）

> 注：M1 全部任务已在 Phase 1-10 中完成，含 Maven 多模块、多租户、AI 对话、风险识别、双前端、Docker 部署。

| 任务ID | 任务描述 | 模块 | 状态 |
|--------|----------|------|------|
| M1-001 | Maven 多模块骨架搭建（7 模块） | backend/ | ✅ 完成 |
| M1-002 | PostgreSQL 初始化 + Flyway 迁移 | backend/ | ✅ 完成 |
| M1-003 | Schema 级多租户路由实现 | counseling-tenant/ | ✅ 完成 |
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

## 十、学生心理画像（design/23）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| PROF-001 | P0：student_profiles 表 + 情绪基线 + 风险轨迹（SQL 聚合） | ✅ 完成 | V12 迁移 + StudentProfileService |
| PROF-002 | P0：对话时注入画像到 System Prompt | ✅ 完成 | AiChatServiceImpl 3.6 段 |
| PROF-003 | P1：沟通偏好 + 技巧有效性（LLM 提炼） | ✅ 完成 | ProfileExtractorService 异步提炼 + Prompt 注入增强 |
| PROF-004 | P2：成长轨迹 + 里程碑 + 教师端雷达图 | ✅ 完成 | ProfileRadarService + ECharts 雷达图 + 里程碑检测 |

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
| AUTH-022 | 家长微信小程序 + 微信 OAuth 登录 | ⏳ 待开始 | 独立小程序工程，后端 parent_bindings 表已设计 |
| AUTH-023 | 监护人同意闭环（短信确认链接） | ✅ 完成 | GuardianConsentService + AuthController 端点 |

### 阶段三：合规加固（后续待办）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| AUTH-030 | 使用时长限制（每日≤30min） | ✅ 完成 | 未保法，UsageTimeLimitService + Redis 每日累计 |
| AUTH-031 | 数据最小化审计 + 定期清理 | ✅ 完成 | DataRetentionCleanupJob + @EnableScheduling，普通30天/高风险365天 |
| AUTH-032 | 家长撤回同意 → 冻结+删除 | ✅ 完成 | PIPL §47，ConsentWithdrawalService + ParentController 端点 |
| AUTH-033 | 年度合规审计报送 | ⏳ 待开始 | 未保条例 §37，流程性报送（非代码） |
| AUTH-034 | WebAuthn 设备端指纹/Face ID（可选） | ⏳ 待开始 | 不采集生物数据，需真机测试 |

---

_本表由 Agent 维护，每次任务变更时更新。_
