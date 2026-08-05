# 65 前端 API/SSE 接缝收敛（ARCH-005）方案与 SPEC

> 关联任务：ARCH-005（深度审计 F-1/F-2/F-3/F-9 回填，doing/61 C4 深化为可实施 SPEC，登记 TASK-TRACKER §二十八）
> 状态：📝 方案定稿 → 待实施（依赖 ARCH-002 先落地 P0-1 最小修复）
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
| `useSilenceNudge.ts` L72 | nudge | 裸 fetch + 手拼 Authorization，**绕过 401 刷新**（P0-1，ARCH-002 先修） |
| `config/remote.ts` L54 | system/config | 裸 fetch |
| `VoiceLoginOverlay.tsx` L82 | tts/login-prompt | 裸 fetch |
| `ChatRoom.tsx` L149 | voice/analyze | 裸 fetch |
| `useTtsPlayer.ts` L166 | tts/synthesize | 裸 fetch |

**F-3 · 契约防线漏 8+ 核心端点**：`apiContract.test.ts` L39-55 `FRONTEND_ENDPOINTS` 仅 15 个，消息/nudge/voice 分析/tts synthesize/tts login-prompt/system config/sessions close+end/chat sessions 8 个核心端点全在案外——**最核心路径最不受保护**。mock 样例三处手写重复。

**F-9 · 三类同意三套 key、版本语义不一**：`api.ts` L46（`_v1`）/ `VoiceConsentDialog.tsx` L3 / `VoiceCallConsentDialog.tsx` L4（`_done`）——同一「已同意」语义三套键名。

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

- `useSilenceNudge` 的请求与解析段改为复用 `useSseStream().streamMessage()`（其本就只消费 token 类型；emotion/risk 事件按需订阅）
- 删除内联 `line.slice(...)` 第二实现；测试内联 reader 改为引用 `useSseStream` 导出的解析工具（或测试辅助工厂集中定义）
- **注意**：ARCH-002（P0-1）先行将裸 fetch 换为 `fetchWarmPrompt`；本任务在其基础上完成解析复用，两步互不冲突（P0-1 保行为，本任务收敛结构）

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
模块：useSseStream（唯一 SSE 解析实现，导出 parseSseChunk 测试工具）
api.ts 新增：fetchSystemConfig / fetchLoginPrompt / fetchTtsSynthesize / fetchVoiceAnalyze（+已有 fetchWarmPrompt）
契约：FRONTEND_ENDPOINTS ≥ 23；缺失端点补案；mock 工厂单一文件（test/ 下集中定义）
同意：ConsentKeys 枚举 + 迁移兼容读
断言：grep -r "fetch(" src/（排除 api.ts 与 hooks/useSseStream.ts）必须为零
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
