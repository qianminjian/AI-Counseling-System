# 审计报告 08 - 教师端（teacher-web）与管理端（admin-web）

- **审计时间**：2026-08-12
- **审计范围**：`frontend/teacher-web`（非测试文件 31 个：App/api/api/endpoints/4 pages/13 teacher components/3 hooks/5 utils）+ `frontend/admin-web`（非测试文件 24 个：App/api/AdminLayout/19 pages）
- **方法**：git log 热点分析（2026-07-20 起）+ 全量读取核心文件（teacher-web 的 App/api/Dashboard/AdminPanel/StudentPanel/usePolling，admin-web 的 App/api/AdminLayout/ConfigPage）+ 端点硬编码统计（grep）+ 测试盘点（teacher 28 / admin 19 测试文件）+ BEACON 冻结决策核对（只读，未改动任何文件）

## 1. 板块概况

**teacher-web**（React 19 + Vite 8 + antd）：装配式单页工作台——`Dashboard.tsx`（263 行）持 Tab 路由 + 轮询/WebSocket/通知三条触达链，7 个业务面板（Overview/AlertQueue/StudentPanel/QualityPanel/NotificationPanel/DeviceManagement/AdminPanel）各为独立组件。质量基础设施完备：FA-15 端点常量表（`callEndpoint` 按 key 消费，api.ts:84-91）、DC-005 共享认证传输、AUD-018 blob 下载收敛（api.ts:234-244）、usePolling 收敛（F3：Dashboard/BigScreen/TodayTodoPanel/WS 心跳四处轮询合一，usePolling.ts:10-13）+ AUD-047 页面不可见暂停。

**admin-web**：19 页平台控制台——`App.tsx` 用 VIEW_REGISTRY 注册表渲染（AD-009，替代 17 分支三元链，App.tsx:32-56，TS 穷尽保证），`AdminLayout.tsx` 角色菜单 + `allowedViews` 路由守卫（:63-65）。鉴权为独立域（DEC-007 R-8：独立登录端点 + `PLATFORM_` token 前缀 + 登录态隔离，与业务 JWT 有意解耦，api.ts:1-3）。

**双轨收敛**：P0 backlog ⑤ 已执行——平台总览迁 admin-web，teacher-web 保留租户内业务管理（邀请码/CSV 导入/租户内审计，AdminPanel.tsx:6）；admin-web 承担跨租户平台管理（配置注册表/风险/SLA/降级矩阵/设备 M13）。整体分工清晰，未发现职责重叠。

**测试**：teacher 28 文件（含 apiContract.test.ts 契约直校验 + authFetch/jwt/riskLevel/sla 工具测试 + 全组件测试）；admin 19 文件（含遍历修复 BUG-A 系列回归用例）。覆盖优于一般水准。

## 2. 热点与风险初判

- **teacher-web**：FA-15 端点常量表（2270a061）、F-01~F-09 安全收口（d141ae65：双 token 迁 sessionStorage/JWT UTF-8 解码/失败安全存储）、BUG-T 系列遍历修复（BUG-T-BASE-04 大屏返回失效、BUG-T-06-01~03 通知中心未读徽标/筛选分页）、doing/92 R-002/013/016 批次。
- **admin-web**：AdminConsole 四阶段（P0 底座 → P1 配置/风险/M7 审核 → P2 降级矩阵/洞察 → M13 设备管理，78f81042/3717d4fb/203c4d57）、BUG-A-001~007 遍历修复（b5e48cf8）、AD-009 视图注册表（2026-08-11）。
- **风险初判**：①admin-web 是唯一未纳入"端点单一事实源"治理的端（硬编码 34 处）；②admin-web 的 POST 封装存在 401/403 处理不一致；③teacher-web Dashboard 为"路由+触达链+布局+品牌"混合体，是教师端复杂度集中点。

## 3. 发现清单

### P0（架构级）
**未发现**。无跨模块耦合与分层违规；admin-web 独立鉴权符合 DEC-007 R-8 冻结决策（有意隔离，非实现偏差）；角色访问控制前端守卫 + 后端双保险（§13.1）落地一致；心理数据展示面（学生档案/预警/会话摘要）权限语义清晰（ops_admin 仅聚合、学生级明细仅 super_admin/audit，AdminLayout.tsx:12 注释对齐 DEC-007 ③）。

### P1（模块级）

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试判断 |
|---|---|---|---|---|---|
| P1-1 | admin-web/src/api.ts 全文件（`/api/v1` 硬编码字符串 34 处 + 8 处裸 fetch） | **端点单一事实源治理未覆盖 admin-web**：doing/94 R-001 / teacher-web FA-15 只覆盖 student-h5 与 teacher-web；admin-web 路径散落在 429 行 api 内，新增端点无编译期约束，且无对应 apiContract 契约测试（teacher-web 有 apiContract.test.ts）。R-001 属跨端架构决策，第三端漏接为治理缺口 | 为 admin-web 建立 `api/endpoints.ts` 常量表（对齐 teacher-web FA-15 形态），adminFetch/POST 封装全部消费；补 adminWebContract.test.ts 契约直校验 | leverage：三端端点治理收敛为同一套机制，新增端点只登记一处；契约回归自动化 | 保留：admin 现有 19 测试继续运行，新契约测试独立补充 |
| P1-2 | admin-web/src/api.ts:121-132（updateConfig）、:242-253（promptAction）、:194-227（degradationOverride/cancelDegradationOverride）、:385-398（exportDeviceQr）、:401-414（batchDeviceOperation） | **POST 封装 401/403 处理不一致**：仅 ackAlertEvent（:346-350）做了登出联动，其余 6 个 POST 封装在 token 过期时只抛"XX 失败/修改失败"，用户停留登录失效页却不知已失效（对照 adminFetch :60-64 的统一 UNAUTHORIZED_EVENT 模式）。会话过期静默化会掩盖真正的失败原因 | 抽出 `postAdmin(path, body)` 统一封装（鉴权头 + 401/403 → adminLogout + UNAUTHORIZED_EVENT + 语义化错误），6 处 POST 全部改走封装 | 一致性：失效态统一登出联动，错误归因不再误导运维 | 保留：admin POST 相关页面测试存在，封装改造后适配 |
| P1-3 | teacher-web/src/pages/Dashboard.tsx:57-65（MENU_ITEMS/ADMIN_MENU_ITEMS）vs :166-174（TITLES） | **菜单/标题双表重复维护**：key 在 MENU_ITEMS 与 TITLES 各登记一次，新增面板需改两处（漏改则标题空串）。admin-web 已有 AD-009 注册表模式可参照 | 合并为单一 `PANEL_REGISTRY: Record<key, {label, icon, render}>`（Dashboard 直接映射渲染），消除双表 | locality：新增面板一处登记；对齐 admin-web AD-009 模式，两端注册表风格统一 | 保留：Dashboard.test.tsx 适配 |
| P1-4 | teacher-web AdminPanel.tsx:28-35（AuditLogVO）vs admin-web api.ts:417-424（AuditLogItem） | **审计日志双端展示口径分裂**：教师端（租户内 audit_logs）与平台端（跨租户 ops/audit-logs）各自定义 VO 且字段不同（action/resourceType/detail vs action/detail/tenantId），无共享 DTO。板块05 已发现 COMP-006 操作审计部分失效，此处前端双展示加重"同一审计、两套语义"的核对成本 | 后端统一审计查询契约（或前端至少抽取 shared DTO + 字段映射层）；短期先收敛前端展示字段命名 | 一致性：审计可核对性（审计是合规红线域）；leverage：汇总后与板块05 审计专题合并处理 | 保留：两端审计相关测试各自存在 |

### P2（局部）

| 编号 | 位置 | 问题描述 | 建议方案 |
|---|---|---|---|
| P2-1 | admin-web/src/App.tsx:102 `setToken('logged-in')` | 登录态哨兵用字面量而非真实 token（真实 token 在 sessionStorage），命名易误导后续维护者 | 改为 `setToken(getAdminToken() ?? 'logged-in')` 或直接布尔化 `setAuthed(true)` |
| P2-2 | teacher-web/src/pages/Dashboard.tsx:26-55（playAlertSound/sendDesktopNotification 页内工具） | 两个工具函数与组件耦合（模块级变量 alertAudioCtx 也在组件文件内），无法独立测试 | 移入 utils/notify.ts（含 alertAudioCtx 单例），与 useAlertWebSocket 的消费解耦 |

## 4. 改进候选排序

- **Strong**：P1-1（admin-web 端点单一事实源——补上 R-001 跨端决策的最后一端，机制现成、复制成本低）；P1-2（POST 鉴权失败统一处理——会话过期属高频运维场景，修复面局部）
- **Worth exploring**：P1-3（Dashboard 面板注册表——对齐 admin-web 已有模式）；P1-4（审计 DTO 收敛——与板块05 COMP-006 合并为审计专题更划算）
- **Speculative**：P2-1/P2-2 为打磨项，随上述改动顺带处理

## 5. 设计一致性核对

| 冻结决策 | 实现核对 | 结论 |
|---|---|---|
| BEACON #28 / DEC-007：平台账号独立表 + 独立登录端点 + `PLATFORM_` token 前缀 + 登录态隔离 | admin-web api.ts:1-3、15-49（独立 sessionStorage 键 admin_token/role/name、独立登录端点 /api/v1/platform/auth/login） | ✅ 一致 |
| DEC-007 ③：ops_admin 仅看聚合数据，学生级明细仅 super_admin/audit | ROLE_MENUS 差异（AdminLayout.tsx:13-60）+ allowedViews 守卫（:63-65） | ✅ 一致（前端守卫；后端 RBAC 双保险属板块01/05 范围） |
| DEC-007 ⑤：P0 backlog ⑤ 双轨收敛（平台总览迁 admin-web） | teacher-web Dashboard.tsx:67-70 仅保留业务管理控制台；平台域 17 视图全在 admin-web | ✅ 一致 |
| DC-005 认证传输三端收敛 | teacher-web 全量消费 shared/auth-transport（api.ts:4-9）；admin-web 独立实现（**DEC-007 R-8 有意隔离，不视为偏差**，但 tokenStorage 模块可复用而未复用，属可选优化） | ✅ 一致（含有意例外） |
| FA-15/R-001 端点单一事实源 | teacher-web 全量消费 ENDPOINTS（api.ts:12-14、84-91）；**admin-web 未接入（见 P1-1）** | ⚠️ 治理缺口（P1-1） |
| doing/83 §13.1 前端菜单双保险 | allowedViews 守卫 + 后端 403 处理（api.ts:66-72，BUG-T-RC-02 显式转 ApiError） | ✅ 一致 |
| design/11 老师端功能面 | 7 面板全实现（工作台/预警/学生/质量/通知/设备/管理），无缺页 | ✅ 一致 |

## 6. 修复建议

- **P0**：无。
- **P1 按收益排序**：①P1-1 admin-web 端点常量表（复制 teacher-web FA-15 模式，含契约测试，半日工作量，建议进入集中修复）；②P1-2 POST 鉴权失败统一（封装收敛，<40 行）；③P1-3 Dashboard 注册表（与 admin-web AD-009 风格统一，建议与 P1-1 同批做）；④P1-4 审计 DTO 收敛（依赖后端契约调整，建议并入板块05 审计专题）。
- **P2**：可选，随上述批次顺带。
- **汇总引用**：P1-4 与板块05 P0-1/P1-3（审计失效、TeacherService 残留）共享根因域——审计链路一致性，建议汇总报告归并为一个修复专题。
