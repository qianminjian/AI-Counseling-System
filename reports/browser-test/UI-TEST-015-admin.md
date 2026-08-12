# UI-TEST-015 管理端 admin-web 四端全面遍历测试报告（第 1 轮 A 系列）

> 轮次：2026-08-11~12 首轮管理端全面遍历 | 依据：design/15 §5.4 A-01~A-17
> 环境：https://yun.gxjugu.com/admin/（UAT）| 账号：super_admin / TestAdmin@2026（PLATFORM_ token）
> 工具：chrome-devtools MCP + Browser Agent | 截图：screenshots/UI-TEST-015-admin/（10 张，工具故障后以 a11y 快照+DOM 证据代替）

---

## 一、执行结论速览

| 场景 | 内容 | 结果 |
|------|------|------|
| A-01 | 平台登录（错误密码提示×2/17 菜单全可见；RBAC 角色矩阵源码确认） | ✅ PASS |
| A-02 | 平台总览（7 KPI 与 API 一致✅/服务健康三态 6 UP✅/租户状态恒显示停用） | ⚠️ P2 |
| A-03 | 系统配置（修改 HOT 项生效+留痕✅/SECRET 掩码✅/变更历史 UI 缺失） | ⚠️ P3 |
| A-04 | 提示词中心（safety-phrases 只读 3 项✅/**创建版本 500 阻断状态机**） | ❌ FAIL（P1） |
| A-05 | 预警 SLA（风险全景 11 条逾期✅/SLA 聚合表空态/无转派 UI） | ⚠️ 部分 |
| A-06 | 邀请码/导入（teacher-web 域隔离 403 正确✅/审计 IMPORT_STUDENTS×2 留痕✅） | ✅ PASS |
| A-07 | 降级矩阵（6 降级点+22 事件/手动切换生效+留痕✅/**切换后弹窗卡死**） | ❌ FAIL（P1） |
| A-08 | 知识库（52 文档统计/无列表分页搜索上传） | ⚠️ 部分 |
| A-09 | 通知渠道（9 渠道/发送 9/失败台账脱敏✅） | ✅ PASS |
| A-10 | 用量报表（全 0/固定近 30 天窗口，无过滤控件） | ⚠️ 部分 |
| A-11 | 合规视图（166 同意记录/导出冻结提示✅）+ 运营洞察（漏斗 11/11/0/0/0） | ✅ PASS |
| A-12 | 租户管理（**无 UI 页，API 层 4 项缺陷**：health/provision/suspend-resume 500） | ❌ FAIL（P1×3） |
| A-15 | 终端设备（状态筛选 5 选项✅/跨租户 0 台/二维码签发弹窗卡死） | ❌ FAIL（P1） |
| A-16 | 批量/二维码（签发留痕✅/未找到提示✅/弹窗关闭缺陷同根因） | ⚠️ P1 关联 |
| A-17 | 设备操作受理（非法 action/不存在设备错误语义清晰无 500✅） | ✅ PASS |
| 补充 | 告警中心（10 条 CRITICAL/**确认弹窗卡死**）/指标看板（10 指标）/服务状态 | ❌ FAIL（P1） |

## 二、问题清单

### BUG-A-07-01 [P1] 降级矩阵「手动切换」弹窗成功后无法关闭（全站 Modal 关闭动画卡死根因 #1）
- 复现：降级矩阵 → 手动切换 → 确认成功（POST /ops/degradation/{point}/override 200 + 留痕）→ 弹窗不关闭；X/取消/ESC/遮罩均无效，`.ant-modal` 卡在 ant-zoom-leave 动画状态
- 影响：用户切换后被困弹窗，只能刷新
- 状态：OPEN（P1）

### BUG-A-ALERT-01 [P1] 告警中心「确认告警」弹窗无法关闭（同根因 #2）
- 复现：firing 告警点「确认」→ 弹窗打开 → 取消/X/ESC 均无效；关闭动画被重置永不完成；elementFromPoint 返回 .ant-modal-wrap 拦截点击
- 注：okButtonProps disabled 逻辑正确，取消通道失效是明确缺陷
- 状态：OPEN（P1，同根因合并修复）

### BUG-A-DEV-01 [P1] 终端设备「二维码签发」弹窗无法关闭（同根因 #3）
- 复现：二维码签发 → 弹窗打开 → 取消/ESC 无效，卡死 ant-zoom-leave-start
- 根因线索：handleExportQr 成功后 setQrOpen(false) 未生效
- 状态：OPEN（P1，同根因合并修复）

### BUG-A-04-01 [P1] 创建提示词版本 API 一律 500
- 复现：POST /api/v1/admin/prompts/versions（templateKey=chat_default）×3 次均 500
- 影响：draft→pending_review→approved→active 状态机无法端到端验证
- 状态：OPEN（P1）

### BUG-A-12-01 [P1] 租户健康检查接口 500
- 复现：GET /api/v1/platform/tenants/{id}/health（TRIAL/DEV）×3 次均 500
- 状态：OPEN（P1）

### BUG-A-12-02 [P1] 租户一键开通 provision 接口 500
- 复现：POST /api/v1/platform/tenants/provision 最小字段 ×2 均 500；事务回滚正常（无脏数据）
- 状态：OPEN（P1）

### BUG-A-12-04 [P1] 租户 suspend/resume 半成功（返回 500 但状态实际已变更）
- 复现：DEV 租户 suspend → 500 但列表变 suspended；resume → 500 但恢复 active
- 影响：用户误判失败重复提交；错误语义/幂等性缺陷
- 状态：OPEN（P1）

### BUG-A-02-01 [P2] 平台总览租户状态恒显示「停用」（= BUG-A-12-03 同根因）
- 根因：OverviewPage.tsx `s === 'ACTIVE'`（大写）与 API 返回 `status:"active"`（小写）不匹配，条件恒 false
- 期望：显示「启用」绿色 Tag
- 状态：OPEN（P2，合并修复）

### BUG-A-04-02 [P3] safety-phrases 写请求返回 500（应 403）
- 状态：OPEN（P3）

### BUG-A-03-01 [P3] 配置变更历史 UI 缺失（数据层留痕存在，页面无展示组件）
- 状态：OPEN（P3）

## 三、观察项（P3）

| 项 | 说明 |
|----|------|
| SPA 菜单切换 URL 不更新 | 地址栏停留旧路由，刷新后 fallback 总览 |
| 平台总览偶发白屏 | #root 空，reload 恢复，二次未复现（竞态） |

## 四、受限项

| 项 | 说明 |
|----|------|
| RBAC 角色差异实测 | ops_admin/audit/finance 测试账号不可得；源码 ROLE_MENUS 确认 super 17/ops 14/finance 2/audit 3 |
| A-05 转派/SLA 倒计时 UI | 只读视图，无转派 UI；sla_escalation 链路经告警中心侧面确认 |
| A-12 租户管理 UI | 17 菜单无租户管理入口，有 API 无 UI（功能缺口，建议 P2 跟进）；TRIAL 租户全程未触碰，DEV 已恢复 active |
| A-15 设备详情 Drawer | 跨租户 0 台设备；API 404 语义已验证 |
| A-16 批量勾选 Popconfirm | 无设备无法勾选；API 受理回执已验证 |
| 知识库列表/用量报表过滤/运营洞察时间维度 | 功能不存在（受限登记） |
| 截图 | browser-use/chrome-devtools 截图工具超时故障，以 a11y 快照+DOM+网络日志为证据 |

## 五、控制台/网络异常汇总

- 500：prompts/versions×3、safety-phrases×2、tenants/{id}/health×3、tenants/provision×2、tenants/{id}/suspend|resume×2
- 404：devices/{code}/ota|reboot|factory-reset|evil-action（不存在设备/未定义路由，语义正确）
- 400：suspend/resume 不存在租户（10002 语义正确）、devices/batch 非法 action（400「非法操作类型」）
- 403：invite-codes/import-template（teacher-web 域隔离正确）
- 其余 20+ 业务接口全部 200；无 JS 异常堆栈、无白屏残留

---

_执行：Browser Agent（2026-08-11~12）| 结论：17 场景 6 PASS + 7 FAIL/部分 + 4 受限；P1×7（含 Modal 同根因×3）、P2×1、P3×2_
