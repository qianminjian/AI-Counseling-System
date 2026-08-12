# 审计报告 07 - 学生端（student-h5）

- **审计时间**：2026-08-12
- **审计范围**：`frontend/student-h5`（非测试文件 78 个；`src` 下入口 App.tsx/main.tsx、api.ts/endpoints.ts、18 hooks、29 components、workers/wakeWordWorker.ts、utils/config/data）
- **方法**：git log 热点分析（2026-07-20 起）+ 全量读取核心链路 9 文件（useWakeWord/useVoiceCallMode/useVoiceprint/useTtsPlayer/useSseStream/useChatSession/ChatRoom/api/endpoints/wakeWordWorker/transformersLoader）+ 测试盘点（70 测试文件）+ BEACON 冻结决策逐项核对（只读，未改动任何文件）

## 1. 板块概况

**结构**：装配式架构——`ChatRoom.tsx`（513 行）作为唯一编排点，装配 17 个业务 hook（useVoicePersona/useTtsPlayer/useVoiceCallMode/useVoiceInputPipeline/useChatSession/useWakeConsentFlow/useAndroidAudioRouting/useBoboExpression/useMotionPreference 等），hooks 各自封装单一职责；语音域深度收敛（S-014 Worker 配置单点、S-015 活动状态单一派生、S-016 TTS 播放链收敛、DC-009 Transformers.js 双实现收敛、FA-10 识别装配层、FA-11 播放回退链单点）。

**依赖关系**：
```
ChatRoom ──useChatSession──> useSseStream（consumeSseStream 纯函数单点）
         ──useVoiceCallMode──> useWakeWord（模块级 Worker 单例）─> wakeWordWorker.ts
         ──useTtsPlayer──> api.fetchTtsSynthesize ─> shared/auth-transport
         ──useVoiceprint──> shared/audio-utils（N-011）+ utils/transformersLoader（DC-009）
```
跨包协作均为"hooks 经 api.ts 接缝消费后端"，无绕过封装直连；模块级单例（Worker/模型/音频元素）用 `__resetXXXForTest` 暴露重置口，可测试性良好。

**规模统计**：语音域 5 文件约 1600 行（useWakeWord 525 + useVoiceprint 328 + useTtsPlayer 407 + useVoiceCallMode 211 + wakeWordWorker 130），是全端复杂度最集中区域；测试 70 文件覆盖全部 29 组件 + 核心 hooks + 纯函数（chatSessionPure/activityState/voiceStatusHint/apiContract 等），测试缺口最小。

## 2. 热点与风险初判

- **F-19~F-30（2026-08-09~08-10，约 20 个 commit）**：声纹/唤醒词 Worker 链路密集修复。其中 F-27（57fa1d5f）为重大故障——"重构误删 init 消息 → Worker 永不加载 → 180s 超时 → UI 永远'正在准备'（今晚全部故障的根源）"，代码注释（useWakeWord.ts:200-201）完整留存事故史。
- **风险初判**：①Worker 通信链路（init 消息/ready 竞态/超时）历史缺陷密度最高，是本板块最脆弱区域；②TTS 合成链（后端→浏览器→none 三级降级）为第二热点；③doing/93（S-014/015/016）与 doing/94（R-001/003/004）前端批次均已落地，收敛治理总体到位。

## 3. 发现清单

### P0（架构级）
**未发现**。无跨模块耦合、分层违规、安全红线问题；隐私即设计落地完整（音频本地处理不上传：useVoiceprint.ts:10、useWakeWord.ts:9-11）；心理健康对话数据无前端外泄路径；共享设备会话隔离（sessionStorage + 5 分钟无操作登出）符合共享 Pad 场景。

### P1（模块级）

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试判断 |
|---|---|---|---|---|---|
| P1-1 | useTtsPlayer.ts:211-237（playSentences）vs :283-365（startStreaming/feedToken/endStreaming） | TTS 播放存在**流式/非流式双轨编排**：两套句子队列状态（sentences/currentSentenceIdx vs streamBufferRef/streamQueueRef/streamIdxRef/streamPlayChainRef）与切句/合并逻辑（splitSentences/mergeShortSentences）双路径调用。S-016 已合并共享编排（enqueueStreamedSentences）但顶层入口仍双轨，后续新增停顿/变速控制需改两处，理解链路过长 | 将"切句→预合成→串行播放"抽象为单一 `playQueue(sentences, {trackFallback, precompute})` 内部状态机，两条入口只做参数差异化；合并 stream* refs 与 sentences 状态 | locality：TTS 行为变更一处生效；模块深度从双轨降为单轨 | 保留：streaming 路径有独立测试锁定（PERF-004/BUG-TTS-02 历史回归点） |
| P1-2 | useTtsPlayer.ts:221（`sentences.map(s => synthesizeSentence(s))`）、:313（流式同样全量预合成） | **并行合成无并发上限**：长回复（10-20 句）会瞬间并发 N 个后端 TTS 合成请求，形成请求峰值并放大合成成本（CosyVoice/方言矩阵）。换取的句间零停顿收益在 8 句以上递减 | 引入并发窗口（如 3-4 路）的预合成队列：`synthesizeNext()` 按序推进，保证"播放第 i 句时 i+1..i+3 已就绪"即可消除停顿 | 成本控制 + 峰值削峰；leverage：TTS 计费/限流策略后续可在此单点调整 | 保留：playSentences 测试存在（S-016 收敛时锁定） |
| P1-3 | useVoiceCallMode.ts:28 `COOLDOWN_SECONDS = 25`、:30 `RESTART_DELAY_MS = 300`、:32 `SPEECH_END_DEBOUNCE_MS = 1800` | 唤醒交互关键参数全部硬编码，与同域对照不一致——useWakeWord 已走远程配置（FA-12：`wakeWord.windowSeconds/silenceRmsThreshold`，useWakeWord.ts:68-76），而对话窗冷却/防抖时长无远程键，运维无法线上调参 | 将三参数登记远程配置键（`voiceCall.*`），本地常量降级为 fallback（复用 getConfigValue 模式） | 可运维性：25s 冷却窗与防抖时长是真实体验敏感参数，线上微调免发版 | 保留：现有 useVoiceCallMode 测试适配（参数注入化后测试更易） |

### P2（局部）

| 编号 | 位置 | 问题描述 | 建议方案 |
|---|---|---|---|
| P2-1 | useWakeWord.ts:52-54、useVoiceprint.ts:32-34、wakeWordWorker.ts:14-16、transformersLoader.ts:85 | F-25 轨迹日志在生产以 `console.info` 全量输出（含每次 Worker 消息），与 AUD-027"生产零噪音"（useWakeWord.ts:48-49 注释）目标存在张力；F-30 仅对 progress 采样去重，status/result 消息仍逐条输出 | progress 之外的消息降为 `console.debug` 或采样（每 N 条 1 条），保留 error/warn 全量 |
| P2-2 | useTtsPlayer.ts:141 `if (!res.ok || res.status === 204)` | 204 被当作失败处理计入失败计数（backendFailCount++）。若后端以 204 表达"无合成内容"（合法语义），会污染降级窗口统计。需核对后端 TtsController 是否可能返回 204 于非错误场景 | 核对后端契约；若 204 为合法空结果应 `return null` 但不计失败 |

## 4. 改进候选排序

- **Strong**：P1-2（TTS 并发窗口——成本与峰值直接相关，改动局部、收益可量化）
- **Worth exploring**：P1-1（播放链单轨化——当前双轨是 S-016 收敛的中间态，是下一步自然演进）；P1-3（唤醒参数远程化——对齐 FA-12 既有模式）
- **Speculative**：P2-1/P2-2 属打磨项，可随 TTS/唤醒专题顺带处理

## 5. 设计一致性核对

| 冻结决策 | 实现核对 | 结论 |
|---|---|---|
| BEACON #23 声纹双模式（local 0.70 / remote 0.55） | VP_VERIFY_THRESHOLD=0.70（config/voiceprint.ts:44）；local 前端比对（useVoiceprint.ts:287-316）、remote 走 api.ts:296-301（tenantId 必传 AUD-001） | ✅ 一致 |
| BEACON #26 模型自托管 SAME_ORIGIN | VP_MODEL_REMOTE_HOST='SAME_ORIGIN'（voiceprint.ts:32）+ buildRemoteHost 同源拼接（transformersLoader.ts:49-56） | ✅ 一致 |
| BEACON #25 唤醒引擎深化（continuous+防抖/首轮过滤/预加载） | 防抖 1800ms（useVoiceCallMode.ts:32,101）、首轮唤醒词残留过滤（:106-114）、预启动 Worker（F-19，useWakeWord.ts:158） | ✅ 一致 |
| BEACON #16 音频不出设备（隐私即设计） | useWakeWord.ts:9-11、useVoiceprint.ts:10 均声明本地推理；远程比对仅传 embedding 不传音频 | ✅ 一致 |
| BEACON #27 ORT 单线程 `numThreads=1` | **实现为 2**（transformersLoader.ts:106、wakeWordWorker.ts:60）——F-8/F-8-Worker（2026-08-09）有意演进（双线程加速 session_create，需 SAB+pthread），代码注释已说明，**但 BEACON 决策 #27 ③ 未同步更新**（grep design/ 无 numThreads=2 登记） | ⚠️ 文档滞后（红线 design-persistence §4.2 轻度违反；非实现缺陷） |
| DC-005 认证传输三端收敛 | student-h5 全量消费 shared/auth-transport（api.ts:11-14） | ✅ 一致 |
| doing/94 R-001 端点单一事实源 | ENDPOINTS 表 + FRONTEND_ENDPOINTS 派生（endpoints.ts:14-60），组件内联路径已登记（createChatSession/chatSessionMessages/closeSession） | ✅ 一致（注释自陈"未登记的表外路径依赖人工登记+快照校验兜底"，属已知收窄） |

## 6. 修复建议

- **P0**：无，本板块不需集中修复。
- **P1 按收益排序**：①P1-2 TTS 并发窗口（成本/峰值，改动 <30 行，推荐进入集中修复）；②P1-1 播放链单轨化（中型重构，建议与 S-016 后续演进合并，可作为独立专题）；③P1-3 唤醒参数远程化（低风险，可随后端配置注册表扩展顺带做）。
- **P2**：可选。P2-1 随 F 系列后续修复顺带处理；P2-2 需先核对后端 204 语义再定。
- **文档同步**：BEACON 决策 #27 ③ 的 numThreads 描述需随本批汇总统一修正（已列证据，供汇总 agent 引用）。
