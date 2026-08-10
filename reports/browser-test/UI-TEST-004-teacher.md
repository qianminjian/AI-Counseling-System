# UI-TEST-004 教师端 Browser Agent 场景化遍历测试报告

> 起始：2026-08-09 09:58（收尾 10:10） | 工具：chrome-devtools MCP（CDP Input）
> 范围：doing/82 §5.2 教师端 T-01 ~ T-08
> 测试账号：李老师 / 123456（userType=teacher）
> 配套文件：ISSUES-教师端.md（问题清单） / screenshots/T-0X-YY-*.png

## 一、场景执行结果

| 场景 | 标题 | 步骤 | 结果 | 关键观察 |
|------|------|------|------|----------|
| **T-01** | 登录与强制改密 | 4 步 | ✅ | 123456 登录成功；无强制改密流程（账号已非首次登录） |
| **T-02** | 工作台概览 | 3 步 | ✅ | Dashboard 渲染 OK；**导出周报按钮可用**（复测确认，原 P1 误判撤回） |
| **T-03** | 预警队列处置闭环 | 5 步 | ⚠️ 部分 | 列表/筛选/导出 OK；认领+处理+完成需"open"数据，未实测闭环 |
| **T-04** | 学生管理 | 5 步 | ✅ | 列表/档案/导出 CSV/返回/高风险提醒 全部 OK；档案含雷达图+画像 |
| **T-05** | 质量监控 | 2 步 | ⚠️ | 4 KPI/低分会话表/回放抽屉 OK；低分率 92.3% 异常高（BUG-T-T05-01 P2，需口径决策） |
| **T-06** | 通知中心与实时推送 | 2 步 | ⚠️ | 历史通知 OK；**实时推送因 WS 故障失效**（BUG-T-BASE-03 P0，需后端协作） |
| **T-07** | 管理控制台（admin） | 3 步 | ✅ 补测（2026-08-10） | admin 登录（临时密码）→ 数据大屏 → 返回工作台 → admin 专属菜单（平台总览+管理控制台）→ 邀请码管理（列表/状态/停用/删除/批量导入 CSV）+ 平台总览（学校/学生/教师/会话统计+学校列表）全部渲染与交互 OK；结合 API 实证（邀请码生成/审计留痕） |
| **T-08** | 数据大屏 | 3 步 | ✅ | 5 KPI + 4 图区 + 1 班级预警对比；暗色科技风 |

## 二、本轮发现的问题

| BUG | 等级 | 标题 | 状态 |
|-----|------|------|------|
| BUG-T-T02-01 | ~~P1~~ | ~~工作台「导出周报」按钮仅触发埋点~~ | **撤回**：复测 `.click()` 直接调用触发 `GET /api/v1/teacher/report/weekly` 返回 200 + 自动 downloadBlob。原报误判源于 chrome-devtools MCP click 工具对 antd Button wrapper 的 synthetic event 触发不完全（见 §五） |
| BUG-T-BASE-03 | **P0** | WebSocket `wss://yun.gxjugu.com/ws/alerts` 连接失败，实时预警推送瘫痪 | OPEN（需后端协作：检查 WS 端点 + nginx upgrade + subprotocol 握手） |
| BUG-T-T05-01 | P2 | 质量监控「低分率」92.3% 异常（13 评分中 12 低分） | OPEN（需口径决策：分母改总会话数 或 过滤 BFS 痕迹） |
| BUG-T-BASE-01 | P3 | 密码输入框缺 `autocomplete="current-password"`（a11y `issue`） | **FIXED**（commit `3718b97`）— Login.tsx 加 `autoComplete="current-password"`，回归用例 7/7 pass |
| BUG-T-BASE-02 | P3 | 企业微信 OAuth 未配置（控制台 error 噪声） | **FIXED**（本轮）— Login.tsx catch 由 `console.error` 降级为 `console.debug`，回归用例 7/7 pass |

## 三、修复循环

### ✅ FIXED：P3 BUG-T-BASE-01（autocomplete）
- **文件**：`frontend/teacher-web/src/pages/Login.tsx`
- **改动**：密码 input 加 `autoComplete="current-password"`；用户名 input 同补 `autoComplete="username"`
- **验证**：Login.test.tsx 6/6 → 本轮后续加成 1 = 7/7 pass
- **commit**：`3718b97`

### ✅ FIXED：本轮 BUG-T-BASE-02（企业微信 fail noise 降级）
- **文件**：`frontend/teacher-web/src/pages/Login.tsx`
- **改动**：useEffect catch 路径 `console.error` → `console.debug`，文案改为 `wecom auth unavailable, falling back to password login`
- **验证**：Login.test.tsx 7/7 pass（新增回归用例「企业微信获取失败时仅 console.debug，不产生 error 噪声」）

### ⏸ 保留：P0 BUG-T-BASE-03 WebSocket 连接失败
- 前端无能为力，需后端协作。详情见 §五 + ISSUES-教师端.md

### ⏸ 保留：P2 BUG-T-T05-01 低分率口径
- 前端无能为力，需数据/口径决策。详情见 ISSUES-教师端.md

### ✅ 撤回：原 P1 BUG-T-T02-01 导出周报按钮
- 复测证明按钮可用：直接 `.click()` 调 `GET /api/v1/teacher/report/weekly` 200 + downloadBlob。原报误判源于 chrome-devtools MCP click 工具对 antd Button wrapper 的 synthetic event 触发不完全。

## 四、下一阶段

按 doing/82 §5.3 → 启动 **UI-TEST-005 家长端**（P-01~P-06 场景）。
- 测试入口：https://yun.gxjugu.com/parent/
- 测试账号：家庭码 `GHH63G` + 手机号 `13814092745`（待 doing/82 复核）
- 范围：首次注册、孩子档案、周报查看、紧急通知、退订推送

## 五、附：教师端测试经验（沉淀到 tool_experience）

1. **antd Menu 在 chrome-devtools MCP click 下不可靠**：MCP `click` 工具对 Antd v5 Menu 的 `li` 元素点击，React synthetic event 不一定被触发（顶层通过 `li.onClick` 走 `e.onClick?.()` wrapper 时若 `e.onClick` 为空则静默）。**绕道方案**：用 `evaluate_script` 找到目标元素后调 `element.click()` 走 DOM 原生事件。适用范围：所有 Antd Menu / 复合组件按钮（如 OverviewPanel 中的"导出周报"按钮）。
2. **教师端导航实为 state-driven**：App.tsx 没有 react-router，5 个菜单全是 `setTab(key)` 内部状态切换；hash `#/alerts` 等仅为 URL 装饰。**复测注意**：hash 变了不代表 tab 切换成功，必须看 `header span` 显示的标题。
3. **`/teacher/` 与 `/bigscreen` 共用同一 index.html**：`/bigscreen` 由 nginx `alias` 到 `/app/teacher` 路径，App.tsx 通过 `location.pathname === '/bigscreen'` 判断渲染 BigScreen。访问时务必用根级 path，不能写 `/teacher/bigscreen`。

---

## 复测更新（2026-08-09）

- **T-03 认领处置闭环**：已由 L-01/L-05 全链路覆盖（认领→处置→关闭 + SLA 倒计时 + 计数联动 30s 自动刷新）✅
- **T-06 WS 实时推送**：BUG-T-BASE-03 已修复——WS 连接成功（后端日志"教师 WebSocket 已连接"）+ 实时推送实证（通知中心未读 6→7，未手动刷新）✅
- **T-07 管理控制台（admin）**：本轮补测全链路——邀请码生成（BHV2RBBY）/停用（disabled）/CSV 导入（测试己）/审计留痕（IMPORT_STUDENTS JSON 落库）✅（UI 文件上传受 MCP 工具限制，导入经 API 实证）
- **T-08 数据大屏**：重验 5 指标卡 + 图表 ✅；**BUG-T-BASE-04**（大屏"返回工作台"按钮无效）已修复（commit 7bf1db8）
