# 62 P0 前端缺陷修复（ARCH-002）方案与 SPEC

> 关联任务：ARCH-002（深度审计 P0 回填，登记 TASK-TRACKER §二十八）
> 状态：📝 方案定稿 → 待实施（已生产上线后第一迭代强制回填）
> 依据：深度审计 2026-08-05（P0-1/P0-2，三域并行只读审计）、doing/61 C4/C5 相关接缝分析
> 词汇：接缝 / 局域性 / 失败安全——见 [13 领域词汇表](../13_领域词汇表.md) 架构词汇表

---

## 1. 背景与问题

生产已上线（DEPLOY-009）。审计发现 2 项 P0 前端缺陷，均为**真实运行缺陷**而非结构摩擦，按审计规则不允许延后：

| ID | 缺陷 | 证据 | 影响 |
|----|------|------|------|
| P0-1 | `useSilenceNudge` 裸 fetch + 手拼 `Authorization`，绕过 api.ts 的 `authFetch` 401 自动刷新 | `frontend/student-h5/src/hooks/useSilenceNudge.ts` L72-79 | token 过期后暖场请求静默 401，冷场引导 25s+ 空转且无任何重试/提示 |
| P0-2 | `EmotionSelect` 直读 `localStorage` 无失败安全 | `frontend/student-h5/src/components/EmotionSelect.tsx` L32（对比 `useWakeEnabled.ts` L15-21 有 try/catch） | 隐私模式/禁用存储 → `SecurityError` 未捕获 → 白屏，阻断整个情绪选择页 |

**判定**：P0-1 属于「已存在统一接缝（api.ts/authFetch）却被绕过」，是 5 处裸 fetch 中唯一绕过 401 刷新的一处；P0-2 是「读取路径无防御」的典型 Web 存储缺陷。两项修复面均极小、无行为变更。

## 2. 目标与非目标

**目标**：
- 暖场请求纳入统一认证接缝：token 过期自动刷新并重放，不静默失败
- 情绪选择页在任何存储不可用场景下可渲染（默认值回退），不白屏
- 修复以最小改动落地，不引入新抽象

**非目标**（明确推迟到后续任务，避免范围膨胀）：
- `useSilenceNudge` 复用 `useSseStream().streamMessage()` 的 SSE 解析收敛 → **ARCH-005**（doing/65）
- `EmotionSelect` 接入 `useWakeEnabled` 消除第二份偏好读写 → **ARCH-006**（doing/66）
- 其余 4 处裸 fetch（remote.ts/VoiceLoginOverlay/ChatRoom/useTtsPlayer）→ **ARCH-005**

## 3. 设计方案

### 3.1 P0-1 · 暖场请求走统一认证接缝

**方案**：在 `api.ts` 新增具名函数 `fetchWarmPrompt(sessionId)`（POST nudge 端点，走现有 `authFetch` 封装），`useSilenceNudge` 的裸 fetch 段替换为调用该函数。`authFetch` 已实现 401 刷新+重放，无需改动。

- 不改 SSE 解析方式（保持当前 token 事件解析），解析收敛归 ARCH-005
- 错误处理：401 刷新失败时按现有降级逻辑处理（暖场静默跳过），但不产生未捕获异常

**删除测试**：删掉裸 fetch 段是「集中复杂度」（调用点并入既有接缝），方向正确；不留转发层。

### 3.2 P0-2 · 存储访问失败安全

**方案**：抽出私有工具函数 `readLocalStorageSafe(key, fallback)` / `writeLocalStorageSafe(key, value)`（try/catch 包裹，异常时返回 fallback/静默），置于 `EmotionSelect.tsx` 所在 utils（或 student-h5 已有 `storage` 工具位，按现状就近放置，不新建目录）。

- 读取失败 → 使用默认值（未选/关闭），页面正常渲染
- 写入失败 → 静默跳过（偏好不持久化不影响功能）
- 与 `useWakeEnabled` 的 try/catch 模式一致（该 hook 已是本项目失败安全样板）

## 4. SPEC

### 4.1 P0-1

```
函数：fetchWarmPrompt(sessionId: string): Promise<WarmPromptResponse>
位置：frontend/student-h5/src/api.ts（复用 authFetch）
行为：POST /api/v1/.../nudge（按 FRONTEND_ENDPOINTS 现有端点定义），
     401 时经 authFetch 自动刷新并重放，最终失败抛可捕获错误
使用点：useSilenceNudge.ts 替换 L72-79 裸 fetch 段
```

### 4.2 P0-2

```
工具：readLocalStorageSafe<T>(key: string, fallback: T): T
     writeLocalStorageSafe<T>(key: string, value: T): void
位置：EmotionSelect.tsx 同目录或既有 utils（不新建模块）
行为：JSON.parse 失败/存储不可用 → 返回 fallback；写入异常 → 静默
使用点：EmotionSelect.tsx L32/L114-118 及同模式所有直读点
```

## 5. 验收标准（EARS 风格）

**P0-1**：
- 当 token 过期时，暖场请求必须触发 401 刷新并在刷新成功后重放，不产生未捕获异常
- 当刷新失败时，暖场必须按现有降级路径静默跳过（不阻塞主对话）
- 当实施完成后，`useSilenceNudge.ts` 中必须不存在裸 `fetch(` 调用（grep 断言）
- 当 student-h5 全量测试运行时，现有用例必须全绿（本任务不新增失败）

**P0-2**：
- 当 localStorage 抛出 `SecurityError`（隐私模式/禁用）时，情绪选择页必须正常渲染且使用默认值
- 当存储不可用时，用户切换情绪必须不崩溃（写入静默失败）
- 当实施完成后，`EmotionSelect.tsx` 必须不存在未包裹 try/catch 的直接 `localStorage` 读写

## 6. 风险与回滚

- **风险**：极低——两项均为局部替换，无接口签名变更（api.ts 为新增函数）
- **回归面**：student-h5 全量测试 + 暖场/情绪选择手工冒烟
- **回滚**：单文件 revert 即可，无数据面

## 7. 关联与落点

- 关联任务：ARCH-005（doing/65，SSE 复用 + 其余裸 fetch）、ARCH-006（doing/66，wakeEnabled 收敛）
- 关联设计：design/28 冷场引导、design/55 学生端全感官交互（情绪选择）
- 词汇表：[13 领域词汇表](../13_领域词汇表.md)
- 登记：TASK-TRACKER §二十八 ARCH-002
