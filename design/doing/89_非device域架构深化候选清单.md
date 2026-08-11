# doing/89 非 device 域架构深化候选清单（N-001~N-013）

> 编号：DOC-110 | 创建：2026-08-11 | 来源：improve-codebase-architecture 第二轮（**已排除 device/toc 域**，doing/88 AD-001~009 已登记）
> 状态：doing（候选清单，待逐项议决实施）| 与 frozen/89 toC 数据链路依赖跟踪**同号异题**（文件名可区分）
> 核验（2026-08-11）：**N-001/N-003 部分闭环**（SPEC AC-89-01/02/06/07 已实施 805eacc+7de2b0a，AC-89-03 验证通过 f57c6af；AC-89-04/05/08 未做），其余 11 项待议决——暂不归档
> 关联：doing/88（无屏终端域候选，AD-001~009）、design/13（领域词汇表）、his/83（后台管理端）
> 架构词汇：模块/接口/深度/接缝/适配器/杠杆/局部性；删除测试结论逐项标注

---

## 一、背景与走查结论

第二轮走查排除已审计范围（device/toc 域 + SecurityConfig + admin-web 路由 + parent-h5 device/toc 页面），聚焦其余 13 个域：

- **安全级摩擦 3**：认证域碎片化（家长端零防护）/ 家长域 withdrawn 旁路 / 统计口径三处并行
- **结构性摩擦 6**：摘要域 10 依赖 / 会话 23 参构造 / 教师域 980 行 / OpsController 22 端点 / 画像域接缝不一致 / 记忆域评分重复
- **前端/部署摩擦 4**：useWakeWord 23 次变更热点 / 语音 DSP 重复 / 面板样板重复 / 部署审计单函数

**共性结论**：安全/租户/分页横向关注点已有正确接缝（runAsSystem/分页插件/会话归属），业务模块普遍缺类型化接口（Map 返回/字符串解析/硬编码阈值）——深化方向是显式化已隐性存在的接缝，而非加抽象。

## 二、候选清单

### N-001 认证域碎片化（AuthProvider + LoginRateLimiter 接缝）【Strong】

- **涉及**：AuthController（457 行 11 端点 token 内联 4 处）/ PlatformAuthController / TocAuthController（token 签发在 Controller，分层倒挂）/ ParentAuthService / LoginLockoutService / PlatformLoginGuard / TocAuthService.rateLimitCheck
- **问题**：4 套认证体系并存；锁定向 3 种实现（用户名/IP/手机号）威胁模型无法统一审查；**ParentAuthService.doLogin 无任何锁定向防护——家长账号是唯一可无限暴力尝试的入口**（安全红线）；TrialAuthService 第 5 套变体。
- **解决**：`AuthProvider` 接缝（authenticate→Principal，各体系实现）+ `LoginRateLimiter` 接口（三实现并排）+ 家长端接入同一限速接口。
- **删除测试**：删 TocAuthController 的 token 签发下沉 Service——集中；删 PlatformLoginGuard 合并 LoginLockoutService——转移（计数模型不同，收进统一接口）。

### N-002 唤醒词域 useWakeWord 深化（WakeWordEngine 工厂）【Strong】

- **涉及**：student-h5/src/hooks/useWakeWord.ts（555 行）/ transformersLoader.ts / useVoiceprint.ts
- **问题**：Worker 主路径 + 主线程降级 + 双模块级单例 + wakeStatus 四态 = 4 状态源互操作；workerConfig 构建重复 2 处；180s 握手 onmessage 内联；**23 次变更呈"修复→新故障→再修"循环**（F-27 注释自述）。
- **解决**：`WakeWordEngine`（工厂 {start,stop,onResult}，状态机收进引擎）+ hook 纯 React 绑定 + `WorkerReady` Promise 显式化 + `createModelStatusStore` 基座共享。
- **删除测试**：删主线程降级路径——集中（双路径归一，失容错属产品取舍）；删双单例改单例——集中。

### N-003 家长域双 token 解析（ParentIdentityResolver 统一 + withdrawn 旁路修复）【Strong】

- **涉及**：ParentController（333 行）——resolveParentToken（旧链接四重校验含 withdrawn）vs resolveParentIdentity（新登录仅签名校验）
- **问题**：同一 Controller 两条身份语义并存；**withdrawn 拦截只保护旧路径——新登录路径绕过同意撤回校验**（隐私合规漏洞）；周报聚合留在 Controller（Service 83 行空壳）；TenantContextHolder 模板重复 4 处。
- **解决**：`ParentIdentityResolver` 统一解析两种语义 + withdrawn 统一拦截；周报下沉 `WeeklyReportService`。
- **删除测试**：删旧路径——集中（身份语义单一化，withdrawn 拦截不再有旁路），需前端联动。

### N-004 统计口径三处并行（Metric VO 族收敛）【Strong】

- **涉及**：TeacherService.getStats / DataAnalyticsService.schoolReport / ParentController.doGetWeeklyReport
- **问题**：三处独立统计聚合；DataAnalyticsService 全 Map 返回（key 字符串魔法，改 key 静默 NPE）；negativeLabels 同文件重复 2 次；ZONE_CN vs CounselingTimeZone 两套时区。
- **解决**：`Metric VO` 族（SchoolReport/EmotionTrend/Satisfaction）作模块接口；共享常量 + 公共时区工具。
- **删除测试**：删 Map 返改变量 VO——集中。

### N-005 摘要域（SummaryDispatcher + TenantBoundary）【Worth exploring】

- **涉及**：MessageSummaryService（421 行 10 构造依赖）
- **问题**：dispatchInsights 向画像/记忆双节点分发（隐性编排者）；**BUG-TENANT-01 模式重复 3 处**（runAsSystem 手包，漏一处即租户串数据）；保密告知 turnCount=0 复用 trick。
- **解决**：`SummaryDispatcher` 接缝（消费方反向注册）+ `TenantBoundary` 统一封装。

### N-006 会话编排（ContextAssembler + MessagePipeline）【Worth exploring】

- **涉及**：ConversationServiceImpl（740 行，构造 23 参数）
- **问题**：sendMessageStream 250 行串 6 步；buildAlliancePrompt 字符串前缀解析记忆（格式变静默丢内容）；sendNudgeStream 重复组装 prompt；appendStatePath 两套表示。
- **解决**：`ContextAssembler` 门面（画像+记忆+联盟+CTX 组装）+ `MessagePipeline`（前置步骤可测模块）。

### N-007 教师域（AlertTodo + CaseLifecycle 模块化）【Worth exploring】

- **涉及**：TeacherService（980 行全仓最大，14 职责）
- **问题**：`new AlertTodoMutePolicy()`/`new CaseLifecycleService()` 绕过 Spring 内联（失替换接缝）；个案状态编码进 teacher_notes content；班主任裁剪逻辑散落 4 方法。
- **解决**：`AlertTodo` + `CaseLifecycle` 模块化（CaseLifecycleService 反哺 Spring 即出现注入接缝）；看板统计交 DataAnalyticsService。

### N-008 运维监控（requireConfirm 接缝 + 端点收敛）【Worth exploring】

- **涉及**：OpsController（269 行 22 端点 6 服务）
- **问题**：`if (!CONFIRM_PHRASE.equals(confirm))` 完全同构 5 处拷贝；22 端点横向铺开；近三天 7 次变更是修端点返回结构反复。
- **解决**：`requireConfirm()` 辅助（或 @RequireConfirm 注解接缝）；端点按风险矩阵/知识库/指标 3 聚合口收敛。

### N-009 画像域（提炼链路收口 + 分页接缝对齐）【Worth exploring】

- **涉及**：StudentProfileService（527 行）/ MessageSummaryService.dispatchInsights / ProfileExtractorService
- **问题**：头注释自称"纯 SQL 聚合"实际提炼在摘要链路（读"画像如何更新"跳 3 文件且注释误导）；L75 仍 .last("LIMIT 20") 原始拼接（AUD-043 分页插件未覆盖）；阈值硬编码。
- **解决**：提炼链路收进画像模块（ProfileExtractor 接口显式化）；L75 接入分页插件。

### N-010 记忆域（MemoryRetrieval 评分单次化 + 被遗忘权接口位）【Worth exploring】

- **涉及**：LongTermMemoryService（466 行 5 职责）
- **问题**：score() 重复计算 3 次（真性能问题）；SENSITIVE_EMOTIONS 与别处潜在重复；PIPL 被遗忘权未接线。
- **解决**：`MemoryRetrieval`（评分单次+缓存）+ `MemoryRetention`（含 deleteByStudent 接口位）。

### N-011 前端语音链路（audio-utils 共享 + 显式 tag 参数）【Worth exploring】

- **涉及**：useVoiceprint.ts（rms/downsample/tslog 与 useWakeWord 逐字重复）/ transformersLoader.ts L84 正则判断调用方
- **解决**：`audio-utils` 共享模块 + transformersLoader 显式 tag 参数（接口从"猜"变"声明"）。

### N-012 教师端面板（usePanelData 数据获取 hook）【Speculative】

- **涉及**：Dashboard.tsx（255 行 7 关注点）/ QualityPanel / StudentPanel（loading/error/retryKey 各自重复）
- **解决**：`usePanelData`（loading/error/retryKey+轮询）+ `useNotification` 收走非布局职责。

### N-013 部署审计（规则与渲染分层）【Speculative】

- **涉及**：deploy/scripts/deploy-audit.sh（dm_audit_report 145 行内嵌 R1-R6 + 排版）/ deploy-metrics.sh（bash 全局平行数组）
- **解决**：规则计算（纯函数可测）与报告渲染分层；部署流按预检/构建/发布/冒烟切 4 阶段。注：deploy-lib.sh 已是全仓最佳分层范例。

## 三、首要建议

**先做 N-001（认证域）→ N-003（家长域 withdrawn 旁路）**——合并为一个「认证与身份解析」深化批次：

- N-001 安全面最高（家长入口零防护 + 威胁模型无法统一审查）
- N-003 是真实隐私合规 bug（withdrawn 拦截只保护旧路径）
- 两者共用 AuthProvider 接缝；详见同号 SPEC：`doing/89_认证与身份解析深化SPEC.md`
- 连带：N-002（useWakeWord 热点）工程化最痛处建议紧随；N-005 TenantBoundary 低成本高杠杆

## 四、执行跟踪

| 候选 | 状态 | 备注 |
|------|------|------|
| N-001 | ✅ 已闭环（2026-08-11） | SPEC 8/8 AC 完成（805eacc/7de2b0a/6297372：LoginRateLimiter 三实现 + AuthProvider 四实现 + 家长端锁定） |
| N-002 | ⬜ 待议决 | 23 次变更热点 |
| N-003 | ✅ 已闭环（2026-08-11） | ParentIdentityResolver 双路径统一 + withdrawn 统一拦截（6297372）+ 周报下沉（7de2b0a） |
| N-004 | ⬜ 待议决 | 口径漂移 |
| N-005 | ⬜ 待议决 | 租户红线 |
| N-006 | ⬜ 待议决 | 功能稳定，重构风险大 |
| N-007 | ⬜ 待议决 | 新需求改动面大 |
| N-008 | ⬜ 待议决 | — |
| N-009 | ⬜ 待议决 | 注释误导 + 接缝不一致 |
| N-010 | ⬜ 待议决 | — |
| N-011 | ⬜ 待议决 | — |
| N-012 | ⬜ 待议决 | — |
| N-013 | ⬜ 待议决 | — |

> 议决流程：项目负责人选择候选 → /grilling 决策树 → /codebase-design 设计 → 实施 → 合并归档（his/89 同号异题）。
