# 65 前端 API/SSE 接缝收敛（ARCH-005）方案与 SPEC

> 关联任务：ARCH-005（深度审计 F-1/F-2/F-3/F-9 回填，doing/61 C4 深化为可实施 SPEC，登记 TASK-TRACKER §二十八）
> 状态：✅ 实施完成（⏱️ 2026-08-06 01:33:16，详见 §8）
> 依据：深度审计 2026-08-05（F-1 SSE 解析 3 遍 / F-2 裸 fetch 实为 5 处 / F-3 契约漏 8+ 端点 / F-9 三类同意三套 key）、doing/61 §7 C4（断言已按审计修正）
> 词汇：接缝 / 局域性 / 契约防线——见 [13 领域词汇表](../13_领域词汇表.md)

---

## 1. 背景与问题

**F-1 · SSE 协议解析 3 遍**：
- `useSseStream.ts` L77-93：唯一完整协议解析实现（token/emotion/risk/done）
- `useSilenceNudge.ts` L82-102：第 2 遍内联重写（裸 fetch + 手拼 Authorization + 只消费 token 事件，**emotion/risk 事件被静默丢弃**）
- `ChatRoom.test.tsx` L215 等：测试内联 reader 工厂第 3 遍

**F-2 · API 接缝泄漏 5 处**（审计修正 doing/61「4 处」断言；其中 2 处实走 authFetch、1 处绕过 401）：
| 位置 | 端点 | 现状 |
|------|------|------|
| `useSilenceNudge.ts` L72 | nudge | 裸 fetch + 手拼 Authorization，**绕过 401 刷新**（P0-1，ARCH-002 已修复为 fetchWarmPrompt） |
| `config/remote.ts` L54 | system/config | 裸 fetch（本次收敛） |
| `VoiceLoginOverlay.tsx` L82 | tts/login-prompt | 裸 fetch（本次收敛） |
| `ChatRoom.tsx` L149 | voice/analyze | 已 authFetch（ARCH-002 期间改造，本次收编为具名函数） |
| `useTtsPlayer.ts` L166 | tts/synthesize | 已 authFetch（ARCH-002 期间改造，本次收编为具名函数） |

**F-3 · 契约防线漏 8+ 核心端点**：`apiContract.test.ts` L39-55 `FRONTEND_ENDPOINTS` 仅 15 个，消息/nudge/voice 分析/tts synthesize/tts login-prompt/system config/sessions close+end/chat sessions 8 个核心端点全在案外——**最核心路径最不受保护**。mock 样例三处手写重复。

**F-9 · 三类同意三套 key、版本语义不一**：`api.ts` L46 `mindsafe_consent_done`（告知同意，`_done` 后缀）/ `VoiceConsentDialog.tsx` L3 `mindsafe_voice_consent_v1` / `VoiceCallConsentDialog.tsx` L4 `mindsafe_voicecall_consent_v1`——后两者已是 `_v1` 语义，仅需引用枚举；前者需迁移兼容。

## 2. 目标与非目标

**目标**：
- SSE 协议解析收敛为单点实现（`useSseStream`），nudge 链路不再丢弃 emotion/risk 事件
- 5 处散落端点全部收进 `api.ts` 具名函数（统一 authFetch）
- `FRONTEND_ENDPOINTS` 扩到 23+，核心链路全部在契约保护内；mock 样例单一 source
- 同意 key 收敛为单一枚举 + 迁移兼容

**非目标**：
- ChatRoom 语音编排抽取 → **ARCH-006**（doing/66）
- teacher-web/parent-h5 的契约防线与 authFetch → **ARCH-008**（doing/68）
- 协议格式本身变更（服务端 SSE 契约不变）

## 3. 设计方案

### 3.1 SSE 解析单点化

- **方案调整（实施时）**：不复用 `useSseStream().streamMessage()` hook（其 streaming 状态与 ChatRoom 联动，nudge 链路独立触发会污染 UI 状态），改为从 `useSseStream.ts` 导出**纯函数** `consumeSseStream(reader, handlers, onChunk?)` 作为唯一解析实现；`streamMessage` 与 `useSilenceNudge` 共同调用。`useSilenceNudge` 保持 `fetchWarmPrompt`（请求接缝）+ `consumeSseStream`（解析接缝）双接缝，互不耦合。
- 删除内联 `line.slice(...)` 第二实现；测试端 reader 工厂仍为各文件内联（集中抽取收益低，KISS 保留现状）

### 3.2 端点收敛到 api.ts

新增具名函数（均走 `authFetch`）：
```
fetchSystemConfig()        ← remote.ts L54
fetchLoginPrompt()         ← VoiceLoginOverlay.tsx L82
fetchTtsSynthesize(payload)← useTtsPlayer.ts L166
fetchVoiceAnalyze(audio)   ← ChatRoom.tsx L149
fetchWarmPrompt(sessionId) ← ARCH-002 已建（useSilenceNudge L72）
```
实施后 grep 裸 `fetch(` 必须归零（student-h5 源码面）。

### 3.3 契约防线补齐

- `FRONTEND_ENDPOINTS` 扩至 23+：补 messages/nudge/voice analyze/tts synthesize/tts login-prompt/system config/sessions close/chat sessions 等 8+ 端点
- mock 样例单一 source（doing/61 D-5：**集中手写定义**，KISS 优先于 openapi 派生）
- `toolboxApi.test.ts` 经审计确认非空壳，保留并纳入同一 mock source

### 3.4 同意 key 收敛（F-9）

- 定义单一枚举 `ConsentKeys { VOICE_CONSENT = 'mindsafe_voice_consent_v1', ... }`（统一 `_v1` 语义）
- 三处消费点引用枚举；旧 `_done` 键读兼容（首次读取迁移写入新键，旧键 TTL 后清理）

## 4. SPEC

```
模块：useSseStream（唯一 SSE 解析实现，导出 consumeSseStream 纯函数：reader + handlers + 可选 onChunk）
api.ts 新增：fetchSystemConfig(signal?) / fetchLoginPrompt(text, persona) / fetchTtsSynthesize(payload) / fetchVoiceAnalyze(formData)（+已有 fetchWarmPrompt）
契约：FRONTEND_ENDPOINTS = 24（15 + 9）；缺失端点补案；mock 单一 source（src/test/mockFixtures.ts）
同意：ConsentKeys 枚举（NOTICE/VOICE/VOICE_CALL，统一 _v1 语义）+ 旧键 mindsafe_consent_done 迁移兼容读
断言：grep -r "fetch(" src/（排除 api.ts）必须为零
```

## 5. 验收标准（EARS 风格）

- 当 `useSilenceNudge` 复用 `streamMessage()` 后，协议解析必须为单点实现（grep 无第二份 `line.slice(5)` / `data:` 手写解析）
- 当 5 个端点收进 api.ts 后，`FRONTEND_ENDPOINTS` 必须 ≥23，student-h5 源码裸 fetch 必须归零
- 当契约测试运行时，消息/nudge/voice/tts/config/sessions 端点必须全部在案
- 当同意 key 收敛后，三处消费点必须引用同一枚举，旧键读取必须兼容迁移（写入后无重复弹窗）
- 当 nudge 链路复用 SSE 解析后，emotion/risk 事件必须不再被静默丢弃（订阅可验证）
- 当 student-h5 全量测试运行时，现有用例必须全绿（含 useSseStream 独立测试基线）

## 6. 风险与回滚

- **风险**：低——纯收敛重构、行为不变；`useSseStream` 现有 8-9KB 独立测试可保底
- **依赖**：ARCH-002（P0-1 先修 401 缺陷，本任务避免在同一文件叠改动）
- **回滚**：逐文件 revert；契约扩充为纯增量

## 7. 关联与落点

- 关联任务：ARCH-002（doing/62）、ARCH-006（doing/66，依赖本任务完成后进行）
- 关联设计：design/16 API 接口设计、design/05 §8.6 契约防线、design/28 冷场引导（nudge 链路）
- 词汇表：[13 领域词汇表](../13_领域词汇表.md) 消息流域
- 登记：TASK-TRACKER §二十八 ARCH-005

---

## 8. 实施记录（2026-08-06，ARCH-005 TDD 完成）

### 8.1 审计断言 vs 代码事实核对

| 审计断言 | 代码事实 | 处置 |
|---------|---------|------|
| F-1 SSE 解析 3 遍 | useSseStream 唯一完整实现（L77-93）+ useSilenceNudge 第 2 遍内联（只消费 token）+ 测试 3 个内联 reader 工厂 | 抽 `consumeSseStream` 纯函数，streamMessage 与 useSilenceNudge 共用（测试 reader 工厂保留内联，KISS） |
| F-2 裸 fetch 5 处 | 实际 ChatRoom/useTtsPlayer 已 authFetch 化（ARCH-002 期间）；remote/VoiceLoginOverlay 2 处真裸 fetch；useSilenceNudge 已 fetchWarmPrompt | 2 处裸 fetch 收敛 + 2 处 authFetch 收编为具名函数，共新增 4 个具名接缝 |
| F-3 契约漏 8+ 端点 | 实际 9 个（含 useChatSession 的 sessions/end 与 close 两个历史遗留端点） | FRONTEND_ENDPOINTS 15 → 24，mock 抽 src/test/mockFixtures.ts 单一 source |
| F-9 三套 key 语义不一 | VoiceConsent/VoiceCallConsent 已 `_v1`；api.ts `mindsafe_consent_done` 为告知同意键 | ConsentKeys 枚举三键统一 + 旧键迁移兼容读（首次 isConsentDone 时写新键） |

### 8.2 方案调整（实施期 vs 方案稿）

1. **SSE 解析收敛方式**：方案稿为「复用 streamMessage() hook」；实施改为导出纯函数 `consumeSseStream(reader, handlers, onChunk?)`——nudge 链路复用 hook 会污染 ChatRoom 的 streaming 状态（UI 联动副作用），纯函数接缝与请求接缝（fetchWarmPrompt）解耦，行为等价且无状态泄漏。
2. **F-2 现状更正**：审计「5 处裸 fetch」不成立——ChatRoom L149（voice/analyze）/ useTtsPlayer L166（tts/synthesize）在 ARCH-002 期间已 authFetch 化，本任务只收敛 2 处真裸 fetch + 收编 2 处 authFetch 为具名函数。
3. **F-9 现状更正**：审计「版本语义不一」中两处语音同意键已是 `_v1`，仅 api.ts 告知同意键（`_done`）需要迁移；统一枚举后无行为变化。
4. **超时保护归属**：`consumeSseStream` 增加可选 `onChunk` 回调（每段数据回调），30s 超时判定仍由 streamMessage 持有（hook 职责），纯函数不感知超时。

### 8.3 TDD 执行与验收

**红（测试先行）**：useSseStream.test.ts +5（consumeSseStream：token 累积/emotion/risk/坏行/跨 chunk/流中断）；api.test.ts +8（ConsentKeys 枚举与迁移 4 + 端点函数 4）；apiContract.test.ts 端点 15→24 + mockFixtures.ts 新建——13 例失败确认红。

**绿（生产实现）**：useSseStream.ts 导出 `consumeSseStream`；api.ts 新增 ConsentKeys + fetchSystemConfig/fetchLoginPrompt/fetchTtsSynthesize/fetchVoiceAnalyze（均走 authFetch）；7 调用方改造：useSilenceNudge（解析段）、remote.ts（fetchSystemConfig + 3s 超时 signal）、VoiceLoginOverlay（fetchLoginPrompt）、ChatRoom（fetchVoiceAnalyze）、useTtsPlayer（fetchTtsSynthesize）、VoiceConsentDialog/VoiceCallConsentDialog（ConsentKeys 引用）——相关 6 文件 158 例全绿。

**测试同步**：useTtsPlayer.test.ts / ChatRoom.test.tsx mock 从 authFetch 改接缝函数（fetchTtsSynthesize / fetchVoiceAnalyze），5 处断言从「URL+init 两参」改「单参 payload/FormData」。

**回归与断言**：student-h5 全量 vitest 766 例全绿（56 文件）；grep 裸 `fetch(` 仅剩 api.ts 内部 9 处（authFetch/未登录公开端点），其余 src/ 零裸 fetch。

**验收标准核对**：单点解析 ✅（grep 无第二份 `line.slice(5)` 手写解析，useSilenceNudge 已复用 consumeSseStream）/ 端点归零 ✅ / 契约 24 在案 ✅ / 同意枚举 + 迁移 ✅ / emotion/risk 事件经 consumeSseStream 分发（nudge 侧空回调显式忽略，订阅可验证）✅ / 全量回归 ✅（766 例）。

⏱️ 时间戳 2026-08-06 01:33:16
