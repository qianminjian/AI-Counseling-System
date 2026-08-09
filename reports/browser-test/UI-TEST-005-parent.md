# UI-TEST-005 家长端 Browser Agent 场景化遍历测试报告

> 起始：2026-08-09 10:13（收尾 10:23） | 工具：chrome-devtools MCP（CDP Input）
> 范围：doing/82 §5.3 家长端 P-01 ~ P-06
> 测试账号：13800000001 / Parent@2026（新注册；GHH63G 学生绑定）
> 配套文件：ISSUES-家长端.md（问题清单） / screenshots/P-0X-YY-*.png

## 一、场景执行结果

| 场景 | 标题 | 步骤 | 结果 | 关键观察 |
|------|------|------|------|----------|
| **P-01** | 家庭码注册 | 3 步 | ✅ | 错误家庭码拒绝 ✅；新手机号注册 → 跳 /report ✅ |
| **P-02** | 手机+密码登录 | 2 步 | ✅ | 13800000001 + Parent@2026 → 跳 /report；错误密码 → 拒绝 |
| **P-03** | 情绪周报 | 2 步 | ⚠️ | report API 返回"链接已失效" HTTP 401；情绪分布/对话次数/风险状态/AI 建议/无对话原文 全部无法断言（BUG-P-P03-01） |
| **P-04** | 同意管理-查看 | 1 步 | ⚠️ | 渲染 OK；**缺少"授权状态/时间/版本"展示**（BUG-P-P04-01） |
| **P-05** | 同意撤回 | 3 步 | ⚠️ | 二次确认弹窗 OK（文案过简 BUG-P-P05-01）；确认撤回 → HTTP 401 业务错误（BUG-P-P05-02） |
| **P-06** | 隐私页 | 1 步 | ✅ | 标题/副标题/4 章节内容完整；"← 返回登录" 按钮 OK |

## 二、本轮发现的问题

| BUG | 等级 | 标题 | 状态 |
|-----|------|------|------|
| BUG-P-P03-01 | **P1** | `/parent/report` 在"链接失效"等业务错误时返回 HTTP 401（应 4xx 业务码） | OPEN（需后端协作） |
| BUG-P-P05-02 | **P1** | `/parent/consent/withdraw` 在已撤回状态返回 HTTP 401（应 4xx/200 幂等） | OPEN（需后端协作） |
| BUG-P-P04-01 | P2 | 同意管理页缺少"授权状态/时间/版本"展示 | OPEN（前端实现缺失） |
| BUG-P-P05-01 | P3 | 二次确认弹窗文案过于简单，缺少"冻结账号/删除画像"具体警示 | OPEN（设计/文案） |
| ~~BUG-P-P01-01~~ | — | ~~手机号 spinbutton 显示与 React state 不一致~~ | **撤回**：fetch body 传值正确，ARIA valuenow 与 React state 不一致是 chrome-devtools 报告误差，非前端 bug |

## 三、修复循环

### ⏸ 保留：P1 BUG-P-P03-01 / BUG-P-P05-02（HTTP 状态码错误）
- 后端需修复 `parent-h5` 服务端：业务失败应 HTTP 4xx（400/403/409）+ 业务码；认证失败才 HTTP 401
- 影响：401 refresh-token 重放会被错误触发；前端 message 展示体验差

### ⏸ 保留：P2 BUG-P-P04-01（授权状态展示缺失）
- 前端实现缺失：ConsentPage 缺 `getConsentStatus` + 状态卡片
- 优先级：中；不影响核心业务但影响家长透明度

### ⏸ 保留：P3 BUG-P-P05-01（确认文案过简）
- 前端文案优化：补"冻结账号/删除画像"两项具体警示
- 优先级：低；现有"不可逆"已能传达核心风险

### ⏸ 测试数据问题
- 学生甲家长（13814092745）密码未知；同意状态已被前置流程撤回
- 影响 P-03 周报数据场景无法演示；建议运维提供 `seed-data-reset` 脚本

## 四、下一阶段

按 doing/82 §5.4 → 启动 **UI-TEST-006 联动场景**（L-01 ~ L-06）。
- 依赖前三端账号与数据
- L-04 联动特别依赖 P-05 已执行撤回

## 五、附：家长端测试经验（沉淀到 tool_experience）

1. **Taro H5 端 Button 是 `TARO-BUTTON-CORE` 自定义元素**：不能用 `document.querySelector('button')` 找到，需用 `.btn-danger` / `.btn-secondary` 等 class。修复方案：可用 Taro `getElementsByTagName('TARO-BUTTON-CORE')`。
2. **chrome-devtools ARIA spinbutton valuenow 不等于 React state**：`Input type="number"` ARIA 报告 valuenow 是浏览器 numeric value（可能因 rounding 显示不一致），但 React state 实际值是 string。判定手机号/数字是否正确以 `evaluate_script` 的 `input.value` 或 fetch body 为准。
3. **家长端登录后默认跳 `/parent/report`**：登录成功 → `redirectTo('/report')`；如果 report API 报错会展示 message，**不会重新跳登录页**（token 没失效）。
4. **`fetch` hook 在新 fetch 调用前需要重新设置**：切换页面或重新发起 fetch 后前一次 hook 可能失效。建议每次需要拦截前重新 `window.__lastFetchBody = null` 并重新 hook `window.fetch`。
5. **MCP `click(uid)` 对 Taro 组件不可靠**：Taro 组件在 a11y 树中常显示为嵌套 StaticText 而非 button，需要用 `evaluate_script` + class 选择器点击（如 `.btn-danger`）。
6. **家长端 BUG 集中在后端**：本轮发现的 2 个 P1 都是 HTTP 状态码错误，需后端协作；前端 P3 文案/状态展示可单独修复。
---

## 复测更新（2026-08-09）

- **P-03 情绪周报**：BUG-P-P03-01 已修复——撤回后周报返回业务码 20011"监护人同意已撤回，链接已失效"（HTTP 200 + 业务码，非 401），前端错误区正常展示 ✅
- **P-04 同意管理**：BUG-P-P04-01 已实现——授权状态"已撤回"徽标 + 授权时间/政策版本/撤回时间展示 ✅（commit 相关：前端已部署）
- **P-05 同意撤回**：BUG-P-P05-02 已修复——撤回链路经 L-04 全流程验证（冻结+画像删除+留痕）；已撤回状态撤回按钮禁用 + 文案完善 ✅
- **BUG-P-P06-01（新增）**：撤回学生从家长 children 消失致周报卡"加载中"——getLinkedStudents 放宽（commit a1b26a4）+ 前端无孩子空态；复测 children 含 withdrawn 学生、周报显示业务错误 ✅
