# 学生端 Browser Agent 遍历测试问题清单

> 起始：2026-08-09 | 执行轮次：第 1 轮 | 测试账号：开心（PIN=1234）/ 新注册测试甲（PIN=待设）
> 来源：doing/82 §5.1 S-01~S-10 + §6.2 提示词

## 静态基线（§3.4）

| # | 检查项 | 结果 | 备注 |
|---|--------|------|------|
| B0-1 | 学生端首页 HTTP 200 + 关键元素渲染 | ✅ PASS | 标题「波波小精灵」、登录/注册 tab、彩虹键盘、主题浮标 3 项均渲染 |
| B0-2 | 控制台无 error | ✅ PASS | 仅 2 条 a11y issue（非 error） |
| B0-3 | 无 404 资源请求 | ⬜ 待 S-01 全流程后断言 |

### BUG-S-S01-01 [P2] 初次注册时无 PIN 长度提示文案
- 场景：S-01 注册与准入 / 学生端注册流程 PIN 设置页
- 步骤：步骤 4（输入 3 位 PIN）
- 期望：输入框附近/键盘区上方有「请输入 4-6 位数字密码」明示长度区间，3 位时给出不足提示
- 实际：无任何长度提示文案，长度限制仅隐含于「下一步」按钮禁用态，用户需自行尝试发现上限
- 截图：screenshots/S-01-03-PIN-4位-下一步disabled.png
- 疑似根因：student-h5/src/components/LoginPage.tsx 注册流程未渲染长度提示
- **修复 commit**：`d7a5aa7`（fix(student-h5): PIN 长度实时提示 + 5 个表单字段 a11y label 绑定）— 在 PIN 指示器下方新增 `<p className="pin-hint">` 实时提示，状态机：0位「请输入 4-6 位数字」/ 不足 4 位「还需要 N 位数字」/ 4-5 位「✓ 可以设置了」/ 6 位「✓ 已达最长 6 位」，配 `aria-live="polite"` 提升 a11y
- **修复验证**：vitest 42/42 全过；deploy.sh student SUCCESS 1m14s
- 状态：FIXED | 修复提交：`d7a5aa7` | 复测结果：**VERIFIED ✅（2026-08-10 生产复测）**——0位「请输入 4-6 位数字」/ 3位「还需要 1 位数字」+下一步 disabled / 4位「✓ 可以设置了」/ 6位「✓ 已达最长 6 位」全状态机实证

### BUG-S-S01-02 [P1] PIN 录入上限硬编码 5 位，与文案声称的「4-6 位」不一致
- 场景：S-01 注册与准入 / 学生端注册流程 PIN 设置页
- 步骤：步骤 4（输入第 6 位 PIN `654321`）
- 期望：6 位 PIN 可正常录入并启用「下一步」（与文案一致）
- 实际：键盘点击第 6 次时数字被静默截断/拒绝，「下一步」始终保持 disabled；代码 `length < 6` 校验使上限=5
- 截图：screenshots/S-01-03-PIN-4位-下一步disabled.png（同视图，反映「6 位不能录」的现象）
- 控制台/网络：evaluate_script `[disabled, true]`，未发现任何网络请求或错误提示
- 疑似根因：student-h5/src/components/LoginPage.tsx `length < 6` 条件（应改为 `< 4 || length > 6`）；或文案改为「4-5 位」
- 修复方向：二选一——(a) 上限改 6、下限改 4；(b) 文案改「4-5 位」并去除文案歧义
- **修复 commit**：`5e85924`（fix(student-h5): PIN 录入上限 5→6 与文案「4-6 位」对齐）— student-h5/src/components/LoginPage.tsx ×3 处 `length<6` 改 `length<7`，并补 LoginPage.test.tsx 回归用例「PIN 可录入 6 位」覆盖 654321 → setPin(654321)
- **修复验证**：vitest 40/40 全过；deploy.sh student 组件 SUCCESS 1m9s；生产环境手动复测 6 位 PIN 录入后 6 个 indicator 全填 + 「进入」按钮启用（截图 S-01-07-复测-PIN-6位enabled.png），错误密码仍正确提示「昵称或 PIN 码错误」零回归
- 状态：FIXED | 修复提交：`5e85924` | 复测结果：VERIFIED ✅

### BUG-S-BASE-01 [P3] 登录页昵称输入框缺 label/name 属性（a11y）
- 场景：S-03 情绪选择与开场 / 学生端登录页（首次渲染）
- 步骤：登录页静态加载（步骤 0）
- 期望：输入框有 `<label>` 或 `id/name` 属性可供 a11y 关联
- 实际：浏览器 a11y 报告「No label associated with a form field」+「A form field element should have an id or name attribute」
- 控制台/网络：list_console_messages → msgid=1,2 [issue] form-field label/name 缺失
- 截图：screenshots/S-base-登录页.png（待截）
- 疑似根因：student-h5/src/components/LoginPage.tsx 昵称 TextField 缺 `<label>`/`id`/`name` 属性
- **修复 commit**：`d7a5aa7`— 5 个 input（登录昵称 / 注册邀请码 / 注册昵称 / 注册年龄 / 注册监护人手机号）补齐 `id`/`name` + `<label htmlFor="...">` 关联
- **修复验证**：vitest 42/42 全过；新增回归用例「登录页昵称 input 有 label 关联」验证 id=`login-name`/name=`pseudonym`/label[for=`login-name`] 均存在
- 状态：FIXED | 修复提交：`d7a5aa7` | 复测结果：**VERIFIED ✅（2026-08-10 生产复测）**——昵称 input 存在 `id=login-name`/`name=pseudonym` + `label[for=login-name]` 关联

