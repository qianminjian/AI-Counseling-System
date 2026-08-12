# 审计报告 03 - 服务层·用户与访问域

> 板块：backend/counseling-service/src/main/java/com/mindsafe/service 下 auth、user、profile、security、tenant、consent、toc、device、voiceprint、usage、billing、session 12 个包
> 审计时间：2026-08-12
> 审计方法：git log 只读热点分析 + 40 个主文件全文走查 + 28 个测试文件覆盖核对 + 设计文档（06/09/10/11/12/BEACON/TASK-TRACKER）一致性比对 + 依赖/事务/死代码静态分析（SearchCodebase/Grep/Read/Glob，全程只读）
> 依据规则：.qoder/rules/code-engineering.md（400 行红线、T4 分层纪律、禁空 catch）、code-finance-precision.md、design/BEACON.md（冻结决策只核对实现一致性）

## 1. 板块概况

### 1.1 结构与规模

| 包 | 文件数 | 行数 | 核心职责 |
|---|---|---|---|
| auth | 8 | 807 | 登录候选/登录留痕、试用与家长认证、锁定与限流、租户门禁、令牌黑名单、密码策略 |
| voiceprint | 4 | 377 | 声纹域纯函数、录入/比对/登录门禁（local/remote 双模式） |
| consent | 2 | 292 | PIPL §31 监护人同意闭环、§47 撤回（冻结+删画像） |
| billing | 2 | 208 | 套餐解析（60s TTL 缓存 + fail-open 降级）、EntitlementFilter 消费的规则矩阵 |
| usage | 2 | 175 | 会话计量（JdbcTemplate 幂等写 usage_events）、时间限额（Redis fail-open） |
| toc | 4 | 479 | toC 家庭账号：注册/验证码/设备解绑/多孩档案/数据删除 |
| device | 5 | 1163 | 设备档案/心跳/绑定码/解绑/OTA/审计、DVC_ 令牌、声纹采集任务（Lua CAS）、偏好授权 |
| profile | 7 | 1664 | 画像聚合/LLM 提炼合并/雷达 6 维/合并门控/元数据戳/效果回收/记忆回注 |
| security | 1 | 186 | AES-256-GCM 字段加密（密钥版本化，默认关闭明文透传防呆） |
| tenant | 1 | 184 | 租户一键开通（@Transactional） |
| session | 2 | 86 | 会话归属校验单点（T4 批次 A，租户条件强制内置） |
| user | 1 | 62 | 管理端密码重置（同租户校验+审计） |
| **合计** | **40** | **~5683** | |

测试：27 个测试文件（auth 4 / billing 1 / consent 2 / device 5 / profile 5 / security 1 / session 1 / tenant 1 / toc 4 / usage 1 / voiceprint 3）。

### 1.2 依赖关系（理解跳跃与 locality）

跨包调用仅 4 处，且全部为"门禁/加密下沉"而非业务编排，无环形依赖：

```
VoiceprintLoginService → auth.TenantAccessGuard     （登录门禁下沉，同层引用方向正确）
TocAuthService        → auth.LoginRateLimiter、security.FieldEncryptionService
TocDeviceService      → device.DeviceService         （toC 复用设备域，单一方向）
UsageCollector        → common.CounselingTimeZone    （业务日界收敛）
```

"注册/登录 → 鉴权 → 声纹校验 → 监护人同意 → 设备绑定"旅程横跨 auth/tenant/voiceprint/consent/device 5 包约 12 个类，但各模块职责单一、边界清晰，理解跳跃成本集中在"登录链服务粒度偏细"（LoginOrchestrator 单点 + 4 个 auth 服务 + 3 个 voiceprint 服务），建议在 LoginOrchestrator javadoc 补旅程地图（低优先级，见 P2）。

### 1.3 事务与分层基线

- @Transactional 共 9 处；Controller 无 Mapper 直连（T4 纪律通过，全 controller 目录仅注入 ObjectMapper 等工具类）；
- tenant 隔离贯穿良好：SessionAccessService 租户条件强制内置、VoiceprintVerifyService 租户维度查询+防御性二次过滤、TrialAuthService/ParentAuthService 系统作用域显式声明；
- 死代码：未发现（EntitlementChecker 被 EntitlementFilter 消费、ProfileEffectivenessTracker 被会话结束分析消费、TenantPlanResolver 被 EntitlementChecker 消费，均已接线）。

## 2. 热点与风险初判（git log）

近期相关变更（2026-07~08）：

| commit | 内容 | 板块关联 |
|---|---|---|
| `013756db` | doing/93 S-001 登录链收敛单点（LoginOrchestrator） | auth 域架构深化 |
| `21712026` | doing/92 R-009② 撤回学生数据优先清理 | consent 域热点（与 ConsentWithdrawalService 联动） |
| `0e9068eb` | doing/94 架构深化第 5-6 轮合并 | 大盘背景 |
| `ca243cf4` | doing/92 收尾批 R-002/013/016 | consent/usage 域 |

**风险初判**：① 登录链正处于持续重构期（S-001 刚落点），接口稳定性与测试保护是后续改动安全的先决条件；② consent 域 R-007/R-008/R-009 系列高频变更，事务边界与 DataRetentionCleanupJob 联动注释存在过时风险；③ device 域两轮架构深化（AD-005/006）后 DeviceService 职责仍在膨胀。

## 3. 发现清单（分级）

### P0 架构级（安全/合规红线、事务一致性）

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试判断 |
|---|---|---|---|---|---|
| P0-1 | consent/GuardianConsentService.java L96-123 | **confirmConsent 无 @Transactional**：三处写操作——insert 同意记录（L105）、reactivate 账号（L114）、审计落库（L119）——中途失败将产生"有同意记录但账号仍 withdrawn"或"账号恢复但同意证据缺失"的中间态。PIPL §31 同意记录是合规证据核心，部分写入破坏证据完整性。对照 ConsentWithdrawalService.withdrawConsent（L64-110）已有 @Transactional，同一域两接口事务不对称 | confirmConsent 补 @Transactional；requestConsent（纯发码无写库）保持无事务 | 合规证据一致性；consent 域事务边界统一（locality） | **不删除**（PIPL 合规核心闭环） |
| P0-2 | toc/TocPrivacyService.java L59-97（空 catch L70-72） | **deleteAllData 三重问题**：① 三步不可逆删除（解绑设备→删档案→账号 DISABLED）无 @Transactional，中途失败留部分删除态；② L70-72 `catch (IllegalArgumentException ignored) {}` 空 catch 无日志（违反 code-engineering §4.4），解绑失败被静默吞掉；③ 不可逆删除无审计落库（consent 域同类删除有审计，此处缺失） | 加 @Transactional；空 catch 至少 log.warn（含 deviceCode）；删除完成写审计；失败清单返回调用方 | 不可逆操作数据一致性 + 可观测性 | 不删除（toC-AC-7 合规路径） |
| P0-3 | device/PlatformDeviceService.java L149 | **qrPayload 硬编码占位符域名**：`"https://{domain}/p/1/" + code`。若未替换即上线，设备绑定二维码指向无效域名；违反 design/06 分层配置原则（密钥/域名应进 .env/yml） | 绑定页域名从配置注入（如 `mindsafe.platform.bind-url`），未配置时启动 fail-fast 或明确告警 | 部署正确性；配置单一事实源 | 抽出 QrPayloadFactory 后可单元测试 |

### P1 模块级（浅模块、重复、职责混乱、测试缺口）

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试判断 |
|---|---|---|---|---|---|
| P1-1 | device/DeviceService.java（472 行）；unbind L281-306 / factoryReset L359-387；auditOperation L462-471 | 超 400 行红线且职责混杂（档案/心跳/绑定码/解绑/配置拉取/OTA/审计 7 类）；**unbind 与 factoryReset 重复"遍历 actives 置 UNBOUND"循环**，未来改解绑语义（如加解绑原因）需同步两处；审计落库与 PlatformDeviceService.batchOperation 各自实现 | 抽 `unbindInternal(device)` 私有方法双处复用；审计下沉共享 AuditRecorder（与 PlatformDeviceService 统一）；行数超限部分拆 DeviceBindingService/DeviceConfigService | locality 提升；解绑语义改一处生效 | DeviceService 保留但瘦身 |
| P1-2 | toc/TocDeviceService.java L49-67、TocFamilyService.java L38-54/L99-107、TocPrivacyService.java L102-106 | **Toc 包用 IllegalArgumentException 而非项目统一 BizException+ErrorCode**（对照 GuardianConsentService/TrialAuthService 均为 BizException），前端拿不到统一错误码，全局异常处理需额外翻译 | 统一改 BizException（ErrorCode.RESOURCE_NOT_FOUND / PARAM_INVALID） | 全链路错误语义一致；P0-2 空 catch 的 catch 类型随之明确 | 不改接口 |
| P1-3 | toc/TocAuthService.java L163-171 | **实现 LoginRateLimiter 但 recordFailure/clearFailures 为空实现**（注释声称"频率模型不需要失败计数"）。接口语义漂移：调用方若依赖失败计数的副作用将静默失效；三个并排实现（LoginLockoutService/PlatformLoginGuard/TocAuthService）契约不一致 | A. 接口拆分 RateLimit / FailureTracking，TocAuthService 只实现前者；B. 保留但接口 javadoc 明确"频率窗口模型不需失败计数"的空实现是有意契约 | 接口契约清晰，防未来误用 | 不删除接口（锁/限流是安全基线） |
| P1-4 | device/DeviceVoiceprintService.java L155-159、L165-166 | **@TODO P0-5 残留 + UPLOADED 自动 complete 占位逻辑**：reportPhase(UPLOADED) 自动调 complete()（L156-158），注释明确"真实 enroll 链路就绪后应移除"；若 enroll 回调已接线而未移除，状态被提前终态化绕过真实链路 | 核对 voice-service 对接状态：已接线则删自动分支，未接线则 P0-5 推进或明确登记；清理 @TODO 注释 | 状态机语义与真实链路一致 | 可删自动 complete 分支（模块保留） |
| P1-5 | usage/UsageCollector.java L45-53、L64-72 | **JdbcTemplate 直写硬编码 `tenant_template.usage_events`**：① schema 名硬编码字符串（配置漂移）；② 绕过 MyBatis TenantLineInnerInterceptor——行级隔离下可行，但**未来 Schema 级隔离迁移时此处是漏网点**且无标识；③ @Scheduled 无租户上下文，正确性依赖 SQL 显式 tenant_id | schema 配置注入；注释明确"行级隔离设计下可接受 + Schema 迁移改造点"；确认 SQL 带 tenant_id | 迁移漏网面收敛；配置单一事实源 | 不删除（M4 usage 计量先行是 DEC-007） |
| P1-6 | 测试缺口（对照 src/test 目录） | **8 个关键服务无直接测试**：auth/AuthUserService、auth/TenantAccessGuard、auth/TokenBlacklistService（jti 黑名单安全核心）、billing/EntitlementChecker（豁免清单+PLAN_FEATURES 决策表，纯函数最易测且零回归保护）、voiceprint/VoiceprintLoginService（登录门禁链）、usage/UsageTimeLimitService（Redis fail-open 语义）、user/AdminUserService（重置密码审计）、profile/ProfileMergeGate 与 ProfileMetaStamper（ProfileMetaStamper 为 package-private final class，外部包不可测，需在 profile 包内补） | 优先补 EntitlementChecker（决策表参数化测试）、TokenBlacklistService、VoiceprintLoginService；ProfileMetaStamper 测试放 profile 包内 | 80% 覆盖率门禁缺口收敛；登录链重构期（S-001 热点）有回归保护 | 纯函数类删除测试成本最低、收益最高 |
| P1-7 | profile/StudentProfileService.java（530 行）；updateProfile L67-128（catch 吞异常 L125-127） | 超 400 行红线；updateProfile 大范围 catch Exception 降级——**写库失败仅 log.warn，调用方拿成功响应但画像未更新**，前端感知与真实状态背离 | catch 收窄至可降级场景（如单维度 JSONB 序列化），写库失败抛 BizException；buildEmotionBaseline/buildRiskTrajectory（L391-453）拆独立类 | 错误语义诚实；类瘦身 | 不删除（画像聚合核心） |
| P1-8 | profile/ProfileMergeGate.java L62-67 | **规则 4 strategy 返回 "REPLACE" 但实现是 EMA 加权**（alpha=0.3×(newConf/existingConf) 上限 0.5）——命名与实现不符，ProfileExtractorService L152-154 日志读 strategy 判断行为会被误导；且 design/10 §2.6 规则 4 定义"EMA 平滑系数 0.4"，代码用 0.3×置信比，与设计文档有偏差 | strategy 改 "EMA_MERGE"；javadoc 记录与设计 0.4 的差异理由或收敛 | 语义自洽；画像合并行为可审计 | 不删除（PROF-023 合并门控核心） |

### P2 局部（死代码、命名、小重复）

| 编号 | 位置 | 问题描述 | 建议方案 |
|---|---|---|---|
| P2-1 | profile/ProfileMetaStamper.java（全文件） | design/10 §2.5 要求"单次写入批次封顶 10 次更新"，实现无封顶（当前字段数有限未触发，字段增多后单次会话可污染画像） | 在 ProfileExtractorService 批量合并处封顶或 MetaStamper 提供批量接口 |
| P2-2 | device/DeviceService.java L462-471 vs device/PlatformDeviceService.java L169-202 | 两处独立实现审计落库，新增审计字段需双处同步 | 统一走 AuditLogService 或共享 AuditRecorder |
| P2-3 | toc/TocFamilyService.java L38-54 | createProfile 接收裸 `Map<String, Object> body`，key 拼写错误编译期不可发现 | 定义 TocChildProfileCreateDTO（record） |
| P2-4 | auth/AuthUserService.java L43-56 | recordLoginSuccess 手动 TenantContextHolder set/clear，异常路径易漏 clear | 收敛到 callAsSystem 工具统一管理 |
| P2-5 | design/BEACON.md #23 | 决策表仍写"remote 阈值 0.55"，与 design/06 §3.3（S4-1 收敛 + B-07/D-04 台账修正 2026-08-08：全链 0.70 单值）及代码 VoiceprintVerifyService L37（0.70）不一致——**文档间漂移**（非质疑冻结决策本身） | BEACON #23 标注"已被 OD-014 修正：全链 0.70 单值" |
| P2-6 | auth/ParentAuthService.java、consent/GuardianConsentService.java（手机号处理） | P0-4 手机号加密仅覆盖 toC（TocAuthService L95/100/125）；家长/监护人手机号仅脱敏日志、未走 FieldEncryptionService。需核对 doing/64 字段加密开关的字段范围清单，若覆盖则属遗漏 | 按 doing/64 范围清单核对并补齐；范围未覆盖则记录为有意豁免 |

### 未发现项（如实说明）

- **金额精度**：本板块无金额计算字段（billing 包仅套餐/配额规则，usage 仅计数），未发现精度问题；
- **死代码**：未发现（已逐一验证接线状态）；
- **资源管理**：未发现未关闭的流/连接（Lua 脚本与 Redis 操作均为短事务，无泄漏模式）；
- **链式依赖**：未发现服务间深层链式调用（跨包仅 4 处单向下沉）。

## 4. 改进候选排序

**Strong（低风险高收益，建议直接进入集中修复）**
1. P0-1 + P0-2 事务边界补全与空 catch 修复（consent/toc 两处小改动，合规与一致性直接受益）
2. P0-3 绑定页域名配置化（消除上线隐患）
3. P1-2 Toc 包错误语义统一 BizException（机械替换，收益明确）
4. P1-6 优先补 EntitlementChecker / TokenBlacklistService / VoiceprintLoginService 测试（纯函数决策表 + 安全核心，登录链重构期回归保护）

**Worth exploring（需小设计决策后实施）**
5. P1-1 DeviceService 解绑逻辑收敛 + 审计单点（先做局部收敛，不做大拆分）
6. P1-3 LoginRateLimiter 接口拆分或契约明确
7. P1-4 DeviceVoiceprintService 自动 complete 收尾（先核对 voice-service 对接状态）
8. P1-8 ProfileMergeGate strategy 命名收敛 + 与 design/10 平滑系数对齐
9. P1-5 UsageCollector schema 配置化 + 迁移注释

**Speculative（收益大但风险高，暂缓）**
10. P1-7 StudentProfileService / DeviceService 大拆分（先完成局部收敛，观察后再拆）
11. LoginOrchestrator 旅程地图 javadoc 补充（体验性优化）

## 5. 设计一致性核对（与 design/*.md 差异清单）

| 项 | 设计依据 | 代码现状 | 结论 |
|---|---|---|---|
| 声纹阈值 0.70 单值 | design/06 §3.3（S4-1 收敛，B-07/D-04 台账修正 2026-08-08）；TASK-TRACKER OD-001/OD-014 | VoiceprintVerifyService L37 构造器默认 0.70；local 端由 API 派生 | ✅ 一致（BEACON #23 文档未同步 → P2-5） |
| 声纹 local 默认 / remote 可选 / 事后补录 | BEACON #23（冻结） | 06 §3.3 `MINDSAFE_VOICEPRINT_MODE:local` 默认；代码未发现强制 remote 路径 | ✅ 一致（冻结决策未质疑） |
| 监护人同意 PIPL §31 闭环 | design/12 §4.4、design/09 追加同步 L393-394（R-007 reactivate、R-008 脱敏守卫） | GuardianConsentService 闭环 + R-007 L107-116 + R-008 L83-85 | ✅ 一致（但 P0-1 事务缺口） |
| 撤回删除范围 R-009（PIPL §47） | design/09 L393：撤回即删画像 + voiceprint/long_term/emotion_diaries 由 DataRetentionCleanupJob 每日清理 | ConsentWithdrawalService L97-101 删 guardian_consent + git log `21712026` R-009② 已合并 | ⚠️ 基本一致，需核对 ConsentWithdrawalService 内"待 DataRetentionCleanupJob 支持"注释是否已过时 |
| 画像合并门控四规则 | design/10 §2.6：置信加权/冲突/半衰期衰减/污染防护（EMA 0.4） | ProfileMergeGate 实现置信加权/冲突/EMA，**无半衰期衰减规则**；规则 4 命名 "REPLACE" 实为 EMA（0.3×置信比） | ⚠️ 差异：P1-8（命名+系数）；半衰期衰减由 decay 字段在数据层处理，属设计分层口径，需确认归属 |
| ProfileMetaStamper 10 次封顶 | design/10 §2.5 | 未实现 | ⚠️ 差异：P2-1 |
| 画像来源可信度排序/置信门槛 0.5 | design/10 §2.5（teacher_input > scale > rule_agg > llm_extract > memory > voice_ser） | ProfileMetaStamper confidence=evidence/(evidence+2)，2 次证据即过 0.5；provenance 各来源写入 | ✅ 一致（门槛对齐） |
| 家长端双模式登录 | design/12 §4.2（家庭码注册 + 手机号密码登录） | ParentAuthService 实现家庭码注册 + 手机号密码登录 | ✅ 一致 |
| EntitlementFilter | BEACON（BA-06 并入 frozen/38，不再单独跟踪） | EntitlementChecker/TenantPlanResolver 已实现并被 counseling-api EntitlementFilter 消费 | ✅ 一致（已接线非死代码） |
| WebAuthn AUTH-034 | TASK-TRACKER 已冻结管理 | 本板块无相关实现 | ✅ 排除（不质疑冻结） |
| 字段加密开关 | design/06 §4.0（doing/64 合并定稿） | FieldEncryptionService enabled=false 默认明文透传 + 防呆告警 | ✅ 一致；覆盖范围待核对（P2-6） |
| 会话归属校验 | T4 批次 A（租户条件强制内置） | SessionAccessService 三接口均内置租户条件 | ✅ 一致 |

## 6. 修复建议

**P0 全修（3 条）**：P0-1/P0-2 事务与空 catch、P0-3 域名配置化——均为小改动，直接进入集中修复批次。

**P1 按收益排序（建议进入同一批次或紧随其后）**：
1. P1-2 错误语义统一（机械、零风险，为 P0-2 的 catch 修复铺路）
2. P1-6 补测 EntitlementChecker/TokenBlacklistService/VoiceprintLoginService（登录链重构期保护，纯函数低成本）
3. P1-1 解绑逻辑收敛 + 审计单点（先收敛后拆分）
4. P1-8 strategy 命名收敛（与 P2-5 文档同步一并做）
5. P1-3 / P1-4 / P1-5 需小设计决策，可与上轮架构深化批次合并
6. P1-7 拆分建议与画像域重构合并评估，不单独立项

**P2 可选**：P2-1/P2-2/P2-3/P2-4 随手修复；P2-5 文档同步；P2-6 需先核对 doing/64 字段范围再定。

**不建议进入本板块修复的**：LoginOrchestrator 大拆（S-001 刚落地，观察期）、DeviceService 三拆（依赖 P1-1 局部收敛效果）。
