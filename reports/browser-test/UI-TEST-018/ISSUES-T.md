# UI-TEST-018 教师端问题清单

## BUG-T-T01-001 [P1] 首次工作台引导浮层无法关闭，阻断主菜单遍历

- 状态：CLOSED（Browser Agent 语义 ref click 误报；真实 DOM click 可关闭）

### R3 复测（2026-08-14）

- 新独立会话登录李老师后，首屏仍显示“工作台”引导浮层。
- 点击“跳过”后浮层仍存在，教师端主菜单遍历继续被阻断。
- 证据：`screenshots/R3-teacher-login.png`、`screenshots/R3-teacher-alerts.png`

### R4 部署后复测（2026-08-14）

- 四端服务重启和 nginx 健康检查通过后，重新打开教师端并登录李老师。
- 语义 ref click 复现“未关闭”，但真实 DOM button click 后浮层正常关闭，主导航可用。
- 结论：非产品缺陷；后续遍历器对 Ant Design 控件使用 DOM click 兜底。
- 证据：`screenshots/R4-teacher-login.png`、`screenshots/R4-teacher-skip.png`
- 环境：UAT `/teacher/`；李老师（psych_teacher）；Browser Agent 0.26.0；Chrome 151
- 复现：
  1. 登录成功进入工作台，截图 `screenshots/T-01-login-result.png`。
  2. 引导浮层显示标题“工作台”，提供 `Close`、`跳过`、`下一步`。
  3. 点击 `Close`，等待 500ms，浮层仍存在；截图 `screenshots/T-onboarding-closed.png`。
  4. 点击 `跳过`，等待 500ms，浮层仍存在；截图 `screenshots/T-onboarding-skip.png`。
  5. 点击“预警队列/学生管理/质量监控/通知中心/终端设备”，页面仍停留在工作台，无法进入对应页面。
- 实际：浮层未关闭，主导航点击未完成路由切换；无闪退，但功能遍历被阻断。
- 期望：Close/跳过关闭引导并恢复主页面交互；菜单可切换到对应页面。
- 影响：T-02~T-09 不能在本轮完成，需要修复或提供可关闭引导的测试配置后复测。

## BUG-T-T02-001 [P1] 绕过引导标记后教师端侧边菜单仍无法切换面板

- 状态：CLOSED（真实 DOM click 可切换；此前为测试执行器语义 ref 误报）
- 复现：在同一已登录测试会话中设置 `localStorage.mindsafe_onboarding_done=true` 并刷新，确认引导浮层已消失，截图 `T-post-onboarding-bypass.png`。
- 分别点击“预警队列”“学生管理”“质量监控”“通知中心”“终端设备”，每次等待并重新 snapshot。
- 实际：页面标题与工作台内容均不变，菜单点击未触发面板切换；截图 `T-02-alerts.png`。
- 期望：各菜单项切换到对应面板并更新标题/内容。
- 影响：教师端 T-02~T-09 主导航不可达；与引导浮层问题区分登记。
