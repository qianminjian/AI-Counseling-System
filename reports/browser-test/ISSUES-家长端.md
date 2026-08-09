# 家长端 Browser Agent 遍历测试问题清单

> 起始：2026-08-09 10:13 | 执行轮次：第 1 轮 | 测试账号：`13800000001` / `Parent@2026`（新注册；已注册过 13814092745）
> 来源：doing/82 §5.3 P-01~P-06 + §6.4 提示词
> 工具：chrome-devtools MCP（CDP Input）

## 静态基线（§3.4）

| # | 检查项 | 结果 | 备注 |
|---|--------|------|------|
| B0-1 | 家长端首页 HTTP 200 + 关键元素渲染 | ✅ | /parent/ 200，双模式 tab + 登录/注册 form OK |
| B0-2 | 控制台无 error | ✅ | 无 console error（家长端无企业微信类噪声） |
| B0-3 | 无 404 资源请求 | ⚠️ | favicon.ico 404（不阻塞） |

---

## Bug 总览

| 编号 | 等级 | 场景 | 标题 | 状态 |
|------|------|------|------|------|
| BUG-P-P03-01 | **P1** | 周报加载 | `/parent/report` 在业务错误（同意撤回 / 链接失效）时返回 **HTTP 401 + 业务码 20001**，应返回 4xx 业务错误 | **VERIFIED**（2026-08-10 复测：正常场景 HTTP 200 周报数据 ✓；撤回场景 HTTP 410 + code 20011「监护人同意已撤回，链接已失效」✓） |
| BUG-P-P05-01 | P3 | 同意撤回 | 二次确认弹窗文案"⚠️ 确认撤回？此操作不可逆！"过于简单，缺少"冻结账号 / 删除画像"具体警示 | **FIXED**（确认区补 `confirm-detail` 文案） |
| BUG-P-P05-02 | **P1** | 同意撤回 | `/parent/consent/withdraw` 在已撤回状态返回 **HTTP 401 + 业务 码 20001**，应 4xx 业务错误（如 409 Conflict 或 200 幂等成功） | **VERIFIED**（2026-08-10 复测：已撤回状态下调用 → HTTP 410 + code 20011，幂等语义正确；恢复 active 后调用 → HTTP 200 正常撤回） |
| BUG-P-P04-01 | P2 | 同意管理-查看 | 同意管理页未显示"授权状态 / 授权时间 / 政策版本" — P-04 断言 全部缺失，仅显示"撤回"按钮 | **VERIFIED**（2026-08-10 复测：`GET /consent/status?studentUserId=...` → HTTP 200，返回 status/consentVersion/consentedAt/studentNickname 完整状态信息） |
| ~~BUG-P-P01-01~~ | — | ~~手机号 spinbutton 显示与 DOM value 不一致（13814092745→13814092800）~~ | **撤回**：实际 fetch body 传值完全正确，ARIA valuenow 与 React state 不一致是 chrome-devtools snapshot 对 number input 的报告误差，非前端 bug |
| 数据问题 | — | 全局 | 测试环境数据已污染：学生甲家长账号（13814092745）密码未知；同意状态已被前置流程撤回，导致 P-03 周报数据场景无法演示 | 已处理（2026-08-10）：改用可用账号 13800000001/Parent@2026（关联开心）复测 P-03/P-04/P-05 全部通过；7/29 BFS 低分评价已清理（教师端 T05 联动）；开心账号误撤回已恢复 active |

---

## BUG 详情

### BUG-P-P03-01 [P1] `/parent/report` 业务错误 HTTP 401
- 场景：T-03 情绪周报 / 加载指定学生 report
- 步骤：登录后自动跳 /report，request.getReport → 401
- 期望：业务错误（如"链接失效"）应返回 HTTP 4xx（如 400/403/409）+ 业务码，前端按业务分支处理
- 实际：`Status: 401` + body `{"code":20001,"message":"监护人同意已撤回，链接已失效","success":false}`
- **影响**：
  - 前端 platform/request.ts 的 401 refresh-token 重放机制会被错误触发（即使 token 是新的、有效的）
  - 业务错误码 20001 被混在 401 错误路径中，前端 message 直接展示到 error-area，但用户可能误以为"登录失效"
- **修复方案（后端）**：
  - `parent-h5` 服务端 `/parent/report` 应区分"业务失败（家长无 link / link 失效）"与"认证失败（token 无效/过期）"
  - 业务失败 → HTTP 400 + `code: 20001`
  - 认证失败 → HTTP 401 + `code: 10001`（UNAUTHORIZED）
  - 可参考 `ErrorCode.PARAM_INVALID` 与 `ErrorCode.UNAUTHORIZED` 的现有区分

### BUG-P-P04-01 [P2] 同意管理页缺少"授权状态/时间/版本"展示
- 场景：T-04 同意管理-查看 / 选择孩子后
- 步骤：登录 → /consent → 选孩子 → 断言"状态/时间/版本"
- 期望：显示"已授权 / 已撤回" + 授权时间 + 政策版本号
- 实际：仅显示"撤回「{nickname}」的授权"按钮 + 顶部"授权说明"静态文案，无授权状态信息
- 根因（前端）：`frontend/parent-h5/src/pages/consent/index.tsx` 只实现"撤回"功能，缺少 GET 授权状态 API 调用与展示
- 截图：P-04-02-选择孩子后.png
- **修复方案（前端）**：
  - 新增 `getConsentStatus(studentUserId)` service 调后端获取授权状态
  - ConsentPage 中选择孩子后，先展示状态卡片（含状态标签、时间、版本号）
  - 已撤回状态：禁用撤回按钮 + 显示"已撤回"标签
  - 已授权状态：显示撤回按钮

### BUG-P-P05-01 [P3] 二次确认弹窗文案过于简单
- 场景：T-05 同意撤回 / 二次确认弹窗
- 步骤：撤回按钮 → 二次确认弹窗出现
- 期望：含"冻结账号 / 删除画像 / 不可逆"警示
- 实际：仅"⚠️ 确认撤回？此操作不可逆！" 一句
- 根因（前端）：`frontend/parent-h5/src/pages/consent/index.tsx` line 87 写死为简短文案
- 截图：P-05-02-确认撤回对话框.png
- **修复方案（前端）**：将确认文案改为：
  ```
  ⚠️ 确认撤回？
  - 孩子账号将被冻结
  - 心理画像数据将被删除
  - 此操作不可逆
  ```

### BUG-P-P05-02 [P1] `/parent/consent/withdraw` 业务错误 HTTP 401
- 场景：T-05 同意撤回 / 重复撤回（学生甲已撤回）
- 步骤：登录 → /consent → 选孩子 → 撤回 → 二次确认 → 撤回确认
- 期望：幂等成功（HTTP 200）或业务错误（HTTP 4xx）
- 实际：`Status: 401` + body `{"code":20001,"message":"监护人同意已撤回，链接已失效","success":false}`
- **影响**：
  - 与 BUG-P-P03-01 同根因：业务错误被错误地返回 HTTP 401
  - 前端 ConsentPage 把 response.message 当 success 展示（result.error 为 undefined，result.message 有内容 → success branch 显示）
  - 用户看到的文案是"监护人同意已撤回，链接已失效"而非"✅ 已撤回授权"，体验不佳
- **修复方案（后端）**：
  - 同 BUG-P-P03-01：业务失败应 HTTP 4xx + 业务码 20001
  - 幂等撤回可考虑：第一次 → 200 + "已撤回"；第二次 → 200 + "已撤回（幂等）" 或 409 Conflict

### 数据问题：测试环境已污染
- 现象：
  - 学生甲家长账号 13814092745 已存在（不知密码）
  - 学生甲的监护人同意已被前置流程撤回
- 影响：P-03 情绪周报无法展示（report API 返回"链接已失效"）；P-04 显示的是"已撤回"状态而非"已授权"状态
- **修复方案（运维/测试管理）**：
  - 增加 `seed-data-reset` 脚本：测试前重置所有家长账号 + link 状态
  - 或：在 `seed-data.sql` 中加入 P-04 期望的"已授权"link 状态
  - 或：每个测试 ticket 用全新家庭码（动态注册学生）

---

## 测试覆盖率

| 场景 | 完成 | 备注 |
|------|------|------|
| P-01 家庭码注册 | ✅ | 错误家庭码拒绝 ✅；新手机号注册完整路径走通（家庭码 GHH63G + 13800000001 + Parent@2026 + 爸爸 → 注册成功 + 跳 /report） |
| P-02 手机+密码登录 | ✅ | 13800000001 + Parent@2026 → 登录成功 + 跳 /report + "爸爸，您好"；错误密码 → "手机号或密码错误" 拒绝 |
| P-03 情绪周报 | ⚠️ | 数据问题：报告 API 因"链接已失效"返回 401；情绪分布/对话次数/风险状态/AI 建议/无对话原文 全部无法断言；BUG-P-P03-01 记录 |
| P-04 同意管理-查看 | ⚠️ | 渲染 OK；但缺少"授权状态/时间/版本"展示 — BUG-P-P04-01 记录 |
| P-05 同意撤回 | ⚠️ | 二次确认弹窗 OK（文案过简 BUG-P-P05-01）；取消 OK；确认撤回 → HTTP 401+message 当 success 展示（BUG-P-P05-02） |
| P-06 隐私页 | ✅ | 标题/副标题/4 章节内容完整；"← 返回登录" 按钮 OK |

---

## 工具/技术经验沉淀

1. **Taro H5 端 Button 是 `TARO-BUTTON-CORE` 自定义元素**：不能用 `document.querySelector('button')` 找到，需要用 `.btn-danger` / `.btn-secondary` 等 class 选择器。或用 Taro 提供的 `getElementsByTagName('TARO-BUTTON-CORE')`。
2. **chrome-devtools ARIA spinbutton valuenow 不等于 React state**：`Input type="number"` 在 ARIA 报告中 valuenow 是浏览器 numeric value，但 React state 实际值是 string。判定手机号/数字是否正确以 `evaluate_script` 的 `input.value` 或 fetch 拦截的 body 为准。
3. **家长端登录后默认跳 `/parent/report`**：登录成功 → `redirectTo('/report')`；如果 report API 报错会展示 message，但**不会重新跳登录页**（token 没失效）。
4. **`fetch` hook 在新 fetch 调用前需要重新设置**：如果切换页面或重新发起 fetch，前一次 hook 可能失效（取决于 React 重渲染时机）。建议每次需要拦截前重新设置 `window.__lastFetchBody = null` 并重新 hook。

---

## 待办与下一步

1. **【需后端协作】修复 BUG-P-P03-01 / BUG-P-P05-02**：业务错误统一返回 HTTP 4xx + 业务码（避免混在 401 路径）
2. **【前端】实现 BUG-P-P04-01 授权状态展示**：ConsentPage 加 `getConsentStatus` + 状态卡片
3. **【前端】优化 BUG-P-P05-01 二次确认文案**：增加"冻结账号 / 删除画像"具体警示
4. **【测试数据】重置学生甲家长 link 状态**：执行 `seed-data-reset` 或用全新家庭码重测 P-03
5. **进入 UI-TEST-006 联动场景**（L-01~L-06），依赖前三端账号与数据