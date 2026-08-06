# AI 小学生心理辅导系统 - 任务跟踪表

> 创建：2026-07-23 | 更新：2026-08-05（**TEST-006 完成登记（DOC-058）**：前后端契约测试三层防线落地——L1 ContractOpenApiIT 端点全量入文档、L2 gen-openapi-snapshot.sh 快照生成入库、L3 前端契约测试；修复 MoodCheckResult 契约漂移；**冻结专题登记：量表施测接线 frozen/59、商用发布合规与备案 frozen/60、外部服务接入与配置 frozen/61，DEC-006 并入 frozen/41**，相关任务行置 🔒 冻结；**2026-08-05 冻结范围扩展**：P2 商业化（BIZ-004/BIZ-006/BILL-002/BILL-003）归 frozen/38、部署升级（OPS-006/PERF-003/DEP-011~016）归 frozen/42、无状态化（PERF-005/STATE-001~005）归 frozen/40、信创（BIZ-005/DBAD-001~006/RISK-004）归 frozen/41、家长端小程序（AUTH-022/PARENT-WX-001~006）归 frozen/43、量表版权门禁（SCALE-003）归 frozen/34、效果量化 A/B（PROF-020/AB-003）归 frozen/39、design/32 商用发布前置归 frozen/60、M4 里程碑冻结跟踪（前置 frozen/60/61）；**远期规划冻结**：COMP-008（WebAuthn，**2026-08-06 起 AUTH-034 同事项一并冻结，远期再考虑**）、UX-003（多语言）、UX-004（无障碍）、PROF-022（初高中适配）纳入冻结规划；**AI-009（心理量表数字化）2026-08-06 纳入冻结专题管理**（施测接线 frozen/59、版权门禁 frozen/34，计分引擎已开发完成待解冻接线）；**作废登记**：DOC-051（QuickStart 指南）、DEC-004（3 版建设方案主版本确认，决策意义已消失）+ RISK-005 关闭；**状态确认**：AI-007/AI-008/PROF-021/UX-005/ORCH-008 核实完成（代码接线验证）、WAKE-012/33 标注完成、审计缺口统一 P2 级；历史：审计问题清单 A/C/D/E 修复闭环 DOC-055、独立 agent 深度审计 DOC-054、设计文档一致性全面核对 DOC-056（编号消歧，原 DOC-053）、设计文档全面更新 DOC-052、2026-07-28 设计文档整体规整 DOC-025、2026-08-01 §二十五 配置统一纳管、2026-07-29 独立审计校正）；**2026-07-28 DATA-005 方案冻结（frozen/62）**：研究数据脱敏导出（IRB 兼容）方案架构审查定稿并冻结归档——4 深化候选（导出管线/伪名化模块/加密接缝/保留豁免）+ 主 Seam（ExportRequest→ExportResult），DATA-005 两处登记置 🔒 冻结，解冻触发=启动学术合作/IRB 合规流程前；**2026-08-05 doing 子文档合并归档（DOC-059）**：doing/58（O 专题过度设计收敛）分主题并入主文档 03 §2.7/04 §6·§8/06 §3.3/10 §2.6·§2.12，doing/59（前后端契约测试 TEST-006）并入 05 §8.6，两文件归档 design/his/，DESIGN-OVERVIEW v5.2 新增 §2.3 已合并子文档对照、冻结区扩展至 12 份（+59/60/61/62）
> 
> 本表用于跟踪项目各阶段任务的进度和责任人。

---

## ⚠️ 审计校正（2026-07-29，钱敏健授权全面修复）

> 独立架构审计（三路并行 agent 交叉印证）结论：**测试全绿 ≠ 功能存在**。91 组件中 43 个为孤儿（生产入口不可达），真实交付面约为台账声称的一半。综合评分 2.8/10，No-Go。本表就此校正。

**状态图例（新增）：**
> - ✅ 已完成 = 已实现**且已接入生产入口**（Controller/Filter/@Scheduled/装配链可达）
> - 🟧 **已编码未接线**（态③）= 代码与单测存在，但从生产入口不可达，线上不生效——**不得计为完成**
> - ❌ 名不副实 = 声称完成但核心机制缺失/失效

**本轮修复批次（fix-01~12）：**
> fix-01 台账重标（本节）→ fix-02 删世界B → fix-03 加密接线(R-01) → fix-04 监护人同意门禁(R-03) → fix-05 SLA兜底(P-05) → fix-06 多租户拦截器(P-02) → fix-07 弱口令fail-fast(R-04) → fix-08 TLS(R-02) → fix-09 种子数据V27清理(R-05) → fix-10 CI修真(Q-01/Q-03) → fix-11 全量回归+文档同步 → fix-12 孤儿组件逐个裁决。
>
> **fix-13 剩余审计问题收官（2026-07-28，16 项全闭环）**：P0-2 限流恒 false 修复+单测 → P0-3/P1-8/P2-16 V32 迁移+密文预算截断+session_summary 加密+_enc 清理 → P0-5 SMS_PROVIDER 默认值统一+logging 醒目标记 → P1-10 监控告警体系（alert-rules 8 规则+Alertmanager 企微应用消息+tts/voice Python metrics 埋点）→ P1-11/12/P2-22 CI 前端覆盖率门禁+Trivy 前端+clean → P1-13/P3-28 JWT iss/aud/jti+token 撤销+DEV_SECRET 隔离 → P1-14 本地 DB 端口 5433→5432 对齐 → P1-15 logback 全局日志脱敏 → P2-18 jacoco 排除 entity 充数+门禁口径 → P2-20 ConversationServiceImpl 占位参数处置+staging 死配置删除 → P2-24/P3-31 tts requirements 上限+Python Dockerfile 加固 → P0-6 ONNX 模型获取脚本+冒烟校验 → P0-4/P1-9 cd.yml rsync 前端+CD 回滚机制 → P2-17/23/26/27/P3-30 文档同步（design/14 保留期 30→180 天、design/12 前端 TS 修正、design/16 审计日志/系统配置 🟩、design/33 保留策略对齐）→ P1-7/P3-29 冻结目录核对（frozen/ 8 份设计文档任务已全部登记于 §二十/§二十一/§二十三 + DESIGN-OVERVIEW v4.0，无需补）。全部经独立验证（后端 mvn 测试 + 前端 tsc/build/vitest + YAML/compose 校验）。

> 说明：§二十三 P0/P1/P2 backlog 中大量 ✅ 实为态③「已编码未接线」孤儿，**逐行裁决归口 fix-12**（与钱敏健逐项确认），本节仅先校正最高信号的失实条目，不在此重复逐行改标。

**fix-12 裁决结果（2026-07-28，钱敏健确认）：**
> 全量扫描（从 18 Controller + @Scheduled + Spring 自动装配出发追踪依赖链）实测 **39 个孤儿**（审计时 43 个含世界 B 已删组件）。
> - **删除 3 个**（YAGNI，零消费者基础设施）：`CacheService` + `CacheServiceTest` / `BusinessMetrics` / `PageResponse`
> - **~~保留·待接线 27 个~~** ✅ 全部接线完成（2026-07-28 P0+P1 批次）：ORCH/CBT/MEM/EMP/ALLY/WB/PEVAL/TOOL/TTSFX/AB/BILL/RISK-204/SAFE-204 全系列生产可达
> - **保留·远期 5 个** ✅ 已接线（2026-07-28）：SessionEndAnalyticsService 聚合调用（VoiceEmotionTrendAnalyzer/TrendAnomalySignaler/EmotionOrchestrationEvaluator/ProfileEffectivenessTracker）+ endSession 链
> - **保留·暂缓 3 个**：AssessmentScoringEngine / ScoringResult / BuiltinScales（量表施测暂缓，决策 #21）
> - **~~保留·待接线（数据层）2 个~~** ✅ 已接线（2026-07-28，AiChatServiceImpl 审计落库）
>
> 审计修复批次 fix-01~12 **全部收官**。P0+P1 接线批次 **全部收官**（2026-07-28）。剩余待实施项均为 🔭 远期或暂缓。

---

## 一、文档整合任务（当前阶段）

| 任务ID | 任务描述 | 状态 | 负责人 | 开始日期 | 完成日期 | 备注 |
|--------|----------|------|--------|----------|----------|------|
| DOC-001 | 提取 15 份 docx 内容为 txt | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | textutil 转换 |
| DOC-002 | 创建 design/ 与 doc/ 目录结构 | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 初版为 design/docs+design/his，后纠偏为 design/+doc/（见 DOC-024） |
| DOC-003 | 将原始 docx 归档至 doc/ | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | git mv 保留历史（中途从 design/his 迁至 doc） |
| DOC-004 | 创建 DESIGN-OVERVIEW.md 总览 | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 含目录、关键摘要 |
| DOC-005 | 创建 TASK-TRACKER.md 跟踪表 | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | |
| DOC-006 | 转换 01_产品架构图 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 338 行，关键内容已转换 |
| DOC-007 | 转换 02_Prompt体系设计 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 含安全 Advisor 链顺序 |
| DOC-008 | 转换 03_CBT对话流程树 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | |
| DOC-009 | 转换 04_风险识别规则库 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | risk_event 为实现基准 |
| DOC-010 | 转换 05_老师后台设计 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | S0-S3 映射红橙黄绿 |
| DOC-011 | 转换 06_数据库结构设计 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 622 行，DDL 核心 |
| DOC-012 | 转换 07_SaaS多学校隔离 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | Schema 级隔离 fail-fast |
| DOC-013 | 转换 08_MVP最小可行版本 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 431 行，完整转换 |
| DOC-014 | 转换 09_商业模式与采购 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 参考类 |
| DOC-015 | 转换 10_政策与合规风险 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 含合规硬约束映射 |
| DOC-016 | 转换 11_竞品深度分析 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 参考类 |
| DOC-017 | 转换 12_技术架构图 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 重大改写：微服务→模块化单体 |
| DOC-018 | 转换 13_Agent工作流 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | LangGraph→Spring AI 改写 |
| DOC-019 | 转换 14_儿童安全对话规范 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 危机热线固化铁律 |
| DOC-020 | 转换 15_心理知识库建设 为 md | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | RAG/Spring AI 适配 |
| DOC-021 | 更新 STRUCTURE.md 反映 docs/his 结构 | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | §2.2 拆分约定 |
| DOC-022 | 更新 BEACON.md/OVERVIEW 引用新结构 | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 修正 6 处断链接 |
| DOC-023 | Git commit 文档整合变更 | ✅ 完成 | Agent | 2026-07-23 | 2026-07-28 | 已在 0860320 提交，工作区干净 |
| DOC-024 | 目录结构纠偏：md 拍平至 design/、docx 迁至 doc/ | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 对齐钱敏健原意，STRUCTURE/BEACON/OVERVIEW 同步 |
| DOC-025 | **设计文档整体规整**：58 份旧文档按使用场景合并为 12 份（01 概述/02 数据库/03 架构/04 部署/05 测试/06 配置与外部服务/07 商业化合规/08 概要/09 学生端上卷/10 学生端下卷/11 老师端/12 家长端）；旧文档（50 份）归档 design/his/；frozen/（34/38-43/58）不合并待后续开发时整合；DESIGN-OVERVIEW v5.0 重写（含编号对照表）；BEACON/STRUCTURE/TASK-TRACKER 引用同步 | ✅ 完成 | Agent | 2026-07-28 | 2026-07-28 | 用户指令：合并分类/最终设计方法输出/旧文档归档/冻结文档不动；编号对照见 DESIGN-OVERVIEW §二 |
| DOC-026 | doc/ 根目录全量归档：15 份原始 docx + README.md 移入 doc/his/（git mv，doc/ 仅存 his/），废弃作历史材料；design/his/ 15 份 md 来源标注 doc/→doc/his/；BEACON/DESIGN-OVERVIEW/README/STRUCTURE 引用同步 | ✅ 完成 | Agent | 2026-07-29 | 2026-07-29 | 用户指令：doc 下的文档全部归档到 /his，废弃，作为历史材料；COMP-010 报告保留原路径引用（历史快照） |
| DOC-052 | **设计文档全面更新**：12 份合并文档同步 2026-08-05 三个提交——bd9d215 fix-13 收官（16 项：限流 P0-2/V32 P0-3·P1-8·P2-16/ONNX P0-6/监控 P1-10/CI 门禁 P1-11·12/日志脱敏 P1-15/JWT+撤销 P1-13·P3-28/端口 P1-14/rsync+回滚 P0-4·P1-9）、e173df7 CD 门禁、62bb542 P1 前端 4 项（CSP wasm/FE-2 大屏字段对齐/FE-3 导出当前会话/FE-4 WS 握手 subprotocol 鉴权）。逐份落点：04 部署（CD 回滚/rsync/压测/本地端口/运维）、05 测试（1466 用例/84.3%/门禁口径/SIT）、09 上卷（日志 PII 脱敏/JWT 四要素+撤销/限流防爆破/归口统计）、10 下卷（TTS v3-flash/方言修正/ONNX 脚本）、11 老师端（WS 握手鉴权/QualityPanel/BigScreen/FE 四项）、02 数据库（V32 修正：content_summary 扩 TEXT+僵尸 _enc 列删除）、08 概要（WS 协议/认证安全）、06 配置（ASR 默认 funasr/language_mode 废弃/M5）、01 概述（M5 先行落地注记）、07 合规（日志脱敏双保险）、12 家长端（tokenType=parent_report）；03 架构核对无滞后。数字验证：05 §8.1（1466/84.3%/713）、10 §8.2（43/1/1/8/3=56）、11 §14（12/1/13=26）全部一致 | ✅ 完成 | Agent | 2026-08-05 | 2026-08-05 | 用户指令：结合当前代码的实际实现，根据近期开发的提交情况，全面更新设计文档，将设计文档内容进行细化、补充完善 |
| DOC-053 | **设计文档 2026-08-02 同步**（补充完善）：针对 2026-07-31 至 2026-08-02 期间 60+ 提交中未纳入 DOC-052 的六大变更深挖补充。落点：① BEACON.md：头追加 2026-08-02 同步说明（10 大类变更）；决策表新增 #22-27（CTX-Agent、声纹双模式、TTS 7×8、唤醒深化、模型自托管、WASM/SIMD/SAB）；当前状态补 32 个 DB 迁移/CTX-Agent 上线；设计演进日志 2026-08-02 行；② design/09 §八·五 新增「主 Agent 上下文简报（CTX-Agent，commit c9121a8 落地）」完整章节 193 行（动机/架构/4段式/落地代码/验收/任务归口 CTX-001~007）；§8.14 任务表新增 CTX-001~005 行；③ design/06：版本头追加同步；§3.3 声纹双模式（commit e6f86ab+0320d84）补 wespeaker 模型+两模式详解+SQL 隔离；§5.1 ASR/SER 解耦补并行执行/超时分层/metrics/合规；§6 拆为 §6.1/6.2/6.3（6.2 ONNX 自托管 + WASM/SIMD/SAB 兼容性表）；④ design/10 §7.6 唤醒深化（commit 689d6dd+4152297+8144101）补状态机加固+continuous+防抖+预加载策略+WASM/SIMD/SAB；⑤ design/03 升至 v3.1：§1 总体架构加端侧 AI 层；§4.1 补 CTX-Agent 4段式注入；§4.4 语音能力拆 Python 边车 + 端侧 WASM 自托管；§11.1 加 MINDSAFE_VOICEPRINT_MODE；⑥ design/04 升至 v3.1：§7.3 加 WASM/SIMD/SAB nginx COOP/COEP 配置；新增 §13 voice-service 部署深化（内存选型/模型宿主机缓存/BuildKit pip 加速）。**核对无滞后模块**：design/01/02/05/07/08/11/12 已含 2026-08-02 之前实现，无需重复 | ✅ 完成 | Agent | 2026-08-02 | 2026-08-02 | 用户指令：以 2026-07-31 以来的提交，核对当前代码实现，深度分析后，以当前最新的实现补充完善设计文档 |
| DOC-054 | **独立 agent 深度审计 + 设计文档补充完善（2026-07-31~08-01 约 55 个提交，剔除 docs/merge）**：起独立审计 agent 按五维度核查（架构合理性/代码质量/工程化规范/团队协作友好度/代码逻辑虚化度），评分 **6.5/10**（代码 8/10、文档一致性 5/10，属「实现好于文档」），产出 S1-S11 文档建议 + 问题清单（A1-A4 架构/C1-C4 代码质量/E1-E5 工程规范/O1-O5 过度设计/D1-D4 僵死代码），全部经代码 grep + 文档 grep 交叉验证。12 个核心提交：2678de6（P0+P1 接线收官：PiiDesensitizer 百家姓+地址 SAFE-204/TenantContextHolder runAsSystem/MindSafeTenantLineHandler fail-fast/GuardianConsentFlowIT 6 用例/RISK-204 attention 信号）、6630be3（ASR 双引擎 funasr/dashscope）、9340c6b（prepare-funasr.sh 宿主机缓存+entrypoint fail-fast）、adf7143（TTS DashScope WebSocket 流式）、7df53ef（声纹按钮显式触发+voice_credential 90 天）、c9121a8（ConversationContextAgent 344 行纯字符串组装，四要素注入 System Prompt Layer 3）、9200b83（extractPersonalInfo → SessionState.personalInfo 会话级）、4edb002（3 处 @Transactional+JDK25 兼容）、e6f86ab（声纹双模式 local/remote V27）、3a58654（占位符昵称 9 词+EmotionDiary/RelaxationExercises 重构）、461faf4（前端 552 tests）。逐份落点：09 上卷（RISK-204/SAFE-204 全量 ⬜→🟩 共 7 处/CTX-Agent §3.12.1 新章节（四要素表+双入口接线+余量质疑登记）/脱敏描述更新/P1 例外注记/统计 31=23🟩+3🟫+3⬜+1❌+1 决策）、10 下卷（中期记忆滚动更新生命周期/7.6.2 占位符昵称过滤）、06 配置（yaml 缩进修正/§5.2.1 模型部署与降级：MODEL_CACHE_DIR+manifest 比对+fail-fast 不静默上云）、04 部署（运维 7 语音模型缓存/16 TTS 流式，1-16 编号修复）。**⚠️ P1 待决策**：CTX-Agent `extractPersonalInfo`（9200b83）用原文（非脱敏 safeContent）提取 realName/age/grade/class → 明文注入 System Prompt，违反「脱敏后才进 LLM」承诺——文档已如实登记例外注记（09 L1451），修复方向：代码改喂 safeContent or 文档承认例外；本次仅文档未动代码。已确认无滞后（agent 验证）：声纹双模式/90 天/按钮触发（01 §3.7、08 §3.1、06 §3.3）、ASR 双引擎（06 §5）、TTS 三级降级（06 §4.5、10 §6.8）、7 音色/唤醒词「你好波波」（10 §7.6.1）。审计问题清单（A1-A4/C1-C4/E1-E5/O1-O5/D1-D4）建议后续按优先级处理，其中 D 类僵死代码需清理 | ✅ 完成 | Agent | 2026-08-05 | 2026-08-05 | 用户指令：起独立的 agent 对本项目进行全面深度审计（以生产可用为目标、悲观评分、按五维度核查），并以 2026-07-31~08-01 提交核对当前代码实现后补充完善设计文档；修改未提交，待用户确认 commit |
| DOC-055 | **DOC-054 审计问题清单 A/C/D/E 修复闭环（全 TDD，全量回归 1474 用例全绿）**：**A 架构 4 项**——A1 TenantContextHolder 线程传播边界（异步传播收口，边界测试）、A2 risk 评分双写冗余消除（RiskEvent 结构化评分落库 V33 + 评分器单写）、A3 会话状态膨胀收敛（SessionState 拆薄 + SessionStateTest）、A4 前端状态管理分散收敛（student-h5 useWakeEnabled hook 抽取 + 测试）；**C 代码质量 4 项**——C1 N+1 残留批量收口（HybridRetrievalService/MemoryRelevanceScorer 批量查询 + 测试）、C2 魔法值常量化（8 实体状态常量 + EntityStatusConstantsTest + 全量替换）、C3 Controller 过厚瘦身（TeacherQualityService 抽取 + TeacherQualityControllerTest/ServiceTest 全绿）、C4 异常吞没（损坏声纹留痕 WARN + 契约测试）；**D 僵死代码 4 项逐项裁决**（D1-D3 已清除/接线，D4 裁决后处理，全量回归确认无引用残留）；**E 工程规范 5 项**——E1 gen-changelog.sh（15 测试 + 初始 CHANGELOG）、E2 db-rollback-drill.sh 迁移回滚演练（14 测试 + V28~V33 rollback SQL 补齐）、E3 verify-doc-numbers.sh 文档数字防漂移（6 测试 + 首次校准）、E4 e2e smoke 断言 28→31、E5 check-commit.sh + .gitmessage 提交粒度规范（10 测试）。回归汇总：后端 711 + student-h5 661 + teacher-web 34 + parent-h5 23 + scripts 45 = **1474 用例全绿**（A/C/D 全量 mvn+vitest，E 四套 scripts 测试） | ✅ 完成 | Agent | 2026-08-05 | 2026-08-05 | 用户指令：A、C、D、E 按这个顺序逐个修复，以 TDD 方式开发；修改未提交，待用户确认 commit |
| DOC-056 | **设计文档一致性全面核对（2026-08-02 以来 47 个提交，编号消歧：原 DOC-053 与 2026-08-02 同步任务同号，main 合并后改此号）**：交叉核对 6 个代码批次与 12 份合并文档——612ef65（P1 后端 7 项：markAsRead IDOR/transitionCase 400+持久化/exportAlerts 数据范围/保留期 30→180/aliyun fail-closed/评分器 8 因子/consent 撤回）、8983862（审计二轮：验证码防爆破/N+1 批量/@Transactional/加密落库/ci.yml/从库读数）、88a8a7b（深度审计：SOS/GuardianConsentGate/RRF/GROUP BY/prepare-models）、890c72f（F-1~F-3/G-1/G-2：BoBoPet 8 态/工具箱/转派静音/模板矩阵/编辑工作流）、d30bc00（SEC-001~007/线程池/nginx 安全头/镜像名）、08-02 声纹 TTS 部署（v3-flash/阈值 0.55/service-manager.sh/PWA 禁缓存/V29/CFG）。逐份落点：09 上卷（RISK-203 6 处 ⬜→🟫+落地记录/RISK-202 线程池③+fail-fast/G-1 门禁硬化/SEC-001 新增/统计 31=21+3+5+1+1）、10 下卷（BoBoPet 🟫→🟩 两处：663 落地记录+927 归口表）、11 老师端（13.2 表追加 P1 修复+性能可靠性两行）、08 概要（GuardianConsentGate 组件名/SMS fail-closed/验证码防爆破/SEC-004/SEC-007）、04 部署（运维 13 service-manager+14 PWA 禁缓存/镜像名统一）、06 配置（数据保留期 normal-session-days=180）。已核对无滞后：09 G-1/G-2/KB-103/RRF/SOS/红队、06 CFG-001~008/声纹阈值/TTS、02 保留期 180、12 撤回/SEC-005/006、04 prepare-models/nginx/actuator/CORS。数字验证：09 任务归口 31=21🟩+3🟫+5⬜+1❌+1 决策 一致 | ✅ 完成 | Agent | 2026-08-05 | 2026-08-05 | 用户指令：以 2026-08-02 以来提交的变更，全面核对代码实现和设计文档一致性，补充和完善设计文档；main 合并后编号消歧 DOC-053→DOC-056；修改未提交，待用户确认 commit |

---

## 二、待确认决策（需钱敏健拍板）

| 决策ID | 决策描述 | 选项 | 推荐 | 状态 | 截止日期 |
|--------|----------|------|------|------|----------|
| DEC-001 | MVP 范围最终确认 | 以 design/08 为准 / 调整 | 以 design/08 为准 | ✅ 已确认 | 2026-07-23 |
| DEC-002 | Java 构建工具 | Maven / Gradle | Maven（信创/银行通用） | ✅ 已确认 | 2026-07-23 |
| DEC-003 | Java ORM 框架 | MyBatis-Plus / Spring Data JPA | MyBatis-Plus（政企/信创主流） | ✅ 已确认 | 2026-07-23 |
| DEC-004 | 3 版建设方案主版本 | 时间戳后缀版 / 整合版 | 需人工比对 md5 | ⛔ 作废（2026-08-05 钱敏健指示：三版差异已由 design/*.md 吸收消化，doc/his/ 只读归档，主版本确认无业务意义，不再决策） | 随时 |
| DEC-005 | 首个 LLM Provider | DeepSeek / 通义 / GLM | DeepSeek（性价比高） | ✅ 已确认（deepseek-v4-flash/pro） | 2026-07-23 |
| DEC-006 | 信创数据库选型 | 达梦 / 人大金仓 / 其他 | MVP 用 PG，M3+ 评估 | 🔒 冻结（随 frozen/41 信创专题跟踪，M3+/政企信创触发时解冻议决） | M3 前 |

---

## 三、MVP 开发任务（M1，已完成）

> 注：M1 全部任务已在 Phase 1-10 中完成，含 Maven 多模块、多租户、AI 对话、风险识别、双前端、Docker 部署。

| 任务ID | 任务描述 | 模块 | 状态 |
|--------|----------|------|------|
| M1-001 | Maven 多模块骨架搭建（7 模块） | backend/ | ✅ 完成 |
| M1-002 | PostgreSQL 初始化 + Flyway 迁移 | backend/ | ✅ 完成 |
| M1-003 | Schema 级多租户路由实现 | counseling-tenant/ | 🟧 部分实现（名不副实已校正）——fix-06 落地**行级**隔离纵深防线：`TenantLineInnerInterceptor` + `TenantContextHolder` + `ParentAuthService` 去硬编码；2026-07-28 M1-003 fail-fast 已收紧：无上下文且非系统作用域的业务表 DAO 调用抛 `IllegalStateException`，合法跨租户链路（8 处）经 `runAsSystem`/`callAsSystem` 显式声明 + `TaskDecorator` 异步传播，单测+IT 全绿；**仍为共享 tenant_template schema 行级隔离，非 Schema 级物理隔离**（路线 A 已定稿，B 挂起），见审计 P-02/P-06 + design/07 §11 |
| M1-004 | 用户与权限模型（JWT + RBAC） | counseling-domain/ | ✅ 完成 |
| M1-005 | Spring AI LLM Provider 接入（DeepSeek） | counseling-ai/ | ✅ 完成 |
| M1-006 | Safety Agent 实现（双层输出审查 + PII） | counseling-ai/ | ✅ 完成 |
| M1-007 | Emotion Agent + CBT Agent 实现 | counseling-ai/ | ✅ 完成 |
| M1-008 | 风险关键词识别规则库（10 类信号） | counseling-ai/ | ✅ 完成 |
| M1-009 | 对话 API（学生端 SSE 流式） | counseling-api/ | ✅ 完成 |
| M1-010 | 预警通知服务（WebSocket 实时推送） | counseling-service/ | ✅ 完成 |
| M1-011 | 学生端 H5（React 19 + Tailwind + PWA） | frontend/student-h5/ | ✅ 完成 |
| M1-012 | 教师端 Web（React 19 + Ant Design 6） | frontend/teacher-web/ | ✅ 完成 |
| M1-013 | Docker Compose 部署配置 | deploy/ | ✅ 完成 |
| M1-014 | 单元测试（123 个，JUnit 5） | 各模块 src/test/ | ✅ 完成 |

---

## 四、M2 任务（已完成，在 Phase 11-15 中实现）

| 任务ID | 任务描述 | 状态 |
|--------|----------|------|
| M2-001 | 放松呼吸练习（学生端 CSS 动画） | ✅ 完成 |
| M2-002 | 高风险学生列表（教师端） | ✅ 完成 |
| M2-003 | 学生档案查看 + AI 摘要（教师端） | ✅ 完成 |
| M2-004 | 使用量/预警量统计（教师端图表） | ✅ 完成 |
| M2-005 | 满意度评价功能（学生端 + 教师端统计） | ✅ 完成 |
| M2-006 | 会话转人工（红色风险 → 教师接管） | ✅ 完成 |
| M2-007 | 家长端 H5 报告（JWT 链接访问） | ✅ 完成 |
| M2-008 | 周报导出（可打印 HTML） | ✅ 完成 |

---

## 五、商业化版本开发（Phase 1-20，已完成）

| Phase | 核心功能 | 状态 |
|-------|----------|------|
| 1-3 | 后端骨架 + 多租户 + AI 对话 + 风险识别 + 学生端 H5 | ✅ |
| 4-6 | 教师端工作台 + 预警队列 + WebSocket 实时通知 | ✅ |
| 7-8 | 语音输入/TTS + 会话历史 + 设置面板 | ✅ |
| 9-10 | 告知同意 + 试用注册 + 密码管理 | ✅ |
| 11-12 | PWA 离线 + CSV 导出 + Prometheus 监控 + 批量导入 | ✅ |
| 13-14 | 情绪趋势分析 + 班级统计 + 学生备注 | ✅ |
| 15 | 会话转人工 + 家长 H5 + 周报导出 | ✅ |
| 16 | 满意度分析 + 呼吸练习增强 + 移动端适配 | ✅ |
| 17 | 平台管理后台 + 质量监控 + 情绪日记 | ✅ |
| 18 | 企微 OAuth + 数据大屏 + 会话导出 PDF | ✅ |
| 19 | Docker Compose + 新手引导 + 话术模板 + 成就系统 | ✅ |
| 20 | .env.example + 暗色模式 + 学生端引导动画 | ✅ |

---

## 六、风险与问题跟踪

| 问题ID | 问题描述 | 影响 | 状态 | 责任人 | 解决方案 |
|--------|----------|------|------|--------|----------|
| RISK-001 | 15 份 docx 内容量大，md 转换耗时 | 文档整合进度 | ✅ 已解决 | Agent | 分批转换完毕，15/15 均已落 md |
| RISK-002 | 12 号技术架构图过度设计（微服务） | 技术选型理解 | ✅ 已解决 | - | 已裁决：模块化单体，12 号§10 保留对照 |
| RISK-003 | 13 号 Agent 工作流基于 LangGraph | Java 技术栈适配 | ✅ 已解决 | Agent | 已改写为 Spring AI（决策 #9） |
| RISK-004 | pgvector 在信创环境不可用 | 长远私有化部署 | 🔒 冻结（frozen/41 信创专题，M3+/政企信创触发时解冻评估） | 钱敏健 | 国产向量方案评估随 frozen/41 跟踪（原 🟡 中，2026-08-05） |
| RISK-005 | 3 版建设方案内容有差异 | 需求理解一致性 | ✅ 关闭（2026-08-05：三版内容已全部演进吸收至 design/*.md，主版本不再确认，DEC-004 作废） | 钱敏健 | 原：需人工比对 md5 后确定主版本；随 DEC-004 作废一并关闭 |
| RISK-006 | 首次目录层级理解偏差（多套一层 docs/his） | 文档导航/引用一致性 | ✅ 已解决 | Agent | 2026-07-23 纠偏为 design/+doc/，同步 5 份状态文件 |

---

## 七、里程碑计划

| 里程碑 | 目标 | 计划日期 | 实际日期 | 状态 |
|--------|------|----------|----------|------|
| **M0：文档整合完成** | 15 份 docx 转为 md，总览/跟踪表就绪 | 2026-07-23 | 2026-07-23 | ✅ 完成 |
| **M0.5：决策确认 + 开发规范** | MVP 范围、Java 子选型确认；开发规范制定 | 2026-07-23 | 2026-07-23 | ✅ 完成 |
| **M1：核心对话+风险识别** | 最小闭环验证 | 2026-09-23 | 2026-07-23 | ✅ 完成（Phase 1-10） |
| **M2：功能体验完善** | 放松训练+教师后台+家长端 | 2026-11-23 | 2026-07-23 | ✅ 完成（Phase 11-15） |
| **M3：商业化版本** | 企微/大屏/导出/成就/部署 | 2027-01-23 | 2026-07-23 | ✅ 完成（Phase 16-20） |
| **M4：部署上线** | 云资源采购 + 生产部署 + 真实用户试用 | 待定 | - | 🔒 冻结跟踪（2026-08-05，依赖 frozen/60/61 解冻后启动） |

---

## 八、内容安全审查体系（M1 已实现，2026-07-23 提交 commit 9ec5278）

| 任务ID | 任务描述 | 模块 | 状态 | 备注 |
|--------|----------|------|------|------|
| SAF-001 | 输入侧风险识别（10 类信号关键词硬规则） | counseling-ai/risk/ | ✅ 完成 | `RiskDetectorServiceImpl` |
| SAF-002 | 输入侧 PII 服务端脱敏（手机/身份证/邮箱） | counseling-ai/safety/ | ✅ 完成 | `PiiDesensitizer` |
| SAF-003 | 输出 Layer1 流式关键词硬过滤（滑动窗口） | counseling-ai/safety/ | ✅ 完成 | `OutputContentFilter` + `SafetyKeywordLibrary` |
| SAF-004 | 输出 Layer2 异步 SAF-002 语义审查 | counseling-ai/safety/ | ✅ 完成 | `OutputReviewService`（fire-and-forget） |
| SAF-005 | 违规上报（依赖倒置，写 risk_events + 通知） | counseling-service/safety/ | ✅ 完成 | `OutputSafetyReporter` 端口 + `OutputSafetyReporterImpl` 适配器 |
| SAF-006 | 单元测试（5 个测试类） | 各模块 src/test/ | ✅ 完成 | 见 design/04 §17.6 |

---

## 九、设计文档维护与试用准入（2026-07-23）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| DOC-025 | 新增 design/21 认证与试用准入设计（兼容认证+告知同意） | ✅ 完成 | 4 个决策点（D1-D4）待钱敏健拍板 |
| DOC-026 | 同步内容安全审查到 design/04 §十七（已实现，真实类名） | ✅ 完成 | 输入风险/PII/输出双层/上报/测试 |
| DOC-027 | 对齐 design/14 §11 实际类名（目标设计 vs M1 实现） | ✅ 完成 | 补输出审查/PII 行 |
| DOC-028 | 对齐 design/18 §3 SAF-002 + §13 Advisor 链实现状态 | ✅ 完成 | 实际占位符 {candidate_reply}/{context} |
| DOC-029 | 立"设计文档与代码一致"底线规则 | ✅ 完成 | core-red-lines §4.5 / AGENTS §3.5 / design-persistence §4.2 |
| DOC-030 | 更新 BEACON / DESIGN-OVERVIEW / TASK-TRACKER | ✅ 完成 | 决策 #10-12、导航 21、版本 v1.5 |
| AUTH-001 | 实施试用准入 P0（告知同意门控+试用注册+ChatController 接 SecurityContext） | ✅ 完成 | Phase 9-10 实现 |
| AUTH-002 | chat 端点收紧鉴权 + 前端接入 JWT | ✅ 完成 | Phase 9-10 实现 |
| AUTH-003 | consent_records / trial_invite_codes 表迁移脚本 | ✅ 完成 | V6__trial_access.sql |

---

## 十、学生心理画像（design/23 + design/29）

### 基础画像（已完成）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| PROF-001 | P0：student_profiles 表 + 情绪基线 + 风险轨迹（SQL 聚合） | ✅ 完成 | V12 迁移 + StudentProfileService |
| PROF-002 | P0：对话时注入画像到 System Prompt | ✅ 完成 | AiChatServiceImpl 3.6 段 |
| PROF-003 | P1：沟通偏好 + 技巧有效性（LLM 提炼） | ✅ 完成 | ProfileExtractorService 异步提炼 + Prompt 注入增强 |
| PROF-004 | P2：成长轨迹 + 里程碑 + 教师端雷达图 | ✅ 完成 | ProfileRadarService + ECharts 雷达图 + 里程碑检测 |

### 年龄适配与画像增强（design/29，P0/P1 已实施）

> 核心问题：grade_level 硬编码 "5-6"、语言模板从未调用、User.gradeCode 未传入 AI 层、画像无年龄/性格维度。
> 核对结论（2026-07-27）：P0+P1（PROF-010~015 + PROF-019）已实现，与 [BEACON.md](BEACON.md) L63 一致；P2/P3 保持待开始/远期。

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| PROF-010 | P0：接通年级——SessionState 新增 grade + AiChatService 接口新增 grade 参数 + gradeCode 解析 | ✅ 完成 | `User.gradeCode` 字段 + `AiChatService.chat` 接 grade 参数 + `ConversationServiceImpl.parseGradeCode`，design/29 §3.1-3.3 |
| PROF-011 | P0：调用 languageTemplateForGrade(grade) 加载语言模板追加到 System Prompt | ✅ 完成 | `PromptTemplateService.languageTemplateForGrade` + `AiChatServiceImpl` L70/L128 调用，design/29 §3.4 |
| PROF-012 | P0：buildProfilePrompt 追加「基础属性」段（年级/年龄/性别/表达深度） | ✅ 完成 | `StudentProfileService.buildProfilePrompt` 含 `## 基础属性` 段，design/29 §3.5 |
| PROF-013 | P1：语言模板升级（三维度：认知水平+比喻库+互动模式+CBT 深度） | ✅ 完成 | LANG_001/002/003 已重写为五段（认知水平/语言约束/比喻库/互动模式/禁止），design/29 §3.9 |
| PROF-014 | P1：性别 × 年龄交叉策略（buildGenderStyle 按年级段分化） | ✅ 完成 | `AiChatServiceImpl.buildGenderStyle` 6 分支矩阵（男/女 × low/mid/high），design/29 §3.10 |
| PROF-015 | P1：动态降级机制（expressionDepth < 0.3 → 降低语言复杂度） | ✅ 完成 | `ConversationServiceImpl.computeEffectiveGrade`，风险场景不降级，design/29 §3.11 |
| PROF-016 | P2：V18 迁移 student_profiles 新增 personality_traits JSONB 列 | ✅ 完成 | V18 迁移 + StudentProfile 实体扩展，design/29 §4.2 |
| PROF-017 | P2：LLM 提炼扩展（PROFILE_EXTRACTOR 新增性格维度：内向/敏感/好奇/兴趣） | ✅ 完成 | ProfileExtractorService.mergePersonalityTraits + EMA 合并，design/29 §3.6 |
| PROF-018 | P2：性格 → Prompt 策略映射 + dominant_interests 暖场取材 | ✅ 完成 | StudentProfileService.appendPersonalityStrategy，design/29 §3.8 |
| PROF-019 | P0-P1 集成测试（1 年级 vs 6 年级 System Prompt 差异 + 降级 + 风险不降级回归） | ✅ 完成 | `ConversationServiceImplTest.GradeComputation` 6 用例（含风险不降级回归），design/29 §七 |
| PROF-020 | P3：画像效果量化（A/B 适配 vs 不适配的满意度/会话深度对比） | 🔒 冻结（frozen/39 效果量化与 A/B 实验专题，2026-08-05） | design/39（工程化设计），design/29 §八 |
| PROF-022 | 初高中学段适配缺口评估（话术/量表/UI 全维度，K12 口径定稿配套挂账，09 §10.1/11 §9.3；PROF-021 已被 design/44 占用故跳号） | 🔒 冻结（2026-08-05 纳入冻结规划；初高中版本启动时解冻） | 2026-07-28 钱敏健定稿维持 K12 表述的风险缓释项 |

---

## 十一、身份认证优化（design/24）

### 阶段一：M1 试用加固（近期开发）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| AUTH-010 | 邀请码一人一码（教师批量生成 + 用后作废） | ✅ 完成 | P0，V13 迁移 + AdminController 批量生成 |
| AUTH-011 | 学生 PIN 码（4-6位数字 + BCrypt） | ✅ 完成 | P0，set-pin/pin-login 端点 |
| AUTH-012 | 登录失败锁定（Redis 计数器 5次/15min） | ✅ 完成 | P0，LoginLockoutService |
| AUTH-013 | 家长 Token 绑定手机 + 短信验证 | ✅ 完成 | P1，SmsService + PhoneVerificationService + ParentController 端点 |
| AUTH-014 | 密码策略（8位+复杂度 + 90天过期） | ✅ 完成 | P1，PasswordPolicyService + V14 迁移 |

### 阶段二：M2 学校正式部署（后续待办）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| AUTH-020 | 学校 Excel 批量导入学生名单 | ✅ 完成 | CSV 导入（AdminController + AdminPanel UI） |
| AUTH-021 | 企微/钉钉 OAuth 配置上线 | 🔒 冻结（同 BIZ-002，见 frozen/61） | 代码已就绪，需企业主体 + 配置 corpId/secret |
| AUTH-022 | 家长微信小程序 + 微信 OAuth 登录 | 🔒 冻结（frozen/43 家长端小程序化专题，企业主体认证门禁，2026-08-05） | design/43（Taro 迁移），见 PARENT-WX 系列任务 |
| AUTH-023 | 监护人同意闭环（短信确认链接）+ 对话入口门禁 | ✅ 完成（门禁 fix-04） | GuardianConsentService（发起/确认/hasGuardianConsent）+ AuthController 端点；ChatController.createSession/sendMessage/sendNudge 前置 `hasGuardianConsent` 校验，未同意抛 CONSENT_REQUIRED(20003)，endSession 不门禁。审计 R-03：此前仅有闭环无运行时门禁，学生可绕过同意直接对话，现已接线，ChatControllerTest 7 用例守卫 |

### 阶段三：合规加固（后续待办）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| AUTH-030 | 使用时长限制（每日≤30min） | ✅ 完成 | 未保法，UsageTimeLimitService + Redis 每日累计 |
| AUTH-031 | 数据最小化审计 + 定期清理 | ✅ 完成 | DataRetentionCleanupJob + @EnableScheduling，普通30天/高风险365天 |
| AUTH-032 | 家长撤回同意 → 冻结+删除 | ✅ 完成 | PIPL §47，ConsentWithdrawalService + ParentController 端点 |
| AUTH-033 | 年度合规审计报送 | 🔒 冻结（同 COMP-007，见 frozen/60） | 未保条例 §37，流程性报送（非代码），上线后 1 年内 |
| AUTH-034 | WebAuthn 设备端指纹/Face ID（可选） | 🔒 冻结（同 COMP-008：2026-08-05 纳入冻结规划，真机测试条件触发，**远期再考虑**） | 不采集生物数据，需真机测试 |
| AUTH-040 | 监护人同意 SMS 闭环（替代试运行自动写入） | ✅ 代码侧完成（2026-07-31） | PIPL §31 完整闭环已落地：配置开关 `mindsafe.consent.trial-auto-grant`（默认 true=试运行自动写入；prod compose 默认 false）→ 注册响应 `guardianConsentPending` → 前端收集监护人手机号 + 验证码确认页（student-h5）→ `/guardian-consent/request+confirm` 写入 guardian_consent；age≥14 本人同意注册即写入（修复 14+ 被门禁卡死 bug）。测试：单测 7 用例 + GuardianConsentFlowIT 6 用例全绿。剩余前置：DEPLOY-010 阿里云 SMS 签名/模板（生产开启 `SMS_PROVIDER=aliyun`） |

---

## 十二、家长端 H5（design/26）

### P1：H5 移动网页（本期）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| PARENT-001 | Vite+React 工程初始化 + 基础架构 | ✅ 完成 | frontend/parent-h5 |
| PARENT-002 | 手机验证页（send-code → verify-phone） | ✅ 完成 | |
| PARENT-003 | 情绪周报页（/parent/report） | ✅ 完成 | |
| PARENT-004 | 同意管理页（撤回同意 + 二次确认） | ✅ 完成 | |
| PARENT-005 | 构建验证 + nginx 部署配置 | ✅ 完成 | vite build 成功，base=/parent/ |

### P2：微信小程序（远期规划，不丢失）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| PARENT-WX-001 | 微信小程序工程注册 + AppID 配置 | 🔒 冻结（frozen/43） | design/43 W-1，需企业主体认证 |
| PARENT-WX-002 | wx.login → openid → parent_bindings 绑定 | 🔒 冻结（frozen/43） | design/43 §3.3/W-4，后端补绑定端点 |
| PARENT-WX-003 | 微信 OAuth 授权页（获取手机号） | 🔒 冻结（frozen/43） | design/43 §3.3，getPhoneNumber 需企业认证 |
| PARENT-WX-004 | taro build --type weapp + 真机调试 | 🔒 冻结（frozen/43） | design/43 W-5 |
| PARENT-WX-005 | 小程序提审 + 上线 | 🔒 冻结（frozen/43） | design/43 W-7，隐私协议/类目审核 |
| PARENT-WX-006 | 订阅消息推送（周报通知） | 🔒 冻结（frozen/43） | design/43 §3.4，微信订阅消息 API |

---

## 十三、商用部署就绪（M4 前置）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| DEPLOY-001 | nginx /parent 路由修复（alias + SPA fallback） | ✅ 完成 | deploy/nginx/default.conf |
| DEPLOY-002 | docker-compose.prod.yml 挂载 parent-h5 + 路径对齐 | ✅ 完成 | /app/student, /app/teacher, /app/parent |
| DEPLOY-003 | deploy.sh 加入 parent-h5 构建+上传 | ✅ 完成 | |
| DEPLOY-004 | 环境变量修复（Redis密码/JWT/SMS/CORS） | ✅ 完成 | docker-compose.prod.yml + .env.example |
| DEPLOY-005 | SMS 可配置化（logging/aliyun 切换） | ✅ 完成 | AliyunSmsService + @ConditionalOnProperty |
| DEPLOY-006 | CORS + 安全头（X-Frame-Options/XSS） | ✅ 完成 | SecurityConfig 加固 |
| DEPLOY-007 | 学校初始化工具（init-school.sh） | ✅ 完成 | 租户+学校+管理员+邀请码 |
| DEPLOY-008 | pgcrypto 扩展（V15 迁移） | ✅ 完成 | crypt/gen_salt 依赖 |
| DEPLOY-009 | 生产部署执行 | ✅ | 三端上线 + nginx /parent 路由 |
| DEPLOY-010 | 阿里云 SMS 签名/模板申请 | 🔒 冻结（同 BIZ-003，见 frozen/61） | 需企业主体 + 审核（AUTH-040 生产态前置） |

---

## 十四、家庭码认证关联体系（FAM）

> 设计思路：教师是信任锚点。学生注册获得家庭码，家长用家庭码+手机号+密码绑定孩子。
> MVP 阶段无需短信，正式版加短信验证只需一步。

| 编号 | 任务 | 状态 | 备注 |
|------|------|------|------|
| FAM-001 | V16 迁移：users.family_code + parent_accounts + parent_student_links | ✅ | |
| FAM-002 | 学生注册时生成家庭码 + /me 返回 familyCode | ✅ | |
| FAM-003 | 家长注册 API（家庭码+手机号+密码+关系） | ✅ | |
| FAM-004 | 家长登录 API（手机号+密码） | ✅ | |
| FAM-005 | 家长端 H5 改造（家庭码注册页 + 密码登录） | ✅ | |
| FAM-006 | 学生端注册成功页显示家庭码 | ✅ | |
| FAM-007 | 家长查看周报改用正式 JWT（复用现有 /parent/report） | ✅ | |

---

## 十五、波波小精灵品牌与宠物交互（design/27）

> 设计思路：品牌固化（波波小精灵）+ 伙伴宠物化（四态动画）+ 语音输入圆球化（宠物与圆球合一），纯前端改动，后端零改动。

| 编号 | 任务 | 状态 | 备注 |
|------|------|------|------|
| BOBO-001 | 品牌固化：PWA 名/title 改「波波小精灵」+ 海豚 favicon/PWA 图标 + 三主题角色固定波波 | ✅ 完成 | index.html / vite.config.js / public/* / ThemeProvider.jsx |
| BOBO-002 | 波波 SVG 角色组件（BoBoPet.jsx，逐部件可动画 + 四态动画基础） | ✅ 完成 | 纯 SVG，随主题变色 |
| BOBO-003 | 圆球变形 + 触感反馈（按住蜷成发光圆球 + vibrate + iOS 视觉补偿） | ✅ 完成 | navigator.vibrate 降级策略 |
| BOBO-004 | 话语气泡组件（SpeechBubble.jsx）+ useTtsPlayer 暴露 currentSentenceText | ✅ 完成 | 逐句滚动与 TTS 同步 |
| BOBO-005 | 接入 ChatRoom（手机悬浮输入栏右上角 + Pad 左栏合并，删除旧麦克风按钮） | ✅ 完成 | 状态映射：recording/streaming/tts.playing；手机气泡右对齐防溢出 |
| BOBO-006 | WelcomeGuide 增加「按住波波说话」引导 + AI 人设改波波（design/18 + 后端 prompt）+ 构建验证 | ✅ 完成 | vite build 通过；后端问候语同步改波波（待部署生效） |

---

## 十六、语音唤醒与冷场引导（design/28）

> 设计思路：三个叠加式能力——唤醒词"哈喽波波"（Transformers.js + Whisper 本地引擎，监听严格限定在对话会话内）+ 冷场决策模型（多信号加权+画像关联，先判断该留白还是该暖场）+ 音色人设（中性角色+男女/温柔音色）；后端 nudge SSE 接口 + 前端沉默检测，不改动现有按住说话主路径。

| 编号 | 任务 | 状态 | 备注 |
|------|------|------|------|
| WAKE-001 | 设计文档 design/28 + design/18 登记 TSK-004 + OVERVIEW/TRACKER/BEACON 同步 | ✅ 完成 | 阶段 0 设计先行 |
| WAKE-002 | 问候语加昵称"哈喽，[昵称]！"（buildGreeting + user.pseudonym） | ✅ 完成 | 阶段 1 后端，唤醒词 onboarding |
| WAKE-003 | 冷场决策模型（信号 A-F 加权评分卡 + 硬规则覆盖）+ SessionState 字段（nudgeCount/lastNudgeAt/expressionDepth/最后消息类型）+ createSession 画像加载 | ✅ 完成 | 阶段 1 后端，信号 F=画像沟通偏好 |
| WAKE-004 | TSK-004 prompt 文件 proactive_nudge_zh-CN_v1.0.0.md + PromptTemplateService.TSK_004 常量 | ✅ 完成 | 阶段 1 后端 |
| WAKE-005 | AiChatService.chatProactive（不写伪造学生消息、nudge 指令追加 system 层、复用双层安全管线） | ✅ 完成 | 阶段 1 后端，不污染记忆 |
| WAKE-006 | ConversationService.sendNudgeStream + ChatController POST /nudge SSE 端点 + 护栏（2 次上限/间隔/escalated 拒绝） | ✅ 完成 | 阶段 1 后端 |
| WAKE-007 | tts-service 补 xiaotaiyang 人设（zh-CN-YunxiNeural）+ CosyVoice2 persona→speaker 映射（去硬编码"中文女"） | ✅ 完成 | 阶段 1 后端，功能三缺陷修复 |
| WAKE-008 | 后端单元/集成测试（问候含昵称、决策模型用例含画像信号 F、护栏、记忆不污染、xiaotaiyang 男声） | ✅ 完成 | 阶段 1 后端，161 个测试全绿 |
| WAKE-009 | ChatRoom 沉默检测计时器 + nudge 调用 + TTS 朗读 + 护栏（2 次上限/说话重置/与录音互斥） | ✅ 完成 | 阶段 2 前端 |
| WAKE-010 | useWakeWord（Whisper）+ useVoiceCallMode 状态机 + VoiceCallConsentDialog 单独授权 + BoBoPet waitingWake 态 + ChatRoom 集成 | ✅ 完成 | 阶段 3，**已从 Porcupine 切换为 Transformers.js + Whisper**（零外部账号），构建通过 |
| WAKE-011 | ~~.env 增加 AccessKey + Picovoice Console 训练唤醒词~~ | ❌ 已取消 | Whisper 开源方案无需任何外部账号/密钥/训练 |
| WAKE-012 | 集成回归（按住说话主路径/红色风险流程不受影响）+ 真机测试（唤醒率/防自听回声/冷却关窗/iOS 兼容） | ✅ 已完成（2026-08-05 标注） | 阶段 4：224 单测全绿 + 三端构建通过 + 主路径/风险流程代码完整性验证；真机测试项待物理设备到位后补测 |
| WAKE-013 | 登录页三主题风格落地（design/demo 三 HTML → LoginPage.jsx 实施） | ✅ 完成 | 2026-07-28；ocean/garden/rainbow 动画背景 + 彩虹键盘(0占两格/无✓) + 一体化语音唤醒勾选框 + 主题切换浮标 + Pad 横屏左品牌右表单；三端构建通过 |

---

## 十七、产品全景优化规划（design/30）

> 来源：项目全面审计 + 业界对标（Woebot/Wysa/心潮），覆盖 10 大方向。
> 详细设计见 `design/30_产品全景优化规划.md`，此处仅列任务 ID 与状态。

### AI 对话质量与智能化（P0）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| AI-001 | 对话质量评估指标体系（共情度/CBT 完成度/安全/满意度） | ✅ 完成 | B |
| AI-002 | LLM-as-Judge 自动评估管线（异步抽样 + 低分标记） | ✅ 完成 | B |
| AI-003 | 教师端质量监控增强（AI 评分可视化 + 抽检回放） | ✅ 完成 | C |
| AI-004 | 多模型路由（DeepSeek 主 + 通义/GLM 备，故障自动切换） | ✅ 完成 | B |
| AI-005 | Prompt 版本管理与 A/B 测试框架 | ✅ 完成 | C |
| AI-006 | RAG 心理知识库（Spring AI VectorStore + pgvector） | ✅ 完成 | E |
| AI-007 | 语音情感分析 SER（emotion2vec+，风险辅助信号） | ✅ 已完成（2026-08-05 状态确认） | voice-service 已完整实现 ASR(SenseVoiceSmall)+SER(emotion2vec_plus_large 9类)+风险融合；**数据闭环 VCL-001~003 已全部接线**（design/47）：语音情绪→currentEmotion 驱动共情策略（置信门控>0.6）/会话结束回注画像 emotionBaseline/跨会话趋势与文本×语音融合/趋势异常→risk_events attention 关注信号+量表复测建议/SER 标注回流评估（SerAccuracyReport）/分类阈值自适应（TrendAnomalySignaler），见 §二十三 VCL 系列 |
| AI-008 | 长期记忆增强（跨会话摘要 + 关键事件 + 画像回注） | ✅ 已完成（2026-08-05 状态确认） | MEM-101~103 已全部接线（design/50）：关键事件提取+top5 回注+画像回注（growthTrack/socialGraph）/主题演化+相关性召回（MemoryRelevanceScorer+ThemeEvolutionEngine）/**风险纵向关联**（MemoryRiskCorrelator 负面主题→risk_events 关注信号）/**多维遗忘策略**（学生意愿>敏感度>时效>数量，LongTermMemoryService.evictOldMemories 接线），见 §二十三 MEM 系列 |
| AI-009 | 心理量表数字化（PHQ-A/GAD-7/SDQ 嵌入式） | 🔒 冻结（2026-08-06 纳入冻结专题管理：施测接线 frozen/59、版权门禁 frozen/34；计分引擎已开发完成，解冻后接线施测） | design/34；SCALE-001/002 开发完成、施测接线冻结（frozen/59）；SCALE-003 版权门禁冻结（frozen/34） |

### 安全合规与信任体系（P0）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| COMP-005 | 敏感数据加密存储（AES-256 + 密钥轮换） | 🟩 已接线（fix-03） | D — FieldEncryptionService 已注入 ConversationServiceImpl：学生/AI 消息 contentSummary 落库前 AES-256-GCM 加密，教师端读取（getSessionMessages/replaySession/export）与摘要生成（generateSummaryAsync）解密；明文数据兼容透传；带密钥回归守卫测试 FieldEncryptionWiring 断言落库密文可还原。未配密钥时降级明文（dev），prod fail-fast |
| COMP-006 | 操作审计日志（管理员/教师敏感操作留痕） | ✅ 完成 | D |
| COMP-007 | 年度合规审计报送（未保条例 §37） | 🔒 冻结（与 AUTH-033 同事项合并，见 frozen/60） | 远期（上线后 1 年内） |
| COMP-008 | WebAuthn 设备认证（可选） | 🔒 冻结（2026-08-05 纳入冻结规划；真机测试条件触发） | 远期 |
| COMP-009 | voice-service 音频「转写即删」清理逻辑核实/补齐（22 §6.3 定稿承诺兑现：ASR/SER 完成后立即删除原始音频，仅留文本与情感特征值） | ✅ 完成（2026-07-28，见 design/22 §6.3 落地记录：voice-service finally 必删+删除日志留痕+mkstemp；Java 侧补 file-size-threshold 12MB 音频全程内存处理；日志不记音频/转写全文） | 近期（商用前） |
| COMP-010 | doc/ 历史物料违规表述扫描（非诊断表述底线：排查"诊断/治疗/心理咨询"等越界表述，出违规清单交钱敏健，25 §十 第 6 条） | ✅ 完成（2026-07-29，报告见 reports/COMP-010-doc物料违规表述扫描报告.md；真违规 7 类 24 处全在归档层，design/13 传导已修复；处置建议钱敏健 2026-07-29 全部确认：不改归档、封禁外发、doc/README 警示已加） | 近期（商用前） |

> COMP-001~004 为商务/法务流程，已移至「十八、商务与法务待办」。

### 工程质量与测试体系（P0）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| TEST-001 | 后端单测覆盖率 → 80%（JaCoCo 门禁） | ✅ 实际已达（2026-07-28 全量验证：整体行 84.3%，各模块 81.2%~97.6%） | A — fix-10 已修真（2026-07-29）：counseling-app report-aggregate verify 阶段生成聚合报告；CI 门禁报告缺失即失败+行覆盖≥40%。P1 批次补测后实测：api 86.7%/service 81.2%/ai 84.5%/app 85.7%/domain 97.6%，整体指令 85.6%/行 84.3%，1443 测试全绿；门禁阈值可随 CI 同步上调至 80% |
| TEST-002 | 前端组件测试（Vitest + Testing Library） | ✅ 已完成 | C |
| TEST-003 | E2E 扩展（12 → 30+ 用例） | ✅ 已完成（实际 tests/e2e/smoke-test.sh 28 个断言，未达 30+ 目标，如实校准） | C |
| TEST-004 | 性能压测基线（k6，100 并发 SSE） | ✅ 完成（脚本 tests/performance/chat-load.js，需手动 k6 执行） | E |
| TEST-005 | CI 增强（覆盖率门禁 + 依赖扫描 + 缓存） | ✅ 完成（fix-10，2026-07-29） | A — fix-10 已修真：mvn verify（surefire+failsafe）替代 mvn test；Trivy exit-code=1 阻断 CRITICAL/HIGH；AuthFlowIT 正常执行（CI Docker）/本地 disabledWithoutDocker 优雅跳过；CI 触发分支加入 develop |
| TEST-006 | 前后端契约测试（OpenAPI + mock 校验） | ✅ 已完成（2026-08-05，DOC-058）：三层防线落地——L1 ContractOpenApiIT 端点全量入文档（5 断言）+ L2 gen-openapi-snapshot.sh 快照生成（123 paths/93 schemas 入库）+ L3 前端契约测试（schemaValidator 22 + apiContract 26 用例）；修复 MoodCheckResult 契约漂移；全量回归 733 前端 + 后端全绿 | 远期 |

### DevOps 与运维能力（P1）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| OPS-001 | CD 自动化（CI → 镜像 → Registry → 部署） | ✅ 完成 | E |
| OPS-002 | Docker 镜像版本化（Git SHA tag + ACR） | ✅ 完成 | E |
| OPS-003 | 结构化日志 + 链路追踪（JSON + traceId） | ✅ 完成 | B |
| OPS-004 | 告警体系（AlertManager → 企微 webhook） | ✅ 完成（fix-05 接线） | B — SlaEscalationScanner @Scheduled 每分钟扫描 open/claimed 且超 SLA 的风险事件，AlertSlaPolicy 判定 escalate→CRITICAL / remind→WARNING，经 AlertService 出口（企微 webhook / 日志降级）发出，内存去重防风暴；SlaEscalationScannerTest 6 用例守卫。审计 P-05：此前红色风险无在线教师时仅 WARN 日志静默丢弃，现已接兜底告警。备注：教师端「自动改派备份老师」的改派动作仍归 WB-001 |
| OPS-005 | 数据库自动备份（pg_dump + 异地 + 恢复演练） | ✅ 完成 | A |
| OPS-006 | 蓝绿/滚动部署 | 🔒 冻结（frozen/42 部署架构升级专题，2026-08-05） | design/42（滚动+蓝绿+expand-contract） |
| OPS-007 | 多环境管理（dev/staging/prod） | ✅ 完成（fix-07 修真） | E — 审计 R-04：docker-compose.prod.yml 此前**从未设置 SPRING_PROFILES_ACTIVE=prod**，application-prod.yml 为死配置，JWT/加密 fail-fast 守卫全部沉默、Swagger 生产开放。fix-07 已修：compose 激活 prod profile + 补 ENCRYPTION_KEY/告警 webhook 映射；application-prod.yml 修 OPENAI→DeepSeek 漂移、删除非 root 不可写的 /var/log 文件日志（logback prod 本为 JSON stdout）；.env.example 全占位化；AdminTenantController 默认密码改 SecureRandom 随机；AliyunSmsService @PostConstruct 凭证 fail-fast |
| OPS-008 | 种子数据生产清理（V27） | ✅ 完成（fix-09） | 审计 R-05：V6 迁移注释明文泄露 minjianq 临时密码、MINDSAFE-TRIAL-001/002/003 硬编码邀请码存活。V27：minjianq password_hash 置无效哈希（限定原泄露哈希，已改密不覆盖）+ 三 TRIAL 码 disabled。裁决（钱敏健 2026-07-28）：DEMO2026 保留（V26 已延期，且 TrialAuthService 按固定试用租户查码，禁租户会断演示链路）；DEV/TRIAL 租户保留 active。V4 测试账号已由 V25 禁用；V8 演示学生因插入条件与 V4 冲突从未生效 |

### 数据智能与效果验证（P1）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| DATA-001 | 干预效果量化（前后量表对比 + 统计显著性） | ✅ 完成 | C |
| DATA-002 | 学生成长轨迹（学期情绪曲线 + 里程碑） | ✅ 完成 | C |
| DATA-003 | 校级报告自动生成（月度/学期 PDF） | ✅ 完成 | C |
| DATA-004 | 预警追踪闭环（预警→处置→回访→评估） | ✅ 完成 | C |
| DATA-005 | 研究数据脱敏导出（IRB 兼容） | 🔒 冻结（2026-07-28 方案冻结，见 frozen/62） | 远期 |

### 商业化与规模化（P2）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| BIZ-001 | 多租户生产化（独立 Schema + 自动迁移） | ✅ 完成 | D |
| BIZ-002 | 企微/钉钉 OAuth 配置上线 | 🔒 冻结（同 AUTH-021，见 frozen/61） | D |
| BIZ-003 | 阿里云 SMS 签名/模板申请 | 🔒 冻结（同 DEPLOY-010，见 frozen/61） | D |
| BIZ-004 | 计费与配额（按学校/学生数） | 🔒 冻结（frozen/38 计费配额与运营后台专题，2026-08-05） | design/38（订阅-权益-计量-配额；BILL-001 ✅、BILL-002 解冻后重建） |
| BIZ-005 | 信创适配评估（达梦/人大金仓） | 🔒 冻结（frozen/41 信创数据库适配专题，2026-08-05） | design/41（迁移风险清单+方言层+向量三路径） |
| BIZ-006 | 运营后台（平台级学校管理/收入/SLA） | 🔒 冻结（frozen/38，2026-08-05） | design/38 §六 |

### 性能与可扩展性（P2）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| PERF-001 | LLM 响应优化（首 token < 1s + 超时降级 + 重试 + 主备模型 + 监控埋点） | ✅ 完成 | E |
| PERF-002 | 数据库优化（慢查询 + 索引 + 连接池） | ✅ 完成 | E |
| PERF-003 | CDN + 前端代码分割 | 🔒 冻结（frozen/42，2026-08-05） | design/42 §四（CDN 缓存分层+manualChunks） |
| PERF-004 | Redis 缓存策略（画像/状态/配置） | ✅ 完成 | E |
| PERF-005 | 水平扩展（无状态 Session + LB + SSE 广播） | 🔒 冻结（frozen/40 水平扩展与无状态化专题，2026-08-05） | design/40（12-Factor 无状态化+Redis Pub/Sub） |
| PERF-006 | TTS 流式透传 + 前端切句优化（首句更快出声） | ✅ 完成 | E |

### 用户体验与交互升级（P2）

| 任务ID | 任务描述 | 状态 | Sprint |
|--------|----------|------|--------|
| UX-001 | 学生端 onboarding 优化 | ✅ 已完成 | E |
| UX-002 | 教师端工作台改版 | ✅ 实质完成（WB-001/002/003 + F-3 余量补全，余量见 design/35） | design/35（Sprint E） |
| UX-003 | 多语言支持（繁体/英文） | 🔒 冻结（2026-08-05 纳入冻结规划） | 远期 |
| UX-004 | 无障碍增强（WCAG 2.1 AA） | 🔒 冻结（2026-08-05 纳入冻结规划） | 远期 |
| UX-005 | 动效与微交互（Lottie + 粒子） | ✅ 已完成（2026-08-05 状态确认） | design/37 §四；TTSFX-001~004 已全部落地（2026-07-28） |

---

## 十八、商务与法务待办（非开发任务，2026-08-05 起冻结跟踪）

> 以下事项为商业化发布的前置合规/行政流程，责任人为钱敏健，需外部机构配合，不涉及代码开发。
> **2026-08-05 冻结**：全部事项统一冻结跟踪——COMP-001~004 + COMP-007 归 frozen/60（商用发布合规与备案），BIZ-002/BIZ-003 归 frozen/61（外部服务接入与配置）；解冻触发见对应冻结文档。

| 编号 | 事项 | 负责方 | 状态 | 备注 |
|------|------|--------|------|------|
| COMP-001 | 等保二级测评（差距评估 + 整改 + 测评机构出报告） | 钱敏健 + 测评机构 | 🔒 冻结（frozen/60） | 教育系统采购硬门槛，审核周期 1-3 月；差距评估已完成（design/31） |
| COMP-002 | 算法备案（生成式 AI，网信办） | 钱敏健 + 法务 | 🔒 冻结（frozen/60） | 需企业主体 + 算法说明文档 + 安全评估报告 |
| COMP-003 | 教育 App 备案（教育部） | 钱敏健 + 学校 | 🔒 冻结（frozen/60） | 进校前提，需学校配合提供办学资质 |
| COMP-004 | 告知同意条款法务审定 | 法务律师 | 🔒 冻结（frozen/60） | 需出具法律意见书，覆盖 PIPL + 未保法；量表同意项为 frozen/59 施测解冻前置 |
| BIZ-002 | 企微/钉钉 OAuth 配置上线 | 钱敏健 + 学校 IT | 🔒 冻结（frozen/61，同 AUTH-021） | 代码已就绪，需企业主体 + 学校提供 corpId/corpSecret |
| BIZ-003 | 阿里云 SMS 签名/模板申请 | 钱敏健 | 🔒 冻结（frozen/61，同 DEPLOY-010） | 需企业营业执照 + 审核 3-7 天（AUTH-040 生产态前置） |
| AUTH-033 | 年度合规审计报送（未保条例 §37） | 钱敏健 + 法务 | 🔒 冻结（frozen/60，同 COMP-007） | 流程性报送，非代码 |
| DEPLOY-010 | 阿里云 SMS 签名/模板（同 BIZ-003） | 钱敏健 | 🔒 冻结（frozen/61） | 与 BIZ-003 同一事项（合并跟踪） |

---

## 十九、文档全面更新（2026-07-28）

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| DOC-031 | 新增 design/33 系统测试培训手册（面向测试人员） | ✅ 完成 | 446 行，含功能测试点/API 速查/关键场景/安全机制/测试工具 |
| DOC-032 | design/25 产品功能说明升级 v2.0 | ✅ 完成 | 补充声纹登录/唤醒词/波波宠物/家庭码/RAG/年龄适配/冷场暖场/回访闭环/知识库等 |
| DOC-033 | DESIGN-OVERVIEW v3.0（+31/32/33 目录） | ✅ 完成 | 33 份设计文档导航 |
| DOC-034 | BEACON.md 当前状态同步 | ✅ 完成 | 反映 Sprint A-E 完成/安全加固/声纹/CI/CD/RAG |
| DOC-035 | TASK-TRACKER 同步 | ✅ 完成 | 本章节 |
| DOC-036 | 逐份核对 design/01~32 与代码一致性 | ✅ 完成 | 见下方核对记录 |

### DOC-036 核对记录

| 文档 | 核对结论 | 处理 |
|------|----------|------|
| 01 产品架构图 | 产品定位/分层/权限矩阵与实现一致 | 无需修改 |
| 02 Prompt 体系 | Advisor 链顺序/安全策略与实现一致 | 无需修改 |
| 03 CBT 流程树 | 状态机流程与实现一致 | 无需修改 |
| 04 风险识别规则库 | 2026-07-23 已对齐（§十七实现类名） | 无需修改 |
| 05 老师后台设计 | 核心模块一致，实际简化了部分（测评/预约未做） | 属 YAGNI 裁剪，无需修改 |
| 06 数据库结构设计 | 核心表一致，新增 12+ 扩展表 | ✅ 已补实现扩展说明 |
| 07 SaaS 多学校隔离 | Schema 级隔离/JWT 鉴权与实现一致 | 无需修改 |
| 08 MVP 最小可行版本 | M1-M4 全部完成，与实现一致 | 无需修改 |
| 09 商业模式 | 参考类文档，无代码对应 | 无需修改 |
| 10 政策与合规 | 参考类文档，合规机制已实现 | 无需修改 |
| 11 竞品分析 | 参考类文档，无代码对应 | 无需修改 |
| 12 技术架构 | 前端/网关/状态管理有偏差 | ✅ 已补实现偏差说明 |
| 13 Agent 工作流 | 2026-07-23 已改写为 Spring AI | 无需修改 |
| 14 儿童安全对话规范 | 2026-07-23 已对齐类名 | 无需修改 |
| 15 心理知识库建设 | RAG 已实现（AI-006），与方案一致 | 无需修改 |
| 16 API 接口设计 | 新增 15+ 端点未覆盖 | ✅ 已补实现偏差说明+新增端点清单 |
| 17 前端架构设计 | 技术栈/结构/状态管理有偏差 | ✅ 已补实现偏差说明 |
| 18 Prompt 模板库 | 2026-07-23 已对齐+登记 TSK-004 | 无需修改 |
| 19 界面详细设计 | 目标设计，实际简化实现 | 保留参考 |
| 20 端到端流程设计 | 核心流程与实现一致 | 无需修改 |
| 21 认证与试用准入 | 已实现（Phase 9-10） | 无需修改 |
| 22 告知同意条款 | 草稿待法务审定，非代码文档 | 无需修改 |
| 23 学生心理画像 | 已实现（PROF-001~004） | 无需修改 |
| 24 身份认证优化 | 已实现（AUTH-010~034） | 无需修改 |
| 25 产品功能说明 | 本次升级 v2.0 | ✅ 已更新 |
| 26 家长端 H5 | P1 已实现，P2 远期 | 无需修改 |
| 27 波波小精灵 | 已实现（BOBO-001~006） | 无需修改 |
| 28 语音唤醒与冷场引导 | 阶段 1-3 已实现 | 无需修改 |
| 29 学生画像与年龄适配 | P0+P1+P2 已实现 | 无需修改 |
| 30 产品全景优化规划 | Sprint A-E 核心完成 | 无需修改 |
| 31 等保二级差距评估 | 合规文档，非代码 | 无需修改 |
| 32 商用发布前置待办 | 行政文档，非代码 | 无需修改 |

---

## 二十、设计补充完善专题（2026-07-28）

> 背景：design/30 §十六要求“具体功能实施前需编写对应详细设计”。本专题基于业界最佳实践调研（MBC/SAMHSA、Wysa/Woebot、Lago/Stripe Billing、offline-first PWA、整群随机实验）一次性补齐 6 份远期任务设计文档，均为设计期、未实施。

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| DOC-037 | 输出 design/34 心理量表数字化设计方案（AI-009） | ✅ 完成 | MBC 闭环/计分引擎/S0 熔断/版权门禁 |
| DOC-038 | 输出 design/35 教师端工作台改版设计（UX-002） | ✅ 完成 | Today View/预警工作流/降噪/个案落地 |
| DOC-039 | 输出 design/36 心理工具箱与离线缓存设计（PROD-006/007） | ✅ 完成 | SOS/安全小岛/离线队列幂等重放 |
| DOC-040 | 输出 design/37 情感化TTS与动效微交互设计（PROD-003/UX-005，docs/17 补白） | ✅ 完成 | 情绪信号源统一/风险语音降级/动效预算 |
| DOC-041 | 输出 design/38 计费配额与运营后台设计（BIZ-004/006） | ✅ 完成 | 订阅-权益-计量-配额/危机链路豁免红线 |
| DOC-042 | 输出 design/39 画像效果量化与A/B实验设计（PROF-020） | ✅ 完成 | 班级整群随机/两层指标/护栏自动停 |
| DOC-043 | 同步 DESIGN-OVERVIEW v3.1 / TASK-TRACKER / BEACON | ✅ 完成 | 本章节 |

关联任务状态联动：AI-009 / UX-002 / BIZ-004 / BIZ-006 / UX-005 / PROF-020 均由“待开始/远期”更新为“📝 设计完成，待实施”；PROD-003/006/007 在 design/30 登记，对应设计见 design/36、37。

---

## 二十一、架构重构级规划（2026-07-28）

> 背景：design/30 将“无状态化水平扩展、信创适配、部署升级、家长端小程序化”列为**远期、规模化触发**的架构重构级任务。本专题基于业界最佳实践调研（The Twelve-Factor App 无状态化/Redis Pub/Sub 广播、信创 KingbaseES/达梦 DM8 迁移工具链、蓝绿·滚动·金丝雀发布 + expand-contract 兼容迁移、CDN 缓存分层 + Vite manualChunks 代码分割、Taro 一码多端）一次性补齐 4 份架构级设计文档，均为设计期、未实施、不含实现代码。

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| DOC-044 | 输出 design/40 水平扩展与无状态化架构设计（PERF-005） | ✅ 完成 | 12-Factor/WebSocket Redis Pub/Sub/会话状态外置/Prompt 缓存迁 Redis/nginx upstream |
| DOC-045 | 输出 design/41 信创数据库适配设计（BIZ-005/DEC-006/RISK-004） | ✅ 完成 | PG→金仓/达梦迁移风险清单 R1~R9/可插拔方言层/向量检索三路径/评估→转换→迁移→回归 |
| DOC-046 | 输出 design/42 部署架构升级设计（OPS-006/PERF-003） | ✅ 完成 | 滚动+蓝绿零停机/expand-contract 兼容迁移/CDN 缓存分层/manualChunks 代码分割 |
| DOC-047 | 输出 design/43 家长端小程序化设计（PROD-008/PARENT-WX-001~006） | ✅ 完成 | Taro React→weapp+H5 迁移，修正 design/26 “已是 Taro”表述不一致（实为 Vite+React SPA） |
| DOC-048 | 同步 DESIGN-OVERVIEW v3.2 / TASK-TRACKER / BEACON | ✅ 完成 | 本章节 |

关联任务状态联动：OPS-006 / PERF-003 / PERF-005 / BIZ-005 / AUTH-022 / PARENT-WX-001~006 均由“待开始/远期”更新为“📝 设计完成，待实施”，分别指向 design/40~43。

> ✅ 已修正（2026-07-28）：design/26 升级 v1.1，§2/§2.1/§2.2/§3.1/§7 的 “Taro 4 工程”表述已统一修正为“P1=Vite+React SPA（当前）、P2=迁 Taro（远期）”，与实际代码及 §3/§5 一致，并指向 design/43。

---

## 二十二、提示词个性化动态编排专题（2026-07-28）

> 背景：钱敏健提出“深度结合年龄/心理画像/进入心情状态 + 合规底线的个性化提示词设计，要动态变化”。调研现状代码后定位最大缺口：“进入心情状态”仅在 SYS-001 占位不驱动策略，且四维为静态拼接无编排层。基于业界最佳实践（Woebot/Wysa mood check-in、SAMHSA/MBC）与心理专业理论（皮亚杰/容纳之窗/WHO PFA/情绪ABC/MI/SEL/依恋理论）输出设计，设计期、未实施、不含实现代码。

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| DOC-049 | 输出 design/44 个性化提示词动态编排引擎设计（PROF-021） | ✅ 完成 | PromptOrchestrationService/StrategyProfile、情绪→开场与回应策略映射、情绪门控 CBT、情绪状态机会话内漂移切换、合规优先级裁决、EMO-001 模板 |
| PROF-021 | 提示词个性化动态编排引擎实施（情绪驱动策略 + 四维编排 + 情绪状态机） | ✅ 已完成（2026-08-05 状态确认） | design/44；ORCH-001~008 已全部落地接线（2026-07-28，含编排引擎/情绪门控/状态机漂移/优先级裁决/性格微调/EMO-001 A/B 灰度），见 §二十三 ORCH 系列 |
| DOC-050 | 同步 DESIGN-OVERVIEW v3.3 / TASK-TRACKER | ✅ 完成 | 本章节 |

关联任务状态联动：本专题与 design/29（年龄/性格适配）、design/28（冷场）、design/39（A/B 量化）互补；PROF-021 为新增任务，统筹四维个性化编排，与 PROF-010~014（design/29）/PROF-020（design/39）同属提示词工程个性化能力线。

---

## 二十三、设计驱动开发任务总表（按优先级，2026-07-28）

> 背景：钱敏健要求将 design/34~44 设计补充/提升衍生的开发任务，按优先级统一登记为可执行 backlog（**暂不开发**）；近期与远期均登记，远期显式标注。
> 定位：本表是全部“待实施”开发任务的**优先级排序单一视图**。各行拆分自对应设计文档自带的实施里程碑（M1/M2/M3 或 P/W/D/M-x 阶段），各设计的单行主任务（AI-009 / UX-002 / PROF-021 / PERF-005 / BIZ-005 等）仍保留在对应功能分区，本表是其阶段级细化与统一排序。
> 状态统一为 ⏳ 待实施（未开发）。优先级判据：**安全/合规 > 对话产品力 > 教师效率 > 学生体验 > 商业化 > 规模化架构**。近期=M4 上线前后应做；远期=规模化/采购/版权/企业认证触发。

### P0 · 近期（安全兜底 + 对话力核心）

| 任务ID | 阶段任务 | 期段 | 来源设计 | 关联主任务 | 状态 |
|--------|----------|------|----------|-----------|------|
| ORCH-001 | 提示词编排引擎骨架 + StrategyProfile + EntryMoodStrategyResolver（情绪→开场/回应策略）+ EMO-001 模板 + 接入 chat() 组装链 | 近期 | design/44 P0 | PROF-021 | ✅ 已完成（2026-07-28） |
| ORCH-002 | 情绪门控 allowCbt（ACTIVATED/CRISIS 禁认知重构） | 近期 | design/44 P0 | PROF-021 | ✅ 已完成（2026-07-28） |
| SCALE-001 | 量表计分引擎 + PHQ-A/GAD-7（免费量表先行，✅ 钱敏健 2026-07-28 确认）+ 关键条目即时熔断（S0 预警）；**施测已定稿暂缓（2026-07-28）：完成开发不接线，待首校共定施测方案（34 头部），退出商用门禁** | 近期 | design/34 M1 | AI-009 | ✅ 开发完成（2026-07-28，不接线施测）+ 🔒 冻结（施测接线决策见 frozen/59） |

### P1 · 近期（对话力延展 + 教师效率 + 学生体验）

| 任务ID | 阶段任务 | 期段 | 来源设计 | 关联主任务 | 状态 |
|--------|----------|------|----------|-----------|------|
| ORCH-003 | 情绪状态机 + 会话内情绪漂移切换（sad→crisis 升级 / anxious→calm 缓解） | 近期 | design/44 P1 | PROF-021 | ✅ 已完成（2026-07-28） |
| ORCH-004 | 情绪镜映话术库（情绪×年龄，纳入模板） | 近期 | design/44 P1 | PROF-021 | ✅ 已完成（2026-07-28） |
| ORCH-005 | 优先级裁决合并 + 冷场(28)/降级(29) 统一入编排 | 近期 | design/44 P1 | PROF-021 | ✅ 已完成（2026-07-28） |
| WB-001 | 教师工作台首屏（待办+时间线+概况条）+ 预警工作流（认领/处理/关闭 + SLA 逾期提醒） | 近期 | design/35 M1 | UX-002 | ✅ 已完成（2026-07-28：后端 AlertSlaPolicy + 前端 TodayTodoPanel/SLA倒计时列/预警时间线） |
| WB-002 | 学生详情页统一落地页 + 五角色字段裁剪 + 降噪（合并/聚合/静音） | 近期 | design/35 M2 | UX-002 | ✅ 已完成（2026-07-28：前端详情页 + 服务端角色裁剪；**F-3 补齐降噪静音规则 AlertTodoMutePolicy + 个案跟踪标志（免 schema 变更）TDD 全绿**） |
| TOOL-001 | 心理工具箱框架 + 情绪温度计 + 接地 + 正念（呼吸并入）+ 前后心情记录，内容包可离线打开 | 近期 | design/36 M1 | PROD-006 | ✅ 已完成（2026-07-28，后端 ToolboxRegistry+MoodCheckRecorder；前端 ToolboxPanel/ToolPractice/ChatRoom 入口 TDD 全绿；2026-07-29 补落地步骤内容包 src/data/toolSteps.ts 分步引导，音频/Lottie 余量见 design/36） |
| TOOL-002 | SOS 模式 + 安全小岛（断网可打开、热线可拨号，恢复网络 1min 内产 S2 事件） | 近期 | design/36 M2 | PROD-006/007 | ✅ 已完成（2026-07-28，后端 SOS 工具列表；前端 SosPanel 纯静态三段式+12355 拨号 TDD 全绿；S2 事件端点/安全小岛创建流程余量见 design/36） |
| TTSFX-001 | 情绪信号源统一 + 波波表情状态机 + 基础微交互（气泡/输入/思考中）+ 减弱动效降级 | 近期 | design/37 M1 | UX-005 | ✅ 已完成（2026-07-28，情绪信号源统一入编排；**✅ 以后端信号源为准，前端动效余量归 TTSFX-004**） |
| TTSFX-002 | 风险语音降级 + 预合成话术库 + 缓存（S1 用预合成、CosyVoice2 超时 2s 内切 edge-tts/纯文字） | 近期 | design/37 M2 | PROD-003 | ✅ 已完成（2026-07-28，VoiceDegradationPolicy） |
| SCALE-002 | 量表任务调度 + 复测 recurrence + 教师端趋势卡片；**施测已定稿暂缓同 SCALE-001（2026-07-28，完成开发不接线，34 头部）** | 近期 | design/34 M2 | AI-009 | ✅ 开发完成（2026-07-28，RecurrenceCalculator，不接线施测）+ 🔒 冻结（见 frozen/59） |

### P2 · 近期偏后（编排精调 + 效果验证 + 商业化底座）

| 任务ID | 阶段任务 | 期段 | 来源设计 | 关联主任务 | 状态 |
|--------|----------|------|----------|-----------|------|
| ORCH-006 | 性格层微调并入编排（衔接 design/29 personality_traits） | 近期 | design/44 P2 | PROF-021 | ✅ 已完成（2026-07-28） |
| ORCH-007 | EMO-001 A/B 版本（不同开场策略）经 PromptVersionService 灰度 | 近期 | design/44 P2 | PROF-021 | ✅ 已完成（2026-07-28） |
| WB-003 | 个案管理 + 测评管理入口（依赖 SCALE-002） | 近期 | design/35 M3 | UX-002 | ✅ 已完成（**F-3 补齐预警转派端点 + 五角色默认落地页差异化路由，teacher-web 新增 vitest 测试基建 TDD 全绿**） |
| TOOL-003 | 离线检测 UI + 消息队列 + 重放幂等 + 本地对话缓存 | 近期 | design/36 M3 | PROD-007 | ⛔ 已回退（2026-07-28 审计：OfflineMessageReplayService 为假接线已清除，目标态保留 design/36） |
| TTSFX-003 | 延迟流水线 + 成就粒子 + 触觉 + 帧率性能自动降级 | 近期 | design/37 M3 | UX-005 | ✅ 已完成（**后端信号源为准；前端动效余量归 TTSFX-004**） |
| TTSFX-004 | 37 前端动效余量：BoBoPet 表情状态机接编排信号 + Lottie 动效层（微交互/成就粒子/触觉）+ 帧率降级与减弱动效的前端实现 + 低端机延迟播放 | 近期 | design/37 审计余量（design/37 落地记录） | UX-005/TTSFX-001 | ✅ 已完成（2026-07-28，见 design/37 TTSFX-004 实施记录，TDD 覆盖 ≥80%） |
| AB-001 | 实验模型 + 班级整群分桶 + 变体注入点 + 曝光日志 + 个案豁免 | 近期 | design/39 M1 | PROF-020 | ⛔ 已清除（2026-07-28 审计判死：注入后零业务消费纯装饰日志，目标态保留 design/39） |
| AB-002 | 指标采集（三表情满意度反馈 + 风险属实勾选 + 各聚合任务） | 近期 | design/39 M2 | PROF-020 | ⛔ 已清除（2026-07-28 随 AB-001 一并清除，目标态保留 design/39） |
| BILL-001 | plans/entitlements/subscriptions 模型 + EntitlementFilter（bool 权益）+ 危机链路豁免注解 | 近期 | design/38 M1 | BIZ-004 | ✅ 已完成（2026-07-28） |
| BILL-002 | 计量事件流 + quota 执行 + 429 头 + 阈值告警 + 学校用量视图 | 近期 | design/38 M2 | BIZ-004 | 🔒 冻结（frozen/38，2026-08-05：quota 曾按 YAGNI 清除，仅 bool 权益保留；计费专题解冻后按 design/38 重建） |

### 远期（规模化 / 采购 / 版权 / 企业认证触发）

> 触发条件未到前不启动；均为设计期、未实施。**2026-08-05 起本表远期项已全部纳入对应 frozen/ 专题冻结跟踪**（状态列标注，不再作独立待办罗列），解冻触发与条件见对应冻结文档；数据库迁移类含红线操作（AGENTS §5 红线 3），实际执行须单独授权。

| 任务ID | 阶段任务 | 期段 | 来源设计 | 关联主任务 | 状态 |
|--------|----------|------|----------|-----------|------|
| SCALE-003 | SDQ 三版本 + MHT + 家长版 H5（**版权 license 校验为发布门禁**） | 🔭 远期 | design/34 M3 | AI-009 | 🔒 冻结（frozen/34 量表数字化专题，版权门禁） |
| ORCH-008 | 情绪编排效果量化并入 design/39 A/B（稳定回落速度/会话深度/满意 度） | 🔭 远期 | design/44 P3 | PROF-021 | ✅ 已完成 |
| AB-003 | 月度分析任务 + 平台实验报告页（含置信区间）+ 护栏指标越界自动停 | 🔭 远期 | design/39 M3 | PROF-020 | 🔒 冻结（frozen/39） |
| BILL-003 | 订阅生命周期自动流转（grace/expired）+ 平台运营后台六模块 | 🔭 远期 | design/38 M3 | BIZ-006 | 🔒 冻结（frozen/38） |
| STATE-001 | Prompt 缓存迁 Redis（改造面小、无长连接） | 🔭 远期 | design/40 P5-1 | PERF-005 | 🔒 冻结（frozen/40） |
| STATE-002 | 会话状态外置 ConversationStateManager（双写灰度→切换） | 🔭 远期 | design/40 P5-2 | PERF-005 | 🔒 冻结（frozen/40） |
| STATE-003 | WebSocket 预警 Redis Pub/Sub 广播 | 🔭 远期 | design/40 P5-3 | PERF-005 | 🔒 冻结（frozen/40） |
| STATE-004 | nginx upstream + 后端多副本（与 DEP-011 共用） | 🔭 远期 | design/40 P5-4 | PERF-005 | 🔒 冻结（frozen/40） |
| STATE-005 | 多实例压测（500 并发 SSE + 预警广播送达率 ≥99%） | 🔭 远期 | design/40 P5-5 | PERF-005 | 🔒 冻结（frozen/40） |
| DBAD-001 | 信创兼容性评估（KDMS/DTS 扫描 + R1~R9 逐项实测结论） | 🔭 远期 | design/41 M-0 | BIZ-005/DEC-006 | 🔒 冻结（frozen/41） |
| DBAD-002 | 可插拔方言层（JsonTypeHandler/数据源路由/SQL 方言 + db-* profile，PG 仍默认） | 🔭 远期 | design/41 M-1 | BIZ-005 | 🔒 冻结（frozen/41） |
| DBAD-003 | Schema 转换（目标库 DDL + 类型人工修正 JSONB/vector/序列） | 🔭 远期 | design/41 M-2 | BIZ-005 | 🔒 冻结（frozen/41） |
| DBAD-004 | 数据迁移 + 行数/校验和/抽样一致性校验（**红线：须授权**） | 🔭 远期 | design/41 M-3 | BIZ-005 | 🔒 冻结（frozen/41） |
| DBAD-005 | 向量方案落地（按 design/41 §四选定路径迁移 RAG） | 🔭 远期 | design/41 M-4 | RISK-004 | 🔒 冻结（frozen/41） |
| DBAD-006 | 应用回归 + PG↔信创双跑对比 + 运维工具链适配 | 🔭 远期 | design/41 M-5/M-6 | BIZ-005 | 🔒 冻结（frozen/41） |
| DEP-011 | nginx 单点 → upstream 池（与 STATE-004 共用，先落地） | 🔭 远期 | design/42 D-1 | OPS-006 | 🔒 冻结（frozen/42） |
| DEP-012 | 多副本 + start-first 滚动发布（强依赖无状态化 STATE-*） | 🔭 远期 | design/42 D-2 | OPS-006 | 🔒 冻结（frozen/42） |
| DEP-013 | 优雅关闭 + LB 摘除/draining 协同 | 🔭 远期 | design/42 D-3 | OPS-006 | 🔒 冻结（frozen/42） |
| DEP-014 | 蓝绿双环境 + upstream 切换 + 冒烟门禁（秒级回滚） | 🔭 远期 | design/42 D-4 | OPS-006 | 🔒 冻结（frozen/42） |
| DEP-015 | 前端代码分割（学生端优先，manualChunks + 路由懒加载） | 🔭 远期 | design/42 D-5 | PERF-003 | 🔒 冻结（frozen/42） |
| DEP-016 | CDN 接入 + 缓存策略（仅公共静态资源，绝不缓存含 PII 响应） | 🔭 远期 | design/42 D-6 | PERF-003 | 🔒 冻结（frozen/42） |
| PARENT-WX-001~006 | 家长端小程序化（Taro 迁移 W-1~W-7，企业主体认证为门禁）——**详见「十二、家长端 H5」P2 分表** | 🔭 远期 | design/43 | AUTH-022 | 🔒 冻结（frozen/43，企业主体认证门禁） |

### design/45~50 深化设计衍生任务（2026-07-28 新增，闭环化专题）

> 背景：钱敏健要求对 6 大专题（提示词工程/画像/语音情感/多音色/知识库/长期记忆）全面深化，产出 design/45~50 独立文档。以下为其衍生开发任务，**暂不开发**，仅登记排序。优先级判据同上。
> 关键更正：AI-008「长期记忆」原「✅完成」与代码不符（画像回注/主题演化/风险关联未做），已在「十七·AI 对话质量」更正为 🟡 部分实现；AI-007「语音情感 SER」基础已实现，更正为 🟡。

| 任务ID | 阶段任务 | 优先级 | 来源设计 | 关联主任务 | 状态 |
|--------|----------|--------|----------|-----------|------|
| PEVAL-001 | 接线未调用的 evaluateConversationQuality 到会话结束异步流程 + 落库 quality_scores（四维分+版本；实际表名 quality_scores（V19），非早期规划的 prompt_eval_result） | P0 近期 | design/45 P0 | AI-002/PROF-021 | ✅ 已完成（2026-07-28） |
| PEVAL-002 | 补全 EMO-001 模板正文并纳入 PromptVersionService（配合 ORCH-001） | P0 近期 | design/45 P0 | PROF-021 | ✅ 已完成（2026-07-28） |
| PEVAL-003 | 模板矩阵登记+版本命名规范 + 红队护栏用例集资产化 + 改版三门禁（红队/审校/eval 不回退） | P1 近期 | design/45 P1 | AI-005 | ✅ 已完成（2026-07-28；G-1 补齐门禁接线：RedTeamRegressionRunner 静态回归+activateVersion 三门禁+audit_logs 审批留痕，护栏 14 条/6 类，测试 55 用例全绿） |
| PEVAL-004 | 评估人群下钻看板 + 提示词 metrics + 灰度分阶段放量/自动回滚 + LLM-as-Judge κ 校准 | P2 近期偏后 | design/45 P2 | AI-003 | ✅ 已完成（2026-07-28） |
| PROF-025 | 画像字段加 provenance/confidence/updated_at/decay 元数据 + 画像→StrategyProfile 结构化决策接线（低置信不参与）；**原名 PROF-022，2026-07-28 审计发现与 L220 初高中学段适配挂账同号，改 PROF-025 消歧** | P0 近期 | design/46 P0 | PROF-021/AI-008 | ✅ 已完成（2026-07-28） |
| PROF-023 | 画像合并门控（置信加权+冲突检测+时效衰减）+ 量表结果回写画像 + 质量评估四维 | P1 近期 | design/46 P1 | AI-009 | ✅ 已完成（2026-07-28） |
| PROF-024 | 画像效果回收（有/无画像会话质量对比接 39/45）+ 无效维度降权自校准 + 教师侧脱敏摘要与订正回流 | P2 近期偏后 | design/46 P2 | PROF-020 | ✅ 已完成（2026-07-28） |
| VCL-001 | 语音情绪映射进 44 currentEmotion 驱动共情策略 + 会话结束聚合回注画像 emotionBaseline | P0 近期 | design/47 P0 | AI-007/PROF-021 | ✅ 已完成（2026-07-28） |
| VCL-002 | voice_emotion_trend 跨会话趋势 + 文本×语音融合与不一致(掩饰)检测 | P1 近期 | design/47 P1 | AI-007 | ✅ 已完成（2026-07-28） |
| VCL-003 | 趋势异常→教师关注信号+量表复测 + SER 标注回流评估儿童 domain 准确度 + 分类阈值自适应 | P2/远期 | design/47 P2/P3 | AI-007 | ✅ 已完成（2026-07-28） |
| TMATCH-001 | VoicePersonaResolver 冷启动默认匹配（性别认同/年龄）+ emotionState→prosody 基调联动（非仅 instruct） | P0 近期 | design/48 P0 | UX-005 | ✅ 已完成（2026-07-28） |
| TMATCH-002 | 画像匹配微调+手动偏好记忆回写46 + 安全/危机稳定基调锁定+预合成矩阵（统一 TTSFX-002）+ 三方同源接线 | P1 近期 | design/48 P1 | UX-005/PROD-003 | ✅ 已完成（2026-07-28） |
| TMATCH-003 | 音色效果回收（完成率/切换/参与度）+ 会话内稳定性 + 匹配规则 A/B 进化 | P2/远期 | design/48 P2/P3 | PROF-020 | ✅ 已完成（2026-07-28） |
| KB-101 | 知识内容首批生产（CBT/SEL/PFA/危机/工具箱，结构化 02/03/36）+ RAG Advisor 接入对话主线（场景触发+年龄过滤+不覆盖安全） | P0 近期 | design/49 P0 | AI-006 | ✅ 已完成（2026-07-28）：62 条语料解析+幂等入库机制（`POST /api/v1/knowledge/corpus`，危机类 10 条缓入待 KB-102）+ `RagAdvisorService` 接主线（场景触发/grade_band 近似过滤/危机双保险/失败安全），解 AI-006 门禁，见 design/49 §6.5 |
| KB-102 | 审核工作流状态机+门禁 + 知识条目元数据增强 + 危机内容与 04/14 单一事实源打通 | P1 近期 | design/49 P1 | AI-006 | ✅ 已完成（2026-07-28，ReviewWorkflowStateMachine+ReviewGateValidator+KnowledgeMetadata） |
| KB-103 | 混合检索（向量+关键词双路 RRF 融合，实际为 RRF 排序而非加权求和）+ groundedness 回收+未命中查询补全 + 语义分块优化 | P2/远期 | design/49 P2/P3 | AI-006 | ✅ 已完成（2026-07-28；审计发现 fuseRRF 仅测试调用未接主线，已于后续审计修复接入 RagAdvisorService.buildRagContext：向量路+关键词路各 top5 → RRF 融合 → 危机隔离/年级段过滤 → top3，关键词路异常降级纯向量，17 用例绿） |
| MEM-101 | **更正 AI-008 状态**（已在十七完成）+ 记忆→画像回注（growthTrack/socialGraph，provenance=memory） | P0 近期 | design/50 P0 | AI-008 | ✅ 已完成（2026-07-28） |
| MEM-102 | recurring_theme 主题演化（聚类+反思）+ 相关性召回升级（向量+重要性+时效+recurring）+ MEM-CTX+continuity 接 45 | P1 近期 | design/50 P1 | AI-008 | ✅ 已完成（2026-07-28，MemoryRelevanceScorer+ThemeEvolutionEngine） |
| MEM-103 | 记忆与风险纵向关联（负面主题→关注信号，非实时报警）+ 遗忘策略升级（时效/敏感/被遗忘权）+ 双向互哺权重调优 | P2/远期 | design/50 P2/P3 | AI-008 | ✅ 已完成（2026-07-28）；ARCH-004 台账核对（2026-08-06）：遗忘策略实际接线 3 维（敏感度>时效衰减>数量淘汰），学生意愿（被遗忘权）恒 false 未接线——无 forget 请求入口，P2 升级，LongTermMemoryService 注释已同步标注 |

### design/51~53 分析文档衍生（2026-07-28 新增，✅ 钱敏健 2026-07-28 全部确认）

> 背景：design/51（横向断链）/52（核心板块心理深化）/53（全板块设计-实现脱节）为分析型文档。其优化方向**绝大多数映射到上方已登记 ID**（ORCH-001~004/PEVAL-001/KB-101/PROF-025/WB-001/MEM-101~102/TMATCH-001/STATE-002~003/BILL-*/SCALE-*/AB-*），不重复登记。下表仅登记 design/52 衍生的**真正新增**项，**2026-07-28 钱敏健全部确认**，状态统一为 ⏳ 待实施（未开发）。总前提：“双世界架构”（世界A线上单 prompt vs 世界B 孤儿 Agent 编排）——见 design/52 〇。**DEC-CBT 重新决策：删除世界 B**（钱敏健 2026-07-29，推翻原「路径1激活」）——世界B 整链死代码、与 SSE 流式冲突、无接线价值，fix-02 删除，线上保留世界A 单 prompt 主线。

| 任务ID | 阶段任务 | 优先级 | 来源设计 | 依赖 | 状态 |
|--------|----------|--------|----------|------|------|
| DEC-CBT | 双世界编排收敛决策——**✅ 重新决策：删除世界 B（钱敏健 2026-07-29）**，推翻原「路径1激活」；世界B（ConversationOrchestrator+7 Agent+CbtStateMachine+ConversationStateManager）整链零调用死代码，无接线价值且与 SSE 流式体验冲突；线上保留世界A（PromptOrchestrationService 单 prompt 主线） | P0 决策 | design/52 〇/一/四；design/13 改写 | 无（最先） | ✅ 已决策删除（2026-07-29），fix-02 执行删除 |
| RISK-201 | RED 硬短路跳过 LLM，复用 CrisisResources（**儿童安全红线级，可独立立即做**） | **P0 安全最高** | design/52 二 | 无 | ✅ 已完成（2026-07-28）：ConversationServiceImpl 4.2 段 RED 硬短路 + 分年级预审核文案 + 安全响应模式，见 design/04 §18.2 落地记录 |
| RISK-202 | M2 语义风险分类（SAF_001）上线补隐性表达 | P0 安全 | design/52 二 | DEC-CBT（已决） | ✅ 已完成（2026-07-28）：SemanticRiskClassifier 非流式前置调用（800ms 门禁可配）+ 主线 1.6 段只升不降 + SafetyAgent 委托复用，见 design/04 §18.3 落地记录 |
| RISK-203 | RiskScoreCalculator + C-SSRS 儿童分级（落地 04 §十） | P1 | design/52 二 | RISK-202 | ✅ 已完成（2026-07-28） |
| RISK-204 | TrendAnalyzer 纵向趋势（汇入 BL-08 通道，合并 VCL-003/MEM-103） | P2 | design/52 二 | — | ✅ 已完成（2026-07-28）：SessionEndAnalyticsService+LongTermMemoryService 持久化 attention 信号到 risk_events（source_type=attention, risk_level=YELLOW），复用 BL-08 通道 |
| SAFE-201 | 保密边界儿童化告知话术（接 design/22，落到对话首触点） | P0 安全 | design/52 三 | 无 | ✅ 已完成（2026-07-28）：ConfidentialityNotice 分年级话术 + createSession 注入 + turn=0 审计落库，见 design/14 §12.3 落地记录 |
| SAFE-202 | Layer2 高敏场景前置化/触发关注 | P1 | design/52 三 | 无 | ✅ 已完成（2026-07-28） |
| SAFE-203 | 危机热线多租户可配置（去硬编码） | P1 | design/52 三 | 无 | ✅ 已完成（2026-07-28） |
| SAFE-204 | 姓名/地址脱敏扩展 | P2 | design/52 三 | 无 | ✅ 已完成（2026-07-28）：PiiDesensitizer 扩展百家姓词典+上下文句式姓名正则+地址三层正则，maskName/maskAddress 方法，26 用例全绿 |
| CBT-201 | CBT 阶段标记结构化输出+落库供评估（接 45） | P1 | design/52 一 | DEC-CBT（已决） | ✅ 已完成（2026-07-28，CbtStageRouter） |
| CBT-202 | 年龄分层 CBT 技能路由（低龄行为激活） | P1 | design/52 一 | CBT-201 | ✅ 已完成（2026-07-28，CbtStageRouter AgeStrategy） |
| EMP-201 | 共情“命名-确认-容纳”评估维度（接 45） | P1 | design/52 四 | PEVAL-001 | ✅ 已完成（2026-07-28，EmpathyStructureEvaluator 三段式+反模式检测） |
| ALLY-201 | 连续性开场（记忆回注生成续接话术） | P1 | design/52 五 | MEM-102 | ✅ 已完成（2026-07-28，AllianceEnhancer） |
| ALLY-202 | 收束“巩固-希望-桥接”结构化 | P1 | design/52 五 | PEVAL-001 | ✅ 已完成（2026-07-28，AllianceEnhancer） |
| ALLY-203 | 中断-回归照护信号（汇入 BL-08） | P2 | design/52 五 | — | ✅ 已完成（2026-07-28，AllianceEnhancer） |

> 量表决策（钱敏健 2026-07-28）：**免费量表 PHQ-A/GAD-7 先行**（与 SCALE-001 一致）；开发可先实现，**施测接线上线前须钱敏健再决策**（未成年人测评合规门禁，见 SCALE-001/002 上线门禁备注）。

> design/53 补充：其 P0~P2 优化方向均映射到上表或已登记 ID，未引入新 ID；核心贡献是**四态判定法**与态③“已建未接线”资产盘点（世界B编排/ConversationStateManager/evaluateSessionAsync/buildRagContext 均零调用），提示“接线性价比远高于重写”。design/51 BL-01~BL-08 断链均已在上方 ORCH/PEVAL/KB/MEM/VCL/TMATCH 各行映射。

### 远期散项（已在既有分区登记，此处汇总排序）

| 任务ID | 任务 | 来源分区 | 状态 |
|--------|------|----------|------|
| AI-007 | 语音情感分析 SER（emotion2vec+，风险辅助信号） | 十七·AI 对话质量 | ✅ 已完成（2026-08-05 状态确认，见 §十七/§二十三 VCL 系列） |
| COMP-007 | 年度合规审计报送（未保条例 §37，流程性） | 十七·安全合规 / 十八 | 🔒 冻结（frozen/60，同 AUTH-033 合并） |
| COMP-008 | WebAuthn 设备认证（可选，不采集生物数据） | 十七·安全合规 | 🔒 冻结（2026-08-05 纳入冻结规划） |
| TEST-006 | 前后端契约测试（OpenAPI + mock 校验） | 十七·工程质量 | ✅ 已完成（2026-08-05，DOC-058，见 §五 TEST-006） |
| DATA-005 | 研究数据脱敏导出（IRB 兼容） | 十七·数据智能 | 🔒 冻结（2026-07-28 方案冻结 frozen/62） |
| UX-003 | 多语言支持（繁体/英文） | 十七·用户体验 | 🔒 冻结（2026-08-05 纳入冻结规划） |
| UX-004 | 无障碍增强（WCAG 2.1 AA） | 十七·用户体验 | 🔒 冻结（2026-08-05 纳入冻结规划） |

> 说明：
> 1. 本表为**排序视图**，不改变既有分区中各主任务的“📝 设计完成，待实施”标记；开发启动时以本表 P0→P1→P2→远期 顺序推进。
> 2. **依赖约束**：ORCH-* 依赖 design/29 年级接通（已完成 P0）；STATE-*（无状态化）是 DEP-012（滚动发布）的强前置——未完成无状态化不得开多副本滚动；SCALE-003 受版权门禁、PARENT-WX 受企业认证门禁、DBAD-004 属数据迁移红线。
> 3. 本次仅登记任务与排序，**未进行任何开发、未做 git 提交**。

---

## 二十四、设计深化总追踪表（2026-07-28，逐篇 triage）

> 背景：钱敏健要求以 `design/` 全部 55 篇为清单，逐篇结合代码现状 + 业界最佳实践 + 目标用户（小学生）+ 落地场景，全面深化设计、补衔接、清「虚假设计未落地」，**本次不写代码，只做设计提升与任务定级**。本表是 triage 单一视图；具体开发任务仍归 §二十三 backlog。
> 责任人约定：**决策/合规/监管红线 → 钱敏健（AI 只提供信息不决策）；法务条款 → 法务；设计深化/文档编辑 → Agent**。
> 现状评级：🟢 近期已深化（34-53 系列，作为本轮基准/仅维护对齐）｜🟡 待深化（基础文档，缺口/断链明确）｜🔵 维护对齐（随批次微调）。
> 深化优先级判据同 §二十三：**安全/合规 > 对话产品力 > 教师效率 > 学生体验 > 商业化 > 规模化架构**。

**深化批次划分（执行顺序）：**
- **P0 深化**（对话治疗与安全核心，DEC-CBT 双世界落地锚）：02 / 03 / 04 / 13 / 14（+44 关联对齐）
- **P1 深化**（对话产品力 / 画像记忆 / 语音交互 / 接口流程 / 合规）：05 / 10 / 15 / 16 / 18 / 20 / 22 / 23 / 27 / 28 / 29 / 54 / 55（+34 施测门禁）
- **P2 深化**（平台运营 / 架构部署 / 商业化 / 基础文档）：01 / 06 / 07 / 08 / 09 / 11 / 12 / 17 / 19 / 21 / 24 / 25 / 26 / 30 / 31 / 32 / 33
- **🟢 维护批次**（34-53 近期专题）：随对应上游批次微调，不单独深化

| 编号 | 文档 | 现状 | 深化重点（缺口 / 衔接 / 虚假未落地） | 优先级 | 责任人 | 状态 |
|------|------|------|--------------------------------------|--------|--------|------|
| 01 | 产品架构图 | 🟡 | 补双世界架构现状（世界A线上单prompt / 世界B未接线）；Agent 协同图对齐 DEC-CBT 决策；数据流补画像/记忆/语音回注汇聚点 | P2 | Agent | ✅ 已深化（§9，2026-07-28，愿景 Agent 层多为等价实现；三条回注环已补；测评倒挂已登记） |
| 02 | Prompt 体系设计 | 🟡 | 与 44/45 编排对齐（静态拼接→先算策略再拼）；情绪门控/合规裁决入组装链；补版本治理引用 | **P0** | Agent | ✅ 已深化（§19，2026-07-28） |
| 03 | CBT 对话流程树 | 🟡 | 标注 CbtStateMachine 属世界B未接线；容纳之窗情绪门控（CRISIS/激活态禁认知重构）；年龄分层 CBT 技能路由；阶段标记结构化落库 | **P0** | Agent | ✅ 已深化（§十一，2026-07-28） |
| 04 | 风险识别规则库 | 🟡 | RED 硬短路跳过 LLM（现状仅留痕不短路）；M2 语义分类补隐性表达；RiskScoreCalculator+C-SSRS 儿童分级；纵向趋势通道 | **P0** | Agent | ✅ 已深化（§十八，2026-07-28） |
| 05 | 老师后台设计 | 🔵 | 与 35 改版对齐（Today View / 预警工作流状态机 / 降噪），正文补状态机，避免与 35 重复叙述 | P1 | Agent | ✅ 已深化（§20，2026-07-28） |
| 06 | 数据库结构设计 | 🟡 | 汇总新增表 DDL：画像元数据(provenance/confidence/decay)、量表 jsonb、分层记忆、voice_emotion_trend、prompt_eval_result、计费；向量索引策略 | P2 | Agent | ✅ 已深化（§10，2026-07-28，待实施 DDL 单一登记处已建；HNSW 统一策略；DDL 执行前须钱敏健确认） |
| 07 | SaaS 多学校隔离 | 🔵 | ✅ 已深化（§11，2026-07-28，**架构级偏差：实际为行级隔离非 Schema 级**，路线 A 已定稿；fix-06 已落地行级 TenantLineInnerInterceptor（策略 B）+ ParentAuthService 去硬编码，fail-fast 待收紧；热线配置 SAFE-203 已补） | P2 | Agent | ✅ 已深化 |
| 08 | MVP 最小可行版本 | 🔵 | ✅ 已深化（§十，2026-07-28，M4/M5 边界已定待确认；人脸识别撤销；测评滞后为风险项） | P2 | 钱敏健+Agent | ✅ 已深化 |
| 09 | 商业模式与采购 | 🔵 | ✅ 已深化（§10，2026-07-28，**客户画像错位：中学→小学主打待确认**；基础版筛查能力未落地不得先行承诺） | P2 | 钱敏健 | ✅ 已深化 |
| 10 | 政策与合规风险 | 🟡 | 补未成年人测评合规门禁(量表施测)、生成式AI 备案、语音本地处理表述诚实性；合规硬约束→任务映射 | P1 | 钱敏健+法务 | ✅ 已深化（§10，2026-07-28，三项待钱敏健确认） |
| 11 | 竞品深度分析 | 🔵 | ✅ 已深化（§9，2026-07-28，Woebot 停运/Wysa LLM 化已更新；差异化重定位=小学真空带+家校闭环+安全工程化；§2 旧表禁用于对外物料） | P2 | Agent | ✅ 已深化 |
| 12 | 技术架构 | 🔵 | 补双世界编排收敛后目标架构；Spring AI Advisor 链现状 vs 目标 | P2 | Agent | ✅ 已深化（§14，2026-07-28，架构事实=Java单体+2 Python边车；Advisor 链定稿：Service 层显式管线为正式路线；网关以 Nginx 为准） |
| 13 | Agent 工作流 | 🟡 | **DEC-CBT 落地锚文档**：显式标注 7 Agent(世界B)已实现零调用；接线方案(SSE 流式兼容：分诊非流式前置+回复流式)、延迟/成本评估、与 44 ORCH 收敛 | **P0** | Agent | ✅ 已深化（§十三，2026-07-28） |
| 14 | 儿童安全对话规范 | 🟡 | Layer2 从「仅留痕」→召回改写；保密边界儿童化告知话术落首触点(SAFE-201)；热线配置化去硬编码 | **P0** | Agent | ✅ 已深化（§十二，2026-07-28） |
| 15 | 心理知识库建设 | 🔵 | 与 49 深化对齐（内容生产/审核工作流/RAG 接主线/RRF）；正文标注未实现项已由 49 承接 | P1 | Agent | ✅ 已深化（§12，2026-07-28） |
| 16 | API 接口设计 | 🟡 | 补新增端点：量表施测/工具箱/编排内部API/画像元数据/记忆；错误码补全；WebSocket 广播(40) | P1 | Agent | ✅ 已深化（§12，2026-07-28，含错误码冲突修复方案） |
| 17 | 前端架构设计 | 🔵 | 补代码分割(42)、离线缓存(36)、全感官交互(55)组件；状态管理对齐 | P2 | Agent | ✅ 已深化（§10，2026-07-28，PWA/代码分割/全感官组件均已落地；学生端 sessionStorage 策略定稿采纳；Zustand/React Query 不引入定阈值） |
| 18 | Prompt 模板库 | 🟡 | 补全 EMO-001 情绪模板；与 45 模板矩阵/红队护栏对齐；Advisor 链组装顺序更新(编排后) | P1 | Agent | ✅ 已深化（§16，2026-07-28） |
| 19 | 界面详细设计 | 🔵 | 补波波宠物四态(27)、工具箱(36)、量表施测 UX(34)、教师工作台(35)界面规格 | P2 | Agent | ✅ 已深化（§9，2026-07-28，教师端单页面板式定稿；个案/预约/量表 UX ⬜ 归口既有任务；预警等级标记统一以 04 为准；§8.4 性别配色降为默认建议） |
| 20 | 端到端流程设计 | 🟡 | 时序图更新为编排后(分诊→策略→回复流式)；补量表施测/工具箱/连续性开场流程；危机短路时序 | P1 | Agent | ✅ 已深化（§10，2026-07-28） |
| 21 | 认证与试用准入 | 🔵 | 4 决策已定(决策#13)正文回填；与 24 认证优化去重 | P2 | 钱敏健+Agent | ✅ 已深化（§十一，2026-07-28，P0/P1 全落地含 WebSocket/PIN/改密；permitAll 兜底收紧列 P2；与 24 职责边界定稿；Schema 表述随 07 改写） |
| 22 | 告知同意条款 | 🔵 | 补量表测评同意项、语音本地处理表述；待法务审定（合规红线，不替决策） | P1 | 钱敏健+法务 | ✅ 已深化（§六，2026-07-28，量表/语音条款缺口已标注待法务审定） |
| 23 | 学生心理画像设计 | 🟡 | 与 46 闭环对齐：字段加 provenance/confidence/decay；画像→StrategyProfile 决策接线；效果回收自校准 | P1 | Agent | ✅ 已深化（§9，2026-07-28） |
| 24 | 身份认证优化方案 | 🔵 | 与 21 去重；PIN/锁定/监护人同意落地状态标注 | P2 | 钱敏健+Agent | ✅ 已深化（§七，2026-07-28，三阶段大面积落地；家长登录改道手机号+密码定稿；parent_bindings DDL 冻结；密码复杂度/企微配置列 P2） |
| 25 | 产品功能说明 | 🔵 | v2.0 已补，随功能深化同步；面向学校/家长表述核对 | P2 | Agent | ✅ 已深化（§十，2026-07-28，修正 Schema 隔离/密码策略两处超前承诺；对外禁用物理隔离表述；测试账号商用前删除归 32） |
| 26 | 家长端 H5 与小程序 | 🔵 | 与 43 小程序化对齐、修正 Taro 表述；周报/同意管理已实现核对 | P2 | Agent | ✅ 已深化（§9，2026-07-28，家庭码双模式定稿采纳实态；parent_bindings 随 24 冻结；§4/§6 重写列 P3） |
| 27 | 波波品牌与宠物交互 | 🔵 | 与 37 情感化TTS/动效三方同源对齐；表情状态机引用；与 55 全感官去重 | P1 | Agent | ✅ 已深化（§十，2026-07-28，实现领先于文档：五态+实时转写已登记） |
| 28 | 语音唤醒与冷场引导 | 🔵 | 冷场决策(NudgeDecisionModel)已生效核对；与 47/48 语音闭环对齐；唤醒授权措辞（合规） | P1 | 钱敏健+Agent | ✅ 已深化（§十二，2026-07-28，三功能全部落地 🟩，xiaotaiyang 已修复；授权措辞+唤醒词入待确认清单） |
| 29 | 学生画像与年龄适配 | 🔵 | 核心竞争力（近期）：与 46 画像闭环、44 编排 personality 层衔接 | P1 | Agent | ✅ 已深化（§十，2026-07-28，实现领先于文档：五断裂点已全部修复 🟩） |
| 30 | 产品全景优化规划 | 🔵 | 路线图纳入 DEC-CBT 落地 + 本设计深化批次；Sprint 节奏对齐 | P2 | 钱敏健+Agent | ✅ 已深化（§十七，2026-07-28，Sprint A-E 已被超越不再按表执行；上线门禁=量表合规+RAG 空库；BIZ-001 挂起待 07） |
| 31 | 等保二级差距评估 | 🔵 | 合规路径随部署推进（非开发） | P2 | 钱敏健 | ✅ 代码侧差距全部修复（2026-07-29）：fix-03 加密接线 / fix-07 prod profile 激活 / fix-08 TLS+wss+origin 收敛 / fix-10 CI 门禁修真 / SecurityConfig anyRequest().authenticated() 收紧（commit 701a3a0，白名单仅 auth 入口/wecom/guardian-consent confirm/parent/health/ws，教师端点已核实全落 /teacher/**+/alerts/** 匹配器）；剩余为运维/文档项（云安全组审计、异地备份、WAF、管理制度 3 份），钱敏健牵头 |
| 32 | 商用发布前置待办 | 🔵 | 补量表施测合规门禁项；与本追踪表联动 | P1 | 钱敏健 | 🔒 冻结（frozen/60 商用发布合规与备案专题关联文档，2026-08-05） |
| 33 | 系统测试培训手册 | 🔵 | 编排/量表/工具箱上线后补测试点 | P2 | Agent | ✅ 已完成（2026-08-05 标注；DOC-031 手册已产出，测试点随上线批次维护） |
| 34 | 心理量表数字化 | 🟢 | 近期已深化；施测接线**上线门禁**决策已冻结跟踪（frozen/59） | P1 | 钱敏健+Agent | 🟢 维护 |
| 35 | 教师端工作台改版 | 🟢 | 近期已深化；随 05 对齐维护 | P1 | Agent | 🟢 维护 |
| 36 | 心理工具箱与离线缓存 | 🟢 | 近期已深化；随 17/19 对齐维护 | P1 | Agent | 🟢 维护 |
| 37 | 情感化TTS与动效 | 🟢 | 近期已深化；随 27/48/55 对齐维护 | P1 | Agent | 🟢 维护 |
| 38 | 计费配额与运营后台 | 🟢 | 近期已深化；随 09 对齐维护 | P2 | Agent | 🟢 维护 |
| 39 | 画像效果量化与A/B | 🟢 | 近期已深化；维护 | P2 | Agent | 🟢 维护 |
| 40 | 水平扩展与无状态化 | 🟢 | 近期已深化（架构远期）；随 07/42 维护 | P2 | Agent | 🟢 维护 |
| 41 | 信创数据库适配 | 🟢 | 近期已深化（远期）；维护 | P2 | 钱敏健+Agent | 🟢 维护 |
| 42 | 部署架构升级 | 🟢 | 近期已深化（远期）；维护 | P2 | Agent | 🟢 维护 |
| 43 | 家长端小程序化 | 🟢 | 近期已深化（远期）；随 26 维护 | P2 | Agent | 🟢 维护 |
| 44 | 个性化提示词动态编排引擎 | 🟢 | 编排核心；DEC-CBT 世界B收敛对齐（ORCH 策略层并入世界B链） | **P0 关联** | Agent | 🟢 维护 |
| 45 | 提示词工程体系深化 | 🟢 | 近期已深化；随 02/18 维护 | P1 | Agent | 🟢 维护（G-1 落地 P1：模板矩阵/红队门禁/三门禁审批留痕，2026-07-28） |
| 46 | 学生画像自动化迭代闭环 | 🟢 | 近期已深化；随 23/29 维护 | P1 | Agent | 🟢 维护 |
| 47 | 语音情感分析数据闭环 | 🟢 | 近期已深化；与 54 去重对齐 | P1 | Agent | 🟢 维护 |
| 48 | 多音色音调自适应匹配 | 🟢 | 近期已深化；随 37 维护 | P1 | Agent | 🟢 维护 |
| 49 | 心理知识库建设深化 | 🟢 | 近期已深化；与 15 对齐 | P1 | Agent | 🟢 维护（G-2 落地运营侧采编工作流：EditorialWorkflowService 四动作编排+缺口报表+editorial 端点，2026-07-28） |
| 50 | 长期记忆增强系统 | 🟢 | 近期已深化；维护 | P1 | Agent | 🟢 维护 |
| 51 | 横向断链分析 | 🟢 | 分析型，本轮横向基准；维护 | 基准 | Agent | 🟢 维护 |
| 52 | 核心功能板块心理深化 | 🟢 | 分析型，DEC-CBT 已决策；P0 深化直接落 02/03/04/13/14 | 基准 | Agent | 🟢 维护 |
| 53 | 全板块设计实现脱节 | 🟢 | 分析型，四态判定基准；维护 | 基准 | Agent | 🟢 维护 |
| 54 | 语音情感分析设计方案 | 🟡 | 新迁入(原docs/16)：与 47 去重合并——明确 54=基础分类方案、47=闭环增强；风险辅助信号定位 | P1 | Agent | ✅ 已深化（§10，2026-07-28，核心链路已落地 🟩，SenseVoice+emotion2vec 定稿选型已登记） |
| 55 | 学生端全感官交互设计方案 | 🟡 | 新迁入(原docs/17)：与 37/27 去重；视觉主题系统落地状态核对 | P1 | Agent | ✅ 已深化（§十，2026-07-28，主体已落地 🟩：三主题+TTS链路+情绪映射；音色路线偏差已定稿登记） |

> 说明：
> 1. 本表为 triage 单一视图，`⬜ 待深化` = 本轮需编辑正文，`🟢 维护` = 近期已深化仅随批次微调；深化产生的新开发任务并入 §二十三 对应 ID，不在此重复登记。
> 2. **虚假设计未落地重点**（承 51-53）：世界B Agent 编排 / ConversationStateManager / evaluateSessionAsync / buildRagContext / 语言模板路由均「已建零调用」——深化时须在对应文档（13/03/04/40/49/29）显式标注「已实现未接线」，避免误读为已生效。
> 3. 本次仅深化设计与定级，**未进行任何开发、未做 git 提交**。

## 二十五、配置统一纳管（design/57）

> 背景：配置分散于环境变量、application.yml、Python 硬编码、前端 TypeScript 四处，存在前后端阈值不同步、TTS 音色矩阵改参数需改代码发版、引导脚本运营不可调等痛点。本专题统一纳管，实现“改配置不改代码”。
> 设计文档：`design/57_配置统一纳管设计.md`（2026-08-01 创建，2026-07-28 v2 更新）
> v2 更新要点：对齐 TTS v4 方言重构（native/instruct 双模式）、ASR-SER 解耦、声纹 remote 模式、TTS 模型 v3-flash、Dockerfile 影响分析

| 任务ID | 任务描述 | 优先级 | 状态 | 备注 |
|--------|----------|--------|------|------|
| CFG-001 | **M1 后端 API**：application.yml 新增 `mindsafe.system-config` 节点（voiceprint/wakeWord/tts/guideScripts）+ 新增 SystemConfigController（GET /api/v1/system/config，permitAll，Cache-Control 5min） | P0 | ✅ 已完成 | design/57 M1①②；TDD 7 个测试全绿 |
| CFG-002 | **M1 前端注入**：新建 `config/remote.ts`（initRemoteConfig + getConfigValue）+ main.tsx 启动加载（3s AbortController 超时静默降级）+ Security 白名单 | P0 | ✅ 已完成 | design/57 M1③；12 个测试全绿 |
| CFG-003 | **M1 声纹阈值统一管控**：useVoiceprint.ts 改从 getConfigValue() 读取 local 阈值（0.70），保留 voiceprint.ts 为 fallback；引导脚本改从远程读取 | P0 | ✅ 已完成 | design/57 M1④；影响 VoiceLoginOverlay + voiceprintStore；578 个前端测试全绿 |
| CFG-004 | **M2 TTS 配置外置**：新建 `tts-service/config.yaml`（7 音色 + 8 方言 native/instruct + 10 情感 + native_dialect_voices）+ app.py 加载改造（保留硬编码 fallback） | P1 | ✅ 已完成 | design/57 M2①②；11 个配置测试全绿 |
| CFG-005 | **M2 部署链路**：Dockerfile 增加 `COPY config.yaml` + docker-compose 透传 DASHSCOPE_TTS_MODEL + .env.example 补变量 | P1 | ✅ 已完成 | design/57 M2③④ |
| CFG-006 | **M2 验证**：35 个 Python TTS 测试全绿（test_app 24 + test_config 11） | P1 | ✅ 已完成 | design/57 M2⑤ |
| CFG-007 | **M3 Voice 配置外置**：新建 `voice-service/config.yaml` + `config.py`（独立模块，解耦重量级依赖）+ app.py 加载改造 + Dockerfile COPY | P2 | ✅ 已完成 | design/57 M3；8 个配置测试全绿 |
| CFG-008 | **M4 文档同步**：.env.example 补 DASHSCOPE_TTS_MODEL + DEPLOY-GUIDE 配置变更流程 + design/57 状态更新 | P2 | ✅ 已完成 | design/57 M4 |

---

## 二十六、审计缺口登记（design/13 + design/20 篇审计，2026-07-28）

> 背景：13/20 两篇深度审计（设计完善度/代码达标度/测试覆盖三维度）修复完成后，遗留 3 项功能/测试缺口 + 2 项历史对标审计残留项，按优先级登记如下。优先级判据同 §二十三：**安全/合规 > 对话产品力 > 教师效率 > 学生体验 > 商业化 > 规模化架构**。**2026-08-05 统一定级：未冻结缺口一律 P2 级待办**（TEST-007 / UX-006；DOC-051 同日作废）；ESC-001（frozen/58）、TEST-008（frozen/59）维持冻结跟踪。

| 任务ID | 任务描述 | 优先级 | 来源 | 状态 | 备注 |
|--------|----------|--------|------|------|------|
| ESC-001 | **/escalate 转人工端点 + 学生端 safety_mode 交互**：学生主动求助（"我想找老师"）触发升级端点（写 risk_event + 通知教师 + 会话置 escalated）+ 前端安全模式界面（热线/找老师入口）。当前仅系统侧 RED 自动升级（RISK-201），学生**主动**求助通道缺失 | **P0 安全**（危机兜底通道） | design/20 §10.1 F-03 / §10.2 升级时序（文档标 ⬜ 属实）；**专题设计见 frozen/58** | 🔒 冻结（远期任务规划，2026-07-28 钱敏健定级；设计文档已产出并移入 frozen/，解冻实施前须确认） | 与 M2-006（红色风险教师接管，✅ 已完成）互补：M2-006 为系统自动升级，本项为主动求助入口 |
| TEST-007 | TeacherService 测试覆盖 33.2% → ≥80%（属教师管理域） | P2 | design/05 责任范围 | ✅ 已完成（2026-08-05） | 行覆盖率 100%（0/439 行未覆盖）、分支 87.7%、方法 98%，净新增 3 用例（TeacherStatsPerformanceTest +2 / TeacherAlertWorkflowTest +1，共 19 个新断言） |
| TEST-008 | 量表发放/答题/结果流程（Service+Controller）补齐——评分引擎（AssessmentScoringEngine/PHQ-A·GAD-7/RecurrenceCalculator）已完备 | —（不单独排期） | design/20 §10.1 F-06 | 🔒 冻结（frozen/59 量表施测接线专题） | **与 SCALE-001/002 施测接线同一门禁**（未成年人测评合规），解冻后随 frozen/59 §4.2 实施，不重复立项 |
| DOC-051 | QuickStart 快速启动指南（新人 5 分钟跑通：docker compose up + 前端 dev） | P2 | 全代码库对标审计 P2-6 | ⛔ 作废（2026-08-05 钱敏健指示） | 残留项核实：CONTRIBUTING/QUICKSTART 均不存在 |
| UX-006 | ChatRoom.tsx 拆分（827 行 → useSseStream / useChatSession hooks 抽离） | P2 | 全代码库对标审计 P2-3 | ✅ 已完成（2026-08-05） | ChatRoom 877→714 行，SSE 传输（useSseStream，10 用例）+ 会话编排（useChatSession，14 用例）抽离，行为等价由 ChatRoom.test.tsx 34 用例 + 新 hooks 24 用例保证，student-h5 全量 685 用例绿 + tsc 干净 |

> 说明：
> 1. 上一轮全代码库对标审计（"明天上线 1 所试点校"标准）其余 P1/P2 项已核实**均已修复**：ErrorBoundary 三端 ✅ / db-backup 定时容器 ✅ / api.ts 类型化（0 处 any）✅ / DEPLOY-GUIDE §十监控启动说明 ✅ / application.yml DEBUG→INFO ✅ / nginx client_max_body_size ✅ / parent-h5 vitest ✅ / ConversationServiceImpl 813→826 行（**2026-08-05 深度审计实测修正：初版记 777 行失实**；改善中，随迭代继续拆分，拆分任务见 ARCH-001 doing/61 与 ARCH-010 doing/70）。
> 2. 本次仅登记任务与排序，**未进行任何开发、未做 git 提交**。

---

_本表由 Agent 维护，每次任务变更时更新。_

---

## 二十七、深度审计过度设计待议项（2026-08-04 登记 → 2026-08-05 全量议决）

> 背景：四路独立深度审计（后端/前端/部署/设计一致性）发现多项「为解决问题不断叠加设计导致复杂度失控」的过度设计。按钱敏健决策，**全部登记为待议项，反复讨论后再定处置**（保留/简化/删除），本批次不实施。
> 议决机制：后续每轮讨论会逐项评估——证据（是否有真实数据支撑参数）> 简化收益 > 拆除成本；待议期间维持现状运行。
> **2026-08-05 全量议决完成**：14 项逐项经代码证据核验，裁决为 ✅ 维持（8）/ 🟩 并入 O 专题（3：OD-004→S2、OD-009→S5、OD-014→S4-1）/ ✅ 已解决（2：OD-012/013）/ ✅ 已议决（1：OD-007，真冗余，保留 backup.sh 移除容器，2026-08-05 钱敏健拍板）。裁决详情与证据锚点见 `design/doing/58_O专题_过度设计收敛_方案与SPEC.md` §2.7；并入项随 O 专题 M2/M4/M6 统一实施，OD-007 实施随统一批次登记。
> **2026-08-05 实施完成（DOC-057）**：O 专题 M1-M6 全部落地（S1 合并双 LLM 提炼 / S2 删 ProfileMergeGate 死分支 / S3 前端 API 合并 / S4 配置双源占位符派生 / S5 删 prepare-funasr 版本比较 / OD-007 移除 db-backup 容器）。全量回归 1529 用例全绿（后端 734 / student-h5 685 / teacher-web 34 / parent-h5 23 / scripts 53），文档同步见 doing/58 §11 实施记录。🟩 并入项与 OD-007 状态列同步更新为 ✅ 已实施。

| 任务ID | 待议项 | 现状 | 复杂度症状 | 候选方向 | 状态 | 议决（2026-08-05） |
|--------|--------|------|-----------|----------|------|------|
| OD-001 | **声纹双模式（local WASM + remote）** | 前端 WeSpeaker WASM + 服务端 256 维比对两套并存，阈值前后端各一份（0.70/0.55） | 双链路维护成本×2；阈值双源已自认缺乏统一管控 | 只保留 remote 模式，删前端 WASM 链路，收敛单一权威阈值 | 🟡 待议 | ✅ 维持：否决删 local（BEACON 决策 #22：生物数据不出设备）；阈值随 O 专题 S4-1 收敛 |
| OD-002 | **通知链路五层叠加** | WebSocket 推送 + DB 通知 + WeCom 运维告警 + SMS + SLA 扫描器，职责交错 | SLA 超时只告运维不告教师；各层无对账 | 简化为 risk_events 落库 → outbox → 教师 WS + 超时升级三层 | 🟡 待议 | ✅ 维持：描述失真（实测即三层目标态，notify_status 即对账；SMS 属监护人链路） |
| OD-003 | **发布三门禁运行时常驻** | 红队护栏 14 条 + 人工复核 + eval 分数门禁在 TemplateMatrixRegistry 运行时承载 | 门禁结果不影响线上行为属仪式性代码 | 移到 CI/CD 脚本，运行时只留读取 | 🟡 待议 | ✅ 维持：fail-closed 真实拦截（activateVersion 失败拒绝激活 + audit_logs，唯一入口） |
| OD-004 | **画像合并门控参数缺实证** | ProfileMergeGate EMA/衰减/冲突三策略 + 0.4 冲突阈值 + 60 天半衰期 | 小样本无实证依据，参数拍脑袋 | 先简单加权平均，待真实数据回流再复杂化 | ✅ 已实施（S2，2026-08-05） | 🟩 并入 O 专题 S2：删 applyDecay/isExpired 死分支；参数（0.4/EMA）不改待数据回流 |
| OD-005 | **双层输出安全审查** | OutputContentFilter（规则）+ OutputReviewService（LLM 复审）职责重叠 | 每次对话多一次 LLM 往返 | 合并单一审查管线 | 🟡 待议 | ✅ 维持：规则抓已知词 + LLM 抓语义变体 = 儿童安全纵深防御；可选低优先：按风险等级抽样复审 |
| OD-006 | **手工 eq(tenantId) 与拦截器双写** | 24 处 .last() 裸 SQL 面靠人肉保证 + TenantLineInnerInterceptor | 拦截器未覆盖面无 fail-fast | 只保留拦截器 + fail-fast，手工 eq 收敛为豁免清单 | 🟡 待议 | ✅ 维持：描述失真（.last() 为 LIMIT 分页非裸 SQL，手工条件为拦截器外显式兜底） |
| OD-007 | **备份双轨** | db-backup 容器每 24h pg_dump + backup.sh 宿主机 cron 同写 daily/ | 无互斥无协调，纯重复 | 二选一作为唯一事实源 | ✅ 已实施（2026-08-05） | ✅ 已议决（2026-08-05）：确认真冗余；按建议保留 backup.sh（周/月分层+恢复演练+生产在用），移除 db-backup 容器；实施随 O 专题批次完成（compose 删 db-backup 服务，备份统一走 backup.sh/restore.sh） |
| OD-008 | **上帝类拆分** | TeacherService 748 行 / ConversationServiceImpl 787 行 / AuthController 475 行 | 职责跨域，新增需求加速腐化 | 按域拆分（统计/预警/个案/报表） | 🟡 待议（随迭代渐进，不一次重构） | ✅ 维持渐进：C3 拆分模式已建（TeacherQualityService 抽出），触碰时按域拆 |
| OD-009 | **prepare-funasr.sh manifest 版本管理** | EXPECTED_MODELS 全 pin "master"，python3 JSON 解析替代 jq | 版本比较恒真，复杂绕远 | 简化或删版本比较 | ✅ 已实施（S5，2026-08-05） | 🟩 并入 O 专题 S5：删恒真比较，保留模型存在性/加载校验（fail-fast） |
| OD-010 | **TTS 音色×方言×情感矩阵收敛** | 7 音色 × 8 方言 × 10 情感，但仅 1 emotion_capable + 1 dialect_capable | 矩阵 90% 死配置；persona_gender 恒传 female | 按实际能力裁剪矩阵，死配置清理 | 🟡 待议（与 design/56 对齐） | ✅ 维持：CFG-004 声明式能力文档，运行时按 capable 分支消费无死代码；裁剪失扩展性 |
| OD-011 | **init-school.sh 三重保险** | ON CONFLICT 幂等 + 随机密码 + must_change_password | 一次性运维脚本过度防御 | 简化保留幂等即可 | 🟡 待议 | ✅ 维持：随机密码 + 强制改密 = 弱口令安全基线（同 R-04）；幂等面向学校现场重复执行 |
| OD-012 | **tts 离线 wheels 强绑定 --no-index** | 新增依赖忘记 refresh-wheels.sh 构建即失败 | 单点人工流程无兜底提示 | 构建失败时给出明确提示或 fallback 源 | 🟡 待议 | ✅ 已解决：fix-13 bd9d215 改 requirements-lite.txt 在线安装，wheels 降级为可选方案 |
| OD-013 | **Grafana 面板先建后补** | LLM 面板有真实指标，TTS 面板无指标即上线（空面板） | 指标缺失补丁未闭环 | 删空面板或补指标（P1-7 已另行处理） | 🟡 待议 | ✅ 已解决（ARCH-009 2026-08-06 修正）：llm-performance.json 的 2 个 TTS 面板（mindsafe_tts_* 未埋点）已删，标题改“MindSafe LLM 性能监控”；TTS 可观测性为已知缺口（指标未埋点），埋点立项后恢复面板为纯增量 |
| OD-014 | **声纹阈值 0.55 双源**（局部关联 OD-001） | 后端 0.55 与前端 0.70 两套参数 | 同一决策两处定义 | 随 OD-001 一并收敛 | ✅ 已实施（S4-1，2026-08-05） | 🟩 并入 O 专题 S4-1：占位符派生收敛双源（0.55/0.70 为两模式实测值，真重复仅后端 0.55 与前端 fallback） |

> 说明：
> 1. 本表于 2026-08-05 完成全量议决（见 §二十七 头部），裁决依据为代码级证据核验（doing/58 §2.7），非照抄登记描述；🟩 并入项随 O 专题统一实施（2026-08-05 全部完成，见 DOC-057），⏳ 待决项维持现状运行。
> 2. 与 OD 相关的紧急修复（声纹 1:N 比对/XFF 伪造等 P0）已另立任务在 §二十八修复，不阻塞本表讨论。
> 3. **DOC-057 登记**（2026-08-05）：O 专题过度设计收敛实施完成闭环登记——S1~S5 + OD-007 全部落地，全量回归 1529 用例全绿（后端 734 / student-h5 685 / teacher-web 34 / parent-h5 23 / scripts 53），实施记录与文档落点见 doing/58 §11、design/04 §8、design/06 §3.3、his/46 §4.2 落地记录、his/50 落地记录。

---

## 二十八、深度审计回填任务总表（2026-08-05）

> 背景：三域并行只读深度审计（2026-08-05，后端 6 模块 + 前端三端 + 工程化/部署/合规，对照 design/01-12 与台账逐项核验）产出 P0×2 / P1×21 / P2 精选问题清单。钱敏健 2026-08-05 指示：**全部纳入计划，每个任务先完善设计与 SPEC**。本表为审计回填任务的优先级排序单一视图，方案与 SPEC 见 doing/62~70。
> 优先级判据同 §二十三：**安全/合规 > 对话产品力 > 教师效率 > 学生体验 > 商业化 > 规模化架构**。冻结项（frozen/34~43、58~62、COMP-007/AUTH-033 等）已按审计规则排除不扣分、不在本表。
> 审计修正事实（2026-08-05 即日闭环）：doing/61 五处断言修正（风险词典 ≥5 份/情绪集合 6 处/裸 fetch 5 处/mock 18 个/toolboxApi 非空壳）；L834 行数失实修正（777→826 实测）。
> **决策项（2026-07-28 全部闭环）**：D-1 ORCH-006 → 路径 B 删除 ✅；D-6 MEM-103 → 台账修正 3 维 + L207 注释同步 ✅；D-7 MessageSummary → 路径 C 两级摘要（常规 ≤200 字提炼 / L3+ 原文保真，无 schema 变更）✅；D-8 PII → 昵称置换注入（realName→pseudonym，明文不进上下文）✅。决策详情见 doing/64、doing/67。

| 任务ID | 阶段任务 | 优先级 | 来源审计项 | 方案与SPEC | 依赖 | 状态 |
|--------|----------|--------|-----------|-----------|------|------|
| ARCH-002 | P0 前端缺陷修复（useSilenceNudge 401 刷新 + EmotionSelect localStorage 失败安全） | P0 | P0-1/P0-2 | doing/62 | 无 | ⏳ 待实施 |
| ARCH-007 | 合规与数据安全修复（MessageSummary 两级摘要 + PII 昵称置换 + PIPL 告知链接） | P0 | B-2/B-5/F-5 | doing/67 | 无 | ⏳ 待实施（决策已闭环） |
| ARCH-003 | 风险知识单一规则源（RiskKeywordRegistry + EmotionVocabulary + 一致性断言） | P0 安全 | B-3 | doing/63 | 无 | ⏳ 待实施 |
| ARCH-004 | 假功能与死代码清理（ORCH-006 路径 B + 7 处僵尸 API + 台账对齐） | P1 | B-1/B-6/B-7 + P2-1 + OVD-1/3 | doing/64 | 无 | ⏳ 待实施（决策已闭环） |
| ARCH-005 | 前端 API/SSE 接缝收敛（SSE 单点 + 5 端点收口 + 契约 23+ + 同意 key 统一） | P1 | F-1/F-2/F-3/F-9 | doing/65 | ARCH-002 | ⏳ 待实施 |
| ARCH-006 | ChatRoom 语音编排抽取（useVoiceInputPipeline + 单例/去重收敛 + 测试黑盒化） | P1 | F-4 + P2-6/7/8 + OVD-5 | doing/66 | ARCH-005 | ⏳ 待实施 |
| ARCH-008 | 教师端/家长端加固（authFetch 统一 + 契约防线 + CSP + token 策略） | P1 | F-6/F-7/F-8 + P2-9/10/12/13 + OVD-6 | doing/68 | 无 | ✅ 已完成（2026-08）：authFetch 移植（9 例）+ teacher-web api.ts 6 处改造 / 契约防线清单测试（teacher 38 + parent 9）/ CSP 配置断言 + token 策略文档化 / console.info 归零 + BigScreen 错误态 + 设计 token 对齐（OVD-6 评估） |
| ARCH-009 | 工程化与发布门禁（pytest 入 CI + 覆盖率 80% + 面板台账 + 回滚演练 + 模型自动化 + parent-h5 lint） | P1 | E-1~E-5 + lint | doing/69 | 无（CI/CD 改动须授权） | ✅ 已完成（2026-08-06）：E-1 pytest 入 CI / E-2 teacher-web 覆盖率 89.99% + 阈值 80 / E-3 TTS 面板已删 + OD-013 修正 / E-4 V01~V27 清单 + V34+ 强制 down（DEPLOY-GUIDE §九）/ E-5 模型投放自动化（manifest 校验和 + --verify 门禁 + 修复发布缺模型缺陷）/ parent-h5 补 oxlint（存量 2 warning 待分批清理） |
| ARCH-010 | 后端代码质量清理（JSON 统一 + Redis key 租户前缀 + 异常可观测 + 模板路由 + closeSession 下线） | P2 | P2-2/4/5 + B-4 + OVD-2/4 | doing/70 | ARCH-003（魔法数引用） | ✅ 已完成（2026-08-06，TDD）：D1 JSON 统一（ObjectMapper 单例 + AliyunSmsService 报文改造）/ D2 Redis key 租户前缀双写迁移 / D3 四路失败 counter（补 memory/evaluation）/ D4 chatProactive 走版本路由 + key 单一源（SYS_001 统一，双表 14 key 一致）/ D5 closeSession 旧接口 TTL 到期下线（API_GONE 410）+ 前端 fallback 删除；后端 1577 用例 + student-h5 787 用例全绿 |

> 说明：
> 1. 本表仅登记与排序，**未进行任何开发、未做 git 提交**；实施顺序建议：ARCH-002 → ARCH-007 → ARCH-003/004（可并行）→ ARCH-005 → ARCH-006 → ARCH-008/009（可并行）→ ARCH-010。
> 2. OD-013（§二十七）「TTS 空面板已删」登记与 llm-performance.json 现状矛盾（仍有 2 个 TTS 面板查不存在的 mindsafe_tts_* 指标），已由 ARCH-009 修复（面板删除 + 台账修正，2026-08-06）；TTS 可观测性缺口登记已知项。
> 3. 审计结论（综合 5.9/10 悲观口径）：**骨架健康、安全扎实、注释诚实，但「设计先行、实现脱节」系统性惯性仍在**——虚化面（ORCH-006/MEM-103/7 处僵尸 API）与前端接缝（SSE/契约/401）是发布就绪最大真实风险；P0 两项 + B-1/B-5 + F-1/F-3 为下一迭代强制回填项。
> 4. 审计全文为会话内交付（未落库）；证据链与逐项评分见深度审计报告（2026-08-05，doing/62~70 各文档头部引用对应审计项）。
