# 07 SaaS 多学校隔离架构

> 来源：`doc/his/07_SaaS多学校隔离架构.docx`（原文 260 行）
> 状态：已转换 | 关联：06 数据库结构、05 老师后台、12 技术架构、决策 #6（Schema 级多租户）
> 核心：**学校为租户粒度，Schema 级隔离 `tenant_{tenant_id}`**，跨租户查询在代码层面严格禁止。
> ⚠️ **实现现状（2026-07-28 核对 / M1-003 fail-fast 已收紧）**：当前代码为**共享表 + 行级 tenant_id 过滤**（所有实体带 `tenantId`）。fix-06 落地路线 A 纵深防线——`counseling-domain` 模块 `com.mindsafe.tenant` 包启用 MyBatis-Plus `TenantLineInnerInterceptor`（已认证请求经 `TenantContextHolder` 自动注入 `tenant_id` 条件；独立 `counseling-tenant` 模块未建，不预建空壳），M1-003 已进一步收紧为 **fail-fast**：无租户上下文且未声明系统作用域（`runAsSystem`/`callAsSystem`）的业务表 DAO 调用直接抛 `IllegalStateException`。`ParentAuthService` 已去单租户硬编码。Schema 级物理隔离仍**未落地**（仍为单 schema 共享表），与决策 #6 存在架构级偏差，详见 §11。

---

## 1. 多租户架构设计

### 1.1 租户模型：学校即租户

采用**学校（School）作为租户粒度**。理由：业务边界清晰（组织架构天然闭合）、数据敏感性高（学生心理测评数据需严格隔离）、配置差异大（独立预警规则/测评体系/通知渠道）、部分重点学校要求私有化部署。

### 1.2 租户标识策略（三级结构）

| 标识类型 | 格式示例 | 说明 |
|------|------|------|
| TenantId | `school_xxxxx` | 全局唯一 UUID，用于数据库主键和 API 路由 |
| SchoolCode | `Nanjing_No1_High` | 业务编码，用于 SSO 集成和外部引用 |
| SubDomain | `nanjing-no1.ai-school.com` | 多租户域名隔离前缀 |

> **关键原则**：TenantId 不可暴露给前端，仅在后端服务间传递；SchoolCode 可出现在 URL 和日志中；所有跨租户操作必须携带 TenantId 且需二次校验。

### 1.3 租户配额管理

| 配额维度 | 免费版 | 专业版/私有部署 |
|------|------|------|
| 用户规模 | 500 人 | 无限制 |
| 存储空间 | 10GB | 100GB+ |
| API 调用频率 | 1000 次/分钟 | 10000 次/分钟 |
| 功能模块 | 基础测评 | 全部模块 |

配额校验在 API 网关层完成，超额返回 `429`，配额信息通过 `X-RateLimit-*` 响应头透传。

## 2. 数据隔离策略

### 2.1 数据库隔离级别：Schema 级隔离（决策 #6）

采用 **Schema 级隔离** 作为主要隔离手段：隔离强度适中（强于共享表，成本低于独立库）、运维复杂度可控、性能影响小（同实例内 Schema 查询优于跨库）、迁移路径清晰。

Schema 命名规范：`tenant_{tenant_id}`，如 `tenant_school_001`。每个租户 Schema 包含完整业务表集合，共享相同表结构定义。

### 2.2 租户数据路由机制

数据路由采用**中间件注入模式**：

1. 请求进入 API 网关，从 JWT/Header 提取 TenantId
2. 网关将 TenantId 注入 `X-Tenant-Id` 请求头
3. 应用层数据访问框架拦截查询，自动切换到对应租户 Schema
4. 持久层在 INSERT/UPDATE 时自动填充租户标识字段
5. 跨 Schema 查询需显式声明 `@CrossTenant` 注解并经安全审批

### 2.3 跨租户查询禁止规则

以下行为在代码层面严格禁止：

- 禁止不带租户上下文的查询，违者触发代码审查拦截
- 禁止在事务中同时操作多个租户 Schema，违者触发运行时异常
- 禁止使用管理员权限绕过租户数据边界
- 禁止在日志中打印其他租户的数据（脱敏规则）
- 禁止文件系统中不同租户的数据文件混放

## 3. 认证与授权

### 3.1 学校 SSO 集成

支持钉钉、企业微信两大国内主流办公平台：

| 平台 | 认证协议 | 用户属性映射 | 管理功能 |
|------|------|------|------|
| 钉钉 | OAuth2.0 + CAS | staffId → UserId | 组织架构同步 |
| 企业微信 | OAuth2.0 + 企业可信 IP | UserId → UnionId | 部门映射 |

**SSO 配置流程**：管理员录入 SSO 凭证（AppKey/AppSecret/Callback URL）→ 系统生成租户专属 Callback 域名 → 首次登录触发用户目录同步 → Token 与租户上下文绑定，有效期 24 小时。

### 3.2 跨校认证禁止

- 用户 Token 必须绑定 TenantId，解构 Token 时同步校验归属租户
- 跨校用户访问自动跳转至归属学校登录页
- 跨校 API 调用返回 `403` 并记录安全审计日志
- 一个手机号可绑定多个 TenantId，但需显式切换上下文

### 3.3 权限继承与覆盖（RBAC + ABAC）

- **平台级权限**（系统管理员）：跨校管理、账单管理、系统配置
- **租户级权限**（学校管理员）：本校用户管理、配置管理、数据查看
- **角色级权限**（教师/辅导员）：所辖学生数据、预警处理、测评管理
- **数据级权限**：只能访问归属自己（含下级）的学生数据

> **覆盖规则**：租户级配置可覆盖平台级默认配置（如预警阈值），但**不可覆盖安全策略和审计规则**。

## 4. 学校级配置

| 配置类别 | 可配置项 |
|------|------|
| 品牌定制 | Logo（PNG/SVG ≤200KB，180x60px）、学校全称（≤20 字）、简称（≤10 字）、主题色（HEX） |
| 预警规则 | SCL-90 总分阈值（默认 160，可配 80-200）、因子异常阈值（默认 2.0，可配 1.5-3.5）、危机等级分界线、连续未测评提醒周期（默认 14 天，可配 7-60）、响应超时（默认 48h，可配 24-168h） |
| 测评题目 | SCL-90（必选）；SDS/SAS/MMPI/EPQ/UCLA 孤独量表（可选，部分专业版）；切换测评体系需二次确认并归档历史数据 |
| 通知渠道 | 钉钉机器人（Webhook/关键词/加密）、企微机器人（Webhook/AgentId）、邮件（SMTP/发件人/签名模板） |

## 5. 网络与安全

### 5.1 传输与存储加密

- 全站强制 HTTPS，TLS >= 1.2
- 证书由可信 CA 签发（Let's Encrypt 或学校自有 CA）
- API 网关到后端内部通信采用 mTLS 双向认证
- 敏感字段（身份证号、心理测评原始数据）AES-256 加密存储
- KMS 集中管理加密密钥，支持自动轮换

### 5.2 API 鉴权机制（三层）

1. API Key + Secret（服务端到服务端调用）
2. JWT Bearer Token（用户端访问，有效期 24 小时）
3. TenantId + UserId 组合校验（防越权）

> Token 结构（JWT Payload）：`iss`（签发者）、`sub`（用户 ID）、`tid`（租户 ID）、`rid`（角色 ID）、`exp`（过期时间）。

### 5.3 租户网络隔离

租户专属 VPC/安全组；数据库只对内网开放，禁止公网直连；跨租户数据交换需消息队列 + 审批流；文件存储采用租户隔离的 Bucket/目录策略。

## 6. 性能隔离

| 资源类型 | 免费版限制 | 专业版限制 |
|------|------|------|
| CPU 瞬间峰值 | 2 核 | 8 核 |
| 内存 | 4GB | 16GB |
| 数据库连接池 | 20 连接 | 100 连接 |

- **公平调度**：加权公平队列（WFQ），基础权重默认 1.0；突发请求可临时借用空闲配额但需 60 秒内归还；批量任务安排低峰期；异步任务队列按租户分组。
- **异常限流**：QPS 超配额 150% 预警、200% 自动限流；检测频繁跨权限访问/大量失败认证；异常租户进观察池降级；限流返回 `429` + `Retry-After`。

## 7. 合规隔离

- **数据驻留**：敏感数据（学生心理档案）必须存储在大陆境内；私有化部署完全驻留学校指定数据中心；跨境传输需脱敏和审批。
- **敏感数据隔离**：心理测评原始数据用租户专属加密密钥物理隔离；学生个人信息与测评结果分离存储通过学生 ID 关联；敏感字段字段级加密权限分离；导出数据自动加水印（含学校标识和导出人）。
- **审计日志隔离**：日志按租户独立存储，文件命名含 TenantId；保留期限（免费版 30 天/专业版 1 年/私有化自定义）；高危操作实时同步审计系统；审计日志只读；租户管理员查本校，平台管理员可跨校查询。

## 8. 私有化部署模式

- **单校私有部署**：All-in-One（≤500 人）/ 分离部署（弹性扩展）/ 容器化部署（Kubernetes 高可用）。学校内网部署，防火墙控制访问，单一入口域名，数据库置于 DMZ。
- **混合部署**：本地节点（学校机房，运行敏感数据处理模块）+ 云端节点（公有云，运行非敏感模块），本地与云端通过加密通道同步（脱敏后），云端控制台统一管理。

## 9. 运维隔离

| 监控维度 | 指标项 | 告警阈值 |
|------|------|------|
| 业务指标 | 日活用户、测评完成率 | 较昨日下降 20% |
| 性能指标 | API 响应时间、数据库 QPS | P99 > 2s |
| 资源指标 | CPU/内存使用率、存储空间 | 使用率 > 80% |
| 安全指标 | 认证失败率、异常访问 | 失败率 > 5% |

- **故障隔离**：实例级隔离（租户优先调度独立实例）、租户级 API 熔断、主备节点自动切换、每 4 小时增量备份 + 每天全量备份。
- **灰度发布**：按租户 ID 哈希决定新版本、功能开关按租户配置、租户内 A/B 测试、5 分钟一键回滚。

---

## 10. Java 实现层适配（补充）

> 原文的路由/隔离描述与技术无关，本节补充 Java 技术栈（决策 #5/#6/#8）落地映射。

| 隔离要求 | Java 实现方案 |
|------|------|
| Schema 级隔离切换 | `AbstractRoutingDataSource` + `ThreadLocal<TenantContext>`，请求进入时由 Filter 解析 `X-Tenant-Id` 绑定，MyBatis 拦截器切换 `search_path` 到 `tenant_{id}` |
| 租户上下文注入 | `TenantContextFilter`（网关后第一道 Filter），从 JWT `tid` 提取并绑定，请求结束 `finally` 清理 ThreadLocal（虚拟线程下同样适用）。**实态：未建独立模块，由 `JwtAuthenticationFilter` + `TenantContextHolder`（common）实现，见 §11** |
| 跨租户查询防护 | MyBatis-Plus `TenantLineInnerInterceptor` 或自定义拦截器强制校验；未绑定 TenantContext 的 DAO 调用抛异常；`@CrossTenant` 注解走独立审批 AOP |
| 配额/限流 | Spring Cloud Gateway（或网关层）+ Redis 令牌桶，按 TenantId 维度限流，超额返回 429 |
| SSO 集成 | Spring Security OAuth2 Client，钉钉/企微各实现 `OAuth2UserService`，登录成功后签发绑定 `tid` 的 JWT |
| 敏感字段加密 | MyBatis TypeHandler + AES-256，密钥从 KMS/Vault 按租户获取；导出加水印在 Service 层完成 |
| 权限过滤 | Spring Security `@PreAuthorize`（RBAC）+ 数据权限拦截器（ABAC），与 05 文档角色权限表对齐 |

> **隔离铁律**：TenantContext 未绑定时，任何进入持久层的查询必须**快速失败（fail-fast）抛异常**，绝不允许「无租户条件」的查询穿透到数据库。这是防止跨校数据泄露的最后一道代码防线。

---

## 11. 深化设计（2026-07-28）：实现现状对照、无状态化影响与租户配置补全

> 图例：🟩 已生效 / 🟧 已实现零调用 / 🟫 仅骨架/部分实现 / ⬜ 未实现

### 11.1 实现现状四态对照（含架构级偏差登记）

| 设计项 | 章节 | 状态 | 核对结论（2026-07-28） |
|------|------|:---:|------|
| Schema 级隔离 `tenant_{id}` | §2.1/§10 | ⬜ | **未落地**。无 `AbstractRoutingDataSource`、无 `search_path` 切换、无 RLS `SET app.tenant_id`。实际为**单 schema 共享表 + 行级 tenant_id 列** |
| 租户拦截器（`com.mindsafe.tenant`@counseling-domain） | §10 | 🟩 | fix-06 落地：`MindSafeTenantLineHandler` + `MybatisPlusConfig`（注册 `TenantLineInnerInterceptor`），依赖 `mybatis-plus-jsqlparser`；由 app 层 `@ComponentScan("com.mindsafe")` 装配。**独立 `counseling-tenant` 模块未建（不预建空壳）**。`TenantContextFilter` 未建，改用 `TenantContextHolder`（common，ThreadLocal）+ JwtAuthenticationFilter set/clear |
| 租户上下文 | §10 | 🟩 | 双通道：`JwtAuthenticationFilter.TenantContext`（record，Controller 显式取用）+ fix-06 新增 `TenantContextHolder`（common 层 ThreadLocal，filter 请求内 set、finally clear，供拦截器读取）。虚拟线程下如迁移须改 `ScopedValue` |
| 行级 tenant_id 过滤 | §2.3 | 🟩 | 实体统一带 `tenantId` 字段；fix-06 启用 `TenantLineInnerInterceptor` 自动注入 `AND tenant_id=?` 纵深防线，M1-003 已收紧「无租户条件查询 fail-fast」铁律：无上下文且非系统作用域直接抛 `IllegalStateException`，`tenants` 公共标识表恒忽略；合法跨租户链路（定时任务/预认证查询）经 `runAsSystem`/`callAsSystem` 显式声明（见 §11.4） |
| SSO（钉钉/企微） | §3.1 | ⬜ | 未实现，当前为家庭码/PIN 自有认证（见 24 篇） |
| 配额/限流 | §1.3/§6 | 🟫 | 有 `ratelimit` 包（API 级），未按租户维度分层计量（归 38 计费配额） |
| 私有化部署 | §8 | ⬜ | 设计期保留 |

**偏差定性（路线 A 已定稿，fix-06 已落地首道防线）**：MVP 阶段「共享表 + 行级过滤」是合理的 KISS 简化（单校试点无隔离压力），但与决策 #6 的 Schema 级目标态存在**架构级偏差**。两条路线：
- **A（已选，fix-06 + M1-003 落地）**：多租户沿用行级隔离，补两道防线——①✅ 已启用 MyBatis-Plus `TenantLineInnerInterceptor` 强制注入 tenant_id 条件 ②✅ 无 TenantContext 的 DAO 调用 fail-fast（M1-003 收紧：无上下文且非系统作用域抛 `IllegalStateException`；合法跨租户链路经 `TenantContextHolder.runAsSystem/callAsSystem` 显式声明，异步线程由 `TaskDecorator` 传播上下文）。Schema 级隔离推迟到首个「要求强隔离」的付费学校签约前实施（expand 路径清晰：按 §2.1 建 schema + 数据搬迁）。
- **B**：现在实施 Schema 级切换。成本高（Flyway 多 schema 迁移、连接池、运维），当前无对应客户需求，违背 YAGNI。

### 11.2 无状态化（design/40）对隔离的影响

- 当前 `TenantContext` 为请求级 record（随 SecurityContext），**本身无状态化友好**，优于设计稿的 ThreadLocal 方案；若未来引入 ThreadLocal/ScopedValue 注入，虚拟线程下须用 `ScopedValue` 而非 ThreadLocal。
- 真正的冲突点在**会话内存态**：`SessionState`（语音情绪趋势等）驻留单实例内存，多实例水平扩展时同一学生请求落到不同实例会丢失租户内会话状态——无状态化改造（40 篇 STATE 系列）须将其外置 Redis，key 必须带 `tenant_id` 前缀实现租户命名空间隔离。
- Schema 迁移采用 **expand-contract**：新增列/表先 expand（兼容旧代码）→ 全量发布 → contract 清理。若未来切 Schema 级隔离，Flyway 须逐租户 schema 循环执行迁移，发布窗口内允许新旧结构并存。

### 11.3 学校级配置补全与偏差修正（§4）

- **补充配置项：心理援助热线号码**（SAFE-203）。危机干预页展示的热线（如 12355/北京 010-82951332）须按学校属地配置，进入 §4 配置表「预警规则」类别；前端从租户配置接口读取，不得硬编码。14/27 篇已引用此项。
- **偏差登记：§4 预警规则写「SCL-90 总分阈值」与实际不符**——①系统实际风险体系为绿/黄/橙/红四级（04 篇规则库 + fuseRiskSignals），无 SCL-90 阈值逻辑；②SCL-90 不适用小学生，量表选型以 34 篇为准（PHQ-A/GAD-7 等）。§4 该行待量表施测（SCALE-001/002）落地后按实际量表重写，本节先行登记不改写正文。

### 11.4 任务归口

| 事项 | 归口任务 | 优先级 | 责任人 |
|------|------|:---:|------|
| `TenantLineInnerInterceptor` 启用 | ✅ fix-06 已落地（策略 B 稳健渐进：已认证自动注入 tenant_id；`MindSafeTenantLineHandler` + 6 用例守卫）；`ParentAuthService` 去单租户硬编码同期完成 | P1 | Agent |
| 无上下文 DAO 调用 fail-fast 收紧 | ✅ M1-003 已落地（2026-07-28）：`ignoreTable` 无上下文分支改为抛 `IllegalStateException`；配套 `TenantContextHolder` 系统作用域 API（`runAsSystem`/`callAsSystem`）+ `TaskDecorator` 异步传播；8 处合法跨租户链路（登录/注册/企微回调/定时任务）显式包裹；单测 6 用例 + IT 18 用例全绿且 fail-fast 零误触发 | P1 | Agent |
| 隔离路线 A/B 决策 | ✅ 已定稿 A：行级隔离为正式架构（2026-07-28 项目负责人）；加固三件套=拦截器覆盖测试+跨租户越权测试+PG RLS 兜底；BIZ-001 维持挂起，遇采购方硬性物理隔离要求再启动 B | P1 | 项目负责人 |
| 热线号码租户配置化 | SAFE-203 | P1 | Agent |
| SessionState 外置 Redis（租户前缀） | STATE 系列（40） | P2 | Agent |
| §4 预警规则按实际量表重写 | SCALE-001/002 后置 | P2 | Agent |
| SSO 钉钉/企微 | 商用前（随学校采购需求） | P3 | 项目负责人+Agent |
