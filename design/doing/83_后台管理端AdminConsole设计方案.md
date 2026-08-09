# doing/83 - 后台管理端 AdminConsole 设计方案

> 状态：开发期（doing 子文档） | 编号：83（接续 doing/82）
> 创建：2026-08-09 | 作者：Agent（钱敏健工作区）
> 关联：design/07（商业化：定价 99/159/259 元/生/年、entitlement 三层、计量 BIZ 系列 P3）；design/03 §8（监控运维架构：Prometheus+Grafana+AlertManager 10 条规则）；design/02（数据库：tenants 隔离根、行级隔离 D-12、model_call_logs）；design/04（部署：service-manager.sh 六服务健康检查、deploy.sh 唯一发布通道）；design/09 §5（风险分级 SLA：R4 红 1 分钟告警/5 分钟确认/15 分钟人工接触）；design/11 §四（预警中心工作流状态机 open→claimed→resolved→closed + S0-S3 处置 SLA）；frozen/38（P2 商业化冻结：BILL-002/003 计量计费、EntitlementFilter 权益映射）
> 产出物：管理端（AdminConsole）完整设计方案——功能清单 × 模块划分 × 与现有架构映射 × 数据库/API/前端设计 × 实施路线（含提示词配置审核中心、业务信号监控、发散补充模块）

---

## 一、背景与目标

### 1.1 背景

产品已具备学生端 / 教师端 / 家长端三端业务界面与一套平台侧 API 雏形（5 个 Controller），但**缺少面向平台运营者的统一管理入口**。深度调研（代码实态 + 设计文档 + 同类产品）后确认现状缺口：

| 管理域 | 现状 | 缺口 |
|--------|------|------|
| 前端入口 | 三端业务界面齐全（student-h5/teacher-web/parent-h5） | **无 admin-web**，管理操作靠 API/脚本/SSH |
| 系统配置 | 配置分散在 application.yml（`SystemConfigProperties` 等 @ConfigurationProperties）、环境变量、Python config.yaml（env > yaml > 代码兜底）、DB（prompt_versions） | 无统一配置管理面板，无变更留痕 |
| 应用监控 | Prometheus + Grafana + AlertManager→企微（10 条规则）；service-manager.sh 六服务健康检查；deploy-metrics/audit 部署审计 | 监控强依赖 Grafana/SSH，**无管理端视图**；服务健康状态不落库 |
| 降级监控 | 降级机制完备：LLM 主备（ResilientChatModel）、TTS 三级（CosyVoice→edge-tts→浏览器）、ASR 双引擎（ASR_ENGINE）、SER_ENABLED、VoiceDegradationPolicy；降级事件仅日志 + 告警 | **无降级状态可视化，无手动切换入口，无降级历史** |
| 租户管理 | AdminTenantController：开通/暂停/恢复/健康检查/列表（super_admin） | 缺套餐/权益/配额/到期管理（Tenant 实体无套餐字段）；配额常量 500/200 未接线校验 |
| 计量计费 | model_call_logs 已落库（token/延迟/费用）；API 级限流 | **无计量汇总、无订阅/账单**（BIZ 系列 P3，冻结 frozen/38） |
| 平台基础 | SecurityConfig 角色授权（ADMIN）；audit_logs 审计 | 无平台管理员账号体系、无角色细分、审计查询仅 200 条 |
| 提示词管理 | PromptVersionService：DB 优先（租户级→全局级→classpath 降级）、版本激活门禁（reviewer 必填 + 红队回归 + eval 回退检查）、A/B 分组、灰度评估（PEVAL）；TemplateMatrixRegistry 模板矩阵/护栏用例 | 能力完备但**无可视化编辑与审核流界面**（现靠 API/脚本）；预审核安全话术（RecallPhrases/CrisisResources/fallback）硬编码，无管理端视图 |
| 业务信号监控 | TeacherAlertController 处置闭环（认领/误报/解决/转派/回访）已实现；RiskEvent 状态机与 SLA 字段齐全（detectedAt/status/resolutionNote/outcome/notifyStatus）；RiskNotifyOutboxService 通知补偿（超 5 次转 dead 人工兜底）；alert-rules.yml **10 条规则全部为基础设施级，零业务级告警** | **无平台级预警时效监控（老师未及时干预/信号超时无曝光）**；dead 兜底无台账视图；无业务级告警规则；无处置闭环统计（认领率/处理时长/闭环率） |
| 知识库/通知/合规 | KnowledgeBaseController 知识文档审核流已实现；通知三渠道（站内 notification + 企微 WeComOAuth + 短信 AliyunSms）；DATA-005 数据导出冻结 | 无管理端接入视图；渠道状态/发送统计/失败台账无曝光 |

### 1.2 目标

1. 建设 **admin-web** 管理端（平台运营者视角），覆盖十二个模块（M1~M12）：系统配置管理、系统应用监控、服务切换降级监控、租户计量计费、租户管理、平台基础、提示词与内容配置中心、业务信号与预警处置监控、知识库与内容管理、通知渠道与触达管理、数据安全与合规中心、运营洞察
2. 复用现有平台侧 API 与监控体系，不重复造轮子：PlatformService / TenantProvisioningService / AlertService / Prometheus / service-manager.sh 全部纳入管理端能力底座
3. **提示词配置审核中心**：把提示词定义与更新收敛到管理端（可视化编辑 + 审核发布流 + 灰度 + A/B + 护栏回归），复用 PromptVersionService 既有门禁能力
4. **业务信号监控**：平台层对预警处置时效（老师未及时干预、信号超时）、通知兜底、处置闭环做管理监控，补齐业务级告警规则
5. 计量计费给出完整设计（定价模型对齐 design/07：99/159/259 元/生/年），**实施时序与 frozen/38 解冻挂钩，本方案先行冻结设计**
6. 发散补充（基于产品实态主动扩展）：知识库与内容管理、通知触达管理、数据安全合规中心、运营洞察——按 YAGNI 收敛为四个模块
7. 管理端自身安全合规：独立角色权限模型、全操作审计、敏感配置不可见（密钥不回显）

### 1.3 范围与边界

- **范围内**：平台级（跨租户）管理能力；租户级管理能力（运营者视角的租户数据查看）；配置查看与受控修改；监控可视化与降级手动切换入口；计量数据采集、汇总、订阅、账单（设计）；**提示词配置审核发布流（M7）**；**业务信号时效监控与逾期管理（M8）**；知识库/通知渠道/合规/运营洞察（M9~M12）
- **范围外**：租户内部业务运营（班级/学生/预警处理等属 teacher-web 现有能力）；家长端；开放平台/API 网关；计费收款/发票（支付渠道对接为远期，仅留接口）；Kubernetes 编排（单机 Compose 阶段）
- **红线**：配置修改遵循"非敏感可改、敏感（密钥/证书）仅查看不展示值、变更留痕、发布需二次确认"；降级切换为运维操作需审计；计量计费实施需 frozen/38 解冻议决（本方案不擅自解冻）

---

## 二、同类产品调研结论

### 2.1 教育 SaaS 管理后台（ClassIn / 希沃集控）

| 产品 | 管理端功能矩阵 | 对本方案的启示 |
|------|--------------|--------------|
| ClassIn | 教务排课 / 监课管理（实时课堂状态）/ 课堂数据报告 / 存储管理 / 成员考勤 / 财务中心 / **主账号 + 子账号权限体系**（细分角色授权） | ① 运营后台必须支持**子账号与角色细分**（运营/财务/审计只读）② 课堂实时监课 → 对应我们的**会话活跃实时监控** |
| 希沃集控 | 区域数据可视化（市-区-校层级）/ 远程教学监控 / 设备批量管控 | ① **层级化数据看板**（平台→租户→学校）② 设备/服务批量管控 → 对应我们的**服务启停与健康检查** |

### 2.2 通用 SaaS 中台：三后台模型与标准模块

**三后台模型**（行业共识）：运营管理后台（平台视角：管租户/资源/权限）+ 租户管理后台（租户管理员：管本校用户/内容）+ 业务应用（学生/教师/家长三端）。本方案即**第一层运营管理后台**。

**SaaS 中台标准模块**（对标本方案映射）：

| 标准模块 | 典型功能 | 本方案映射 |
|---------|---------|-----------|
| 租户管理 | 注册/开通、配置、隔离、生命周期 | M5（复用 AdminTenantController + 扩展套餐/配额） |
| 订阅管理 | 套餐定义、开通、升降级、续费、到期提醒 | M4（subscriptions 表 + 到期扫描） |
| 计费系统 | 用量计量、阶梯、周期、账单、发票、收款 | M4（usage_events + billing；发票/收款留接口） |
| 权限管理 | 角色、菜单、数据权限 | M6（平台管理员角色模型） |
| 运营管理 | 平台健康度、租户流失预警、客户成功 | M2/M3（监控 + 租户活跃度） |

### 2.3 心理健康 SaaS（橙星云 / 心大陆 / 心灵伙伴）

| 产品 | 管理端能力 | 对本方案的启示 |
|------|-----------|--------------|
| 橙星云 | 测评-预警-档案-干预闭环管理、**红橙黄绿四级预警**、市-校-生多层级 | ① 平台级预警总览（RiskEvent 跨租户聚合已有雏形：overview 的 totalAlerts/openAlerts）② 层级钻取 |
| 心大陆 | 学生心理档案、AI 分层预警、区域数据看板 | ① 租户详情页（PlatformService.tenantDetail 已有）强化为运营视角档案 |
| 心灵伙伴 | 多维度数据看板（测评覆盖/预警处理率/干预效果） | ① 平台运营指标看板（会话量/活跃度/预警处理率） |

### 2.4 计量计费模式（通用 SaaS）

| 模式 | 说明 | 适用性 |
|------|------|--------|
| 按用户数（per-seat） | 按注册/活跃学生数计费 | ✅ 与 design/07 定价模型（99/159/259 元/生/年）一致，**主计量维度** |
| 按用量（usage-based） | LLM 调用量/token/语音合成次数 | ✅ 成本治理维度（design/07 §2.8：LLM 成本待 model_call_logs 聚合），**辅助计量维度** |
| 按特性 + 服务等级 | 功能分层 + SLA 分级 | ✅ entitlement 三层（基础/专业/旗舰）+ SLA 99.5/99.7/99.9 |
| 计价与计费分离（腾讯云 TStack 模式） | 计量（usage_events 原始事件）→ 计价（rate 规则）→ 计费（账单）分层 | ✅ 本方案 M4 采用此分层：**计量与计价解耦，先计量后计价** |

### 2.5 调研启示汇总（方案设计原则）

1. **先计量、后计费**：计量（usage_events 采集）是地基，可先落地；计价/账单与 frozen/38 解冻同步
2. **管理端 = 运营后台 + 运维控制台二合一**：教育 SaaS 习惯将业务运营与系统运维分开，但本产品规模（单机 Compose、运营初期）合并在一个 admin-web 中，按模块与角色隔离即可（KISS/YAGNI）
3. **角色细分是行业标配**：至少支持超管/运营/运维/审计只读四类
4. **层级钻取是心理 SaaS 标配**：平台总览 → 租户详情 → 学校 → 学生/会话，数据逐层下钻
5. **到期与流失预警**：订阅到期提醒、租户活跃度下降预警（客户成功闭环）

---

## 三、现状盘点：已有能力与缺口

### 3.1 平台侧 API 实态（可直接复用）

| 类 | 端点 | 能力 | 权限 |
|----|------|------|------|
| PlatformController | GET /api/v1/platform/overview | 跨租户总览（租户/学校/学生/教师/会话/近 7 天活跃/预警统计） | ADMIN |
| PlatformController | GET /api/v1/platform/tenant-stats | 租户列表（含各校学生/教师/会话数） | ADMIN |
| PlatformController | GET /api/v1/platform/tenants/{id} | 单租户详情（学校列表 + 近 7 天会话趋势） | ADMIN |
| PlatformController | GET /api/v1/platform/schools | 跨租户学校列表 | ADMIN |
| AdminTenantController | POST /api/v1/platform/tenants/provision | 一键开通（租户+学校+管理员，强随机临时密码） | ADMIN（super_admin） |
| AdminTenantController | POST .../tenants/{id}/suspend·resume | 暂停/恢复（数据保留，禁止登录） | ADMIN |
| AdminTenantController | GET .../tenants/{id}/health | 租户健康检查（admin/student/school 计数 + healthy 判定） | ADMIN |
| AdminTenantController | GET /api/v1/platform/tenants | 租户列表（基础字段） | ADMIN |
| AdminUserController | （重置密码） | 用户密码重置 | ADMIN |
| AdminController | /api/v1/admin/invite-codes/* | 邀请码生成/批量/停用/删除/学生 CSV 导入/审计日志查询（200 条） | ADMIN |
| AdminPromptController | /api/v1/admin/prompts/* | Prompt 版本 CRUD/激活门禁（reviewer 必填）/A-B 对比/模板矩阵/红队护栏/灰度放量评估 | ADMIN |

### 3.2 监控与运维实态

| 能力 | 实态 | 管理端接入方式 |
|------|------|--------------|
| 指标采集 | Prometheus 3 个 scrape job：backend `/actuator/prometheus`（mindsafe_llm_* / mindsafe_tts_*）、tts-service `/metrics`（tts_synthesize_*、tts_engine_available{engine}）、voice-service `/metrics`（voice_analyze_*、voice_asr_ready、voice_ser_ready、voice_ser_enabled） | 管理端只读展示（数据经后端聚合 API 转发） |
| 告警 | AlertManager → 企微应用消息，10 条规则（CPU/内存/磁盘/容器重启/API 5xx/对话失败率/TTS 引擎等） | 告警事件列表页（读 AlertManager API） |
| 服务健康 | service-manager.sh：六服务（postgres redis tts voice backend nginx）健康检查；tts 消费 /health engine（cosyvoice-cloud/edge-tts/none），voice 消费 /health status（UP/DEGRADED/DOWN） | 服务拓扑页（后端定时探测或读快照） |
| 降级机制 | LLM 主备（DeepSeek V4→qwen-plus，mindsafe_llm_model_fallback_total）；TTS 三级（CosyVoice→edge-tts→浏览器 speechSynthesis）；ASR 双引擎（ASR_ENGINE 切换 funasr→dashscope）；SER_ENABLED 独立开关；VoiceDegradationPolicy（S0 静默/S1 预合成/S2 强制安抚） | M3 降级监控 + 手动切换 |
| 部署审计 | deploy-metrics.sh（12 步计时 + OK/WARN/CRITICAL 信号）、deploy-audit.sh（R1-R6 回归审计），落 logs/deploy/ | 部署历史页（读审计报告） |
| 人工兜底 | RiskNotifyOutboxService.markDead() 失败转人工 → AlertService 统一出口 | 告警中心聚合 |

### 3.3 缺口汇总（管理端要补的能力）

1. **admin-web 前端不存在** → 新建（复用 teacher-web 技术栈）
2. **配置无管理面板** → M1：配置注册表 + 变更留痕 + 生效机制（热生效/重启生效分类）
3. **监控无运营视图** → M2：服务拓扑/健康状态/指标看板/告警中心/部署历史
4. **降级无视图无开关** → M3：降级状态实时视图 + 手动切换 API + 降级事件历史
5. **租户无套餐权益** → M4/M5：subscriptions/套餐/配额/到期；Tenant 实体扩展
6. **计量无汇总** → M4：usage_events 采集 + 租户用量报表（设计冻结，实施待解冻）
7. **平台账号体系** → M6：平台管理员表 + 角色（超管/运营/运维/审计只读）
8. **提示词无可视化编辑审核界面** → M7：在线编辑 + 审核发布流 + 门禁可视化 + 安全话术只读视图（能力已有，缺界面与流程）
9. **预警时效无平台级曝光** → M8：SLA 时效监控 + 逾期升级 + 业务级告警规则（当前 alert-rules.yml 零业务规则）+ 处置闭环统计
10. **知识库/通知/合规无管理端视图** → M9/M10/M11：审核运营/渠道台账/合规跟踪（能力已有，缺接入）
11. **无业务健康度视图** → M12：会话质量/预警漏斗/租户健康度（运营者「产品整体运行得怎么样」的答案）

---

## 四、总体架构

### 4.1 定位与拓扑

```
                         ┌──────────────────────────────────────────┐
                         │              admin-web（新增）             │
                         │   React 19 + TS + Vite（复用 teacher-web 栈）│
                         └──────┬───────────────────────────────────┘
                                │ HTTPS（宿主 nginx 443，/admin/ 路径）
┌───────────────────────────────▼───────────────────────────────────┐
│                        backend（counseling-api）                    │
│   /api/v1/platform/**（扩展）  /api/v1/admin/**（现有）  /api/v1/ops/**（新增）│
│   ── PlatformConsoleService 域（counseling-service，新增子域）       │
└───────┬──────────────┬───────────────┬──────────────┬──────────────┘
        │              │               │              │
   ┌────▼───┐    ┌─────▼────┐    ┌─────▼─────┐   ┌────▼──────┐
   │PostgreSQL│  │  Redis   │    │ tts/voice │   │Prometheus/│
   │(配置/计量/ │  │(运行时态/ │    │(Python 边车│   │Grafana/   │
   │ 订阅/审计) │  │ 降级状态) │    │ /health)  │   │AlertManager│
   └─────────┘   └──────────┘    └───────────┘   └───────────┘
```

- **前端**：admin-web 独立应用，宿主 nginx `/admin/` 路径反向代理（与三端同构部署）
- **后端**：新增 `com.mindsafe.api.controller.adminconsole.*` 控制器 + `com.mindsafe.service.platform` 域扩展（PlatformConsole 系列 Service）；运维执行类端点（服务启停/降级切换）走 `/api/v1/ops/**`
- **数据源**：业务数据（DB 直查聚合，复用 PlatformService 模式）；监控数据（后端代理 Prometheus HTTP API，**管理端不直连监控栈**，保持单一入口与鉴权）

### 4.2 技术栈

| 层 | 选型 | 理由 |
|----|------|------|
| 前端 | React 19 + TypeScript + Vite 8（与 teacher-web 同栈，复用 shared 模块与构建链） | 维护成本最低，CI 门禁/部署通道零改造 |
| UI | 与 teacher-web 一致的组件体系（ECharts 图表 + 表格/表单组件，复用 FA-03 useECharts 等既有模式） | 风格统一 |
| 设计体系 | doing/75 方案 A「青屿」：与 teacher-web/parent-h5 共用同名同值 --ms-* 设计令牌（品牌青绿 #2BA8A0/中性/语义/形态/图表九色/暗色变体），详见 §8.1~8.9 | 四端视觉统一 |
| 后端 | Spring Boot 3 + MyBatis-Plus（复用 TenantLineInnerInterceptor 行级隔离机制——**注意平台域表需排除租户拦截或单独数据源规则**） | 现状延续 |
| 鉴权 | JWT（现有）+ `hasRole("ADMIN")` + userType 细分（super_admin/ops_admin/finance_admin/audit） | 现状扩展 |

### 4.3 权限模型

| 角色（userType） | 说明 | 可访问模块 |
|-----------------|------|-----------|
| super_admin（现有） | 平台超管：全部能力 + 平台账号管理 | M1~M12 全量 |
| ops_admin（新增） | 运维角色：监控/降级切换/服务管理/业务信号处置 | M2、M3、M5/M8/M10 只读、M8 升级操作、M12 只读 |
| finance_admin（新增） | 财务角色：计量/订阅/账单 | M4 全量、M5 只读 |
| audit（新增，只读） | 审计角色：只读所有模块 + 审计日志 + 合规中心 | M1~M12 只读（含审计日志、M11 合规） |

实现：`platform_admin` 表（userType + 角色）+ SecurityConfig 新增 `hasRole("PLATFORM_*")` 或基于 userType 的授权；菜单级权限由 admin-web 前端按角色渲染 + 后端端点级强制（双重校验）。

### 4.4 模块划分总览

| 模块 | 名称 | 核心能力 | 依赖现有资产 |
|------|------|---------|-------------|
| M1 | 系统配置管理 | 配置注册表/变更留痕/生效机制 | SystemConfigProperties、config.yaml、prompt_versions |
| M2 | 系统应用监控 | 服务拓扑/健康状态/指标看板/告警中心/部署历史/使用性能 | Prometheus、service-manager.sh、AlertService、deploy-audit.sh |
| M3 | 服务切换降级监控 | 降级状态视图/手动切换/降级历史 | ResilientChatModel、TTS/ASR/SER 开关、Redis 状态键 |
| M4 | 租户计量计费 | 计量采集/用量报表/订阅套餐/账单（设计冻结） | model_call_logs、design/07 定价模型 |
| M5 | 租户管理 | 生命周期/套餐权益/配额/健康检查增强/层级钻取 | AdminTenantController、PlatformService |
| M6 | 平台基础 | 平台账号/RBAC/审计日志/操作留痕 | SecurityConfig、audit_logs |
| M7 | 提示词与内容配置中心 | 提示词在线编辑/审核发布流/灰度 A-B/护栏回归/安全话术只读 | PromptVersionService、TemplateMatrixRegistry、RedTeamRegressionRunner |
| M8 | 业务信号与预警处置监控 | 风险全景/SLA 时效监控/逾期升级/通知兜底台账/处置闭环统计/业务级告警 | RiskEvent 状态机、TeacherAlertController 链路、RiskNotifyOutboxService |
| M9 | 知识库与内容管理 | 知识文档审核流/内容质量报告 | KnowledgeBaseController |
| M10 | 通知渠道与触达管理 | 渠道状态/发送统计/失败台账/渠道配置 | NotificationService、WeComOAuthService、AliyunSmsService |
| M11 | 数据安全与合规中心 | 数据留存策略/审计全景/隐私合规清单（导出审批冻结） | design/02 留存策略、audit_logs、consent 域 |
| M12 | 运营洞察 | 会话质量/活跃情绪趋势/预警漏斗/租户健康度 | quality_scores、RiskEvent、PlatformService |

---

## 五、功能模块详细设计

### 5.1 M1 系统配置管理

**目标**：把散落配置（application.yml / 环境变量 / Python config.yaml / DB）收敛为「配置注册表 + 分类视图 + 受控修改 + 变更留痕」，不改动现有配置加载机制（保持配置源单一事实源不变，管理端只是**视图层 + 变更通道**）。

**功能清单**：

| 功能 | 说明 | 实现要点 |
|------|------|---------|
| 1.1 配置分类浏览 | 按域分组的只读视图：系统基础（服务端口/日志级别）/ 安全（声纹阈值 0.70、弱口令策略）/ 语音（ASR 引擎、SER_ENABLED、TTS 音色）/ 对话（超时、重试、降级话术）/ 预警（规则阈值、热线配置）/ 商业化（定价、权益开关） | 配置注册表 `sys_config` 维护元数据（key/域/类型/敏感级/来源/生效方式）；值实时读自现有配置源 |
| 1.2 配置修改 | 非敏感配置在线修改（如日志级别、降级超时、预警阈值） | 写 `sys_config_history`（快照旧值/新值/操作人/时间/原因）+ 推送到运行时（优先走现有一级配置源热刷新：Spring @ConfigurationProperties 刷新、环境变量不可热改的标记「重启生效」） |
| 1.3 敏感配置管控 | 密钥/证书/API Key 类**只显示掩码状态（已配置/未配置）**，不回显值 | 敏感级=SECRET 的 key 一律脱敏；修改走「平台超管 + 二次确认」 |
| 1.4 配置变更历史 | 全部变更可追溯（谁/何时/改了什么/为什么） | sys_config_history + audit_logs 双写 |
| 1.5 Prompt 配置入口 | **M7 提示词配置中心入口**：提示词完整能力（在线编辑/审核发布/灰度/护栏）已收敛为独立模块 M7，M1 仅保留菜单入口链接，不重复实现 | 指向 M7 页面；避免双模块定义同一能力 |
| 1.6 运行时配置下发 | GET /api/v1/system/config（现有公开端点）展示其包含的键，标注「前端运行时配置」 | 只读展示 + 变更提示缓存 300s |

**关键设计决策**：
- **不引入新配置源**：sys_config 只是「注册表 + 视图 + 变更通道」，真实值仍在原配置源（环境变量优先原则不变）——避免双事实源（对齐 DOC-083 DA-14 config.yaml 兜底诚实化原则）
- 生效方式两级：`HOT`（可热刷，走 RefreshScope/发布事件）/ `RESTART`（需重启服务，修改后给出指引并可触发「服务重启入口」（M2 联动））

### 5.2 M2 系统应用监控

**目标**：把 Prometheus/Grafana/service-manager 的能力以**运营者视图**呈现在管理端，并提供告警事件中心与部署历史。

> **衔接**：本模块消费监控链路数据（Prometheus 指标 + AlertManager 告警 + alert_events），**监控链路实现统一归口 `design/doing/83_服务降级监控与告警设计.md`**（OPS-MON-001~008：指标埋点/告警规则/采集落库/部署演练）；本模块只做展示与操作。

**功能清单**：

| 功能 | 说明 | 实现要点 |
|------|------|---------|
| 2.1 服务拓扑与健康状态 | 六服务（postgres/redis/tts/voice/backend/nginx）卡片式拓扑：运行状态 + 健康状态（复用 service-manager check_health 语义：UP/DEGRADED/DOWN 三级）+ 依赖关系图（postgres/redis → tts/voice → backend → nginx） | 后端定时（如 30s）探测（Java 侧实现同语义探活，或读 service-manager 输出）；快照落 `service_health_snapshots` 供历史曲线 |
| 2.2 关键指标看板 | 平台级指标：LLM 首 token 延迟 P50/P90/P99、重试/超时/降级次数、模型回退（from/to）、TTS 合成耗时/错误、语音分析请求量/ASR-SER 就绪态、后端 JVM/HTTP 指标 | 后端代理 Prometheus HTTP API（/api/v1/ops/metrics/query），前端 ECharts 渲染；指标清单映射 design/03 §8 表 + 降级监控文档 §3.1（`tts_degraded_events_total` / `mindsafe_llm_model_fallback_total` 等降级指标） |
| 2.3 告警事件中心 | 告警列表（规则名/级别/状态/时间/详情）、确认（ack）、关闭；聚合 AlertManager 事件 + 系统内 AlertService 告警（企微已发，此处留痕） | 读 AlertManager API（含降级监控文档 §3.2 新增 3 条规则）+ AlertService；`alert_events` 表落历史（**采集器归口降级监控文档 OPS-MON-008**，AlertManager 数据默认 120h 保留，历史需落库） |
| 2.4 部署历史 | deploy-audit.sh 审计报告列表（组件/耗时/信号 OK/WARN/CRITICAL/回归规则命中） | 后端读 logs/deploy/audit-*.md 解析展示（或 service 定时扫描落库） |
| 2.5 服务操作 | 服务启停/重启入口（复用 service-manager.sh 语义），操作二次确认 + 审计 | `/api/v1/ops/services/{name}/{action}`；执行方式：后端调用服务器端脚本（经 SSH 受限通道或管理端直连探测；单机部署可后端进程内执行 shell——**需安全评估，见 §10**） |
| 2.6 租户活跃监控 | 平台→租户→学校层级活跃度钻取（会话量/活跃学生/预警未处理） | 复用 PlatformService.overview/tenantStats/tenantDetail + 扩展时间维度查询 |

**关键设计决策**：
- **监控数据展示走后端代理**：管理端不直连 Prometheus（避免暴露 9090、统一鉴权、统一 CORS）
- 服务健康**快照落库**（service_health_snapshots）：支持历史健康曲线与 SLA 统计（对齐 design/07 SLA 99.5/99.7/99.9 承诺的可验证性）

### 5.3 M3 服务切换降级监控

**目标**：把现有完备但「看不见、摸不着」的降级机制变为**可视化 + 可手动干预 + 可追溯**。

> **衔接**：本模块负责降级点的**展示与操作**（矩阵视图/手动切换/影响面提示）；自动降级的**检测与事件落库**（指标埋点 + 降级事件检测器）归口 `design/doing/83_服务降级监控与告警设计.md`（OPS-MON-002/007），本模块消费其产出（指标 + degradation_events）并补充 manual 事件。

**现状降级机制盘点（全部已实现，本模块只加管理与视图）**：

| 降级点 | 机制 | 状态来源 | 手动切换可行性 |
|--------|------|---------|--------------|
| LLM 主备 | ResilientChatModel：DeepSeek V4（主）→ qwen-plus（备），失败自动回退 | `mindsafe_llm_model_fallback_total` 指标 + 运行时状态键 | ✅ 可切换（改配置 + 热刷） |
| LLM 超时/重试 | LlmStreamEnhancer：超时/重试/兜底话术 | `mindsafe_llm_timeout_total` / `retry_total` / `fallback_total` | ✅ 参数可调（M1 配置） |
| TTS 三级 | CosyVoice（云）→ edge-tts（本地）→ 浏览器 speechSynthesis | tts /health engine 字段 + `tts_engine_available` 指标 | ✅ 可手动指定引擎（tts-service 配置/接口） |
| ASR 双引擎 | funasr（本地）→ dashscope（云），ASR_ENGINE 环境变量切换 | voice /health status + `voice_asr_ready` | ✅ 可切换（环境变量 + 重启，RESTART 级） |
| SER 情绪识别 | SER_ENABLED 独立开关（DA-02 启用与就绪解耦） | `voice_ser_enabled` / `voice_ser_ready` | ✅ 可开关（环境变量 + 重启） |
| 语音降级策略 | VoiceDegradationPolicy：S0 静默 / S1 预合成 / S2 强制安抚 | 配置 + 运行时 | ✅ 策略档位可调（M1 配置） |
| 唤醒词 | whisper ONNX WASM 纯前端本地推理（COOP/COEP 依赖） | 前端侧 | 前端能力，管理端仅展示配置状态 |

**功能清单**：

| 功能 | 说明 | 实现要点 |
|------|------|---------|
| 3.1 降级状态实时视图 | 「能力降级矩阵」：每个降级点显示当前档位（主/备、引擎名、开关态、就绪态），异常态高亮 | 后端聚合：读 tts/voice /health（后端代理）+ Redis 运行时状态键 + Prometheus 指标即时查询（降级指标见降级监控文档 §3.1） |
| 3.2 手动切换入口 | 运维角色对可切换降级点执行手动切换（如 TTS 强制 edge-tts、LLM 强制 qwen-plus、SER 关闭、ASR 引擎切换） | `/api/v1/ops/degradation/{point}`：写 Redis 运行时开关/覆盖配置 → 即时生效（优先运行时键，不动部署文件）；操作二次确认 + 审计；**同步写 degradation_events（trigger_type=manual）** |
| 3.3 降级事件历史 | 每次降级发生/恢复/手动切换记录事件（时间/点/从→到/触发方式 auto|manual/操作人） | `degradation_events` 表：**auto 事件由监控侧检测器落库（降级监控文档 OPS-MON-007），manual 事件由手动切换 API 直接写库**；列表查询按时间倒序 + 过滤 |
| 3.4 自动降级通知联动 | 自动降级发生时（如 tts engine 变化）除企微告警外，管理端事件流可见 | 企微告警由降级监控文档 §3.2 规则触发（OPS-MON-003）；管理端经 alert_events 消费展示（采集器 OPS-MON-008） |
| 3.5 降级影响面提示 | 切换前提示影响：如「强制 edge-tts：音质降级、响应变慢；强制 qwen-plus：能力差异 X」 | 静态影响说明表（配置在 sys_config 或代码常量） |

**关键设计决策**：
- **运行时覆盖优先**：手动切换写 Redis 运行时键（覆盖配置），服务重启后回落配置默认值——避免改坏部署文件（与「部署文件最小变更」原则一致）
- **降级 ≠ 宕机**：语义沿用 service-manager D5/DA-02（DEGRADED 仍健康），管理端 UI 用「黄色降级」而非「红色故障」表达
- 手动切换仅限运维角色（ops_admin/super_admin），财务/审计只读

### 5.4 M4 租户计量计费（设计冻结，实施待 frozen/38 解冻）

**目标**：完整设计计量（usage_events）→ 计价（rate）→ 计费（订阅/账单）三层，**本方案定稿设计，实施时点与 frozen/38（BILL-002/003）解冻议决挂钩**。设计基线对齐 design/07：定价 99/159/259 元/生/年、entitlement 三层（基础=筛查+报告 / 专业=+AI+预警+家校 / 旗舰=+区域看板+API）、计量维度（活跃学生数 + LLM 调用量）。

**计量事件模型**（腾讯云 TStack 计价计费分离模式）：

| 层 | 表/机制 | 说明 |
|----|--------|------|
| 计量采集 | `usage_events`（新增） | 原始事件：租户/事件类型（active_student_snapshot 日快照、llm_call、tts_call、asr_call）/数量/token 数/时间；**异步写入不阻塞业务**（复用 outbox 或 MQ 思路，可先同步写 + 批量刷） |
| 计量维度 | 活跃学生（近 30 天有会话学生数，日快照）、LLM 调用量（token）、TTS/ASR 调用量 | 活跃学生数：`usage_events` 每日定时任务生成快照（复用 CounselingSession 统计）；LLM：model_call_logs 已有，**聚合即可**（按租户/session 关联补 tenant_id——需核对 model_call_logs 是否有 tenant_id 列，无则补列） |
| 计价规则 | `rate_plans`（新增） | 套餐单价（99/159/259 元/生/年）+ 超量单价（LLM 超额 token 单价）——**价格参数化不硬编码** |
| 订阅 | `subscriptions`（新增） | 租户 × 套餐 × 生效期/到期日 × 状态（active/expired/canceled/trial）；续费规则（60 天前 9 折、连 3 年 8.5 折，design/07 §2.6） |
| 账单 | `billing_cycles` + `billing_lines`（新增） | 周期账单：应计费学生数 × 单价 + 超量费用；状态（draft/issued/paid/overdue）；发票/收款**留扩展字段**（收款渠道对接为远期） |

**功能清单**：

| 功能 | 说明 | 实现要点 |
|------|------|---------|
| 4.1 租户用量报表 | 每租户：活跃学生数（日/月曲线）、LLM token 用量（日/月）、TTS/ASR 调用量、对比套餐配额 | usage_events + model_call_logs 聚合；报表查询只读 |
| 4.2 平台成本总览 | 全平台 LLM 成本（model_call_logs.cost_amount 聚合）、单生成本（对齐 design/07 §2.8「LLM 成本 15-20 元/生/年」验证） | 聚合查询 |
| 4.3 订阅管理 | 套餐定义维护（rate_plans CRUD）、租户订阅开通/升降级/续费/到期提醒（到期前 30/7/1 天生成提醒事件 → AlertService） | subscriptions + 定时任务 |
| 4.4 配额联动 | 套餐配额（学生数上限/日会话上限）与 TenantProvisioningService 配额（当前 DEFAULT_MAX_STUDENTS=500 常量**未接线**）接通：配额从套餐读、超限拦截（403 配额错误码） | 需实现配额校验（当前缺口），属 M5 配额管理联动 |
| 4.5 账单管理 | 账单生成（周期任务）、查看、状态流转；导出 CSV | billing_cycles/billing_lines |
| 4.6 权益开关 | entitlement 三层模块开关落租户配置（design/07 §2.8「不落代码分支」）——**EntitlementFilter 已冻结 frozen/38**，本模块只设计不实施 | 设计：tenants 表加 plan_code/entitlement jsonb 列或独立表；实施待解冻议决 |

**冻结边界声明**：M4 的 4.3~4.6 属 BILL-002/003（P2 商业化）范围，**本方案完成设计定稿，不实施**；4.1~4.2（用量报表）依赖 usage_events 采集，**已议决先行落地采集层（2026-08-09，DEC-007）：属计量而非计费，先行采集 + 报表，frozen/38 登记同步更新**。

### 5.5 M5 租户管理

**目标**：在 AdminTenantController 现有能力上补齐套餐/权益/配额/到期/层级钻取，形成完整的租户生命周期管理。

**租户生命周期**：

```
开通（provision）→ 正常（active）→ 暂停（suspended，数据保留禁登录）→ 恢复（resume）
                ↘ 套餐到期（expired，降级为只读或冻结，策略配置化）
                ↘ 归档（archived，远期，数据保留）
```

**功能清单**：

| 功能 | 说明 | 实现要点 |
|------|------|---------|
| 5.1 租户列表与搜索 | 现有 list 增强：状态筛选/关键字搜索/套餐显示/到期日显示/用量概要 | 扩展 AdminTenantController.list 或新增 PlatformConsole 查询 |
| 5.2 一键开通 | 现有 provision 复用 + 扩展套餐/配额/试用期字段 | TenantProvisioningService.provisionTenant 扩展参数（向后兼容） |
| 5.3 暂停/恢复/归档 | 现有 suspend/resume 复用；新增归档（归档=软删 deleted_at + 数据保留） | 扩展 |
| 5.4 租户健康检查 | 现有 healthCheck 复用（admin/school/student 计数 + healthy 判定），管理端展示 + 历史 | 复用 + 快照 |
| 5.5 租户详情钻取 | 现有 tenantDetail（学校列表 + 7 天会话趋势）扩展：学生/教师列表、预警分布（红橙黄绿）、用量概要、订阅信息、操作历史 | PlatformService 扩展 |
| 5.6 配额管理 | 学生数上限/日会话上限（当前常量 500/200 未接线）→ 套餐配额或租户级覆盖；超限拦截 | **需新建配额校验**（会话创建/学生创建处拦截，403 QUOTA_EXCEEDED） |
| 5.7 数据留存查看 | 合同期/冻结/匿名化策略（design/02 数据留存）在租户详情展示当前租户数据状态 | 只读展示 |

### 5.6 M6 平台基础

| 功能 | 说明 | 实现要点 |
|------|------|---------|
| 6.1 平台账号管理 | 平台管理员账号 CRUD（独立 `platform_admin` 表，与租户 users 表解耦——平台账号不属于任何租户） | **已议决（2026-08-09，DEC-007）：独立 platform_admin 表 + 独立登录端点**（登录复用现有 JWT 体系或独立端点，见 R-1/R-8） |
| 6.2 角色权限 | 四角色（super_admin/ops_admin/finance_admin/audit）端点级授权 | SecurityConfig 扩展 hasRole / hasAuthority；菜单由前端按角色渲染 |
| 6.3 审计日志 | 平台级审计：现有 audit_logs（租户维度）扩展平台操作（配置变更/降级切换/服务操作/订阅变更） | AuditLogService 复用（tenantId 允许 null 表示平台级） |
| 6.4 登录安全 | 管理端登录：密码策略（复用弱口令 fail-fast 既有机制）、登录失败锁定、二次确认机制 | 复用现有认证 |

### 5.7 M7 提示词与内容配置中心（用户核心需求）

**目标**：把提示词「定义/更新/审核/发布/灰度」全流程收敛到管理端，让非开发人员可直接在后台更新与定义提示词；同时把预审核安全话术纳入受控视图。现有能力（PromptVersionService/TemplateMatrixRegistry）已支撑大部分，本模块补**可视化编辑 + 审核流 + 门禁可视化**。

**现有提示词体系实态**（复用基础）：

| 能力 | 实态 | 本模块用途 |
|------|------|-----------|
| 模板加载 | DB 优先（租户级 → 全局级）→ classpath 降级（KEY_TO_CLASSPATH 映射） | 编辑保存写入 DB，classpath 为兜底不回写 |
| 版本激活门禁 | activateVersion：reviewer 必填 + RedTeamRegressionRunner 红队静态回归 + eval 分数回退检查（GateResult） | 发布动作直接复用，前端展示门禁明细 |
| A/B 分组 | createVersion 支持 abGroup（control/treatment）；versionTag 供会话记录 | 实验管理页 |
| 灰度评估 | rollout-eval（PEVAL-004）：stageIndex/safetyMean/blockRate/evalDelta 五档决策 | 灰度状态可视化 |
| 模板矩阵 | TemplateMatrixRegistry：TemplateEntry（templateId/version/audience/changelog） | 模板总览页 |
| 红队护栏 | GuardrailCase（self_harm/violence/sexual/pii/jailbreak 五类） | 护栏用例库页 + 回归执行 |

**功能清单**：

| 功能 | 说明 | 实现要点 |
|------|------|---------|
| 7.1 模板矩阵总览 | 全部模板（模板 ID × 版本 × 适用人群 × 状态 × 变更日志）一览，区分系统模板/租户模板 | 接入 /api/v1/admin/prompts/matrix；租户级覆盖标记 |
| 7.2 提示词在线编辑 | 选中模板 → 在线编辑（支持变量占位符校验/字数统计/基础语法检查）→ 保存为草稿（新版本） | 复用 createVersion（abGroup 默认 control）；新增草稿态（不激活） |
| 7.3 审核与发布流 | 状态机：draft → pending_review → approved → active；提交审核（填变更说明）→ 审核人审阅（reviewer 必填）→ 红队回归门禁 → 激活 | **prompt_versions 加 status 字段**（兼容现有 isActive）；激活仍走 activateVersion 门禁（reviewer/红队/eval 三重） |
| 7.4 发布门禁明细可视化 | 门禁失败原因逐条展示（红队用例失败清单/分数回退值/缺失审校人） | 读 GateResult.failures；失败不可激活 |
| 7.5 A/B 实验管理 | 同模板 control/treatment 双版本对比、灰度阶段推进（rollout-eval 决策展示） | 复用 ab-comparison + rollout-eval |
| 7.6 护栏用例库与回归 | 五类护栏用例查看、按需触发全量红队回归、回归结果报告 | 复用 guardrails 端点 + RedTeamRegressionRunner；回归耗时较长需异步任务 + 结果通知（AlertService INFO） |
| 7.7 安全话术只读视图 | RecallPhrases（召回话术）/CrisisResources（RED 安全回复/热线）/fallback（兜底话术）/VoiceDegradationPolicy 话术——**预审核合规内容，只读展示**，变更走发布评审（代码级，对齐 DOC-080 R-7 议决：维持代码内维护） | 后端只读接口汇总常量内容；页面标注「预审核内容，变更需发布评审」 |
| 7.8 模板影响面 | 每个模板/版本被引用情况：会话量（counseling_sessions.prompt_version）、A/B 效果摘要 | 聚合查询 |
| 7.9 变更审计 | 全部创建/审核/激活/停用留痕（audit_logs + PROMPT_ACTIVATE 既有） | 复用 |

**关键设计决策**：
- **安全话术与提示词分离管控**：可在线编辑的是「模板类提示词」（对话/情绪/报告等 Prompt 体系）；涉及儿童安全红线的预审核话术（热线/召回/安全回复）**维持代码级预审核**（R-7 议决），管理端只读——防止运行时改话术绕过安全合规审核
- **审核人必填不可缺省**：激活门禁沿用现有 reviewer 必填逻辑，管理端强制选择审核人
- **发布即灰度可选**：高风险模板（safety 域）建议先走灰度（rollout-eval 五档）再全量；管理端对 safety/emotion 域模板标红提示

### 5.8 M8 业务信号与预警处置监控（用户核心需求）

**目标**：平台层对「风险信号从产生到处置闭环」全链路做监控与管理——老师是否及时干预、信号是否超时、通知是否送达、处置是否闭环。现有 RiskEvent 状态机与 TeacherAlertController 处置链路已完整，**缺平台级聚合视图、时效监控与逾期升级机制**。

**信号链路与 SLA 基线**（design/09 §5.3 + design/11 §4.1）：

```
检出(risk_events.detectedAt) → 通知教师(notifyStatus) → 认领(claimed) → 处置(resolved) → 回访(follow_up) → 闭环(closed)
      ↑ 告警 1min(R4)/SLA 确认 5min 处置 15min(S0) / S2 1 工作日 / S3 3 工作日
```

**功能清单**：

| 功能 | 说明 | 实现要点 |
|------|------|---------|
| 8.1 跨租户风险全景 | 红橙黄绿分布、今日新增/未处置数、近 7 天趋势、按租户/学校下钻 | RiskEvent 聚合（platform 域扩展查询，riskLevel 1-4 映射红橙黄绿） |
| 8.2 预警处置时效监控 | SLA 计时与达标率：检出→通知耗时、通知→认领耗时、认领→处置耗时；按等级/租户聚合（达标率/逾期率/P95 处理时长） | 纯查询聚合（detectedAt/assignedAt/status 时间戳）；**无新增表** |
| 8.3 逾期预警管理与升级 | 定时扫描（如 5 分钟）未按 SLA 处置的预警（S0 超 5min 未认领/15min 未处置等）→ 逾期清单 + 升级通知（AlertService CRITICAL/WARNING → 企微）→ 平台超管可介入转派/联系学校 | **复用已实现 SlaEscalationScanner**（P-05/WB-001：RED 5min/ORANGE 15min → ESCALATE CRITICAL、claimed 超时/YELLOW → REMIND WARNING、冷却去重，含 SlaEscalationScannerTest）——本次仅扩展：①升级动作补 `sla_escalation_log` 留痕（扫描器现只告警不落库）②平台级逾期清单查询 + 转派/强制关闭端点 ③业务指标埋点（8.6） |
| 8.4 通知兜底台账 | notifyStatus=dead 的预警清单（通知超 5 次失败转人工）：人工核对/补发/关闭处置 | RiskNotifyOutboxService 既有状态机 + 管理端台账页（写操作需二次确认） |
| 8.5 处置闭环统计 | 认领率/闭环率/误报率（false-positive）/回访完成率（followUpDone）/处置结果分布（outcome）按月聚合 | RiskEvent 聚合 |
| 8.6 业务级告警规则 | **补齐 alert-rules.yml 业务段**（当前 10 条全为基础设施级）：预警逾期（S0 超 5min 未认领）、dead 堆积（≥N）、单租户预警激增（较基线 ≥3 倍）、认领率骤降 | 规则入 Prometheus 需业务指标——**新增 `mindsafe_risk_events_overdue_total` 等业务 gauge/counter 埋点**（Micrometer 扩展，8.3 扫描任务产出） |
| 8.7 危机事件追踪 | RED（R4）级事件全量追踪清单：从检出→处置→回访全时间线、是否 24h 内初步响应（design/09 效果评估口径） | RiskEvent 高风险过滤 + 时间线聚合 |
| 8.8 信号来源分析 | 检出方式分布（keyword_agent 硬规则/语义分类/输出审查/语音情感/普测）、风险类型分布 | RiskEvent.detectedBy/riskType 聚合 |

**关键设计决策**：
- **业务指标进 Prometheus**：业务级告警需要指标源——8.3 定时扫描任务同时产出 `mindsafe_risk_*` 系列 gauge（逾期数/处理时长/认领率），Prometheus 规则直接消费，AlertManager 企微通道复用（不改告警链路，只加规则）
- **SLA 可配置**：各等级 SLA 阈值（5min/15min/1 工作日/3 工作日）入 sys_config（M1 注册表），扫描任务读配置——学校可谈差异化 SLA（对齐 design/07 SLA 分级承诺）
- **平台介入权**：逾期预警平台超管可转派/强制升级（写 RiskEvent.assignedUserId + 升级日志），不直接替老师处置（处置是学校职责，平台监督与协助）
- **数据脱敏**：全景/清单页默认脱敏（学生昵称打码），明细查看需 audit 及以上角色且留痕（心理数据 S0-S4 分级沿用）

### 5.9 M9 知识库与内容管理（发散补充）

**目标**：心理知识库从「API 级管理」升级为管理端可视化运营——文档上传/审核/发布/失效全流程收敛到后台，与 M7 提示词配置中心形成「内容双轨」（提示词管对话行为，知识库管回答素材），并复用 KnowledgeBaseController 现有审核流（documents 上传 → review 审核 → editorial 编辑 → report 质量报告）。

**功能清单**：

| 功能 | 说明 | 实现要点 |
|------|------|---------|
| 9.1 文档库总览 | 全部知识文档列表：标题/分类/来源/状态（pending→approved→published）/版本/上传人 | 扩展 GET /documents（分页 + 状态筛选） |
| 9.2 上传与版本管理 | 管理端上传新文档、覆盖旧版本（新版本号，保留历史） | 复用 POST /documents + POST /corpus；版本历史展示 |
| 9.3 审核流 | 待审列表 → 审阅 → 通过/驳回（驳回填理由回传作者） | 复用 PUT /documents/{docId}/review；管理端待审角标 |
| 9.4 内容质量报告 | editorial/report 质量报告：语义重复/过时/与风险规则库冲突检测结果 | 复用 GET /editorial/report + POST /editorial；定时生成 |
| 9.5 发布与失效 | approved 文档发布（published）、下架（软删保留历史） | 扩展 DELETE /documents/{docId} 语义（软删）；发布状态流转 |
| 9.6 知识命中统计 | Top 检索命中文档（近 30 天）、未命中提问 Top（知识缺口信号） | 检索日志落库/聚合（现有未记检索日志则补埋点） |

**关键设计决策**：
- **与 M7 明确分工**：M7 管提示词（对话行为模板），M9 管知识库（回答内容素材），同一发布评审原则（高风险内容不可绕过审核直接生效）
- **软删不物理删**：心理知识文档涉及可追溯性要求（合规），下架仅置失效态
- 9.6 检索日志若无现成埋点，P2 落地时补（低成本：search 接口异步写表）

### 5.10 M10 通知渠道与触达管理（发散补充）

**目标**：平台多渠道（站内/企微/短信）通知的运营视图——渠道健康、发送量/送达率、失败台账、渠道配置与触达策略。素材：NotificationService（三渠道分发）、RiskNotifyOutboxService（outbox 状态机 + dead 转人工）、sms/wecom 服务目录。

**功能清单**：

| 功能 | 说明 | 实现要点 |
|------|------|---------|
| 10.1 渠道状态总览 | 企微应用连通性（access_token 是否有效/过期）、短信服务商可用性、站内信正常性 | 定时探测（企微 token 校验/短信商 health 接口）；读 wecom/sms 服务状态 |
| 10.2 发送统计 | 按渠道/通知类型（风险预警/普测报告/系统公告）/租户统计：发送量、失败量、送达率（近 7/30 天） | outbox 表聚合（status 分布）；无新增表 |
| 10.3 失败台账 | outbox status=failed/dead 明细：重试次数、失败原因、人工重发/关闭 | 复用 RiskNotifyOutboxService 状态机 + 台账页（与 M8.4 联动，此处为通用通知视角） |
| 10.4 渠道配置 | 企微 webhook/agent 配置、短信签名/模板——**敏感项走 M1 SECRET 机制**（只显示已配置/未配置） | sys_config 注册（sensitive=SECRET） |
| 10.5 触达策略配置 | 各通知场景渠道优先级（如风险预警=站内+企微+短信三级递增；普测报告=站内+短信） | sys_config（json 类型）；分发逻辑当前硬编码优先级，P2 改造为配置驱动 |

**关键设计决策**：
- **渠道配置与触达策略分离**：渠道配置是「连接凭据」（SECRET），触达策略是「业务规则」（可编辑）——两类敏感度不同，分开管控
- 触达策略改造需动 NotificationService 分发逻辑（当前硬编码），列 P2 明确改造点并加回归测试（对齐 M8 预警通知链路不可中断原则）

### 5.11 M11 数据安全与合规中心（发散补充）

**目标**：平台级合规运营视图——数据留存状态、告知同意覆盖、审计全景、等保与发布合规跟踪；导出审批设计预留（实施冻结待议决）。素材：ConsentRecord、audit_logs、design/02 留存策略、design/22 告知同意、design/31 等保二级、design/32 发布前置待办。

**功能清单**：

| 功能 | 说明 | 实现要点 |
|------|------|---------|
| 11.1 数据留存总览 | 各租户留存策略执行状态：合同期/冻结期/匿名化，到期清理任务执行状态 | 读留存策略实现（现有清理任务）+ 租户维度汇总 |
| 11.2 告知同意覆盖 | ConsentRecord 统计：同意/拒绝率、版本分布、待更新名单（新版同意书未重新确认） | ConsentRecord 聚合 |
| 11.3 审计全景 | 跨租户审计日志检索（平台级 + 各租户操作），按操作人/动作/时间过滤 | audit_logs 扩展（tenantId 可空）+ 检索端点 |
| 11.4 合规清单跟踪 | 等保二级差距项（design/31）、商用发布前置待办（design/32）维护与状态跟踪 | 文档驱动的清单页（静态维护，入 sys_config json） |
| 11.5 数据导出审批（设计预留，冻结） | 个人数据导出请求 → 审批流 → 导出留痕；涉及 S0-S4 心理数据分级管控 | **设计定稿不实施**：导出请求表 + 审批状态机设计预留，实施待议决（心理数据导出是高敏操作） |

**关键设计决策**：
- **合规视图以「可追溯」为第一目标**：所有合规项可查「当前状态 + 最近变更 + 责任角色」，不追求实时
- 11.5 明确冻结：心理数据导出属高敏操作，须先走合规议决（对齐 design/31 等保差距与 design/32 发布待办）

### 5.12 M12 运营洞察（发散补充）

**目标**：从「监控指标」上升到「业务健康度」——会话质量、情绪趋势、预警漏斗、租户健康度评分，支撑平台运营者回答「产品整体运行得怎么样」。素材：QualityScore、RiskEvent、CounselingSession、PlatformService。

**功能清单**：

| 功能 | 说明 | 实现要点 |
|------|------|---------|
| 12.1 会话质量看板 | QualityScore 聚合：平均分趋势、低质量会话占比（按租户/周）、质量分分布 | QualityScore 聚合查询 |
| 12.2 预警漏斗 | 检出→通知→认领→处置→闭环各环节转化率（全平台周趋势），与 M8 互补（M8 看单事件时效，M12 看整体漏斗） | RiskEvent 状态分布聚合；复用 M8 埋点指标 |
| 12.3 情绪趋势 | 对话情绪分布周趋势（脱敏聚合：只出分布不出个体） | 会话情绪字段聚合（若存于会话快照则直查，否则补埋点） |
| 12.4 租户健康度 | 综合评分：服务可用性 × 预警处置达标率 × 会话质量 → 红黄绿分级租户列表 | 评分规则入 sys_config（权重可调）；三源聚合 |
| 12.5 效果指标报告 | design/09 效果评估口径：危机事件 24h 响应率、家长知情率、干预效果反馈率（定期报表） | RiskEvent 时间线聚合 + 回访数据 |

**关键设计决策**：
- **聚合只出分布不出个体**：情绪/质量类洞察一律脱敏聚合（对齐心理数据 S0-S4 分级与 design/29 画像隐私约束）
- 租户健康度评分是「运营建议性指标」非「考核性指标」：红黄绿仅用于平台主动介入排序，不对外部学校展示原始分数

---

## 六、数据库设计（新增表）

> 全部新增表挂 `public` schema，**平台级表不参与租户行级隔离**（TenantLineHandler 需配置忽略表名单，与现有 tenants/schools 处理一致）；与 design/02 表设计风格一致。

### 6.1 sys_config（配置注册表）

| 字段 | 类型 | 说明 |
|------|------|------|
| config_id | uuid PK | |
| config_key | varchar(128) UNIQUE | 配置键（如 `mindsafe.safety.voiceprint-threshold`） |
| domain | varchar(32) | 配置域（system/security/voice/chat/alert/commercial） |
| value_type | varchar(16) | string/number/bool/json |
| sensitive | varchar(8) | NORMAL/SECRET（SECRET 不回显值） |
| effect_mode | varchar(8) | HOT/RESTART |
| source | varchar(32) | application.yml/env/python-config/db |
| description | varchar(512) | 说明 |
| updated_at / updated_by | | 最近变更信息 |

### 6.2 sys_config_history（配置变更历史）

| 字段 | 类型 | 说明 |
|------|------|------|
| history_id | uuid PK | |
| config_key | varchar(128) | |
| old_value / new_value | text | 快照（SECRET 存掩码标记） |
| operator | varchar(64) | 操作人 |
| reason | varchar(512) | 变更原因（必填） |
| changed_at | timestamptz | |

### 6.3 service_health_snapshots（服务健康快照）

| 字段 | 类型 | 说明 |
|------|------|------|
| snapshot_id | bigserial PK | |
| service | varchar(16) | postgres/redis/tts/voice/backend/nginx |
| status | varchar(16) | UP/DEGRADED/DOWN |
| detail | jsonb | 引擎/就绪态等附加信息（tts engine、voice asr/ser） |
| sampled_at | timestamptz | 采样时间（30s 粒度，保留 30 天，超期清理） |

### 6.4 alert_events（告警事件历史）

| 字段 | 类型 | 说明 |
|------|------|------|
| event_id | uuid PK | |
| source | varchar(16) | alertmanager/alertservice |
| rule_name / severity | | 规则名/级别（CRITICAL/WARNING/INFO） |
| status | varchar(16) | firing/resolved/ack/closed |
| summary / detail | text | 摘要与详情 |
| acknowledged_by / acknowledged_at | | 确认信息 |
| fired_at / resolved_at | timestamptz | |

### 6.5 degradation_events（降级事件历史）

| 字段 | 类型 | 说明 |
|------|------|------|
| event_id | uuid PK | |
| point | varchar(32) | llm/tts/asr/ser/voice-policy/wake-word |
| from_state / to_state | varchar(64) | 切换前/后档位 |
| trigger_type | varchar(8) | auto/manual |
| operator | varchar(64) | 手动时操作人 |
| detail | varchar(512) | 原因/影响 |
| occurred_at | timestamptz | |

### 6.6 usage_events（计量事件，M4 采集层）

| 字段 | 类型 | 说明 |
|------|------|------|
| event_id | uuid PK | |
| tenant_id | uuid | 租户（可空=平台级） |
| metric | varchar(32) | active_student_snapshot/llm_call/tts_call/asr_call |
| value | numeric | 数量（token 数/调用次数/学生数） |
| unit | varchar(16) | count/token/seconds |
| event_time | timestamptz | |
| ref_id | uuid | 关联（session_id/call_id，可空） |

### 6.7 rate_plans / subscriptions / billing_cycles / billing_lines（M4 计价计费层，设计冻结）

| 表 | 核心字段 | 说明 |
|----|---------|------|
| rate_plans | plan_code/plan_name/price_per_student/price_unit/overage_rate/features(jsonb)/status | 套餐定义（99/159/259 参数化） |
| subscriptions | subscription_id/tenant_id/plan_code/start_date/end_date/status/auto_renew/discount | 租户订阅 |
| billing_cycles | cycle_id/tenant_id/subscription_id/period_start/period_end/student_count/llm_cost/base_amount/overage_amount/total_amount/status | 周期账单 |
| billing_lines | line_id/cycle_id/metric/quantity/unit_price/amount | 账单明细 |
| invoice_ref | invoice_no/payment_status | 发票收款扩展字段（远期） |

### 6.8 platform_admin（平台账号，M6）

| 字段 | 类型 | 说明 |
|------|------|------|
| admin_id | uuid PK | |
| username / password_hash | | 登录凭证（BCrypt） |
| role | varchar(16) | super_admin/ops_admin/finance_admin/audit |
| display_name | varchar(64) | |
| status / created_at / last_login_at | | |

> ✅ 已议决（2026-08-09，DEC-007）：独立 platform_admin 表 + 独立登录端点（§12 R-1 方案 A），后续可平滑迁移。

### 6.9 sla_escalation_log（M8 逾期升级留痕）

| 字段 | 类型 | 说明 |
|------|------|------|
| escalation_id | uuid PK | |
| risk_event_id | uuid | 关联预警（索引） |
| stage | varchar(16) | 超时阶段：ack（认领）/handle（处置）/follow_up（回访） |
| expected_at | timestamptz | SLA 应完成时间点 |
| escalated_at | timestamptz | 实际升级时间 |
| action | varchar(32) | notify_escalate（通知升级）/transfer（转派）/force_close（强制关闭） |
| operator | varchar(64) | 平台操作人（可空=自动升级） |
| detail | varchar(512) | 升级说明/处置意见 |

### 6.10 prompt_versions.status 扩展（M7，改现有表）

| 变更 | 说明 |
|------|------|
| 新增列 status | varchar(16)：draft / pending_review / approved / active / retired |
| 兼容策略 | 保留现有 is_active；激活时两者同步（is_active=true ⇔ status=active）；历史数据回填：is_active=true→active，其余→approved |
| 新增列 reviewer / review_comment | 审核人/审核意见落库（现有 activateVersion 的 reviewer 参数当前仅入参未落库） |

> M9/M10/M11/M12 不新增平台级表：知识库复用 knowledge 域现有表（review 状态已有）、通知统计复用 outbox 表聚合、合规与洞察复用 ConsentRecord/audit_logs/QualityScore/RiskEvent 聚合——**保持最小表增量（KISS）**，仅 M8 升级留痕（6.9）与 M7 状态字段（6.10）为必要变更。

---

## 七、API 设计

> 前缀约定：`/api/v1/platform/**`（现有，扩展）、`/api/v1/ops/**`（新增运维执行）、`/api/v1/admin/**`（现有，管理业务）；全部 `hasRole("ADMIN")` 或细化角色；响应统一 ApiResponse。

### 7.1 M1 配置管理

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | /api/v1/platform/config/registry | 配置注册表（分域） | ADMIN（audit 可读） |
| GET | /api/v1/platform/config/{key} | 单配置详情（SECRET 掩码） | ADMIN |
| POST | /api/v1/platform/config/{key} | 修改配置（body：value/reason 必填） | super_admin |
| GET | /api/v1/platform/config/{key}/history | 变更历史 | ADMIN（audit 可读） |

### 7.2 M2 应用监控

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | /api/v1/ops/services/status | 六服务实时健康（含 DEGRADED 语义） | ops_admin/super_admin/audit |
| GET | /api/v1/ops/services/health-history?service=&range= | 健康快照历史 | 同上 |
| GET | /api/v1/ops/metrics/query?expr=&range= | Prometheus 指标代理查询（白名单表达式） | 同上 |
| GET | /api/v1/ops/alerts | 告警事件列表（聚合 AlertManager + 本地） | 同上 |
| POST | /api/v1/ops/alerts/{id}/ack | 确认告警 | ops_admin |
| GET | /api/v1/ops/deployments | 部署历史（audit-*.md 解析） | 同上 |
| POST | /api/v1/ops/services/{name}/{action} | 服务启停/重启（start/stop/restart） | ops_admin（二次确认头 X-Confirm） |

### 7.3 M3 降级监控

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | /api/v1/ops/degradation/matrix | 降级矩阵实时状态 | ops_admin/super_admin/audit |
| POST | /api/v1/ops/degradation/{point}/override | 手动切换（body：to/ reason 必填） | ops_admin（二次确认） |
| DELETE | /api/v1/ops/degradation/{point}/override | 取消覆盖回配置默认 | ops_admin |
| GET | /api/v1/ops/degradation/events | 降级事件历史 | 同上 |

### 7.4 M4 计量计费（设计冻结）

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | /api/v1/platform/usage/tenant/{tenantId}?metric=&from=&to= | 租户用量报表 | finance_admin/super_admin/audit |
| GET | /api/v1/platform/usage/overview | 平台成本总览 | finance_admin/super_admin |
| GET/POST/PUT | /api/v1/platform/plans | 套餐 CRUD | super_admin（实施时） |
| GET/POST | /api/v1/platform/subscriptions | 订阅管理 | finance_admin/super_admin（实施时） |
| GET | /api/v1/platform/bills | 账单列表/详情/导出 | finance_admin/super_admin（实施时） |

### 7.5 M5 租户管理（现有 + 扩展）

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| GET | /api/v1/platform/overview | 平台总览 | ✅ 现有 |
| GET | /api/v1/platform/tenant-stats | 租户统计列表 | ✅ 现有 |
| GET | /api/v1/platform/tenants/{id} | 租户详情（扩展：预警分布/用量/订阅） | 现有 + 扩展 |
| POST | /api/v1/platform/tenants/provision | 开通（扩展：planCode/试用期） | 现有 + 扩展 |
| POST | /api/v1/platform/tenants/{id}/suspend·resume | 暂停/恢复 | ✅ 现有 |
| GET | /api/v1/platform/tenants/{id}/health | 健康检查 | ✅ 现有 |
| GET | /api/v1/platform/tenants/{id}/quota | 配额详情 | 新增 |
| PUT | /api/v1/platform/tenants/{id}/quota | 配额调整（super_admin） | 新增 |
| POST | /api/v1/platform/tenants/{id}/archive | 归档 | 新增 |

### 7.6 M6 平台基础

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET/POST/PUT/DELETE | /api/v1/platform/admins | 平台账号 CRUD | super_admin |
| GET | /api/v1/platform/audit-logs?action=&limit= | 平台审计日志（现有 200 条扩展分页） | audit/super_admin |
| GET | /api/v1/platform/roles | 角色清单 | ADMIN |

### 7.7 M7 提示词配置中心

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | /api/v1/admin/prompts/matrix | 模板矩阵总览（✅ 现有） | ADMIN |
| GET | /api/v1/admin/prompts/versions?templateId= | 版本列表（✅ 现有） | ADMIN |
| POST | /api/v1/admin/prompts/versions | 新建版本/草稿（✅ 现有 createVersion，扩展草稿态） | ADMIN |
| POST | /api/v1/admin/prompts/versions/{id}/submit | 提交审核（draft→pending_review，扩展） | ADMIN |
| POST | /api/v1/admin/prompts/versions/{id}/review | 审核通过（pending_review→approved，扩展） | super_admin（审核人必填） |
| POST | /api/v1/admin/prompts/versions/{id}/activate | 激活（✅ 现有 activateVersion 门禁：reviewer+红队+eval） | super_admin |
| POST | /api/v1/admin/prompts/versions/{id}/rollout | 灰度推进（✅ 现有 rollout-eval） | super_admin |
| GET | /api/v1/admin/prompts/guardrails | 护栏用例库 + 回归报告（✅ 现有） | ADMIN（执行回归 super_admin） |
| GET | /api/v1/admin/prompts/safety-phrases | 安全话术只读汇总（新增只读端点） | ADMIN |

### 7.8 M8 业务信号与预警处置监控

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | /api/v1/platform/risk/overview | 跨租户风险全景（红橙黄绿/新增/未处置/趋势） | ops_admin/super_admin/audit |
| GET | /api/v1/platform/risk/sla | SLA 时效统计（按等级/租户：达标率/逾期率/P95） | 同上 |
| GET | /api/v1/platform/risk/overdue | 逾期预警清单（扫描任务产出，分页） | 同上 |
| POST | /api/v1/platform/risk/{id}/escalate | 升级处置：转派/强制关闭（body：action/reason） | ops_admin/super_admin（二次确认） |
| GET | /api/v1/platform/risk/dead-notifications | 通知兜底台账（notifyStatus=dead） | ops_admin/super_admin/audit |
| POST | /api/v1/platform/risk/{id}/dead-notification/resend | 人工补发/关闭（二次确认） | ops_admin |
| GET | /api/v1/platform/risk/closure-stats | 处置闭环统计（认领率/闭环率/误报率/回访完成率） | 同上 |
| GET | /api/v1/platform/risk/crisis-track | 危机事件（R4）追踪时间线 | super_admin/audit |

### 7.9 M9 知识库与内容管理

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | /api/v1/knowledge/documents?status=&page= | 文档库总览（✅ 现有，扩展分页/筛选） | ADMIN |
| POST | /api/v1/knowledge/documents | 上传新文档（✅ 现有） | ADMIN |
| PUT | /api/v1/knowledge/documents/{docId}/review | 审核通过/驳回（✅ 现有） | super_admin（审核人） |
| DELETE | /api/v1/knowledge/documents/{docId} | 下架（✅ 现有，语义=软删） | ADMIN |
| GET | /api/v1/knowledge/editorial/report | 质量报告（✅ 现有） | ADMIN |
| GET | /api/v1/knowledge/stats/top-hits | 命中统计 Top 文档（新增） | ADMIN |

### 7.10 M10 通知渠道与触达管理

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | /api/v1/ops/notify/channels | 渠道状态（企微/短信/站内） | ops_admin/super_admin/audit |
| GET | /api/v1/ops/notify/stats?channel=&type=&range= | 发送统计（量/失败/送达率） | 同上 |
| GET | /api/v1/ops/notify/failures | 失败台账（outbox failed/dead） | ops_admin/super_admin/audit |
| POST | /api/v1/ops/notify/failures/{id}/resend·close | 重发/关闭 | ops_admin（二次确认） |
| PUT | /api/v1/platform/config/{key} | 渠道配置/触达策略（复用 M1 通道，SECRET 掩码） | super_admin |

### 7.11 M11 数据安全与合规中心

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | /api/v1/platform/compliance/retention | 数据留存状态总览（各租户策略执行） | audit/super_admin |
| GET | /api/v1/platform/compliance/consents | 告知同意覆盖统计 + 待更新名单 | audit/super_admin |
| GET | /api/v1/platform/audit-logs/global?tenantId=&operator=&action= | 跨租户审计检索（扩展现有） | audit/super_admin |
| GET | /api/v1/platform/compliance/checklist | 等保/发布合规清单状态 | audit/super_admin |
| POST | /api/v1/platform/compliance/export-requests | 导出审批流（**设计预留，冻结实施**） | super_admin |

### 7.12 M12 运营洞察

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | /api/v1/platform/insights/quality | 会话质量看板（QualityScore 聚合） | ops_admin/super_admin/audit |
| GET | /api/v1/platform/insights/funnel | 预警漏斗（检出→闭环转化率） | 同上 |
| GET | /api/v1/platform/insights/mood-trend | 情绪分布周趋势（脱敏聚合） | 同上 |
| GET | /api/v1/platform/insights/tenant-health | 租户健康度评分（红黄绿列表） | ops_admin/super_admin |
| GET | /api/v1/platform/insights/effect-report | 效果指标报告（24h 响应率/家长知情率） | audit/super_admin |

---

## 八、前端设计（admin-web 页面清单）

> 技术栈：React 19 + TS + Vite（与 teacher-web 同构）；路由 `/admin/`；布局：左侧菜单 + 顶栏（角色/环境标识/告警铃铛）。

| 菜单 | 页面 | 核心组件 |
|------|------|---------|
| 总览 | 平台运营总览 | 指标卡（租户/学校/学生/会话/预警）+ 近 7 天会话趋势（复用 PlatformService.overview 数据）+ 服务健康概览 + 最近告警 |
| 系统配置 | 配置注册表（分域 Tab） | 配置分组列表、敏感掩码、变更弹窗（reason 必填）、变更历史抽屉 |
| 系统配置 | Prompt 管理 | 复用 AdminPromptController：版本列表/编辑/激活（reviewer 必填）/矩阵/护栏/灰度 |
| 系统配置 | Prompt 审核流 | 草稿→提交审核→审核（diff 视图：新旧版本并排对比）→激活门禁明细（红队失败清单）→灰度进度 |
| 内容管理 | 知识库管理 | 文档列表（状态筛选/版本）+ 上传向导 + 审核操作（通过/驳回）+ 质量报告 + 命中统计 |
| 内容管理 | 安全话术视图 | 召回话术/热线/兜底话术只读列表（标注「预审核内容，变更需发布评审」） |
| 业务信号 | 风险全景 | 红橙黄绿分布图 + 今日新增/未处置卡片 + 近 7 天趋势 + 租户下钻（数据默认脱敏） |
| 业务信号 | 时效监控 | SLA 达标率/逾期率表（按等级×租户）+ P95 处理时长 + 逾期预警清单（升级/转派操作，二次确认） |
| 业务信号 | 处置台账 | 通知兜底台账（dead 补发/关闭）+ 闭环统计（认领率/闭环率/误报率）+ 危机事件时间线（R4） |
| 应用监控 | 服务拓扑 | 六服务卡片（状态色 UP 绿/DEGRADED 黄/DOWN 红）+ 依赖连线 + 操作（启停/重启，二次确认） |
| 应用监控 | 指标看板 | ECharts：LLM/TTS/ASR 指标曲线（复用 FA-03 useECharts 模式） |
| 应用监控 | 告警中心 | 告警表格（级别筛选/ack/关闭）+ 告警详情抽屉 |
| 应用监控 | 部署历史 | 部署列表（信号徽标 OK/WARN/CRITICAL）+ 审计报告查看 |
| 应用监控 | 通知渠道 | 渠道状态卡（企微/短信/站内）+ 发送统计图表 + 失败台账（重发/关闭） |
| 降级监控 | 降级矩阵 | 能力矩阵表（档位/状态/影响）+ 手动切换弹窗（影响提示 + reason + 二次确认）+ 事件时间线 |
| 租户计量 | 用量报表 | 租户选择 + 指标曲线（活跃学生/LLM token）+ 平台成本总览（冻结态：设计预览标注） |
| 租户计量 | 订阅管理 | 套餐表 + 租户订阅列表 + 到期提醒（冻结态：占位） |
| 租户管理 | 租户列表 | 搜索/筛选表格 + 开通向导（code/name/phone/plan）+ 暂停/恢复/归档操作 |
| 租户管理 | 租户详情 | 概览卡 + 学校列表 + 会话趋势 + 预警分布（红橙黄绿）+ 用量概要 + 配额编辑 |
| 运营洞察 | 会话质量 | QualityScore 趋势（平均分/低质量占比）+ 情绪分布周趋势（脱敏聚合） |
| 运营洞察 | 预警漏斗 | 检出→通知→认领→处置→闭环转化率漏斗图 + 租户健康度红黄绿列表 |
| 数据合规 | 留存与同意 | 租户留存策略状态表 + 告知同意覆盖统计 + 待更新名单 |
| 数据合规 | 审计全景 | 跨租户审计检索（tenant/操作人/动作/时间筛选） |
| 平台基础 | 平台账号 | 账号表格 + 角色选择 + 启用禁用 |
| 平台基础 | 审计日志 | 日志表格（action 筛选/分页） |

**前端实现要点**：
- 路由守卫：按角色渲染菜单 + 未授权 403 页（与 teacher-web 现有模式一致）
- 所有写操作（配置修改/服务操作/降级切换/订阅变更）弹确认框 + reason 必填
- 图表组件复用 FA-03 useECharts 既有模式；共享模块复用 frontend/shared
- 构建/CI/部署：并入现有前端构建链（CI 前端门禁 + deploy.sh 前端 rsync），宿主 nginx `/admin/` location 指向 admin-web dist

### 8.1 页面风格与设计系统（对齐 doing/75 方案 A 青屿）

**设计对齐原则**：admin-web 是产品第四个端，遵循产品统一设计体系 **doing/75 方案 A「青屿」**——与 teacher-web / parent-h5 **共用同名同值 `--ms-*` 设计令牌**（主色治愈青绿 #2BA8A0），不另立品牌、不另起色板。student-h5 的儿童主题（海洋探险）为儿童场景特例，管理端面向成人运营者，不适用。技术栈对齐 teacher-web：React 19 + antd v5（ConfigProvider zhCN）+ CSS 变量 token + ECharts。

**令牌复用方式（DRY）**：admin-web 直接引入与 teacher-web `src/index.css` 同源的设计令牌文件（--ms-* 全量：品牌/中性/语义/形态/框架/图表九色/暗色变体）；实施时评估将 token 文件提升至 frontend/shared 供 teacher-web / parent-h5 / admin-web 三方共用（对齐现有 shared 模块约定，若改造面大则先复制同源文件，标注同源勿改，后续再收敛）。

### 8.2 色彩体系（token 明细，实值来自 teacher-web 已落地）

| token | 值 | 用途 |
|-------|-----|------|
| --ms-primary | #2BA8A0 | 品牌主色：主按钮/链接/激活态/Logo |
| --ms-primary-strong | #1E7F7A | 主色加深：渐变末端/按压态 |
| --ms-primary-soft | #E8F6F4 | 主色浅底：选中背景/标签底 |
| --ms-bg / --ms-bg-elevated | #FAF9F6 / #F4F7F6 | 页面背景 / 次级底（输入框） |
| --ms-card | #FFFFFF | 卡片底 |
| --ms-text / secondary / muted | #22303A / #5C6B76 / #8A97A0 | 主/次/弱文字三级 |
| --ms-border / --ms-border-soft | #E3E8E6 / #EEF2F0 | 常规边框 / 浅底边框 |
| --ms-success / -soft | #2E9E6B / #E8F6EE | 成功态（服务 UP/达标） |
| --ms-warning / -soft | #D98E32 / #FDF1E3 | 警告态（降级/逾期风险） |
| --ms-danger / -soft | #D9534F / #FDEBEA | 危险态（服务 DOWN/严重逾期） |
| --ms-radius-card / control / pill | 16px / 12px / 24px | 卡片 / 控件 / 胶囊 |
| --ms-shadow-card / tab | 0 4px 16px rgba(43,168,160,.08) / 0 2px 6px rgba(43,168,160,.12) | 卡片阴影 / 激活 tab（青绿调） |
| --ms-sider-bg | #163B38 | 深青侧边栏（工作台框架） |
| --ms-chart-1~9 | #4fc3f7 #81c784 #ff8a65 #ffd54f #ce93d8 #64b5f6 #ef5350 #ffb74d #90a4ae | 图表系列色（顺序取用） |

> 暗色模式：`[data-theme='dark']` 覆盖层变体（--ms-primary-soft→#123B38、bg→#0F1518、card→#1B2328、语义色提亮 #3DBB7F/#E8A84C/#E86762 等）与 teacher-web 完全一致，见 8.8。

### 8.3 业务状态色语义映射（管理端特有语义与既有 token 的绑定）

| 业务语义 | 状态/等级 | 色值（token） | 呈现规范 |
|---------|----------|-------------|---------|
| 服务健康（M2） | UP / DEGRADED / DOWN | success / warning / danger | 卡片左上状态点 + 文字；DEGRADED 用黄色表达「降级非故障」 |
| 预警等级（M8） | R4 红 / R3 橙 / R2 黄 / R1 绿 | danger / chart-3 橙 #ff8a65 / warning / success | 等级 Tag（soft 底 + 深字）；风险全景分布图同色系 |
| 逾期状态（M8） | 未超时 / 临期（<30%）/ 已逾期 | 默认 / warning / danger | 时效列：临期淡黄底、逾期红字 + 「升级」操作高亮 |
| 租户健康度（M12） | 健康 / 关注 / 风险 | success / warning / danger | 红黄绿点 + 列表排序 |
| 告警级别（M2） | CRITICAL / WARNING / INFO | danger / warning / muted | 告警中心级别徽标 |
| 配置敏感级（M1） | SECRET / NORMAL | muted 徽标「已配置/未配置」/ 默认 | SECRET 不展示值，仅状态徽标 |
| 提示词状态（M7） | draft / pending_review / approved / active / retired | muted / warning / primary / success / muted | 版本列表状态 Tag 同色系 |

> 规则：**状态一律用语义色，品牌青绿不参与状态表达**（青绿只做品牌/交互主色），保证运营者扫读速度；红绿表达不单独依赖颜色，同时带文字/图标（色盲友好，见 8.9）。

### 8.4 字体与排版

- 字体栈：与三端一致 `-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif`（不引入 web 字体，避免加载成本）
- 字号层级：指标卡大数字 32px/700（--ms-stat-big 既有类）、指标标题 20px、卡片标题 16px/600、正文 14px、辅助说明 12px muted（--ms-hint 既有类）
- 数字对齐：指标/时长/金额类数字用 `font-variant-numeric: tabular-nums`（表格列对齐稳定）
- 行高：正文 1.6，表格行高 48px（密集数据场景）

### 8.5 布局规范

**登录页**：全屏 --ms-primary-soft 背景 + 居中卡片（16px 圆角 + --ms-shadow-card），品牌 Logo + 系统名，表单控件 12px 圆角；登录失败/锁定态用 danger 提示条（对齐弱口令 fail-fast 既有机制）。

**主框架**（对齐 teacher-web 工作台）：
```
┌──────────┬────────────────────────────────────────────┐
│ 侧边栏    │ 顶栏：面包屑 │ 环境标识(prod/test) 角色 │ 告警铃铛 主题切换 │
│ #163B38  ├────────────────────────────────────────────┤
│ 深青底    │ 内容区（--ms-bg 底，卡片 16px 圆角 + 青绿阴影）    │
│ 菜单分组   │                                            │
│ 模块图标   │                                            │
└──────────┴────────────────────────────────────────────┘
```
- 侧边栏 240px 固定：深青底 #163B38、菜单分组标题 muted、激活项 --ms-primary 左竖条 + 浅底高亮；告警铃铛在顶栏右侧，有未确认 CRITICAL 时红点 + danger 色
- 内容区：页头（标题 + 描述 + 主操作按钮右对齐）→ 筛选栏 → 卡片容器
- 看板页栅格：指标卡 4 列（lg 断点），图表卡 2 列；宽屏 ≥1440 时指标卡可 6 列

### 8.6 组件规格（antd v5 + token 化）

| 组件 | 规格 | 说明 |
|------|------|------|
| 卡片 | 16px 圆角 + --ms-shadow-card，antd bordered=false | 页面容器统一用 Card |
| 表格 | 行高 48px、列头 muted 底色、斑马纹可选；长表格固定表头 + 操作列 sticky | 密集数据场景主体 |
| 表单 | 控件 12px 圆角、label 14px secondary；**reason 必填项加红色 * 与提示文案** | 配置修改/切换操作表单 |
| 弹窗 | 二次确认：标题 + 操作摘要（X-Confirm 头内容预览）+ 影响提示（danger/warning 底）+ reason 输入 | 危险操作统一 Modal.confirm 定制 |
| 标签 Tag | soft 底 + 深色字（--ms-*-soft + --ms-*），圆角 6px | 状态/等级/敏感级 |
| 按钮 | 主操作 primary（青绿）胶囊化（--ms-radius-pill 可选）；危险操作 danger；禁用态 muted | 每页一个主操作 |
| 空态 | --ms-empty / --ms-empty-lg 既有类（图标 + 文案 + 可选操作按钮） | 清单/台账空数据 |
| 抽屉 | 详情类（告警详情/变更历史/审核 diff）用 Drawer 右侧滑出，宽度 480/720 | 不打断主列表 |
| 步骤条 | Prompt 审核流/开通向导用 Steps（状态色同 8.3） | 流程类页面 |

**写操作交互规范**（全站统一）：
1. 点击触发 → Modal.confirm：标题 = 操作名，正文 = 操作摘要（将执行什么/影响什么，danger 类红字标注影响面）
2. reason 必填（配置修改/降级切换/升级处置四类强制）→ 确认按钮才可点
3. 前端携带 `X-Confirm: <操作摘要>` 头 + 后端二次校验 → 成功 Toast + 列表刷新；失败展示原因（含后端错误码）

### 8.7 图表规范

- 复用 FA-03 useECharts 既有模式（暗色自适应：读 data-theme 重设 axis/legend 文字色）
- **系列色固定 --ms-chart-1~9 顺序取用**，不用默认 palette（保证跨端视觉一致）；趋势主色用 --ms-primary
- 语义图表（风险全景红橙黄绿/健康度红黄绿）直接用 8.3 语义色，不套系列色
- 大数字（指标卡）用 --ms-stat-big 样式 + tabular-nums；图表区高度统一 320px（看板）/ 260px（详情内嵌）
- 空数据图：显示 --ms-empty 态而非空坐标轴（避免误导为 0）

### 8.8 暗色模式

- 机制对齐 F-01（teacher-web 已落地）：antd `darkAlgorithm` + `<html data-theme>` 同步 + `[data-theme='dark']` 覆盖 --ms-* 变体
- admin-web 默认亮色；顶栏提供主题切换（记忆 localStorage，与 teacher-web 同 key 则两端同步）
- 图表：暗色下坐标轴/图例文字用 --ms-bs-text 系列浅色系（复用既有 token）；语义色用暗色提亮变体（#3DBB7F/#E8A84C/#E86762）
- 数据大屏类页面（如后续接大屏）不随主题，常驻暗色（对齐 F-06 BigScreen 约定）

### 8.9 无障碍与细节

- 状态不只靠颜色：语义状态均带文字/图标（如「● UP」而非纯色点），色盲/灰阶显示器可读
- 对比度：正文/背景 ≥ 4.5:1（#22303A on #FFFFFF ✅；muted 文字仅用于辅助说明，不作关键信息）；暗色变体同样校验
- 焦点可见：表格行/按钮 focus 环用 --ms-primary 2px outline；键盘可达（Tab 遍历顺序 = 视觉顺序）
- 表单错误：danger 文字 + 输入框红边 + aria-describedby 关联（与 teacher-web 表单一致）
- 字号适配：管理端不跟随系统大字（运营工具场景，保持 14px 基准）；登录页例外提供「放大」开关可选

**前端实现要点（补充）**：
- 设计令牌文件与 teacher-web 同源（8.1），实施时禁止私自改色值——改色走 doing/75 体系评审，保证四端一致

---

## 九、与现有架构映射（复用与新增对照）

| 现有资产 | 复用方式 | 新增/扩展 |
|---------|---------|----------|
| PlatformService（overview/tenantStats/tenantDetail/schools） | M2 看板、M5 详情直接调用 | 扩展：时间维度查询、预警分布、配额 |
| AdminTenantController + TenantProvisioningService | M5 生命周期直接复用 | 扩展 provision 参数（planCode）、archive、quota 校验 |
| PlatformController | M2/M5 API 基座 | 保持 |
| AdminController（邀请码/导入/审计） | 原样接入 admin-web 页面 | 审计查询分页化 |
| AdminPromptController | M7 Prompt 管理原样接入 | 新增 submit/review 端点 + safety-phrases 只读端点 + 草稿态 |
| PromptVersionService / TemplateMatrixRegistry / RedTeamRegressionRunner | M7 版本/门禁/灰度/护栏直接复用 | prompt_versions.status 字段 + reviewer 落库 |
| RiskEvent 状态机 + TeacherAlertController 链路 | M8 时效统计/危机追踪数据源（纯查询） | **复用 SlaEscalationScanner/AlertSlaPolicy**（已实现 P-05）+ sla_escalation_log 留痕扩展 + 升级端点（转派/强制关闭） |
| RiskNotifyOutboxService（outbox + dead） | M8.4 兜底台账、M10 发送统计/失败台账数据源 | 台账管理端点（补发/关闭，二次确认） |
| KnowledgeBaseController（documents/review/editorial/report） | M9 知识库管理原样接入 | 分页/筛选扩展 + top-hits 统计 |
| QualityScore / ConsentRecord / CounselingSession | M12 质量看板、M11 同意覆盖数据源 | 无（纯聚合） |
| AlertService（WeCom/Logging 条件装配） | M2 告警中心、M3 降级事件通知复用统一出口 | 无（仅消费） |
| Prometheus（3 job + 10 规则） | M2 指标/告警数据源（后端代理） | service_health_snapshots 定时采样；**+3 降级规则**（TtsPrimaryEngineDegraded/TtsDegradeRatioHigh/LlmPrimaryFailing，降级监控文档 §3.2，OPS-MON-003） |
| service-manager.sh（六服务健康/启停） | M2 服务状态语义对齐（UP/DEGRADED/DOWN） | 管理端操作入口（安全评估） |
| ResilientChatModel/TTS 三级/ASR/SER/VoiceDegradationPolicy | M3 降级点盘点（全部既有） | 运行时覆盖键（Redis）+ degradation_events（**auto 写入归口降级监控文档 OPS-MON-007，manual 由本模块写**） |
| model_call_logs | M4 LLM 计量聚合源 | 补 tenant_id 列（若缺） |
| TenantLineHandler 行级隔离 | 平台表排除隔离（与 tenants/schools 同机制） | sys_config 等平台表加入忽略名单 |
| SecurityConfig（hasRole ADMIN） | M6 权限基座 | 细分角色授权 |
| audit_logs + AuditLogService | M6 审计复用 | tenantId 可空（平台级操作） |
| admin-web | — | **全新建**（前端应用 + 后端 console 控制器） |
| teacher-web index.css（--ms-* token 全量） | 设计令牌直接复用（§8.1 同源引入） | 评估提升至 frontend/shared 三方共用 |

---

## 十、安全与合规

1. **角色最小化**：四角色（super_admin/ops_admin/finance_admin/audit），端点级强制授权 + 前端菜单双保险；默认拒绝（deny-by-default）
2. **敏感配置**：SECRET 类配置只显示「已配置/未配置」，值永不出 API；配置修改仅 super_admin 且 reason 必填
3. **高危操作二次确认**：服务启停/降级切换/订阅变更要求 `X-Confirm: <操作摘要>` 头 + reason 字段 + 审计留痕
4. **降级切换影响评估**：手动切换前展示影响面（§5.3 功能 3.5），切换事件全量落库
5. **审计全覆盖**：配置变更/服务操作/降级切换/账号管理/订阅变更 → audit_logs（平台级 tenantId=null）
6. **服务操作执行通道**（已议决 R-2，2026-08-09）：P0 采用方案①——管理端仅展示 + 引导 SSH 操作（最安全，YAGNI），服务启停仍走 service-manager.sh（人工 SSH），管理端只读展示状态；后端受限命令执行（方案②）与独立运维 agent（方案③）挂远期。
7. **合规对齐**：平台级数据查看权限受控（心理数据敏感，S0-S4 分级沿用）；管理端访问日志记录；管理端入口（/admin/）可配置 IP 白名单（nginx 层，部署期配置）

---

## 十一、实施路线

> 分四期，每期独立可交付、可回归；计量计费（P3）受 frozen/38 解冻约束。**用户核心需求（提示词配置审核、业务信号监控）安排在 P1，不做后续期延后。**

| 期 | 范围 | 交付物 | 依赖/约束 |
|----|------|--------|----------|
| **P0 底座** | M6 平台账号与角色 + admin-web 骨架 + M2 服务拓扑（只读，方案①） | admin-web 上线（总览/服务状态/告警中心只读/审计日志/平台账号） | 无冻结依赖；后端：platform_admin 表 + SecurityConfig 角色细化 + ops 只读端点；告警中心只读先行（AlertManager 直读，不依赖落库） |
| **P1 配置与业务核心** | M1 配置管理 + **M7 提示词配置审核中心** + **M8 业务信号与预警处置监控** + M2 指标看板/健康快照/告警中心完整 | 配置注册表/变更留痕/Prompt 在线编辑审核发布流/风险全景/SLA 时效监控/逾期升级/业务级告警规则 | sys_config、sla_escalation_log、prompt_versions.status；**M8 逾期扫描复用已实现 SlaEscalationScanner（P-05），新增面：sla_escalation_log 留痕 + 平台级清单/转派端点 + mindsafe_risk_* 业务指标埋点 + alert-rules.yml 业务段**；M7 需 submit/review 端点扩展；**M2 指标看板/告警中心完整版依赖监控链路前置：OPS-MON-003/004/008（降级监控文档）** |
| **P2 治理深化** | M3 降级监控 + **M9 知识库管理** + **M10 通知渠道管理** + **M12 运营洞察** | 降级可视化管理闭环/知识库审核运营/渠道统计与触达策略/会话质量与预警漏斗 | degradation_events（**auto 事件依赖 OPS-MON-007**）；M10 触达策略改造需动 NotificationService 分发逻辑（回归测试护航）；M12 依赖 M8 指标 |
| **P3 商业化与合规** | M4 计量计费（usage_events 采集 + 用量报表先行）+ **M11 数据安全合规中心** | 计量采集与报表；订阅/账单设计定稿待解冻；合规视图（留存/同意/审计全景） | **frozen/38 解冻议决**；M11 导出审批流冻结待议决 |

**各期验收标准（示例，落地时细化为可测断言）**：
- P0：平台账号 CRUD 可用；四角色登录后菜单差异正确；服务六状态正确展示；审计日志分页可查
- P1：配置修改生效（HOT 类即时、RESTART 类提示）；变更历史可追溯；Prompt 草稿→审核→激活全流程可走通（激活门禁红队失败时不可激活）；逾期扫描 5 分钟周期正确产出清单并触发升级通知（企微）；SLA 统计与手工计算一致（抽样断言）；指标看板数据与 Grafana 一致（抽样断言）
- P2：手动切换 TTS 引擎后 /health 反映新档位；取消覆盖后回落默认；事件历史完整；知识文档审核流可走通；触达策略切换后通知渠道顺序生效（回归测试通过）
- P3：用量报表与 model_call_logs 手工聚合一致（抽样断言）；订阅到期提醒触发；合规视图数据与留存策略实现一致

---

## 十二、风险与开放问题

> 议决状态（2026-08-09 项目负责人确认，DEC-007）：**R-1/R-2/R-7/R-8 已议决**（按推荐方案定稿）；**R-6/R-9~R-12 已定执行方案**（照设计执行）；**R-3/R-4 留待对应实施阶段（P1/P3）确认**；M4 采集层先行落地已确认（§5.4）。

| # | 问题 | 说明 | 结论 |
|---|------|------|------|
| R-1 | 平台账号模型：独立 platform_admin 表 vs 复用 users（tenant_id=null） | 影响登录链路改造范围；现有 super_admin 已挂 TenantContext.userType | ✅ **已议决**：独立 platform_admin 表 + 独立登录端点（方案 A，隔离最清晰），后续可平滑迁移 |
| R-2 | 服务操作执行通道：后端执行 shell vs 只读展示 + SSH 人工 | 安全风险 vs 便利性 | ✅ **已议决**：P0 只读展示（方案①）；后端执行通道挂远期 |
| R-3 | 配置热生效范围：哪些配置可 HOT | Spring @ConfigurationProperties 部分可刷（RefreshScope），环境变量类不可 | ⏳ P1 细化：仅标记 HOT 的开放修改，其余只读 + 重启指引 |
| R-4 | model_call_logs 是否含 tenant_id | 计量聚合必需；若缺需补列（历史数据回填策略） | ✅ **已核对通过**（2026-08-09 审计）：`ModelCallLog.tenantId` 已存在，无需补列，无历史回填 |
| R-5 | 计量计费解冻时序 | BILL-002/003 冻结于 frozen/38（P2 商业化），EntitlementFilter 同冻结 | 本方案设计定稿；解冻时按 frozen/38 议决流程执行 |
| R-6 | 平台级表与租户行级隔离 | TenantLineHandler 需排除平台表 | ✅ **已定**：P0 落地时配置忽略名单 + 集成测试覆盖 |
| R-7 | 运维角色最低权限边界 | ops_admin 是否可看学生级数据（平台钻取到学生） | ✅ **已议决**：ops_admin 仅看聚合数据，学生级明细仅 super_admin/audit |
| R-8 | 管理端与三端共用 JWT 体系 | 平台账号登录态与业务登录态混用风险 | ✅ **已议决**：独立登录端点 + 独立 token 前缀（PLATFORM_） |
| R-9 | 逾期扫描任务的幂等与时钟一致性 | 扫描任务多实例并发会重复升级；服务器时钟偏移影响 SLA 判定 | ✅ **已定**：扫描任务加分布式锁（Redis SETNX）+ 每事件升级记录唯一约束（risk_event_id+stage）；SLA 计时基于 DB 时间戳（应用写入）不依赖扫描时点 |
| R-10 | Prompt 在线编辑与代码内模板双源风险 | DB 模板可绕过代码评审直接生效（classpath 降级存在，但 DB 优先已覆盖） | ✅ **已定**：激活门禁三重（reviewer+红队+eval）不可绕过；safety 域模板强制灰度；classpath 兜底仅限未配置 DB 版本时 |
| R-11 | 业务指标进 Prometheus 的隐私边界 | 预警/情绪类指标若带标识信息会泄露心理数据 | ✅ **已定**：指标只含计数/时长/等级，绝不带学生/教师标识（对齐 S0-S4 分级）；Grafana 面板访问走管理端代理不直连 |
| R-12 | 触达策略配置化改造影响预警通知链路 | M10.5 改造 NotificationService 分发逻辑可能影响风险预警通知可靠性 | ✅ **已定**：P2 改造时预警通知路径加回归测试（outbox 状态机单测 + 集成测试）；改造默认值=现状行为 |

---

## 十三、SPEC 开发计划（ticket 级验收标准）

> 说明：本表为 §11 实施路线的**可测化落地**（AC 级，对齐降级监控文档 AC 模式），每个 ticket 对应可测断言；每个 ticket 以 TDD 实施（先写失败测试）。**审计基线（2026-08-09 代码实态核对）**：M2/M3/M5/M7/M8/M9 引用组件与端点全部存在且有测试（AdminPromptController 8 端点/KnowledgeBaseController 7 端点/PlatformController 4 端点）；`SlaEscalationScanner`（P-05）已实现（M8 复用）；`ModelCallLog.tenantId` 已存在（R-4 关闭）；`prompt_versions` 无 status 字段（6.10 为真新增）；定时任务先例 `@Scheduled`（RiskNotifyRetryJob/DataRetentionCleanupJob/SlaEscalationScanner）。

### 13.1 P0 底座（M6 + admin-web 骨架 + M2 服务拓扑/告警只读）

| Ticket | 任务 | 前置 | 验收标准（AC-P0-xx） |
|--------|------|------|---------------------|
| ADMIN-P0-01 | platform_admin 表 + 实体 + 迁移 | 无 | 表结构按 §6.8；迁移脚本幂等可重跑；platform_admin 加入 TenantLineHandler 忽略名单（R-6，集成测试覆盖） |
| ADMIN-P0-02 | 平台登录端点 + 独立 JWT（PLATFORM_ 前缀）+ 四角色 | P0-01 | 独立 `/api/v1/platform/auth/login`（R-1/R-8）；token 前缀 `PLATFORM_`；四角色枚举；错误密码/禁用账号 401 + 审计留痕 |
| ADMIN-P0-03 | SecurityConfig 角色细化（PLATFORM_ 授权域） | P0-02 | 端点级 hasRole 生效；未授权 403；三端业务端点回归不回归（现有测试全绿） |
| ADMIN-P0-04 | admin-web 脚手架 + 路由守卫 + 角色菜单 | P0-02 | `/admin/` 路由；登录页（青屿 §8.5）；未登录跳登录；四角色菜单差异断言 |
| ADMIN-P0-05 | M2 服务拓扑（只读）+ service_health_snapshots | P0-03 | `GET /api/v1/ops/services/status` 六服务 UP/DEGRADED/DOWN（语义对齐 service-manager）；快照 30s 落库；健康历史查询可用 |
| ADMIN-P0-06 | 告警中心只读（AlertManager 直读） | P0-05 + 监控栈已部署 | `GET /api/v1/ops/alerts` 返回 AlertManager active 告警（无落库依赖）；非 ops 角色 403 |
| ADMIN-P0-07 | 审计日志查询（跨租户） | P0-03 | audit_logs 平台级查询（tenantId 可空过滤）；分页；操作人/动作/时间筛选 |
| ADMIN-P0-08 | P0 回归门禁 | P0-01~07 | 后端 mvn verify + 前端 vitest 全绿；新增测试：平台登录/角色越权/服务状态 mock（覆盖率按项目门禁） |

### 13.2 P1 配置与业务核心（M1 + M7 + M8 + M2 完整）

| Ticket | 任务 | 前置 | 验收标准（AC-P1-xx） |
|--------|------|------|---------------------|
| ADMIN-P1-01 | sys_config 注册表 + 变更留痕 | P0 | 表 §6.1/6.2；CRUD + SECRET 掩码（值永不出 API）；变更写 sys_config_history；HOT/RESTART 两级（R-3：仅标记 HOT 开放修改，其余只读 + 重启指引）；仅 super_admin 可改 + reason 必填 |
| ADMIN-P1-02 | M7 审核发布流（submit/review/状态机） | P0 + 现有 AdminPromptController | prompt_versions.status 扩展（§6.10）；draft→pending_review→approved→active 流转；reviewer 必填；safety 域强制灰度；红队失败不可激活（复用 RedTeamRegressionRunner） |
| ADMIN-P1-03 | M7 门禁可视化 + safety-phrases 只读 | P1-02 | 红队结果/灰度进度/护栏可查；safety 话术只读端点（写请求 403） |
| ADMIN-P1-04 | M8 风险全景 + 时效监控（纯查询） | P0 | 8.1/8.2 聚合与手工 SQL 抽样一致；riskLevel 1-4 映射红橙黄绿正确；P95 计算正确 |
| ADMIN-P1-05 | M8 逾期升级扩展（sla_escalation_log + 清单/转派端点） | P1-04 | 复用 SlaEscalationScanner（不改其告警逻辑）；升级动作补落 sla_escalation_log（现只告警不留痕）；平台逾期清单正确；转派/强制关闭端点 X-Confirm + 审计 |
| ADMIN-P1-06 | M8 业务指标埋点 + alert-rules 业务段 | P1-05 | mindsafe_risk_* 由扫描任务产出（gauge）；4 条业务规则 promtool 语法通过；指标不含学生/教师标识（R-11） |
| ADMIN-P1-07 | M2 指标看板（完整） | OPS-MON-003/004/008 + P0 | 白名单表达式代理查询；图表与 Grafana 抽样一致；非白名单表达式拒绝（403/400） |
| ADMIN-P1-08 | M2 告警中心完整（alert_events 消费 + ack） | P1-07 | 列表聚合 alert_events + AlertManager；ack 写库 + 状态流转；仅 ops_admin 可 ack |
| ADMIN-P1-09 | 前端 P1 页面组 | 对应后端 | 配置注册表/Prompt 管理/审核流/风险全景/时效监控/处置台账/指标看板/告警中心页面可用；写操作确认框 + reason 必填 |
| ADMIN-P1-10 | P1 回归门禁 | P1-01~09 | 全量回归；SLA 统计与手工计算一致（抽样）；Prompt 草稿→审核→激活全流程可走通（红队失败时不可激活） |

### 13.3 P2 治理深化（M3 + M9 + M10 + M12）

| Ticket | 任务 | 前置 | 验收标准（AC-P2-xx） |
|--------|------|------|---------------------|
| ADMIN-P2-01 | M3 降级矩阵 + 手动切换 | OPS-MON-007 + P1 | 矩阵状态聚合（/health + Redis 覆盖键 + Prometheus 指标）；切换写 Redis 键 + degradation_events manual 事件；取消覆盖回落默认（重启后也回落）；影响面提示展示；仅 ops_admin/super_admin |
| ADMIN-P2-02 | M3 事件时间线（消费 degradation_events） | P2-01 | auto/manual 事件完整展示（时间倒序 + 点/类型过滤）；auto 事件与降级监控文档 OPS-MON-007 产出一致 |
| ADMIN-P2-03 | M9 知识库管理扩展 | P0 + 现有 KnowledgeBaseController | 分页/筛选扩展 + top-hits 统计；审核流复用现有 review/editorial（不改动既有语义） |
| ADMIN-P2-04 | M10 通知渠道统计 + 失败台账 + 触达策略 | P1 | 发送统计图表（企微/短信/站内）；dead 台账补发/关闭（二次确认）；触达策略配置化改造默认值=现状行为（NotificationService 分发逻辑回归测试护航） |
| ADMIN-P2-05 | M12 运营洞察 | P1 | 会话质量趋势（QualityScore）；预警漏斗（检出→通知→认领→处置→闭环）；租户健康度红黄绿列表 |
| ADMIN-P2-06 | 前端 P2 页面组 | 对应后端 | 降级矩阵/知识库/通知渠道/运营洞察页面可用；降级切换弹窗含影响提示 + reason + 二次确认 |
| ADMIN-P2-07 | P2 回归门禁 | P2-01~06 | 手动切换后 /health 反映新档位；取消覆盖回落默认；事件历史完整；全量回归全绿 |

### 13.4 P3 商业化与合规（M4 采集 + M11，冻结项不实施）

| Ticket | 任务 | 前置 | 验收标准（AC-P3-xx） |
|--------|------|------|---------------------|
| ADMIN-P3-01 | usage_events 采集层（M4 先行落地） | P0 | 采集点埋设（chat/model call 关键路径）；事件幂等（重复提交去重）；采集与 model_call_logs 抽样一致（R-4 已核对 tenantId 存在） |
| ADMIN-P3-02 | 用量报表 | P3-01 | 活跃学生快照 + LLM token 聚合；报表与手工聚合一致（抽样）；页面标注「计量预览，计费冻结」 |
| ADMIN-P3-03 | M11 合规视图 | P1 | 留存策略状态/同意覆盖统计/审计全景；与 design/02 留存策略、ConsentRecord 实现一致 |
| ADMIN-P3-04 | P3 回归门禁 | P3-01~03 | 全量回归全绿；冻结项不实施（M4 4.3~4.6 计费、M11 导出审批，见 §5.4/§5.11） |

### 13.5 开发准备清单（启动前逐项核对）

| 项 | 内容 | 归属 |
|----|------|------|
| DB 迁移 | 按 §6.1→6.10 顺序落地（sys_config→sys_config_history→service_health_snapshots→alert_events→degradation_events→usage_events→platform_admin→sla_escalation_log→prompt_versions.status ALTER）；幂等 + 版本化（沿用项目 V 系列迁移脚本机制） | 后端 |
| admin-web 脚手架 | frontend/admin-web 新建（React 19 + TS + Vite，与 teacher-web 同构）；--ms-* token 同源引入（§8.1，评估提升 frontend/shared）；路由 /admin/ | 前端 |
| 监控前置 | 降级监控文档 OPS-MON-002~008：M2 依赖 OPS-MON-003/004/008（P1 前），M3 依赖 OPS-MON-007（P2 前） | 监控 |
| 密钥/配置 | 平台登录 JWT secret（独立于业务 JWT）；Grafana 密码；WECOM_* 4 项（降级监控文档 §3.3） | 部署 |
| 测试门禁 | 后端 mvn verify + 前端 vitest + Python + shell 套件全绿；新增代码覆盖率按项目门禁（核心 60%/整体 45%） | 全量 |
| 权限矩阵核对 | §7 各端点权限列 → SecurityConfig 单测覆盖（每端点越权测试，R-7 含 ops_admin 聚合边界） | 后端 |

---

## 附：功能清单总览（速查）

| 模块 | 功能编号 | 功能 |
|------|---------|------|
| M1 系统配置管理 | 1.1~1.6 | 配置分类浏览 / 配置修改 / 敏感配置管控 / 变更历史 / Prompt 配置入口 / 运行时配置下发展示 |
| M2 系统应用监控 | 2.1~2.6 | 服务拓扑与健康状态 / 关键指标看板 / 告警事件中心 / 部署历史 / 服务操作 / 租户活跃监控 |
| M3 服务切换降级监控 | 3.1~3.5 | 降级状态实时视图 / 手动切换入口 / 降级事件历史 / 自动降级通知联动 / 降级影响面提示 |
| M4 租户计量计费 | 4.1~4.6 | 租户用量报表 / 平台成本总览 / 订阅管理 / 配额联动 / 账单管理 / 权益开关（4.3~4.6 冻结） |
| M5 租户管理 | 5.1~5.7 | 租户列表搜索 / 一键开通 / 暂停恢复归档 / 健康检查 / 详情钻取 / 配额管理 / 数据留存查看 |
| M6 平台基础 | 6.1~6.4 | 平台账号管理 / 角色权限 / 审计日志 / 登录安全 |
| M7 提示词与内容配置中心 | 7.1~7.9 | 模板矩阵总览 / 提示词在线编辑 / 审核与发布流 / 门禁明细可视化 / A-B 实验管理 / 护栏用例库与回归 / 安全话术只读视图 / 模板影响面 / 变更审计 |
| M8 业务信号与预警处置监控 | 8.1~8.8 | 跨租户风险全景 / 预警处置时效监控（SLA） / 逾期预警管理与升级 / 通知兜底台账 / 处置闭环统计 / 业务级告警规则 / 危机事件追踪 / 信号来源分析 |
| M9 知识库与内容管理 | 9.1~9.6 | 文档库总览 / 上传与版本管理 / 审核流 / 内容质量报告 / 发布与失效 / 知识命中统计 |
| M10 通知渠道与触达管理 | 10.1~10.5 | 渠道状态总览 / 发送统计 / 失败台账 / 渠道配置 / 触达策略配置 |
| M11 数据安全与合规中心 | 11.1~11.5 | 数据留存总览 / 告知同意覆盖 / 审计全景 / 合规清单跟踪 / 数据导出审批（冻结） |
| M12 运营洞察 | 12.1~12.5 | 会话质量看板 / 预警漏斗 / 情绪趋势 / 租户健康度 / 效果指标报告 |

> 本方案为设计定稿文档（doing 子文档），P0~P2 分期实施、P3 冻结等待解冻议决；实施时按 design/05 测试体系补充对应测试，最终态并入主文档（建议 03 技术架构 + 07 商业化 + 02 数据库）。
