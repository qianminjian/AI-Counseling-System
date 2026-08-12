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

---

## 第 2 轮（2026-08-11 全面遍历，UI-TEST-012）

### BUG-S-08-1 [P1] 心情日记重复打卡返回 500 系统内部错误
- 场景：S-08 情绪日记 / 每日打卡
- 步骤：1) 首次打卡成功（POST /api/v1/diary/checkin 200）；2) 同天再次打卡不同情绪 → 500
- 实际：POST /api/v1/diary/checkin 返回 HTTP 500 `{"code":10001,"message":"系统内部错误"}`（稳定复现）；前端提示「打卡没成功，请检查网络后再试一次」
- 期望：重复打卡应覆盖更新当天记录（design/05 §4.6「重复打卡 覆盖更新（非新增）」）或返回明确业务提示
- 控制台：`[EmotionDiary] 打卡失败: ApiError: 系统内部错误`（msgid=293/295）
- 截图：UI-TEST-012-student/S-08-05-B-打卡500错误提示.png、S-08-06-B-重复打卡500失败.png
- 根因：EmotionDiary.create 插入日界用 LocalDate.now()（JVM 默认 UTC），查询用 CounselingTimeZone.today()（Asia/Shanghai）——上海 00:00~08:00 窗口期两次打卡双 insert 触发唯一索引冲突 500
- **修复 commit**：`f72ecc2`（EmotionDiaryService 插入日界统一 CounselingTimeZone + BadgeService.computeStreak 同源 + 新增 EmotionDiaryServiceTest 回归）
- 状态：FIXED | 复测结果：**VERIFIED ✅（2026-08-12，UI-TEST-016）**——UI 拦截重复打卡；API 幂等 200 覆盖更新（同 diaryId），趋势图/streak 正常

### BUG-S-08-2 [P3] 打卡失败提示文案误导
- 场景：S-08 情绪日记
- 实际：「打卡没成功，请检查网络」——实际是服务端 500 非网络故障，且未区分「当天已打卡」
- 期望：按错误码展示准确原因
- **修复 commit**：`05c1fe6`（EmotionDiary 按 ApiError.code 区分 10001/业务提示/网络三类文案）
- 状态：FIXED | 复测结果：**VERIFIED ✅（2026-08-12）**——全程未出现「打卡没成功，请检查网络」

### BUG-S-04-01 [P3] 拒绝语音唤醒授权后设置面板仍显示「语音唤醒已开启」
- 场景：S-04 对话室设置面板
- 实际：拒绝授权后设置仍显示「🎙️ 语音唤醒已开启 直接说\"哈喽波波\"就能叫我」
- 期望：拒绝授权后开关显示关闭态，或文案区分「开关已开但未授权」
- 说明：功能逻辑正确（监听需 wakeEnabled && hasConsent），属文案/状态展示歧义
- 截图：UI-TEST-012-student/S-04-04-设置面板音色列表.png
- 状态：OPEN（P3，排期）

### BUG-S-02-01 [P3] 登录页无隐私/协议链接入口
- 场景：S-02 登录页
- 实际：登录页无任何链接，隐私说明仅存在于注册时 ConsentGate 弹层
- 期望：登录页底部提供《隐私政策》/《服务协议》入口（合规惯例）
- 截图：UI-TEST-012-student/S-02-01-登录页控件总览.png
- 状态：OPEN（P3，排期）

