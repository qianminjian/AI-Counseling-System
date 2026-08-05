# 66 ChatRoom 语音编排抽取（ARCH-006）方案与 SPEC

> 关联任务：ARCH-006（深度审计 F-4 + P2-6/7/8 + OVD-5 回填，doing/61 C5 深化为可实施 SPEC，登记 TASK-TRACKER §二十八）
> 状态：📝 方案定稿 → 待实施（依赖 ARCH-005 完成后进行，doing/61 建议 C5 排 C4 之后）
> 依据：深度审计 2026-08-05（F-4：715 行神组件 / 18 个 vi.mock / 白盒回调捕获；P2-6/7/8 单例复制与去重双算法；OVD-5 boBoPet 双实例化）、doing/61 §8 C5（断言已按审计修正）
> 词汇：神组件 / 局域性 / 黑盒测试——见 [13 领域词汇表](../13_领域词汇表.md)

---

## 1. 背景与问题

**F-4 · ChatRoom 神组件**：`ChatRoom.tsx` 715 行 / 13+ hooks / 15 state·ref；语音输入编排（录音 → voice/analyze → 自动发送）整链内联；`SpeechRecognition` 状态机 + 按住说话三指针 + bobo 状态优先级派生 + boBoPet 双实例化。测试 `ChatRoom.test.tsx` 548 行 / **18 个 vi.mock**（审计修正 doing/61「15 个」断言）/ 白盒回调捕获（capturedRecordingCallback 捕获组件内部回调）——每加一个 hook 就加一块 mock，重构阻力最大。

**P2-6/7/8 · 单例与偏好收敛**：
- `useWakeWord` / `useVoiceprint` 单例 store 逐字重复
- 两套去重算法（`deduplicateText` 与 Android 侧去重）
- `useWakeEnabled` 半接入：`EmotionSelect` 重新声明同一 localStorage key `mindsafe_wake_enabled`（无 try/catch，失败安全已由 ARCH-002 修复）；wakeEnabled 3 个写入点

**OVD-5 · boBoPet 双实例化**：Pad 左栏 + 手机悬浮各自独立 SVG 动画。

## 2. 目标与非目标

**目标**：
- 录音→分析→自动发送整链抽为 `useVoiceInputPipeline` hook，ChatRoom 退化为装配层
- 测试从白盒回调捕获改为 hook 级黑盒，ChatRoom.test 体积减半
- 单例收敛：useWakeWord/useVoiceprint 去重、去重算法归一、wakeEnabled 接入统一 hook（含 EmotionSelect）
- boBoPet 双实例化评估并收敛

**非目标**（doing/61 D-4 决策）：
- bobo 表情状态机抽取（与动效预算 TTSFX-004 相关，另议）
- 表情状态机/动效层重构（非结构摩擦）
- UI 视觉重构

## 3. 设计方案

### 3.1 useVoiceInputPipeline（核心）

```
frontend/student-h5/src/hooks/useVoiceInputPipeline.ts
输入：sessionId、onTranscription(text)（发送回调）
状态机：IDLE → RECORDING → ANALYZING → SENDING → IDLE（+ERROR/TIMEOUT 分支）
封装：SpeechRecognition 生命周期 + 按住说话三指针 + 录音数据 → fetchVoiceAnalyze（ARCH-005 已建 api 函数）→ 自动发送 → 去重（deduplicateText 归内）
输出：{ isRecording, isAnalyzing, start(), stop(), error }
```

- ChatRoom 仅保留装配：绑定按钮事件 + 渲染状态 UI
- 删除组件内录音/分析/发送链（约 150-200 行收敛）
- 去重算法归一：`useChatSession.deduplicateText`（已存在纯函数）为唯一实现，Android 侧去重评估对齐（跨端一致性）

### 3.2 测试黑盒化

- `useVoiceInputPipeline.test.ts`：hook 级黑盒（renderHook + 假 SpeechRecognition/假 fetch），覆盖状态机全分支（含超时/错误）
- `ChatRoom.test.tsx`：mock 数从 18 降至 ≤8（仅 mock 外部依赖，不再捕获内部回调）；白盒 capturedRecordingCallback 删除

### 3.3 单例与偏好收敛

- `useWakeWord` / `useVoiceprint`：抽取共享 store 基座（或确认其一为宿主、另一复用——实施时按引用面最小者定，**不做新抽象框架**）
- `EmotionSelect` 接入 `useWakeEnabled`（ARCH-002 已加失败安全，本任务消除第二份 key 读写）；wakeEnabled 3 写入点收敛为 1（统一走 useWakeEnabled 提供的方法）
- 同意 key 统一归 ARCH-005（ConsentKeys），本任务不重复处理

### 3.4 boBoPet 双实例化（OVD-5）

- 评估：单实例 + 变体（Pad 左栏静态、手机悬浮动画）vs 维持双实例
- 建议：**评估后定**——若两处动画差异大（不同断点/动效预算），维持双实例并抽取共享 SVG 渲染函数即可，不强行单例（避免为收敛而收敛）

## 4. SPEC

```
hook：useVoiceInputPipeline（见 3.1 状态机）
去重：deduplicateText 为唯一实现；Android 侧调用或映射同一逻辑
偏好：useWakeEnabled 为 wakeEnabled 唯一读写点；EmotionSelect 接入
测试：pipeline hook 黑盒测试 ≥ 状态机全分支；ChatRoom.test mock ≤8 且无白盒回调捕获
boBoPet：评估结论落 design/37 或本任务记录（共享渲染函数 or 维持双实例）
```

## 5. 验收标准（EARS 风格）

- 当 `useVoiceInputPipeline` 建立后，ChatRoom 内必须不存在录音/分析/发送链内联实现（grep 断言：`voice/analyze` 调用仅存在于 hook 内）
- 当测试改造完成后，`ChatRoom.test.tsx` 的 vi.mock 数量必须 ≤8，白盒回调捕获必须为零
- 当 pipeline hook 测试运行时，状态机全分支（正常/超时/识别错误/分析失败）必须覆盖且全绿
- 当偏好收敛后，`mindsafe_wake_enabled` 的读写点必须唯一（grep 断言），EmotionSelect 不得再声明该 key
- 当去重归一后，文本去重逻辑必须为单点实现
- 当全量回归运行时，student-h5 测试必须全绿，主对话冒烟（录音→发送→回复）必须通过

## 6. 风险与回滚

- **风险**：中——ChatRoom 是主路由核心组件；现有白盒测试需同步重写（测试先行：先写 hook 黑盒测试，再改组件）
- **依赖**：ARCH-005（fetchVoiceAnalyze 具名函数先行，本任务不引入新裸调用）
- **回滚**：hook 抽取与测试重写分提交；boBoPet 评估不涉及代码回滚

## 7. 关联与落点

- 关联任务：ARCH-002（doing/62，EmotionSelect 失败安全先修）、ARCH-005（doing/65，API 函数先建）
- 关联设计：design/55 学生端全感官交互、design/37 情感化 TTS（boBoPet）、design/28 语音唤醒
- 词汇表：[13 领域词汇表](../13_领域词汇表.md)
- 登记：TASK-TRACKER §二十八 ARCH-006
