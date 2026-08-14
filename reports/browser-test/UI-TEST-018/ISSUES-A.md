# UI-TEST-018 管理端问题清单

## BUG-A-A11-001 [P1] 指标看板菜单点击后仍停留在服务状态

- 状态：CLOSED（真实 DOM click 可切换；此前为测试执行器语义 ref 误报）

### R3 复测（2026-08-14）

- 新独立会话登录 super_admin 后点击“指标看板”。
- 页面标题仍为“平台运营总览”，菜单未切换到指标看板。
- 证据：`screenshots/R3-admin-login.png`、`screenshots/R3-admin-metrics.png`

### R4 部署后复测（2026-08-14）

- 四端服务重启和 nginx 健康检查通过后，重新打开管理端并登录 super_admin。
- 语义 ref click 复现“未切换”，但真实 DOM click 后标题切换为“指标看板”。
- 结论：非产品缺陷；后续遍历器对 Ant Design Menu 使用 DOM click 兜底。
- 证据：`screenshots/R4-admin-after-login.png`、`screenshots/R4-admin-metrics.png`
- 环境：UAT；`/admin/`；super_admin；Browser Agent 0.26.0；Chrome 151
- 场景：A-11 运营洞察/指标看板入口遍历
- 前置：已成功登录管理端，当前页面标题为“服务状态”。
- 复现步骤：
  1. 在左侧菜单找到“指标看板”。截图：`screenshots/A-指标看板.png`。
  2. 点击“指标看板”，等待 networkidle。
  3. 重新获取交互快照并检查页面标题。
  4. 重复点击一次进行复现，截图：`screenshots/A-indicators-verify.png`。
- 实际：两次点击后页面标题仍为“服务状态”，内容未进入指标看板；Browser Agent 未捕获 JS 错误，但功能入口不可达。
- 期望：点击“指标看板”后进入指标看板页面，标题、内容和 URL/路由与菜单语义一致。
- 影响：管理端指标看板功能无法通过主导航访问，A-11 不可完成。
- 建议排查：确认菜单项路由映射、权限路由和点击事件绑定；修复后重新执行 A-11，并回归所有管理端菜单。

## BUG-A-A11-002 [P1] 告警中心/审计日志/终端设备菜单点击后仍停留在服务状态

- 状态：CLOSED（真实 DOM click 可切换；此前为测试执行器语义 ref 误报）
- 复现：在已登录管理端、当前页面为“服务状态”时，分别点击“告警中心”“审计日志”“终端设备”，每次等待并重新 snapshot；三次页面标题均仍为“服务状态”。
- 证据：`screenshots/A-告警中心.png`、`screenshots/A-审计日志.png`、`screenshots/A-终端设备.png`。
- 期望：三项菜单分别进入对应页面，标题和内容与菜单语义一致。
- 影响：管理端三项核心功能入口不可达。
