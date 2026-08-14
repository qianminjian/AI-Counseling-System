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

## R18/R19（2026-08-14，批次继续）

| 编号 | 端/场景 | 页面状态 | 动作 | 结果 | 截图 |
|---|---|---|---|---|---|
| R18-01 | S/S-02~04 | 登录页/首页 | 使用“测试丁”输入 PIN 1234 登录，跳过引导 | 登录成功进入 active 首页 | `screenshots/R18-student-testding-login.png`、`screenshots/R18-student-testding-result.png` |
| R18-02 | S/S-03~04 | 首页/聊天 | 选择“开心”并开始聊天；遍历语音说明弹窗并选择“暂不使用” | 进入聊天页，弹窗控件可操作 | `screenshots/R18-student-emotion-happy.png`、`screenshots/R18-student-chat.png`、`screenshots/R18-student-chat-voice-dismissed.png` |
| R18-03 | S/S-04/07/10 | 聊天页/退出弹窗 | 输入并发送文本，点击结束，遍历退出确认并确认退出 | 页面保持可操作；确认后返回登录页 | `screenshots/R18-student-chat-message.png`、`screenshots/R18-student-chat-response.png`、`screenshots/R18-student-chat-ended.png`、`screenshots/R18-student-after-logout.png` |
| R19-01 | P/P-03/P-06 | 家长首页 | 使用既有演示账号登录并查看情绪周报 | 登录成功，展示孩子“开心”的周报统计与建议 | `screenshots/R19-parent-login-result.png`、`screenshots/R19-parent-report.png` |
| R19-02 | P/P-04 | 数据授权管理 | 进入授权管理并打开“开心”详情 | 识别“撤回授权”控件；未点击不可逆撤回操作 | `screenshots/R19-parent-consent.png`、`screenshots/R19-parent-consent-detail.png` |
| R20-01 | S/S-06 | 放松练习列表 | 打开练习列表，进入 3-2-3 呼吸法，关闭语音引导并提前结束 | 5 个练习入口可识别；练习页控件可操作并可返回 | `screenshots/R20-student-relax-ready.png`、`screenshots/R20-student-breathing.png`、`screenshots/R20-student-breathing-voice-off.png`、`screenshots/R20-student-relax-return.png` |
| R20-02 | S/S-08 | 情绪日记 | 选择开心、填写备注、提交记录 | 表单控件和提交按钮可操作；页面无崩溃/报错弹窗 | `screenshots/R20-student-diary.png`、`screenshots/R20-student-diary-filled.png`、`screenshots/R20-student-diary-result.png` |
| R20-03 | S/S-08 | 成就/设置 | 打开成就入口，随后打开设置，遍历主题、声音、语音播报、语音唤醒、动效、触觉等控件 | 设置面板完整渲染；主题和开关可切换，完成按钮可返回 | `screenshots/R20-student-achievements.png`、`screenshots/R20-student-settings.png`、`screenshots/R20-student-settings-toggles.png`、`screenshots/R20-student-settings-complete.png` |
| R21-01 | T/T-09 | 终端设备 | 进入终端设备，点击刷新、展开班级筛选、识别查询输入框及绑定设备入口 | 页面正常渲染；当前无设备数据，未点击绑定设备 | `screenshots/R21-teacher-devices.png`、`screenshots/R21-teacher-device-class-options.png` |
| R21-02 | T/T-05 | 质量监控 | 进入质量监控页 | 平均评分、近 7 天评分、低分会话、趋势区域正常渲染；未发现异常弹窗 | `screenshots/R21-teacher-quality.png` |
| R22-01 | A/A-01/A-04 | 平台总览/Prompt 管理 | 登录 super_admin，进入总览和 Prompt 管理，识别版本表及新建版本入口 | 租户总览、Prompt 版本列表正常；未点击新建版本或审核/激活操作 | `screenshots/R22-admin-overview.png`、`screenshots/R22-admin-prompts.png`、`screenshots/R22-admin-prompt-detail.png` |
| R22-02 | A/A-08 | 知识库/通知渠道 | 依次进入知识库和通知渠道 | 两个页面均正常加载并显示标题；未执行审核、发送等写操作 | `screenshots/R22-admin-knowledge.png`、`screenshots/R22-admin-notifications.png` |
| R22-03 | A/A-09/A-10 | 用量报表/数据合规 | 依次进入用量报表和数据合规中心 | 页面入口正常加载；未执行导出或审批 | `screenshots/R22-admin-usage.png`、`screenshots/R22-admin-compliance.png` |
| R23-01 | S/S-05 | 学生风险识别 | 测试丁进入聊天，发送模拟危机语句并等待 45 秒；打开 SOS 面板 | SOS 面板可打开并显示 12355；消息请求在响应头阶段无结果，形成 P1 问题 | `screenshots/R23-student-risk-filled.png`、`screenshots/R23-student-risk-result.png`、`screenshots/R23-student-risk-timeout.png`、`screenshots/R23-student-sos.png` |
| R23-02 | T/T-03/L-05 | 教师预警联动 | 重新登录李老师，进入预警队列 | 出现测试丁 `sos_open` 黄色待处理预警，另有既有红色自伤/自杀记录；未点击认领/处理/误报 | `screenshots/R23-teacher-alert-created.png`、`screenshots/R23-teacher-alert-queue.png` |
| POST-01 | S/T/P/A | 发布后入口复测 | 统一部署后分别打开四端 UAT 地址并等待 networkidle | 四端入口均返回 200、首屏可识别并保存截图；学生登录后续会话曾无响应，未将其标记为通过 | `screenshots/POST-student-entry.png`、`screenshots/POST-teacher-entry.png`、`screenshots/POST-parent-entry.png`、`screenshots/POST-admin-entry.png`、`screenshots/POST-student-login-result.png` |
| POST-02 | S/S-02~05 | 新独立会话复测 | 两个新会话提交测试丁 PIN，等待登录状态并尝试 snapshot/screenshot | 两次均在登录后的 Browser Agent 状态读取阶段无响应，未取得足够证据，不判定产品通过/失败 | `screenshots/POST-student-login-result.png` |
| POST-03 | S/S-03~04 | 懒加载修复后复测 | 新会话登录测试丁，跳过引导，选择开心并开始聊天 | 登录后首页可正常 snapshot；创建聊天请求长时间 pending，页面状态读取无交互元素 | `screenshots/POST4-student-home.png`、`screenshots/POST4-student-chat-state.png` |

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

# R9 教师端筛选控件遍历（2026-08-14）

| 步骤 | 场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R9-01 | T-04 质量监控 | 打开质量监控页并识别页面控件 | 展示近 7 天/30 天质量指标，无错误弹窗或崩溃 | `screenshots/R9-teacher-quality.png` |
| R9-02 | T-05 通知中心 | 查看“全部”列表 | 显示通知列表及多个“已读”按钮 | `screenshots/R9-teacher-notifications.png` |
| R9-03 | T-05 通知中心 | 点击“未读”Tab；语义点击无状态变化后对当前 Segmented 控件执行 DOM click | 切换成功，列表变为橙色预警通知 | `screenshots/R9-teacher-notifications-unread-dom.png` |
| R9-04 | T-05 通知中心 | 点击“已读”Tab 的当前页面控件 DOM click | 切换成功，列表显示红色预警通知 | `screenshots/R9-teacher-notifications-read-tab.png` |

R9 结论：教师端通知中心 Tab 工作正常；初次语义 ref 点击无变化属于控件触发器兼容性问题，DOM 兜底后状态正确切换，未登记为产品缺陷。

# R10 管理端筛选与审计遍历（2026-08-14）

| 步骤 | 场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R10-01 | A-14 指标看板 | 打开页面并识别控件 | 页面加载成功，显示指标看板标题，未见异常弹窗 | `screenshots/R10-admin-metrics.png` |
| R10-02 | A-15 告警中心 | 打开页面并识别表格、分页、确认按钮 | 告警表格加载，分页 1–5/10、下一页及多个确认按钮可识别；未点击确认 | `screenshots/R10-admin-alerts.png` |
| R10-03 | A-15 告警中心 | 打开状态筛选 | 展示 firing/resolved/ack/closed 选项 | `screenshots/R10-admin-alert-status-options.png` |
| R10-04 | A-15 告警中心 | 选择 resolved | 页面仍显示 firing 数据，筛选视觉状态未形成明确结果，待后续用 DOM 控件验证 | `screenshots/R10-admin-alert-resolved-filter.png` |
| R10-05 | A-16 审计日志 | 打开页面、点击首条审计行 | 审计表格和分页正常；点击行无可见详情状态 | `screenshots/R10-admin-audit.png`、`screenshots/R10-admin-audit-detail.png` |

R10 结论：管理端筛选与审计页面可加载；告警 resolved 筛选和审计行详情的语义触发结果需继续确认，当前不升级为产品缺陷。

# R11 家长端登录与 Tab 遍历（2026-08-14）

| 步骤 | 场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R11-01 | P-01 登录 | 不填手机号/密码，提交 | 提示“请输入正确的 11 位手机号” | `screenshots/R11-parent-login-empty.png` |
| R11-02 | P-01 登录 | 填写合法格式手机号和错误密码，提交 | 提示“手机号或密码错误” | `screenshots/R11-parent-login-wrong.png` |
| R11-03 | P-01 Tab | 切换“首次注册” | 进入家庭码、手机号、密码、关系选择和注册按钮页面 | `screenshots/R11-parent-register-tab.png` |
| R11-04 | P-01 Tab | 切回“登录” | 返回登录表单，Tab 切换正常 | `screenshots/R11-parent-login-tab.png` |

R11 结论：家长端登录校验和双向 Tab 切换正常；未使用真实家庭码、未创建账号。

# R12 教师端角色分支（2026-08-14）

| 步骤 | 场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R12-01 | T-01 王老师登录 | 使用王老师账号登录 | 登录成功，主菜单包含工作台/预警队列/学生管理/质量监控/通知中心/终端设备 | `screenshots/R12-teacher-wang-dashboard.png` |
| R12-02 | T-05 王老师通知中心 | 打开通知中心 | 显示通知中心，未读数量为 1 | `screenshots/R12-teacher-wang-notifications.png` |
| R12-03 | T-05 王老师通知中心 | 切换“未读”Tab（DOM 兜底） | Tab 切换成功，显示未读列表 | `screenshots/R12-teacher-wang-unread.png` |

R12 结论：王老师角色登录和菜单权限正常；与李老师相比通知数量按账号隔离，未发现越权或渲染异常。

# R13 学生端公共入口与弹窗（2026-08-14）

| 步骤 | 场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R13-01 | S-00 主题 | 点击海洋主题按钮 | 页面保持可用，主题状态截图留存 | `screenshots/R13-student-theme-wave.png` |
| R13-02 | S-00 声音进入 | 点击声音进入并等待 1 秒 | 页面仍停留登录首屏；控制台仅见声纹/唤醒模型加载日志及模型类型 warning，无 JS error | `screenshots/R13-student-voice.png` |
| R13-03 | S-00 隐私政策 | 点击隐私政策（DOM 兜底） | 打开隐私政策与服务协议弹窗，含四个章节和“我知道了”按钮 | `screenshots/R13-student-privacy-dom.png` |
| R13-04 | S-00 服务协议 | 关闭弹窗后点击服务协议（DOM 兜底） | 打开同一条款弹窗，关闭回退正常 | `screenshots/R13-student-service-dom.png` |

R13 结论：学生端公共弹窗和主题入口可用；声音入口触发后需等待模型加载，当前无崩溃证据。

# R14 管理端设备与分页（2026-08-14）

| 步骤 | 场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R14-01 | A-17 终端设备 | 打开状态筛选 | 识别全部状态、已绑定在线、待绑定、离线、未激活选项；批量升级/恢复出厂按钮禁用 | `screenshots/R14-admin-devices-status.png` |
| R14-02 | A-17 终端设备 | 选择“待绑定” | 状态筛选更新为“待绑定”，列表显示暂无数据 | `screenshots/R14-admin-devices-unbound.png` |
| R14-03 | A-16 审计日志 | 点击第 2 页（DOM 兜底） | 分页成功，显示不同日期及 PIN_LOGIN 记录 | `screenshots/R14-admin-audit-page2-dom.png` |

R14 结论：终端设备状态筛选和审计日志分页正常；无设备数据时批量操作保持禁用，未执行写操作。

# R15 教师端预警队列筛选入口（2026-08-14）

| 步骤 | 场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R15-01 | T-02 预警队列 | 打开预警队列 | 表格加载 1 条红色预警，显示时间、等级、SLA、学生、类型、状态、处理/误报按钮及分页 | `screenshots/R15-teacher-alert-status-options.png` |
| R15-02 | T-02 预警队列 | 通过 DOM 事件展开“状态筛选” | 展示待处理、已认领、已解决、误报选项 | `screenshots/R16-teacher-alert-resolved-filter-dom.png` |
| R15-03 | T-02 预警队列 | 通过 DOM 事件展开“最低等级” | 展示黄色及以上、橙色及以上、仅红色选项 | `screenshots/R16-teacher-alert-level-dom.png` |
| R15-04 | T-02 预警队列 | 选择“已解决”+“仅红色” | 两个筛选值更新，结果为空；未执行处理/误报写操作 | `screenshots/R16-teacher-alert-red-filter-dom.png` |

R15/R16 结论：预警队列表格和两个筛选控件正常；首次语义 click 未展开属于控件触发兼容性问题，DOM 事件后功能正常，不登记为产品缺陷。

# R17 家长端注册关系与密码边界（2026-08-14）

| 步骤 | 场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R17-01 | P-01 注册关系 | 选择“爸爸” | 关系按钮状态切换 | `screenshots/R17-parent-father.png` |
| R17-02 | P-01 注册关系 | 选择“其他” | 关系按钮状态切换 | `screenshots/R17-parent-other.png` |
| R17-03 | P-01 注册边界 | 输入 3 位家庭码、合法格式手机号、3 位短密码并提交 | 正确提示“密码至少 6 位”，未发起有效注册 | `screenshots/R17-parent-short-validation.png` |

R17 结论：家长端关系选择和密码长度边界正常；未使用真实家庭码、不创建账号。

# R24 单会话串行补充遍历（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R24-01 | 教师/T-01 | 李老师登录并识别首次引导弹窗 | 登录成功；引导包含 Close/跳过/下一步；截图留存 | `screenshots/R24-teacher-home.png`、`screenshots/R24-teacher-tour-2.png` |
| R24-02 | 教师/T-02/T-03 | 关闭引导后进入预警队列，识别筛选、表格、认领/处理/误报控件 | 队列显示测试丁黄色 `sos_open`；认领成功；处理弹窗可输入干预记录并确认，状态更新为已处理 | `screenshots/R24-teacher-alert-queue.png`、`screenshots/R24-teacher-alert-process-dialog.png`、`screenshots/R24-teacher-alert-processed.png` |
| R24-03 | 教师/T-04 | 进入学生管理、打开测试丁档案、添加观察备注 | 档案显示风险、近期会话和备注；测试备注添加成功 | `screenshots/R24-teacher-students.png`、`screenshots/R24-teacher-student-profile.png`、`screenshots/R24-teacher-note-added.png` |
| R24-04 | 教师/T-05/T-09 | 进入质量监控、通知中心、终端设备；识别 Tab、筛选、刷新、绑定设备控件 | 页面均可加载；通知 Tab 和绑定设备弹窗可遍历；未提交真实设备绑定 | `screenshots/R24-teacher-quality.png`、`screenshots/R24-teacher-notifications.png`、`screenshots/R24-teacher-bind-dialog.png` |
| R24-05 | 家长/P-01 | 打开首次注册，遍历家庭码、手机号、密码和关系选择；输入无效边界 | 表单控件可用；关系选择可切换；未使用真实家庭码、未创建账号 | `screenshots/R24-parent-entry.png`、`screenshots/R24-parent-register.png`、`screenshots/R24-parent-register-error.png` |
| R24-06 | 管理/A-01/A-02 | super_admin 登录并识别平台总览、租户列表、主菜单 | 登录成功；平台总览和租户表格正常加载 | `screenshots/R24-admin-entry.png`、`screenshots/R24-admin-home.png` |
| R24-07 | 管理/A-03/A-04 | 进入配置注册表和 Prompt 管理；打开新建 Prompt 版本弹窗 | SECRET 值保持掩码；配置修改/历史和 Prompt 新建控件可识别；未创建新版本 | `screenshots/R24-admin-config.png`、`screenshots/R24-admin-prompt.png`、`screenshots/R24-admin-prompt-new.png` |

R24 结论：本轮采用单 Browser Agent 会话串行遍历；教师端补齐预警处置、学生档案备注、质量监控、通知中心和设备绑定弹窗；家长端补齐注册边界；管理端补齐配置和 Prompt 弹窗入口。真实家庭码、设备绑定、Prompt 创建等依赖数据或产生持久副作用的动作仍未执行。

# R26 管理端 17 菜单 BFS 补充（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R26-01 | 管理/A-01 | super_admin 登录并保存平台总览 | 登录成功，平台总览和租户表格加载 | `screenshots/BFS-A-01-overview.png` |
| R26-02 | 管理/A-03~A-17 | 按侧边菜单顺序访问配置注册表、Prompt、风险全景、时效监控、处置台账、降级矩阵、知识库、通知渠道、运营洞察、用量报表、数据合规、服务状态、指标看板、告警中心、审计日志、终端设备 | 16 个页面均可加载并保存截图；识别到筛选、分页、配置修改/历史、Prompt 新建、降级手动切换、告警确认、二维码签发、批量升级、恢复出厂等控件；未提交持久副作用 | `screenshots/BFS-A-02-配置注册表.png` 至 `screenshots/BFS-A-17-终端设备.png` |

R26 结论：管理端一级菜单可达性和窗口状态遍历完成；写入/高风险操作保留为“未执行（副作用）”，需独立测试数据和回滚方案后再执行。

# R27 家长端有效账号复测停止点（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R27-01 | 家长/P-02 | 打开家长端登录页，输入项目测试资产定义的有效手机号和密码 | 登录页初始渲染正常，表单控件可识别；截图留存 | `screenshots/BFS-P-login.png` |
| R27-02 | 家长/P-02 | 使用真实键盘事件替换手机号/密码并提交 | Browser Agent 在输入事件阶段无响应，Chrome 渲染进程 CPU 持续高占用；按超时规则停止并清理会话，未确认登录结果 | `screenshots/BFS-P-login.png` |

R27 结论：记录为 `OBS-P-POST-001` 待复核；尚不能区分 Taro H5 输入事件、浏览器自动化兼容性或页面运行时卡死。未修改源码、未再次部署。

# R28 家长端输入事件复核（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R28-01 | 家长/P-02 | 新会话打开登录页，使用 DOM value setter + input/change 事件设置测试账号 | 页面保持渲染；Taro 自定义控件 accessibility value 与 DOM value 显示不完全一致 | `screenshots/BFS-P-valid-r2.png` |
| R28-02 | 家长/P-02 | 通过 Taro 自定义按钮、表单元素和坐标点击分别提交 | 未捕获 `/api/v1/parent/auth/login` 请求，未取得登录结果；未再次触发卡死 | `screenshots/BFS-P-valid-r2.png` |

R28 结论：与历史报告 `UI-TEST-014-parent.md` 的真实登录成功证据交叉后，当前优先归类为 Browser Agent/Taro H5 事件注入兼容性待复核，不进入源码修复或部署批次。

# R29 学生端当前账号状态复核（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图/网络证据 |
|---:|---|---|---|---|
| R29-01 | 学生/S-02 | 使用测试丁 + PIN 1234 登录 | `/api/v1/auth/pin-login` 返回 401，页面显示“昵称或 PIN 码错误” | `screenshots/BFS-S-login-current-failure.png` |
| R29-02 | 学生/S-02 | 使用开心 + PIN 1234 登录 | 同样返回 401；未进入首页，无法执行会话结束评价 | 同上；Browser Agent network 记录两次 401 |

R29 结论：与 R25 部署后测试丁登录成功的证据矛盾，当前优先归类为 UAT 账号/PIN 或租户状态发生变化；不修改认证代码、不重复部署，后续需恢复有效 active 测试账号后继续 S-04/S-07。

# R30 新学生账号与会话评价闭环（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R30-01 | 学生/S-01 | 使用 DEMO2026 走告知同意、邀请码、昵称、性别、年龄、确认注册 | 注册确认弹窗和注册成功页正常；家庭码由页面生成，未在报告明文记录 | `screenshots/BFS-S-new-registration.png`、`screenshots/BFS-S-registration-success.png` |
| R30-02 | 学生/S-02 | 设置 4 位 PIN，跳过声纹录入 | PIN 设置确认和声纹“以后再说”分支正常 | `screenshots/BFS-S-pin-success.png`、`screenshots/BFS-S-pin-confirmed.png` |
| R30-03 | 学生/S-04 | 登录新账号、跳过引导、选择开心、进入聊天、发送普通文本 | 首页和聊天窗口正常；AI 回复成功返回 | `screenshots/BFS-S-new-login.png`、`screenshots/BFS-S-chat-message.png`、`screenshots/BFS-S-chat-response.png` |
| R30-04 | 学生/S-07 | 点击结束，选择“挺好的”并提交评价 | 结束评价弹窗控件完整；提交后返回情绪选择首页 | `screenshots/BFS-S-end-dialog.png`、`screenshots/BFS-S-evaluation-submitted.png` |

R30 结论：学生端注册、登录、基础对话和会话结束评价闭环通过；旧测试丁/开心账号的 401 仍保留为环境数据观察项，不影响新测试账号闭环证据。

# R31 家长端新家庭码注册与授权只读闭环（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R31-01 | 家长/P-01 | 使用 R30 页面生成的家庭码、新虚构手机号、首次密码和“妈妈”关系提交注册 | 注册成功并自动进入家长周报首页 | `screenshots/BFS-P-registration-result.png` |
| R31-02 | 家长/P-03 | 查看本周统计、建议和无对话空态 | 周报首页正常显示统计周期、状态、建议和空态 | `screenshots/BFS-P-registration-result.png` |
| R31-03 | 家长/P-04 | 打开数据授权管理并选择新绑定孩子 | 显示已授权状态、授权时间和政策版本；撤回按钮可见但未点击 | `screenshots/BFS-P-authorization.png`、`screenshots/BFS-P-child-authorization.png` |

R31 结论：新家庭码注册、家长自动登录、周报首页和授权详情只读闭环通过；撤回授权保持未执行。

# R32 学生风险硬规则→教师预警联动复核（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图/网络证据 |
|---:|---|---|---|---|
| R32-01 | 学生/S-05 | 新 active 学生进入“难过”会话，发送“我现在很难受，想伤害自己，需要帮助” | 会话与消息接口均 200；AI 返回安全安抚并表示请老师帮助 | `screenshots/BFS-S-risk-message.png`、`screenshots/BFS-S-risk-response.png` |
| R32-02 | 教师/T-02 | 李老师刷新预警队列并查看最新事件 | 队列未出现“自动化复测”新事件；仅显示历史测试丁事件 | `screenshots/BFS-T-risk-queue.png` |
| R32-03 | 后端证据 | 检查对应会话日志 | 仅见语义分类超时并降级纯硬规则，未见该会话风险融合/风险事件落库日志 | 后端日志 sessionId 已脱敏记录于本轮工作记录 |

R32 结论：确认 `想伤害自己` 未被硬规则识别；语义层超时后未形成教师预警，属于安全链路缺陷。已按 TDD 补充红色硬规则回归测试并修复词表，纳入下一次四端统一部署与复测批次。

# R33 统一部署后风险识别与教师可见性复测（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图/网络证据 |
|---:|---|---|---|---|
| R33-01 | 学生/S-01/S-02/S-05 | 新建唯一学生账号、设置 PIN、登录、选择难过并发送“我现在很难受，想伤害自己，需要帮助” | 注册登录成功；AI 返回安全响应，明确告知已通知老师并给出 110/120/400-161-9995 求助信息 | `screenshots/BFS-S-postdeploy-registration.png`、`screenshots/BFS-S-postdeploy-risk-response.png` |
| R33-02 | 后端风险链路 | 检查该会话服务日志 | `level=RED`、score=96、`风险事件已持久化`、进入 RED 安全响应模式；修复生效 | 后端日志以 sessionId/riskEventId 关联，未在报告明文输出凭据 |
| R33-03 | 教师/T-02 | 李老师登录后查询预警队列 API/页面 | 当前仅返回历史测试丁两条事件，新学生事件未出现在李老师当前队列；页面无崩溃或错误弹窗 | `screenshots/BFS-T-postdeploy-risk-queue.png` |

R33 结论：学生硬规则识别、风险落库和安全回复已通过；教师端可见性仍待租户归属/教师授权数据核验，暂不将其判定为源码回归。Browser Agent 已关闭，未残留测试浏览器进程。

# R34 教师可见学生范围只读核验（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图/网络证据 |
|---:|---|---|---|---|
| R34-01 | 教师/T-04 | 李老师调用当前教师学生列表，按新学生昵称搜索 | 返回空列表；全量可见列表仅有小明、测试己、测试丁，未包含新注册试用学生 | `screenshots/BFS-T-postdeploy-students-scope.png` |
| R34-02 | 管理/A-01 | super_admin 只读查看平台总览 | 平台显示 3 个租户、2 个学生；未执行租户开通、暂停、恢复等写操作 | `screenshots/BFS-A-postdeploy-overview.png` |

R34 结论：新注册学生不在李老师当前教师租户/班级可见范围内，解释了风险事件落库但教师队列不可见；本轮不修改租户关系或生产数据。教师预警源码暂不判缺陷，需明确测试租户绑定后再做联动验收。

# R35 管理端只读深层页面补充遍历（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R35-01 | 管理/A-09 | 进入用量报表 | 显示 llm_call、active_student_snapshot、tts_call、asr_call 指标和近 30 天统计窗口；无可交互控件、无异常 | `screenshots/BFS-A-usage-deep.png` |
| R35-02 | 管理/A-10 | 进入数据合规中心 | 显示告知同意总数、近 7 天新增、guardian/trial/withdrawal 分布；导出审批明确冻结，未执行写操作 | `screenshots/BFS-A-compliance-deep.png` |
| R35-03 | 管理/A-11 | 进入运营洞察 | 显示检出/通知/认领/处置/闭环统计、7 天趋势和租户健康度；确认 TRIAL 租户存在 36 个事件，DEV 租户 2 个事件 | `screenshots/BFS-A-insights-deep.png` |
| R35-04 | 管理/A-03 | 进入风险全景 | 页面加载成功并保存截图，无报错弹窗或渲染异常 | `screenshots/BFS-A-risk-deep.png` |

R35 结论：管理端用量、合规、运营洞察和风险全景只读场景补充通过；运营洞察进一步证明新学生风险事件属于 TRIAL，而李老师当前可见数据属于 DEV，R34 的不可见性为租户隔离预期。

# R36 教师大屏与学生声音登录安全分支（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R36-01 | 教师/T-07 | 王老师登录后访问大屏入口 `/bigscreen` | 聚合脱敏大屏加载成功：会话、活跃学生、待处理预警、满意度、趋势、情绪/风险分布和班级对比均可读，无个案明细 | `screenshots/BFS-T-bigscreen-root.png` |
| R36-02 | 教师/T-07 | 点击“返回工作台” | 首次语义点击未触发路由；使用真实 DOM 事件后返回 `/teacher/`，页面正常，归类为自动化事件注入兼容性观察 | `screenshots/BFS-T-bigscreen.png` |
| R36-03 | 学生/S-09 | 未配置声纹的登录页点击“声音进入” | 显示“还没录过你的声音哦”，引导先用 PIN 登录再到设置录音；点击“知道啦”关闭，未申请/启用麦克风 | `screenshots/BFS-S-voice-login-overlay.png` |

R36 结论：教师大屏和学生声纹未配置安全降级分支通过；未执行真实声纹录入、麦克风授权或声纹登录。

# R37 家长既有有效账号授权只读与单孩边界（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R37-01 | 家长/P-03/P-04 | 使用既有有效家长账号登录周报 | 登录成功，显示周期、本周对话、轮次、状态、情绪分布和建议 | `screenshots/BFS-P-existing-report.png` |
| R37-02 | 家长/P-04 | 进入数据授权管理 | 显示授权说明、选择孩子控件；当前账号仅绑定一个孩子“开心” | `screenshots/BFS-P-existing-consent-2.png` |
| R37-03 | 家长/P-04 | 打开孩子授权详情 | 显示已授权、授权时间、政策版本 v1.0；撤回按钮可识别但未点击 | `screenshots/BFS-P-existing-child-detail.png` |

R37 结论：既有家长账号的周报、授权管理和孩子详情只读路径通过；多孩切换无法执行，因为当前账号只有一个绑定孩子，撤回授权按不可逆操作边界保持未执行。

# R38 家长公开隐私页与无效设备扫码安全分支（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R38-01 | 家长/P-06 | 未登录访问 `/parent/privacy` | 公开隐私页正常加载，展示收集范围、使用方式、未成年人保护和家长权利；无交互异常 | `screenshots/BFS-P-privacy-public.png` |
| R38-02 | 家长/P-07 | 访问无效设备二维码路由 `/parent/p/1/INVALIDDEVICE000` | 显示“未找到该设备，请核对机身二维码，或联系学校管理员”；无绑定控件、无写请求 | `screenshots/BFS-P-invalid-device.png` |

R38 结论：家长公开隐私页和无效扫码错误分支通过；真实设备绑定、偏好修改和解绑仍未执行。

# R39 管理端知识库/通道/告警/审计只读深层遍历（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R39-01 | 管理/A-08 | 进入知识库 | 显示 52 篇文档且均为已发布，分类统计为 social_skills 10、development_psychology 12、emotion_regulation 15、cbt_technique 15；未发现可提交控件 | `screenshots/BFS-A-knowledge-deep.png` |
| R39-02 | 管理/A-08 | 进入通知渠道 | 显示近 30 天发送 13 条，渠道均为 in_app，并展示失败记录入口；未发现可提交控件 | `screenshots/BFS-A-channel-deep.png` |
| R39-03 | 管理/A-05 | 进入告警中心 | 识别状态筛选、分页、每行“确认”按钮；未点击确认，避免改变告警状态 | `screenshots/BFS-A-alerts-deep-2.png` |
| R39-04 | 管理/A-11 | 进入审计日志 | 显示跨租户 LOGIN、PIN_LOGIN、ALERT_CLAIM、ALERT_RESOLVE 等事件及分页；保持只读 | `screenshots/BFS-A-audit-deep.png` |

R39 结论：四个管理端深层页面均可加载，无报错弹窗、卡死或渲染错乱；告警确认、知识审核、通道发送等写操作未执行。

# R40 教师质量监控只读深层遍历（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R40-01 | 教师/T-05 | 李老师登录并关闭首次工作台引导 | 登录成功；引导弹窗可见“跳过/下一步”，使用“跳过”关闭；无异常 | `screenshots/BFS-T-deep-login.png`、`screenshots/BFS-T-quality-final.png` |
| R40-02 | 教师/T-05 | 进入质量监控 | 显示平均评分 4.3/5、近 7 天均分 4.3/5、低分会话 0、低分率 0% 和近 30 天质量趋势 | `screenshots/BFS-T-quality-deep-2.png` |
| R40-03 | 教师/T-05 | 查看待抽检会话区域 | 显示“暂无低分会话，AI 对话质量良好”空态；无导出/审核写操作可执行 | `screenshots/BFS-T-quality-final.png` |

R40 结论：教师质量监控核心只读指标、趋势和低分空态通过；本轮未产生可供抽检的低分会话，因此未执行质量审核或 PDF 导出闭环。

# R41 学生体验账号登录可用性复核（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R41-01 | 学生/S-02 | 进入学生端登录页，识别昵称输入框、0-9 PIN 按钮、退格、登录、声音进入及隐私/服务协议入口 | 所有首屏控件正常渲染；登录按钮在昵称/PIN 未完成时禁用 | `screenshots/BFS-S-recheck-login.png` |
| R41-02 | 学生/S-02 | 输入昵称“开心”，按 PIN 1234 并提交登录 | 页面返回“昵称或 PIN 码错误”，未进入首页；无崩溃、卡死或异常弹窗 | `screenshots/BFS-S-recheck-pin.png`、`screenshots/BFS-S-recheck-result.png` |

R41 结论：历史定义为 active 的体验账号“开心”在当前 UAT 实际不可登录；记录为环境账号可用性观察项 `OBS-S-POST-003`，不修改账号状态或生产数据。真实学生深层设置/换人仍需可复用 active 账号。

# R42 管理端配置注册表与 Prompt 版本只读遍历（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R42-01 | 管理/A-03 | 进入配置注册表并识别域筛选、配置行和操作控件 | 显示 alert/security/voice 配置；SECRET 值以“***已配置***”掩码展示，生效方式为 HOT；修改/历史入口可识别 | `screenshots/BFS-A-config-deep.png` |
| R42-02 | 管理/A-03 | 打开首条配置的历史记录 | 弹窗显示对应配置键及“暂无变更记录”；关闭会话前未修改配置 | `screenshots/BFS-A-config-history-deep.png` |
| R42-03 | 管理/A-04 | 进入 Prompt 管理 | 显示 chat_default 模板、版本/A-B/状态/生效/说明/内容/操作列，当前版本 1 为 control、active、生效；“新建版本”入口可识别 | `screenshots/BFS-A-prompt-deep.png` |

R42 结论：配置掩码、历史只读弹窗和 Prompt 版本状态展示通过；未执行配置修改、新建版本、审核或激活等持久化操作。

# R43 管理端设备入口加载超时观察（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R43-01 | 管理/A-15~A-17 | super_admin 登录后进入终端设备菜单 | Browser Agent 在页面加载等待阶段超时，未取得新的设备页截图；按超时规则停止并关闭会话，未执行任何设备操作 | 无（加载超时前未完成截图） |

R43 结论：本次仅记录设备入口一次加载超时，不新增源码缺陷判定；既有 R14/R26 已取得设备状态筛选、空态及批量操作禁用证据。后续统一复测时需重新验证设备入口加载时延和页面稳定性。

# R44 管理端设备列表与状态筛选复核（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R44-01 | 管理/A-15 | super_admin 登录并进入终端设备管理 | 页面正常加载，显示刷新、二维码签发、批量升级、恢复出厂、全选、表格列和暂无数据空态 | `screenshots/BFS-A-devices-r2.png` |
| R44-02 | 管理/A-15~A-16 | 展开设备状态筛选 | 识别全部状态、已绑定在线、待绑定、离线、未激活五个选项；无设备数据时全选、批量升级、恢复出厂均禁用 | `screenshots/BFS-A-devices-status-r2.png` |

R44 结论：设备列表加载、状态筛选、空态和危险批量控件禁用复核通过；二维码签发、升级、恢复出厂及设备详情仍因无设备数据未执行。

# R45 家长端隔离会话登录/注册控件复测（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R45-01 | 家长/P-01/P-02 | 独立临时浏览器打开 `/parent/` | 页面保持在 MindSafe 家长端；识别登录、首次注册、手机号和密码控件；未连接用户浏览器 | `screenshots/BFS-P-login-r4.png` |
| R45-02 | 家长/P-01 | 点击“首次注册”Tab | 页面保持 `/parent/`，显示家庭码、手机号、密码和妈妈/爸爸/祖父母/其他四个关系选项及注册入口 | `screenshots/BFS-P-register-r4-click.png` |

R45 结论：当前版本家长登录/注册入口在隔离 Browser Agent 中可正常切换，未复现此前异常外部跳转；未输入有效家庭码、未创建账号、未产生持久化写操作。

# R46/R47 家长注册提交控件兼容性复核（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图/代码证据 |
|---:|---|---|---|---|
| R46-01 | 家长/P-01 | 注册页滚动到底并填写虚构非法数据 | 视觉文本显示“注册并绑定”，但 Browser Agent 快照未暴露提交控件；未实际提交 | `screenshots/BFS-P-register-bottom-r6.png`、`screenshots/BFS-P-invalid-r6.png` |
| R46-02 | 家长/P-01 | 尝试 DOM 精确文本定位提交控件 | 未命中；页面 URL 保持 `/parent/`，无数据写入 | `ISSUES-P.md` R6/R7 |
| R47-01 | 本地回归证据 | 检查 VerifyPage 实现与测试 | 源码存在 `Button formType="submit"`/onClick；VerifyPage.test.tsx 覆盖提交和非法校验 | `frontend/parent-h5/src/pages/verify/index.tsx`、`frontend/parent-h5/src/test/VerifyPage.test.tsx` |

R46/R47 结论：提交逻辑已有本地测试覆盖，当前阻塞是 Browser Agent 对 Taro custom element 的可访问性/事件注入兼容性；不将其登记为新产品缺陷。

# R48 家长注册提交逻辑本地回归验证（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 证据 |
|---:|---|---|---|---|
| R48-01 | 家长/P-01/P-02 | 执行 `npm test -- --run src/test/VerifyPage.test.tsx` | 1 个测试文件、13/13 用例通过；覆盖登录/注册成功、手机号/密码/家庭码校验、API 失败和 loading 状态 | `frontend/parent-h5/src/test/VerifyPage.test.tsx`、Vitest 输出 |

R48 结论：本地表单提交逻辑和校验回归通过；UAT Browser Agent 未能稳定触发 Taro custom element，不进入源码修复或部署批次。

# R49 家长端全量前端测试回归（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 证据 |
|---:|---|---|---|---|
| R49-01 | 家长/P-01~P-07 | 在 `frontend/parent-h5` 执行 `npm test -- --run` | 27 个测试文件、219/219 用例通过；ErrorBoundary 测试中的“测试爆炸”是预期异常注入，未导致失败 | Vitest 输出 |

R49 结论：家长端现有组件、路由、服务、设备/隐私和表单回归测试全绿；UAT 仍保留真实家庭/多孩/硬件数据缺口及 Browser Agent custom element 兼容性观察。

# R50 学生端全量前端测试回归（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 证据 |
|---:|---|---|---|---|
| R50-01 | 学生/S-00~S-10 | 在 `frontend/student-h5` 按锁文件恢复依赖后执行 `npm test -- --run` | 78 个测试文件、959/959 用例通过；覆盖登录/注册、对话、风险、工具、评价、日记、声纹和设置相关逻辑 | Vitest 输出 |

R50 结论：学生端本地自动化回归全绿；UAT 仍需可复用 active 账号才能继续真实登录后的 Browser Agent 深层遍历。

# R51 教师端全量前端测试回归（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 证据 |
|---:|---|---|---|---|
| R51-01 | 教师/T-01~T-09 | 在 `frontend/teacher-web` 按锁文件恢复依赖后执行 `npm test -- --run` | 35 个测试文件、221/221 用例通过 | Vitest 输出 |

R51 结论：教师端登录、预警、学生管理、质量监控、通知、大屏和设备相关代码回归全绿；UAT 仍需真实误报/转派和设备数据闭环。

# R52 管理端全量前端测试回归（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 证据 |
|---:|---|---|---|---|
| R52-01 | 管理/A-01~A-17 | 在 `frontend/admin-web` 执行 `npm test -- --run` | 23 个测试文件、64/64 用例通过 | Vitest 输出 |

R52 结论：管理端菜单、配置、Prompt、告警、设备和运营页面代码回归全绿；UAT 仍需设备数据及持久化写操作边界验证。

# R53 四端生产构建门禁（2026-08-14）

| 步骤 | 端 | 命令 | 结果 | 备注 |
|---:|---|---|---|---|
| R53-01 | 学生 | `npm run build` | 通过 | tsc + Vite 构建成功；存在大 chunk 警告 |
| R53-02 | 教师 | `npm run build` | 通过 | tsc + Vite 构建成功；存在大 chunk 警告 |
| R53-03 | 家长 | `npm run build` | 通过 | Taro H5 Webpack 构建成功；entrypoint 337 KiB 体积警告 |
| R53-04 | 管理 | `npm run build` | 通过 | tsc + Vite 构建成功；存在大 chunk 警告 |

R53 结论：四端当前代码均可生产构建；体积警告记录为性能观察项，不阻断统一部署。本轮未部署，等待四端 UAT 缺口/问题批次收敛。

# R54 四端 UAT 入口 HTTP/标题门禁（2026-08-14）

| 端 | URL | HTTP | 最终 URL | 页面标题 |
|---|---|---:|---|---|
| 学生 | `/mindsafe/` | 200 | 未跳转 | 波波小精灵 |
| 教师 | `/teacher/` | 200 | 未跳转 | MindSafe 教师工作台 |
| 家长 | `/parent/` | 200 | 未跳转 | MindSafe 家长端 |
| 管理 | `/admin/` | 200 | 未跳转 | MindSafe 平台管理后台 |

R54 结论：四端 UAT 入口可达且标题与目标端一致；本轮为只读 HTTP 检查，未启动浏览器、未操作业务数据、未部署。

# R55 学生端首屏主题与隐私弹窗复测（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R55-01 | 学生/S-00 | 独立临时浏览器打开学生端并识别首屏按钮、输入框、数字键盘、隐私/协议入口 | 首屏控件全部可识别；登录按钮在未输入时禁用 | `screenshots/BFS-S-s00-r2-entry.png` |
| R55-02 | 学生/S-00 | 依次点击 🌊、🌸、🌈 主题按钮 | 页面主题文案/视觉状态随点击切换，未跳转、未报错 | `screenshots/BFS-S-s00-r2-theme1.png`、`screenshots/BFS-S-s00-r2-theme2.png`、`screenshots/BFS-S-s00-r2-theme3.png` |
| R55-03 | 学生/S-00 | 点击隐私政策；ref 未改变状态后执行当前页面 DOM click 兜底 | 打开隐私政策与服务协议弹窗，显示服务性质、信息收集、未成年人保护、紧急求助和“我知道了” | `screenshots/BFS-S-s00-r2-privacy-dom.png` |

R55 结论：学生端首屏主题切换和隐私弹窗通过；服务协议独立打开本轮未取得可靠证据，继续保留待复核，不把 ref click 无状态变化直接判为产品缺陷。

# R56 学生端服务协议弹窗复测（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R56-01 | 学生/S-00 | 关闭隐私弹窗后，用当前页面 DOM click 打开“服务协议” | 弹出“隐私政策与服务协议”弹窗，显示服务性质、信息收集、未成年人保护、紧急求助四章节和“我知道了” | `screenshots/BFS-S-service-r3.png` |
| R56-02 | 学生/S-00 | 点击“我知道了” | 弹窗关闭并回到学生登录页；未跳转、未写入业务数据 | `screenshots/BFS-S-service-r3.png` |

R56 结论：服务协议入口、弹窗内容和关闭回父页通过；首屏协议路径完成 DOM 兜底复测。

# R57 学生端虚构账号错误登录复测（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R57-01 | 学生/S-02 | 输入虚构昵称“不存在的测试用户”，按 PIN 1234 并提交 | 登录按钮由禁用变为可用；页面提示“昵称或 PIN 码错误”，URL 仍为学生端登录页 | `screenshots/BFS-S-invalid-r3-filled.png`、`screenshots/BFS-S-invalid-r3-result.png` |

R57 结论：学生端错误凭据提示和不跳转行为通过；未触碰现有测试账号、未写入业务数据。

# R58 学生端注册告知同意门控复测（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R58-01 | 学生/S-01 | 点击“新注册” | 打开使用前重要信息弹窗，显示 8 个告知章节、同意 checkbox 和“同意并继续”按钮；初始按钮禁用 | `screenshots/BFS-S-register-r2-entry.png` |
| R58-02 | 学生/S-01 | 勾选“我已阅读并理解以上全部内容” | checkbox 变为 checked，按钮从 disabled 变为可用 | `screenshots/BFS-S-register-r2-consent.png` |
| R58-03 | 学生/S-01 | 点击已启用“同意并继续” | ref click 未取得后续注册页状态，未提交注册；不把导航记为通过 | `screenshots/BFS-S-register-r2-form.png` |

R58 结论：告知同意门控本身通过；“同意并继续”后续导航仍需 DOM 事件复核，未创建账号、未写入数据。

# R59 学生端同意后注册表单导航复测（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R59-01 | 学生/S-01 | 每次页面变化后重新 snapshot；DOM 勾选同意 checkbox，再 DOM click“同意并继续” | 成功进入注册表单，显示邀请码、昵称、性别、年龄、声纹同意说明、注册按钮及协议入口 | `screenshots/BFS-S-consent-dom-r3.png` |

R59 结论：告知同意门控及后续注册表单导航通过；本轮未填写或提交邀请码，未创建账号、未写入业务数据。

# R60 学生端注册表单控件遍历（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R60-01 | 学生/S-01 | 在同意后注册表单识别交互控件 | 识别邀请码、昵称、男生/女生、年龄、声纹同意 checkbox、注册按钮、隐私/协议入口 | `screenshots/BFS-S-register-form-r4.png` |
| R60-02 | 学生/S-01 | 切换“女生” | 性别选择状态切换成功，无跳转或异常 | `screenshots/BFS-S-register-girl-r4.png` |
| R60-03 | 学生/S-01 | 关闭并重新开启声纹同意 checkbox | checkbox 可在 off/on 间切换；提示明确声音仅保存在本机 | `screenshots/BFS-S-register-voice-off-r4.png`、`screenshots/BFS-S-register-voice-on-r4.png` |

R60 结论：注册表单主要控件和可逆状态切换通过；邀请码/昵称/年龄校验及注册提交未执行，避免创建新的 UAT 账号。

# R61 学生端注册边界与监护人字段分支（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R61-01 | 学生/S-01 | 填写虚构邀请码 `BADCODE`、1 字昵称、年龄 0 | 表单保持当前页，无崩溃或外部跳转；未创建账号 | `screenshots/BFS-S-register-invalid-r5-filled.png` |
| R61-02 | 学生/S-01 | 点击“注册 🚀” | 未发生账号创建；页面显示“家长手机号 *”字段，进入未成年监护人信息分支 | `screenshots/BFS-S-register-invalid-r5-result.png` |

R61 结论：注册边界路径触发监护人手机号步骤且无异常；本轮未填写家长手机号、未提交邀请码，错误邀请码/年龄的最终拒绝提示仍待安全数据条件下复核。

# R62 管理端只读菜单与配置历史弹窗 BFS 补充（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R62-01 | 管理/A-01 | 独立临时 profile 登录管理端并识别平台总览及 17 个侧边菜单项 | 登录成功；总览租户表、分页状态和所有菜单项可读；未见崩溃/报错弹窗 | `screenshots/BFS-A-r62-login.png`、`screenshots/BFS-A-r62-login-filled.png` |
| R62-02 | 管理/A-03 | 进入配置注册表，识别域筛选、配置表格、SECRET 掩码和修改/历史控件 | 页面正常；SECRET 只显示掩码；未点击修改 | `screenshots/BFS-A-r62-config.png` |
| R62-03 | 管理/A-03 弹窗 | 打开首条配置的“历史”并关闭弹窗 | 弹窗显示配置键和“暂无变更记录”；关闭后回到原配置页，未改变数据 | `screenshots/BFS-A-r62-config-history.png`、`screenshots/BFS-A-r62-config-history-closed.png` |
| R62-04 | 资源安全 | 关闭 Browser Agent 会话并执行 `close --all`、session list、进程核验 | 无 active agent-browser session；系统原有 Chrome 进程未操作 | — |

R62 结论：管理端登录、菜单、只读配置页及弹窗开闭路径通过；本轮仍遵守写操作安全边界。隔离临时会话已关闭且未连接用户浏览器。

# R63 教师端工作台、质量监控与通知中心 BFS 补充（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R63-01 | 教师/T-01 | 独立临时 profile 登录李老师并识别工作台、预警队列、学生管理、质量监控、通知中心、终端设备菜单 | 登录成功；工作台指标和既有预警摘要可读；未见崩溃/报错弹窗 | `screenshots/BFS-T-r63-login.png`、`screenshots/BFS-T-r63-login-filled.png` |
| R63-02 | 教师/T-01 弹窗 | 遍历首次工作台引导，点击“跳过/关闭”；ref 无状态变化时使用当前页面 DOM click 复核 | 引导弹窗关闭并回到工作台；未改变业务数据 | `screenshots/BFS-T-r63-dashboard.png`、`screenshots/BFS-T-r63-onboarding-dom.png` |
| R63-03 | 教师/T-05 | 进入质量监控并读取平均评分、近 7 天指标、低分会话空态和趋势区域 | 页面正常渲染，低分会话为 0；未执行导出/审核写操作 | `screenshots/BFS-T-r63-quality.png`、`screenshots/BFS-T-r63-quality-dom.png` |
| R63-04 | 教师/T-06 | 进入通知中心，识别全部/未读/已读 Tab、逐条“已读”、分页控件 | 三个 Tab、5 个已读按钮和分页均可识别；未点击状态变更按钮 | `screenshots/BFS-T-r63-notifications.png` |
| R63-05 | 资源安全 | 关闭会话，执行 `close --all` 并再次核验 session list | 无 active session；未连接或操作用户浏览器 | — |

R63 结论：教师端工作台、引导弹窗、质量监控和通知中心只读路径通过；菜单导航/ref 事件兼容性按测试计划记录，未形成产品故障。

# R64 家长端注册、隐私与无效设备 BFS 补充（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R64-01 | 家长/P-01/P-02 | 打开家长端登录页，识别登录/首次注册 Tab、手机号和密码控件 | 首屏正常；登录 Tab 和表单控件可识别，无异常 | `screenshots/BFS-P-r64-login.png` |
| R64-02 | 家长/P-01 | 切换首次注册，识别家庭码、手机号、密码及妈妈/爸爸/祖父母/其他关系选项 | 注册字段和关系选项可识别；填写虚构数据后页面无崩溃；未提交 | `screenshots/BFS-P-r64-register.png`、`screenshots/BFS-P-r64-register-filled.png` |
| R64-03 | 家长/P-01 | 滚动并检查注册提交控件 | 页面视觉文字包含“注册并绑定”，但可访问性快照未暴露提交按钮；未强制注入提交事件 | `screenshots/BFS-P-r64-register-bottom.png` |
| R64-04 | 家长/P-06 | 打开公开个人信息保护告知页 | 收集范围、使用方式、未成年人保护和家长权利内容可读，无交互异常 | `screenshots/BFS-P-r64-privacy.png` |
| R64-05 | 家长/P-07 | 打开无效设备二维码路径 `/parent/p/1/INVALIDDEVICE000` | 显示“未找到该设备，请核对机身二维码，或联系学校管理员”；未出现绑定/配网写控件 | `screenshots/BFS-P-r64-invalid-device.png` |
| R64-06 | 资源安全 | 关闭会话，执行两次 `close --all` 并核验 session list | 无 active session；未连接或操作用户浏览器 | — |

R64 结论：家长端登录/注册入口、注册字段、隐私页和无效设备分流通过；注册提交控件未暴露的观察待真实设备/有效家庭码条件下复核，未执行任何副作用操作。

# R65 教师端 head_teacher 角色与学生档案 BFS 补充（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R65-01 | 教师/T-01 RBAC | 使用王老师（head_teacher）登录并识别工作台菜单 | 登录成功；工作台、预警队列、学生管理、质量监控、通知中心、终端设备菜单可见；通知数量与李老师角色不同 | `screenshots/BFS-T-r65-login.png`、`screenshots/BFS-T-r65-dashboard.png` |
| R65-02 | 教师/T-04 | 进入学生管理，识别年级/班级筛选、昵称搜索、风险 checkbox、CSV 导出、分页和学生行操作 | 三名学生列表正常渲染；控件可识别；未执行 CSV 导出 | `screenshots/BFS-T-r65-students.png` |
| R65-03 | 教师/T-04 | 切换“只看风险学生”并用 DOM click 复核 | ref 触发未改变快照，DOM 事件路径已执行；页面无崩溃或报错 | `screenshots/BFS-T-r65-risk-filter.png`、`screenshots/BFS-T-r65-risk-filter-dom.png` |
| R65-04 | 教师/T-04 弹窗/详情 | 打开学生“查看档案”，遍历档案内容、备注输入和添加按钮，返回列表 | 档案显示姓名、年级、班级、冻结状态、最高风险、会话统计；备注按钮因空输入禁用；返回列表成功 | `screenshots/BFS-T-r65-profile.png`、`screenshots/BFS-T-r65-profile-closed.png` |
| R65-05 | 资源安全 | 关闭会话，执行两次 `close --all` 并核验 session list | 无 active session；未操作用户浏览器 | — |

R65 结论：head_teacher 角色登录、菜单、学生筛选及档案详情只读路径通过；未执行导出、备注提交、预警处置或设备写操作。

# R66 学生端体验账号当前可用性复核（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R66-01 | 学生/S-02 | 独立临时 profile 打开学生端，识别登录页全部控件 | 登录页完整渲染：主题、登录/注册、昵称、数字键盘、PIN 提交、声音入口、隐私/服务协议均可识别 | `screenshots/BFS-S-r66-login-filled.png` |
| R66-02 | 学生/S-02 | 输入“开心”和 PIN `1234` 后提交，记录网络结果 | 页面显示“昵称或 PIN 码错误”；`POST /api/v1/auth/pin-login` 返回 HTTP 401；未进入已登录页面 | `screenshots/BFS-S-r66-login-result.png` |
| R66-03 | 资源安全 | 关闭会话，执行两次 `close --all` 并核验 session list | 无 active session；未操作用户浏览器 | — |

R66 结论：体验账号当前仍不可复用；与既有 R29/R41 结果一致，归类为 UAT 账号/PIN/租户状态观察，不修改认证源码或账号数据。

# R67 教师端预警队列只读 BFS 补充（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R67-01 | 教师/T-03 | 李老师登录后进入预警队列，识别状态筛选、最低等级筛选、导出、可排序列和分页 | 页面正常；2 条历史预警可读，未见崩溃/报错；导出未点击 | `screenshots/BFS-T-r67-alerts.png` |
| R67-02 | 教师/T-03 | 识别预警行、状态、学生、类型和操作控件；尝试行级只读点击 | 历史行及“处理/误报”按钮可识别；行点击未改变页面状态；未执行处理/误报 | `screenshots/BFS-T-r67-alert-detail.png` |
| R67-03 | 资源安全 | 关闭会话，执行两次 `close --all` 并核验 session list | 无 active session；未操作用户浏览器 | — |

R67 结论：教师预警队列的只读筛选、排序、分页和历史数据路径通过；处置按钮保留为未执行的有副作用控件。

# R68 家长端错误登录控件与事件路径复核（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R68-01 | 家长/P-02 | 独立临时 profile 打开登录页，填充虚构手机号和错误密码 | 字段填充成功，页面保持稳定，无崩溃/卡死 | `screenshots/BFS-P-r68-invalid-filled.png` |
| R68-02 | 家长/P-02 | 通过当前页面 DOM 定位可见“登录”文本并触发一次提交复核，检查网络请求 | 页面无错误提示、无跳转；未捕获 `/parent/auth/login` 请求；未强制注入隐藏提交事件 | `screenshots/BFS-P-r68-invalid-result.png` |
| R68-03 | 资源安全 | 关闭会话，执行两次 `close --all` 并核验 session list | 无 active session；未操作用户浏览器 | — |

R68 结论：错误登录提交控件在可访问树中未暴露，当前复核不足以判定后端认证故障；保留为家长端 Taro 事件/可访问性观察，待有效家庭账号或真实交互条件下复核。

# R69 管理端 Prompt 与知识库只读 BFS 补充（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R69-01 | 管理/A-04 | super_admin 登录后进入 Prompt 管理，识别新建版本、版本、A/B、状态、生效、说明、内容和操作列 | 当前 chat_default 版本 1/control/active/生效；页面正常，无报错 | `screenshots/BFS-A-r69-prompt.png` |
| R69-02 | 管理/A-04 | 尝试只读点击 Prompt 行，复核状态变化 | 行点击未产生详情或状态变化；未点击新建、审核、激活、发布 | `screenshots/BFS-A-r69-prompt-detail.png` |
| R69-03 | 管理/A-08 | 进入知识库统计页 | 页面正常显示“知识库统计”标题；未发现可提交写操作 | `screenshots/BFS-A-r69-knowledge.png` |
| R69-04 | 资源安全 | 关闭会话，执行两次 `close --all` 并核验 session list | 无 active session；未操作用户浏览器 | — |

R69 结论：管理端 Prompt 和知识库只读入口/状态信息通过；所有版本创建、审核、激活、发布及通知发送操作继续按安全边界保留未执行。

# R70 教师端终端设备只读 BFS 补充（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R70-01 | 教师/T-09 | 李老师登录后进入终端设备管理 | 页面稳定加载；显示绑定设备、刷新、班级筛选、归属 ID 查询、设备表头和空态 | `screenshots/BFS-T-r70-devices.png` |
| R70-02 | 教师/T-09 | 点击“刷新”并重新识别状态 | 刷新后仍为暂无设备空态，无错误弹窗或渲染错乱；未点击绑定设备 | `screenshots/BFS-T-r70-devices-refresh.png` |
| R70-03 | 资源安全 | 关闭会话，执行两次 `close --all` 并核验 session list | 无 active session；未操作用户浏览器 | — |

R70 结论：教师端设备入口、刷新、筛选/查询控件和空态通过；绑定设备及设备写操作因无安全可回收设备数据未执行。

# R71 学生端注册深层分支与提交事件复核（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R71-01 | 学生/S-01 | 打开新注册并遍历 8 节告知内容、同意 checkbox 和禁用/启用按钮 | checkbox 初始未勾选；DOM 事件勾选后“同意并继续”启用并进入注册表单 | 已留存于本轮 Browser Agent 操作记录；表单截图见 `screenshots/BFS-S-r71-register-filled.png` |
| R71-02 | 学生/S-01 | 填写 `DEMO2026`、虚构昵称、年龄 10，检查未成年监护人手机号分支 | 页面出现“家长手机号 *”；填充虚构手机号后无崩溃；未提交成功 | `screenshots/BFS-S-r71-register-filled.png`、`screenshots/BFS-S-r71-registration-result.png` |
| R71-03 | 学生/S-01 | 改为年龄 16、关闭声纹 checkbox，分别通过 ref/DOM 触发注册 | 页面仍停留注册表单；未捕获注册请求，未生成账号；提交事件路径不足以判定后端故障 | `screenshots/BFS-S-r71-registration-dom-result.png`、`screenshots/BFS-S-r71-registration-age16.png`、`screenshots/BFS-S-r71-registration-submit.png` |
| R71-04 | 资源安全 | 关闭会话，执行两次 `close --all` 并核验 session list | 无 active session；未操作用户浏览器 | — |

R71 结论：监护人字段和声纹可逆分支已覆盖；本轮未创建新账号，注册提交无网络请求的现象先归类为事件注入/当前表单状态观察，待真实用户事件或有效邀请码条件下复核。

# R72 管理端用量、合规与运营洞察只读 BFS 补充（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R72-01 | 管理/A-09 | 进入用量报表 | 页面标题和统计页面正常加载，无交互异常；未执行导出 | `screenshots/BFS-A-r72-usage.png` |
| R72-02 | 管理/A-10 | 进入数据合规中心 | 页面正常加载；合规统计入口可达；未执行导出审批或数据写操作 | `screenshots/BFS-A-r72-compliance.png` |
| R72-03 | 管理/A-11 | 进入运营洞察并识别日期/日均分/样本数和租户事件/健康度表格 | 7 天趋势、TRIAL/DEV 租户事件数、未处置、逾期和健康度均可读；未见崩溃或报错 | `screenshots/BFS-A-r72-insights.png` |
| R72-04 | 资源安全 | 关闭会话，执行两次 `close --all` 并核验 session list | 无 active session；未操作用户浏览器 | — |

R72 结论：管理端 A-09/A-10/A-11 只读页面和表格状态通过；导出审批、数据变更和租户操作继续未执行。

# R73 管理端告警中心只读 BFS 补充（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R73-01 | 管理/A-05 | super_admin 登录后进入告警中心，识别状态筛选和表格列 | 页面正常；规则、级别、状态、推送、来源、摘要、触发时间和操作列可读 | `screenshots/BFS-A-r73-alerts.png` |
| R73-02 | 管理/A-05 | 识别当前 10 条告警行和逐条“确认”按钮 | 告警数据和确认按钮全部可识别；未点击确认，未改变告警状态 | `screenshots/BFS-A-r73-alerts.png` |
| R73-03 | 管理/A-05 | 识别页码 1–5、向后 5 页、10、下一页和页码 combobox | 多页控件全部可识别；未改变查询状态 | `screenshots/BFS-A-r73-alerts.png` |
| R73-04 | 资源安全 | 关闭会话，执行两次 `close --all` 并核验 session list | 无 active session；未操作用户浏览器 | — |

R73 结论：管理端告警中心只读控件、告警数据和多页分页通过；确认操作按安全边界未执行。

# R74 管理端审计日志只读 BFS 补充（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R74-01 | 管理/A-16 | super_admin 登录后进入审计日志 | 页面正常加载；时间、动作、操作人、租户、详情列可读 | `screenshots/BFS-A-r74-audit.png` |
| R74-02 | 管理/A-16 | 识别 LOGIN、PIN_LOGIN、ALERT_CLAIM、ALERT_RESOLVE 等事件和详情内容 | 审计事件可读且详情未泄露测试密码/PIN；未点击写操作 | `screenshots/BFS-A-r74-audit.png` |
| R74-03 | 管理/A-16 | 识别 1–5、向后 5 页、10、下一页和页码 combobox | 多页控件全部可识别；未改变审计数据 | `screenshots/BFS-A-r74-audit.png` |
| R74-04 | 资源安全 | 关闭会话，执行两次 `close --all` 并核验 session list | 无 active session；未操作用户浏览器 | — |

R74 结论：管理端审计日志事件、详情和分页只读路径通过；未执行任何数据清理或状态写入。

# R75 教师端独立大屏入口复测（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R75-01 | 教师/T-07 | 王老师登录后访问 `/bigscreen` 独立入口 | 聚合大屏正常加载；会话、活跃学生、待处理预警、满意度、累计会话、趋势、情绪/风险/班级对比标题可读 | `screenshots/BFS-T-r75-bigscreen-root.png` |
| R75-02 | 教师/T-07 | 点击“返回工作台”视觉 ref 并检查 URL/页面状态 | 本轮 ref 点击后 URL 仍为 `/bigscreen`；未继续操作外部页面；既有 R36 已以 DOM 兜底验证返回路径，本轮记录为自动化事件注入观察 | `screenshots/BFS-T-r75-bigscreen-return.png` |
| R75-03 | 资源安全 | 关闭会话，执行两次 `close --all` 并核验 session list | 无 active session；未操作用户浏览器 | — |

R75 结论：教师大屏独立入口和只读聚合数据通过；返回按钮的视觉 ref 事件兼容性沿用 R36 观察，不新增源码故障。

# R78 教师端李老师登录态 BFS 只读补充（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R78-01 | 教师/T-01 | 隔离 Browser Agent 打开教师端，填充李老师/密码并登录 | 登录成功；进入工作台；未连接用户浏览器 | `screenshots/teacher-auth-R78-teacher-login-enter.png`、`screenshots/teacher-auth-R78-teacher-login-filled.png`、`screenshots/teacher-auth-R78-teacher-dashboard.png` |
| R78-02 | 教师/T-02 | 识别工作台新手引导并用页面内真实 DOM click 关闭/跳过 | 引导关闭；工作台保留 1 条红色逾期预警、导出入口、全部预警和查看全部控件；未执行导出 | `screenshots/teacher-auth-R78-teacher-onboarding-skip2.png` |
| R78-03 | 教师/T-03 | 进入预警队列，识别状态/最低等级筛选、表格、分页和处理/误报控件 | 页面正常；可读到测试丁红色自伤/自杀预警和黄色 SOS 记录；未点击处理/误报 | `screenshots/teacher-auth-R78-0006-alert-list.png` |
| R78-03a | 教师/T-03 | 尝试对测试丁高风险行打开只读详情 | 当前行语义定位未命中；页面保持预警列表，无崩溃/报错；未改写状态 | `screenshots/teacher-auth-R78-0007-alert-detail.png` |
| R78-04 | 教师/T-03 | 以真实 DOM click 复核预警队列菜单触发 | 菜单成功切换；视觉 ref click 未改变页面，归类为自动化事件兼容性观察，不登记产品缺陷 | `screenshots/teacher-auth-R78-teacher-alert-domclick.png` |
| R78-05 | 教师/T-04 | 进入学生管理，识别筛选、搜索、风险 checkbox、学生行和档案入口 | 小明显示冻结/红色，测试己显示正常，测试丁高风险数据可见；未导出或修改档案 | `screenshots/teacher-auth-R78-0008-students.png` |
| R78-06 | 教师/T-05 | 进入质量监控 | 平均评分 4.3/5、近 7 天均分、低分会话/低分率和趋势入口正常；无崩溃/错误弹窗 | `screenshots/teacher-auth-R78-0009-quality.png` |
| R78-07 | 教师/T-06 | 进入通知中心，识别全部/未读/已读 Tab 和逐条“已读”按钮 | 页面正常，5 个已读操作控件可识别；未点击状态写入控件 | `screenshots/teacher-auth-R78-0010-notifications.png` |
| R78-08 | 教师/T-09 | 进入终端设备，识别绑定、刷新、班级、归属 ID、查询和表格控件 | 页面稳定为空态；未执行绑定或设备写操作 | `screenshots/teacher-auth-R78-0011-devices.png` |
| R78-09 | 资源安全 | 关闭 session，执行两次 `close --all` 并复核 session list | 无 active session；未操作被测软件以外窗口 | — |

R78 结论：李老师登录态下教师工作台、预警队列、学生管理、质量监控、通知中心和终端设备只读路径通过；真实数据与控件均已记录。处理/误报、导出、绑定、设备编排和低分 PDF 等持久化/外部副作用操作继续待安全夹具。

# R79 管理端 super_admin 登录态一级菜单 BFS（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R79-01 | 管理/A-01 | 隔离 Browser Agent 打开管理端，填充 super_admin/密码并登录 | 登录成功；进入平台总览；未连接用户浏览器 | `screenshots/admin-auth-R79-admin-login-enter.png`、`screenshots/admin-auth-R79-admin-login-filled.png`、`screenshots/admin-auth-R79-admin-overview.png` |
| R79-02 | 管理/A-02~A-17 | 依次点击 16 个一级菜单：配置注册表、Prompt 管理、风险全景、时效监控、处置台账、降级矩阵、知识库、通知渠道、运营洞察、用量报表、数据合规、服务状态、指标看板、告警中心、审计日志、终端设备 | 每个菜单均可触发并保存截图；页面未出现崩溃或错误弹窗；平台总览当前为暂无数据空态 | `screenshots/admin-auth-R79-admin-01.png` … `screenshots/admin-auth-R79-admin-16.png` |
| R79-03 | 管理/A-03/A-04/A-05/A-15~A-17 | 识别配置、Prompt、告警和终端页面的可操作入口 | 仅识别配置修改、Prompt 新建/审核/激活、告警确认、设备绑定/批量操作等控件；未执行任何写操作 | 同上 |
| R79-04 | 资源安全 | 关闭 session，执行两次 `close --all` 并复核 session list | 无 active session；未操作被测软件以外窗口 | — |

R79 结论：super_admin 登录成功并完成管理端 17 个一级菜单（平台总览 + 16 菜单）只读入口遍历；需要逐页弹窗/分页和四角色 RBAC 的深层复核仍按既有 R62/R69/R72/R73/R74 证据及后续补测推进，所有持久化动作继续待安全夹具。

# R80 家长端有效账号登录兼容性与注册 Tab 只读补充（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R80-01 | 家长/P-02 | 隔离 Browser Agent 打开家长端，填充设计资料中的手机号/密码 | 密码控件可填充；手机号 spinbutton 在 ref/键盘输入后被页面重渲染为不同值，未获得可复用登录态 | `screenshots/parent-auth-R80-parent-login-enter.png`、`screenshots/parent-auth-R80-parent-login-filled.png` |
| R80-02 | 家长/P-02 | 识别登录控件并尝试一次页面内真实事件触发 | 登录按钮为 generic、未暴露 button/submit role；未捕获有效登录请求，未注入 token 或绕过认证 | `screenshots/parent-auth-R80-parent-home.png` |
| R80-03 | 家长/P-01 | 切换“首次注册” Tab，识别家庭码、手机号、密码和关系控件 | 注册表单正常显示：家庭码、手机号、密码及妈妈/爸爸/祖父母/其他关系选项；未提交注册、未创建账号 | `screenshots/parent-auth-R80-parent-register-tab.png` |
| R80-04 | 资源安全 | 关闭 session，执行两次 `close --all` 并复核 session list | 无 active session；未操作被测软件以外窗口 | — |

R80 结论：家长端注册 Tab 控件和关系分支可识别；有效账号登录因手机号控件重渲染/登录控件可访问性问题未形成成功登录证据，暂归自动化兼容性与 UAT 账号待复核，不登记后端缺陷。

# R81 学生端 active 凭证复核（2026-08-14）

| 步骤 | 端/场景 | 操作 | 结果 | 截图 |
|---:|---|---|---|---|
| R81-01 | 学生/S-02 | 隔离 Browser Agent 打开学生登录页，识别主题、昵称、数字键盘、PIN、声音进入、隐私/协议控件 | 首屏全部可交互控件可识别；页面稳定无崩溃 | `screenshots/student-auth-R81-student-auth-enter.png` |
| R81-02 | 学生/S-02 | 输入测试丁并点击 PIN 1234，提交登录 | 页面显示“昵称或 PIN 码错误”；未进入首页，历史 401 现象复现 | `screenshots/student-auth-R81-student-auth-testding-filled.png`、`screenshots/student-auth-R81-student-auth-testding-result.png` |
| R81-03 | 学生/S-02 | 输入开心并点击 PIN 1234，提交登录 | 同样显示“昵称或 PIN 码错误”；未进入首页 | `screenshots/student-auth-R81-student-auth-kx-filled.png`、`screenshots/student-auth-R81-student-auth-kx-result.png` |
| R81-04 | 资源安全 | 关闭 session，执行两次 `close --all` 并复核 session list | 无 active session；未操作被测软件以外窗口 | — |

R81 结论：测试丁/开心两个设计资料凭证均无法在当前 UAT 建立 active 学生登录态；与 R29/R41/R66 一致，继续归类为账号/PIN/租户数据前置问题，未修改认证源码或线上数据。
