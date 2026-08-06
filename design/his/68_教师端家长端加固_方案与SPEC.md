# 68 教师端/家长端加固（ARCH-008）方案与 SPEC

> 关联任务：ARCH-008（深度审计 F-6/F-7/F-8 + P2-9/10/12/13 + OVD-6 回填，登记 TASK-TRACKER §二十八）
> 状态：✅ 已实施（ARCH-008 全量完成，2026-08-06）
> 依据：深度审计 2026-08-05（F-6 teacher-web 401 刷新缺失 / F-7 两端零契约防线 / F-8 token XSS 面 + CSP；P2-9 三端 token 策略不一致 / P2-10 设计 token 漂移 / P2-12 console.info 残留 / P2-13 BigScreen 静默；OVD-6 schemaValidator 评估）
> 词汇：接缝 / 契约防线 / 失败安全——见 [13 领域词汇表](../13_领域词汇表.md)

---

## 1. 背景与问题

student-h5 的接缝治理（ARCH-005）不覆盖另两端，审计实测：

| ID | 问题 | 证据 | 影响 |
|----|------|------|------|
| F-6 | teacher-web 5 处导出裸 fetch 无 401 刷新；`importStudentsCsv` 401 直接清 token 不尝试刷新 | `teacher-web/src/api.ts` L218-333/L335-352 | 教师端批量操作 token 过期即失败/登出 |
| F-7 | parent-h5 零契约防线（无 openapi 快照/schema 校验）；teacher-web 手写 VO 类型同样零保护 | 两端均无 `__contract__` | 两端接口变更静默破坏 |
| F-8 | token 存 localStorage 的 XSS 读取面 + CSP 缺失（已登记 P2/P3 未冻结） | teacher-web api.ts L1-2、parent-h5 auth.ts L1-3 | 存储型 XSS 即 token 窃取 |
| P2-9 | 三端 token 存储策略不一致（localStorage/httpOnly cookie 混用） | 三端 api/auth 层 | 安全基线不统一 |
| P2-10 | 三端设计 token 值不一致（均与 design/08 §4.1 表不同） | 三端样式文件 vs design/08 | 品牌一致性漂移 |
| P2-12 | console.info 残留 22 处（实测，全在 student-h5） | 三端源码 | 生产噪音 |
| P2-13 | BigScreen 失败静默（数据加载失败无提示） | teacher-web BigScreen 相关 | 大屏空态无反馈 |
| OVD-6 | 手写 schemaValidator（~200 行）vs openapi-typescript + zod | parent-h5 schema 层 | 可评估替换 |

## 2. 目标与非目标

**目标**：
- teacher-web API 层统一 authFetch（401 刷新+重放），导出链修复
- 两端补轻量契约防线（openapi 快照或端点清单校验，student-h5 模式对齐）
- token 存储策略三端统一（短期方案）+ CSP 头（nginx 层）
- 设计 token 与 design/19 对齐（或反向修正文档）
- 噪音与静默清理（console.info、BigScreen 失败提示）

**非目标**：
- httpOnly cookie 全面迁移（若评估结论为远期，登记冻结跟踪）
- 三端全部契约测试（同 student-h5 深度）——先补端点清单级防线
- 前端 UI 重构

## 3. 设计方案

### 3.1 F-6 · teacher-web authFetch 统一

- 将 student-h5 `api.ts` 的 `authFetch`（401 刷新+重放）模式移植到 teacher-web（teacher-web 现无此封装）
- 5 处导出裸 fetch 与 `importStudentsCsv` 全部改走统一封装；401 不再直接清 token，先刷新重试一次
- parent-h5 同步评估（其 auth.ts 是否有同缺陷，审计 F-6 只点名 teacher-web，实施时顺带核查）

### 3.2 F-7 · 两端契约防线（轻量起步）

- 方案（doing/61 D-5 精神：KISS）：两端各建 `FRONTEND_ENDPOINTS` 式端点清单契约测试（对齐 student-h5 `apiContract.test.ts` 模式），先覆盖核心端点
- OVD-6 评估：手写 schemaValidator（~200 行）在 parent-h5 的现状 vs openapi-typescript + zod——**评估后决定替换与否**；若替换，产出构建期类型生成 + 运行时校验；若保留，明确其职责边界并补测试

### 3.3 F-8 · token 存储与 CSP

- **短期**（本任务）：CSP 响应头已由 P1-DEP 前置完成（`deploy/nginx/security-headers.conf` 含完整 CSP，`default-src 'self'` + wss/tts 同源反代白名单）——本任务验收改为**配置断言**（文件内 CSP 指令存在性 + 无 unsafe-inline 默认值）；两端 token 键名与过期语义统一（文档化三端策略，见 §8）
- **远期**（登记）：httpOnly cookie 迁移评估（含 CSRF 配套）——冻结跟踪或独立任务
- XSS 读取面短期缓解：CSP 已收紧 + 前端 sanitize 面自查（不做大改）

### 3.4 P2 清理组

- P2-10 设计 token：三端 token 值核对 design/08 §4.1 → **方案调整（文档先行）**：student-h5 多主题为产品特性、teacher-web antd 默认、parent-h5 自绘 CSS，强行统一 = 前端 UI 重构（违反 §2 非目标）→ design/08 补三端实态差异声明（风险语义色板为跨端强一致项），视觉全量统一登记远期
- P2-12：三端 console.info 清除（生产噪音归零，实测 22 处全在 student-h5）
- P2-13：BigScreen 数据加载失败 → 错误提示 + 重试按钮（不静默）

## 4. SPEC

```
teacher-web：authFetch 移植（401 刷新+重放，刷新失败才登出）；5 处导出 + importStudentsCsv 改走封装（实测 6 处裸 fetch 全改）
契约：两端 FRONTEND_ENDPOINTS 清单测试（核心端点先行，对齐 student-h5 模式）
CSP：已前置（P1-DEP security-headers.conf）→ 验收改为配置断言（指令存在性 + 无 unsafe-inline 默认）
token：三端策略文档化统一（键名/过期语义，见 §8 表）；httpOnly 迁移登记远期
设计 token：design/08 §4.1 为基准；三端实态差异声明落档（不强行统一，风险语义色板强一致）
清理：console.info 归零（22 处）；BigScreen 失败提示 + 重试
OVD-6：schemaValidator 保留评估（零依赖 144 行、双入口职责清晰、有测试覆盖）→ 结论落档 §8
```

## 5. 验收标准（EARS 风格）

- ✅ 当 teacher-web authFetch 落地后，token 过期时批量导出/导入必须自动刷新重放成功，刷新失败才登出——**authFetch 9 例测试 + api.ts 收敛（6 处改造）**
- ✅ 当契约防线落地后，两端核心端点必须在案（清单测试通过）——**teacher 38 例 + parent 9 例**
- ✅ 当 CSP 头生效后，响应必须包含 CSP 且不含不安全内联默认值——**P1-DEP 已前置，验收改为 security-headers.conf 配置断言**（本地无 nginx 运行时，无法 curl 实测）
- ✅ 当设计 token 对齐后，三端关键 token 值必须与基准一致——**方案调整为实态差异声明落档（design/08 §4.1），风险语义色板强一致**
- ✅ 当清理完成后，三端生产源码 console.info 必须为零——**grep 断言 0 匹配**
- ✅ 当 BigScreen 加载失败时，页面必须显示错误态与重试入口——**P2-13 红→绿 5 例**
- ✅ 当三端全量测试运行时，必须全绿——**student-h5 788 + teacher-web 83 + parent-h5 36 = 907 例**（另三端 tsc --noEmit 通过）

## 6. 风险与回滚

- **风险**：中——teacher-web 是教师核心工具，authFetch 替换影响全量 API；CSP 收紧可能误伤白名单外资源（上线前用浏览器逐页冒烟）
- **注意**：CSP 属 nginx 配置（部署层），修改后需重启验证；token 策略文档化为决策前置
- **回滚**：CSP 头 revert nginx 配置即可；authFetch 替换逐文件 revert

## 7. 关联与落点

- 关联任务：ARCH-005（doing/65，student-h5 接缝模式为样板）、ARCH-007（doing/67，家长端合规入口同域）
- 关联设计：design/08 系统功能概要设计（token 基准）、design/26 家长端、design/05 契约防线
- 词汇表：[13 领域词汇表](../13_领域词汇表.md)
- 登记：TASK-TRACKER §二十八 ARCH-008

---

## 8. 实施记录（2026-08-06）

### 8.1 交付物与测试基线

| 项 | 交付 | 测试 |
|----|------|------|
| F-6 | teacher-web `api.ts` 新增 `authFetch`（401 刷新+重放）；`api()` 收敛；6 处裸 fetch 全改造（exportSessionPdf / openWeeklyReport / exportAlertsCsv / exportStudentsCsv / downloadImportTemplate / importStudentsCsv），401 刷新失败才 clearToken+reload | authFetch.test.ts 9 例 |
| F-7 | 两端新增 `api/endpoints.ts`（FRONTEND_ENDPOINTS：teacher 35 条 / parent 5 条）+ `test/apiContract.test.ts`（清单质量 + 源码防漂移，`?raw` 读取源码） | teacher 38 + parent 9 例 |
| F-8 | CSP 已由 P1-DEP 前置（nginx/security-headers.conf）；token 三端策略文档化（见 8.3） | 配置断言（文档对照） |
| P2-10 | design/08 §4.1 补三端实态差异声明（文档先行，不强行统一） | — |
| P2-12 | console.info 22 处删除（6 文件全在 student-h5：useChatSession 1 / useWakeWord 5 / useVoiceCallMode 6 / useVoiceprint 1 / VoiceLoginOverlay 1 / wakeWordWorker 8），console.warn/error/debug 保留（故障信号） | grep 断言 0 |
| P2-13 | BigScreen 新增 error state + 错误条（role="alert"）+ 重试按钮（aria-label="重试"） | BigScreen.test.tsx +2 例 |

**全量回归**：student-h5 788 + teacher-web 83 + parent-h5 36 = **907 例全绿**；三端 `tsc --noEmit` 通过。

### 8.2 方案调整记录（5 条，doing 与代码一致）

1. **裸 fetch 实际 6 处**（非 SPEC 所述 5 处导出）：第 6 处为 importStudentsCsv 自带 Authorization 手写头——一并收敛入 authFetch。
2. **CSP 已前置完成**：P1-DEP 交付 nginx `security-headers.conf`（完整 CSP），本任务验收由「curl -I 实测」调整为「配置断言」（本地无 nginx 运行时）。
3. **P2-12 实际 22 处**（非 26+），且全部集中在 student-h5（teacher-web/parent-h5 无残留）——清理范围收敛。
4. **P2-10 改文档先行**：三端主色差异为合理产品差异化（儿童多主题特性 / antd 默认 / 自绘 CSS），强行统一违反 §2 非目标（前端 UI 重构）→ design/08 落实态差异声明，风险语义色板为跨端强一致项。
5. **parent-h5 无 F-6 缺陷**（auth.ts 已有 `_retried` 刷新重试）→ 不动。
6. **契约测试读源码不用 node:fs**：两端 tsconfig 均无 @types/node，改用 Vite 原生 `import apiSource from '...?raw'`（student-h5 先例），tsc 零新增依赖通过。

### 8.3 token 三端策略（P2-9 文档化）

| 端 | 键名 | 存储 | 过期语义 | 设备策略 |
|----|------|------|---------|---------|
| student-h5 | `mindsafe_student_token` / `mindsafe_student_refresh` | sessionStorage | access 2h + refresh 7d | 会话级（学校共享设备，关页即清） |
| teacher-web | `mindsafe_token` / `mindsafe_refresh` | localStorage | access 2h + refresh 7d | 持久（教师工作台） |
| parent-h5 | `parent_token` / `parent_refresh_token` | localStorage | access 2h + refresh 7d | 持久（家长端） |

后端统一：access token 2h + refresh token 7d（JWT，jti 撤销粒度，P1-13 已加固）。存储位置差异为共享设备 vs 个人设备的合理设计；**httpOnly cookie 全面迁移登记远期任务**（含 CSRF 配套，P2-9 冻结跟踪）。

### 8.4 OVD-6 评估结论：保留 schemaValidator

`schemaValidator.ts`（144 行，零依赖）双入口职责清晰：`validateSchema` 正向校验（required 强制）/ `validateMock` 反向兼容（mock ⊆ schema，不强制 required），支持 type（含数组）/required/properties/items/enum/$ref，被 schemaValidator.test.ts + apiContract.test.ts 消费。替换为 openapi-typescript + zod 需引入构建期类型生成 + 运行时校验双基建，收益不匹配改动成本（YAGNI）→ **保留，职责边界已明确**（详见设计/05 契约防线体系）。
