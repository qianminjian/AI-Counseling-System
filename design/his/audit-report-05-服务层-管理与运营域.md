# 审计报告 05 - 服务层·管理与运营域

- 审计时间：2026-08-12（批次 3，与板块 06 并行）
- 审计范围：`backend/counseling-service/src/main/java/com/mindsafe/service` 下 19 包 48 文件（admin/teacher/parent/assessment/casemanage/achievement/analytics/audit/monitoring/notification/alert/wecom/sms/platform/quality/retention/ops/config/common），约 7650 行；对照 `src/test` 对应包测试（仅评估覆盖，不审测试质量）
- 审计方法：git log 只读热点分析 + 48 个 main 文件全量走读 + 验证性 grep（时区/分页/AUD 规则收敛/死代码/解密单点）；只读约束全程遵守，未修改任何文件

## 1. 板块概况

**结构**：板块含 4 类职责——

| 类别 | 包 | 文件数 | 质量基线 |
|---|---|---|---|
| 教师/个案/预警 | teacher + casemanage | 9 | S-007 已拆三子域（TeacherNoteStore/AlertLifecycleService/TeacherDashboardService），AlertSlaPolicy/AlertTodoMutePolicy 为纯函数 |
| 平台运营 | admin + platform + ops + analytics | 4 | PlatformAdminService 登录校验完整；PlatformLoginGuard 防爆破带 100k 上限 |
| 监控告警 | monitoring + alert + notification + wecom | 16 | OPS-MON-007/008 已实施；RiskNotifyOutboxService outbox 补偿状态机完整 |
| 支撑/清理 | audit + sms + quality + retention + config + common + assessment + achievement + parent | 19 | AssessmentScoringEngine/BuiltinScales 纯规则；CounselingTimeZone 收敛口完整 |

**依赖关系**：本板块是"管理侧汇聚面"——多数服务消费板块 04 的对话域产物（MessageSummaryService/QualityScore/RiskEvent）与 domain 层 Mapper，包间协作呈**汇聚型**（面向对话域收敛），非蜘蛛网。异常点集中在三处：TeacherService 仍直接依赖 14 个构造（拆后未收敛）、ParentService 绕过 BA-10 直查 Mapper、monitoring 包内 DegradationEventDetector↔DegradationMatrixService 双向调用。

**规模统计**：48 文件 / 约 7650 行；最大 TeacherService（866 行），最小 AlertService 接口（24 行）。纯函数组件（AlertSlaPolicy/AlertTodoMutePolicy/EmpathyStructureEvaluator/AssessmentScoringEngine/CounselingTimeZone）占比约 12%，质量普遍高。

## 2. 热点与风险初判

git log（2026-07-20 以来本目录）热点与对应风险：

- **监控/告警/SLA 域高频修补（约 20 条提交）**：`60e3941f` 告警落库主键缺失、`f73ef6e5` SLA 升级留痕主键缺失+冷却期 60 分钟、`b24ab064` MetricsQueryService 编码链路 500、`60818cb6`/`0b041070`/`953f3a24` 平台跨租户聚合 500 三次修复、`fe30c826` 通知分页 total 修复——该域持续"打补丁"，但**多实例一致性**（P0-4/P0-5）与**口径统一**（P1-1/P1-2）仍是结构性空白，补丁未触及根因。
- **S-007 拆分**（`627b6bba`/`da00cbf7`）：已执行，但 TeacherService 残留 866 行/14 构造依赖，拆分不彻底（P1-3）。
- **平台域三次 500 修复**：指向 PlatformService 跨租户聚合的高风险面（P1-7 N+1 同源）。

风险初判：**P0 集中在合规留痕链（审计日志两处失效）与已冻结决策 R-010 的回归**；**P1 集中在多实例语义与口径双源**。

## 3. 发现清单（分级表）

### P0 架构级

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试 |
|---|---|---|---|---|---|
| P0-1 | [DataRetentionCleanupJob.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/retention/DataRetentionCleanupJob.java):149-151、:155-156、:165-167 | **系统级审计留痕全链路失效**：主任务 DATA_RETENTION_CLEANUP（:149）、异常分支 DATA_RETENTION_CLEANUP_ERROR（:155）、撤回学生清理（:168，注释自认）均以 tenantId=null 调用 auditLogService.log，而 `audit_logs.tenant_id NOT NULL`（:165 注释已登记"与既有同病"）——**数据删除这一最敏感操作无审计可查**，且已知未修 | 基建修复：audit_logs.tenant_id 改可空（或系统级代理租户 + 显式标记）；修复后补"系统级审计落库"断言测试 | 合规留痕恢复，PIPL/等保面兜底；locality：单点基建修复覆盖全部 3 处调用 | 删除测试不适用（是修复不是删除） |
| P0-2 | [PlatformService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/platform/PlatformService.java):147；[DataAnalyticsService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/analytics/DataAnalyticsService.java):37 | **R-010 冻结决策回归**：doing/92 R-010 已议决"业务日界收敛至 CounselingTimeZone"（2026-08-11 实施三处收敛），但本板块两处新代码仍违反——PlatformService:147 用 `ZoneId.systemDefault()`（依赖部署服务器时区，最严重）；DataAnalyticsService:37 硬编码 `ZoneId.of("Asia/Shanghai")`（与 CounselingTimeZone 形成双源，且该校报/对比统计口径不与 CSV 导出对齐） | 两处改走 CounselingTimeZone（todayStart/dateKey）；DataAnalyticsService 移除 ZONE_CN 常量 | R-010 收敛闭环，服务器非东八区不再漂移；leverage：CounselingTimeZone 已有 6 方法完整收敛口，纯消费改造 | 删除测试通过（收敛口已存在，删除双源实现即消解复杂度） |
| P0-3 | [AuditLogService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/audit/AuditLogService.java):87-109 | **操作审计的 IP 哈希 + User-Agent 字段恒为空**：captureRequestContext 用 `RequestContextHolder.getRequestAttributes()`，而 TenantContextPropagationConfig（板块 03 已核）的 TaskDecorator 只传播租户上下文不传播请求上下文——@Async 审计线程中恒为 null，COMP-006"操作审计留痕"的溯源维度（IP/UA）部分失效 | ① 请求上下文在调用方同步线程内捕获后传入（auditEntry 增加 ipHash/ua 字段由调用侧填），或 ② TaskDecorator 增加 RequestContextHolder 传播；修复后断言审计记录含 IP 哈希 | COMP-006 溯源维度恢复；leverage：修复点在单处配置/单方法，覆盖全部操作审计 | 删除测试不适用 |
| P0-4 | [DegradationEventDetector.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/monitoring/DegradationEventDetector.java):62、:97-108 | **多实例下降级事件防抖失效**：degradedState 为实例内存状态机，Redis 分布式锁（SETNX+所有权校验 :106-108）只防并发扫描不防冷却窗口——实例 A 标记 degraded 后，实例 B 在 A 的防抖窗口内仍会重复写 auto 事件（TASK-TRACKER OPS-MON-007 登记"last_value 防抖"未说明多实例语义） | 防抖状态迁 Redis（last_value 键 TTL=防抖窗口）或 DB 幂等（degradation_events 唯一键 + ON CONFLICT）；事件写入走统一入口 | 消除告警风暴/重复事件污染数据面；locality：防抖逻辑集中在 detector 单点 | 删除测试不适用 |
| P0-5 | [SlaEscalationScanner.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/teacher/SlaEscalationScanner.java):63、:150-164 | **SLA 升级冷却表为实例内存，多实例双发升级通知**：lastAlertAt 内存 Map 在多实例下互不知晓；`synchronized(this)` 仅防单实例并发；isEnabled 每次扫描查 sys_config 可缓存 | lastAlertAt 迁移 Redis（TTL=冷却期）；isEnabled 加短 TTL 本地缓存；补多实例语义的测试 | 消除重复升级告警（该域已因主键缺失修过两次，见热点）；leverage：同 P0-4 可共用"分布式去重"基础设施 | 删除测试不适用 |

### P1 模块级

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试 |
|---|---|---|---|---|---|
| P1-1 | [NotificationServiceImpl.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/notification/NotificationServiceImpl.java):103、:152 | **未读通知双口径**：getNotifications(status=UNREAD) 用 `isNull(readAt)`（:103），countUnread 用 `ne(deliveryStatus,"read")`（:152）——同一用户两个接口未读数可能不一致（deliveryStatus 有第三种状态时漂移） | countUnread 改为与列表一致的 `isNull(readAt)` 单一口径（readAt 为唯一权威已读标记） | 前后端未读徽标与列表一致；locality：一行 SQL 收敛 | 删除测试通过（删掉双源中的一源） |
| P1-2 | [OpsInsightsService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/ops/OpsInsightsService.java):180 | **租户健康逾期判定硬编码 60 分钟，与 AlertSlaPolicy 分级 SLA 冲突**：AlertSlaPolicy（S0=5min/S1=15min/S2=60min）是冻结口径，而 tenantHealth 统一按 60 分钟判定"逾期"——S0 事件 10 分钟未处理被判健康，S2 事件 50 分钟未处理却被误判逾期 | tenantHealth 逾期判定消费 AlertSlaPolicy 的分级阈值（按事件 riskLevel 映射） | 运营看板口径与 SLA 策略一致；leverage：AlertSlaPolicy 已是纯函数可注入 | 删除测试通过（口径收敛至既有策略类） |
| P1-3 | [TeacherService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/teacher/TeacherService.java):42、:87、:101、:590、:326 | **S-007 拆分残留**：① notificationMapper 死字段（:42 声明/:87 构造参数/:101 赋值，grep 全文件无业务使用）；② getHighRiskStudents:590 班主任过滤为内存过滤，getAlerts:326 为 SQL 下推——同一语义两处实现口径不一致；③ 仍持 14 个构造依赖（拆后门面依旧臃肿） | 删死字段；getHighRiskStudents 班主任过滤下推 SQL（对齐 getAlerts）；评估二次拆分（预警生命周期/档案/统计仍混居） | 消除死依赖与口径分叉；locality：教师域两处读取对齐后理解成本下降 | 删除测试通过（notificationMapper 删除零消费者） |
| P1-4 | [CaseLifecycleService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/casemanage/CaseLifecycleService.java):checkFollowupDue/getDueFollowups/shouldReferToCase/shouldTerminateRetest/CaseSummary | **随访调度未接线死代码**：5 个方法/字段无 main 消费方（grep 仅 P2FinalBatchTest 引用）；transition 已由 TeacherService:480 接线——类一半活着一半是死代码，删除测试：删死方法后复杂度集中在活路径 | 二选一：接线到 SlaEscalationScanner/独立随访 Job（若产品需要随访提醒），或删除未接线方法 | 若删除：消解误导性"已实现"信号；若接线：随访能力真正生效 | 删除测试通过（5 方法零生产消费者） |
| P1-5 | [AdminService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/admin/AdminService.java):192、:164 | **importStudents 初始密码无法分发**：随机 6 位密码（:192）仅写入 user 表，ImportResult 无密码字段、接口无返回——学生拿到账号无法登录（对比 AdminTenantController 租户开通会返回初始密码）；CSV `split(",")` 无引号转义（:164）；classCode 无存在性校验 | ImportResult 增加初始密码列（或默认密码策略+强制首登改密）；CSV 解析换 RFC4180 库/引号感知 split；classCode 预校验 | 导入功能真正可用（当前是半成品）；locality：导入管线单点修复 | 删除测试不适用 |
| P1-6 | **AUD-043 分页安全化存量未清零（6 处）**：[OpsService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/monitoring/OpsService.java):98、:113；[DegradationMatrixService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/monitoring/DegradationMatrixService.java):141、:227；[OpsInsightsService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/ops/OpsInsightsService.java):83；[TeacherQualityService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/teacher/TeacherQualityService.java):63 | `.last("LIMIT N")` 存量 6 处仍存活（另 SysConfigService:94、WeComOAuthService:32 同源）。**已澄清**：领域层 [MybatisPlusConfig.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-domain/src/main/java/com/mindsafe/tenant/MybatisPlusConfig.java):16-18 登记 doing/92 R-022——存量 14 文件实测值均为常量无注入面，已列 LEGACY，新增 .last 由 CI 脚本拦截——**属排期收敛项而非缺陷**；但本板块 6 处加上板块 03/04 存量仍未清零，建议列入集中修复机械批次（对照 TeacherService:331/522/531、TeacherNoteStore:41 已收敛的范式） | 6 处改 MyBatis-Plus 分页插件；`LIMIT 1` 类改 selectOne+orderBy 首条 | AUD-043 存量清零，防注入+统一分页语义 | 删除测试通过（拼接实现被插件替换） |
| P1-7 | [PlatformService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/platform/PlatformService.java):94-124 | **平台租户聚合 N+1**：tenantStats 对每租户 4 次 selectCount（会话/学生/教师/风险事件），平台总览随租户数线性放大；叠加该域已有三次 500 修复史（跨租户聚合 runAsSystem） | 聚合下推 SQL（GROUP BY 一次取回，参照 TeacherService:729 已改 DB 分组的口径）或并行查询 | 平台页响应稳定；leverage：与 P1-6 同为平台域读路径治理 | 删除测试不适用 |

### P2 局部

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试 |
|---|---|---|---|---|---|
| P2-1 | [AliyunSmsService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/sms/AliyunSmsService.java):163、[PhoneVerificationService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/sms/PhoneVerificationService.java):130、[LoggingSmsService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/sms/LoggingSmsService.java):36 | maskPhone 三处重复实现 | 收敛至 common（CounselingTimeZone 同款工具位）或 SmsService 默认方法 | 脱敏口径单源 | 删除测试通过 |
| P2-2 | [AliyunSmsService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/sms/AliyunSmsService.java) | HttpClient 无 readTimeout 配置 | 构造时设 connect/read timeout | 短信通道卡死不拖垮线程 | 删除测试不适用 |
| P2-3 | [PhoneVerificationService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/sms/PhoneVerificationService.java):62-70 | 先写 Redis 再发短信：发送失败后冷却键已占位，用户 60 秒内无法重试（假死窗口） | 先发短信成功再写 Redis，或失败回滚冷却键 | 提升验证码触达可用性 | 删除测试不适用 |
| P2-4 | [TeacherService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/teacher/TeacherService.java):388、:397、:455、:516、:568；[TeacherQualityService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/teacher/TeacherQualityService.java):249 | TeacherNote 解密 5 处直接 `fieldEncryptionService.decrypt(note.getContent())` 无单点（S-006 只收敛了消息转写）；session_summary 解密同样直连（当前唯一消费方，低风险） | 参照 S-006 readDecryptedMessages 模式，为 TeacherNote 提供读单点 | 防解密语义漂移（对照 S-006 保密告知过滤教训） | 删除测试通过 |
| P2-5 | [DataAnalyticsService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/analytics/DataAnalyticsService.java):280 | 风险事件频率统计用 createdAt，TeacherService 口径用 detectedAt——风险时间口径双源（事件写入有 detectedAt 语义） | 统一 detectedAt | 运营/教师侧风险趋势一致 | 删除测试通过 |
| P2-6 | [AlertLifecycleService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/teacher/AlertLifecycleService.java):160 | 全限定名 `com.baomidou.mybatisplus...LambdaQueryWrapper` 与 import 混用 | 归一 import | 可读性 | 删除测试不适用 |
| P2-7 | [OpsInsightsService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/ops/OpsInsightsService.java):61-73、:197 | channelStats 近 30 天全量加载内存分组（TeacherService:729 已改 DB 分组，同类未修）；DurationBetween 命名违规（小写开头方法） | 改 DB GROUP BY；方法重命名 | 内存占用与命名一致性 | 删除测试通过 |

### 测试覆盖评估（对照 80% 门禁）

- **有测试**：achievement/BadgeServiceTest、alert/AlertServiceTest、analytics/DataAnalyticsServiceTest、assessment/AssessmentScoringEngineTest+RecurrenceCalculatorTest、audit/AuditLogServiceTest、common/CounselingTimeZoneTest、config/SysConfigServiceTest（7 包）
- **无测试（11 包）**：teacher 全包（含 SlaEscalationScanner/AlertLifecycleService/TeacherService 高风险管理组件）、monitoring 全包（DegradationEventDetector/AlertEventCollector 多实例语义恰是缺口）、notification 全包（**RiskNotifyOutboxService 状态机 pending→sent/failed→dead 零测试**）、ops、retention（数据清理——P0-1 审计失效即因无测试暴露）、platform、parent、sms、casemanage、wecom、quality（ConversationQualityService LLM-as-Judge 加权）
- **结论**：本板块"出事最多"的监控/告警/平台域恰为测试空白区；outbox 状态机与 SLA 扫描器是典型的"高价值难测接口"，建议列入集中修复时的测试补写优先清单

## 4. 改进候选排序

**Strong（高杠杆，建议进集中修复）**：
- F-1 系统级审计落库修复（P0-1）——一次基建修复覆盖 3 处失效调用，合规红线
- F-2 时区收敛收口（P0-2）——冻结决策回归，CounselingTimeZone 收敛口已就绪
- F-3 @Async 审计溯源修复（P0-3）——COMP-006 声称完成但溯源维度失效
- F-4 分布式去重统一（P0-4 + P0-5）——两个 P0 同根（实例内存状态 → Redis/DB 幂等），可共享基础设施

**Worth exploring（按收益排序）**：
- F-5 AUD-043 六处收敛（P1-6）——红线规则，工作量小
- F-6 未读通知口径统一（P1-1）+ 运营逾期口径统一（P1-2）——口径双源治理，一行级改动
- F-7 CaseLifecycleService 接线或删除（P1-4）——死代码治理，需产品确认随访是否上线
- F-8 AdminService.importStudents 密码分发（P1-5）——功能半成品补全
- F-9 TeacherService 二次拆分 + 死字段清理（P1-3）——S-007 收尾
- F-10 平台聚合 N+1（P1-7）——平台页性能

**Speculative（可选）**：
- F-11 短信脱敏/超时/冷却三项治理（P2-1/2/3）
- F-12 教师侧解密单点（P2-4）——参考 S-006 教训低成本预防

## 5. 设计一致性核对

| 设计文档 | 冻结决策 | 实现核对结论 |
|---|---|---|
| doing/92 R-010（his/92，已登记实施） | 业务日界统一 CounselingTimeZone | ❌ **回归**：PlatformService:147 systemDefault、DataAnalyticsService:37 硬编码（见 P0-2）；EmotionDiary/UsageTimeLimit/TeacherService/BadgeService/TeacherDashboardService 已收敛 ✅ |
| doing/93 S-007（his/93） | TeacherService 拆三子域（984→802 行） | ✅ 已实施，但残留死字段+14 构造依赖，拆分不彻底（P1-3）；S-006 消息读取单点收敛有效（TeacherService:645、TeacherQualityService:207 均走 readDecryptedMessages）✅ |
| doing/92 R-009②（his/92） | 撤回学生数据优先清理 | ✅ 已实施（DataRetentionCleanupJob:168），但其系统级审计无法落库（P0-1）❌ |
| AUD-043（doing/92 R-022 登记） | 分页安全化替代 `.last("LIMIT")` | ⚠️ 存量已登记 LEGACY（14 文件常量无注入面，CI 拦截新增），但本板块 6 处存量未清零（P1-6）——排期项非缺陷 |
| TASK-TRACKER COMP-006 | 操作审计留痕 | ⚠️ 部分失效：@Async 下 IP/UA 恒空（P0-3）+ 系统级审计无法落库（P0-1） |
| TASK-TRACKER OPS-MON-007/008 | 降级事件检测器/告警采集器 | ✅ 已实施，但防抖/冷却为实例内存态，多实例语义未定义（P0-4/P0-5）；AlertEventCollector 质量高（runAsSystem/防抖/超时齐全） |
| doing/87 M3 运行时档位（his/87，93728448） | 运行时档位联动 | ⚠️ DegradationMatrixService override 为记录型切换，tts/voice 运行时未接入覆盖键（注释自述）——实现中状态，非缺陷 |
| ADMIN-P1-01 sys_config 注册表 | HOT/RESTART 两级 + SECRET 掩码 + 留痕 | ✅ SysConfigService 完整一致（掩码不回读、RESTART 只读、reason 必填） |
| design/35 §4.2 降噪机制第 3 条 | 个案跟踪中学生 S2/S3 静音，S0/S1 不可静音 | ✅ AlertTodoMutePolicy 与 AlertSlaPolicy 一致，安全侧保守（null 不可静音） |
| frozen/88 企微告警 | 企微 webhook 推送 | ✅ WeComAlertService @Async 先入库再推送；LoggingAlertService @ConditionalOnMissingBean 兜底正确 |
| S-009 风险事件统一入口 | RiskEventWriter 唯一写入 | 板块 04 已核（本板块不重复）；本板块各消费方（告警/通知/质量）均只读 RiskEvent，无绕道 |

**已冻结决策需排除的项**：无（本板块无与冻结决策冲突的设计质疑；P0-2/P0-4/P0-5 属"冻结决策的实现一致性"问题而非质疑决策本身）。

## 6. 修复建议

**P0 全修（4 项，建议并入集中修复第一批）**：P0-1 系统级审计落库（先于任何数据清理功能上线）、P0-2 时区收敛、P0-3 @Async 审计溯源、P0-4/P0-5 分布式去重（可合并为"多实例语义治理"一个工作项，涉及 Redis 键迁移 + 幂等写入，需配套 outbox/事件表唯一键）。

**P1 按收益排序（建议第二批）**：P1-6 AUD-043 六处收敛（低风险机械改动）→ P1-1/P1-2 口径统一（低风险）→ P1-4 CaseLifecycleService 死代码（需产品裁决接线或删除）→ P1-5 importStudents 补全（功能可用性）→ P1-3 TeacherService 收尾 → P1-7 平台聚合 N+1。

**P2 可选**：P2-1~P2-7 随手治理，其中 P2-4（解密单点）建议优先——与 S-006 同型问题，低成本预防语义漂移。

**测试补写优先清单**（随修复项走，不单独排期）：RiskNotifyOutboxService 状态机、SlaEscalationScanner（含多实例语义测试）、DegradationEventDetector 防抖、DataRetentionCleanupJob 审计落库断言——均属 80% 覆盖率门禁下最值得补的关键路径。
