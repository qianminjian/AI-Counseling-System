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

# R6 统一部署后复测（2026-08-14）

| 步骤 | 端 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R6-01 | 学生端 | 统一部署命令执行后新会话打开 `/mindsafe/` | 页面可加载，登录/注册/数字键盘/协议控件可识别 | `screenshots/R6-st-initial.png` |
| R6-02 | 教师端 | 新会话打开 `/teacher/` | 登录页可加载，用户名/密码/登录控件可识别 | `screenshots/R6-t-initial.png` |
| R6-03 | 家长端 | 新会话打开 `/parent/` | 登录页可加载，登录/首次注册/手机号/密码控件可识别 | `screenshots/R6-p-initial.png` |
| R6-04 | 管理端 | 新会话打开 `/admin/` | 登录页可加载，用户名/密码/登录控件可识别 | `screenshots/R6-a-initial.png` |

## R6 部署结果

- 四端构建与四端静态资源同步成功；tts/voice rsync 阶段因远端 SSH connection reset，脚本最终 `FAILED（rsync）`，未完成后端镜像重建、服务重启和部署后 smoke gate。
- R6 仅证明四端入口可加载，不宣称本轮发布完整成功；按批次部署要求不再重复部署。

## R6 继续遍历路径

- 管理端：平台总览 → 配置注册表 → Prompt 管理 → 风险全景 → 时效监控 → 处置台账 → 降级矩阵 → 知识库 → 通知渠道 → 运营洞察 → 用量报表 → 数据合规 → 服务状态 → 指标看板 → 告警中心 → 审计日志 → 终端设备管理；每个页面均完成当前窗口控件快照和截图，未见错误弹窗或渲染崩溃。
- 教师端：工作台 → 预警队列 → 学生管理 → 质量监控 → 通知中心 → 终端设备；每个页面均完成当前窗口控件快照和截图，未见错误弹窗或渲染崩溃。
- 家长端：登录 → 首次注册 → 填写无效家庭码/手机号/密码 → 注册并绑定；显示“家庭码无效”，流程正常返回校验结果。
- 学生端：登录 → 输入“开心” → PIN 1234 → 提交；稳定显示“昵称或 PIN 码错误”，与 R4/R5 结果一致。

# R7 深层弹窗/详情遍历（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R7-01 | 管理/A-02 配置注册表 | 点击首行“历史” | 打开“变更历史”弹窗，显示“暂无变更记录”；未执行修改 | `screenshots/R7-admin-config-history.png` |
| R7-02 | 管理/A-03 Prompt 管理 | 打开页面并检查版本行、内容列、新建版本入口 | 页面显示 control 版本和新建版本按钮；未执行写入 | `screenshots/R7-admin-prompt.png` |
| R7-03 | 教师/T-03 学生管理 | 点击“测试己”链接及“查看档案”按钮 | 页面保持学生列表，未打开详情；记录为控件无可见状态变化，未重复提交 | `screenshots/R7-teacher-student-profile.png`、`screenshots/R7-teacher-student-profile-modal.png` |

# R8 学生账号状态分支（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R8-01 | 学生/S-02 冻结账号 | 输入“小明”和台账 PIN，提交登录 | 稳定显示“账号已冻结，请联系家长或学校重新授权” | `screenshots/R8-student-frozen-filled.png`、`screenshots/R8-student-frozen-result.png`、`screenshots/R8-student-frozen-final.png` |

R8 结论：冻结账号分支工作正常；“开心”仍返回通用“昵称或 PIN 码错误”，结合教师端学生列表不存在该昵称，继续归类为线上账号台账/租户数据问题，不修改登录代码。
