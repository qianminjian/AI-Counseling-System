# UI-TEST-018 Browser Agent 操作日志（当前批次）

| 序号 | 端/场景 | 页面状态 | 动作 | 结果 | 截图 |
|---:|---|---|---|---|---|
| 001 | S/S-00 | 未登录首屏 | 打开 `/mindsafe/`，snapshot 识别 23 个交互控件 | 成功 | S-00-initial.png |
| 002 | T/T-01 | 登录页 | 输入李老师/密码并提交 | 登录成功 | T-01-login-result.png |
| 003 | A/A-01 | 登录页 | 输入 super_admin/密码并提交 | 登录成功，主菜单 17 项 | A-01-login-result.png |
| 004 | P/P-01 | 登录页 | 点击首次注册 | 进入家庭码注册页 | P-01-register-entry.png |
| 005 | S/S-02 | 未登录首屏 | 输入开心，按 1/2/3/4，点击进入 | 提示昵称或 PIN 码错误 | S-02-pin-filled.png、S-02-login-result.png |
| 006 | T/T-01 | 工作台引导浮层 | 点击 Close | 浮层仍存在 | T-onboarding-closed.png |
| 007 | T/T-01 | 工作台引导浮层 | 点击跳过 | 浮层仍存在 | T-onboarding-skip.png |
| 008 | T/T-02~09 | 工作台引导浮层 | 点击主菜单项目 | 页面未切换，受浮层阻断 | T-*.png |
| 009 | P/P-01 | 家庭码注册 | 输入无效家庭码/测试手机号/密码，选择妈妈 | 表单可填；提交文本非 button | P-01-invalid-filled.png |
| 010 | P/P-01 | 家庭码注册 | 点击“注册并绑定” | 页面无变化 | P-01-invalid-result.png |
| 011 | A/A-11 | 服务状态页 | 点击指标看板 | 仍为服务状态 | A-指标看板.png、A-indicators-verify.png |
| 012 | A/A-11 | 服务状态页 | 点击告警中心/审计日志/终端设备 | 均仍为服务状态 | A-告警中心.png、A-审计日志.png、A-终端设备.png |
| 013 | S/S-00 | 未登录首屏 | 点击隐私政策/服务协议 | URL、页面、弹窗均无变化 | S-00-privacy.png、S-00-service.png |

## 执行器状态

- 每个端使用独立 `agent-browser --session ui018-<端>` 会话。
- 未打开或操作被测软件以外窗口。
- 本批次未执行代码修改、数据库写入、部署或发布。
# R3 线上基线复核（2026-08-14）

| 步骤 | 端 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R3-01 | 学生端 | 独立会话打开 `/mindsafe/`，等待 networkidle，获取首屏快照 | 登录/注册、昵称输入、数字键盘、隐私/协议、声音入口均可识别 | `screenshots/R3-student-initial.png` |
| R3-02 | 教师端 | 独立会话打开 `/teacher/`，等待 networkidle，获取首屏快照 | 用户名、密码、显示密码、登录控件可识别 | `screenshots/R3-teacher-initial.png` |
| R3-03 | 家长端 | 独立会话打开 `/parent/`，等待 networkidle，获取首屏快照 | 登录/首次注册切换和登录表单可识别 | `screenshots/R3-parent-initial.png` |
| R3-04 | 管理端 | 独立会话打开 `/admin/`，等待 networkidle，获取首屏快照 | 用户名、密码、显示密码、登录控件可识别 | `screenshots/R3-admin-initial.png` |
# R4 部署后线上复测（2026-08-14）

| 步骤 | 端 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R4-01 | 学生端 | 部署后新会话打开并获取首屏快照 | 首屏可加载，登录/注册及协议控件可识别 | `screenshots/R4-student-initial.png` |
| R4-02 | 教师端 | 部署后新会话登录李老师 | 登录成功，但工作台引导仍显示；点击“跳过”后仍未关闭 | `screenshots/R4-teacher-login.png`、`screenshots/R4-teacher-skip.png` |
| R4-03 | 家长端 | 部署后新会话打开并获取首屏快照 | 首屏可加载，登录/首次注册入口可识别 | `screenshots/R4-parent-initial.png` |
| R4-04 | 管理端 | 部署后新会话登录 super_admin，点击“指标看板” | 登录成功；点击后仍显示“平台运营总览” | `screenshots/R4-admin-after-login.png`、`screenshots/R4-admin-metrics.png` |

# R5 账号状态核验（2026-08-14）

| 步骤 | 端 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R5-01 | 管理端 | 新会话登录 super_admin，检查平台菜单 | 仅显示租户/平台运维类菜单，无学生明细入口；未做写操作 | — |
| R5-02 | 教师端 | 新会话登录李老师，进入“学生管理” | 学生列表显示小明、测试己、测试丁 | — |
| R5-03 | 教师端 | 在“搜索学生昵称”输入“开心”并提交 | 列表仍无“开心”，保留上述三条学生记录 | `screenshots/R5-teacher-student-search-kx.png` |
