# 三端联动测试汇总报告（UI-TEST-006 专题）

> 专题：doing/82 UI-TEST-006 三端联动场景 L-01~L-06
> 执行日期：2026-08-09 | 环境：生产（yun.gxjugu.com）
> 关联故障修复：BUG-TENANT-01b / BUG-LLM-01 / BUG-LLM-02 / BUG-LLM-03 / BUG-UI-01 / BUG-UI-03 / BUG-UI-05 / BUG-KB-01/02/03 / BUG-AUDIT-01

---

## 一、场景结论总览

| 场景 | 结论 | 关键断言 | 证据 |
|---|---|---|---|
| L-01 学生对话→教师预警处置闭环 | ✅ 通过 | 安全短路/预警到达/处置闭环 | L-01 相关截图（会话内） |
| L-02 会话收束→摘要→教师查看 | ✅ 通过（含 BUG-UI-01 修复） | 摘要生成（≤200 字非原文）/摘要可见/原文不可见 | `tmp/L-02-摘要-修复后.png` |
| L-03 学生数据→家长周报 | ✅ 通过（前期完成） | 周报一致性/风险口径 | 前期记录 |
| L-04 家长撤回→学生冻结 | ✅ 通过（含 BUG-UI-03 修复） | 登录拒绝/教师端冻结+画像删除 | `tmp/L-04-学生冻结.png`、`tmp/L-04-教师冻结.png` |
| L-05 教师干预→学生感知 | ✅ 通过（含 BUG-UI-05 修复） | SLA 倒计时/待办置顶/计数联动（30s 自动刷新） | `tmp/L-05-计数联动.png` |
| L-06 邀请码全链路 | ⚠️ 部分通过（见 §三） | 生成 ✓/注册成功 ✓/教师可见受限 | `tmp/L-06-学生丙注册.png` |

---

## 二、L-01 ~ L-06 执行明细

### L-02 会话收束 → 摘要 → 教师查看
- 学生端（小明 PIN 1234）：2 轮低风险对话 → 满意度⭐⭐⭐⭐ → 会话结束
- 教师端档案 → 对话摘要弹窗：AI 语义摘要（主要话题/情绪趋势/关键点/建议，≤200 字，非原文）
- **修复前**：弹窗同时泄露逐轮对话原文（违反 design/11「摘要而非原文」）→ BUG-UI-01 修复后仅显示摘要卡片 ✅

### L-04 家长撤回 → 学生冻结（跨端联动）
- 前置准备：生产库创建 DEV 租户测试家长（`13800000002`/`123456`）+ 绑定小明（生产原无学校家长账号）
- 家长 API 撤回 → 返回 `status=withdrawn`（冻结+画像删除+留痕）
- 学生端：小明 PIN 登录 → **拒绝**（提示"昵称或 PIN 码错误"，无冻结专属文案——发现项 F-1）
- 教师端：档案页账号状态「冻结」+ 心理画像「暂无画像数据」（student_profiles 已删，库证 profile_rows=0）✅
- **修复前**：教师端无冻结展示且冻结学生从列表消失（BUG-UI-03）→ 修复后列表带「冻结」标识 + 状态列 + 档案状态项 ✅

### L-05 教师 SLA 与计数联动
- 学生丁（教师端导入，DEV 租户，PIN 1234）对话触发 ORANGE（"他们总欺负我，还抢我东西，我好难受"）→ risk_level=2 霸凌/网络欺凌 open
- 教师端工作台：今日待办**置顶**（测试丁/霸凌/待认领）+ **SLA 倒计时**（"剩 7min"，S1 15min 档）✅
- 认领 → 状态 待认领→已认领、时间线→处理中；**待处理预警计数 1→0**
- **修复前**：计数需手动刷新（BUG-UI-05，Dashboard 无数据轮询）→ 修复后 30s 轮询自动更新（实测认领后 45s 内计数自动变 0）✅
- 第一条消息"被同学欺负"未触发（硬规则关键词"被欺负/欺负我"未命中"被同学欺负"，且语义分类 800ms 超时降级）——发现项 F-2

### L-06 邀请码全链路
- admin 生成邀请码 `EZSH285M`（8 位，DEV 租户，maxUses=100）✅
- 学生端注册测试丙（DEMO2026 通道）：注册成功 + PIN 设置 + 家庭码 2YJSQP ✅
- **设计偏差 D-1**：`EZSH285M`（DEV 租户邀请码）被试用注册通道拒收（`validateAndConsumeInviteCode` 仅认 TRIAL 租户）——学校邀请码无注册通道；教师端可见性断言受 TRIAL 租户隔离限制（测试丙不可见，属预期隔离行为，与 doing/82 假设不符）

---

## 三、缺陷与发现清单

### 已修复并部署（8 项，全部验证闭环）

| 编号 | 缺陷 | 根因 | 修复 | 状态 |
|---|---|---|---|---|
| BUG-TENANT-01b | 会话摘要租户 fail-fast | @Async 多 TaskExecutor 无 @Primary 降级 SimpleAsyncTaskExecutor + 无 runAsSystem | @Primary + runAsSystem 包裹 | ✅ 生产验证（线程名/堆栈实证） |
| BUG-LLM-01 | 备用模型 404 | base-url 含 /v1 + 框架补全 /v1 → 双前缀 | normalizeBaseUrl 剥离尾部 /v1 | ✅ 部署后 404 消失 |
| BUG-LLM-02 | 备用模型 400 unknown model | 降级转发未替换 Prompt 模型名 | withFallbackModel（call+stream） | ✅ 降级后 MiniMax 调用成功 |
| BUG-LLM-03 | RAG 知识注入 404 静默失败 | 生产未配 EMBEDDING_*，回退 DeepSeek（无 embeddings 端点） | 独立变量 + DashScope text-embedding-v4（1536 维）+ 归一化 + 语料 52 篇入库发布 | ✅ 检索实测命中（学业压力 0.574 等） |
| BUG-KB-01 | 摄入 SQL 缺 ::vector cast | INSERT 未 cast（search 已有） | SQL 补 `?::vector` | ✅ 摄入成功 |
| BUG-KB-02 | 审核接口不认全局域文档 | findDocumentStatus/transition 按 tenant_id 精确匹配 | 放宽 `(tenant_id = ? OR tenant_id IS NULL)` | ✅ 52 篇 published |
| BUG-KB-03 | 检索恒空 | 相似度阈值 0.7 过滤全部（text-embedding-v4 实测分布 0.46~0.57） | 阈值 0.7→0.45 | ✅ 检索命中 |
| BUG-UI-01 | 教师端摘要弹窗泄露对话原文 | Drawer 展示 getSessionMessages 逐轮原文 | 默认仅摘要卡片；质量监控质控场景显式开启 | ✅ 重验无原文 |
| BUG-UI-03 | 教师端无冻结状态展示 | 学生 VO 无 status + 列表过滤 active | listVisibleStudents + VO status + 前端冻结标识 | ✅ 重验冻结可见 |
| BUG-UI-05 | 工作台计数不自动刷新 | OverviewPanel 仅挂载加载 | 30s usePolling 轮询 | ✅ 认领后自动 1→0 |
| BUG-AUDIT-01 | 审计 detail 非 JSON 致 json 列落库失败（监护人同意/撤回/知识库等 8 处调用，审计静默丢失） | 调用方传普通文本，audit_logs.detail 为 json 类型 | AuditLogService 防御性归一化（普通文本包装 {"message":...}） | ✅ 生产验证 GUARDIAN_CONSENT 落库为 JSON |
| F-1 | 冻结账号登录提示笼统（"昵称或 PIN 码错误"，学生无法理解） | 登录链路过滤 withdrawn 状态，无专属提示 | 查询放宽含 withdrawn + 冻结专属提示（PIN/密码双通道，User.STATUS_WITHDRAWN 收敛） | ✅ 生产验证小明登录显示"账号已冻结" |
| F-2 | 风险硬规则关键词漏报（"被同学欺负"不命中"被欺负"） | 关键词中间插词变体未覆盖 | ORANGE/霸凌分类词典扩充 4 个高频变体 | ✅ 生产验证原漏报语句硬规则命中 ORANGE |
| F-8 | OnnxRuntime session_create 极慢（主线程+Worker 单线程 30-60s）| numThreads=1 受限主线程 pthread Worker | F-8 主线程 numThreads=1→2（commit 8a8ae59）+ F-8-Worker 同步（commit 4d5b4bd）+ 双埋点（commit acbdc47）| ✅ 实测 声纹主线程 261-474ms / 唤醒 Worker 557ms（加速 50-100×） |
| BUG-SW-01 | workbox 导航 fetch 失败→ SW 接管→ 唤醒引擎重置 | vite-plugin-pwa 强制 autoUpdate 强制注册 SW | 学生端 vite.config `VitePWA({ disable: true })`（commit 9d8e566）| ✅ 部署后 nav fetch 不再被 SW 拦截 |

### 待处理发现项

| 编号 | 级别 | 描述 | 建议 |
|---|---|---|---|
| F-2 | P2 | 语义分类 800ms 门禁频繁超时降级硬规则（关键词扩充已修复漏报，语义链路仍待评估） | 观察 LLM 主模型延迟；评估门禁/超时配置与备用模型调度 |
| F-4 | P2 | 企微告警 webhook 未配置（`URI is not absolute` 降级日志），SLA 升级/提醒仅落日志 | 配置 ALERT_WECOM_WEBHOOK_URL |
| D-1 | 设计 | 学校邀请码（admin 生成）无注册通道（试用注册仅认 TRIAL 租户）；L-06「教师可见」断言与 TRIAL 隔离架构不符 | 设计决策：学校邀请码注册通道（注册进邀请码租户）或明确学校学生仅走教师导入 |

---

## 四、测试数据变更清单（生产库）

| 数据 | 说明 |
|---|---|
| 用户 `admin`（user_type=admin） | 知识库管理/邀请码生成（密码已单独提供，建议修改） |
| 用户 `测试丁`（student，DEV 租户 grade5 class1，PIN 1234） | 教师导入 + PIN（L-05 触发者） |
| 用户 `测试丙`（trial_student，TRIAL 租户，PIN 1234） | DEMO2026 邀请码注册（L-06） |
| 家长 `测试家长`（phone 13800000002/123456，DEV 租户）+ 绑定小明 | L-04 撤回动作执行者 |
| 知识库 52 篇 published（10 篇危机类缓入） | BUG-LLM-03 全链路 |
| 小明账号 status=withdrawn（撤回冻结，不可逆需重新授权） | L-04 终态 |

---

## 五、部署记录（2026-08-09）

| 时间 | 组件 | commit | 冒烟 |
|---|---|---|---|
| 13:01 | backend | a116be4（BUG-LLM-02） | 32/32 |
| 13:24 | teacher | cdf905f（BUG-UI-01） | nginx 校验 |
| 13:35 | backend | fcf5e83（BUG-LLM-03） | 32/32 |
| 13:52 | backend | b915514（BUG-KB-01） | 32/32 |
| 14:10 | backend | d087407（BUG-KB-02） | 32/32 |
| 14:25 | backend | f3feb57（BUG-KB-03） | 32/32 |
| 15:00 | backend + teacher | 21a7864（BUG-UI-03/05） | 32/32 ×2 |
| 19:30 | backend | 8c72b55（BUG-AUDIT-01）+ 6364be7（F-1/F-2） | 32/32 |
| 20:30 | student | 5f73d23（声纹进度修复）+ 4d5b4bd（F-8-Worker）+ acbdc47（session_create 埋点）+ 0c61f96（prepare-models retry）+ 9d8e566（BUG-SW-01 禁 PWA） | nginx 校验 |
