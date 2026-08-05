# 68 教师端/家长端加固（ARCH-008）方案与 SPEC

> 关联任务：ARCH-008（深度审计 F-6/F-7/F-8 + P2-9/10/12/13 + OVD-6 回填，登记 TASK-TRACKER §二十八）
> 状态：📝 方案定稿 → 待实施
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
| P2-10 | 三端设计 token 值不一致（均与 design/19 表不同） | 三端样式文件 vs design/19 | 品牌一致性漂移 |
| P2-12 | console.info 残留 26+ 处 | 三端源码 | 生产噪音 |
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

- **短期**（本任务）：nginx `default.conf` 补 CSP 响应头（`default-src 'self'` 收紧，保留 wss/tts 域白名单）；两端 token 键名与过期语义统一（文档化三端策略）
- **远期**（登记）：httpOnly cookie 迁移评估（含 CSRF 配套）——冻结跟踪或独立任务
- XSS 读取面短期缓解：CSP 收紧 + 前端 sanitize 面自查（不做大改）

### 3.4 P2 清理组

- P2-10 设计 token：三端 token 值核对 design/19 → 以 design/19 为基准统一（design 先行改稿→再改三端，遵循「先文档后实践」）
- P2-12：三端 console.info 清除（生产噪音归零）
- P2-13：BigScreen 数据加载失败 → 错误提示 + 重试按钮（不静默）

## 4. SPEC

```
teacher-web：authFetch 移植（401 刷新+重放，刷新失败才登出）；5 处导出 + importStudentsCsv 改走封装
契约：两端 FRONTEND_ENDPOINTS 清单测试（核心端点先行，对齐 student-h5 模式）
CSP：nginx default.conf 补 Content-Security-Policy（default-src 'self' + 白名单）
token：三端策略文档化统一（键名/过期语义）；httpOnly 迁移登记远期
设计 token：design/19 为基准，三端对齐（先文档后实践）
清理：console.info 归零；BigScreen 失败提示 + 重试
OVD-6：schemaValidator 评估结论落设计文档（替换 or 保留+补测试）
```

## 5. 验收标准（EARS 风格）

- 当 teacher-web authFetch 落地后，token 过期时批量导出/导入必须自动刷新重放成功，刷新失败才登出
- 当契约防线落地后，两端核心端点必须在案（清单测试通过）
- 当 CSP 头生效后，curl -I 响应必须包含 Content-Security-Policy 且不含不安全内联默认值
- 当设计 token 对齐后，三端关键 token 值必须与 design/19 一致（抽查断言）
- 当清理完成后，三端生产源码 console.info 必须为零（grep 断言）
- 当 BigScreen 加载失败时，页面必须显示错误态与重试入口（不静默）
- 当三端全量测试运行时，必须全绿

## 6. 风险与回滚

- **风险**：中——teacher-web 是教师核心工具，authFetch 替换影响全量 API；CSP 收紧可能误伤白名单外资源（上线前用浏览器逐页冒烟）
- **注意**：CSP 属 nginx 配置（部署层），修改后需重启验证；token 策略文档化为决策前置
- **回滚**：CSP 头 revert nginx 配置即可；authFetch 替换逐文件 revert

## 7. 关联与落点

- 关联任务：ARCH-005（doing/65，student-h5 接缝模式为样板）、ARCH-007（doing/67，家长端合规入口同域）
- 关联设计：design/19 界面详细设计（token 基准）、design/26 家长端、design/05 契约防线
- 词汇表：[13 领域词汇表](../13_领域词汇表.md)
- 登记：TASK-TRACKER §二十八 ARCH-008
