# AI 小学生心理辅导系统 - 任务跟踪表

> 创建：2026-07-23 | 更新：2026-07-27（核对 design/29 PROF-010~015+019 实现状态，同步为 ✅ 完成，与代码一致）
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
| PROF-016 | P2：V18 迁移 student_profiles 新增 personality_traits JSONB 列 | ⏳ 待开始 | design/29 §4.2 |
| PROF-017 | P2：LLM 提炼扩展（PROFILE_EXTRACTOR 新增性格维度：内向/敏感/好奇/兴趣） | ⏳ 待开始 | design/29 §3.6 |
| PROF-018 | P2：性格 → Prompt 策略映射 + dominant_interests 暖场取材 | ⏳ 待开始 | design/29 §3.8 |
| PROF-019 | P0-P1 集成测试（1 年级 vs 6 年级 System Prompt 差异 + 降级 + 风险不降级回归） | ✅ 完成 | `ConversationServiceImplTest.GradeComputation` 6 用例（含风险不降级回归），design/29 §七 |
| PROF-020 | P3：画像效果量化（A/B 适配 vs 不适配的满意度/会话深度对比） | ⏳ 远期 | design/29 §八 P3 |

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
| AUTH-022 | 家长微信小程序 + 微信 OAuth 登录 | ⏳ 远期 | 待 H5 稳定后转小程序，见 PARENT-WX 系列任务 |
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
| PARENT-WX-001 | 微信小程序工程注册 + AppID 配置 | ⏳ 远期 | 需企业主体认证 |
| PARENT-WX-002 | wx.login → openid → parent_bindings 绑定 | ⏳ 远期 | 后端表已设计 |
| PARENT-WX-003 | 微信 OAuth 授权页（获取手机号） | ⏳ 远期 | 微信开放平台配置 |
| PARENT-WX-004 | taro build --type weapp + 真机调试 | ⏳ 远期 | 依赖 PARENT-001~005 |
| PARENT-WX-005 | 小程序提审 + 上线 | ⏳ 远期 | 隐私协议/类目审核 |
| PARENT-WX-006 | 订阅消息推送（周报通知） | ⏳ 远期 | 微信订阅消息 API |

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
| WAKE-012 | 集成回归（按住说话主路径/红色风险流程不受影响）+ 真机测试（唤醒率/防自听回声/冷却关窗/iOS 兼容） | ⏳ 待开始 | 阶段 4 |

---

## 十七、产品全景优化规划（design/30）

> 来源：项目全面审计 + 业界对标（Woebot/Wysa/心潮），覆盖 10 大方向。
> 详细设计见 `design/30_产品全景优化规划.md`，此处仅列任务 ID 与状态。

### AI 对话质量与智能化（P0）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| AI-001 | 对话质量评估指标体系（共情度/CBT 完成度/安全/满意度） | ⏳ 待开始 | B |
| AI-002 | LLM-as-Judge 自动评估管线（异步抽样 + 低分标记） | ⏳ 待开始 | B |
| AI-003 | 教师端质量监控增强（AI 评分可视化 + 抽检回放） | ⏳ 待开始 | C |
| AI-004 | 多模型路由（DeepSeek 主 + 通义/GLM 备，故障自动切换） | ⏳ 待开始 | B |
| AI-005 | Prompt 版本管理与 A/B 测试框架 | ⏳ 待开始 | C |
| AI-006 | RAG 心理知识库（Spring AI VectorStore + pgvector） | ⏳ 待开始 | E |
| AI-007 | 语音情感分析 SER（emotion2vec+，风险辅助信号） | ⏳ 待开始 | 远期 |
| AI-008 | 长期记忆增强（跨会话摘要 + 关键事件 + 画像回注） | ⏳ 待开始 | C |
| AI-009 | 心理量表数字化（PHQ-A/GAD-7/SDQ 嵌入式） | ⏳ 待开始 | 远期 |

### 安全合规与信任体系（P0）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| COMP-001 | 等保二级测评准备（差距评估 + 整改） | ⏳ 待开始 | A |
| COMP-002 | 算法备案（生成式 AI，网信办） | ⏳ 待开始 | D |
| COMP-003 | 教育 App 备案（教育部） | ⏳ 待开始 | D |
| COMP-004 | 告知同意条款法务审定 | ⏳ 待开始 | D |
| COMP-005 | 敏感数据加密存储（AES-256 + 密钥轮换） | ⏳ 待开始 | D |
| COMP-006 | 操作审计日志（管理员/教师敏感操作留痕） | ⏳ 待开始 | D |
| COMP-007 | 年度合规审计报送（未保条例 §37） | ⏳ 待开始 | 远期 |
| COMP-008 | WebAuthn 设备认证（可选） | ⏳ 待开始 | 远期 |

### 工程质量与测试体系（P0）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| TEST-001 | 后端单测覆盖率 → 80%（JaCoCo 门禁） | ⏳ 待开始 | A |
| TEST-002 | 前端组件测试（Vitest + Testing Library） | ⏳ 待开始 | C |
| TEST-003 | E2E 扩展（12 → 30+ 用例） | ⏳ 待开始 | C |
| TEST-004 | 性能压测基线（k6，100 并发 SSE） | ⏳ 待开始 | E |
| TEST-005 | CI 增强（覆盖率门禁 + 依赖扫描 + 缓存） | ⏳ 待开始 | A |
| TEST-006 | 前后端契约测试（OpenAPI + mock 校验） | ⏳ 待开始 | 远期 |

### DevOps 与运维能力（P1）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| OPS-001 | CD 自动化（CI → 镜像 → Registry → 部署） | ⏳ 待开始 | E |
| OPS-002 | Docker 镜像版本化（Git SHA tag + ACR） | ⏳ 待开始 | E |
| OPS-003 | 结构化日志 + 链路追踪（JSON + traceId） | ⏳ 待开始 | B |
| OPS-004 | 告警体系（AlertManager → 企微 webhook） | ⏳ 待开始 | B |
| OPS-005 | 数据库自动备份（pg_dump + 异地 + 恢复演练） | ⏳ 待开始 | A |
| OPS-006 | 蓝绿/滚动部署 | ⏳ 待开始 | 远期 |
| OPS-007 | 多环境管理（dev/staging/prod） | ⏳ 待开始 | E |

### 数据智能与效果验证（P1）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| DATA-001 | 干预效果量化（前后量表对比 + 统计显著性） | ⏳ 待开始 | C |
| DATA-002 | 学生成长轨迹（学期情绪曲线 + 里程碑） | ⏳ 待开始 | C |
| DATA-003 | 校级报告自动生成（月度/学期 PDF） | ⏳ 待开始 | C |
| DATA-004 | 预警追踪闭环（预警→处置→回访→评估） | ⏳ 待开始 | C |
| DATA-005 | 研究数据脱敏导出（IRB 兼容） | ⏳ 待开始 | 远期 |

### 商业化与规模化（P2）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| BIZ-001 | 多租户生产化（独立 Schema + 自动迁移） | ⏳ 待开始 | D |
| BIZ-002 | 企微/钉钉 OAuth 配置上线 | ⏳ 待开始 | D |
| BIZ-003 | 阿里云 SMS 签名/模板申请 | ⏳ 待开始 | D |
| BIZ-004 | 计费与配额（按学校/学生数） | ⏳ 待开始 | 远期 |
| BIZ-005 | 信创适配评估（达梦/人大金仓） | ⏳ 待开始 | 远期 |
| BIZ-006 | 运营后台（平台级学校管理/收入/SLA） | ⏳ 待开始 | 远期 |

### 性能与可扩展性（P2）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| PERF-001 | LLM 响应优化（首 token < 1s + 超时降级） | ⏳ 待开始 | E |
| PERF-002 | 数据库优化（慢查询 + 索引 + 连接池） | ⏳ 待开始 | E |
| PERF-003 | CDN + 前端代码分割 | ⏳ 待开始 | 远期 |
| PERF-004 | Redis 缓存策略（画像/状态/配置） | ⏳ 待开始 | E |
| PERF-005 | 水平扩展（无状态 Session + LB + SSE 广播） | ⏳ 待开始 | 远期 |

### 用户体验与交互升级（P2）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| UX-001 | 学生端 onboarding 优化 | ⏳ 待开始 | E |
| UX-002 | 教师端工作台改版 | ⏳ 待开始 | E |
| UX-003 | 多语言支持（繁体/英文） | ⏳ 待开始 | 远期 |
| UX-004 | 无障碍增强（WCAG 2.1 AA） | ⏳ 待开始 | 远期 |
| UX-005 | 动效与微交互（Lottie + 粒子） | ⏳ 待开始 | 远期 |

---

_本表由 Agent 维护，每次任务变更时更新。_
