# doing/81 架构深化候选清单（第四轮·未覆盖区）方案与 SPEC

> 登记：2026-08-08 | 方式：/improve-codebase-architecture（参数：排除已审计区，剩余部分审计），3 路并行探索 agent（后端 Java / 前端三端 / Python 与部署链路），全部只读
> 排除区：AUD-001~071、DC-001~012、T1-T5、B/F/D 系列（his/77+78）、BA/FA/DA 系列（his/79）、ARCH-001~010、取消 CD、**doing/80 刚登记问题点**（B-01~19/F-01~16/D-01~23）
> 状态：**已实施完成（2026-08-08）**：批次 A 全量闭环（BA-09/10/11 按 TOP 3 SPEC + DA-08~11），待合并归档（登记 DOC-079）；批次 B（Worth）/批次 C（Speculative）待议决

---

## §1 审查概述

### 1.1 方法与范围

- 热点定位：`git log -50` 显示后端 conversation 域变更最密集（ConversationServiceImpl×4、SessionState×2、MessageSummaryService），前端 ChatRoom/useTtsPlayer/主题契约层密集，部署链路 voice/tts 服务密集
- 探索深度：后端精读 20+ 核心链路文件；前端精读 20+ 核心文件并交叉 grep 消费链；部署逐一核对 compose/entrypoint/config/CI
- **Strong×7 全部代码级复核实锤，0 处 agent 报告失实**

### 1.2 候选总览（25 候选）

| 编号 | 分级 | 一句话问题 |
|---|---|---|
| BA-09 | 🟢 Strong | nudge 决策链 6 文件无 locality，快照/Redis 真值双轨并行判定，多实例必然发散 |
| BA-10 | 🟢 Strong | MessageSummary 读取/解密/拼接三处重复（文案已漂移），SessionSummaryUpdater 浅模块 |
| BA-11 | 🟢 Strong | ConversationServiceImpl 直连 3 Mapper，DB 细节泄漏编排层（DB 侧无会话仓储） |
| BA-12 | 🟡 Worth | PromptVersionService 路由/门禁/报表三语义混杂，abComparison 无时间窗 |
| BA-13 | 🟡 Worth | 统计报表聚合散落四服务（Teacher/DataAnalytics/PromptVersion/ProfileRadar），无统一报表域 |
| BA-14 | 🟡 Worth | ToolboxController 业务编排 + `Map<String,Object>` 无类型请求体 |
| BA-15 | ⚪ Spec | Layer2 异步租户上下文手动捕获 + stripCodeFence 双处复制 |
| BA-16 | ⚪ Spec | SessionState 279 行上帝对象：领域行为体 + Redis 实体 + 每轮全量序列化 |
| FA-09 | 🟢 Strong | emotionBus 三方同源契约虚化：气泡色调绕开总线另读信号，双词表仅 happy 交集 |
| FA-10 | 🟡 Worth | 同一 SpeechRecognition 在 useVoiceInputPipeline/useVoiceCallMode 各装配一遍，双 workaround |
| FA-11 | 🟡 Worth | useTtsPlayer 427 行内 4 处重复「synthesize→playBlob→降级→none」回退链 |
| FA-12 | 🟡 Worth | RemoteConfig 声明面 > 消费面（wakeWord 窗长/tts personas 键无人消费，改配置静默失效） |
| FA-13 | 🟡 Worth | THEMES.vars 与 THEME_STYLES 双轨 token 色值逐字重复（FA-02 只收敛沉浸式三组件） |
| FA-14 | 🟡 Worth | 状态机枚举（wakeStatus/mode）泄漏到 ChatRoom JSX 双处各映射一遍中文文案 |
| FA-15 | 🟡 Worth | 端点路径三处镜像（api.ts 函数内 + endpoints.ts + 测试正则扫描），重复由纪律维持 |
| FA-16 | 🟡 Worth | emotionLabels.ts 纯 re-export 兼容垫片零消费，浅模块 |
| FA-17 | ⚪ Spec | useWakeConsentFlow/useAndroidAudioRouting 两接线 hook 零独立测试 |
| DA-08 | 🟢 Strong | config_loader.py 双份逐字节复制无同步门禁（DA-03 只护 metrics_common） |
| DA-09 | 🟢 Strong | ASR/SER 模型名 7 处事实源（entrypoint/prepare-funasr/config.yaml/app/ci 未声明 seam） |
| DA-10 | 🟢 Strong | ASR_ENGINE 默认值矛盾（entrypoint dashscope vs app funasr）+ Dockerfile ARG 假接缝未拆 |
| DA-11 | 🟢 Strong | deploy.sh（唯一发布通道 453 行）零测试覆盖，路径映射等关键逻辑无回归防线 |
| DA-12 | 🟡 Worth | 部署根路径 `/guju/mindsafe` 5+ 处字面量，$REMOTE_DIR 定义后未复用 |
| DA-13 | 🟡 Worth | compose nginx 服务 + deploy/nginx/*.conf 未启用死资产，宿主 nginx 配置无版本控制 |
| DA-14 | 🟡 Worth | config.yaml 缺失时兜底「可启动但运行即 500」（KeyError/IndexError），测试锁定了该裂缝 |
| DA-15 | ⚪ Spec | entrypoint 无条件要求 SER 模型，与 SER_ENABLED=false 决策不一致 |

---

## §2 候选深度分析（已验证）

### BA-09 🟢 Strong｜nudge 决策链双真值 + 6 文件无 locality

- **涉及文件**：ConversationServiceImpl / SessionState / NudgeDecisionModel / NudgeStrategy / NudgeProperties / RedisSessionStateStore
- **深度代码分析（已核实）**：T5 已把 nudge 计数真值原子化到 Redis 独立键，但 `ConversationServiceImpl:497` 仍走 `session.canNudge()` 快照判定、`:553 markNudged()` 仍改快照、`:396 nudgeActive` 信号读快照 `getNudgeCount()`——多实例下快照与 Redis 真值必然发散
- **Problem**：理解「冷场暖场」一个概念跨 6 文件跳转；快照与真值双轨并行，行为不可靠
- **Solution**：nudge 计数/时间戳读取统一走 Redis 独立键（快照字段降级展示），canNudge 快照路径删除，Lua 为唯一判定
- **Benefits**：单一真值源、多实例行为一致；决策链收拢后整链可测（当前双真值分支零覆盖）

### BA-10 🟢 Strong｜MessageSummary 读取三处重复 + SessionSummaryUpdater 浅模块

- **涉及文件**：MessageSummaryService / SessionSummaryUpdater / ConversationServiceImpl
- **深度代码分析（已核实）**：「查 MessageSummary → 解密 → 拼接对话文本 → LLM 摘要」模式在 3 类重复（仅过滤条件微差），已见角色标注文案漂移（「学生/AI」vs「学生/波波」）；SessionSummaryUpdater 与 MessageSummaryService 职责高度重叠
- **Problem**：三处独立演进必漂移（已发生）；删除测试：SessionSummaryUpdater 删除只搬家不消失——浅模块
- **Solution**：消息读取统一收口 MessageSummaryService（readSessionTranscript），SessionSummaryUpdater 并入/复用
- **Benefits**：消除三处演进漂移；解密+拼接+过滤逻辑获得单点真实测试面

### BA-11 🟢 Strong｜会话编排器直连 3 Mapper，DB 侧无会话仓储

- **涉及文件**：ConversationServiceImpl
- **深度代码分析（已核实）**：`:62-64` 构造器直持 sessionMapper/messageSummaryMapper/userMapper，10+ 处 DB 访问散落编排层（会话增改查/摘要读写/用户读取），LambdaQueryWrapper/实体部分更新细节泄漏；Redis 侧有 SessionStateStore 仓储，DB 侧无对应
- **Problem**：T4 只下沉到 service，service 内无二次边界；编排器 813 行 + 25 依赖构造器
- **Solution**：抽 CounselingSessionStore（DB 会话读写），摘要查询归 MessageSummaryService（联动 BA-10），编排器只持领域服务
- **Benefits**：DB 读写获得独立测试面（现 51 例测试全 mock Mapper，仓储本身零测试）

### BA-12 🟡 Worth exploring｜PromptVersionService 三语义混杂 + abComparison 无时间窗

- **涉及文件**：PromptVersionService（511 行）
- **问题**：运行期模板路由 / 管理端三门禁发布 / A/B 报表聚合（:400-463 全量加载租户会话内存分组，无时间窗无分页——B-15 同型未登记处）
- **方案**：报表拆统计域、门禁拆治理服务，只留解析路由
- **收益**：热路径（每轮对话 resolve）与冷路径解耦；报表加时间窗后承受表增长

### BA-13 🟡 Worth exploring｜统计报表聚合散落四服务，无统一报表域

- **涉及文件**：TeacherService / DataAnalyticsService / PromptVersionService / ProfileRadarService
- **问题**：「查表→内存聚合→VO」同构模式 4 服务各自实现；报表口径（时区/时间窗/截断）无单点治理（B-03/B-15 修复后仍会回潮）
- **方案**：收敛统一统计读取层（时间窗/分页/班级范围下推），报表服务只做装配
- **收益**：口径变更单点生效；B-03/B-15 修复防回潮

### BA-14 🟡 Worth exploring｜ToolboxController 业务编排 + Map 无 DTO

- **涉及文件**：ToolboxController:94-134 / MoodCheckRecorder / RelaxationService
- **问题**：recordMoodCheck 全编排在 Controller（Map 强转、工具验证、mood 计算、relaxation 落库、attention 告警），`Map<String,Object>` 请求体无类型契约（前端传字符串即坏请求）
- **方案**：mood-check 编排下沉 MoodCheckService，请求体改 DTO
- **收益**：Controller 恢复薄壳；落库编排获得可测单元（现只有纯函数测试，编排零覆盖）

### BA-15 ⚪ Speculative｜Layer2 异步租户手动捕获 + stripCodeFence 双处复制

- **涉及文件**：OutputReviewService:101-118 / MessageSummaryService:151-174
- **问题**：异步租户上下文手动捕获/恢复内嵌调用处；stripCodeFence/parseReview 与 parseInsights 双处同构复制（含代码围栏剥离）
- **方案**：TaskDecorator 统一传播；代码围栏剥离收敛公共工具
- **收益**：async 租户隔离语义单点可测；消除双处漂移

### BA-16 ⚪ Speculative｜SessionState 上帝对象（279 行，双态聚合）

- **涉及文件**：SessionState / RedisSessionStateStore
- **问题**：9 组字段（元数据/轮次/情绪历史/安全模式/情绪状态机/CTX-Agent 上下文/个人信息…），同时是 Redis JSON 实体与领域行为体；每轮消息全量 JSON 序列化
- **方案**：与 BA-09 联动——nudge 状态先行剥离，安全模式/情绪状态机后续独立，SessionState 降级聚合根
- **收益**：写路径可评估裁剪（高频全量序列化）；领域行为归属清晰

### FA-09 🟢 Strong｜emotionBus 三方同源契约虚化 + 双情绪词表

- **涉及文件**：emotionBus.ts / theme/emotionTypography.ts / useChatSession.ts:101-107 / MessageBubble.tsx:34
- **深度代码分析（已核实）**：design/37「三方同源」契约仅表情状态机真正订阅；气泡色调实读 `msg.emotion`（孩子语音情绪）绕开总线；REPLY_EMOTIONS（happy/gentle/encourage/calm/serious/soothe）与 EMOTION_TYPO 键集仅 happy 交集——接总线后五类回复情绪静默落 neutral
- **Problem**：契约虚化 + 词表双轨，改一处即劈叉；按规范接总线会静默退化
- **Solution**：择一信号源并文档化，双词表并入 shared 单源合一
- **Benefits**：情绪语义单点注册；测试断言两词表一致，防「接错源」回归

### FA-10 🟡 Worth exploring｜SpeechRecognition 双实现双 workaround

- **涉及文件**：useVoiceInputPipeline.ts:108-143 / useVoiceCallMode.ts:92-136
- **问题**：同一浏览器能力两条语音链各装配一遍；Android 重复 final bug 只在一处修（另一处用防抖+首轮过滤），修一处漏一处
- **方案**：抽共享装配层（语言/连续/拼接/去重），两调用方只接结果回调
- **收益**：平台差异收敛单点；装配与去重策略分别可单测

### FA-11 🟡 Worth exploring｜useTtsPlayer 内 4 处重复播放回退链

- **涉及文件**：useTtsPlayer.ts:203-216,250-261,332-342,365-374（427 行）
- **问题**：speak/speakSentence/feedToken 链/endStreaming 尾句四处重复「synthesize→playBlob→browserSpeak 降级→engine=none」；新增行为（播放日志/中断）需改四处
- **方案**：提取单句播放链 playSentence 统一消费
- **收益**：播放语义单点；测试收敛一条链，降级分支不四路重复断言

### FA-12 🟡 Worth exploring｜RemoteConfig 声明面 > 消费面

- **涉及文件**：remote.ts:19-39,104-119 / useWakeWord.ts:26-34 / useTtsPlayer.ts:137-140
- **问题**：声明 wakeWord.{modelId/windowSeconds/silenceRmsThreshold}、tts.{defaultPersona/personas}，getConfigValue 实际消费面仅 guideScripts/verifyThreshold/maxTemplates——运维改这些键静默失效，接口承诺失效无告警
- **方案**：删未消费键或真接线（唤醒窗长/阈值走 getConfigValue），声明与消费面一致
- **收益**：配置契约真实化；测试断言每声明键有消费点

### FA-13 🟡 Worth exploring｜THEMES.vars 与 THEME_STYLES 双轨 token

- **涉及文件**：ThemeProvider.tsx:12-77 / immersiveStyles.ts:10-83
- **问题**：三主题调色板两套平级 token 体系各写一遍，色值逐字重复（FA-02 只收敛沉浸式三组件，未消除与 ThemeProvider 双轨）；改主题需双改，漏改即白字白底
- **方案**：THEME_STYLES 从 THEMES 派生或并入同一定义
- **收益**：主题单点；immersiveStyles 测试从硬编码断言退化为跨体系一致性断言

### FA-14 🟡 Worth exploring｜状态机枚举泄漏到 JSX 双处

- **涉及文件**：ChatRoom.tsx:333-350,373-413
- **问题**：useVoiceCallMode/useWakeWord 内部枚举泄漏到 JSX 双处各映射一遍中文文案，改文案需同步两处
- **方案**：状态→文案映射收敛为单一描述函数或 VoiceStatusHint 子组件
- **收益**：状态机 seam 不外泄；文案映射可直接单测

### FA-15 🟡 Worth exploring｜端点路径三处镜像，契约防线实为固化重复

- **涉及文件**：teacher-web api.ts:175-291 / api/endpoints.ts:9-54 / apiContract.test.ts:33-58
- **问题**：端点路径硬编码 api.ts 函数内，同时镜像 endpoints.ts 与测试清单；apiContract 正则扫源码断言 ⊆ 清单——新增端点必双改，模板拼路径形态漏检
- **方案**：端点收敛为常量表（path/method/导出名），api() 消费常量，清单从常量导出
- **收益**：单点编辑；断言从「正则扫字符串」升级为「直接校验常量表」

### FA-16 🟡 Worth exploring｜emotionLabels.ts 零消费垫片

- **涉及文件**：teacher-web/src/utils/emotionLabels.ts:1-6
- **问题**：F4 收编后遗留纯 re-export 兼容垫片，全库零消费；删除测试=搬家型浅模块
- **方案**：删除，存量引用改指 shared
- **收益**：少一跳转发层，grep 直达事实源

### FA-17 ⚪ Speculative｜两接线 hook 零独立测试

- **涉及文件**：useWakeConsentFlow.ts / useAndroidAudioRouting.ts
- **问题**：DC-012 抽出的接线 hook 无独立测试（computeBoboState 有），800ms 弹窗时序/600ms 预热/document 监听仅被集成测试间接覆盖
- **方案**：补 fake-timers 行为测试
- **收益**：接线层可测，回归定位快

### DA-08 🟢 Strong｜config_loader.py 双份复制无同步门禁

- **涉及文件**：ci.yml:250-253 / voice-service/config_loader.py / tts-service/config_loader.py
- **深度代码分析（已核实）**：两 config_loader.py 53 行逐字节相同（diff 输出为空）；DA-03 只建 metrics_common 的 diff 门禁（ci.yml:253），config_loader 同构风险零防护
- **Problem**：深合并语义（空 dict 保守保留/None 显式置空）是 D1 配置单源化核心契约，单边修改即静默分叉
- **Solution**：docker-build-smoke job 增加 diff 两文件（与 metrics_common 同款一行门禁），或提为共享构建层
- **Benefits**：双份同步从人肉记得变机器门禁；locality 提升，CI 即防漂移

### DA-09 🟢 Strong｜ASR/SER 模型名 7 处事实源，隐式 seam 未声明

- **涉及文件**：entrypoint.sh:20-23 / deploy/scripts/prepare-funasr.sh:35-36,152-160 / config.yaml:7,15 / app.py:223-224 / config.py:21,26
- **深度代码分析（已核实）**：同一模型名 4 文件 7 处表达（grep 全仓 25 命中）；entrypoint 硬编码 REQUIRED_MODELS 校验目录、prepare-funasr 硬编码下载、health 硬编码展示名；权威 config.yaml 与消费方间是未声明 seam
- **Problem**：私有化/信创定制模型（design/41 演进路线）时，换模型必踩 entrypoint 校验误判或下载错模型，无门禁捕获
- **Solution**：entrypoint/prepare-funasr 从 config.yaml 读取模型名（脚本内 yaml 解析），或 CI 断言 yaml 值出现在各消费点
- **Benefits**：换模型变单点修改；shell 测试可注入 yaml 变更验证 entrypoint，测试面从零到有

### DA-10 🟢 Strong｜ASR_ENGINE 默认值矛盾 + Dockerfile ARG 假接缝

- **涉及文件**：voice-service/Dockerfile:11 / docker-compose.prod.yml:126-127 / entrypoint.sh:12 / app.py:40
- **深度代码分析（已核实）**：Dockerfile `ARG ASR_ENGINE=dashscope` 零消费（注释自认「不再控制依赖安装」）但 compose 仍传 build args（:127）；entrypoint.sh 默认 dashscope vs app.py:40 默认 funasr——脱离 compose 手动 run 时行为撕裂
- **Problem**：假接缝未拆 + 双默认值矛盾；引擎选择无单一事实源
- **Solution**：删 Dockerfile ARG + compose build args；entrypoint 不设默认（唯一环境变量驱动）或与 app.py 对齐
- **Benefits**：删除测试通过（ARG 是浅资产，删除后复杂度消失）；引擎选择单源

### DA-11 🟢 Strong｜deploy.sh（唯一发布通道）零测试覆盖

- **涉及文件**：deploy.sh 全文（453 行）/ ci.yml:218-236
- **深度代码分析（已核实）**：路径映射（:170-179，含「deploy/ 变更→全量」隐式规则）、retry 执行器（:238-257）、check_nginx_paths（:407-435）、CD 残留清理（:314-325）零断言；DA-05 把六件套+backup-common 入 CI，但发布脚本自身是最大未测试区——漏组件（如 deploy/ 变更不触发 nginx reload）只能上线后暴露
- **Problem**：唯一发布通道无回归防线，隐式规则不可见不可测
- **Solution**：抽纯函数（路径映射/组件判定）或 mock ssh/rsync/docker 的 shell 测试，挂入 shell-tools-test
- **Benefits**：发布链回归防线；隐式规则显式可测

### DA-12 🟡 Worth exploring｜部署根路径 5+ 处字面量

- **涉及文件**：deploy.sh:40,439-441 / service-manager.sh:23,268
- **问题**：`/guju/mindsafe` 跨两脚本多处硬编码，check_nginx_paths spec 拼字面量绕过 $REMOTE_DIR；deploy.sh:47 注释自认「与 service-manager.sh 保持一致」的手工同步依赖
- **方案**：deploy.sh 内统一 $REMOTE_DIR 拼接；shell 测试 grep 断言两脚本根路径一致（DC-002 模式）
- **收益**：单一事实源；路径变更有测试兜底

### DA-13 🟡 Worth exploring｜compose nginx 死资产 + 宿主 nginx 无版本控制

- **涉及文件**：docker-compose.prod.yml:187-211 / deploy.sh:45-47 / service-manager.sh:65-66,174-184
- **问题**：nginx 配置双事实源——仓库 deploy/nginx/（挂载到未启用容器，改动不生效）与宿主 /etc/nginx/nginx.conf（生效但不在仓库）；理解「前端如何被服务」需跳转 4 文件
- **方案**：议决 a) 删 compose nginx 服务 + deploy/nginx/*.conf（删除测试：复杂度集中到宿主单源）；b) 宿主 nginx.conf 版本化入库 + deploy.sh 同步上传 + `nginx -t` 校验
- **收益**：b 将 nginx 纳入唯一发布通道；a 消除未启用死资产

### DA-14 🟡 Worth exploring｜config.yaml 兜底「可启动但运行即 500」

- **涉及文件**：tts-service/app.py:92-101,196,258 / voice-service/config.py:29 + app.py:194 / voice-service/test_config.py:50,121
- **问题**：兜底契约不诚实——tts 兜底 `voice_personas:{}` 下 `VOICE_PERSONAS["xiaoxing"]` 必 KeyError；voice 兜底 `emotion_labels:[]` 下 max_idx 必 IndexError；且 test_config.py 断言空矩阵为期望行为，测试面与运行契约脱节
- **方案**：a) 兜底补全最小运行矩阵（xiaoxing persona/neutral instruct/9 标签）；b) 启动期 fail-fast 校验（yaml 缺失即拒绝启动），删除虚假兜底
- **收益**：诚实契约 + 删除测试；测试断言启动失败而非静默带病运行

### DA-15 ⚪ Speculative｜entrypoint 无条件要求 SER 模型

- **涉及文件**：voice-service/entrypoint.sh:20-23 / app.py:139-150
- **问题**：ASR_ENGINE=funasr + SER_ENABLED=false 时 entrypoint 仍硬性要求 emotion2vec 目录存在——显式禁用 SER 却被其模型阻断部署；模型检查面与加载面对 SER_ENABLED 感知不同步
- **方案**：entrypoint 读取 SER_ENABLED，false 时从 REQUIRED_MODELS 剔除 emotion2vec；补 shell 测试
- **收益**：行为一致性；entrypoint 从零测试到有测试

---

## §3 Top 3 深度 SPEC（定稿）

### SPEC-1【BA-09】nudge 决策链收敛为 Redis 单一真值源

- **目标**：删除快照判定路径，Lua 原子键为唯一判定
- **实施**：
  1. `RedisSessionStateStore` 暴露 nudge 计数/时间戳读写（T5 已建键，补读接口）
  2. `ConversationServiceImpl:497` canNudge 改走 Redis；`:553` markNudged 改走 Lua（含时间戳）
  3. `SessionState` 快照 nudge 字段降级为展示用（`:396` nudgeActive 改读 Redis 计数）
  4. `SessionState.canNudge/markNudged` 删除（同步删测试）
- **验收**：多实例并发下 nudge 计数一致（并发测试）；整链单测覆盖 Redis 路径；后端回归全绿
- **联动**：BA-16（SessionState 剥离 nudge 状态）可顺带完成

### SPEC-2【DA-09】模型名单一事实源（config.yaml 权威 + CI 断言）

- **目标**：换模型变单点修改
- **实施（方案 A：CI 断言，改动最小）**：
  1. `tests/unit/scripts/verify-model-names.sh`：解析 config.yaml 模型名，断言出现在 entrypoint.sh / prepare-funasr.sh / app.py 消费点
  2. 挂入 ci.yml shell-tools-test job
- **实施（方案 B：脚本读 yaml，彻底）**：entrypoint.sh/prepare-funasr.sh 内 yaml 解析取模型名（Python 单行或 grep 提取），删除硬编码
- **验收**：改 config.yaml 模型名 → CI 红（方案 A）或消费点自动跟随（方案 B）；shell 测试全绿
- **建议**：先 A 后 B（A 是门禁防漂移，B 是根治；B 涉及 entrypoint 改动需部署验证）

### SPEC-3【BA-10】消息读取单点化 + SessionSummaryUpdater 收编

- **目标**：消除三处重复，文案漂移不再发生
- **实施**：
  1. MessageSummaryService 增 `readSessionTranscript(sessionId, 过滤参数)`（查→解密→拼接，角色标注统一「学生/AI」）
  2. ConversationServiceImpl:706-731 与 SessionSummaryUpdater:68-91 改调单点
  3. SessionSummaryUpdater 收编进 MessageSummaryService（或仅保留薄调度层）
  4. 文案断言测试（防「学生/波波」漂移回潮）
- **验收**：全仓无第二处「查→解密→拼接」实现；文案单点断言测试通过；会话摘要相关测试全绿
- **联动**：BA-11（DB 访问收口）可顺带完成摘要查询部分

---

## §4 实施要点表（其余 22 候选）与建议批次

### 批次 A（Strong 全量，按 TOP 3 SPEC + 其余 Strong）

| 编号 | 实施要点 | 工作量 |
|---|---|---|
| BA-09 | 按 SPEC-1 | M |
| DA-09 | 按 SPEC-2（先 A 后 B） | S-M |
| BA-10 | 按 SPEC-3 | M |
| BA-11 | 抽 CounselingSessionStore（DB 会话读写），编排器只持领域服务；补仓储测试 | M |
| DA-08 | docker-build-smoke 加一行 diff 门禁（config_loader.py） | S |
| DA-10 | 删 Dockerfile ARG + compose build args；entrypoint/app 默认值对齐（环境变量唯一驱动） | S |
| DA-11 | deploy.sh 路径映射/组件判定抽纯函数 + shell 测试挂 shell-tools-test | M |

### 批次 B（Worth exploring 高价值）

| 编号 | 实施要点 |
|---|---|
| FA-09 | 择一信号源（建议：气泡色调改走 emotionBus），双词表并入 shared 单源；加词表一致性测试 |
| FA-11 | useTtsPlayer 提取 playSentence 单句播放链，四处统一消费 |
| FA-13 | THEME_STYLES 从 THEMES 派生，调色板单源；immersiveStyles 测试改跨体系一致性断言 |
| DA-14 | tts/voice 兜底补最小运行矩阵 + fail-fast（二选一，建议 a 补矩阵 + b 启动校验双做） |
| FA-12 | RemoteConfig 删未消费键（或真接线），加「每声明键有消费点」测试 |
| BA-14 | recordMoodCheck 下沉 MoodCheckService + 请求体 DTO |
| BA-12 | abComparison 加时间窗/分页，报表语义拆统计域（**承接 R-5，DOC-080 已议决**） |
| FA-10 | 抽 SpeechRecognition 共享装配层（去重策略单点） |
| DA-13 | 议决 a/b：建议 b（宿主 nginx 版本化入库 + deploy.sh 上传 + nginx -t） |
| DA-12 | $REMOTE_DIR 统一拼接 + 根路径一致性 shell 测试 |
| FA-14 | 状态→文案映射收敛单一描述函数 |
| FA-15 | 端点收敛常量表，apiContract 直接校验常量 |
| BA-13 | 统一统计读取层（时间窗/分页/班级下推），先于 B-03/B-15 修复落地防回潮（**承接 R-5，DOC-080 已议决**） |
| FA-16 | 删除 emotionLabels.ts 垫片（**承接 R-3，DOC-080 已议决**） |

### 批次 C（Speculative 议决）

| 编号 | 建议 |
|---|---|
| BA-15 | 建议实施（低成本）：TaskDecorator 统一传播 + stripCodeFence 收公共工具 |
| BA-16 | 建议随 BA-09 联动（nudge 状态剥离），其余字段独立化另行评估 |
| FA-17 | 建议实施：两接线 hook 补 fake-timers 测试 |
| DA-15 | 建议实施：entrypoint 感知 SER_ENABLED |

---

## §5 审计过程记录

- 方式：/improve-codebase-architecture（参数：排除已审计区，剩余部分审计），3 路并行探索 agent，全部只读
- 日期：2026-08-08
- Strong×7 全部主 agent 代码级复核实锤，0 处失实
- 排除区：AUD/DC/T/B·F·D/BA·FA·DA/ARCH 系列 + 取消 CD + doing/80（B/F/D 问题点）
- 状态：候选清单 + SPEC 登记完成（doing/81），批次 A~C 待排期；**2026-08-08 批次 A 实施完成（BA-09/10/11 + DA-08~11，DOC-079）；批次 B/C 待排期（DOC-080 已议决：R-3 合并 FA-16、R-5 合并 BA-12/BA-13 一并跟踪）**

---

_候选续用 BA/FA/DA 系列编号（第四轮，自 BA-09/FA-09/DA-08 起），与 his/79 编号无冲突_
