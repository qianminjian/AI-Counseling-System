# doing/77 架构深化候选清单（方案与 SPEC）

> 状态：🟡 部分实施（B1/D1/F1+F2 已落地，2026-08-08；其余候选待议决）
> 登记编号：DOC-073（接续 DOC-072）
> 触发方式：/improve-codebase-architecture（全项目范围）
> 基线去重：AUD-001~071（his/71）、DC-001~012（his/72）、DOC-072 T1-T5（his/76）、parent-h5 Taro 迁移（his/73）——以下候选全部为**未覆盖的新摩擦点**

---

## §1 审查概述

### 1.1 方法与范围

- **探索**：3 路并行 agent（后端 Java 五模块 / 前端三端 / Python 服务+部署链路），git 历史热区定位（近 40 条提交集中在 DOC-072、parent-h5 Taro、doing/75 风格统一）
- **深度验证**：所有 Strong 与关键 Worth exploring 候选均经**代码级证据复核**（grep 行号锚点），发现 1 处 agent 报告失实（F1：student 端 authFetch 实际已存在，仅服务特殊场景）已修正
- **评估词汇**：module / interface / depth / seam / adapter / leverage / locality + 删除测试（deletion test）
- **约束**：只读审计，未修改任何代码；未跑测试（全量回归需串行且昂贵）

### 1.2 候选总览

| # | 候选 | 强度 | 一句话 |
|---|------|------|--------|
| B1 | 危机热线五源漂移（号码冲突 12355 vs 400-161-9995） | 🟢 Strong | SAFE-203 声称闭环实未闭环，RED 硬短路仍读硬编码 |
| B2 | nudge 暖场策略双真值常量漂移（Lua vs 快照） | 🟡 Worth | T5 引入的双轨遗留，改阈值需改三处 |
| B3 | RedisChatMemory：僵死 KEYS 方法 + 无租户 key + 元数据丢失 | 🟡 Worth | 死代码 + 生产反模式 + 跨进程契约无版本 |
| B4 | LLM 文案硬编码 Java（buildGenderStyle 70+ 行）绕过版本路由 | 🟡 Worth | 复用现有 PromptVersionService 零新机制 |
| B5 | TeacherService 分页安全化残留 + 班级范围查询五重复 | 🟡 Worth | 纪律钩子拦不到 Service 内部 LIMIT 回流 |
| B6 | buildEmotionSuggestion 绕过 DC-008 词表（3/9 码值） | ⚪ Speculative | 单点文案，收编成本最低 |
| F1 | 三端 JSON 请求器语义三分歧（接缝诚实度） | 🟢 Strong | DC-005 接缝立起但主路径未进接缝 |
| F2 | student 登录前裸 fetch ×5 | 🟡 Worth | 四段模板逐字重复 |
| F3 | teacher-web 轮询生命周期 ×4 | 🟡 Worth | setInterval+hidden+cleanup 四套 |
| F4 | 情绪展示映射 ×4（前端镜像 DC-008） | 🟡 Worth | 键空间不统一（英文 code vs 中文 label） |
| F5 | TTS 降级朗读 ×3 | 🟡 Worth | 第三份实现游离在既有 seam 之外 |
| F6 | 麦克风 PCM 采集启动 ×2 | ⚪ Speculative | 权限生命周期语义各自演化 |
| D1 | 配置双源逐字复制 + 浅合并缺陷 | 🟢 Strong | 配一个方言=丢默认矩阵（事故隐患） |
| D2 | voice-service 测试空白 + e2e 脚本失实 | 🟢 Strong | 与 tts 测试深度反差，import hack 才可测 |
| D3 | 配置透传契约只覆盖 TTS（voice 三变量死配置） | 🟢 Strong | 机制覆盖一半服务 |
| D4 | deploy.sh 重试/SSH/.env 解析散落 | 🟡 Worth | 同一概念三处变体 |
| D5 | tts /health DEGRADED 语义无下游消费者 | 🟡 Worth | 降级状态被系统性丢弃 |
| D6 | nginx 双轨 + 部署角落残留（GHCR/硬编码 schema） | 🟡 Worth | 宿主配置无版本化 |

---

## §2 B1 危机热线五源漂移 · 号码冲突 🟢 Strong

### 2.1 深度代码分析（已验证）

| 源 | 位置 | 内容 | 是否读配置 |
|----|------|------|-----------|
| OutputContentFilter | `backend/counseling-ai/.../safety/OutputContentFilter.java` L38/L58 | `CRISIS_HOTLINE = "400-161-9995"` + `@Value("${mindsafe.safety.crisis-hotline:...}")` 构造注入 | ✅ Layer1 关键词过滤路径 |
| CrisisResources | `.../safety/CrisisResources.java` L14/L45/L56 | `NATIONAL_PSYCHOLOGICAL_AID` 常量 + 两段话术内嵌号码 | ❌ 常量 |
| CrisisResourceProvider | `.../safety/CrisisResourceProvider.java` L35 | RED 硬短路话术 `"全国心理援助热线：" + CrisisResources.NATIONAL_PSYCHOLOGICAL_AID` | ❌ 常量（L28 注释"后续扩展：查 tenant_config"） |
| RecallPhrases | `.../safety/RecallPhrases.java` L28 | 召回话术内嵌 `400-161-9995（24 小时都有人接）` | ❌ 常量 |
| RiskResponseStrategy | `.../conversation/strategy/RiskResponseStrategy.java` L42 | **`拨打心理援助热线 12355`（号码冲突 ❌）** | ❌ 常量 |
| application.yml | `backend/counseling-app/src/main/resources/application.yml` L164 | `crisis-hotline: ${MINDSAFE_CRISIS_HOTLINE:400-161-9995}` | 配置载体（已存在） |

**删除测试实证**：删除 `@Value` 注入后，RED 硬短路/召回话术/风险响应三条路径行为完全不变——配置化只覆盖 5 条路径中的 1 条。

### 2.2 Problem

- 跨 seam 泄漏：热线事实源从配置层泄漏进常量类、Java 字符串、prompt 文本，五处各自维护
- **号码冲突实锤**：`12355`（RiskResponseStrategy）vs `400-161-9995`（其余四处）同时在产——学生收到哪条取决于走哪条路径
- locality 缺失：学校部署 `MINDSAFE_CRISIS_HOTLINE` 后，Layer1 生效但 RED/召回/引导仍输出代码旧号码

### 2.3 Solution / Benefits

- 热线事实源收敛为单一配置载体（复用现有 `mindsafe.safety.crisis-hotline`），所有话术拼装点（RED/召回/时长引导/Layer1）统一经同一 Provider 渲染；话术模板保留预审核硬编码（防 LLM 幻觉，遵 design/14 铁律），号码字段参数化注入
- 一个契约测试（改配置→断言三路径输出）锁死全链；号码冲突类 bug 从"人工 grep 5 处"变成不可能

---

## §3 B2 nudge 暖场策略双真值常量漂移 🟡 Worth exploring

### 3.1 深度代码分析（已验证）

- `RedisSessionStateStore.java` L43/45：`NUDGE_MAX_COUNT=2` / `NUDGE_MIN_INTERVAL_SECONDS=20`，L160-161 注入 Lua（Redis 真值路径）
- `SessionState.java` L163-168：`canNudge()` 内联 `nudgeCount >= 2` 与 `>= 20`（快照判定路径）
- `ConversationServiceImpl`：护栏 2 用快照 canNudge，随后 tryNudge 走 Lua 真值 + markNudged 写回

### 3.2 Problem / Solution

- **Problem**：同一业务策略两个模块两套常量（注释自认"以 Lua 为准"）；无编译期护栏或测试能发现常量漂移——改一侧阈值另一侧静默失配；修改策略需同时改三处文件手工保证一致
- **Solution**：nudge 阈值提升为单一配置源（`mindsafe.conversation.nudge-*`），Lua 由 Spring 渲染注入、快照判定引用同源；**或反向**——删快照侧 `canNudge()` 只留 Lua 真值（快照本质是"省一次 Redis 调用"的性能捷径，存在价值存疑）
- **Benefits**：DC-010"策略下沉"模式自然延伸；测试从"mock 两边数值断言一致"变为"配一处值断言两路径行为一致"

---

## §4 B3 RedisChatMemory 僵死 KEYS + 无租户 key + 元数据丢失 🟡 Worth exploring

### 4.1 深度代码分析（已验证）

- `RedisChatMemoryRepository.java` L49-50：`findConversationIds()` 用 `redisTemplate.keys("*")`（O(N) 阻塞扫描）——**全项目无调用者**（grep 确认）
- L31：`KEY_PREFIX = "chat:memory:"` 无租户段（对比 ARCH-010 已给 `session:state` 加租户段，同一 Redis 命名空间两套惯例）
- L122-123：`serializeMessage` 用 `Map.of` 只存 role/content 两字段，消息元数据（token 统计等）序列化往返中静默丢失

### 4.2 Problem / Solution

- **Problem**：僵死方法（AUD-071 清理 RecurrenceCalculator 同类遗留未覆盖此处）+ 生产反模式埋雷；key 设计漂移；跨进程序列化契约无版本字段，元数据损坏在链路上而非接口上
- **Solution**：删 `findConversationIds`（或改 SCAN 游标 + 租户前缀）；序列化升级保留完整元数据（显式 schema 版本字段）；key 增加租户段对齐 `session:state`
- **Benefits**：会话记忆唯一持久化 seam 成为可测试契约（golden JSON 往返测试锁格式）；与 ARCH-010 模式对齐

---

## §5 B4 LLM 文案硬编码 Java，绕过 PromptVersionService 🟡 Worth exploring

### 5.1 深度代码分析（已验证）

- `AiChatServiceImpl.java` L397：`buildGenderStyle(gender, grade)` 私有方法，L82/L126 两处调用拼入 system prompt——6 段性别×年龄风格中文 prompt（70+ 行）以 Java 字符串固化
- `LlmStreamEnhancer.java` L48-49：`FALLBACK_MESSAGE` 学生向文案硬编码，L166 使用
- 对比既有设施：`PromptVersionService`（模板 key + A/B 分组 + versionTag 落库 + DB/classpath 双源降级）成熟可用

### 5.2 Problem / Solution

- **Problem**：面向 LLM 的内容域（prompt 文案本身）固化在实现层——cross-seam leak；改一句沟通风格话术 = 改代码 + 重新部署，无版本追踪/A/B/热更新；与已登记"TeacherController 话术模板硬编码"同病但杠杆更高
- **Solution**：文案下沉 classpath `prompts/` 模板（`gender_style_{gender}_{band}`），经现有 PromptVersionService 路由；FALLBACK_MESSAGE 并入"学生向文案单一来源"
- **Benefits**：零新机制获得版本追踪/A/B/热更新/文案解耦四重能力；测试从 mock prompt 字符串变为断言模板渲染结果

---

## §6 B5 TeacherService 分页安全化残留 + 班级范围查询重复 🟡 Worth exploring

### 6.1 深度代码分析（已验证）

- `.last("LIMIT n")` 字符串拼接残留三处：L368 `LIMIT 1`、L599 `LIMIT 10`、L608 `LIMIT 20`（同文件 L282 注释确认 selectPage 已用于部分路径——AUD-043 未全覆盖）
- 班级/学生范围查询重复：`.eq(User::getUserType, User.USER_TYPE_STUDENT)` 出现 **5 处**（L143/L261/L656/L735/L761）——同一"班级学生集合"语义多次逐字实现（agent 报告为 3 处，复核为 5 处）

### 6.2 Problem / Solution

- **Problem**：纪律双模式并存——commit-msg hook 只拦 Controller 禁 Mapper，**拦不住 Service 内部 LIMIT 回流**；范围解析重复 5 处，各带不同后续处理但查询本身同一语义
- **Solution**：三处 `.last("LIMIT")` 统一改 selectPage（与同文件既有模式一致，成本极低）；班级学生集合抽私有方法或下沉 `SessionAccessService`（该服务已承担归属校验单点职责，范围解析是自然邻居）
- **Benefits**：安全纪律获得显式收口点；范围过滤测试从五处各测收敛为一处 + 复用断言

---

## §7 B6 buildEmotionSuggestion 绕过 DC-008 词表 ⚪ Speculative

### 7.1 深度代码分析（已验证）

- `ConversationRiskProcessor.java` L291-298：`buildEmotionSuggestion` switch 只覆盖 sad/fearful/angry 三码值，anxious/withdrawn/lonely/crisis/scared/nervous 全落 default"学生语音情绪异常"
- 对比：`EmotionVocabulary.ZH_LABELS`（DC-008 权威源）已覆盖全词表——注释声称收编此处但实际只收编了负面判定，中文文案映射漏网

### 7.2 Problem / Solution

- 改引 `labelOf(voiceEmotion)` 拼接"学生语音情绪「{label}」，建议关注"，删本地 switch；覆盖面从 3 码值扩大到全词表，新增码值自动正确
- 价值低于前五个（单点文案、无安全命门），但收编成本最低；与 F4（前端镜像）同源可联动

---

## §8 F1 三端 JSON 请求器语义三分歧 🟢 Strong

### 8.1 深度代码分析（已验证，修正 agent 报告一处）

- **student** `frontend/student-h5/src/api.ts`：
  - L83 `authFetch = createAuthFetch(storage)` **已存在**（DC-005 接缝），但仅服务特殊场景（multipart 上传/SSE 流/音频下载，L80-82 注释）——agent 报告"未实现 authFetch"**失实，已修正**
  - 主路径 `api()`（L153+）**独立实现**认证逻辑：L161 `Authorization: Bearer ${token}`、L166 `if (res.status === 401)`、L171-175 手动刷新重放——与 authFetch 双轨并存
  - 登录前裸 fetch：L222 trial/register、L238 pin-login、L273 voice-login（见 F2）
- **teacher** `frontend/teacher-web/src/api.ts`：`!json.success` 判定
- **parent** `frontend/parent-h5/src/platform/request.ts`：L80 `if (!res.ok)` HTTP 层判定（Taro.request 封装）——与另两端 success 语义分歧
- **shared** `frontend/shared/src/auth-transport/`：authFetch/apiError/refresh/sessionExpired/tokenStorage 全套接缝已立起

### 8.2 Problem / Solution

- **Problem**：接缝诚实度——DC-005 已立起 seam，但主路径（api()）未进接缝，三端成功判定语义分歧（Bearer 双轨 / res.ok vs json.success），parent 返回整 JSON 逼出防御双处理
- **Solution**：api() 主路径切换至 authFetch 统一认证（Bearer 注入/401 刷新重放/错误契约）；三端 success 判定统一；删 parent 防御双处理
- **Benefits**：一次收敛消灭三端分歧 + 双轨认证 + 两处防御代码；删除测试干净（authFetch 语义已成熟，测试齐备）

---

## §9 F2 student 登录前裸 fetch ×5 🟡 Worth exploring

### 9.1 深度代码分析（部分验证）

- `api.ts` L222/L238/L273（trial-register / pin-login / voice-login）逐字重复「fetch→json→!success→throw」四段模板；agent 报告另含 getVoiceprintConfig / remoteVoiceprintVerify 共 5 处（认证前无法走带 token 的 api()）

### 9.2 Problem / Solution

- 抽 `publicFetch`（与 api() 同构但不注入 token），5 处收敛为 1 个实现 + 5 行调用；错误契约一处定义
- 与 F1 同文件，建议同批处理

---

## §10 F3 teacher-web 轮询生命周期 ×4 🟡 Worth exploring

### 10.1 深度代码分析（agent 证据）

- Dashboard(15s) / BigScreen(30s) / TodayTodoPanel(30s) 三套「setInterval + hidden 暂停 + cleanup」逐字重复；WS ping 第四处（AUD-047 补丁本身重复 3 次）

### 10.2 Problem / Solution

- 抽 `usePolling(fn, interval, {pauseOnHidden})`，4 处收敛；隐藏暂停/错误重试语义统一；测试从"无测试"变为 hook 级单测（定时器/隐藏暂停路径）

---

## §11 F4 情绪展示映射 ×4（前端镜像 DC-008）🟡 Worth exploring

### 11.1 深度代码分析（agent 证据）

- MessageBubble（英文 code）/ EmotionSelect / EmotionDiary / parent report（中文 label 键）四处各自实现映射且键空间不统一；后端 DC-008 已收敛 EmotionVocabulary，前端镜像未做

### 11.2 Problem / Solution

- 建 shared emotionMeta 模块（code→icon/color/label 单一映射），4 处消费；与后端 labelOf 契约对齐
- 与 B6 同源联动：后端收编 + 前端镜像 = 前后端契约测试闭环

---

## §12 F5 TTS 降级朗读 ×3 🟡 Worth exploring

### 12.1 深度代码分析（agent 证据）

- useTtsPlayer 已有 browserSpeak 能力，VoiceLoginOverlay / RelaxationExercises 各自重写 cancel→utter→zhVoice 三段——第三份实现游离在既有 seam 之外

### 12.2 Problem / Solution

- 两处复用 useTtsPlayer.browserSpeak（或提升 shared）；降级链（CosyVoice→edge-tts→browser）语义收敛
- 与 D5（DEGRADED 语义消费）同链联动

---

## §13 F6 麦克风 PCM 采集启动 ×2 ⚪ Speculative

### 13.1 深度代码分析（agent 证据）

- VoiceLoginOverlay.initMic 与 useWakeWord 启动段逐字重复（getUserMedia 约束 + AudioContext + resume + 音量 rms）；useAudioRecorder.warmUp 第三种流管理

### 13.2 Problem / Solution

- 抽"麦克风采集会话"模块（启动/音量回调/释放/错误映射统一），三处消费；权限生命周期（拒绝/切换设备）成为可测契约

---

## §14 D1 配置双源逐字复制 + 浅合并缺陷 🟢 Strong

### 14.1 深度代码分析（已验证，双服务实锤）

- **tts-service** `app.py`：L105 `_DEFAULT_CONFIG`（内置默认，与 config.yaml 逐字复制 7 音色/8 方言/10 情感矩阵）；L154 `copy.deepcopy(_DEFAULT_CONFIG)`；**L167 `config[key].update(value)` 浅合并**——voice_personas 4 层嵌套结构，yaml 部分配置（如只改一个方言 instruct）会整体替换默认矩阵
- **voice-service** `config.py`：L18 `DEFAULT_CONFIG`；L47 deepcopy；**L58 `config[key].update(value)` 同样浅合并**——同一模式两服务各自实现
- 合并 bug 目前只修在 voice 测试（test_partial_config_merges_with_defaults 只测 asr 一层），tts 4 层嵌套无人覆盖

### 14.2 Problem / Solution

- **Problem**：删除测试直接失败（删 config.yaml 行为完全不变——yaml 零增值）；浅合并 = 部分 persona 配置触发整体替换（潜在生产事故）；双服务各实现一套
- **Solution**：默认值单源化（建议 yaml 保留权威、代码只留最小兜底）；配置加载统一为**深合并**（deep merge），合并语义显式测试；两服务共用同一加载函数
- **Benefits**：连带解决 D2（import hack 才可测）与 D3（四层漂移）一半工作量；partial persona 场景可断言"默认矩阵保留 + 只覆盖指定项"

---

## §15 D2 voice-service 测试空白 + e2e 脚本失实 🟢 Strong

### 15.1 深度代码分析（agent 证据 + 结构确认）

- voice-service 仅 4 个 py：app.py / config.py / dashscope_asr_e2e.py / test_config.py——**无 test_app.py、无 test_dashscope_asr.py**（docstring 引用的文件不存在）；tts-service 有 4 个测试文件（含 12 项 mock SDK 深度用例 test_tts_engines.py）
- `dashscope_asr_e2e.py` 是 print 脚本（无 assert、非 pytest），重复生产 Recognition 逻辑却不可回归
- 可测性缺陷：模块级模型加载使 pytest 无法 import app；tts 靠 importlib.reload hack 规避——"配置在 import 时读"结构本身在惩罚测试
- CI python-services-test job 对 voice 实际只执行 test_config.py

### 15.2 Problem / Solution

- 按 DC-011 已给 tts 建立的适配器模式（TTSBackend seam），给 voice 的 ASR 调用抽可注入 seam；纯函数（结果解析/超时/错误映射）先行测试；e2e 脚本转 pytest 冒烟用例或删除
- 与 D1 同批：配置 seam 落位后 voice 可测性障碍（模块级 import 副作用）随之消失

---

## §16 D3 配置透传契约只覆盖 TTS 🟢 Strong

### 16.1 深度代码分析（agent 证据）

- `scripts/verify-config-passthrough.sh` L22：grep 硬编码 `DASHSCOPE_|TTS_` 前缀——DC-003 契约是 TTS 专用
- voice-service app.py 消费 `VOICE_PROCESS_TIMEOUT` / `VOICE_ANALYZE_TIMEOUT` / `VOICE_CORS_ORIGINS` 三个运行时变量，但 .env.example 未登记、prod compose 未透传——**死配置**（代码路径永远拿默认值）
- 配置语义在代码默认值 → .env.example → compose → 容器环境变量四层漂移，无自动化双向校验

### 16.2 Problem / Solution

- 契约从"前缀白名单"泛化为"按服务声明的变量清单"：CI 校验 compose 透传 ⊇ .env 登记 ⊇ 代码消费（双向）；补 voice 三变量登记与透传
- 沿用 verify-config-passthrough.sh 模式扩展覆盖面，不新造轮子；新增变量 CI 自动标红

---

## §17 D4 deploy.sh 重试/SSH/.env 解析散落 🟡 Worth exploring

### 17.1 深度代码分析（agent 证据）

- 同一"重试外部操作"概念三处变体：rsync 递增退避（L232-245）/ build 固定 sleep 60（L309-316）/ nginx 校验 sleep 5（L366-373），参数互不相通
- SSH 选项双定义：`SSH_OPTS`（L34）与 check_nginx_paths 内独立参数（L367）
- .env 解析散落：service-manager.sh 手动 grep REDIS_PASSWORD，与 compose env_file 是第三套读取方式

### 17.2 Problem / Solution

- 收敛"带重试的执行器"与"SSH 参数构造"为脚本内单一函数（统一退避/超时来源）；.env 读取收敛为单一 source 点带缺失校验
- 统一执行器后可对退避逻辑 dry-run 断言（目前脚本无 dry-run 能力）

---

## §18 D5 tts /health DEGRADED 语义无下游消费者 🟡 Worth exploring

### 18.1 深度代码分析（agent 证据）

- tts /health（app.py L285-293）在引擎不可用（engine=="none"）时返回 DEGRADED 但 HTTP 仍 200——**死语义**：service-manager / compose healthcheck / Dockerfile HEALTHCHECK 三重健康检查全部只查 URL 可达、不读 body
- TTS 三级降级（CosyVoice→edge-tts→前端兜底）防御链中，最外层编排感知不到第一级失效，告警粒度从"降级"退化到"宕机"

### 18.2 Problem / Solution

- 让 DEGRADED 有消费者：降级时返回非 200 让编排层可见，或 service-manager 解析 body 告警（"TTS 已降级 edge-tts，CosyVoice 不可用"）——二选一，**不推翻现有三套判定**（与 DC-004 议决不冲突）
- Prometheus 已监控 TTS 失败率/全引擎告警，可拿到更早更细的预警信号

---

## §19 D6 nginx 双轨 + 部署角落残留 🟡 Worth exploring

### 19.1 深度代码分析（agent 证据）

- nginx 双轨：仓库 http 版 `default.conf` 挂 dev/test compose，生产走宿主 https 版 `default-ssl.conf`（无版本化、无校验手段），限流/缓存/超时各自演化
- dev/prod/test 三份 compose 的 environment 块逐字重复（含 LLM_PRIMARY_* 旧名迁移逻辑）
- setup-server.sh §7 仍提示 GHCR login 与 GitHub Secrets（DOC-069 取消 CD 后已无消费方）；restore.sh L102 硬编码 `tenant_template.users` schema 领域知识进恢复脚本

### 19.2 Problem / Solution

- nginx 收敛单轨（版本化宿主配置 + 校验和/`nginx -t` 语法检查门禁）；compose 公共 environment 抽公共锚点；清理 CD 残留段；restore.sh schema 参数化或从 DB 元数据读取
- deploy.sh 的 nginx 路径校验可升级为配置语法校验，部署前即失败

---

## §20 Top recommendation

1. **B1 危机热线五源漂移**（安全零容错）——唯一"声称已闭环但实际未闭环"的候选：SAFE-203 登记配置化完成，但 RED 硬短路仍读硬编码，且两个号码同时在产。删除测试最干净，一个契约测试锁死全链。
2. **D1 配置双源 + 浅合并**（事故隐患）——deletion test 直接证明 config.yaml 零增值，浅合并是"只配一个方言就丢默认矩阵"的实打实事故。修复自然沉淀两服务共享深合并模式，连带 D2/D3 一半工作量。
3. **F1 三端请求器三分歧**（接缝诚实度）——DC-005 接缝已立起但主路径未进接缝，一次收敛消灭三端语义分歧 + 双轨认证 + 防御双处理，F2 同文件同批。

三者互不依赖可并行立项。B2 nudge 双真值为 T5 刚引入的双轨遗留，越早收编成本越低。

---

## §21 附注与后续

- 本清单登记编号 **DOC-073**；议决后实施跟踪按项目惯例落本文件 §22+ SPEC 章节或 TASK-TRACKER
- 候选证据锚点行号为 2026-08-08 审计时快照，实施前以代码现状为准（参照 his/76 §3.3 教训：审计行号会随重构失效）
- Speculative 三项（B6/F6/D6 部分）建议随对应主线候选（B6 随 B4/F4、F6 随 F2、D6 随 D1/D3）顺带处理，不单独立项
- HTML 可视化报告：`tmp/architecture-review-20260808-074829.html`（已 git 忽略，不入版本库）

---

# SPEC 章节（2026-08-08 深度设计定稿）

## §22 B1 SPEC · 危机热线收敛为单一 Provider

**目标**：热线事实源单一化，五路径全部经配置注入渲染，消灭号码冲突。

**改动文件**：
- 新增 `counseling-ai/.../safety/CrisisHotlineProvider.java`：构造注入 `mindsafe.safety.crisis-hotline`（缺省回退常量，与现 OutputContentFilter 语义一致），暴露 `render(String templateKey)` 与 `hotline()`；话术模板（RED/召回/时长引导/Layer1）保留在各自的预审核常量/配置中，仅 `{hotline}` 占位符由 Provider 渲染
- 修改 `CrisisResourceProvider`：RED 话术拼接改 Provider 渲染
- 修改 `RecallPhrases`：话术改 `{hotline}` 占位符
- 修改 `RiskResponseStrategy` L42：12355 → 占位符渲染（**同时消除号码冲突**）
- 修改 `OutputContentFilter`：crisisHotline 字段迁移至 Provider（保持构造注入向后兼容：保留无参/单参构造器供测试）

**测试**：
- 新增 `CrisisHotlineProviderTest`：改配置 → 断言 RED/召回/风险响应/时长引导四路径输出全部含新号码（一个契约测试锁死全链）
- 回归：现有 OutputContentFilterTest / RiskResponseStrategyTest 全量保留

**验收**：
- `grep -rn "12355\|400-161-9995" backend --include="*.java"`（排除测试与常量定义）→ 仅剩 Provider 一处兜底常量
- 配置 `MINDSAFE_CRISIS_HOTLINE=xxx` 启动 → 五路径输出均为 xxx

## §23 D1 SPEC · 配置默认值单源 + 深合并

**目标**：两服务配置加载统一为"yaml 权威 + 代码最小兜底 + 深合并"。

**改动文件**：
- 新增 `backend/tts-service/config_loader.py`（或 voice 同款共享模块，若两服务部署独立则各放一份相同实现 + 测试）：`load_config(path=None, defaults=None)` 实现递归深合并（dict 递归 update，list 替换）；优先级 env > yaml > 默认值
- 修改 `tts-service/app.py`：删除 `_DEFAULT_CONFIG` 全量默认矩阵，保留最小兜底（空 dict 或必要字段）；L167 浅合并 → 深合并调用
- 修改 `voice-service/config.py`：同上（DEFAULT_CONFIG 收敛最小兜底；L58 → 深合并）
- `config.yaml` 保持为权威默认值载体（CFG-004 免重建改配置价值保留）

**测试**：
- 深合并语义显式测试：partial persona（只配一个方言 instruct）→ 断言默认矩阵保留 + 仅覆盖指定项；覆盖 yaml 缺失场景（回退默认值）
- tts 侧补 4 层嵌套合并用例（当前缺口）

**验收**：
- tts/voice 各 `pytest` 全绿；`config.yaml` 只配一个方言后加载结果包含完整默认矩阵

## §24 F1 SPEC · 三端请求语义收敛进 auth-transport

**目标**：三端主路径统一走 DC-005 接缝，成功判定语义单一。

**改动文件**：
- `frontend/student-h5/src/api.ts`：`api()` 内部认证逻辑（L157-183 手写 Bearer + 401 重放）替换为基于 `createAuthFetch` 的封装（保留 api() 签名与错误语义，内部实现切换）；登录前裸 fetch 5 处抽 `publicFetch`（F2 同批）
- `frontend/parent-h5/src/platform/request.ts`：成功判定从 `!res.ok` 统一为 success 契约（对齐 shared apiError 语义）；删除 report/consent 两处 `(res.data || res)` 防御双处理
- `frontend/teacher-web/src/api.ts`：对齐 shared 错误契约（如语义已一致则仅验证）

**测试**：
- student：apiContract.test.ts / api.test.ts 全量保留并断言语义不变
- parent：请求器单测（mock Taro.request）+ 两处防御双处理删除后组件测试回归
- shared auth-transport 测试已有（authFetch.test.ts 等）作为契约基准

**验收**：
- 三端各 `vitest` 全绿；`grep -rn "res.ok" parent-h5` → 无主路径残留

## §25 其余候选实施要点（议决后展开）

| 候选 | 实施要点 | 建议批次 |
|------|---------|---------|
| B2 nudge | 配置源 `mindsafe.conversation.nudge-*` + Lua 渲染注入 + 快照引用同源；或删除 canNudge 快照 | 与 B1 同批（对话安全域） |
| B3 RedisChatMemory | 删 findConversationIds（无调用者）；serializeMessage 加 schema 版本字段；key 加租户段 | 与 B2 同批 |
| B4 LLM 文案 | prompts/ 模板 + PromptVersionService 路由；FALLBACK_MESSAGE 同迁 | 独立小批 |
| B5 TeacherService | 3 处 .last("LIMIT")→selectPage；班级范围查询抽 SessionAccessService | 独立小批 |
| B6 情绪文案 | buildEmotionSuggestion 改 labelOf 拼接 | 随 B4 或 F4 |
| F3 usePolling | teacher-web hooks/usePolling + 4 处替换 | 独立小批 |
| F4 emotionMeta | shared emotionMeta 模块 + 4 处消费 | 随 B6 联动 |
| F5 browserSpeak | VoiceLoginOverlay/RelaxationExercises 复用 useTtsPlayer | 随 F3 |
| F6 麦克风会话 | 采集会话模块 + 3 处消费 | Speculative，随 F2 |
| D2 voice 测试 | ASR seam + 纯函数测试 + e2e 脚本整改 | 随 D1 同批 |
| D3 透传契约 | 变量清单泛化 + voice 三变量登记透传 | 随 D1 同批 |
| D4 deploy.sh | 重试执行器 + SSH 参数收敛 + .env source 单点 | 独立小批 |
| D5 health | /health DEGRADED 非 200 或 service-manager 读 body 告警 | 随 D4 |
| D6 nginx/残留 | 单轨化 + compose 锚点 + 清 CD 残留 + restore.sh 参数化 | 随 D4 |

## §26 Speculative 探索备忘

- **B6**：收益最小但成本最低，随 B4 或 F4 顺带；不单独立项
- **F6**：依赖浏览器权限流细节，需实测后评估；探索方式=对比三处实现差异清单
- **D6 宿主 nginx**：需现场确认生产宿主配置是否已从仓库同步（Speculative 成分），单轨化前置调研

---

## §27 实施记录（2026-08-08，/implement 三线）

> 范围：§22 B1 + §23 D1 + §24 F1（含 F2 同批），其余候选按 §21"议决后展开"不纳入本轮。

| 任务 | 结果 | 证据 |
|---|---|---|
| B1 危机热线五源收敛 | ✅ | CrisisHotlineProvider（{hotline} 渲染 + 配置注入 + 兜底常量）；五路径（Layer1/RED/召回/时长引导/热线文本）全部经 Provider；12355 冲突消除；全仓库字面量仅剩兜底常量一处（grep 验收） |
| D1 配置深合并单源化 | ✅ | tts/voice 各放 config_loader.py（deep_merge + load_config）；tts 全量矩阵迁出至 config.yaml 权威源（代码兜底最小化）；voice emotion_labels 同步收敛骨架（review 修正 M2）；删除测试翻转（删 config.yaml 行为改变） |
| F1 三端请求语义收敛 | ✅ | student api() 主路径切换 authFetch 接缝；parent 成功判定统一 success 契约（删 !res.ok + 两处防御双处理）；teacher 验证已对齐；`grep res.ok parent-h5` 无残留 |
| F2 登录前裸 fetch ×5 | ✅ | publicFetch 抽取（trialRegister/pinLogin/voiceLogin/getVoiceprintConfig/remoteVoiceprintVerify），错误契约一处定义（fallback message 保留） |
| code-review 修正 | ✅ | M1（L5 无渲染路径→注释防呆 + render 契约测试）；M2（voice 矩阵收敛）；M3（深合并边界语义：空 dict 保留/None 替换 + 2 测试）；L1（request.ts 注释对齐 4xx 契约）；L4（时长超限接线测试） |
| 全量回归 | ✅ | 前端三端 vitest 1189 项全绿（student 858 / parent 120 / teacher 211）；Python pytest 87 项全绿（tts 77 / voice 10）；后端 6 模块 783 项 0 失败（counseling-app 12 个环境依赖集成测试除外，非本轮改动） |
| git 提交 | ✅ | 原子提交（文档登记 + 三线实施，check-commit 纪律） |
