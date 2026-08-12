# 管理端 Browser Agent 遍历测试问题清单

> 起始：2026-08-11 | 执行轮次：第 1 轮（UI-TEST-015 A-01~A-17）+ 复测（UI-TEST-016）
> 来源：design/15 §5.4 A 系列 + 四端全面遍历
> 环境：https://yun.gxjugu.com/admin/（super_admin/TestAdmin@2026，PLATFORM_ token）

---

## 问题总览

| 编号 | 等级 | 场景 | 标题 | 状态 |
|------|------|------|------|------|
| BUG-A-MODAL-01 | P1 | 全局 | antd Modal 关闭动画卡死（降级矩阵/告警确认/二维码签发/配置修改 4 处同根因） | **VERIFIED** ✅（2026-08-12，禁用动画修复+7 次开-关循环复测） |
| BUG-A-04-01 | P1 | 提示词中心 | 创建提示词版本 API 一律 500（平台上下文缺失） | **VERIFIED** ✅（2026-08-12，创建 200 draft→submit 200 pending_review） |
| BUG-A-12-01 | P1 | 租户管理 | 租户健康检查接口 500 | **VERIFIED** ✅（2026-08-12，health 200 返回健康数据） |
| BUG-A-12-02 | P1 | 租户管理 | 租户 provision 接口 500（事务回滚正常） | **VERIFIED** ✅（2026-08-12，provision 200 开通成功） |
| BUG-A-12-04 | P1 | 租户管理 | suspend/resume 半成功（500 但状态已变） | **VERIFIED** ✅（2026-08-12，suspend/resume 全 200，DEV 已恢复） |
| BUG-A-02-01 | P2 | 平台总览 | 租户状态恒显示「停用」（API 小写 vs 前端大写判断） | **VERIFIED** ✅（2026-08-12，3/3 active 显示绿色「启用」） |
| BUG-A-04-02 | P3 | 提示词中心 | safety-phrases 写请求返回 500（应 403/405） | OPEN（P3，排期） |
| BUG-A-03-01 | P3 | 系统配置 | 配置变更历史 UI 缺失（数据层留痕存在） | OPEN（P3，排期） |
| BUG-A-TOKEN-01 | P2 | 全局 | 过期/无效 token 解析失败落 500（应 401） | **VERIFIED** ✅（2026-08-12，401 code 20001 实测） |

## 根因与修复记录

### BUG-A-MODAL-01 [P1] antd Modal 关闭动画卡死
- 根因：antd 6.5.4 + React 19.2 CSSMotion 关闭回调不触发 → ant-zoom-leave 动画循环、wrap 永不隐藏、遮罩锁死
- 修复（05c1fe6）：ConfigPage/AlertPage/DevicePage/DegradationPage 4 处 Modal 加 `transitionName="" maskTransitionName=""` 禁用动画（无动画路径直接卸载）
- 复测：7 次打开-关闭循环全部立即关闭，可继续交互 ✅

### BUG-A-04-01/12-01/12-02/12-04 [P1] 平台接口 500 系列
- 根因（共同）：平台 token 分支不设置 TenantContext（details=null）且不标记系统作用域——controller 的 ctx.tenantId() 触发 NPE；业务表 SQL 触发 MindSafeTenantLineHandler fail-fast（IllegalStateException）
- 修复（953f3a2）：JwtAuthenticationFilter 平台分支补 `setDetails(new TenantContext(null, adminId, PLATFORM_USER_TYPE))` + `TenantContextHolder.setSystemScope(true)`
- 复测：health/provision/suspend/resume/提示词创建全 200 ✅

### BUG-A-02-01 [P2] 租户状态大小写
- 修复（05c1fe6）：OverviewPage `String(s ?? '').toUpperCase() === 'ACTIVE'`
- 复测：3/3 active 租户绿色「启用」✅

### BUG-A-TOKEN-01 [P2] 过期 token 500
- 修复（c21606b + 2152c83）：过滤器捕获解析异常继续放行 + SecurityConfig 401/403 双语义收口
- 复测：无 token/无效 token → 401；正常登录 → 200 ✅

## 受限项（登记）

- RBAC 四角色矩阵：ops/audit/finance 测试账号不可得，源码 ROLE_MENUS 确认
- A-12 租户管理 UI：17 菜单无租户管理入口（有 API 无 UI，功能缺口 P2 跟进）
- A-05 转派/SLA 倒计时 UI：只读视图
- 设备详情 Drawer/批量勾选：跨租户 0 台设备
- 截图工具故障：以 a11y 快照+DOM+网络请求为证据

---
_首轮遍历+复测完成：P1×6 全部 VERIFIED ✅，P2×2 VERIFIED ✅，P3×2 排期跟踪_
