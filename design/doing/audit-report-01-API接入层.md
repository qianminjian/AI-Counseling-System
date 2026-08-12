# 审计报告 01 - API 接入层

- **审计时间**：2026-08-12
- **审计范围**（只读）：
  - `backend/counseling-api/src/main/java/com/mindsafe/api`（controller 35 / security 10 / auth 4 / filter 2 / websocket 4 / ratelimit 2 / config 5 / dto 5，共约 7,372 行）
  - `backend/counseling-app/src/main/java`（4 文件：启动类、租户异步传播、日志脱敏 Appender）
  - `backend/counseling-common/src/main/java`（11 文件：dto/util/enums/exception/tenant）
  - 对应测试：api 46 个测试文件、app 8 个、common 3 个（仅评估覆盖与可测试性）
- **审计方法**：git log 只读变更热点 → 走读全部核心文件（鉴权链/过滤链/限流/WebSocket/异常处理）→ grep 调用方分布识别死代码与重复 → 对照 design/03、08、10、11、12 与 BEACON/TASK-TRACKER 冻结决策 → 输出分级发现清单。

---

## 1. 板块概况

**结构**（分层与 design/03 §3.3 一致：`counseling-api`=Web 层，`counseling-app`=装配，`counseling-common`=共享）：

```
counseling-api
├── controller/   35 个（TeacherController 477 行最厚，AuthController 402 行次之）
├── security/     认证链（JwtTokenProvider 280 行 / JwtAuthenticationFilter / 4 个 AuthProvider / ParentIdentityResolver / SecuritySupport）
├── auth/         登录编排（LoginOrchestrator S-001 单点 / TrialAuthStrategy）
├── filter/       EntitlementFilter（权益，功能冻结）/ TraceFilter
├── websocket/    预警推送（Handler + HandshakeInterceptor + Config + Listener）
├── ratelimit/    RateLimiter（Redis 固定窗口）+ RateLimitInterceptor
├── config/       SecurityConfig / GlobalExceptionHandler / WebMvcConfig / OpenApiConfig / SystemConfigProperties
└── dto/          chat 3 + device 1 + toolbox 1（另有 common/dto 5 个、controller 内嵌 record 约 20+）
counseling-common  ApiResponse / ErrorCode / BizException / TenantContextHolder / PiiDesensitizer / ClientIpResolver / TextUtils 等
counseling-app     MindSafeApplication / TenantContextPropagationConfig / PiiDesensitizingAppender / MaskedLoggingEvent
```

**依赖关系**：api 层依赖 service（16 个文件 import `com.mindsafe.domain.entity.*`）、依赖 common；service 依赖 domain；无 Mapper 注入 controller（design/03 §4.2 分层纪律已遵守，经 grep 验证仅 ObjectMapper 例外放行）。

**规模**：api 模块约 7.4k 行 / 67 文件；测试 46 文件覆盖 35 个 controller 中 34 个（缺 TocProfileController），security/auth/websocket/ratelimit 均有测试；`EntitlementFilter`、`SecuritySupport`、`TraceFilter`、`GlobalExceptionHandler`、`RateLimiter` 无直接单测。

## 2. 热点与风险初判（git log 近 30 条）

| 热点 | 涉及提交 | 风险初判 |
|---|---|---|
| doing/93 S-001 登录链收敛单点 | 013756db | 已落地 LoginOrchestrator，但签发仍部分留在 AuthController（trialRegister 内联签发） |
| doing/93 S-011① 认证上下文统一单点 | 6691f80c | **迁移不完整**：4 套写法并存（见 F1），本板块最大一致性风险 |
| doing/92 R-017 单次 parse / R-016 tokenType 枚举化 | ca243cf4 / a178717e | **仅覆盖 HTTP filter 链**：WebSocket/家长/refresh/voice 仍 6~8 次 parse（见 F2） |
| BUG-A-TOKEN-01 未认证统一 401 | 2152c834 / c21606be | 已修复，但 SecurityConfig 手拼 401/403 JSON 与 ApiResponse 契约不一致（见 F6） |
| doing/90 安全扫描 P3 | 8ee9b505 | 安全头部落地，但 SecurityConfig 出现两段重复 headers 配置（见 F16） |
| doing/93 S-011③ Map 类型化收敛 | 240354b1 | 仅收敛 DeviceController 等部分，controller 层仍 25+ 处 `Map<String,Object>`（见 F11） |
| BUG-T-RC-01 班主任角色补配 | e7a2eba1 前序 | 角色清单双维护已实际发生过漏配事故（见 F4） |

## 3. 发现清单（分级）

### P0 架构级

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试判断 |
|---|---|---|---|---|---|
| F1 | `api/security/SecuritySupport.java:23` vs `api/controller/ChatController.java:139-144` vs `api/controller/AuthController.java:135,149,190,238` vs `api/controller/TeacherController.java:74,134,228,236,243,269,346,373,437` | **S-011 冻结决策"认证上下文提取统一单点"未完成**：同一模块 4 套写法并存——SecuritySupport.requireContext（部分 controller 用）、ChatController 私有 extractContext（与 SecuritySupport 逐字重复）、AuthController 内联 instanceof（4 处）、`(UUID) auth.getPrincipal()` 强转（TeacherController 10 处 / AuthController 2 处，null 或类型不符时 ClassCastException→500 而非 401） | 集中修复：全量迁移到 `SecuritySupport.requireContext`；删除 ChatController.extractContext 与全部内联 instanceof/getPrincipal 强转 | locality：鉴权上下文语义收敛单点，新 controller 无所适从问题消除；错误码统一 401 | **值得保留并加深**。删除 SecuritySupport 将把复杂度打回 35 个 controller 各自实现，集中度骤增；保留它并完成迁移是收敛方向 |
| F2 | `api/security/JwtTokenProvider.java:188-278`；`api/websocket/AlertWebSocketHandler.java:66-68,75,82-83`；`api/security/ParentIdentityResolver.java:38-41,59-60`；`api/controller/AuthController.java:264-273,280`（refresh 6+2 次 parse）、`205-215`（voice-login 4 次） | **R-017"单次 parse"只覆盖 HTTP filter 链**：JwtTokenProvider 保留新旧两套 API（parseOnce 快照 vs validateToken/isAccessToken/isVoiceCredential/getUserType/getUserId/getTenantId/getTokenId/getRemainingMs 全套旧方法），WebSocket 握手 6 次 parse、家长解析 4 次、refresh 6+2 次。旧 API 的"单点剥离平台前缀"约束只能靠调用方自觉，8/10 前缀漏剥事故同源模式仍在 | 扩展 parseOnce 覆盖：WebSocket/ParentIdentityResolver/AuthController 全部改用 `parseOnce` + TokenType 判定；旧 getXxx 方法收敛为基于 ParsedToken 的组合方法或直接删除 | 性能（高频端点 4~8 次解析→1 次）+ 一致性（token 语义单点） | **删除测试**：旧 API 除 refresh 黑名单 TTL（getRemainingMs 合法）与 websocket/parent 外无其他 main 调用方（grep 验证），全部迁移后旧方法可删；删除后复杂度不转移（都是同一 provider 内部） |
| F3 | `api/config/SecurityConfig.java:88-129`（permitAll 白名单）vs `api/filter/EntitlementFilter.java:88-108`（mapPathToFeature）vs `api/ratelimit/RateLimitInterceptor.java:87-111`（resolveAction）vs `api/config/WebMvcConfig.java:23-25`（addPathPatterns） | **路径知识 4 处分散维护**：新增/调整任一端点需同步 4 个文件，且风格各异（requestMatchers 前缀 / startsWith / contains / ant pattern）。EntitlementFilter 豁免路径（预警/SOS/危机"硬编码不可覆盖"）与 SecurityConfig permitAll 存在重叠判断，无单测防漂移 | 收敛为单一"路径注册表"（如 RouteCatalog：path→auth/entitlement/ratelimit 元数据），SecurityConfig/EntitlementFilter/RateLimitInterceptor 均消费该注册表 | locality：路径策略一处声明全局生效；避免豁免误配（安全红线） | **值得保留/加深**：各拦截器功能不同不可删，但路径映射逻辑抽离为注册表后，filter 变薄且映射可单测 |
| F4 | `api/websocket/AlertWebSocketHandler.java:39`（ALERT_ROLES）vs `api/config/SecurityConfig.java:174`（教师五角色） | **角色清单双维护**：WebSocket 接入角色与 REST 教师角色各维护一份；BUG-T-RC-01（HEAD_TEACHER 漏配全接口 403）已证明该模式会漏配 | ALERT_ROLES 引用 SecurityConfig 统一常量（或共享角色常量类），单点声明 | 一致性：角色增改不再两处漂移 | 删除测试：抽出共享常量后 WS 角色判断逻辑仍留 handler（认证语义不同），常量集中不搬移复杂度 |
| F5 | `api/filter/EntitlementFilter.java:77-78`；`api/filter/EntitlementFilter.java:88-108`（无测试） | **权益拦截响应手拼 JSON 无转义**（message 含引号/emoji 即破 JSON）；mapPathToFeature 路径映射是计费功能冻结（design/38 §4.2）的关键决策面，**无任何单测** | 复用 ApiResponse 序列化出口；为 mapPathToFeature + 豁免路径补单测（表驱动：路径→feature/豁免） | 安全（计费绕过/响应完整性）+ 可测试性 | 删除测试：filter 骨架保留，映射逻辑抽为可单测纯函数（MapPathFeatureResolver），复杂度不转移 |
| F6 | `api/config/SecurityConfig.java:68,73`（{code,message,success}）vs `api/filter/EntitlementFilter.java:78`（{code,message}）vs `api/ratelimit/RateLimitInterceptor.java:78`（{code,message,data}）vs `common/dto/ApiResponse.java:13`（{code,message,data,timestamp}，无 success） | **错误响应契约 4 套形状**：401/403 手拼 success 字段、限流手拼 data、权益拦截无 data/timestamp——前端 authFetch 需兼容多形状，新增拦截器易再破契约 | 统一出口：所有非 controller 路径（entryPoint/deniedHandler/限流/权益）经同一 ApiResponse 序列化工具写回 | 契约一致性；前端分支收敛 | 值得保留：统一序列化工具（非新增层），删除测试通过（纯工具无状态） |
| F7 | `api/config/GlobalExceptionHandler.java:41-85` | **错误码→HTTP 状态映射与 ErrorCode 枚举脱钩**：魔法数字 switch 按分段+显式 case 维护，新增 ErrorCode 忘改映射即语义错乱（如 20xxx 新码落 400）。resolveStatus 中 20002/20003/20007/20010 等显式列表与 ErrorCode 注释无编译期绑定 | ErrorCode 枚举增加 httpStatus 字段（或映射表以枚举为 key），resolveStatus 删除；同时删除已无 case 的枚举注释 | 编译期强制：加错误码必须定状态码 | 删除测试：状态映射从 handler 移入 ErrorCode 后 handler 变薄；若移除 ErrorCode 内映射则 400/500 语义分裂，故值得保留 |

### P1 模块级

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试判断 |
|---|---|---|---|---|---|
| F8 | `api/controller/TeacherController.java:266-325,370-431,434-470,473-475` | **controller 过厚（477 行）**：HTML 周报模板（60+ 行内联 CSS）、CSV 导出+转义（csv()/html()）、情绪标签翻译（emotionZh 依赖 `com.mindsafe.ai.risk.EmotionVocabulary`）全部在 controller；表现层与端点耦合，渲染逻辑不可单测 | 抽 `AlertExportRenderer`/`WeeklyReportRenderer`（service 层或独立渲染组件），controller 只做鉴权+委托 | controller 职责清晰；渲染（CSV 转义/HTML 转义）可单测；API 层不再依赖 ai 模块 | **搬移复杂度**（渲染逻辑不消失），但收益在可测试性与职责分离，仍值得做 |
| F9 | `api/controller/TeacherController.java:250`（返回 List\<RiskEvent\>）、`api/controller/OpsController.java`、`api/controller/AdminController.java`、`api/controller/AdminTenantController.java`、`api/controller/AdminPromptController.java:59`、`api/controller/PlatformController.java`、`api/controller/TocProfileController.java` 等 16 文件；`api/auth/LoginOrchestrator.java:7,75-104`、`api/controller/AuthController.java:105,215,241` | **api 层直接依赖/暴露 domain 实体**：多个端点把 MyBatis 实体直接作响应体（RiskEvent/AlertEvent/AuditLog/Tenant/TrialInviteCode/PromptVersion/School/TocChildProfile）；User 实体（含 passwordHash:61、pinHash:70）在 api 层流转（LoginOrchestrator 用 List\<User\> 做候选查询）。实体字段演进（加列）会直接改变 API 契约，且敏感字段暴露面存在于 api 层 | 响应侧收敛 VO（高风险实体优先），认证链改用最小身份快照（CandidateSummary）替代 User 实体下传 | 契约稳定 + 敏感字段最小暴露 | 删除测试：认证链候选查询改快照后 service 侧逻辑不变；实体直接暴露是"删除后复杂度回到 service 转 VO"的搬移，但当前 16 处直接暴露属违约，值得修 |
| F10 | `api/controller/ChatController.java:151-155`（requireGuardianConsent 在 create/send/nudge 三处手动调用，58,75,102） | **监护人同意门禁（合规红线 PIPL §31）依赖 controller 逐端点记得调用**：漏一处即匿名绕过门禁，无 service 层强制兜底 | 门禁下沉 ConversationService（createSession/sendMessageStream 内强制校验），controller 层移除重复调用 | 合规强制单点：新对话端点默认带门禁 | 值得保留：门禁逻辑下沉 service 后 controller 变薄，复杂度转移至唯一入口（合理落位） |
| F11 | `api/controller/TeacherController.java:97,121,131,151,167,181,223,341,343`、`api/controller/DeviceController.java`（16 处）、`api/controller/AdminPromptController.java:56-80` | **S-011③ Map 类型化收敛不完整**：controller 层仍 25+ 处 `Map<String,Object>/Map<String,String>` 作请求体/响应体（含 Map 请求体 `body.get("content")` 无编译期校验） | 续做类型化：TeacherController 的 notes/takeover/transitionCase 请求体、AdminPromptController 版本创建体改为 record；响应 Map 按端点定 VO | 编译期字段校验 + 契约可文档化 | 删 Map 换 record 后复杂度不转移（同层替换），纯收益 |
| F12 | `api/dto/`（5 文件）vs `common/dto/`（5 文件）vs controller 内嵌 record（AuthController:313-400 内嵌 11 个、DeviceController:200-213 内嵌 4 个、TeacherController:335 内嵌 1 个） | **DTO 位置三套策略无约定**：请求/响应 record 分散在 dto 包、common 包与 controller 类内；common/dto/chat 的 SessionInfo/StreamMessageEvent 与 api/dto/chat 的 CreateSessionRequest 同域却分居两处 | 定约定：请求 DTO→api/dto、跨模块响应 DTO→common/dto、controller 内嵌仅限端点私有局部类型；存量逐步迁移 | locality：找 DTO 不再跨三层翻找 | 删除测试：纯文件归位，无功能影响，低风险高一致性收益 |
| F13 | `api/config/SecurityConfig.java:117`（`/api/v1/parent/**` permitAll）+ `api/security/ParentIdentityResolver.java` | **家长端认证在 Spring Security 体系外**：permitAll + 每个 controller 方法手动调 resolveLoginIdentity/requireLinkedStudent；机制上无 filter 层强制，新端点忘调即匿名可达。N-003 已冻结"统一校验出口"（实现已收敛），本条目只指机制层缺口，不质疑冻结决策 | 可选加固：为 `/parent/**` 加 ParentAuthFilter（只做 token 解包注入 SecurityContext，不改变业务语义），controller 改从 SecurityContext 取 | 认证强制化：忘调不再静默开放 | 删除测试：新增 filter 属于加深（把分散手动调用集中为强制点），与 F1 同型，值得做但依赖冻结域边界确认 |
| F14 | `api/controller/DeviceController.java:54,61,70,137,178,184,191` | **operator 参数无认证绑定**：操作者身份从 query 传字符串，登录态用户可伪造他人 operator 落审计 | operator 从认证上下文（SecuritySupport.requireUserId）取值，query 参数废弃 | 审计完整性（设备操作留痕可信） | 删除测试：删 operator 参数后服务层签名调整，属接口收敛，复杂度不转移 |
| F15 | `api/ratelimit/RateLimiter.java:62-87 vs 98-122` | **两个 tryAcquire 方法逻辑重复**（increment→null→expire→阈值判断结构几乎逐行一致）；限流窗口/阈值硬编码不可配置 | 抽公共 incrementAndCheck 私有方法；阈值窗口改配置项（yaml 可调） | 单点演进位与窗口 | 删除测试：合并后行为不变，纯内部重构 |
| F16 | `api/config/SecurityConfig.java:51-59 vs 77-80` | **headers 重复配置**：同一 HttpSecurity 上两次 `.headers()`（CSP 块 + 安全头块），后者重复设置 frameOptions/contentTypeOptions；doing/90 P3-2 追加时未并入既有块 | 合并为单块 headers 配置 | 配置可读性 | 删除测试：合并后帧选项/嗅探保护不变（实测行为等价） |

### P2 局部

| 编号 | 位置 | 问题描述 | 建议方案 |
|---|---|---|---|
| F17 | `api/security/TocAuthProvider.java:42-45` | 私有 maskPhone 与 `common/util/PiiDesensitizer.java` 掩码能力重复（规则还不同：保留 3 后 4 vs 保留前 3 后 2） | 统一走 PiiDesensitizer 或抽 PhoneMasker |
| F18 | `app/logging/PiiDesensitizingAppender.java:15` | 注释"PiiDesensitizer（counseling-ai 模块）"过时，实际位于 counseling-common | 修正注释 |
| F19 | `api/auth/TrialAuthStrategy.java:24-27` | `authenticate(Object request)` 参数类型不安全（Object + instanceof），应直接收 TrialRegisterRequest | 改类型化参数 |
| F20 | `api/controller/AuthController.java:294`、`api/security/ParentIdentityResolver.java:36,59-60,69` | `authHeader.replace("Bearer ", "")` 无前缀校验且重复调用 3 次（ParentIdentityResolver 对同一 header 重复 replace） | 抽 header 解析工具（无前缀直接拒绝） |
| F21 | `api/controller/DeviceController.java:89,100,113,143` | `deviceCode == null \|\| !exists(deviceCode)` 存在性检查 4 处重复，且 exists 后再操作存在 TOCTOU | 下沉服务层统一校验 |
| F22 | `api/controller/AuthController.java` | 402 行承载 9 类端点（登录/试注册/改密/PIN/声纹/刷新/登出/me/监护人同意），监护人同意闭环与认证职责混杂 | 监护人同意端点拆至独立 controller（或 service 已承载则至少分层注释） |
| F23 | `api/controller/TocProfileController.java`（无测试）；`api/filter/EntitlementFilter.java`、`api/security/SecuritySupport.java`、`api/filter/TraceFilter.java`、`api/config/GlobalExceptionHandler.java`、`api/ratelimit/RateLimiter.java`（无直接单测） | 80% 覆盖率门禁下的关键缺口：权益映射（F5）、认证上下文提取、异常状态映射（F7）、限流器均无直接单测；TocProfileController 是唯一无测试的 controller | 补：EntitlementFilter 表驱动路径映射测试、SecuritySupport 上下文测试、GlobalExceptionHandler 状态映射表测试、RateLimiter 计数测试、TocProfileController 基础端点测试 |
| F24 | `api/controller/TeacherController.java:131-138` | addNote 收 `Map<String,String> body`（content/noteType 无校验），与 S-011③ 类型化方向相悖 | 改 record + @Valid |

## 4. 改进候选排序

**Strong（高信号、集中修复收益大）**
1. **F1+F2 合并**：S-011 迁移收尾 + parseOnce 全覆盖（WebSocket/Parent/refresh/voice）。一次集中改动同时消除"4 套上下文写法"与"两套 JWT API 面"，是本板块最高杠杆项。
2. **F3+F4 合并**：路径/角色注册表单点化。两类清单都已有现实事故（HEAD_TEACHER 漏配、permitAll 与豁免重叠），收敛为注册表后 security/ratelimit/entitlement 三处消费同一事实源。
3. **F6+F7 合并**：错误契约统一（ErrorCode 挂 httpStatus + 统一序列化出口）。同时修复 4 套响应形状与状态映射脱钩。
4. **F8**：TeacherController 渲染逻辑下沉。直接砍掉 477 行中最难测的 100+ 行表现层代码，controller 恢复"鉴权+委托"职责。

**Worth exploring**
5. **F9** 实体→VO 收敛（按端点风险排序，Ops/Admin/Teacher 高风险实体优先）。
6. **F10** 监护人门禁下沉 service（合规红线强制化）。
7. **F12** DTO 位置约定 + 存量迁移（低风险高一致性）。
8. **F11** Map 类型化续做。

**Speculative**
9. **F13** 家长认证 filter 化（涉及冻结域边界，需确认后实施）。
10. **F14** operator 认证绑定（设备审计完整性，依赖固件侧接口变更窗口）。

## 5. 设计一致性核对（design/*.md）

| 设计声明 | 位置 | 代码实态 | 结论 |
|---|---|---|---|
| 分层纪律：Controller 禁止注入 Mapper，租户条件由 Service 强制内置 | design/03 §4.3 | grep 验证无 mapper 注入 controller | ✅ 一致 |
| 安全管线：PiiDesensitizer 内容入 LLM 前脱敏 | design/03 §4.3 | ConversationServiceImpl:231 调用 | ✅ 一致 |
| WebSocket 三重校验（validateToken+isAccessToken+黑名单）+ 角色仅 teacher/psych/class/admin | design/08 §2.9 | AlertWebSocketHandler:66-68 一致；ALERT_ROLES 额外含 HEAD_TEACHER（:39） | ⚠️ 代码为 BUG-T-RC-01 修复的正确方向，**design/08 文档滞后**，需回写 |
| 鉴权收紧：尾部兜底改默认 deny+白名单（P2 项） | design/08 §3.1 | SecurityConfig:177 `anyRequest().authenticated()` | ✅ 已实施 |
| 认证方式矩阵：企微 OAuth 代码就绪待填 corpId/secret（🟫） | design/08 §3.2 | WeComOAuthController 存在且公开端点 `/auth/wecom/**` | ✅ 一致 |
| 权益过滤：豁免路径硬编码不可覆盖；不满足 403；配额超限 429 | design/38 §4.2（冻结） | EntitlementFilter:56-83 实现与设计一致 | ✅ 一致（仅响应体形状不符统一契约，见 F6） |
| 限流：chat 消息/建会话 30 次/分钟/用户 | design/08 §3.2 存活任务 | RateLimiter:28-29 + Interceptor:94 | ✅ 一致 |
| 声纹登录红线：不采集不存储生物数据，服务端仅存 embedding | design/08 §3.2 | 服务端仅 token 签发，模板留设备端 | ✅ 一致 |

**需排除的已冻结决策**（本报告不质疑其方向）：S-001 LoginOrchestrator 登录编排单点、S-011 SecuritySupport 统一单点（*方向冻结，本报告只指出迁移未完成*）、R-016 token 枚举化、R-017 单次 parse（*同，只指出覆盖不全*）、N-001 认证 Provider 接缝、N-003 ParentIdentityResolver 统一校验出口、DEC-007 平台独立 token 前缀、EntitlementFilter 功能本体（BILL-001）。

## 6. 修复建议

**P0 全修**（均已冻结/半冻结决策的收尾，非新架构决策）：
- F1+F2 作为**单一集中批次**实施（上下文提取单点收尾 + parseOnce 全覆盖），涉及 controller 35 个 + security 4 个 + websocket 1 个，跨文件但模式统一，适合集中修复；修复后 `SecuritySupport`/`ParsedToken` 成为唯一事实源。
- F3+F4 路径/角色注册表批次（安全敏感，需配表驱动单测）。
- F5/F6/F7 错误契约与权益映射批次（含 EntitlementFilter 单测补齐，权益功能虽冻结但测试属实现核对，不违反冻结）。

**P1 按收益排序**：F8（渲染下沉）→ F10（门禁下沉）→ F9（实体收敛，高风险端点优先）→ F12（DTO 约定）→ F11（Map 类型化）→ F15/F16（小重构）→ F14（依赖外部窗口）。

**P2 可选**：F17-F24 随 P0/P1 批次顺带处理（F20/F21 与 F1/F2 同文件可同批次；F23 测试补齐建议随各功能批次同步提交，避免独立测试批次空转）。

**不建议进入集中修复**：F13（家长认证 filter 化）——涉及冻结域边界与家长端契约，建议单独立项评估后再定，勿混入本轮批次。

**总体判断**：本板块架构基底良好（分层纪律、冻结决策落地率高、测试文件覆盖广），主要问题集中在**两轮重构（S-011/R-017）迁移未收尾**导致的同一概念多实现并存——这是"理解跳跃"成本最高、修复性价比最高的区域，建议作为下一轮集中修复的优先入口。
