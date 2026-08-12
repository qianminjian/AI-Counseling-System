# 审计报告 06 - 领域数据层

- 审计时间：2026-08-12（批次 3，与板块 05 并行）
- 审计范围：`backend/counseling-domain`（entity 37 + mapper 36 + handler 1 + typehandler 1 + util 2 + tenant 2 = 79 主文件 + tenant 配置 2，共约 3800 行）；对照 `src/test` 7 个测试
- 审计方法：git log 只读热点分析 + 基础设施文件（tenant/typehandler/handler/util）全量走读 + 关键实体（RiskEvent/User/AuditLog/CounselingSession/MessageSummary/Notification/DegradationEvent）逐字段核对 + Mapper 接口全量盘点 + 验证性 grep（type-handlers-package 注册范围/魔法值散落/JsonbTypeHandler 显式引用）；只读约束全程遵守，未修改任何文件

## 1. 板块概况

**结构**：counseling-domain 为独立 Maven 模块（供 counseling-service/counseling-api/counseling-ai 共享），6 个子包——

| 子包 | 文件数 | 职责 | 质量基线 |
|---|---|---|---|
| entity | 37 | 数据实体（@TableName 映射 tenant_template 租户模板 schema） | C2 状态/类型常量收敛（7+ 实体，ff2f1620）；工厂方法统一 |
| mapper | 36 | MyBatis-Plus 接口 | **33/36 为空壳**（纯 extends BaseMapper）；仅 3 个带自定义方法 |
| handler | 1 | UuidTypeHandler（@MappedTypes(UUID.class) 按类型注册） | ✅ 无全局污染风险 |
| typehandler | 1 | JsonbTypeHandler（String↔jsonb） | ✅ 显式 @TableField 引用 16 处，**绝不入扫描路径**（3740d266 事故已文档化） |
| util | 2 | DeviceCodeUtil（base32+Luhn）、MessageSummarySummarizer（语义提炼） | ✅ 纯函数，测试齐全 |
| tenant | 2 | MindSafeTenantLineHandler（租户行隔离 fail-fast）+ MybatisPlusConfig | ✅ 豁免名单 15 表 + M1-003 fail-fast + AD-003 只读访问器 |

**依赖关系**：领域层**零横向依赖**（entity/mapper 互不引用，唯一引用方向是 Mapper→Entity、实体→TenantSchema/JsonbTypeHandler），是典型的"薄数据层"。复杂查询语义全部上移至 Service 层 wrapper 拼装——这是理解跳跃的根源（见 P1-3）。

**规模统计**：约 3800 行；最大 RiskEvent（175 行），最小空壳 Mapper（9 行）。领域层为**纯数据契约**，无业务逻辑（仅工厂方法与状态机标记方法如 Notification.markSent/markRead）。

## 2. 热点与风险初评

git log（2026-07-20 以来）热点：

- `3740d266` **JsonbTypeHandler 全局注册污染所有 String 参数导致登录 500**——领域层最严重历史事故，已修复且配置层隔离（application.yml:69 type-handlers-package 只扫 handler 包），类注释明确警示。**当前状态安全**，但需警惕未来有人把 typehandler 包加进扫描路径。
- `ff2f1620` C2 实体状态/类型常量收敛（7 实体 + 一致性测试）——收敛**半程**：会话终态（taken_over/escalated）与 Notification.deliveryStatus 未覆盖（P1-1/P1-2）。
- `2b364cf6` AUD-041~043（租户前缀/分页安全化）与 `cef6430a` Q-002 迁移一致性测试——租户行隔离与迁移对齐治理成熟。
- `af3402b3` ARCH-007 两级摘要与 PII 昵称置换、`e041d2a0` BA-04 摘要策略上移——实体层摘要语义（MessageSummary）已收敛至服务层单点。

风险初评：领域层**基础设施面质量高、无明显 P0**；风险集中在**语义契约未收敛**（状态魔法值散落）与**贫血模型的结构性代价**（SQL 语义无处集中，导致板块 03/04/05 反复出现的口径双源问题在领域层无治理抓手）。

## 3. 发现清单（分级表）

### P0 架构级

**未发现独立的 P0 级问题**。领域层两大红线（租户行隔离 fail-fast、类型处理器注册隔离）实现均正确且有测试守卫。与板块 05 共享的 P0-1（audit_logs.tenant_id NOT NULL 致系统级审计无法落库）其 DDL 依据在本层：`V7__commercial_schema.sql:32`（`tenant_id UUID NOT NULL`），修复需改迁移——**建议将 P0-1 的修复归口领域层迁移**（audit_logs.tenant_id 改可空或引入系统级代理租户），板块 05 报告已详细描述。

### P1 模块级

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试 |
|---|---|---|---|---|---|
| P1-1 | [CounselingSession.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-domain/src/main/java/com/mindsafe/domain/entity/CounselingSession.java):15-20；服务层 4 处：[TeacherService.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/teacher/TeacherService.java):155、[ConversationServiceImpl.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/conversation/ConversationServiceImpl.java):281、[CounselingSessionStore.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/conversation/CounselingSessionStore.java):62、[SummaryCompensationJob.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-service/src/main/java/com/mindsafe/service/conversation/SummaryCompensationJob.java):58 | **会话终态魔法值散落 4 处**：`"taken_over"`（转人工接管）与 `"escalated"`（风险升级）为字符串字面量，CounselingSession 实体仅收敛 completed/active 两常量——**frozen/58 转人工升级（冻结决策）的终态语义依赖裸字面量**，新增消费方（摘要补偿/会话查询/统计）极易拼错或遗漏；C2 一致性测试（EntityStatusConstantsTest）未覆盖终态 | 实体补 `STATUS_TAKEN_OVER`/`STATUS_ESCALATED` 常量，4 处消费方收敛引用；EntityStatusConstantsTest 增补终态断言 | 转人工升级语义单源（安全红线相关状态不可裸写）；locality：常量收敛后跨包引用可检索 | 删除测试通过（字面量被常量替换，复杂度集中于实体） |
| P1-2 | [Notification.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-domain/src/main/java/com/mindsafe/domain/entity/Notification.java):54、:60、:65 | **deliveryStatus 状态值三散落 + readAt 双字段语义并存**：`"pending"/"sent"/"read"` 无实体常量（工厂 :54 与 markSent/markRead :60/:65 硬编码），且已读语义由 `deliveryStatus="read"` 与 `readAt` 双字段共同表达——**板块 05 P1-1（未读通知双口径）的根因在实体层语义设计**：两查询口径（isNull(readAt) vs ne(deliveryStatus,"read")）在实体层无单一权威定义 | 收敛状态常量至实体（DELIVERY_PENDING/SENT/READ）；明确 readAt 为唯一已读权威、deliveryStatus 仅表达投递（sent），markRead 不再改 deliveryStatus（或反向，二选一登记决策） | 未读口径单源，板块 05 P1-1 根治；locality：语义定义集中在实体方法 | 删除测试通过（删双源之一，countUnread/列表口径一致） |
| P1-3 | mapper 包 36 接口（33 空壳） | **贫血模型的结构性代价**：复杂查询语义全部在 Service 层用 LambdaQueryWrapper 拼装，领域层无 SQL 治理面——板块 05 反复出现的"同一语义两处实现口径不一致"（内存过滤 vs SQL 下推、createdAt vs detectedAt）在此层无收敛抓手；删除测试：删空壳 Mapper 仅让 Service 直连 BaseMapper，复杂度不消解 | 不删 Mapper（基础设施接缝），而是为**高风险聚合查询**（跨表统计/分组聚合）引入 Mapper 层 @Select 查询方法（参照 EmotionDiaryMapper.upsertCheckin 范式），逐步把口径类 SQL 从 Service 下移 | 查询语义单点化、可测试化；leverage：每收敛一个聚合查询即消灭一类口径双源 | 删除测试失败（空壳 Mapper 是必要接缝），改为"复杂度迁移"测试 |
| P1-4 | [DegradationEvent.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-domain/src/main/java/com/mindsafe/domain/entity/DegradationEvent.java)（无幂等字段）；V34 迁移（degradation_events 建表） | **降级事件无幂等键**：板块 05 P0-4（多实例重复写 auto 事件）的 DB 幂等方案需要唯一键支撑（如 point+fromState+occurredAt 窗口唯一约束），当前实体与表结构均无 | 与板块 05 P0-4 联动：V 迁移加唯一约束（或实体增加 dedup_key 字段），写入走 ON CONFLICT | 重复事件在 DB 层兜底去重；leverage：一次迁移配合 detector 改造闭环 | 删除测试不适用 |

### P2 局部

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试 |
|---|---|---|---|---|---|
| P2-1 | [RiskEvent.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-domain/src/main/java/com/mindsafe/domain/entity/RiskEvent.java):86、[CounselingSession.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-domain/src/main/java/com/mindsafe/domain/entity/CounselingSession.java):71、:75 | 工厂方法内魔法值：RiskEvent.fromDetection:86 `status="open"`（常量 STATUS_OPEN 已存在却未用）；CounselingSession.create:71 `interactionMode="text"`、:75 `transcriptPolicy="summary_only"`（无常量可循） | 工厂方法改引常量；interactionMode/transcriptPolicy 补常量 | 与 C2 收敛目标一致，防工厂与业务写入漂移 | 删除测试通过 |
| P2-2 | [EmotionDiaryMapper.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-domain/src/main/java/com/mindsafe/domain/mapper/EmotionDiaryMapper.java):19 | **唯一一处 @InterceptorIgnore(tenantLine=true) 绕过租户拦截**：R-011 原子 upsert 需要（SQL 显式携带 tenant_id，注释说明充分）；但未来新增调用方若复用该模式可能漏带 tenant_id，纵深防线缺口 | 保留实现；补一条测试断言 upsertCheckin 的 tenant_id 参数必须与上下文一致（或改为 Mapper 方法内强校验） | 绕过点行为被锁定，防回归 | 删除测试不适用 |
| P2-3 | [MessageSummary.java](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/backend/counseling-domain/src/main/java/com/mindsafe/domain/entity/MessageSummary.java):39-41 | topicTags 字段"当前恒 []，预留列"——无生产消费者（刻意保留，BA-04 已登记 DB 列不删决策） | 保持现状或随下一次摘要 schema 变更评估删除；不建议本轮动 | 仅提示，避免误判为死代码 | 删除测试通过（零消费者） |

### 测试覆盖评估（对照 80% 门禁）

- **7 个测试覆盖良好**：MindSafeTenantLineHandlerTest（fail-fast）、MigrationTenantConsistencyTest（doing/91 Q-002 迁移一致性）、EntityStatusConstantsTest（C2 常量）、TypeHandlerCoverageTest（类型处理器）、DomainEntityFactoryTest（工厂方法）、DeviceCodeUtilTest、MessageSummarySummarizerTest——**基础设施与纯函数全测**。
- **缺口**：① Mapper 层仅 EmotionDiaryMapper 有行为、无直接测试（依赖 service 层间接覆盖）；② 无"typehandler 不注册到扫描路径"的守卫测试（TypeHandlerCoverageTest 若已含则补：确认其断言范围）；③ EntityStatusConstantsTest 未覆盖会话终态（P1-1 修复时同步补）。
- **结论**：领域层为全项目测试质量最高的层之一，覆盖缺口与 P1 修复项绑定即可。

## 4. 改进候选排序

**Strong（高杠杆）**：
- F-1 会话终态常量收敛（P1-1）——frozen/58 转人工升级语义单源，4 处字面量 + 测试补断言，改动小收益高
- F-2 Notification 状态语义收敛（P1-2）——根治板块 05 P1-1 未读双口径，实体层一行语义决策

**Worth exploring**：
- F-3 高风险聚合查询下移 Mapper（P1-3）——治理口径双源的结构性起点，需按查询逐个评估
- F-4 DegradationEvent 幂等键（P1-4）——随板块 05 P0-4 分布式去重联动实施

**Speculative（可选）**：
- F-5 工厂方法魔法值清理（P2-1）
- F-6 EmotionDiaryMapper 绕过守卫测试（P2-2）

## 5. 设计一致性核对

| 设计文档 | 冻结决策 | 实现核对结论 |
|---|---|---|
| C2（ff2f1620，his/94 登记） | 实体状态/类型常量收敛 7+ 实体 + 一致性测试 | ⚠️ 部分收敛：User/RiskEvent/CounselingSession/DegradationEvent ✅；会话终态 taken_over/escalated（P1-1）与 Notification.deliveryStatus（P1-2）未覆盖 ❌ |
| 3740d266 事故（登录 500） | JsonbTypeHandler 禁止全局注册 | ✅ 修复正确且配置层隔离（application.yml:69 只扫 handler 包；typehandler 包仅显式 @TableField 引用 16 处；类注释警示） |
| doing/92 R-011 | 打卡原子 upsert（ON CONFLICT） | ✅ EmotionDiaryMapper.upsertCheckin + @InterceptorIgnore 说明充分 |
| doing/92 R-015 | Layer2 输出安全审查 JSON 留痕 | ✅ RiskEvent.reviewJson（jsonb） |
| doing/92 R-021 | 摘要截断按 code point | ✅ MessageSummarySummarizer:81-85 offsetByCodePoints |
| doing/92 R-022 | .last 存量登记 LEGACY | ✅ MybatisPlusConfig:16-18 登记（板块 05 P1-6 已澄清） |
| doing/91 Q-002 | 迁移与 IGNORE_TABLES 一致性 | ✅ MigrationTenantConsistencyTest + MindSafeTenantLineHandler.ignoredTables() 只读访问器 |
| design/08 §5.1 + design/09 §3.3 | message_summaries 两级摘要（提炼/保真/加密） | ✅ MessageSummary 实体注释完整对齐（D-7 路径 C）；BA-04 策略上移服务层单点一致 |
| M1-003 fail-fast | 无租户上下文拒绝执行 | ✅ MindSafeTenantLineHandler:88-92（系统作用域豁免 + 显式声明） |
| frozen/58 转人工升级 | 会话终态语义 | ⚠️ 实现存在（taken_over/escalated）但为裸字面量（P1-1），语义未在实体层冻结 |

**已冻结决策需排除的项**：无（领域层不涉及冻结决策的设计质疑，仅实现一致性核对）。

## 6. 修复建议

**P0 联动**：本层无独立 P0；板块 05 P0-1（系统级审计落库）的 DDL 修复归口本层迁移（V7:32 NOT NULL 改可空或代理租户），建议与板块 05 P0 批次合并实施，领域层出迁移 + 板块 05 出服务侧适配。

**P1 建议进集中修复第二批**：P1-1 会话终态常量（含 frozen/58 语义冻结 + 测试断言）→ P1-2 Notification 状态语义收敛（联动板块 05 P1-1）→ P1-4 降级事件幂等键（联动板块 05 P0-4）→ P1-3 聚合查询下移（长线治理，逐查询评估）。

**P2 可选**：P2-1/P2-2 随手治理；P2-3 维持现状。

**测试**：P1 修复均带测试补写（终态常量断言、未读口径断言、幂等写入断言）；领域层测试体系本身无需扩容。
