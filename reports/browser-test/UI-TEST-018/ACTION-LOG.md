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
