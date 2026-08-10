# doing/88 - 后台管理端 AdminConsole 实施态设计（结合代码实态）

> 状态：开发期（doing 子文档） | 编号：88（接续 his/87 冻结区，doing 区 74/84/86 之后）
> 创建：2026-08-10 | 作者：Agent（钱敏健工作区）
> **承接**：his/83 后台管理端 AdminConsole 设计方案（DOC-086/095，设计态，2026-08-09 已归档）——本专题输出**实施态**：结合 P0~P3 四期全部交付后的代码实态，形成「设计 ↔ 代码」对照的最终态设计文档
> **关联**：doing/86 后台管理端部署验证专题（部署后验证清单）；frozen/38（M4 计费层冻结）；frozen/87（LLM 成本跟踪）；design/01 §六（平台管理后台模块状态表）；design/03 §8（监控运维）；design/02 §6.6（平台级表）
> **产出物**：实施态架构 + M1~M12 能力域「设计→代码」落点对照 + 数据模型实态 + 主文档同步说明

---

## 一、背景与目标

### 1.1 背景

doing/83（his/83）完成后台管理端 AdminConsole 设计方案：12 能力域（M1~M12）+ 10 张表 + 4 API 域 12 节 + admin-web 前端 25 页面，四期实施（P0 底座 → P1 配置与业务核心 → P2 治理深化 → P3 商业化与合规冻结）。2026-08-09 双文档合并归档（DOC-095），设计态已并入主文档（01 §六 / 03 §8 / 02 §6.6 / 07 §2.8.1）。

**本专题目的**：AdminConsole 四期已全部交付（29 ticket），代码实态与设计态存在演进（页面口径 25→17 tsx 组件 15 视图、路由实现方式、表结构细节）。按「设计文档与代码一致」底线规则，输出实施态设计文档，作为后续维护与主文档同步的单一事实源。

### 1.2 目标

1. 记录 AdminConsole **实施态架构**（前端/后端/认证/数据全链路代码落点）
2. 建立 **M1~M12 能力域「设计 → 代码」对照表**（页面 / API / 表 / Service）
3. 明确**与设计态差异**（含 P3 冻结项与实施中调整）
4. 供 doing/86 部署验证与 frozen/38 解冻后实施引用

### 1.3 范围与边界

- **范围**：admin-web 前端 + 平台侧后端（Platform*/Ops/Admin* 域）+ V34~V38 迁移 + 认证安全
- **边界**：不重复设计态内容（详见 his/83）；不覆盖冻结项（M4 计价/计费，frozen/38）；教师端 admin 面板（平台总览/管理控制台）属 teacher-web 角色裁剪，仅引用

---

## 二、实施总览（P0~P3 四期交付）

| 期 | 内容 | ticket | 交付状态 |
|----|------|--------|---------|
| P0 | 底座：platform_admin 独立账号 + 登录端点 + ops 域 + admin-web 骨架（V35） | 8 | ✅ 完成（2026-08-09） |
| P1 | 配置与业务核心：M7 提示词中心 / M8 预警 SLA / 邀请码与批量导入（V36） | 8 | ✅ 完成 |
| P2 | 治理深化：M3 降级矩阵 / M9 统计 / M10 台账 / M12 洞察 | 7 | ✅ 完成 |
| P3 | 商业化与合规：M4 计量采集层 / M11 合规视图（V37/V38） | 6 | ✅ 完成（计费层冻结待 frozen/38） |

**交付统计**：后端 8 个平台域 Controller、迁移 V34~V38（8 张新表 + sys_config 种子）、前端 admin-web 15 视图（17 tsx 组件）、API 12 节、全量回归绿。

---

## 三、代码架构实态

### 3.1 前端 admin-web（独立应用，非教师端内嵌）

```
frontend/admin-web/
├── src/
│   ├── App.tsx                    # state 路由 + 登录守卫 + 角色视图分发（401/403 联动）
│   ├── api.ts                     # admin 请求封装（PLATFORM_ token 前缀 + UNAUTHORIZED_EVENT）
│   ├── components/AdminLayout.tsx # 布局 + allowedViews(role) 角色菜单
│   └── pages/                     # 15 视图
│       ├── LoginPage / OverviewPage / ForbiddenPage
│       ├── ConfigPage / PromptPage / RiskPage / SlaPage
│       ├── LedgerPage / DegradationPage / KnowledgePage
│       ├── ChannelPage / InsightsPage / UsagePage / CompliancePage
```

- **路由方式**：state 路由（`view` 状态机，非 URL 路由）——`ADMIN-P0-04` 设计决策
- **视图 ↔ 能力域**：overview（M2 总览）/ config（M1）/ prompt（M7）/ risk+sla（M8）/ ledger（M10）/ degradation（M3）/ knowledge（M9）/ channel（M10 通知）/ insights（M12）/ usage（M4）/ compliance（M11）
- **角色双保险**：后端 403 + 前端 `allowedViews(role)` 菜单过滤（ForbiddenPage）

### 3.2 后端平台域（8 Controller）

| Controller | 域 | 关键端点（实态） |
|---|---|---|
| PlatformAuthController | 认证 | POST /login（platform_admin 独立登录，PLATFORM_ token） |
| PlatformController | 总览 | /overview、/tenant-stats、/tenants/{id}、/schools |
| PlatformConfigController | M1 配置 | /registry、/{key}（GET/PUT）、/{key}/history（变更留痕） |
| OpsController | M2/M3/M8 | /services/status、/health-history、/alerts、/audit-logs、/risk/overview、/risk/sla-stats、/risk/overdue、/risk/{id}/transfer |
| AdminController | M10 邀请码/导入 | POST /batch、/{codeId}、/import-template、/import-students、/audit-logs |
| AdminPromptController | M7 提示词 | /versions（列表/详情/创建）、/{id}/activate、/deactivate、/submit、/review、/safety-phrases |
| AdminTenantController | M5 租户 | /provision、/{id}/suspend、/resume、/{id}/health |
| AdminUserController | M6 账号 | 平台用户管理（P0 backlog 余项） |

### 3.3 认证与权限（DEC-007 方案 A 落地）

- **独立账号模型**：`platform_admin` 表（与租户 users 解耦）
- **独立登录端点**：`/api/v1/platform/auth/login`（平台域），token 前缀 `PLATFORM_`（不共用业务 JWT）
- **四角色**：super_admin / ops_admin / audit / content_admin（`allowedViews` 映射）
- **安全**：登录防爆破（PlatformLoginGuard 内存计数，5 次失败锁 15 分钟）；权限不足 403 而非 404 泄漏

---

## 四、M1~M12 能力域「设计 → 代码」对照表

| 能力域 | 设计（his/83） | 代码落点（实态） | 差异说明 |
|---|---|---|---|
| M1 系统配置 | 配置注册表 + 变更留痕 | `sys_config` + `sys_config_history`；PlatformConfigController /registry、/{key}/history；ConfigPage | 一致；V38 种子数据落库 |
| M2 系统应用监控 | 三态 + 指标看板 + 告警中心 | `service_health_snapshots` + `alert_events`；OpsController /services/status、/health-history、/alerts；OverviewPage | 一致 |
| M3 服务切换降级监控 | 降级矩阵 + 运行时覆盖键 | `degradation_events`；OpsController /alerts；DegradationPage（矩阵可视化） | 手动切换运行时键按 R-2 决策挂远期 |
| M4 租户计量计费 | usage_events 采集→计价→计费三层 | `usage_events`（V37 采集层 ✅）；UsagePage 用量报表 | **计价/计费冻结**（frozen/38），仅采集层落地 |
| M5 租户管理 | 生命周期 + 配额 + 详情 | AdminTenantController /provision、suspend、resume、health | P1 交付 |
| M6 平台基础 | platform_admin 四角色 | `platform_admin` 表 + PlatformAuthController；四角色菜单映射 | 与 DEC-007 方案 A 一致 |
| M7 提示词中心 | 可视化编辑 + 审核流 + 安全话术只读 | AdminPromptController /versions 全流程（submit→review→activate）+ /safety-phrases；PromptPage | P1 交付（用户核心需求） |
| M8 业务信号与预警 | 风险全景 + SLA 时效 + 逾期升级 | OpsController /risk/*（overview/sla-stats/overdue/transfer）+ `sla_escalation_log`（V36）；RiskPage + SlaPage | P1 交付 |
| M9 知识库管理 | 内容管理 | KnowledgePage + 知识库管理端点 | P2 交付 |
| M10 通知与台账 | 渠道管理 + 审计台账 | ChannelPage + LedgerPage；OpsController /audit-logs；AdminController /audit-logs | P2 交付 |
| M11 数据安全合规 | 合规中心 | CompliancePage（数据安全/合规视图） | P2/P3 交付 |
| M12 运营洞察 | 洞察分析 | InsightsPage（M12 洞察） | P2 交付 |

---

## 五、数据模型实施态（V34~V38）

| 迁移 | 表 | 用途 |
|---|---|---|
| V34 | `degradation_events`、`alert_events` | 降级事件 / 告警事件（M3/M2） |
| V35 | `platform_admin`、`service_health_snapshots` | 平台账号（DEC-007）/ 服务健康快照（M2） |
| V36 | `sys_config`、`sys_config_history`、`sla_escalation_log` | 配置注册表 + 留痕（M1）/ SLA 逾期升级日志（M8）；含 `prompt_versions.status` 扩展 |
| V37 | `usage_events` | 计量采集层（M4，计费冻结） |
| V38 | `sys_config` 种子 | 配置默认值种子数据 |

> 设计态登记"新增 10 张表"——实施态实为 **8 张新表 + prompt_versions.status 字段扩展**（口径修正记录）。

---

## 六、部署验证状态（关联 doing/86）

- AdminConsole 部署验证项由 doing/86 跟踪（依赖生产监控栈部署/真实环境）
- 已随 deploy.sh 部署上线（admin-web 独立应用，/admin 路由，nginx alias）
- 2026-08-10 实测：admin 登录 → 数据大屏（teacher-web 角色裁剪）→ 管理控制台（邀请码管理/批量导入）→ 平台总览（学校/学生/会话统计）全部通过（UI-TEST-004 T-07 补测）

---

## 七、与主文档对照（DOC-095 并入位置 + 本专题补充）

| 主文档 | 已并入（DOC-095，设计态） | 本专题补充（实施态） |
|---|---|---|
| 01 §六 平台管理后台 | 十二模块状态表 + DEC-007 决策 + 四期路线 | 模块状态表标注「✅ 已交付（P0~P3）」+ 实施态落点引用 doing/88 |
| 03 §8 监控运维 | 规则数 8→17 + 降级/业务指标 + §8.1 服务降级监控链路 | 实态表（degradation_events/alert_events/service_health_snapshots）核对 |
| 02 §6.6 平台级表 | V34~V38 八表 + prompt_versions.status | 口径修正（10 张表登记 → 8 表 + 字段扩展） |
| 07 §2.8.1 计量计费 | usage_events 采集先行 + 计费冻结 | 保持冻结（frozen/38），采集层实测（UsagePage） |
| 05 §8.6 | — | admin-web 测试体系（vitest 配置已入库） |

---

## 八、后续冻结项

1. **frozen/38**：M4 计价/计费层（rate_plans/subscriptions/billing），解冻后按 his/83 §5.4 实施
2. **frozen/87**：LLM 成本跟踪与 DeepSeek 涨价应对（87-01~04），与 M4 用量数据衔接
3. **R-2 服务操作执行通道**：P0 只读展示 + SSH 人工（后端受限命令执行挂远期）
4. **doing/86 部署验证项**：生产监控栈部署后逐项闭环

---

## 九、验收标准

| # | 验收项 | 标准 |
|---|--------|------|
| 1 | 实态对照 | M1~M12 均有代码落点（页面/端点/表），与代码 grep 一致 |
| 2 | 口径修正 | 表数量（10→8+字段）、页面（25→15 视图）差异显式记录 |
| 3 | 主文档同步 | 01/03/02/07 追加 2026-08-10 实施态同步行 |
| 4 | 可溯源性 | 承接 his/83 + DOC-095，冻结项指路 frozen/38/87 |

---

## 附：与既有体系衔接

- **his/83**：设计态（调研/SPEC/AC 过程数据不入本专题，只读溯源）
- **doing/86**：部署验证清单（本专题 §六 状态引用）
- **design/01 §六**：平台管理后台模块状态（实施态标注）
- **TASK-TRACKER**：DOC-098 登记 + §三十一/§三十二 tickets 状态
