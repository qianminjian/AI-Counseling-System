# AI 小学生心理辅导系统 - 任务跟踪表

> 创建：2026-07-23 | 更新：2026-08-09（**后台管理端 AdminConsole 议决登记（DEC-007，2026-08-09）**：doing/83 待议决点按建议方案全部落定——R-1 独立 platform_admin 表 + 独立登录端点（方案 A）/ R-2 P0 只读展示 + SSH 人工（方案①）/ R-7 ops_admin 仅看聚合数据 / R-8 独立 token 前缀 PLATFORM_ / M4 usage_events 采集层先行落地（frozen/38 同步登记）；R-3/R-4 留待 P1/P3；R-6/R-9~R-12 照设计执行；doing/83 正文同步更新（§5.4/§5.6/§6.8/§10/§12 议决状态落定）；**后台管理端 AdminConsole 设计方案登记（DOC-086）**：doing/83 生成——深度调研同类产品（教育 SaaS ClassIn/希沃、通用 SaaS 三后台模型与中台标准模块、心理健康 SaaS 橙星云/心大陆/心灵伙伴、计量计费模式）+ 代码实态盘点（5 个平台 Controller 复用 + 监控体系 + 降级机制 + 缺口 7 项）→ 完整方案：M1 系统配置管理（配置注册表 sys_config + 变更留痕，不引入新配置源）/ M2 系统应用监控（服务拓扑 UP/DEGRADED/DOWN 三态 + 指标看板 + 告警中心 + 部署历史 + service_health_snapshots）/ M3 服务切换降级监控（降级矩阵可视化 + 手动切换运行时覆盖键 + degradation_events，联动 ResilientChatModel/TTS 三级/ASR/SER/VoiceDegradationPolicy）/ M4 租户计量计费（usage_events 计量→rate_plans 计价→subscriptions/billing 计费三层，对齐 design/07 99/159/259 定价，4.3~4.6 设计冻结待 frozen/38 解冻议决）/ M5 租户管理（生命周期 + 配额接线 + 详情钻取）/ M6 平台基础（platform_admin 四角色）/ M7 提示词与内容配置中心（可视化编辑 + 审核发布流 + 门禁可视化 + 安全话术只读）/ M8 业务信号与预警处置监控（跨租户风险全景 + SLA 时效监控 + 逾期升级 + 通知兜底台账 + 业务级告警规则）/ M9 知识库与内容管理 / M10 通知渠道与触达管理 / M11 数据安全与合规中心 / M12 运营洞察；页面风格对齐 doing/75 青屿设计体系（§8.1~8.9：令牌复用/状态色语义映射/布局/组件/图表/暗色/无障碍）；新增表 10 张（含 sla_escalation_log、prompt_versions.status）；API 4 域 12 节；admin-web 前端新建（25 页面，2026-08-09 审计修正：原登记 24 失实，§八 清单实为 25 页）；实施四期 P0 底座→P1 配置与业务核心（M7/M8 用户核心需求）→P2 治理深化→P3 商业化与合规（冻结）；开放问题 R-1~R-12；**Browser Agent 三端 Web 界面自动化遍历测试设计登记（DOC-085）**：doing/82 生成——30 场景案例（S-01~10 学生端 / T-01~08 教师端 / P-01~06 家长端 / L-01~06 三端联动）+ **C-01~07 对话窗口专项**（2026-08-09 补充：语音性能基线/转写准确性/长语音成功度/唤醒灵敏准确/上下文记忆/暖场能力/超时降级等待唤醒，语音类由 SKIP 升级为三层自动化——L0 服务级 API + L1 本地真链路（唤醒词纯前端 whisper 本地推理，test compose 可测）+ L2 降级路径，假麦克风注入 `--use-file-for-fake-audio-capture`）+ **执行补充（2026-08-09）**：目标环境切换 https://yun.gxjugu.com/ 实际部署实例（HTTPS）+ 实际账号（教师 李老师/12345、学生 开心/1234、家庭码 GHH63G 家长首次注册 13814092745）+ 账号/测试数据按需创建与后台脚本预置授权 + **UAT 环境定义（2026-08-09）**：yun.gxjugu.com 为 UAT 测试环境，全部测试数据，系统范围内自由创建/修改/删除、无需恢复，最高权限，边界=仅限本系统范围（§3.2 权限定义）+ 遍历执行纪律 8 条（视觉全控件识别/已操作状态集合防循环/BFS 弹窗闭环/四类异常检测/深度 8 步数 200 超时停止/每步截图实时附件/报告三件套/窗口隔离）+ 每场景 Browser Agent 提示词 + 问题登记规范（reports/browser-test/ISSUES-<端>.md，BUG-<端>-<场景>-<序号> [P0-P3] 状态机 OPEN→FIXED→VERIFIED→REGRESSION）+ 修复-部署-复测闭环（每端 3 轮上限，收敛定义 P0=0 且 P1=0 且 P2/P3 未关闭 ≤3），ticket UI-TEST-001~008 见 §二十九，承接 R-4（Playwright 预留态登记，DOC-082）；**doing 子文档合并归档（DOC-072）**：doing/76 全项目深度审计整改（T1 BEACON 数字修正 / T2 删 counseling-tenant 僵尸目录 / T3 危机热线配置化（`mindsafe.safety.crisis-hotline` 注入，环境变量 `MINDSAFE_CRISIS_HOTLINE` 可覆盖，缺省回退常量）/ T4 Controller 禁 Mapper 分层纪律（12 Controller、58 处 Mapper 下沉领域 Service，纪律钩子 code-engineering §3.5 + check-commit.sh）/ T5 SessionState Lua 原子化；1783 单测全绿）最终态并入 **03** §3.3（分层纪律）+ **09** §5.14.3（热线配置化），文件归档 design/his/76（只读溯源），doing 区清空（仅 .gitkeep），DESIGN-OVERVIEW v6.2、BEACON 演进日志同步登记；**TEST-006 完成登记（DOC-058）**：前后端契约测试三层防线落地——L1 ContractOpenApiIT 端点全量入文档、L2 gen-openapi-snapshot.sh 快照生成入库、L3 前端契约测试；修复 MoodCheckResult 契约漂移；**冻结专题登记：量表施测接线 frozen/59、商用发布合规与备案 frozen/60、外部服务接入与配置 frozen/61，DEC-006 并入 frozen/41**，相关任务行置 🔒 冻结；**2026-08-05 冻结范围扩展**：P2 商业化（BIZ-004/BIZ-006/BILL-002/BILL-003）归 frozen/38、部署升级（OPS-006/PERF-003/DEP-011~016）归 frozen/42、无状态化（PERF-005/STATE-001~005）归 frozen/40、信创（BIZ-005/DBAD-001~006/RISK-004）归 frozen/41、家长端小程序（AUTH-022/PARENT-WX-001~006）归 frozen/43、量表版权门禁（SCALE-003）归 frozen/34、效果量化 A/B（PROF-020/AB-003）归 frozen/39、design/32 商用发布前置归 frozen/60、M4 里程碑冻结跟踪（前置 frozen/60/61）；**远期规划冻结**：COMP-008（WebAuthn，**2026-08-06 起 AUTH-034 同事项一并冻结，远期再考虑**）、UX-003（多语言）、UX-004（无障碍）、PROF-022（初高中适配）纳入冻结规划；**AI-009（心理量表数字化）2026-08-06 纳入冻结专题管理**（施测接线 frozen/59、版权门禁 frozen/34，计分引擎已开发完成待解冻接线）；**作废登记**：DOC-051（QuickStart 指南）、DEC-004（3 版建设方案主版本确认，决策意义已消失）+ RISK-005 关闭；**状态确认**：AI-007/AI-008/PROF-021/UX-005/ORCH-008 核实完成（代码接线验证）、WAKE-012/33 标注完成、审计缺口统一 P2 级；历史：审计问题清单 A/C/D/E 修复闭环 DOC-055、独立 agent 深度审计 DOC-054、设计文档一致性全面核对 DOC-056（编号消歧，原 DOC-053）、设计文档全面更新 DOC-052、2026-07-28 设计文档整体规整 DOC-025、2026-08-01 §二十五 配置统一纳管、2026-07-29 独立审计校正）；**2026-07-28 DATA-005 方案冻结（frozen/62）**：研究数据脱敏导出（IRB 兼容）方案架构审查定稿并冻结归档——4 深化候选（导出管线/伪名化模块/加密接缝/保留豁免）+ 主 Seam（ExportRequest→ExportResult），DATA-005 两处登记置 🔒 冻结，解冻触发=启动学术合作/IRB 合规流程前；**2026-08-05 doing 子文档合并归档（DOC-059）**：doing/58（O 专题过度设计收敛）分主题并入主文档 03 §2.7/04 §6·§8/06 §3.3/10 §2.6·§2.12，doing/59（前后端契约测试 TEST-006）并入 05 §8.6，两文件归档 design/his/，DESIGN-OVERVIEW v5.2 新增 §2.3 已合并子文档对照、冻结区扩展至 12 份（+59/60/61/62）；**2026-08-06 doing 子文档合并归档（DOC-060）**：his/61~70（ARCH-001~010 深度审计治理系列）全部并入主文档 03/02/04/05/08/09/12（各节落点见 DESIGN-OVERVIEW §2.3），十文件归档 design/his/，doing 区清空仅剩 .gitkeep；主文档版本头同步（03/04 v3.2、02/05/08/09/12 v3.1），DESIGN-OVERVIEW v5.3；**2026-08-06 深度审计问题清单登记（DOC-061）**：4 路独立 agent 交叉审计（后端/前端三端/工程化部署/设计一致性）问题清单落 doing/71（AUD-001~071：P0×4 含声纹跨租户比对与 CD 链路三处硬断裂、P1×9、P2×27、P3×19、僵死代码×9、过度设计保留 3 项），声纹免密登录非过度设计（儿童最优入口，后续默认本地模式，项目负责人裁决）、EntitlementFilter 归 frozen/38 不纳入；修复任务后续统一排期（批次 A~E 见 doing/71 §10），DESIGN-OVERVIEW v5.4、BEACON 演进日志同步登记；**2026-08-06 doing 子文档合并归档（DOC-062）**：doing/63 LLM 主备配置通用化并入 06 §3.4（03 §配置示例、04 §3.2/§11 旧命名引用同步更新）、doing/64 ENCRYPTION_KEY 启用开关并入 06 §3.5（07 §5 映射已含）、doing/65 部署配置统一待办清单并入 04 §14（切换执行记录/教训/回滚通道/配置统一要点），三文件归档 design/his/（与 develop 线 63~65 同号异题，文件名可区分），doing 区仅剩 doing/71；DESIGN-OVERVIEW v5.5、BEACON 演进日志同步登记；**2026-08-06 批次 D 完成登记（DOC-061 批次 D）**：AUD-063~071 僵死代码清理全部闭环（RecurrenceCalculator 显式冻结 + 台账补登记 03 §4.2.1；storage.ts 安全封装全量接入——VoiceConsentDialog/VoiceCallConsentDialog/WelcomeGuide/RelaxationExercises/useVoicePersona，生产代码裸 localStorage 清零；LoginPage 重复动态 import 已删；wakeWord.ts 矛盾注释重写；scripts/archive、tts-wheels、tmp/*.patch、e2e 残留全部验证无残留），AUD-060~062 议决落定（双部署通道 CD 为主 deploy.sh 仅限紧急热修→DEPLOY-GUIDE Step 7 改述；备份三层保留不收敛 + cron 已由 AUD-032 接线；声纹 remote 链路保留已加固、local 默认），doing/71 §7/§8 状态更新；DESIGN-OVERVIEW v5.6、BEACON 演进日志同步登记；**2026-08-06 架构深化候选清单登记（DOC-063）**：improve-codebase-architecture 全量架构审查（3 路并行 agent：后端 Java/前端三端/Python 与部署链路）候选清单落 doing/72（DC-001~012：Strong×5——风险分级单一类别源/备份 cron 断裂/配置透传契约/双部署通道/认证传输三端收敛；Worth exploring×6——声纹域下沉/声纹注册 hook/情绪五处收敛/唤醒词模型加载器/会话编排策略下沉/音色引擎适配器；Speculative×1——ChatRoom 神组件规则抽离）+ Top recommendation（DC-001 为深化起点，DC-002 备份断裂属故障级先行排障）+ 台账失实 3 处附注（AUD-061 备份 cron 路径断裂、his/57 P8 仅修非 prod、ARCH-003 类别/情绪收敛未竟）；DESIGN-OVERVIEW v5.7、BEACON 演进日志同步登记；**2026-08-07 取消 CD 决策登记（DOC-063）**：**决策反转（AUD-060）**——已实际部署到环境，决定取消 GitHub CD，只做 CI（质量门禁）；发布与部署统一走真实环境（deploy.sh 唯一通道：rsync 源码 + 服务器本地构建）。背景：CD 镜像 pull 模型在 3Mbps 带宽下 voice 2.26GB 全量 36min+ 且 GHCR 抖动整次失败，14 个坑（触发断裂/大小写/重试/健康探针/并发/顺序 bug）成本远超收益；doing/72 方案与 SPEC 落 design/doing/；cd.yml 已删除；deploy.sh 增强（build 重试 3 次 + CD 残留 IMAGE 变量自动清理 + 头部议决注释反转）；DEPLOY-GUIDE §二/Step 5-7/secrets/文件清单/镜像加速全面改写；doing/71 AUD-002/004/009/035/053 关闭、AUD-003 降 P2、AUD-060 反转；CI 零改动（无镜像构建）；重新引入 CD 演进条件见 doing/72 §2.4（frozen/42 挂账）；**2026-08-07 台账修正登记（DOC-064）**：doing/71 AUD-061（备份 cron 路径断裂）、his/57 P8（仅修非 prod compose）、ARCH-003（类别/情绪收敛未竟）三处附注落台账（追加修正记录不改历史结论）；**2026-08-07 DC-004 去向登记（DOC-065）**：doing/72 候选 DC-004 双部署通道经 YAGNI 议决未纳入实施，已由 doing/71 §7 AUD-060 批次 D 单独处理完毕（2026-08-06 议决：CD 为主、deploy.sh 仅限紧急热修，DEPLOY-GUIDE Step 7 第 3 点改述），doing/72 总览/§5/SPEC 范围三处去向互链；**2026-08-07 doing 子文档合并归档（DOC-066）**：doing/72 架构深化候选清单（DC-001~012：11 项实施落地——DC-001 风险分级单一类别源/DC-002 备份 cron 排障/DC-003 配置透传契约/DC-005 认证传输共享模块/DC-006 声纹域下沉/DC-007 声纹注册收敛/DC-008 情绪收敛/DC-009 唤醒词模型加载器/DC-010 会话编排策略下沉/DC-011 音色引擎适配器/DC-012 ChatRoom 规则抽离 + DC-004 移交 AUD-060）最终态并入主文档 03/04/08/09/10（各节落点见 DESIGN-OVERVIEW §2.3），文件归档 design/his/72，doing 区仅剩 doing/71；主文档版本头同步（03/04 v3.3、08/09 v3.2、10 v3.1），DESIGN-OVERVIEW v5.8、BEACON 演进日志同步登记；**2026-08-07 doing 子文档合并归档（DOC-067）**：doing/71 深度审计问题清单（DOC-061，AUD-001~071）批次 A~E 全部闭环（AUD-001~004 P0×4、AUD-005~013 P1×9、AUD-014~040 P2×27、AUD-041~059 P3×19、AUD-060~062 议决、AUD-063~071 僵死代码），文件归档 design/his/71，doing 区清空（仅 .gitkeep）；合并前同步修正：03/SystemConfigProperties 声纹阈值 0.55 失实→0.70（AUD-001 对齐单一事实源）、VoiceprintVerifyService 装配缺陷（构造器 @Value 注入）、AUD-043 分页改造测试回归（10 测试类 selectPage mock 同步，surefire 858+355 全绿）；DESIGN-OVERVIEW v5.9、BEACON 演进日志同步登记；**2026-08-07 台账修正登记（DOC-068）**：his/72 头部状态字段失实附注——`design/his/72_架构深化候选清单_方案与SPEC.md` 头部仍为「⏳ 待议决（12 个深化候选已定稿，待选定后进入设计细化与实施）」，未随 DOC-066 归档同步更新；事实：DC-001~012 归档前已全部实施完成（11 项实施 + DC-004 移交 AUD-060），最终态落点 03 §2.3.2/§2.7.2、04 §3.2.1/§5.6/§8.1、08 §3.9、09 §5.13、10 §6.14/§7.6.4（与登记一致）；his/72 只读溯源不改文件，溯源以落点章节为准（追加修正记录不改历史结论，照 DOC-064 模式）；DOC-066 归档发起经项目负责人确认，流程合规；**2026-08-07 doing 子文档合并归档（DOC-069）**：doing/72 取消CD收敛部署通道（DOC-063，2026-08-07 决策反转 AUD-060——取消 GitHub CD 只做 CI，deploy.sh 唯一发布通道）最终态并入 04 §4·§5.1~5.7（镜像策略/两段式流程/CI 含 G1-G4 优化/CD 取消/secrets 标注/发布通道/演进条件）+ 03 §9（部署架构 CI/CD 行），文件归档 his/72_取消CD（与候选清单同号异题，文件名可区分），doing 区清空（仅 .gitkeep），主文档版本头同步（03/04 追加 2026-08-07 同步），DEPLOY-GUIDE 实施期已同步，DESIGN-OVERVIEW v6.0、BEACON 演进日志同步登记；**doing 子文档合并归档（DOC-070/071，2026-08-07）**：doing/73 家长端 Taro 迁移 P0 并入 12（适用对象/最新口径/分阶段表/框架表 4 处）+ 08 §4.1 家长端技术形态行 + frozen/43 实态注记 + his/26 修正说明追加，doing/75 家长端老师端风格统一并入 08 §4.1（形态 token 变量名/保留项更新：紫 #722ed1 已收编、BigScreen 复核保留/页面级统一记录），两文件归档 his/73、his/75，doing 区清空（仅 .gitkeep），DESIGN-OVERVIEW v6.1、BEACON 演进日志同步登记；**架构深化候选清单第三轮登记（DOC-074，2026-08-08）**：improve-codebase-architecture（参数：已经审计过的部分排除在外，剩余部分审计）候选清单落 doing/79——排除基线 DC 系列/B·F·D 系列/AUD 系列/DOC-072 T1-T5/ARCH 系列，22 新候选（BA-01~08/FA-01~08/DA-01~07，Strong×4/Worth×9/Speculative×9）全部代码级复核实锤，Top 3 深度 SPEC 定稿（BA-02 导出周报越权 / DA-01+DA-07 镜像漏拷+CI 构建冒烟 / FA-01 风险等级单源），doing 区：78 + 79；**2026-08-08 doing 子文档合并归档（DOC-075）**：doing/78 架构深化候选清单续（DOC-073 拆出 13 未议决候选 B2-B6/F3-F6/D2-D6）14 候选七批全部实施——B2 nudge 配置单源（NudgeProperties）/ B3 RedisChatMemory 收编 / B4 文案模板化（style 9 性别风格 + fallback 兜底）/ B5 TeacherService 分页安全化 / B6 情绪文案收编（EmotionVocabulary 权威词表 + 消费点未知兜底）/ F3 usePolling / F4 emotionMeta 共享模块 / F5 browserSpeak 复用 / F6 麦克风会话模块 / D2 voice 测试补全（dashscope ASR e2e）/ D3 透传契约泛化 / D4 deploy.sh retry 收敛 + load_env_var 单点 / D5 tts health DEGRADED 消费 / D6 restore.sh 参数化 + setup-server GHCR 清理；全量回归后端 1383 + 前端 1221 + Python 110 全绿；code-review 修正 dashscope 子模块导入（High-1）；文件归档 design/his/78（只读溯源），doing 区仅剩 doing/79；DESIGN-OVERVIEW v6.5；**doing 子文档合并归档（DOC-076，2026-08-08）**：doing/79 架构深化候选清单第三轮（DOC-074）23 候选五批全部闭环——批次 A：BA-02 导出越权收敛 + DA-01 tts 镜像修复 + DA-06 prepare-models 前置校验 + DA-07 CI 构建冒烟；批次 B：FA-01 风险等级单源 + FA-08/FA-03/FA-04/FA-02/FA-05；批次 C：BA-01 TTS 假 API 删除 + BA-03 mood-check 落库 + BA-04 摘要策略单点化 + BA-05 groundedness 伪信号删除 + BA-07 浅模块收敛 + BA-08 告警接线；批次 D：FA-06 ChatRoom 面板抽离 + DA-02 voice 就绪消费 + DA-03 metrics 共享；批次 E 议决：DA-04 发布后置冒烟门禁（方案 B）+ DA-05 shell 测试入 CI；BA-06 已并入 frozen/38 不再单独跟踪（EntitlementFilter 权益映射，2026-08-08 项目负责人指令）最终态并入 03 §2.3.3·§4.2.1 / 04 §5.2·§5.6·§9.2~9.3 / 08 §2.5·§2.6·§4.1·§5.5，文件归档 design/his/79（只读溯源），doing 区清空（仅 .gitkeep）；DESIGN-OVERVIEW v6.6、BEACON 演进日志同步登记；归档发起经项目负责人确认（2026-08-08）；**doing 子文档合并归档（DOC-077，2026-08-08）**：doing/80 部署计时与监控模型（部署可观测性）——12 部署步骤毫秒计时（dm_start/dm_end，python3 取时）+ 固定格式汇报（组件/结果/总耗时/步骤明细条形图/信号/建议/日志路径，trap EXIT 统一出口成功失败均输出，失败步骤自动推导）+ 结构化日志 logs/deploy/deploy-<ts>.log（统计段 deploy_result/step_*_ms/signal 供基线解析）+ 监控模型（最近 10 次基线 mean/p90/max → 绝对+相对阈值判定 → OK/WARN/CRITICAL 信号，失败强制 CRITICAL）+ 自动修复完善（L2 rsync 降速自愈 / L3 builder 清缓存自愈 / L4 失败模式知识库 6 特征指引）+ .deploy-state 统计快照；新库 deploy/scripts/deploy-metrics.sh（独立可测），tests/unit/scripts/deploy-metrics-test.sh T1-T12 全绿（CI 七件套自动拾取）；最终态并入 04 §5.8，文件归档 design/his/80（只读溯源），doing 区清空（仅 .gitkeep）；DESIGN-OVERVIEW、BEACON 演进日志同步登记；归档发起经项目负责人确认（2026-08-08）；**doing 子文档合并归档（DOC-078，2026-08-08）**：doing/81 部署日志审计与回归分析（部署过程审计标准化）——每次部署结束 trap EXIT 链第二步自动审计（与退出码无关，失败部署同样审计）：窗口解析最近 10 次 deploy-*.log → 回归规则表 R1-R6（R1 步骤耗时回归复用 dm_judge 且基线排除本次日志防自污染 / R2 成功率 <80% WARN <60% CRITICAL / R3 失败模式聚类 DM_DIAG_FEATURES 计数 ≥3 / R4 最近 5 次连续 3 次上升趋势 / R5 非 OK 信号 ≥50% / R6 有汇报缺统计段 >2 份）→ A1 日志轮转（上限 50 份）+ A2 修复指引；固定格式审计报告全文落盘 logs/deploy/audit-<ts>.md + 终端摘要 5 行；新库 deploy/scripts/deploy-audit.sh（依赖 metrics），tests/unit/scripts/deploy-audit-test.sh T1-T12 全绿（CI 八件套自动拾取）；最终态并入 04 §5.9，文件归档 design/his/81（只读溯源），doing 区清空（仅 .gitkeep）；DESIGN-OVERVIEW v6.8、BEACON 演进日志同步登记；归档发起经项目负责人确认（2026-08-08）；**doing/80 与 doing/81 第四轮实施完成登记（DOC-079，2026-08-08）**：doing/80 深度审计问题清单第四轮批次 A（SPEC-A1~A6 P1 六项：B-01 预警班级范围下推 SQL + 导出独立上限 5000 / D-01 prod 端口 127.0.0.1 / D-02 restore 停服 + 演练指引 / D-03 DEPLOY-GUIDE cron 路径 + 防回潮断言 / F-01 teacher-web 暗色 token 覆盖层 / F-02 测试计数回写 1237）+ 批次 B（SPEC-B1~B7 台账失实群：his/79 修正 + design/03 落点 / 声纹阈值四文档 0.70 / 告警 10 条 / 覆盖率三概念 / verify-config-passthrough 入 CI / design/02 多租户表述 / 死目录清理 + README/STRUCTURE）全部闭环；doing/81 架构深化候选清单第四轮批次 A（BA-09 nudge Redis 单一真值源 + 并发测试 / BA-10 消息读取单点化 + SessionSummaryUpdater 收编 / BA-11 CounselingSessionStore 仓储 + 10 用例 / DA-08 config_loader diff 门禁 / DA-09 模型名单一事实源 / DA-10 Dockerfile ARG 拆除 / DA-11 deploy-lib 纯函数 + 测试）全部闭环；全量回归：后端 BUILD SUCCESS（counseling-api 352 含 3 遗留用例同步 getAlertsForExport）+ 前端 1237（884/228/125）+ Python 118（78+40+1skip）+ shell 十二件套全 PASS；doing/80、doing/81 状态标记已实施待合并归档（与 his/80 部署计时、his/81 部署审计同号异题）；批次 C/D（P2/P3）待排期；R-1~R-7 已议决（DOC-080，2026-08-08）；BEACON 演进日志同步登记；**doing/80 第四轮全面闭环（DOC-081，2026-08-08）**：doing/80 批次 C（P2 十项：TTS 文本上限+限流注册 / 统计日边界 Asia/Shanghai / exportSession HTML 转义 / 声纹 embedding 校验 / noImplicitAny 分端推进 / parent request 收敛 / hex 收编 token / teacher 存储安全封装 / JWT UTF-8 解码 / 静默 catch 补日志）+ 批次 D（P3 十九项：后端 B-10~B-19 / 前端 F-10~F-16 / 工程化 D-13~D-21 含 rollback 健康检查与僵尸卷清理）+ R-1（镜像加速源改阿里云 ACR）/ R-2（test compose 本地构建，AUD-003 确认闭环）/ R-4（Playwright 预留态登记）/ R-7（干预话术 TEMPLATES 下沉 service 层）+ doing/81 FA-16（承接 R-3：删 emotionLabels 垫片 + shared 测试入 CI，teacher-web 233 全绿）全部实施完成；doing/80、doing/81 状态段同步标记；详见 BEACON 设计演进日志 2026-08-08；**doing/80 合并归档（DOC-082，2026-08-08）**：doing/80 深度审计问题清单第四轮（批次 A~D + R-1/R-2/R-4/R-7 + FA-16 全部闭环）最终态并入 **02**（D-12 多租户行级隔离）/ **04**（D-01 端口收口·R-1 ACR 加速·R-2 test compose·D-02 恢复停服·D-10 verify-config 入 CI·D-13 rollback 健康检查·D-19 rsync 模型同步）/ **05**（D-06 覆盖率口径·R-4 Playwright 预留态）/ **06**（B-05 嵌入向量契约·B-07 阈值单值）/ **11**（R-7 话术模板下沉），文件归档 design/his/80_深度审计问题清单（与 his/80 部署计时同号异题，文件名可区分），doing 区仅剩 doing/81（批次 B/C 待议决）；DESIGN-OVERVIEW v6.11、BEACON 演进日志同步登记；**doing/81 批次 B/C 实施完成（DOC-083，2026-08-08）**：批次 B（FA-10 SpeechRecognition 共享装配层 / FA-12 RemoteConfig 删未消费键 / FA-14 VoiceStatusHint 收敛 / FA-15 端点常量表 / BA-14 MoodCheckService 下沉 + DTO / DA-12 $REMOTE_DIR 统一拼接 + 测试 / DA-13 nginx 双事实源消除（议决 a+b：prod 死资产删除 + host/ 版本化位 + sync_host_nginx 上传门禁）/ DA-14 config.yaml 兜底诚实化 + 启动契约 fail-fast）+ 批次 C（BA-15 TenantContextTaskDecorator + TextUtils 公共化 / FA-17 两接线 hook fake-timers 测试 / DA-15 entrypoint 感知 SER_ENABLED）全部闭环；全量回归：后端 Maven 7 模块 BUILD SUCCESS + 前端 928/205/127 + Python 83/40+1skip + shell 十四件套全 PASS；冻结主文档同步（design/04 七处 + design/07 一处 + STRUCTURE.md）；详见 BEACON 设计演进日志 2026-08-08（DOC-083）；**doing 子文档合并归档（DOC-084，2026-08-08）**：doing/81 架构深化候选清单第四轮（DOC-079+081+083，批次 A~C + FA-16 全部闭环）最终态并入主文档 03（§2.3·§3.3·§5.3）/ 04（§3.1·§5.2·§5.6）/ 05（§8.6.1）/ 06（§3.1·§4.0·§5.2·§5.2.1）/ 08（§2.5）/ 09（§3.9）/ 10（§6.2）/ 11（§13.3），各主文档头部追加 2026-08-08 同步行，文件归档 design/his/81_架构深化候选清单第四轮（与 his/81 部署审计同号异题，文件名可区分），doing 区清空（仅 .gitkeep）；DESIGN-OVERVIEW v6.12、BEACON 演进日志同步登记
> 
> 本表用于跟踪项目各阶段任务的进度和责任人。

---

## ⚠️ 审计校正（2026-07-29，项目负责人授权全面修复）

> 独立架构审计（三路并行 agent 交叉印证）结论：**测试全绿 ≠ 功能存在**。91 组件中 43 个为孤儿（生产入口不可达），真实交付面约为台账声称的一半。综合评分 2.8/10，No-Go。本表就此校正。

**状态图例（新增）：**
> - ✅ 已完成 = 已实现**且已接入生产入口**（Controller/Filter/@Scheduled/装配链可达）
> - 🟧 **已编码未接线**（态③）= 代码与单测存在，但从生产入口不可达，线上不生效——**不得计为完成**
> - ❌ 名不副实 = 声称完成但核心机制缺失/失效

**本轮修复批次（fix-01~12）：**
> fix-01 台账重标（本节）→ fix-02 删世界B → fix-03 加密接线(R-01) → fix-04 监护人同意门禁(R-03) → fix-05 SLA兜底(P-05) → fix-06 多租户拦截器(P-02) → fix-07 弱口令fail-fast(R-04) → fix-08 TLS(R-02) → fix-09 种子数据V27清理(R-05) → fix-10 CI修真(Q-01/Q-03) → fix-11 全量回归+文档同步 → fix-12 孤儿组件逐个裁决。
>
> **fix-13 剩余审计问题收官（2026-07-28，16 项全闭环）**：P0-2 限流恒 false 修复+单测 → P0-3/P1-8/P2-16 V32 迁移+密文预算截断+session_summary 加密+_enc 清理 → P0-5 SMS_PROVIDER 默认值统一+logging 醒目标记 → P1-10 监控告警体系（alert-rules 8 规则+Alertmanager 企微应用消息+tts/voice Python metrics 埋点）→ P1-11/12/P2-22 CI 前端覆盖率门禁+Trivy 前端+clean → P1-13/P3-28 JWT iss/aud/jti+token 撤销+DEV_SECRET 隔离 → P1-14 本地 DB 端口 5433→5432 对齐 → P1-15 logback 全局日志脱敏 → P2-18 jacoco 排除 entity 充数+门禁口径 → P2-20 ConversationServiceImpl 占位参数处置+staging 死配置删除 → P2-24/P3-31 tts requirements 上限+Python Dockerfile 加固 → P0-6 ONNX 模型获取脚本+冒烟校验 → P0-4/P1-9 cd.yml rsync 前端+CD 回滚机制 → P2-17/23/26/27/P3-30 文档同步（design/14 保留期 30→180 天、design/12 前端 TS 修正、design/16 审计日志/系统配置 🟩、design/33 保留策略对齐）→ P1-7/P3-29 冻结目录核对（frozen/ 8 份设计文档任务已全部登记于 §二十/§二十一/§二十三 + DESIGN-OVERVIEW v4.0，无需补）。全部经独立验证（后端 mvn 测试 + 前端 tsc/build/vitest + YAML/compose 校验）。

> 说明：§二十三 P0/P1/P2 backlog 中大量 ✅ 实为态③「已编码未接线」孤儿，**逐行裁决归口 fix-12**（与项目负责人逐项确认），本节仅先校正最高信号的失实条目，不在此重复逐行改标。

**fix-12 裁决结果（2026-07-28，项目负责人确认）：**
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
| DOC-024 | 目录结构纠偏：md 拍平至 design/、docx 迁至 doc/ | ✅ 完成 | Agent | 2026-07-23 | 2026-07-23 | 对齐项目负责人原意，STRUCTURE/BEACON/OVERVIEW 同步 |
| DOC-025 | **设计文档整体规整**：58 份旧文档按使用场景合并为 12 份（01 概述/02 数据库/03 架构/04 部署/05 测试/06 配置与外部服务/07 商业化合规/08 概要/09 学生端上卷/10 学生端下卷/11 老师端/12 家长端）；旧文档（50 份）归档 design/his/；frozen/（34/38-43/58）不合并待后续开发时整合；DESIGN-OVERVIEW v5.0 重写（含编号对照表）；BEACON/STRUCTURE/TASK-TRACKER 引用同步 | ✅ 完成 | Agent | 2026-07-28 | 2026-07-28 | 用户指令：合并分类/最终设计方法输出/旧文档归档/冻结文档不动；编号对照见 DESIGN-OVERVIEW §二 |
| DOC-026 | doc/ 根目录全量归档：15 份原始 docx + README.md 移入 doc/his/（git mv，doc/ 仅存 his/），废弃作历史材料；design/his/ 15 份 md 来源标注 doc/→doc/his/；BEACON/DESIGN-OVERVIEW/README/STRUCTURE 引用同步 | ✅ 完成 | Agent | 2026-07-29 | 2026-07-29 | 用户指令：doc 下的文档全部归档到 /his，废弃，作为历史材料；COMP-010 报告保留原路径引用（历史快照） |
| DOC-052 | **设计文档全面更新**：12 份合并文档同步 2026-08-05 三个提交——bd9d215 fix-13 收官（16 项：限流 P0-2/V32 P0-3·P1-8·P2-16/ONNX P0-6/监控 P1-10/CI 门禁 P1-11·12/日志脱敏 P1-15/JWT+撤销 P1-13·P3-28/端口 P1-14/rsync+回滚 P0-4·P1-9）、e173df7 CD 门禁、62bb542 P1 前端 4 项（CSP wasm/FE-2 大屏字段对齐/FE-3 导出当前会话/FE-4 WS 握手 subprotocol 鉴权）。逐份落点：04 部署（CD 回滚/rsync/压测/本地端口/运维）、05 测试（1466 用例/84.3%/门禁口径/SIT）、09 上卷（日志 PII 脱敏/JWT 四要素+撤销/限流防爆破/归口统计）、10 下卷（TTS v3-flash/方言修正/ONNX 脚本）、11 老师端（WS 握手鉴权/QualityPanel/BigScreen/FE 四项）、02 数据库（V32 修正：content_summary 扩 TEXT+僵尸 _enc 列删除）、08 概要（WS 协议/认证安全）、06 配置（ASR 默认 funasr/language_mode 废弃/M5）、01 概述（M5 先行落地注记）、07 合规（日志脱敏双保险）、12 家长端（tokenType=parent_report）；03 架构核对无滞后。数字验证：05 §8.1（1466/84.3%/713）、10 §8.2（43/1/1/8/3=56）、11 §14（12/1/13=26）全部一致 | ✅ 完成 | Agent | 2026-08-05 | 2026-08-05 | 用户指令：结合当前代码的实际实现，根据近期开发的提交情况，全面更新设计文档，将设计文档内容进行细化、补充完善 |
| DOC-053 | **设计文档 2026-08-02 同步**（补充完善）：针对 2026-07-31 至 2026-08-02 期间 60+ 提交中未纳入 DOC-052 的六大变更深挖补充。落点：① BEACON.md：头追加 2026-08-02 同步说明（10 大类变更）；决策表新增 #22-27（CTX-Agent、声纹双模式、TTS 7×8、唤醒深化、模型自托管、WASM/SIMD/SAB）；当前状态补 32 个 DB 迁移/CTX-Agent 上线；设计演进日志 2026-08-02 行；② design/09 §八·五 新增「主 Agent 上下文简报（CTX-Agent，commit c9121a8 落地）」完整章节 193 行（动机/架构/4段式/落地代码/验收/任务归口 CTX-001~007）；§8.14 任务表新增 CTX-001~005 行；③ design/06：版本头追加同步；§3.3 声纹双模式（commit e6f86ab+0320d84）补 wespeaker 模型+两模式详解+SQL 隔离；§5.1 ASR/SER 解耦补并行执行/超时分层/metrics/合规；§6 拆为 §6.1/6.2/6.3（6.2 ONNX 自托管 + WASM/SIMD/SAB 兼容性表）；④ design/10 §7.6 唤醒深化（commit 689d6dd+4152297+8144101）补状态机加固+continuous+防抖+预加载策略+WASM/SIMD/SAB；⑤ design/03 升至 v3.1：§1 总体架构加端侧 AI 层；§4.1 补 CTX-Agent 4段式注入；§4.4 语音能力拆 Python 边车 + 端侧 WASM 自托管；§11.1 加 MINDSAFE_VOICEPRINT_MODE；⑥ design/04 升至 v3.1：§7.3 加 WASM/SIMD/SAB nginx COOP/COEP 配置；新增 §13 voice-service 部署深化（内存选型/模型宿主机缓存/BuildKit pip 加速）。**核对无滞后模块**：design/01/02/05/07/08/11/12 已含 2026-08-02 之前实现，无需重复 | ✅ 完成 | Agent | 2026-08-02 | 2026-08-02 | 用户指令：以 2026-07-31 以来的提交，核对当前代码实现，深度分析后，以当前最新的实现补充完善设计文档 |
| DOC-054 | **独立 agent 深度审计 + 设计文档补充完善（2026-07-31~08-01 约 55 个提交，剔除 docs/merge）**：起独立审计 agent 按五维度核查（架构合理性/代码质量/工程化规范/团队协作友好度/代码逻辑虚化度），评分 **6.5/10**（代码 8/10、文档一致性 5/10，属「实现好于文档」），产出 S1-S11 文档建议 + 问题清单（A1-A4 架构/C1-C4 代码质量/E1-E5 工程规范/O1-O5 过度设计/D1-D4 僵死代码），全部经代码 grep + 文档 grep 交叉验证。12 个核心提交：2678de6（P0+P1 接线收官：PiiDesensitizer 百家姓+地址 SAFE-204/TenantContextHolder runAsSystem/MindSafeTenantLineHandler fail-fast/GuardianConsentFlowIT 6 用例/RISK-204 attention 信号）、6630be3（ASR 双引擎 funasr/dashscope）、9340c6b（prepare-funasr.sh 宿主机缓存+entrypoint fail-fast）、adf7143（TTS DashScope WebSocket 流式）、7df53ef（声纹按钮显式触发+voice_credential 90 天）、c9121a8（ConversationContextAgent 344 行纯字符串组装，四要素注入 System Prompt Layer 3）、9200b83（extractPersonalInfo → SessionState.personalInfo 会话级）、4edb002（3 处 @Transactional+JDK25 兼容）、e6f86ab（声纹双模式 local/remote V27）、3a58654（占位符昵称 9 词+EmotionDiary/RelaxationExercises 重构）、461faf4（前端 552 tests）。逐份落点：09 上卷（RISK-204/SAFE-204 全量 ⬜→🟩 共 7 处/CTX-Agent §3.12.1 新章节（四要素表+双入口接线+余量质疑登记）/脱敏描述更新/P1 例外注记/统计 31=23🟩+3🟫+3⬜+1❌+1 决策）、10 下卷（中期记忆滚动更新生命周期/7.6.2 占位符昵称过滤）、06 配置（yaml 缩进修正/§5.2.1 模型部署与降级：MODEL_CACHE_DIR+manifest 比对+fail-fast 不静默上云）、04 部署（运维 7 语音模型缓存/16 TTS 流式，1-16 编号修复）。**⚠️ P1 待决策**：CTX-Agent `extractPersonalInfo`（9200b83）用原文（非脱敏 safeContent）提取 realName/age/grade/class → 明文注入 System Prompt，违反「脱敏后才进 LLM」承诺——文档已如实登记例外注记（09 L1451），修复方向：代码改喂 safeContent or 文档承认例外；本次仅文档未动代码。已确认无滞后（agent 验证）：声纹双模式/90 天/按钮触发（01 §3.7、08 §3.1、06 §3.3）、ASR 双引擎（06 §5）、TTS 三级降级（06 §4.5、10 §6.8）、7 音色/唤醒词「你好波波」（10 §7.6.1）。审计问题清单（A1-A4/C1-C4/E1-E5/O1-O5/D1-D4）建议后续按优先级处理，其中 D 类僵死代码需清理 | ✅ 完成 | Agent | 2026-08-05 | 2026-08-05 | 用户指令：起独立的 agent 对本项目进行全面深度审计（以生产可用为目标、悲观评分、按五维度核查），并以 2026-07-31~08-01 提交核对当前代码实现后补充完善设计文档；修改未提交，待用户确认 commit |
| DOC-055 | **DOC-054 审计问题清单 A/C/D/E 修复闭环（全 TDD，全量回归 1474 用例全绿）**：**A 架构 4 项**——A1 TenantContextHolder 线程传播边界（异步传播收口，边界测试）、A2 risk 评分双写冗余消除（RiskEvent 结构化评分落库 V33 + 评分器单写）、A3 会话状态膨胀收敛（SessionState 拆薄 + SessionStateTest）、A4 前端状态管理分散收敛（student-h5 useWakeEnabled hook 抽取 + 测试）；**C 代码质量 4 项**——C1 N+1 残留批量收口（HybridRetrievalService/MemoryRelevanceScorer 批量查询 + 测试）、C2 魔法值常量化（8 实体状态常量 + EntityStatusConstantsTest + 全量替换）、C3 Controller 过厚瘦身（TeacherQualityService 抽取 + TeacherQualityControllerTest/ServiceTest 全绿）、C4 异常吞没（损坏声纹留痕 WARN + 契约测试）；**D 僵死代码 4 项逐项裁决**（D1-D3 已清除/接线，D4 裁决后处理，全量回归确认无引用残留）；**E 工程规范 5 项**——E1 gen-changelog.sh（15 测试 + 初始 CHANGELOG）、E2 db-rollback-drill.sh 迁移回滚演练（14 测试 + V28~V33 rollback SQL 补齐）、E3 verify-doc-numbers.sh 文档数字防漂移（6 测试 + 首次校准）、E4 e2e smoke 断言 28→31、E5 check-commit.sh + .gitmessage 提交粒度规范（10 测试）。回归汇总：后端 711 + student-h5 661 + teacher-web 34 + parent-h5 23 + scripts 45 = **1474 用例全绿**（A/C/D 全量 mvn+vitest，E 四套 scripts 测试） | ✅ 完成 | Agent | 2026-08-05 | 2026-08-05 | 用户指令：A、C、D、E 按这个顺序逐个修复，以 TDD 方式开发；修改未提交，待用户确认 commit |
| DOC-056 | **设计文档一致性全面核对（2026-08-02 以来 47 个提交，编号消歧：原 DOC-053 与 2026-08-02 同步任务同号，main 合并后改此号）**：交叉核对 6 个代码批次与 12 份合并文档——612ef65（P1 后端 7 项：markAsRead IDOR/transitionCase 400+持久化/exportAlerts 数据范围/保留期 30→180/aliyun fail-closed/评分器 8 因子/consent 撤回）、8983862（审计二轮：验证码防爆破/N+1 批量/@Transactional/加密落库/ci.yml/从库读数）、88a8a7b（深度审计：SOS/GuardianConsentGate/RRF/GROUP BY/prepare-models）、890c72f（F-1~F-3/G-1/G-2：BoBoPet 8 态/工具箱/转派静音/模板矩阵/编辑工作流）、d30bc00（SEC-001~007/线程池/nginx 安全头/镜像名）、08-02 声纹 TTS 部署（v3-flash/阈值 0.55/service-manager.sh/PWA 禁缓存/V29/CFG）。逐份落点：09 上卷（RISK-203 6 处 ⬜→🟫+落地记录/RISK-202 线程池③+fail-fast/G-1 门禁硬化/SEC-001 新增/统计 31=21+3+5+1+1）、10 下卷（BoBoPet 🟫→🟩 两处：663 落地记录+927 归口表）、11 老师端（13.2 表追加 P1 修复+性能可靠性两行）、08 概要（GuardianConsentGate 组件名/SMS fail-closed/验证码防爆破/SEC-004/SEC-007）、04 部署（运维 13 service-manager+14 PWA 禁缓存/镜像名统一）、06 配置（数据保留期 normal-session-days=180）。已核对无滞后：09 G-1/G-2/KB-103/RRF/SOS/红队、06 CFG-001~008/声纹阈值/TTS、02 保留期 180、12 撤回/SEC-005/006、04 prepare-models/nginx/actuator/CORS。数字验证：09 任务归口 31=21🟩+3🟫+5⬜+1❌+1 决策 一致 | ✅ 完成 | Agent | 2026-08-05 | 2026-08-05 | 用户指令：以 2026-08-02 以来提交的变更，全面核对代码实现和设计文档一致性，补充和完善设计文档；main 合并后编号消歧 DOC-053→DOC-056；修改未提交，待用户确认 commit |

---

## 二、待确认决策（需项目负责人拍板）

| 决策ID | 决策描述 | 选项 | 推荐 | 状态 | 截止日期 |
|--------|----------|------|------|------|----------|
| DEC-001 | MVP 范围最终确认 | 以 design/08 为准 / 调整 | 以 design/08 为准 | ✅ 已确认 | 2026-07-23 |
| DEC-002 | Java 构建工具 | Maven / Gradle | Maven（信创/银行通用） | ✅ 已确认 | 2026-07-23 |
| DEC-003 | Java ORM 框架 | MyBatis-Plus / Spring Data JPA | MyBatis-Plus（政企/信创主流） | ✅ 已确认 | 2026-07-23 |
| DEC-004 | 3 版建设方案主版本 | 时间戳后缀版 / 整合版 | 需人工比对 md5 | ⛔ 作废（2026-08-05 项目负责人指示：三版差异已由 design/*.md 吸收消化，doc/his/ 只读归档，主版本确认无业务意义，不再决策） | 随时 |
| DEC-005 | 首个 LLM Provider | DeepSeek / 通义 / GLM | DeepSeek（性价比高） | ✅ 已确认（deepseek-v4-flash/pro） | 2026-07-23 |
| DEC-006 | 信创数据库选型 | 达梦 / 人大金仓 / 其他 | MVP 用 PG，M3+ 评估 | 🔒 冻结（随 frozen/41 信创专题跟踪，M3+/政企信创触发时解冻议决） | M3 前 |
| DEC-007 | **后台管理端 AdminConsole 议决（doing/83，2026-08-09）**：① R-1 平台账号模型=**独立 platform_admin 表 + 独立登录端点**（方案 A）② R-2 服务操作执行通道=**P0 只读展示 + SSH 人工**（方案①，后端执行通道挂远期）③ R-7 运维角色边界=**ops_admin 仅看聚合数据，学生级明细仅 super_admin/audit** ④ R-8 登录体系=**独立登录端点 + 独立 token 前缀 PLATFORM_** ⑤ M4 计量采集层（usage_events）**先行落地**（属计量非计费，脱离 frozen/38 同步登记）⑥ R-3/R-4 留待 P1/P3 阶段确认；R-6/R-9~R-12 照设计执行 | 按分析建议拍板（用户指令：按建议执行落地，后续逐步推进） | ✅ 已确认 | 2026-08-09 |

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
| RISK-004 | pgvector 在信创环境不可用 | 长远私有化部署 | 🔒 冻结（frozen/41 信创专题，M3+/政企信创触发时解冻评估） | 项目负责人 | 国产向量方案评估随 frozen/41 跟踪（原 🟡 中，2026-08-05） |
| RISK-005 | 3 版建设方案内容有差异 | 需求理解一致性 | ✅ 关闭（2026-08-05：三版内容已全部演进吸收至 design/*.md，主版本不再确认，DEC-004 作废） | 项目负责人 | 原：需人工比对 md5 后确定主版本；随 DEC-004 作废一并关闭 |
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
| DOC-025 | 新增 design/21 认证与试用准入设计（兼容认证+告知同意） | ✅ 完成 | 4 个决策点（D1-D4）待项目负责人拍板 |
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
| PROF-022 | 初高中学段适配缺口评估（话术/量表/UI 全维度，K12 口径定稿配套挂账，09 §10.1/11 §9.3；PROF-021 已被 design/44 占用故跳号） | 🔒 冻结（2026-08-05 纳入冻结规划；初高中版本启动时解冻） | 2026-07-28 项目负责人定稿维持 K12 表述的风险缓释项 |

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
| COMP-010 | doc/ 历史物料违规表述扫描（非诊断表述底线：排查"诊断/治疗/心理咨询"等越界表述，出违规清单交项目负责人，25 §十 第 6 条） | ✅ 完成（2026-07-29，报告见 reports/COMP-010-doc物料违规表述扫描报告.md；真违规 7 类 24 处全在归档层，design/13 传导已修复；处置建议项目负责人 2026-07-29 全部确认：不改归档、封禁外发、doc/README 警示已加） | 近期（商用前） |

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
| OPS-008 | 种子数据生产清理（V27） | ✅ 完成（fix-09） | 审计 R-05：V6 迁移注释明文泄露 minjianq 临时密码、MINDSAFE-TRIAL-001/002/003 硬编码邀请码存活。V27：minjianq password_hash 置无效哈希（限定原泄露哈希，已改密不覆盖）+ 三 TRIAL 码 disabled。裁决（项目负责人 2026-07-28）：DEMO2026 保留（V26 已延期，且 TrialAuthService 按固定试用租户查码，禁租户会断演示链路）；DEV/TRIAL 租户保留 active。V4 测试账号已由 V25 禁用；V8 演示学生因插入条件与 V4 冲突从未生效 |

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
| BIZ-001 | 多租户生产化（独立 Schema + 自动迁移） | 🟧 部分实现（D-12 台账修正，2026-08-08）：共享 tenant_template 行级隔离已落地（TenantLineInnerInterceptor），Schema 级物理隔离为远期升级路线，与 M1-003 对齐 | D |
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

> 以下事项为商业化发布的前置合规/行政流程，责任人为项目负责人，需外部机构配合，不涉及代码开发。
> **2026-08-05 冻结**：全部事项统一冻结跟踪——COMP-001~004 + COMP-007 归 frozen/60（商用发布合规与备案），BIZ-002/BIZ-003 归 frozen/61（外部服务接入与配置）；解冻触发见对应冻结文档。

| 编号 | 事项 | 负责方 | 状态 | 备注 |
|------|------|--------|------|------|
| COMP-001 | 等保二级测评（差距评估 + 整改 + 测评机构出报告） | 项目负责人 + 测评机构 | 🔒 冻结（frozen/60） | 教育系统采购硬门槛，审核周期 1-3 月；差距评估已完成（design/31） |
| COMP-002 | 算法备案（生成式 AI，网信办） | 项目负责人 + 法务 | 🔒 冻结（frozen/60） | 需企业主体 + 算法说明文档 + 安全评估报告 |
| COMP-003 | 教育 App 备案（教育部） | 项目负责人 + 学校 | 🔒 冻结（frozen/60） | 进校前提，需学校配合提供办学资质 |
| COMP-004 | 告知同意条款法务审定 | 法务律师 | 🔒 冻结（frozen/60） | 需出具法律意见书，覆盖 PIPL + 未保法；量表同意项为 frozen/59 施测解冻前置 |
| BIZ-002 | 企微/钉钉 OAuth 配置上线 | 项目负责人 + 学校 IT | 🔒 冻结（frozen/61，同 AUTH-021） | 代码已就绪，需企业主体 + 学校提供 corpId/corpSecret |
| BIZ-003 | 阿里云 SMS 签名/模板申请 | 项目负责人 | 🔒 冻结（frozen/61，同 DEPLOY-010） | 需企业营业执照 + 审核 3-7 天（AUTH-040 生产态前置） |
| AUTH-033 | 年度合规审计报送（未保条例 §37） | 项目负责人 + 法务 | 🔒 冻结（frozen/60，同 COMP-007） | 流程性报送，非代码 |
| DEPLOY-010 | 阿里云 SMS 签名/模板（同 BIZ-003） | 项目负责人 | 🔒 冻结（frozen/61） | 与 BIZ-003 同一事项（合并跟踪） |

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

> 背景：项目负责人提出“深度结合年龄/心理画像/进入心情状态 + 合规底线的个性化提示词设计，要动态变化”。调研现状代码后定位最大缺口：“进入心情状态”仅在 SYS-001 占位不驱动策略，且四维为静态拼接无编排层。基于业界最佳实践（Woebot/Wysa mood check-in、SAMHSA/MBC）与心理专业理论（皮亚杰/容纳之窗/WHO PFA/情绪ABC/MI/SEL/依恋理论）输出设计，设计期、未实施、不含实现代码。

| 任务ID | 任务描述 | 状态 | 备注 |
|--------|----------|------|------|
| DOC-049 | 输出 design/44 个性化提示词动态编排引擎设计（PROF-021） | ✅ 完成 | PromptOrchestrationService/StrategyProfile、情绪→开场与回应策略映射、情绪门控 CBT、情绪状态机会话内漂移切换、合规优先级裁决、EMO-001 模板 |
| PROF-021 | 提示词个性化动态编排引擎实施（情绪驱动策略 + 四维编排 + 情绪状态机） | ✅ 已完成（2026-08-05 状态确认） | design/44；ORCH-001~008 已全部落地接线（2026-07-28，含编排引擎/情绪门控/状态机漂移/优先级裁决/性格微调/EMO-001 A/B 灰度），见 §二十三 ORCH 系列 |
| DOC-050 | 同步 DESIGN-OVERVIEW v3.3 / TASK-TRACKER | ✅ 完成 | 本章节 |

关联任务状态联动：本专题与 design/29（年龄/性格适配）、design/28（冷场）、design/39（A/B 量化）互补；PROF-021 为新增任务，统筹四维个性化编排，与 PROF-010~014（design/29）/PROF-020（design/39）同属提示词工程个性化能力线。

---

## 二十三、设计驱动开发任务总表（按优先级，2026-07-28）

> 背景：项目负责人要求将 design/34~44 设计补充/提升衍生的开发任务，按优先级统一登记为可执行 backlog（**暂不开发**）；近期与远期均登记，远期显式标注。
> 定位：本表是全部“待实施”开发任务的**优先级排序单一视图**。各行拆分自对应设计文档自带的实施里程碑（M1/M2/M3 或 P/W/D/M-x 阶段），各设计的单行主任务（AI-009 / UX-002 / PROF-021 / PERF-005 / BIZ-005 等）仍保留在对应功能分区，本表是其阶段级细化与统一排序。
> 状态统一为 ⏳ 待实施（未开发）。优先级判据：**安全/合规 > 对话产品力 > 教师效率 > 学生体验 > 商业化 > 规模化架构**。近期=M4 上线前后应做；远期=规模化/采购/版权/企业认证触发。

### P0 · 近期（安全兜底 + 对话力核心）

| 任务ID | 阶段任务 | 期段 | 来源设计 | 关联主任务 | 状态 |
|--------|----------|------|----------|-----------|------|
| ORCH-001 | 提示词编排引擎骨架 + StrategyProfile + EntryMoodStrategyResolver（情绪→开场/回应策略）+ EMO-001 模板 + 接入 chat() 组装链 | 近期 | design/44 P0 | PROF-021 | ✅ 已完成（2026-07-28） |
| ORCH-002 | 情绪门控 allowCbt（ACTIVATED/CRISIS 禁认知重构） | 近期 | design/44 P0 | PROF-021 | ✅ 已完成（2026-07-28） |
| SCALE-001 | 量表计分引擎 + PHQ-A/GAD-7（免费量表先行，✅ 项目负责人 2026-07-28 确认）+ 关键条目即时熔断（S0 预警）；**施测已定稿暂缓（2026-07-28）：完成开发不接线，待首校共定施测方案（34 头部），退出商用门禁** | 近期 | design/34 M1 | AI-009 | ✅ 开发完成（2026-07-28，不接线施测）+ 🔒 冻结（施测接线决策见 frozen/59） |

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

> 背景：项目负责人要求对 6 大专题（提示词工程/画像/语音情感/多音色/知识库/长期记忆）全面深化，产出 design/45~50 独立文档。以下为其衍生开发任务，**暂不开发**，仅登记排序。优先级判据同上。
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

### design/51~53 分析文档衍生（2026-07-28 新增，✅ 项目负责人 2026-07-28 全部确认）

> 背景：design/51（横向断链）/52（核心板块心理深化）/53（全板块设计-实现脱节）为分析型文档。其优化方向**绝大多数映射到上方已登记 ID**（ORCH-001~004/PEVAL-001/KB-101/PROF-025/WB-001/MEM-101~102/TMATCH-001/STATE-002~003/BILL-*/SCALE-*/AB-*），不重复登记。下表仅登记 design/52 衍生的**真正新增**项，**2026-07-28 项目负责人全部确认**，状态统一为 ⏳ 待实施（未开发）。总前提：“双世界架构”（世界A线上单 prompt vs 世界B 孤儿 Agent 编排）——见 design/52 〇。**DEC-CBT 重新决策：删除世界 B**（项目负责人 2026-07-29，推翻原「路径1激活」）——世界B 整链死代码、与 SSE 流式冲突、无接线价值，fix-02 删除，线上保留世界A 单 prompt 主线。

| 任务ID | 阶段任务 | 优先级 | 来源设计 | 依赖 | 状态 |
|--------|----------|--------|----------|------|------|
| DEC-CBT | 双世界编排收敛决策——**✅ 重新决策：删除世界 B（项目负责人 2026-07-29）**，推翻原「路径1激活」；世界B（ConversationOrchestrator+7 Agent+CbtStateMachine+ConversationStateManager）整链零调用死代码，无接线价值且与 SSE 流式体验冲突；线上保留世界A（PromptOrchestrationService 单 prompt 主线） | P0 决策 | design/52 〇/一/四；design/13 改写 | 无（最先） | ✅ 已决策删除（2026-07-29），fix-02 执行删除 |
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

> 量表决策（项目负责人 2026-07-28）：**免费量表 PHQ-A/GAD-7 先行**（与 SCALE-001 一致）；开发可先实现，**施测接线上线前须项目负责人再决策**（未成年人测评合规门禁，见 SCALE-001/002 上线门禁备注）。

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

> 背景：项目负责人要求以 `design/` 全部 55 篇为清单，逐篇结合代码现状 + 业界最佳实践 + 目标用户（小学生）+ 落地场景，全面深化设计、补衔接、清「虚假设计未落地」，**本次不写代码，只做设计提升与任务定级**。本表是 triage 单一视图；具体开发任务仍归 §二十三 backlog。
> 责任人约定：**决策/合规/监管红线 → 项目负责人（AI 只提供信息不决策）；法务条款 → 法务；设计深化/文档编辑 → Agent**。
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
| 06 | 数据库结构设计 | 🟡 | 汇总新增表 DDL：画像元数据(provenance/confidence/decay)、量表 jsonb、分层记忆、voice_emotion_trend、prompt_eval_result、计费；向量索引策略 | P2 | Agent | ✅ 已深化（§10，2026-07-28，待实施 DDL 单一登记处已建；HNSW 统一策略；DDL 执行前须项目负责人确认） |
| 07 | SaaS 多学校隔离 | 🔵 | ✅ 已深化（§11，2026-07-28，**架构级偏差：实际为行级隔离非 Schema 级**，路线 A 已定稿；fix-06 已落地行级 TenantLineInnerInterceptor（策略 B）+ ParentAuthService 去硬编码，fail-fast 待收紧；热线配置 SAFE-203 已补） | P2 | Agent | ✅ 已深化 |
| 08 | MVP 最小可行版本 | 🔵 | ✅ 已深化（§十，2026-07-28，M4/M5 边界已定待确认；人脸识别撤销；测评滞后为风险项） | P2 | 项目负责人+Agent | ✅ 已深化 |
| 09 | 商业模式与采购 | 🔵 | ✅ 已深化（§10，2026-07-28，**客户画像错位：中学→小学主打待确认**；基础版筛查能力未落地不得先行承诺） | P2 | 项目负责人 | ✅ 已深化 |
| 10 | 政策与合规风险 | 🟡 | 补未成年人测评合规门禁(量表施测)、生成式AI 备案、语音本地处理表述诚实性；合规硬约束→任务映射 | P1 | 项目负责人+法务 | ✅ 已深化（§10，2026-07-28，三项待项目负责人确认） |
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
| 21 | 认证与试用准入 | 🔵 | 4 决策已定(决策#13)正文回填；与 24 认证优化去重 | P2 | 项目负责人+Agent | ✅ 已深化（§十一，2026-07-28，P0/P1 全落地含 WebSocket/PIN/改密；permitAll 兜底收紧列 P2；与 24 职责边界定稿；Schema 表述随 07 改写） |
| 22 | 告知同意条款 | 🔵 | 补量表测评同意项、语音本地处理表述；待法务审定（合规红线，不替决策） | P1 | 项目负责人+法务 | ✅ 已深化（§六，2026-07-28，量表/语音条款缺口已标注待法务审定） |
| 23 | 学生心理画像设计 | 🟡 | 与 46 闭环对齐：字段加 provenance/confidence/decay；画像→StrategyProfile 决策接线；效果回收自校准 | P1 | Agent | ✅ 已深化（§9，2026-07-28） |
| 24 | 身份认证优化方案 | 🔵 | 与 21 去重；PIN/锁定/监护人同意落地状态标注 | P2 | 项目负责人+Agent | ✅ 已深化（§七，2026-07-28，三阶段大面积落地；家长登录改道手机号+密码定稿；parent_bindings DDL 冻结；密码复杂度/企微配置列 P2） |
| 25 | 产品功能说明 | 🔵 | v2.0 已补，随功能深化同步；面向学校/家长表述核对 | P2 | Agent | ✅ 已深化（§十，2026-07-28，修正 Schema 隔离/密码策略两处超前承诺；对外禁用物理隔离表述；测试账号商用前删除归 32） |
| 26 | 家长端 H5 与小程序 | 🔵 | 与 43 小程序化对齐、修正 Taro 表述；周报/同意管理已实现核对 | P2 | Agent | ✅ 已深化（§9，2026-07-28，家庭码双模式定稿采纳实态；parent_bindings 随 24 冻结；§4/§6 重写列 P3） |
| 27 | 波波品牌与宠物交互 | 🔵 | 与 37 情感化TTS/动效三方同源对齐；表情状态机引用；与 55 全感官去重 | P1 | Agent | ✅ 已深化（§十，2026-07-28，实现领先于文档：五态+实时转写已登记） |
| 28 | 语音唤醒与冷场引导 | 🔵 | 冷场决策(NudgeDecisionModel)已生效核对；与 47/48 语音闭环对齐；唤醒授权措辞（合规） | P1 | 项目负责人+Agent | ✅ 已深化（§十二，2026-07-28，三功能全部落地 🟩，xiaotaiyang 已修复；授权措辞+唤醒词入待确认清单） |
| 29 | 学生画像与年龄适配 | 🔵 | 核心竞争力（近期）：与 46 画像闭环、44 编排 personality 层衔接 | P1 | Agent | ✅ 已深化（§十，2026-07-28，实现领先于文档：五断裂点已全部修复 🟩） |
| 30 | 产品全景优化规划 | 🔵 | 路线图纳入 DEC-CBT 落地 + 本设计深化批次；Sprint 节奏对齐 | P2 | 项目负责人+Agent | ✅ 已深化（§十七，2026-07-28，Sprint A-E 已被超越不再按表执行；上线门禁=量表合规+RAG 空库；BIZ-001 挂起待 07） |
| 31 | 等保二级差距评估 | 🔵 | 合规路径随部署推进（非开发） | P2 | 项目负责人 | ✅ 代码侧差距全部修复（2026-07-29）：fix-03 加密接线 / fix-07 prod profile 激活 / fix-08 TLS+wss+origin 收敛 / fix-10 CI 门禁修真 / SecurityConfig anyRequest().authenticated() 收紧（commit 701a3a0，白名单仅 auth 入口/wecom/guardian-consent confirm/parent/health/ws，教师端点已核实全落 /teacher/**+/alerts/** 匹配器）；剩余为运维/文档项（云安全组审计、异地备份、WAF、管理制度 3 份），项目负责人牵头 |
| 32 | 商用发布前置待办 | 🔵 | 补量表施测合规门禁项；与本追踪表联动 | P1 | 项目负责人 | 🔒 冻结（frozen/60 商用发布合规与备案专题关联文档，2026-08-05） |
| 33 | 系统测试培训手册 | 🔵 | 编排/量表/工具箱上线后补测试点 | P2 | Agent | ✅ 已完成（2026-08-05 标注；DOC-031 手册已产出，测试点随上线批次维护） |
| 34 | 心理量表数字化 | 🟢 | 近期已深化；施测接线**上线门禁**决策已冻结跟踪（frozen/59） | P1 | 项目负责人+Agent | 🟢 维护 |
| 35 | 教师端工作台改版 | 🟢 | 近期已深化；随 05 对齐维护 | P1 | Agent | 🟢 维护 |
| 36 | 心理工具箱与离线缓存 | 🟢 | 近期已深化；随 17/19 对齐维护 | P1 | Agent | 🟢 维护 |
| 37 | 情感化TTS与动效 | 🟢 | 近期已深化；随 27/48/55 对齐维护 | P1 | Agent | 🟢 维护 |
| 38 | 计费配额与运营后台 | 🟢 | 近期已深化；随 09 对齐维护 | P2 | Agent | 🟢 维护 |
| 39 | 画像效果量化与A/B | 🟢 | 近期已深化；维护 | P2 | Agent | 🟢 维护 |
| 40 | 水平扩展与无状态化 | 🟢 | 近期已深化（架构远期）；随 07/42 维护 | P2 | Agent | 🟢 维护 |
| 41 | 信创数据库适配 | 🟢 | 近期已深化（远期）；维护 | P2 | 项目负责人+Agent | 🟢 维护 |
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
| ESC-001 | **/escalate 转人工端点 + 学生端 safety_mode 交互**：学生主动求助（"我想找老师"）触发升级端点（写 risk_event + 通知教师 + 会话置 escalated）+ 前端安全模式界面（热线/找老师入口）。当前仅系统侧 RED 自动升级（RISK-201），学生**主动**求助通道缺失 | **P0 安全**（危机兜底通道） | design/20 §10.1 F-03 / §10.2 升级时序（文档标 ⬜ 属实）；**专题设计见 frozen/58** | 🔒 冻结（远期任务规划，2026-07-28 项目负责人定级；设计文档已产出并移入 frozen/，解冻实施前须确认） | 与 M2-006（红色风险教师接管，✅ 已完成）互补：M2-006 为系统自动升级，本项为主动求助入口 |
| TEST-007 | TeacherService 测试覆盖 33.2% → ≥80%（属教师管理域） | P2 | design/05 责任范围 | ✅ 已完成（2026-08-05） | 行覆盖率 100%（0/439 行未覆盖）、分支 87.7%、方法 98%，净新增 3 用例（TeacherStatsPerformanceTest +2 / TeacherAlertWorkflowTest +1，共 19 个新断言） |
| TEST-008 | 量表发放/答题/结果流程（Service+Controller）补齐——评分引擎（AssessmentScoringEngine/PHQ-A·GAD-7/RecurrenceCalculator）已完备 | —（不单独排期） | design/20 §10.1 F-06 | 🔒 冻结（frozen/59 量表施测接线专题） | **与 SCALE-001/002 施测接线同一门禁**（未成年人测评合规），解冻后随 frozen/59 §4.2 实施，不重复立项 |
| DOC-051 | QuickStart 快速启动指南（新人 5 分钟跑通：docker compose up + 前端 dev） | P2 | 全代码库对标审计 P2-6 | ⛔ 作废（2026-08-05 项目负责人指示） | 残留项核实：CONTRIBUTING/QUICKSTART 均不存在 |
| UX-006 | ChatRoom.tsx 拆分（827 行 → useSseStream / useChatSession hooks 抽离） | P2 | 全代码库对标审计 P2-3 | ✅ 已完成（2026-08-05） | ChatRoom 877→714 行，SSE 传输（useSseStream，10 用例）+ 会话编排（useChatSession，14 用例）抽离，行为等价由 ChatRoom.test.tsx 34 用例 + 新 hooks 24 用例保证，student-h5 全量 685 用例绿 + tsc 干净 |

> 说明：
> 1. 上一轮全代码库对标审计（"明天上线 1 所试点校"标准）其余 P1/P2 项已核实**均已修复**：ErrorBoundary 三端 ✅ / db-backup 定时容器 ✅ / api.ts 类型化（0 处 any）✅ / DEPLOY-GUIDE §十监控启动说明 ✅ / application.yml DEBUG→INFO ✅ / nginx client_max_body_size ✅ / parent-h5 vitest ✅ / ConversationServiceImpl 813→826 行（**2026-08-05 深度审计实测修正：初版记 777 行失实**；改善中，随迭代继续拆分，拆分任务见 ARCH-001 his/61 与 ARCH-010 his/70）。
> 2. 本次仅登记任务与排序，**未进行任何开发、未做 git 提交**。

---

_本表由 Agent 维护，每次任务变更时更新。_

---

## 二十七、深度审计过度设计待议项（2026-08-04 登记 → 2026-08-05 全量议决）

> 背景：四路独立深度审计（后端/前端/部署/设计一致性）发现多项「为解决问题不断叠加设计导致复杂度失控」的过度设计。按项目负责人决策，**全部登记为待议项，反复讨论后再定处置**（保留/简化/删除），本批次不实施。
> 议决机制：后续每轮讨论会逐项评估——证据（是否有真实数据支撑参数）> 简化收益 > 拆除成本；待议期间维持现状运行。
> **2026-08-05 全量议决完成**：14 项逐项经代码证据核验，裁决为 ✅ 维持（8）/ 🟩 并入 O 专题（3：OD-004→S2、OD-009→S5、OD-014→S4-1）/ ✅ 已解决（2：OD-012/013）/ ✅ 已议决（1：OD-007，真冗余，保留 backup.sh 移除容器，2026-08-05 项目负责人拍板）。裁决详情与证据锚点见 `design/doing/58_O专题_过度设计收敛_方案与SPEC.md` §2.7；并入项随 O 专题 M2/M4/M6 统一实施，OD-007 实施随统一批次登记。
> **2026-08-05 实施完成（DOC-057）**：O 专题 M1-M6 全部落地（S1 合并双 LLM 提炼 / S2 删 ProfileMergeGate 死分支 / S3 前端 API 合并 / S4 配置双源占位符派生 / S5 删 prepare-funasr 版本比较 / OD-007 移除 db-backup 容器）。全量回归 1529 用例全绿（后端 734 / student-h5 685 / teacher-web 34 / parent-h5 23 / scripts 53），文档同步见 doing/58 §11 实施记录。🟩 并入项与 OD-007 状态列同步更新为 ✅ 已实施。

| 任务ID | 待议项 | 现状 | 复杂度症状 | 候选方向 | 状态 | 议决（2026-08-05） |
|--------|--------|------|-----------|----------|------|------|
| OD-001 | **声纹双模式（local WASM + remote）** | 前端 WeSpeaker WASM + 服务端 256 维比对两套并存，阈值前后端各一份（0.70/0.55） | 双链路维护成本×2；阈值双源已自认缺乏统一管控 | 只保留 remote 模式，删前端 WASM 链路，收敛单一权威阈值 | 🟡 待议 | ✅ 维持：否决删 local（BEACON 决策 #22：生物数据不出设备）；阈值随 O 专题 S4-1 收敛（已实施：全链 0.70 单值，2026-08-08 台账修正） |
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

> 背景：三域并行只读深度审计（2026-08-05，后端 6 模块 + 前端三端 + 工程化/部署/合规，对照 design/01-12 与台账逐项核验）产出 P0×2 / P1×21 / P2 精选问题清单。项目负责人 2026-08-05 指示：**全部纳入计划，每个任务先完善设计与 SPEC**。本表为审计回填任务的优先级排序单一视图，方案与 SPEC 见 his/62~70。
> 优先级判据同 §二十三：**安全/合规 > 对话产品力 > 教师效率 > 学生体验 > 商业化 > 规模化架构**。冻结项（frozen/34~43、58~62、COMP-007/AUTH-033 等）已按审计规则排除不扣分、不在本表。
> 审计修正事实（2026-08-05 即日闭环）：his/61 五处断言修正（风险词典 ≥5 份/情绪集合 6 处/裸 fetch 5 处/mock 18 个/toolboxApi 非空壳）；L834 行数失实修正（777→826 实测）。
> **决策项（2026-07-28 全部闭环）**：D-1 ORCH-006 → 路径 B 删除 ✅；D-6 MEM-103 → 台账修正 3 维 + L207 注释同步 ✅；D-7 MessageSummary → 路径 C 两级摘要（常规 ≤200 字提炼 / L3+ 原文保真，无 schema 变更）✅；D-8 PII → 昵称置换注入（realName→pseudonym，明文不进上下文）✅。决策详情见 his/64、his/67。

| 任务ID | 阶段任务 | 优先级 | 来源审计项 | 方案与SPEC | 依赖 | 状态 |
|--------|----------|--------|-----------|-----------|------|------|
| ARCH-001 | 对话主链路架构深化（C1 编排拆分：PersonalInfoExtractor + PromptAssemblyService + 主题词单一源） | - | 架构审查第 2 轮（improve-codebase-architecture） | his/61 | 无 | ✅ C1 已实施（2026-07-28，TDD：3 组件 32 例；主类 844→758 行、僵尸依赖清零；counseling-service 792 绿）；C2~C5 由 ARCH-003/004/005/006 承接 |
| ARCH-002 | P0 前端缺陷修复（useSilenceNudge 401 刷新 + EmotionSelect localStorage 失败安全） | P0 | P0-1/P0-2 | his/62 | 无 | ✅ 已实施（2026-07-28，TDD 落地，见 his/62） |
| ARCH-007 | 合规与数据安全修复（MessageSummary 两级摘要 + PII 昵称置换 + PIPL 告知链接） | P0 | B-2/B-5/F-5 | his/67 | 无 | ✅ 已实施（2026-07-28，TDD 落地，见 his/67） |
| ARCH-003 | 风险知识单一规则源（RiskKeywordRegistry + EmotionVocabulary + 一致性断言） | P0 安全 | B-3 | his/63 | 无 | ✅ 已实施（TDD 全绿，见 his/63） |
| ARCH-004 | 假功能与死代码清理（ORCH-006 路径 B + 7 处僵尸 API + 台账对齐） | P1 | B-1/B-6/B-7 + P2-1 + OVD-1/3 | his/64 | 无 | ✅ 实施完成（2026-08-06，见 his/64 §8） |
| ARCH-005 | 前端 API/SSE 接缝收敛（SSE 单点 + 5 端点收口 + 契约 23+ + 同意 key 统一） | P1 | F-1/F-2/F-3/F-9 | his/65 | ARCH-002 | ✅ 实施完成（2026-08-06，见 his/65 §8） |
| ARCH-006 | ChatRoom 语音编排抽取（useVoiceInputPipeline + 单例/去重收敛 + 测试黑盒化） | P1 | F-4 + P2-6/7/8 + OVD-5 | his/66 | ARCH-005 | ✅ 已完成（2026-07-28 07:10，全量回归 788 例，见 his/66） |
| ARCH-008 | 教师端/家长端加固（authFetch 统一 + 契约防线 + CSP + token 策略） | P1 | F-6/F-7/F-8 + P2-9/10/12/13 + OVD-6 | his/68 | 无 | ✅ 已完成（2026-08）：authFetch 移植（9 例）+ teacher-web api.ts 6 处改造 / 契约防线清单测试（teacher 38 + parent 9）/ CSP 配置断言 + token 策略文档化 / console.info 归零 + BigScreen 错误态 + 设计 token 对齐（OVD-6 评估） |
| ARCH-009 | 工程化与发布门禁（pytest 入 CI + 覆盖率 80% + 面板台账 + 回滚演练 + 模型自动化 + parent-h5 lint） | P1 | E-1~E-5 + lint | his/69 | 无（CI/CD 改动须授权） | ✅ 已完成（2026-08-06）：E-1 pytest 入 CI / E-2 teacher-web 覆盖率 89.99% + 阈值 80 / E-3 TTS 面板已删 + OD-013 修正 / E-4 V01~V27 清单 + V34+ 强制 down（DEPLOY-GUIDE §九）/ E-5 模型投放自动化（manifest 校验和 + --verify 门禁 + 修复发布缺模型缺陷）/ parent-h5 补 oxlint（存量 2 warning 待分批清理） |
| ARCH-010 | 后端代码质量清理（JSON 统一 + Redis key 租户前缀 + 异常可观测 + 模板路由 + closeSession 下线） | P2 | P2-2/4/5 + B-4 + OVD-2/4 | his/70 | ARCH-003（魔法数引用） | ✅ 已完成（2026-08-06，TDD）：D1 JSON 统一（ObjectMapper 单例 + AliyunSmsService 报文改造）/ D2 Redis key 租户前缀双写迁移 / D3 四路失败 counter（补 memory/evaluation）/ D4 chatProactive 走版本路由 + key 单一源（SYS_001 统一，双表 14 key 一致）/ D5 closeSession 旧接口 TTL 到期下线（API_GONE 410）+ 前端 fallback 删除；后端 1577 用例 + student-h5 787 用例全绿 |

> 说明：
> 1. 本表任务已全部实施完成（2026-07-28 ~ 2026-08-06，各任务状态列见上；ARCH-001 C1 于 2026-07-28 落地，C2~C5 随对应 ARCH 任务承接）；实施顺序：ARCH-002 → ARCH-007 → ARCH-003/004（可并行）→ ARCH-005 → ARCH-006 → ARCH-008/009（可并行）→ ARCH-010，另 ARCH-001 C1 独立完成。
> 2. OD-013（§二十七）「TTS 空面板已删」登记与 llm-performance.json 现状矛盾（仍有 2 个 TTS 面板查不存在的 mindsafe_tts_* 指标），已由 ARCH-009 修复（面板删除 + 台账修正，2026-08-06）；TTS 可观测性缺口登记已知项。
> 3. 审计结论（综合 5.9/10 悲观口径）：**骨架健康、安全扎实、注释诚实，但「设计先行、实现脱节」系统性惯性仍在**——虚化面（ORCH-006/MEM-103/7 处僵尸 API）与前端接缝（SSE/契约/401）是发布就绪最大真实风险；P0 两项 + B-1/B-5 + F-1/F-3 为下一迭代强制回填项。
> 4. 审计全文为会话内交付（未落库）；证据链与逐项评分见深度审计报告（2026-08-05，his/62~70 各文档头部引用对应审计项）。
> 5. **DOC-060 登记**（2026-08-06）：doing/61~70 ARCH-001~010 方案与 SPEC 全部并入主文档——03 §4.1.1（ARCH-001）/§2.7.1（ARCH-002/005）/§2.3.1（ARCH-006）/§2.5.1（ARCH-008）/§3.1·§4.2.1·§5.2.1（ARCH-004/010）、09 §5.5.1·§5.9·§3.12（ARCH-003/010）、05 §1.2·§8.6.1（ARCH-008/009/005）、08 §2.5·§4.1（ARCH-007/008）、02 §7.2.1（ARCH-007）、12 §八（ARCH-007）、04 §5.5（ARCH-009）；十文件归档 design/his/（只读溯源），doing 区清空；DESIGN-OVERVIEW v5.3 §2.3 对照表 +10 行；本表「方案与SPEC」列引用同步改为 his/6X。
> 6. **DOC-061 登记**（2026-08-06）：深度审计问题清单落 doing/71（4 路独立 agent 交叉审计：后端/前端三端/工程化部署/设计一致性），AUD-001~071 编号定稿——P0×4（AUD-001 声纹 verify 跨租户全库比对 + 阈值 0.55 + 90 天凭证；AUD-002 setup-server 目录布局与 cd.yml 断裂；AUD-003 test/prod 容器名冲突无切换；AUD-004 CD 健康检查 IP+HTTPS 必败）、P1×9（AUD-005 guardian-consent 配置失实、AUD-006 TTS 音色硬编码、AUD-007 三端 token 策略不一致、AUD-008 40MB 模型无条件预下载、AUD-009 rollback 竞态、AUD-010 CONSENT 变量漂移、AUD-011/012 08 错误码与响应契约失实、AUD-013 analytics 反向幽灵）、P2×27（AUD-014~040）、P3×19（AUD-041~059）、过度设计保留 AUD-060~062、僵死代码 AUD-063~071；**用户裁决**：声纹免密登录系统非过度设计（对小朋友最合适入口，后续默认本地声纹模式，P0 安全缺陷不受影响仍必修）、EntitlementFilter 已冻结（frozen/38）不纳入；修复任务后续统一排期（批次 A~E 见 doing/71 §10），本表任务行待排期后逐项登记。
> 7. **DOC-062 登记**（2026-08-06）：doing/63、64、65（main 线独立编号，与 develop 线 63~65 同号异题）全部实施完成，最终态并入主文档——**06 §3.4**（doing/63 LLM 主备：LLM_PRIMARY_*/LLM_BACKUP_* 任意双供应商 + LlmExtraBodyConfig 按 base-url 注入 + 旧名回退链 LLM-GEN-012；06 §2/§5.2/§7.2/§7.3/§8.1 与 03 §配置示例、04 §3.2/§11 旧命名引用同步更新）、**06 §3.5**（doing/64 加密开关：ENCRYPTION_ENABLED 默认 false 终态决策 + 开关语义 + 商业化解锁清单）、**04 §14**（doing/65 部署配置统一：新老套切换执行记录/教训/回滚通道 + DEP-001~008 配置统一结论 + 部署问题固化 5 项）；三文件归档 `design/his/`（只读溯源），doing 区仅剩 doing/71（DOC-061 审计清单）；DESIGN-OVERVIEW v5.5 §2.3 对照表 +3 行；主文档版本头同步（03/04/06）。
> 8. **DOC-063 登记**（2026-08-07）：**取消 CD 收敛部署通道（决策反转 AUD-060）**——已实际部署到环境，决定取消 GitHub CD 只做 CI，发布与部署统一走真实环境（deploy.sh 唯一通道）。深度分析与方案落 `design/doing/72_取消CD收敛部署通道_方案与SPEC.md`：14 个坑处置归属表（11 项随 CD 取消关闭、3 项通用教训固化 deploy.sh：build 重试/健康探针/nginx）；cd.yml 已删除（Git 历史可追溯）；deploy.sh 增强（compose build 重试 3 次 + .env CD 残留 IMAGE 变量自动清理）；DEPLOY-GUIDE 全面改写（§二架构/Step 5-7/本地部署环境配置/文件清单/镜像加速方案 B 停用）；doing/71 AUD-002/004/009/035/053 🗑关闭、AUD-003 保留降 P2、AUD-060 ⚠️反转；CI 零改动（ci.yml 无镜像构建推送）；服务器清理项（.cd-state-* 删除、.env IMAGE 残留清理、ghcr 镜像清理）见 doing/72 §3.3；重新引入 CD 演进条件（多环境/带宽≥10Mbps/合规留痕）见 doing/72 §2.4，frozen/42 挂账。
> 9. **DOC-064~066 登记**（2026-08-07）：**DOC-064** 台账修正 3 处——doing/71 AUD-061（备份 cron 路径断裂补正）、his/57 P8（仅修非 prod compose 补正）、ARCH-003（类别/情绪收敛未竟补正），修正机制：追加修正记录不改历史结论；**DOC-065** DC-004 双部署通道去向——未纳入 doing/72 实施（YAGNI），由 doing/71 §7 AUD-060 批次 D 议决处理完毕（2026-08-06：CD 为主、deploy.sh 仅限紧急热修，DEPLOY-GUIDE Step 7 第 3 点改述）；**DOC-066** doing/72 合并归档——最终态并入主文档：**03 §2.3.2**（DC-012 ChatRoom 六态纯函数 computeBoboState + useVoiceCallMode 收紧）·**§2.7.2**（DC-005 认证传输五能力 authFetch/refresh/tokenStorage/apiError/sessionExpired）、**04 §3.2.1**（DC-003 配置透传契约）·**§5.6**（DC-004 双部署通道职责）·**§8.1**（DC-002 备份 cron 排障）、**08 §3.9**（DC-006/007 声纹域下沉与注册收敛）、**09 §5.13**（DC-001 风险单一类别源/DC-008 情绪收敛/DC-010 策略下沉）、**10 §6.14**（DC-011 音色引擎适配器）·**§7.6.4**（DC-009 唤醒词模型加载器）；文件归档 `design/his/72`（只读溯源），doing 区仅剩 doing/71（DOC-061 审计清单，批次 E 待排期）；主文档版本头同步（03/04 v3.3、08/09 v3.2、10 v3.1）；DESIGN-OVERVIEW v5.8 §2.3 对照表 +1 行；本表「方案与SPEC」列引用同步改为 his/72。
> 10. **DOC-067 登记**（2026-08-07）：doing/71 深度审计问题清单合并归档——AUD-001~071 批次 A~E 全部闭环：批次 A（AUD-001~004 P0×4：声纹 verify 租户维度+双层过滤+阈值 0.70 对齐、setup-server/cd.yml 目录断裂、test/prod 容器名、CD 健康检查，DEPLOY-GUIDE 改述）、批次 B（AUD-005~013 P1×9，AUD-013 analytics 三端点回填 08 §5.5）、批次 C（AUD-014~040 P2×27）、批次 D（AUD-063~071 僵死代码清理 + AUD-060~062 议决落定，03 §4.2.1 冻结登记）、批次 E（AUD-041~059 P3×19：RateLimiter 指纹限流、CI/CD 门禁、分页安全化 AUD-043、前端隐私/Dashboard/大屏等）；合并前修正：03 与 SystemConfigProperties 声纹阈值 0.55 失实→0.70（AUD-001 对齐）、VoiceprintVerifyService 构造器补 @Value 装配（原启动必失败）、AUD-043 分页改造 10 个测试类 mock 回归（selectPage 同步）；文件归档 design/his/71（只读溯源），doing 区清空（仅 .gitkeep）；DESIGN-OVERVIEW v5.9 §2.3 对照表 +1 行；本表「方案与SPEC」列引用同步改为 his/71。
> 11. **DOC-068 登记**（2026-08-07）：his/72 头部状态字段失实附注——归档文件头部仍为「⏳ 待议决（12 个深化候选已定稿，待选定后进入设计细化与实施）」，未随 DOC-066 更新；实际 DC-001~012 全部实施完成（11 项实施 + DC-004 移交 AUD-060），落点见 03 §2.3.2/§2.7.2、04 §3.2.1/§5.6/§8.1、08 §3.9、09 §5.13、10 §6.14/§7.6.4；his/72 只读溯源不改文件，溯源以落点章节为准（追加修正记录不改历史结论，照 DOC-064 模式）；DOC-066 归档发起经项目负责人确认（2026-08-07）。
> 12. **DOC-069 登记**（2026-08-07）：doing/72 取消CD收敛部署通道合并归档——决策反转 AUD-060（2026-08-06「CD 为主」→ 2026-08-07 实战反转：3Mbps 带宽 CD 镜像 pull 模型成本远大于收益）；最终态落点 04 §4（镜像策略 GHCR 停用）·§5.1（两段式流程）·§5.2（CI 最终态含 G1-G4）·§5.3（CD 取消与回滚语义）·§5.4（secrets CD 停用标注）·§5.6（发布通道最终态）·§5.7（重新引入 CD 演进条件，frozen/42 挂账）+ 03 §9（CI/CD 行）；文件归档 design/his/72_取消CD（与 his/72 候选清单同号异题，文件名可区分）；DEPLOY-GUIDE §二/Step 5-7/secrets 表实施期已同步；归档发起经项目负责人确认（2026-08-07）。
> 13. **DOC-070 登记**（2026-08-07）：doing/73 家长端 Taro 迁移（P0）合并归档——parent-h5 自 Vite SPA 原地迁移 Taro 4 + React 18.3.1（H5 产物行为/URL/部署等价，weapp 通道配置就绪未启用）；最终态落点 **12**（适用对象行/最新方案口径行/分阶段表 P0/P1 已落地/技术选型框架表 4 处）+ **08** §4.1（家长端技术形态行）+ **frozen/43** 实态注记（P2 小程序设计 PARENT-WX-001~006 继续有效，企业主体认证仍为硬门槛）+ **his/26** 修正说明追加（历史快照保留原状，最新口径以 12 与 his/73 为准）；文件归档 design/his/73（只读溯源），doing 区清空（仅 .gitkeep）；DESIGN-OVERVIEW v6.1 §2.3 对照表 +1 行；归档发起经项目负责人确认（2026-08-07）。
> 14. **DOC-071 登记**（2026-08-07）：doing/75 家长端老师端风格统一（青屿方案 A）合并归档——Sider 深青 #163B38/激活项青绿软填充 rgba(43,168,160,0.28)/卡片 16px+青屿阴影/三端 --ms 语义 token 统一，20 页面级对象全量优化（§7.7：家长端 4 页 + 老师端 4 页级对象 + 12 面板）；最终态落点 **08** §4.1（落地载体追加形态 token 变量名 --ms-radius-card/--ms-shadow-card 等 + 保留项更新：紫 #722ed1 已收编移除、BigScreen 复核确认保留、情绪分类色/中性灰阶保留）；文件归档 design/his/75（只读溯源）；DESIGN-OVERVIEW v6.1 §2.3 对照表 +1 行；归档发起经项目负责人确认（2026-08-07）。
> 15. **DOC-072 登记**（2026-08-08）：doing/76 全项目深度审计整改（T1 BEACON 数 字修正 / T2 删 counseling-tenant 僵尸目录 / T3 危机热线配置化 / T4 Controller 禁 Mapper 分层纪律 / T5 SessionState Lua 原子化）合并归档——最终态落点 **03** §3.3 （分层纪律：Controller 禁注入/import MyBatis Mapper，数据访问下沉领域 Service；12 Controller、58 处 Mapper 清理；纪律钩子 code-engineering §3.5 + scripts/check-commit.sh 提交拦截）+ **09** §5.14.3（危机热线配置化：`mindsafe.safety.crisis-hotline` 注入 + 环境变量 `MINDSAFE_CRISIS_HOTLINE` 覆盖 + 缺省回退常量 400-161-9995）；1783 单测全绿，10 个原子提交已推送 origin/main（bb8d16c..8805ec7）；文件归 档 design/his/76（只读溯源），doing 区清空（仅 .gitkeep）；DESIGN-OVERVIEW v6.2 §2.3 对照表 +1 行；归档发起经项目负责人确认（2026-08-08）。
> 16. **DOC-073 登记**（2026-08-08）：doing/77 架构深化候选清单（improve-codebase-architecture 全量审查，B1~B6/F1~F6/D1~D6 共 16 候选）三线实施后合并归档——最终态落点 **09** §3.11·§5.14.3（B1 危机热线五源收敛：`CrisisHotlineProvider` 单一权威源 + 五路径（Layer1/RED 分年级/召回/时长引导/L5 模板）统一 `render()` 渲染，12355 冲突消除，全仓库字面量仅剩兜底常量一处）+ **06** §4.0（D1 配置深合并单源化：tts/voice 各放 config_loader.py，加载优先级 env > yaml > 代码最小兜底，矩阵类数据 config.yaml 权威单源，深合并语义显式测试）+ **03** §2.7.3（F1+F2 三端请求语义收敛：student api() 切 authFetch 接缝、parent success 契约统一、publicFetch 五处裸 fetch 收敛）；未议决 13 候选（B2-B6/F3-F6/D2-D6）拆出 doing/78 轻量决策入口（详细分析溯源 his/77）；全量回归：前端三端 vitest 1189 项 + Python pytest 87 项 + 后端 783 项 0 失败（counseling-app 12 环境依赖 IT 除外）；4 个原子提交（9cfaed0/d3d2e5e/3ff4d66/2d21aeb）；文件归档 design/his/77（只读溯源），doing 区仅剩 doing/78；DESIGN-OVERVIEW v6.3 §2.3 对照表 +1 行；归档发起经项目负责人确认（2026-08-08）。
> 17. **DOC-074 登记**（2026-08-08）：doing/79 架构深化候选清单第三轮（improve-codebase-architecture，参数「已经审计过的部分排除在外，剩余部分审计」）候选清单落 doing/79（候选清单 + Top 3 SPEC 一体）——排除基线：DC-001~012（his/72）、B1~B6/F1~F6/D1~D6（his/77，13 未议决在 doing/78）、AUD-001~071（his/71）、DOC-072 T1-T5（his/76）、ARCH-001~010（his/61~70）；22 新候选（BA-01~08 后端 / FA-01~08 前端 / DA-01~07 部署），Strong×4（BA-02 教师导出/周报 classScope=null 全校越权——与 P1 resolveClassScope 修复意图冲突；DA-01 tts Dockerfile L20 COPY 漏 tts_engines.py/tts_policy.py 下次发布必 crash-loop；FA-01 风险等级常量 6 处漂移含 0 级 undefined/1-2 级同色；FA-02 THEME_STYLES 双副本）+ Worth×9 + Speculative×9；全部 Strong 候选代码级复核实锤（0 处 agent 报告失实）；Top 3 深度 SPEC 定稿（doing/79 §27 BA-02 班级范围强制收敛 / §28 DA-01+DA-07 镜像修复+CI 构建冒烟 / §29 FA-01 riskLevel.ts 单源），其余 19 候选实施要点表 + 建议批次（§30）；HTML 可视化报告 tmp/architecture-review-20260808-090132.html（git 忽略）；doing 区：78 + 79；DESIGN-OVERVIEW v6.4、BEACON 同步登记。
> 18. **DOC-075 登记**（2026-08-08）：doing/78 架构深化候选清单续（DOC-073 拆出 13 未议决候选 B2-B6/F3-F6/D2-D6）14 候选七批全部实施——B2 nudge 配置单源（NudgeProperties @ConfigurationProperties）/ B3 RedisChatMemory 收编 / B4 文案模板化（prompts/style 9 性别风格 + prompts/fallback 兜底 + 模板路由）/ B5 TeacherService  分页安全化（范围查询收敛 SessionAccessService）/ B6 情绪文案收编（EmotionVocabulary 权威词表 + 消费点未知兜底「未知」）/ F3 usePolling / F4 emotionMeta 共享模块 / F5 browserSpeak 复用 / F6 麦克风会话模块 / D2 voice 测试补全（dashscope ASR e2e）/ D3 透传契约泛化（verify-config-passthrough）/ D4 deploy.sh retry 执行器收 敛 + load_env_var 单点 / D5 tts health DEGRADED 语义消费（降级≠宕机）/ D6 restore.sh TENANT_SCHEMA 参数化 + setup-server GHCR 清理；全量回归：后端 1383 项（ai 508 + service 875）+ 前端 1221 项（student 873 + teacher 223 + parent 125）+ Python 110 项（voice 33+1skip + tts 77）0 失败；code-review 修正：dashscope 顶层不暴露 Recognition（High-1 必 AttributeError）→ 子模块显式导入 + api_key 注入（23 项测试含回归）；已知取舍 §5.1（EmotionDiary 双平静 = DC-008 词表粒度，测试显式断言）；文件归档 design/his/78（只读溯源），doing 区仅剩 doing/79；DESIGN-OVERVIEW v6.5 §2.3 对照表 +1 行；归档发起经项目负责人确认（2026-08-08）。
> 19. **DOC-076 登记**（2026-08-08）：doing/79 架构深化候选清单第三轮（DOC-074）23 候选五批全部闭环合并归档——批次 A：BA-02 教师导出/周报班级范围强制收敛（exportStudents/weeklyReport 接入 resolveClassScope，null=全校路径消除，TeacherClassScopeTest 三用例扩展）+ DA-01 tts Dockerfile COPY 补拷引擎模块（voice 核对无同类遗漏）+ DA-06 deploy.sh 部署前置 prepare-models --verify 门禁 + DA-07 CI docker-build-smoke job（只 build 不推送）；批次 B：FA-01 riskLevel.ts 单源（6 处替换 + 0 级补全/1-2 级拆两色/标签统一）+ FA-08 OverviewPanel 单一 load + mountedRef + FA-03 useECharts hook + ProfileRadarChart 按需注册 + FA-04 SessionMessagesDrawer 共享（QualityPanel 补 cancelled 守卫）+ FA-02 immersiveStyles.ts 单源 + FA-05 muted localStorage 持久化；批次 C：BA-01 TTS 假 API 删除（2 端点 + 3 类，openapi 同步）+ BA-03 mood-check 落库 RelaxationSession + BadgeService 徽章统一入口 + BA-04 MessageSummary 策略上移 service 单入口（ObjectMapper 注入，删实体工厂与零消费字段）+ BA-05 groundedness 伪信号删除（identifyContentGaps 保留——真实消费 EditorialWorkflowService，§6.1 分析过时）+ BA-07 ToolboxService 合并 AuthUserService + BA-08 outbox markDead 接入 AlertService + WeComAlertService 构造器注入 + DataAnalytics 三端点 javadoc 冻结声明 + 双 trend 合并；批次 D：FA-06 useChatRoomPanels + ChatRoomHeader（595→529 行）+ DA-02 voice /health 三态 + alert-rules 2 条 + services-overview dashboard + service-manager 消费 + DA-03 metrics_common.py 复制共享（tts/voice 逐字节一致 + CI diff 门禁）；批次 E 议决：DA-04 发布后置冒烟门禁（方案 B——deploy 现场执行 smoke-test.sh，失败门禁中止 + SKIP_SMOKE=1 逃生口；教师/管理员链路凭据不进 ssh 命令行由 *IT.java 覆盖）+ DA-05 CI shell-tools-test job（六件套 + backup-common）+ 删 tests/integration 空壳目录；BA-06 已并入 frozen/38 不再单独跟踪（EntitlementFilter 权益映射漂移：FEAT 映射永不命中 + BASIC/PREMIUM 恒不可达 + 每请求 selectById；解冻时二选一议决，2026-08-08 项目负责人指令）；最终态落点 **03** §2.3.3·§4.2.1（FA-06/BA-01~08）+ **04** §5.2·§5.6·§9.2~9.3（DA-01~07）+ **08** §2.5·§2.6·§4.1·§5.5（BA-02~04/FA-01~02）；全量回归后端 1383 + 前端 1221 + Python 110 全绿 + 六件套 7/7 PASS；4 原子提交 + 1 提交（6157213）；文件归档 design/his/79（只读溯源），doing 区清空（仅 .gitkeep）；DESIGN-OVERVIEW v6.6 §2.3 对照表 +1 行；归档发起经项目负责人确认（2026-08-08）。
> 20. **DOC-079 登记**（2026-08-08）：doing/80 深度审计问题清单第四轮 + doing/81 架构深化候选清单第四轮实施完成（批次 A/B 闭环，待合并归档）——doing/80 批次 A（SPEC-A1~A6 P1 六项：B-01 预警班级范围下推 SQL `getAlertsForExport`（导出独立上限 5000 不再被钳 100）+ 班级学生集合下推 / D-01 prod 端口收口 127.0.0.1:18082:8080 对齐 04 §3.1 铁律 / D-02 restore.sh 恢复前停 backend + 演练指引 / D-03 DEPLOY-GUIDE cron 路径改实际 deploy/backup.sh + backup-common 防回潮断言 / F-01 teacher-web `[data-theme='dark']` token 覆盖层 / F-02 前端测试计数 CI 同级实跑回写 1237）批次 B（SPEC-B1~B7：his/79 BA-01 归档修正（VoiceDegradationPolicy 保留在用）+ design/03 落点 / 声纹阈值四文档统一 0.70 / 告警规则三处统一 10 条 / 覆盖率目标/达成/门禁三概念分行 / verify-config-passthrough-test.sh 入 CI / design/02 多租户行级隔离表述 + BEACON#6 / scripts/archive 与 parent-h5/src/api 死目录清理 + README/STRUCTURE 对齐）；doing/81 批次 A（BA-09 nudge Redis 单一真值源：getNudgeCount/getLastNudgeAt/tryNudge Lua 原子 + NudgeConcurrencyIT + SessionState.canNudge/markNudged 删除 / BA-10 消息读取单点化 readSessionTranscript + SessionSummaryUpdater 收编 / BA-11 CounselingSessionStore 仓储（10 用例）+ 编排器 3 Mapper 降为 UserMapper 单点 + 保密告知收编 MessageSummaryService / DA-08 config_loader diff 门禁 / DA-09 verify-model-names.sh 入 CI / DA-10 voice Dockerfile ARG 拆除 + 默认值对齐 / DA-11 deploy-lib.sh 纯函数 + 测试入 CI）；全量回归：后端 BUILD SUCCESS（含 counseling-api 3 遗留用例同步 getAlertsForExport）+ 前端 1237（884/228/125）+ Python 118 + shell 十二件套全 PASS；doing/80、doing/81 状态标记已实施待合并归档（与 his/80 部署计时、his/81 部署审计同号异题，归档时文件名可区分）；批次 C/D（P2/P3）与 R-1~R-7 待议决。
> 21. **DOC-080 登记**（2026-08-08）：doing/80 §12 待议决 R-1~R-7 全部拍板（项目负责人指令：按分析建议处理，合并项并入对应任务跟踪，**不启动实施**）——**R-1（D-09 镜像加速源）选 A**：改阿里云官方 ACR 加速地址（setup-server.sh + 服务器 daemon.json 现场同步，照 DOC-072 脚本与现场一致教训），独立任务待排期（S）；**R-2（D-07 test compose GHCR 死链）选 A**：镜像改 `mindsafe/*:local` 本地构建（先 deploy.sh build 后 up；顺带检查 AUD-003 容器名冲突挂账项），独立任务待排期（S）；**R-3（F-05 shared 边界）选 B 改良**：维持相对路径（单仓同版本发布，workspaces 收益不足），删 emotionLabels.ts 垫片 + shared 测试入 CI 门禁兜底——**合并 FA-16**（doing/81 批次 B）；**R-4（D-23 Playwright）选 B**：预留态登记保留——审计结论过时（smoke-test.sh 31 断言已按 DA-04 接线 deploy.sh 发布后置冒烟门禁，design/04 L222 登记），仅 playwright 浏览器 E2E（config + 3 spec 120 行）未启用，文档标注预留态待排期（S）；**R-5（B-09 TeacherService 上帝类）选 B 有条件**：维持观察（无行为缺陷证据，纯重构收益低），统计域改造时顺势拆 DashboardStatsService——**合并 BA-12/BA-13**（doing/81 批次 B）；**R-6（D-22 tts 异常处理）选 A**：全局异常 handler + dashscope 显式超时（成本极低，TTS 主链路收益真实）——**合并 B-02**（doing/80 批次 C）；**R-7（B-14 话术硬编码）选 B 改良**：维持代码内维护（心理干预话术属预审核合规内容，走发布评审比运行时改 DB 更可控），TEMPLATES 从 Controller 下沉 service 层恢复分层，独立任务待排期（S）。
> 22. **DOC-081 登记**（2026-08-08）：EmotionDiary「双平静」已知取舍（DOC-075 §5.1）决策落地——05 系统测试指导驱动的生产 UI 遍历测试（第 3 轮 bfs3）复现 calm/neutral 同译「平静」同面板重复选项；拍板方案：打卡面板展示层去重（移除 calm 入口、仅保留 neutral），词表单一源 EMOTION_META/EmotionVocabulary 不动（后端 calm 仍合法，对话情绪采集不受影响），趋势图未知/历史码值兜底改显式中性引用（不依赖数组索引防错位）；落点 frontend/student-h5/src/components/EmotionDiary.tsx + src/test/EmotionDiary.test.tsx（断言 6→5 情绪、平静 ×2→×1）。
> 23. **DOC-082 登记**（2026-08-08）：学生端对话窗口全面测试驱动批量修复——2 项修复 + 全面复测：(1) PWA SW 自动更新机制（main.tsx 新增 controllerchange 即时 reload + 60s 周期主动 update + waiting 状态 SKIP_WAITING；vite.config.js 原 sw.js 已有 skipWaiting+clientsClaim 仅补客户端感知层）；(2) 情绪集首页 EmotionSelect 与 EmotionDiary 打卡面板统一（shared/emotionMeta 新增 STUDENT_EMOTION_TAGS=['happy','sad','angry','scared','nervous'] 常量，两端 EMOTIONS 数组都引用此基线；消除原来打卡含平静 vs 首页含害怕的差异；calm/neutral 同译去重顺带落实）；测试同步 EmotionDiary 断言 6→5 + "害怕" 新增；前端三端 tsc 0 错 + student-h5 928 单测全绿；落点 frontend/student-h5/src/main.tsx + src/components/EmotionSelect.tsx + src/components/EmotionDiary.tsx + frontend/shared/src/emotionMeta.ts + src/test/EmotionDiary.test.tsx。

> 24. **DOC-083 登记**（2026-08-09）：对话窗口深度测试发现 P0-1 — WelcomeGuide 全屏遮罩（z:9999）未设 pointer-events，导致新用户首次进入情绪选择页时点击情绪按钮被引导层拦截，必须先主动点击「跳过」才能选情绪；修复方案：WelcomeGuide 根容器加 pointer-events:none，进度点/标题/文案/下一步/跳过/滑动提示所在「交互岛」单独开 pointer-events:auto，引导可用 + 下方情绪按钮可点。落点 frontend/student-h5/src/components/WelcomeGuide.tsx。

> 25. **DOC-085 登记**（2026-08-09）：Browser Agent 三端 Web 界面自动化遍历测试设计登记——方案与提示词单一事实源落 `design/doing/82_BrowserAgent三端Web界面自动化遍历测试设计.md`（承接 R-4 Playwright 预留态，DOC-082）：30 场景案例（S-01~10 学生端 / T-01~08 教师端 / P-01~06 家长端 / L-01~06 三端联动）+ 每场景可执行 Browser Agent 提示词（环境/步骤/断言/截图记录四要素）+ 问题登记规范（reports/browser-test/ISSUES-<端>.md，按端+场景汇总，BUG 条目 P0-P3 分级 + OPEN→FIXED→VERIFIED→REGRESSION 状态机）+ 修复-部署-复测闭环（每端测试完→自动修复（TDD）→自动部署（deploy.sh/compose）→自动复测，3 轮上限，收敛定义 P0=0 且 P1=0 且 P2/P3 未关闭 ≤3，超限升级人工）；语音类（声纹 remote/ASR/TTS 真实链路）因 test compose 不含 voice/tts 标记 SKIP-语音，断言降级路径照常；执行顺序：环境准备→学生端→教师端→家长端→联动场景→汇总归档；ticket 见 §二十九。

> 31. **DOC-091 登记**（2026-08-09）：无屏交互终端配置体系专题——doing/84 方案与 SPEC 生成 + frozen/74 解冻至 doing/74（仅加引用）。doing/84（设计单一事实源 = `design/doing/84_无屏交互终端配置体系_方案与SPEC.md`）：承接 doing/74 §2.4/§3.1/§4.1/§4.5/§8.4 配置面页面级落地——21 项同类产品调研（开源生态 7 / 消费级 IoT 7 / 儿童陪伴硬件 7，含小智 6 位码绑定、华为回连检查状态机、萤石机身标签、微信 scene 短码等）+ 6 类配置页面（扫码入口/配网页/绑定/声纹录入/状态/管理台 M13）+ 机身二维码三层规范（静态码 URL+deviceCode / 绑定验证码 / P2 动态码）+ 配置状态机（扫码→连热点→配网→回连检查→绑定）+ device 域 API 与 4 表 + P0/P1/P2 路线（CFG-001~012）+ EARS AC-84-01~28；frozen/74 解冻（2026-08-09，文件迁移 design/doing/74，主体 §1-14 零改动，仅头部状态/关联/下一步 3 处加 doing/84 引用）；DESIGN-OVERVIEW v6.13 同步；ticket 见 §三十二。
> 30. **DOC-090 登记**（2026-08-09）：doing/83 双文档 ticket 去冗余整合（项目负责人指令：ticket 不冗余保存在设计文档）——删除降级监控文档 §九（ticket 工作包 7 片）与后台管理端文档 §十五（ticket 工作包 29 片），降级监控 §六 任务表压缩为引用行（内容已在 §三十）；设计文档仅保留设计规格（方案/表/API/风格/AC 定义），**ticket 单一事实源 = TASK-TRACKER §三十/§三十一**；§三十 执行顺序更新（002→003→004→005→007→008→006）；§三十一 说明更新（唯一跟踪表，AC 定义见设计文档 §13）；两文档残留引用清理完毕。
> 29. **DOC-089 登记**（2026-08-09）：ticket 跟踪表补登记（修正 DOC-087/088 遗漏）——OPS-MON-007/008 补入 §三十（原仅降级监控文档任务表）；**新增 §三十一 后台管理端 AdminConsole ticket 表（29 行 ADMIN-P0-01~P3-04，状态⬜ 待排期，含归属与跨专题依赖）**；原文档 §九/§十五 标注「执行跟踪见 TASK-TRACKER，本表为 ticket 设计定义」，确立「定义在文档、跟踪在台账」双层结构（对齐 OPS-MON 先例）；frontier：ADMIN-P0-01 与 OPS-MON-002 可立即启动。
> 28. **DOC-088 登记**（2026-08-09）：doing/83 双文档深度审计 + SPEC 开发计划——审计结论（代码实态核对）：① `SlaEscalationScanner`（P-05/WB-001：RED 5min/ORANGE 15min ESCALATE/冷却去重）**已实现**，M8 逾期扫描由「新增」改「复用 + 扩展」（sla_escalation_log 留痕/平台清单与转派端点/业务指标）；② `ModelCallLog.tenantId` 已存在，**R-4 关闭**（无需补列）；③ admin-web 页面数台账修正（24/14 → 实际 25，§八 清单）；④ M7 `prompt_versions` 无 status 字段（6.10 真新增）；⑤ 定时任务用 `@Scheduled` 先例 4 处（OPS-MON-007/008 组件落点据此定）；⑥ M2/M3/M5/M7/M9 Controller 端点/指标/规则全部核对通过（AdminPrompt 8 端点/KB 7 端点/Platform 4 端点/alert-rules 10 条/service-manager 根目录/tts metrics 单 label）；产出：后台管理端文档新增 **§13 SPEC 开发计划**（ADMIN-P0-01~08/P1-01~10/P2-01~07/P3-01~04 共 29 ticket + §13.5 开发准备清单 6 项），降级监控文档补组件落点与执行顺序（OPS-MON-002→003→004→005→007→008）与审计核对注；为启动开发就绪。
> 27. **DOC-087 登记**（2026-08-09）：doing/83 双文档衔接整合——`83_服务降级监控与告警设计.md`（**监控链路/数据层**：指标埋点 → 告警规则 → 通知 → 事件落库 → 部署演练）与 `83_后台管理端AdminConsole设计方案.md`（**管理视图/操作层**：M2 指标看板/告警中心、M3 降级矩阵/手动切换）职责边界厘清：监控链路实现统一归口降级监控文档，**新增 OPS-MON-007（降级事件检测器：指标增量轮询 → degradation_events auto/恢复事件落库，防抖 + 跳过 manual 覆盖点 + SETNX 锁，AC-9；提取自管理端 §5.3 3.3）与 OPS-MON-008（告警采集器：AlertManager 拉取 + AlertService 同步 → alert_events，AC-10；提取自管理端 §5.2 2.3）+ SPEC AC-9/AC-10**；管理端文档 M2/M3 改为消费侧并补数据源引用（3 条降级规则 → 告警中心、降级指标 → 指标看板/降级矩阵、degradation_events auto/manual 写入方划分）、§九 映射与 §11 实施路线依赖更新（M2 P1 依赖 OPS-MON-003/004/008，M3 P2 依赖 OPS-MON-007；P0 告警中心只读先行不依赖落库）。
> 26. **DOC-086 登记**（2026-08-09，**同号异题：develop 线**——与 main 线服务降级监控专题共用 DOC-086/doing/83 编号，文件名可区分，参照 DOC-077/078、his/72 与 72_取消CD 先例）：后台管理端 AdminConsole 设计方案登记——深度调研同类产品（教育 SaaS ClassIn 教务/监课/财务/子账号权限、希沃集控层级看板；通用 SaaS 三后台模型 + 中台标准模块：租户/订阅/计费/权限/运营管理；心理健康 SaaS 橙星云/心大陆/心灵伙伴：测评-预警-档案闭环、红橙黄绿四级预警、市-校-生多层级；计量计费模式：per-seat/usage-based/特性+SLA/计价计费分离）+ 代码实态盘点（5 个平台 Controller 可复用：Platform/AdminTenant/AdminUser/Admin/AdminPrompt；监控体系：Prometheus 3 job + 10 规则 + AlertManager→企微 + AlertService + service-manager.sh 六服务 UP/DEGRADED/DOWN；降级机制完备：ResilientChatModel 主备/TTS 三级/ASR 双引擎/SER_ENABLED/VoiceDegradationPolicy；缺口 7 项：无 admin-web 前端/配置无面板/监控无运营视图/降级无视图无开关/租户无套餐权益/计量无汇总/平台账号体系缺）→ 完整方案落 `design/doing/83_后台管理端AdminConsole设计方案.md`：M1 系统配置管理（配置注册表 sys_config + sys_config_history 变更留痕 + 敏感 SECRET 掩码 + 生效方式 HOT/RESTART 两级，不引入新配置源）/ M2 系统应用监控（服务拓扑三态 + 指标看板后端代理 Prometheus 白名单表达式 + 告警中心 alert_events + 部署历史 + service_health_snapshots 快照落库支撑 SLA 验证）/ M3 服务切换降级监控（降级矩阵实时视图 + 手动切换走 Redis 运行时覆盖键不落部署文件 + degradation_events 历史 + 影响面提示，语义「降级≠宕机」沿用 D5/DA-02）/ M4 租户计量计费（usage_events 计量 → rate_plans 计价 → subscriptions/billing 计费三层，对齐 design/07 99/159/259 元/生/年定价与 entitlement 三层，活跃学生快照 + model_call_logs LLM 聚合；**4.3~4.6 设计冻结待 frozen/38 解冻议决**）/ M5 租户管理（生命周期开通/暂停/恢复/归档 + 配额接线（现常量 500/200 未校验）+ 详情钻取预警分布）/ M6 平台基础（platform_admin 四角色 super_admin/ops_admin/finance_admin/audit + 审计 tenantId 可空平台级）；新增表 10 张；API 4 域（platform 扩展/ops 新增运维执行/admin 现有）；admin-web 前端新建（React 19+TS+Vite 同栈，25 页面（2026-08-09 审计修正：原登记 14 失实），/admin/ 路径）；实施四期 P0 底座（M6+骨架+服务拓扑只读，服务操作先走方案①SSH 人工）→ P1 配置监控深化 → P2 降级监控（运行时覆盖键）→ P3 计量计费（冻结）；开放问题 R-1~R-8（平台账号模型独立表 vs users、服务操作执行通道、配置热生效范围、model_call_logs tenant_id 核对、解冻时序、平台表行级隔离排除、ops_admin 最小权限、管理端 JWT 独立）；**DEC-007 议决落定（2026-08-09）**：R-1 独立 platform_admin 表 + 独立登录端点 / R-2 P0 只读展示 + SSH 人工 / R-7 ops_admin 仅看聚合数据 / R-8 独立 token 前缀 PLATFORM_ / M4 采集层先行落地，R-3/R-4 留待 P1/P3。
> 26. **DOC-086 登记**（2026-08-09，**同号异题：main 线**——与 develop 线后台管理端专题共用 DOC-086/doing/83 编号，文件名可区分）：服务降级监控与告警设计登记（BUG-TTS-01 事故复盘：CosyVoice 主引擎 100% 失败静默降级 edge-tts 长期运行无告警）——方案与 SPEC 单一事实源落 `design/doing/83_服务降级监控与告警设计.md`：**TTS 降级事件指标** `tts_degraded_events_total{direction="cosyvoice->edge_tts"}`（独立 Metrics 实例适配单 label 结构，判定 engine≠首选且非 retried）+ **LLM 主备切换指标零改动复用**（`ResilientChatModel` 已有 `mindsafe.llm.model_fallback{from,to}`，仅补规则）+ **alert-rules 新增 3 条**（TtsPrimaryEngineDegraded 成功计数推导 / TtsDegradeRatioHigh 降级率 30% / LlmPrimaryFailing 引用 `mindsafe_llm_model_fallback_total`）+ 生产部署 monitoring 栈（.env 补 WECOM_* 4 项 + GRAFANA_PASSWORD）+ 降级演练（断 DASHSCOPE key → 指标 +1 → 企微 ≤5 分钟触达）；ticket OPS-MON-001~006 见 §三十，验收标准 AC-1~8。

---

## 二十九、Browser Agent Web 界面自动化遍历测试（2026-08-09）

> 登记说明：本专题承接 design/05 §8.6 R-4（Playwright 预留态登记，DOC-082），落地真实浏览器级 UI 自动化遍历。方案与提示词单一事实源：`design/doing/82_BrowserAgent三端Web界面自动化遍历测试设计.md`（doing 子文档，编号 82，开发期状态）。

| Ticket | 内容 | 范围 | 状态 |
|--------|------|------|------|
| UI-TEST-001 | 测试设计文档生成（doing/82：30 场景案例 + 提示词 + 问题登记规范 + 修复闭环） | 全量 | ✅ 本次完成（2026-08-09） |
| UI-TEST-002 | 环境与数据准备（test compose 起服 + init-school.sh + 账号矩阵 + 测试数据基线 + 静态基线断言） | 环境 | ⬜ 待排期 |
| UI-TEST-003 | 学生端遍历 S-01~10 + 问题登记 + 修复-部署-复测闭环至收敛 | 学生端 | ⬜ 待排期 |
| UI-TEST-004 | 教师端遍历 T-01~08 + 问题登记 + 修复-部署-复测闭环至收敛 | 教师端 | ⬜ 待排期 |
| UI-TEST-005 | 家长端遍历 P-01~06 + 问题登记 + 修复-部署-复测闭环至收敛 | 家长端 | ⬜ 待排期 |
| UI-TEST-006 | 联动场景 L-01~06 + 问题登记 + 修复-部署-复测闭环至收敛 | 三端联动 | ⬜ 待排期 |
| UI-TEST-007 | 汇总报告（reports/browser-test/SUMMARY.md）+ doing/82 合并归档（DOC-085） | 全量 | ⬜ 待排期 |
| UI-TEST-008 | 对话窗口专项执行（C-01~07：假麦克风注入 + 服务级 ASR/TTS 真链路 + 唤醒/记忆/暖场/超时全链路）→ ISSUES-对话专项.md → 修复-部署-复测循环 | 语音/对话 | ⬜ 待排期 |

> 执行顺序：UI-TEST-002 → 003（学生端 S-01~10）→ 008（对话窗口 C-01~07）→ 004 → 005 → 006 → 007（UI-TEST-008 与 003 同批次优先，其问题并入学生端修复循环）；依赖：003~006 各自完成后立即触发修复-部署-复测闭环（3 轮上限），L 场景依赖 S/T/P 收敛后环境。

> 问题登记：各端问题清单落 `reports/browser-test/ISSUES-<端>.md`（BUG-<端>-<场景>-<序号> [P0-P3]），修复后状态流转 OPEN→FIXED→VERIFIED→REGRESSION；每端收敛定义 P0=0 且 P1=0 且 P2/P3 未关闭 ≤3，达 3 轮上限未收敛升级人工。

---

## 三十、服务降级监控与告警（2026-08-09）

> 登记说明：本专题承接 BUG-TTS-01 事故复盘（CosyVoice 主引擎 100% 失败静默降级 edge-tts 长期运行，无告警触达管理员）。方案与 SPEC 单一事实源：`design/doing/83_服务降级监控与告警设计.md`（doing 子文档，编号 83，开发期状态）。

| Ticket | 内容 | 范围 | 状态 |
|--------|------|------|------|
| OPS-MON-001 | doing/83 设计文档生成（方案 + SPEC：TTS 降级指标 / 复用 LLM 指标 / 3 条规则 / 部署 / 演练，AC-1~8） | 全量 | ✅ 本次完成（2026-08-09） |
| OPS-MON-002 | TTS 降级指标埋点（app.py 独立 Metrics 实例 + direction 标签 + test_app.py 用例） | tts-service | ✅ 实施完成（2026-08-09，89 pytest 全绿） |
| OPS-MON-003 | alert-rules 新增 3 条规则（TtsPrimaryEngineDegraded / TtsDegradeRatioHigh / LlmPrimaryFailing） | monitoring | ✅ 实施完成（2026-08-09，YAML 13 条验证通过） |
| OPS-MON-004 | 生产部署 monitoring 栈（.env 补 WECOM_* 4 项 + GRAFANA_PASSWORD → compose up） | 部署 | ⬜ 待部署窗口（.env 红线，人工执行） |
| OPS-MON-005 | 降级演练 + 验证（断 DASHSCOPE key → 指标 +1 → 企微 ≤5 分钟触达 → 恢复） | 验证 | ⬜ 待排期 |
| OPS-MON-006 | 合并归档（doing/83 最终态并入 design/04 §9 监控 / 06 §配置章节 → 归档 his/83） | 全量 | ⬜ 待排期 |
| OPS-MON-007 | 降级事件检测器（30s 轮询指标增量 → degradation_events auto/恢复事件落库，last_value 防抖 + 跳过 manual 覆盖点 + SETNX 锁，AC-9；定义见降级监控文档 §3.5） | counseling-service monitoring | ✅ 实施完成（2026-08-09，6 用例全绿） |
| OPS-MON-008 | 告警采集器（60s 拉取 AlertManager → alert_events upsert + AlertService 同步，resolved 流转 + 30 天清理，AC-10；定义见降级监控文档 §3.6） | counseling-service monitoring | ✅ 实施完成（2026-08-09，5 用例全绿） |

> 执行顺序：OPS-MON-002 → 003 → 004 → 005 → 007 → 008 → 006（007 依赖 002/004，008 依赖 004；005 演练通过后 006 归档）；AC-6 演练为归档前置门禁。

---

## 三十一、后台管理端 AdminConsole ticket（2026-08-09，DOC-089 登记）

> 登记说明：doing/83 后台管理端方案（§13）的 29 个 ADMIN ticket **本表为唯一执行跟踪表**（状态/排期/归属）；AC 定义（验收标准断言）见设计文档 §13（唯一 AC 定义处，DRY）。跨专题依赖：P0-06/P1-07/P1-08 → OPS-MON-003/004/008；P2-01/P2-02 → OPS-MON-007。frontier：ADMIN-P0-01 与 OPS-MON-002 可立即启动。

| Ticket | 任务 | 归属 | 状态 |
|--------|------|------|------|
| ADMIN-P0-01 | platform_admin 表 + 实体 + 迁移（§6.8，R-6 忽略名单） | 后端 | ⬜ 待排期 |
| ADMIN-P0-02 | 平台登录端点 + 独立 JWT（PLATFORM_ 前缀）+ 四角色 | 后端 | ⬜ 待排期 |
| ADMIN-P0-03 | SecurityConfig 角色细化（PLATFORM_ 授权域） | 后端 | ⬜ 待排期 |
| ADMIN-P0-04 | admin-web 脚手架 + 路由守卫 + 角色菜单 | 前端 | ⬜ 待排期 |
| ADMIN-P0-05 | M2 服务拓扑（只读）+ service_health_snapshots | 后端 | ⬜ 待排期 |
| ADMIN-P0-06 | 告警中心只读（AlertManager 直读，依赖 OPS-MON-003/004） | 后端 | ⬜ 待排期 |
| ADMIN-P0-07 | 审计日志查询（跨租户） | 后端 | ⬜ 待排期 |
| ADMIN-P0-08 | P0 回归门禁（P0-01~07 全绿） | 全量 | ⬜ 待排期 |
| ADMIN-P1-01 | sys_config 注册表 + 变更留痕（HOT/RESTART 两级） | 后端 | ⬜ 待排期 |
| ADMIN-P1-02 | M7 审核发布流（submit/review/状态机，三重门禁） | 后端 | ⬜ 待排期 |
| ADMIN-P1-03 | M7 门禁可视化 + safety-phrases 只读 | 后端 | ⬜ 待排期 |
| ADMIN-P1-04 | M8 风险全景 + 时效监控（纯查询） | 后端 | ⬜ 待排期 |
| ADMIN-P1-05 | M8 逾期升级扩展（sla_escalation_log + 转派端点，复用 SlaEscalationScanner） | 后端 | ⬜ 待排期 |
| ADMIN-P1-06 | M8 业务指标埋点 + alert-rules 业务段（R-11） | 后端 | ⬜ 待排期 |
| ADMIN-P1-07 | M2 指标看板（完整，依赖 OPS-MON-003/004/008） | 后端 | ⬜ 待排期 |
| ADMIN-P1-08 | M2 告警中心完整（alert_events 消费 + ack） | 后端 | ⬜ 待排期 |
| ADMIN-P1-09 | 前端 P1 页面组（八页） | 前端 | ⬜ 待排期 |
| ADMIN-P1-10 | P1 回归门禁（P1-01~09 全绿） | 全量 | ⬜ 待排期 |
| ADMIN-P2-01 | M3 降级矩阵 + 手动切换（依赖 OPS-MON-007） | 后端 | ⬜ 待排期 |
| ADMIN-P2-02 | M3 事件时间线（消费 degradation_events） | 后端 | ⬜ 待排期 |
| ADMIN-P2-03 | M9 知识库管理扩展 | 后端 | ⬜ 待排期 |
| ADMIN-P2-04 | M10 通知渠道统计 + 失败台账 + 触达策略 | 后端 | ⬜ 待排期 |
| ADMIN-P2-05 | M12 运营洞察 | 后端 | ⬜ 待排期 |
| ADMIN-P2-06 | 前端 P2 页面组（四页） | 前端 | ⬜ 待排期 |
| ADMIN-P2-07 | P2 回归门禁（P2-01~06 全绿） | 全量 | ⬜ 待排期 |
| ADMIN-P3-01 | usage_events 采集层（M4 先行，幂等） | 后端 | ⬜ 待排期 |
| ADMIN-P3-02 | 用量报表（计量预览标注） | 前端 | ⬜ 待排期 |
| ADMIN-P3-03 | M11 合规视图 | 后端 | ⬜ 待排期 |
| ADMIN-P3-04 | P3 回归门禁（P3-01~03 全绿，冻结项不实施） | 全量 | ⬜ 待排期 |

---

## 三十二、无屏交互终端配置体系 ticket（2026-08-09，DOC-091 登记）

> 登记说明：doing/84 无屏交互终端配置体系（方案与 SPEC）的 CFG ticket **本表为唯一执行跟踪表**（状态/排期/归属）；AC 定义（验收标准断言 AC-84-01~28）见设计文档 §四~§八（唯一 AC 定义处，DRY）。跨专题依赖：CFG-008 → doing/83 M13 挂载点（ADMIN 系列排期）；CFG-011 → frozen/43 W-1 企业主体认证；CFG-012 → NST-HW-02 §3.6 二期。frontier：P0 阶段（CFG-001~005）待立项启动。

| Ticket | 任务 | 阶段 | 归属 | 状态 |
|--------|------|------|------|------|
| CFG-001 | device 域 4 表 + 设备上报/心跳端点（§六） | P0 | 后端 | ⬜ 待立项 |
| CFG-002 | 扫码入口页（parent-h5 `/p/:v/:deviceCode` + 离线兜底 + 步骤条） | P0 | 前端 | ⬜ 待立项 |
| CFG-003 | 配网引导层 + 回连检查轮询（复用原生 captive portal） | P0 | 前端 | ⬜ 待立项 |
| CFG-004 | 设备绑定页 + 绑定验证码会话（语音播报） | P0 | 前后端 | ⬜ 待立项 |
| CFG-005 | 二维码印制规范落地（机身铭牌 + 包装 300×300） | P0 | 产品/硬件 | ⬜ 待立项 |
| CFG-006 | 声纹录入引导页 + 编排 API | P1 | 前后端 | ⬜ 待立项 |
| CFG-007 | 设备信息与状态页（toB/toC 双视图） | P1 | 前端 | ⬜ 待立项 |
| CFG-008 | 管理台配置页（挂 doing/83 M13） | P1 | 前后端 | ⬜ 待立项 |
| CFG-009 | 固件定制配网页（热点名/服务器地址/JSON API，可选） | P1 | 固件 | ⬜ 待立项 |
| CFG-010 | toC 家长端配置引导（家庭/孩子归属 + 远程管理） | P2 | 前后端 | ⬜ 待立项 |
| CFG-011 | 小程序通道（scene 短码，前置 43 号 W-1） | P2 | 前端 | ⬜ 待立项 |
| CFG-012 | L3 动态码 + L1 加密配网（Security 1 / WeXin-BLE-Provision） | P2 | 固件/后端 | ⬜ 待立项 |

> 执行顺序：P0（CFG-001 → 002 → 003 → 004 → 005）→ P1（006 → 007 → 008，009 可选）→ P2（010 → 011 → 012）；CFG-008 依赖 doing/83 M13 挂载点落地；归档门禁：P0 真机全链路验收（配网成功率 ≥90%）+ doing/84 合并归档（最终态并入 doing/74 与 12 号主文档）。
