# UI-TEST-018 本轮执行摘要（进行中）

## 当前覆盖

| 端 | 首屏 | 登录/注册 | 主导航 | 深层场景 | 状态 |
|---|---:|---:|---:|---:|---|
| 学生端 S | ✅ | ⚠️ 体验账号登录实测拒绝；注册流程已完成 | ✅ 局部（情绪日记、放松工具、设置、隐私协议） | 未完成 | 新会话登录仍失败，深层场景受账号状态阻断 |
| 教师端 T | ✅ | ✅ 李老师 | ⚠️ 引导浮层无法关闭 | 未开始 | BUG-T-T01-001 阻断 |
| 家长端 P | ✅ | ⚠️ 首次注册可见但无提交按钮 | 未开始 | 未开始 | BUG-P-P01-001 阻断 |
| 管理端 A | ✅ | ✅ super_admin | ⚠️ 4 个菜单点击不切页 | 主菜单已逐项取证 | BUG-A-A11-001/002 |

## 已发现问题

1. `BUG-A-A11-001`：指标看板点击后仍停留在服务状态。
2. `BUG-A-A11-002`：告警中心、审计日志、终端设备点击后仍停留在服务状态。
3. `BUG-T-T01-001`：教师端工作台引导浮层无法关闭，主菜单不可用。
4. `BUG-T-T02-001`：绕过引导标记后教师端侧边菜单仍无法切换面板。
5. `BUG-P-P01-001`：家长首次注册没有可操作的提交按钮。
6. 学生端体验账号当前提示“昵称或 PIN 码错误”，已截图，待账号状态或登录交互进一步核验。
7. `OBS-S-S00-001/002` 已澄清：底部隐私政策/服务协议需滚动到可见区域，复测通过。
8. `BUG-S-S02-002`：注册成功后新会话使用同一昵称/PIN 无法登录。
9. `OBS-S-S01-001` 已澄清：告知页需先滚动到底再勾选同意，复测通过。
10. `OBS-S-S08-001` 已澄清：日记提交按钮需滚动到可见区域，复测成功。

## 截图索引

- 首屏：`screenshots/S-00-initial.png`、`screenshots/ui018-teacher-initial.png`、`screenshots/ui018-parent-initial.png`、`screenshots/ui018-admin-initial.png`
- 学生端：`S-02-pin-filled.png`、`S-02-login-result.png`、`S-01-register-entry.png`、`S-01-consent-checked.png`、`S-01-scrolled-bottom.png`、`S-01-after-scroll-agree.png`、`S-01-registration-complete.png`、`S-03-home-after-register.png`、`S-06-toolbox.png`、`S-06-breathing-start.png`、`S-06-breathing-end.png`、`S-08-submit-result-visible.png`、`S-10-settings.png`、`S-00-privacy.png`、`S-00-service.png`
- 教师端：`T-01-login-result.png`、`T-onboarding-closed.png`、`T-onboarding-skip.png`、`T-*.png`
- 家长端：`P-01-register-entry.png`、`P-01-invalid-filled.png`、`P-01-invalid-result.png`
- 管理端：`A-*.png`、`A-indicators-verify.png`

## 停止/继续条件

- 不进行代码修复、部署或生产发布，除非取得明确授权并确认目标环境和回滚方案。
- 若继续保持当前线上版本，剩余深层场景只能记录为 blocked，不能标记通过。
- 修复后需重新执行对应端全量 BFS，并完成 S/T/P/A 四端回归及 L 联动场景。
