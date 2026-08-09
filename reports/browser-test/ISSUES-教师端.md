# 教师端 Browser Agent 遍历测试问题清单

> 起始：2026-08-09 | 执行轮次：第 1 轮 | 测试账号：李老师（userType=teacher，密码 123456）
> 来源：doing/82 §5.2 T-01~T-08 + §6.3 提示词

## 静态基线（§3.4）

| # | 检查项 | 结果 | 备注 |
|---|--------|------|------|
| B0-1 | 教师端首页 HTTP 200 + 关键元素渲染 | ✅ | /teacher/ 200，关键 antd 控件 + Dashboard 指标卡正常 |
| B0-2 | 控制台无 error | ⚠️ 1 项降级 | msgid=85 `[Login] 获取企业微信登录地址失败: ApiError: 企业微信未配置` — 非阻塞，企业微信 OAuth 未启用 |
| B0-3 | 无 404 资源请求 | ✅ | CSS/JS/index 全部 200/304，无 404 |

---

## Bug 总览

| 编号 | 等级 | 场景 | 标题 | 状态 |
|------|------|------|------|------|
| BUG-T-BASE-01 | P3 | 登录页 | 密码输入框缺 `autocomplete="current-password"` 属性（a11y `issue` 警告） | **FIXED**（commit `3718b97`） |
| BUG-T-BASE-02 | P3 | 登录页 | 企业微信登录失败（`企业微信未配置`）— 控制台 noise | **FIXED**（commit `b30bf79`，降级为 `console.debug`，无 error 噪声；OAuth 仍需运维配 CorpID/AgentID/Secret） |
| BUG-T-BASE-03 | P0 | 全局 | WebSocket `wss://yun.gxjugu.com/ws/alerts` 连接失败，**实时预警推送瘫痪** | **VERIFIED**（2026-08-10 复测通过：curl http1.1 握手 101 + 浏览器 open+pong（protocol=alerts.v1）+ 后端日志“教师 WebSocket 已连接 在线=2”；首连偶发失败由 hook 5s 重连兑底，最终稳定连接） |
| BUG-T-T02-01 | **不成立** | ~~工作台「导出周报」按钮无任何动作~~ | **复测证明按钮可用**：直接 `.click()` 调用触发 `GET /api/v1/teacher/report/weekly` 返回 200 + 自动 downloadBlob 弹出下载。早先误判源于 chrome-devtools MCP click 工具对 Ant Design Button wrapper 的 synthetic event 触发不完全（详见 teacher-tool-experience） |
| BUG-T-T05-01 | P2 | 质量监控 | 低分率 92.3% 异常高 — 测评样本过低或计算口径需校对 | **VERIFIED**（2026-08-10：根因为 7/29 BFS 压测 13 条刻意低分评价污染；UAT 授权清理（satisfaction_rating 置 NULL，保留会话）；API 复测 flagRate=0.0%、avgRating=4.3） |

---

## BUG 详情

### BUG-T-BASE-01 [P3] 密码输入框缺 autocomplete 属性 — ✅ FIXED（commit `3718b97`）
- 场景：登录页（李老师输入密码框）
- 步骤：T-01 登录步骤 2
- 期望：`<input type="password" autocomplete="current-password">`
- 实际：浏览器 a11y 报告 `An element doesn't have an autocomplete attribute (suggested: "current-password")`
- 根因：`teacher-web/src/pages/Login.tsx` 密码输入框未声明 autocomplete 属性
- **修复内容**：`frontend/teacher-web/src/pages/Login.tsx` 第 83/87 行 — 用户名 input 加 `autoComplete="username"`，密码 input 加 `autoComplete="current-password"`（顺手统一加用户名，消该类 a11y 提示）
- **回归测试**：`Login.test.tsx` 新增用例《用户名/密码 input 应具备 a11y 自动填充属性》— 验证 `input.autocomplete === 'username' | 'current-password'`
- **验证结果**：vitest 1 file / 6 tests passed → 后续加成 1 = 7 passed

### BUG-T-BASE-02 [P3] 企业微信登录地址获取失败 — ✅ FIXED（noise 降级；OAuth 启用仍需运维配置）
- 场景：登录页加载时
- 步骤：T-01 登录步骤 0（首次渲染）
- 期望：企业微信 OAuth 在配置存在时可用；未配置时控制台不产生 error 噪声
- 实际：getWecomAuthUrl reject → catch 中 `console.error('[Login] 获取企业微信登录地址失败:', e)` 产生 error 级别控制台信息
- 控制台：msgid=85 [error] → 修复后降为 [debug]
- 根因：合理降级路径不该以 error 级别上报；配置缺失为预期状态
- **修复内容**：`frontend/teacher-web/src/pages/Login.tsx` 第 12–19 行 useEffect catch — `console.error` 改为 `console.debug`，文案明确「fallback to password login」
- **回归测试**：`Login.test.tsx` 新增用例《企业微信获取失败时仅 console.debug，不产生 error 噪声》— `vi.spyOn(console, 'error')` 断言未被调用；`vi.spyOn(console, 'debug')` 断言被调用
- **验证结果**：vitest 7/7 passed
- **未解决部分**：实际启用企业微信登录仍需运维配置 `WECOM_CORP_ID` / `WECOM_AGENT_ID` / `WECOM_SECRET`（后端 API `/api/v1/auth/wecom/auth-url` 依赖），与本 BUG 修复无关

### BUG-T-BASE-03 [P0] WebSocket `/ws/alerts` 实时预警推送连接失败 — ✅ VERIFIED（2026-08-10 复测）
- **复测证据（2026-08-10）**：
  1. curl `--http1.1` + Upgrade 头 + 真实教师 token → `HTTP/1.1 101 Switching Protocols`（nginx → 后端全链路通）
  2. 浏览器实测 `new WebSocket(wss://host/ws/alerts, ['alerts.v1', 'auth.<jwt>'])` → `open + pong`（protocol=alerts.v1 协商成功）
  3. 后端日志实证：`教师 WebSocket 已连接: tenant=1, user=2, 当前在线=2`（页面 hook 连接成功）
- **根因复盘**：服务器链路（nginx `location /ws/` → 18082 + 后端 subprotocol 认证）均正常；8/9 测试失败时的连接拒绝（code 1008）源于当时 token 无效（“无效/非 access/已登出”日志），现链路验证通过
- **遗留观察**：页面加载首连偶发一次 `closed before established`，hook 自带 5s 重连兜底后稳定连接（在线数保持）——产品行为可接受，无需修复

### BUG-T-T02-01 [P1] 工作台「导出周报」按钮无动作
- 场景：T-02 工作台概览 / 顶部 KPI 区旁"导出周报（可打印 PDF）"按钮
- 步骤：T-02 步骤 3（点击导出按钮）
- 期望：触发 PDF 下载或打开打印预览（按钮文案含"可打印 PDF"）
- 实际：仅触发埋点 `uL('openWeeklyReport', 'weekly_report.pdf')`，**未发起任何 GET 请求**（无 `/api/v1/teacher/report/weekly` 调用记录）；无新窗口、无下载、无打印预览
- 控制台：msgid=对应埋点事件
- 网络：`list_network_requests` 过滤 `export|weekly|weekly_report` 无命中
- 截图：T-02-01 工作台 fullPage（按钮可见）
- **疑似根因**：`teacher-web/src/components/teacher/OverviewPanel.tsx` 中按钮 onClick 经 antd Button wrapper 后 `li` 层 `onClick` 调用 `e.onClick?.(...)`，但 `e.onClick` 在最里层 button props 上缺失，wrapper 兜底失败；按钮实际绑定的 React onClick（fiber 第 3 层）为 `()=>uL('openWeeklyReport', 'weekly_report.pdf')`，这是埋点事件，不是 `openWeeklyReport()` 函数调用
- **修复方案**：将按钮 onClick 改为直接调用 `openWeeklyReport()`（=`() => downloadBlob('openWeeklyReport', 'weekly_report.pdf')`），不应包裹在埋点 wrapper 内

### BUG-T-T05-01 [P2] 低分率 92.3% 异常高 — ✅ VERIFIED（2026-08-10 数据清理）
- **根因确认**：13 条已评会话中 12 条低分（≤2★）全部来自 7/29 BFS 自动化压测（小明账号刻意低分反馈）；8/9 真实测试 4 条为 4-5★
- **处理**：UAT 环境授权内将 7/29 压测评分置 NULL（`satisfaction_rating`，会话本身保留）
- **复测**：`GET /teacher/quality/stats` → flaggedCount=0、flagRate=0.0%、avgRating=4.3 ✅
- **口径说明**：flagRate = 低分(≤2★)/总评分 为行业标准定义，未改代码；长期防污染建议（测试痕迹用 tenantId 隔离/时间窗口过滤）已在设计中记录

---

## 测试覆盖率

| 场景 | 完成 | 备注 |
|------|------|------|
| T-01 登录与强制改密 | ✅ | 无强制改密流程（账号非首次登录） |
| T-02 工作台概览 | ⚠️ | 渲染 OK；导出周报按钮可用（复测确认） |
| T-03 预警队列处置闭环 | ⚠️ 部分 | 列表/筛选/导出 OK；认领+处理+完成 需"open"状态活跃预警，**当前数据无，无法实测闭环** |
| T-04 学生管理 | ✅ | 列表/档案/导出 CSV/返回/高风险提醒均 OK |
| T-05 质量监控 | ✅ | 4 KPI/低分会话表/回放抽屉/导出 PDF 入口全部 OK；另发现低分率异常（BUG-T-T05-01） |
| T-06 通知中心 | ✅ | 历史通知已读列表展示 OK；实时推送因 WS 故障无法实测 |
| T-07 管理控制台 | ⏭ 跳过 | 李老师 userType=teacher，菜单不渲染"管理控制台 / 平台总览"，**需 admin 账号才能覆盖** |
| T-08 数据大屏 | ✅ | 5 KPI + 4 图区 + 1 班级预警对比；全屏暗色科技风 |

---

## 待办与下一步

1. **【需后端协作】修复 P0 BUG-T-BASE-03 WebSocket**：查后端 alerts WS 端点 + nginx upgrade 配置 + subprotocol 握手（见 §BUG 详情）
2. ~~修复 P3 BUG-T-BASE-01 autocomplete~~ — **已 fix (commit `3718b97`)**，同 PR/工作回合重复 dev 后复测
3. ~~修复 P3 BUG-T-BASE-02 wecom noise~~ — **已 fix (本轮)**，实际启用 OAuth 仍需运维配 CorpID/AgentID/Secret
4. **【需口径决策】修 BUG-T-T05-01 低分率口径**：分母改为"总会话数" 或 过滤 BFS 痕迹（7/29 压测样本）
5. **【需额外账号】补 T-07 管理控制台测试**：需 ADMIN 账号；可在下一轮创建专用账号或借现网商
6. **进入 UI-TEST-005 家长端**（P-01~P-06），本轮下一个 ticket

