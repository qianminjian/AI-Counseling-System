# UI-TEST-018 家长端问题清单

## OBS-P-POST-001 [待复核] 有效账号复测时输入事件导致 Browser Agent/渲染进程无响应

- 发现轮次：R27（2026-08-14，R25 统一部署后）。
- 环境：UAT `/parent/`，项目测试资产中的有效家长账号；Browser Agent 单会话、Chrome headless。
- 复现：登录页初始可渲染；使用真实键盘事件替换手机号和密码并提交时，Agent 命令持续无响应，Chrome 渲染进程 CPU 持续高占用。
- 影响：本次无法取得有效账号登录后的周报/授权详情复测结果。
- 处置：达到测试指导“窗口卡死/超时自动停止”条件，已终止 Agent 和 Chrome 子进程；未修改源码、未部署。
- 后续：用全新单会话先验证普通 `fill` 与 DOM 表单提交，再决定是否登记产品缺陷；禁止并行重试。

## BUG-P-P01-001 [P1] 首次注册页面没有可操作的注册提交控件

- 状态：CLOSED（Taro custom button 需使用 DOM click；此前为测试执行器语义 ref 误报）
- 环境：UAT `/parent/`；首次注册页；Browser Agent 0.26.0；Chrome 151
- 复现：
  1. 打开家长端，点击“首次注册”。截图 `screenshots/P-01-register-entry.png`。
  2. 填写无效家庭码 `XXXXXX`、测试手机号和测试密码，选择“妈妈”。截图 `screenshots/P-01-invalid-filled.png`。
  3. 视觉快照中仅显示静态文本“注册并绑定”，没有 button/input[type=submit]。
  4. 使用文本定位点击“注册并绑定”，页面无变化；截图 `screenshots/P-01-invalid-result.png`。
  5. DOM 控件计数：`button=0`、`form button=0`、`[type=submit]=0`。
- 6. 已滚动到表单底部并在密码框按 Enter，页面仍无变化；截图 `screenshots/P-01-submit-visible.png`、`screenshots/P-01-enter-submit-result.png`。
- 实际：注册提交动作没有可交互控件，无法提交家庭码校验，也无法得到错误提示或注册结果。
- 期望：提供明确可聚焦、可点击的提交按钮；非法家庭码应显示拒绝提示，合法家庭码进入注册成功态。
- 影响：P-01 家长注册主流程不可执行，P-02~P-07 依赖的家长会话无法建立。

### R4 复核结论（2026-08-14）

- 通过 Taro `taro-button-core` 的真实 DOM click 切换到首次注册并触发“注册并绑定”。
- 缺少手机号时正确显示“请输入正确的 11 位手机号”。
- 结论：产品提交逻辑可用，原故障为测试执行器未触发 custom element click。
