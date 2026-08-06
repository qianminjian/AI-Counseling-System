# 66 ChatRoom 语音编排抽取（ARCH-006）方案与 SPEC

> 关联任务：ARCH-006（深度审计 F-4 + P2-6/7/8 + OVD-5 回填，doing/61 C5 深化为可实施 SPEC，登记 TASK-TRACKER §二十八）
> 状态：✅ 已完成（2026-07-28 07:10，TDD 红→绿→收敛→全量回归 788 例全绿）
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

---

## 8. 实施记录（TDD 完成 2026-07-28）

### 8.1 交付物

| 文件 | 动作 | 说明 |
|------|------|------|
| `src/hooks/useVoiceInputPipeline.ts` | 新建 183 行 | 录音→分析→自动发送整链（状态机全分支，18 例黑盒测试） |
| `src/test/useVoiceInputPipeline.test.ts` | 新建 303 行 | hook 级黑盒：假 SpeechRecognition + 假 fetch + fake timers |
| `src/test/ChatRoom.test.tsx` | 重写 | mock 18→8（保留外部依赖 mock，白盒回调捕获删除），38 例全绿 |
| `src/components/ChatRoom.tsx` | 改造 613 行 | 删内联语音链（handleRecordingComplete/30s timer/startVoiceSession/4 refs），退化为装配层 |
| `src/utils/modelStatusStore.ts` | 新建 | createModelStatusStore() 工厂（useWakeWord/useVoiceprint 共享基座） |
| `src/test/setup.ts` | 增强 | jsdom PointerEvent polyfill（jsdom 26 未实现，坐标 init 丢失） |
| `src/components/EmotionSelect.tsx` | 收敛 | 接入 useWakeEnabled，删第二份 `mindsafe_wake_enabled` 读写 |
| `src/hooks/useWakeWord.ts` / `useVoiceprint.ts` | 收敛 | 单例 store 替换为工厂实例（-51 行重复实现） |

### 8.2 方案调整（测试先行暴露，均落码）

1. **transcript 展示拼接顺序修正**：旧实现 `finalTranscript + interimTranscript` 在“先 interim 后 final”场景顺序颠倒（'很开心我今天'）；新实现按 `results` 出现顺序拼接 + 连续 final 去重（displayTranscript）。测试先行暴露的旧实现缺陷。
2. **pipeline 接口终态**：`useVoiceInputPipeline({ onTranscription })` → `{ isRecording, isAnalyzing, isSending, supported, error, liveTranscript, warmUp, releaseStream, start, stop, cancel }`；isRecording/isAnalyzing/supported/warmUp/releaseStream 直接透传 useAudioRecorder（单一事实源），error 为提示文案由消费方定时清空，过短判定（<1000ms）进 pipeline 内。
3. **ChatRoom.test.tsx 真实化范围**：10 个组件真实化（ThemeProvider wrapper/useVoicePersona/授权弹窗×2/SatisfactionDialog/ConfirmDialog/DraggableVoiceButton/MessageBubble/ToolboxPanel/SosPanel），授权弹窗改操作 localStorage 的真实链路（pointerDown 触发 voice 授权、800ms 自动弹唤醒授权）。
4. **jsdom PointerEvent 缺失**：jsdom 26 无 PointerEvent → testing-library fallback 到 Event 构造器丢失 clientX/clientY/pointerId（按住说话上滑判定依赖 clientY）；setup.ts 全局安装 PointerEvent polyfill 解决。
5. **deduplicateText 单点确认**：`useChatSession.deduplicateText`（导出纯函数）即唯一实现；pipeline 内 final 去重（跳过连续相同条目）是展示层逻辑与首尾重复检测不同，不重复收敛。
6. **modelStatusStore 工厂**：两 hook 原各实现一套相同 useSyncExternalStore 外部 store（status/progress/error + Set 订阅 + 快照缓存），抽 `createModelStatusStore()` 基座，行为零差异。

### 8.3 boBoPet 双实例评估（OVD-5 落档）

维持双实例 + 共享工厂（不强行单例）：
- ChatRoom `const boBoPet = (size, bubbleAlign) => <BoBoPet .../>` 工厂已共享渲染 props；Pad 左栏（170px 静态展示）与手机悬浮（60px，DraggableVoiceButton 内部 render-prop 调用）断点不同、动效预算不同；
- 动画差异大（不同断点/动效预算），符合 doing/61 D-4「不强行单例」决策。

### 8.4 验收对照

- ✅ ChatRoom 内无 `voice/analyze` 内联调用（grep 零匹配，fetchVoiceAnalyze 仅存在于 pipeline）
- ✅ ChatRoom.test.tsx vi.mock = 8（useTtsPlayer/useVoiceCallMode/useSilenceNudge/useWakeWord/useVoiceInputPipeline/api/BoBoPet/SettingsPanel），白盒回调捕获为零
- ✅ pipeline 状态机全分支覆盖：正常/30s 超时/过短/识别错误/分析失败/降级/无转写 18 例
- ✅ `mindsafe_wake_enabled` 读写点唯一（useWakeEnabled），EmotionSelect 不再声明该 key
- ✅ 去重逻辑单点（useChatSession.deduplicateText）
- ✅ 全量回归 788 例全绿（57 文件，2026-07-28 07:07）
