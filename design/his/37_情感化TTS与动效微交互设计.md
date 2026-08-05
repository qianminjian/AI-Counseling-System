# 37 - 情感化TTS与动效微交互设计

> 创建：2026-07-28 | 状态：🟡 后端已实施（TTSFX-001/002/003 接线完成，情绪信号源统一入编排 + VoiceDegradationPolicy 风险降级已接 TtsController）；前端动效资产（Lottie/粒子/触觉）未在 student-h5 落地 | 对应规划任务：PROD-003 + UX-005（design/30）| 2026-07-28 冻结区审计校正
>
> 定位：对 design/55（学生端全感官交互设计方案）的**工程化深化与补白**——55 已定义音色人设、情绪→instruct 映射、指令工程规范；本文档补齐其明确留白的部分（语音内容安全、风险场景降级、离线备选），并新增动效微交互体系（Lottie + 粒子 + 触觉），两者合并为一套“情感表达层”设计。不重复 55 已定内容。

---

## 一、业界对标（避免空想的依据）

### 1.1 情感化 TTS 工程实践

| 实践 | 来源 | 借鉴决策 |
|------|------|---------|
| 情绪标签由**上游对话模型决定，TTS 只执行**，不让 TTS 自行推断情绪 | 主流语音助手（Alexa emotions / 天猫精灵）的分层架构 | 采纳：情绪标签在 AI 回复生成时一并输出（现有 emotion 字段），tts-service 只映射 instruct，职责单一 |
| **情绪强度要克制**：儿童场景过度戏剧化的语音会显得"假"，降低信任 | 儿童语音产品（Yoto/Toniebox 等无屏音频产品的编辑准则） | 情绪 instruct 只用"轻微/温和"修饰词，禁用"非常激动""大哭"级别表达 |
| 首包延迟 > 1.5s 用户会认为"没听到" → 流式或分句合成 | 语音交互体感研究通用阈值 | docs/17 已有句子切分策略，本文档补充首句优先合成 + 边播边合成流水线 |
| 合成缓存：高频固定话术预合成 | 所有量产语音产品 | 固定话术（开场/冷场/危机/工具引导）构建期预合成为静态音频，运行时只合成动态内容 |

### 1.2 儿童动效设计准则

| 准则 | 来源 | 借鉴决策 |
|------|------|---------|
| **动效要"回应"而非"表演"**：每个动效必须由用户行为或系统状态触发，无意义循环动画会分散注意力 | Material Design Motion / 儿童教育 App（Khan Academy Kids、Duolingo ABC）实践 | 动效清单全部绑定触发器（§四），禁止装饰性常驻动画（波波呼吸感 idle 除外） |
| **奖励动效克制使用**，避免可变奖励成瘾模式 | 儿童数字产品伦理准则（Designed for Childhood / 5Rights） | 粒子庆祝只在成就达成时播放，不做随机奖励；单次 ≤2s |
| `prefers-reduced-motion` 必须尊重 | WCAG 2.1 / 前庭障碍可访问性 | 全局动效开关 + 跟随系统设置，降级为淡入淡出 |
| Lottie 性能：单动画 ≤150KB、避免同屏 >2 个复杂动画、优先 dotLottie | Lottie 官方性能指南 | 资源预算见 §四.4 |
| 情绪同步：宠物/角色表情与对话情绪一致可显著提升儿童共情与留存 | 陪伴型产品（Replika/Woebot avatar）通用做法 | 波波表情状态机与 TTS 情绪标签共用同一信号源（§三.1） |

---

## 二、现状与差距

### 2.1 已有资产

| 资产 | 状态 |
|------|------|
| tts-service（FastAPI :10096） | CosyVoice2 instruct 主 + edge-tts 降级；4 音色人设；6 情绪 instruct 映射；≤500 字限制 |
| docs/17 | 音色人设、情绪→参数映射、指令工程规范、语速年龄适配（180-280 字/分）、句子切分、儿童语音 UX 10 原则 |
| BoBoPet.jsx | 宠物基础动画状态 |
| SettingsPanel.jsx | 音色选择 |

### 2.2 docs/17 明确留白（本文档补齐）

1. 语音内容安全审查细节（§三.3）
2. 极端风险场景语音降级（§三.2）
3. 离线模式 TTS 备选（§三.4）
4. 动效微交互体系（docs/17 未涉及，§四）
5. 性能与延迟工程（§三.5）

（儿童声纹克隆、方言支持维持 docs/17 的 M3+ 定位，本文档不展开。）

---

## 三、情感化 TTS 深化

### 3.1 统一情绪信号源（单一事实源原则）

AI 回复生成时输出结构化情绪标签（现有 6 类：happy/gentle/encourage/calm/serious/soothe），**同一标签同时驱动三个消费方**：

```
AI 回复 {text, emotion, riskLevel}
   ├→ tts-service：emotion → EMOTION_INSTRUCTS（已有）
   ├→ BoBoPet：emotion → 表情状态机（新增映射，§四.1）
   └→ 气泡/背景微动效：emotion → 主题微调（§四.2）
```

> 设计决策：不允许前端各自根据文本猜情绪——三方不一致（语音温柔、表情大笑）比没有情绪更伤体验。

### 3.2 风险场景语音降级（补 docs/17 留白）

| 风险等级 | 语音策略 | 理由 |
|---------|---------|------|
| S3/S2 | 正常合成，emotion 强制归入 `soothe/calm`，语速降至该年龄段下限 | 情绪安抚基调 |
| S1 | **禁用动态合成**，改用预合成安抚话术音频库（构建期由心理顾问审定文本后生成） | 危机话术不允许 TTS 发挥出错；预合成 = 零延迟 + 零合成事故 |
| S0 | 同 S1 预合成库 + 后续界面转热线卡片后**不再播放任何语音** | 把注意力交给真人求助通道，语音继续说话会干扰拨号 |
| 合成失败/超时（任意等级） | 降级链：CosyVoice2 → edge-tts（已有）→ **纯文字 + 波波抱抱动画** | 最终兜底必须无声可用 |

### 3.3 语音内容安全审查（补 docs/17 留白）

分层职责，**不在 tts-service 里做内容审查**（它只是执行器）：

1. **上游已审**：进入 TTS 的文本必然已过 safety 包审查（AI 回复本身的安全链路），TTS 不重复审
2. **TTS 侧只做技术校验**：长度 ≤500 字（已有）、剔除不可读符号/URL/emoji（emoji 转空或转语气词）、SSML/instruct 注入防护——**用户可控内容永不进入 instruct 字段**（instruct 只来自服务端 EMOTION_INSTRUCTS 白名单枚举，杜绝"prompt 注入到语音指令"）
3. **音频缓存键**：`hash(text+persona+emotion+speed)`，同文重复请求直接命中缓存（Redis/对象存储），既是性能优化也保证同文同音的一致性

### 3.4 离线/弱网 TTS 备选（补 docs/17 留白）

与 design/36 离线设计对齐：

- **不做端侧神经 TTS**（Web 端模型体积与 CPU 占用对低端教室平板不现实，且音色不一致伤害人设）
- 离线可听内容 = 预合成静态音频：工具箱引导音频（design/36 已定）+ 固定陪伴话术包（约 30 句 × 4 音色，CacheFirst 预缓存，估算 <8MB）
- 对话动态回复离线时降级纯文字（design/36 §4.1 已定边界），波波用文字气泡 + 动画表达情绪

### 3.5 延迟工程（体感优化）

```
目标：从 AI 首句文本就绪 → 学生听到声音 ≤ 1.5s（P90）

流水线：
AI 流式输出 → 首句完整即送 TTS（不等全文）→ 首句音频返回即播
             → 后续句子并行合成，按序入播放队列
             → 播放间隙 < 300ms（队列预取）
```

- tts-service 增加并发合成队列参数与句级请求（前端已有切分策略，服务不改协议只调用多次）
- 预合成库（开场/冷场/危机/工具）零延迟直播
- 监控指标：`tts_first_audio_ms`（P50/P90）、`tts_fallback_rate`、缓存命中率，接入现有 metrics 包 + Prometheus

---

## 四、动效微交互体系（UX-005）

### 4.1 波波表情状态机（情绪同步核心）

| emotion / 事件 | 波波状态 | 动效资产 |
|----------------|---------|---------|
| happy | 开心弹跳 + 眼睛弯弯 | Lottie `bobo_happy` |
| gentle / calm | 轻轻点头 + 缓慢眨眼 | Lottie `bobo_gentle` |
| encourage | 举手加油 | Lottie `bobo_cheer` |
| soothe / serious | 靠近 + 抱抱姿态 | Lottie `bobo_hug` |
| 学生输入中 | 侧耳倾听 | Lottie `bobo_listen` |
| AI 思考中 | 歪头冒泡泡（替代冷冰冰的 loading） | Lottie `bobo_think` |
| 离线 | 打盹 | Lottie `bobo_sleep`（design/36 已引用） |
| idle（无事件 30s+） | 呼吸感微动（唯一允许的常驻动画） | CSS transform，非 Lottie |

状态机规则：事件驱动、可打断、同刻仅一个状态；S0/S1 界面锁定 `bobo_hug`，不随后续消息切换。

### 4.2 微交互清单（全部绑定触发器）

| 触发 | 动效 | 时长 | 技术 |
|------|------|------|------|
| 发送消息 | 气泡从输入框飞入列表 | 250ms | CSS transform/opacity |
| AI 回复到达 | 气泡淡入 + 波波表情切换 | 300ms | CSS + Lottie |
| 语音播放中 | 气泡旁声波律动条 | 跟随音频 | Canvas/CSS，跟 audio currentTime |
| 成就达成 | 星星粒子迸发 + 徽章弹出 | ≤2s，一次性 | canvas-confetti（轻量）|
| 工具练习完成 | 波波鼓掌 + 柔和光晕 | 1.5s | Lottie |
| 呼吸练习 | 圆环缩放引导（吸气扩/呼气缩） | 跟随节奏 | CSS animation，精确计时 |
| 长按语音输入 | 麦克风水波纹 | 持续 | CSS |
| 下拉/页面切换 | 波波耳朵拉伸彩蛋 | 400ms | Lottie 片段 |

**禁做清单**：随机弹出奖励、无限循环装饰粒子、抖动/闪烁类动效（癫痫风险，WCAG 2.3.1：任何内容闪烁 <3 次/秒）、模态强打断动画。

### 4.3 可访问性与设置

- 设置面板新增"动画效果"开关（跟随系统 `prefers-reduced-motion` 默认值，可手动覆盖）
- 降级模式：所有 Lottie 换静态首帧图，过渡动效换 150ms 淡入淡出，粒子禁用；呼吸练习保留（它是功能不是装饰）但改为数字倒计时 + 渐变
- 触觉反馈（振动）：仅"成就达成"“呼吸节拍"两处，`navigator.vibrate` 渐进增强（iOS Safari 不支持则静默跳过），设置内可关

### 4.4 性能预算（低端教室平板为基线）

| 项 | 预算 |
|----|------|
| 单个 Lottie 资产 | ≤150KB（dotLottie 压缩） |
| 同屏 Lottie 实例 | ≤2（波波 + 至多 1 个附加） |
| 动效帧率底线 | 30fps；掉帧检测（rAF 采样）连续 <24fps 自动切降级模式 |
| 动效资产总包 | ≤1.5MB，纳入 Workbox precache（离线可用，与 design/36 对齐） |
| 粒子库 | canvas-confetti（~5KB gzip），不引入完整粒子引擎 |

---

## 五、API 与配置增量

| 项 | 说明 |
|----|------|
| tts-service `POST /synthesize` | 不改协议；新增响应头 `X-TTS-Engine`（cosyvoice/edge/cache）供前端埋点降级率 |
| tts-service 预合成 CLI | 构建期脚本：输入审定话术 CSV（text, scene, emotion）× 4 persona → 输出静态音频目录（工具箱 + 危机 + 冷场话术包） |
| 前端 `emotionBus` | 单例事件源：AI 回复的 emotion 分发给 TTS 播放器 / BoBoPet / 主题层（§三.1 落地） |
| 设置项 | `animationEnabled`、`hapticsEnabled` 存本地 + 用户偏好接口（沿用 SettingsPanel 既有模式） |

---

## 六、实施里程碑与验收

| 阶段 | 范围 | 验收标准（EARS 摘要） |
|------|------|---------------------|
| M1 | 情绪信号源统一 + 波波表情状态机 + 基础微交互（气泡/输入/思考中） | WHEN AI 回复 emotion=soothe THEN 波波 SHALL 在语音开播前切换到 hug 状态；WHEN 系统开启减弱动效 THEN 所有 Lottie SHALL 呈现静态首帧 |
| M2 | 风险语音降级 + 预合成话术库 + 缓存 | WHEN 触发 S1 THEN 播放的音频 SHALL 来自预合成库（日志可证）；WHEN CosyVoice2 超时 THEN SHALL 在 2s 内切 edge-tts 或纯文字 |
| M3 | 延迟流水线 + 成就粒子 + 触觉 + 性能自动降级 | WHEN AI 首句就绪 THEN 首音频播放延迟 SHALL P90 ≤1.5s；WHEN 帧率连续低于 24fps THEN SHALL 自动进入降级模式 |

**测试要点**：三消费方情绪一致性快照用例；instruct 注入尝试用例（用户文本含"用愤怒的语气"必须不影响 instruct）；S0 流程语音静默验证；低端安卓平板真机帧率实测；`prefers-reduced-motion` 双态截图对比。

---

## 附：与其他设计文档的关系

- design/55：本文档的母文档——人设/情绪映射/指令工程/切分策略以其为准，本文档只补白不覆盖
- design/04 / design/14：S0-S1 危机话术文本来源（预合成库的内容源，需心理顾问审定）
- design/36：离线音频缓存与波波打盹状态的共享设计
- design/27：波波品牌形象与动效资产风格约束
- design/28：冷场话术预合成包的文本来源

---

## 落地记录（2026-07-28 冻结区审计补录）

### TTSFX-001/002/003（均已于 2026-07-28 接线）

| 里程碑 | 落地方式 | 审计核实 |
|--------|----------|---------|
| M2 风险语音降级 | `tts/VoiceDegradationPolicy.java`（S1 预合成/S0 静默/超时降级链）已接 `TtsController`；配套 `TtsPipelineScheduler`/`VoiceEffectivenessTracker` | ✅ 后端已接线（有单测） |
| M1 情绪信号源统一 | AI 回复 emotion 单一信号源入编排（接 design/44 StrategyProfile），三方同源契约成立 | ✅ 后端契约已立 |
| 预合成话术库 | 与 TMATCH-002（design/48）预合成矩阵统一 | ✅ |

### 未实施余量（审计发现，需重新排期）

- **前端动效资产未落地**：student-h5 无 Lottie 依赖与 `bobo_*` 资产，`BoBoPet.tsx` 未承接 emotion 表情状态机（§4.1 目标态未达成）；微交互清单（§4.2）、减弱动效降级（§4.3）、成就粒子/触觉（M3）均属前端未实施
- 预合成 CLI 与构建期话术包产出流程待验证（离线可听内容依赖）
- 结论：TASK-TRACKER TTSFX-001/003 标✅以后端信号源与降级链路为准；前端 UX 层应视为 ⏳ 待实施，解冻时优先补波波表情状态机（情绪同步是体验核心）

### TTSFX-004 实施记录（2026-07-28，TDD）

| 层 | 落地 | 测试 |
|----|------|------|
| 后端回复情绪推导 | `counseling-ai` 新增 `ReplyEmotionResolver`：StrategyProfile → 六类回复情绪（serious/soothe/calm/happy/encourage/gentle）纯规则零 LLM，null 档案兜底 gentle | `ReplyEmotionResolverTest` 10 用例 |
| SSE 情绪事件下发 | `ConversationServiceImpl.sendMessageStream` 在 token 流前发射 `emotion` 事件（M1“语音开播前切换表情”） | `ConversationServiceImplTest` +2 契约用例（序列/标签） |
| 前端单一信号源 | `utils/emotionBus.ts`：6 类标签白名单归一化 + 订阅失败隔离；ChatRoom SSE 解析接 `emotion`/`risk` 事件 | `emotionBus.test.ts` 8 用例 |
| 表情状态机 | `utils/boboExpressions.ts` 纯 reducer + `hooks/useBoboExpression`；S0/S1（riskLevel≥2）锁定 hug，risk-cleared 解锁 | `boboExpressions.test.ts` 17 + `useBoboExpression.test.ts` 8 用例 |
| 动效降级 | `utils/motionPreference.ts` + `hooks/useMotionPreference`：默认跟随系统减弱动效、设置面板可覆盖（§4.3）；rAF 采样连续 <24fps 自动降级（§4.4）；触觉门禁接入 BoBoPet vibrate | `motionPreference.test.ts` 13 + `useMotionPreference.test.ts` 4 + `BoBoPetHaptics.test.tsx` 2 用例 |
| 组件层 | `BoBoPet` 新增 `expression`/`motionOff` prop（与既有交互态正交叠加）；`SettingsPanel` 新增“动效与触感”双开关 | `BoBoPetExpressions.test.tsx` 8 用例；核心模块覆盖率 98.52% |

**工程决策**：仓库无 Lottie 资产，表情层用纯 SVG 逐部件动画实现 §4.1 状态机契约（零新依赖，KISS）；Lottie 资产到位后可平滑替换表情层渲染，状态机/信号源契约不变。

**余量（保留）**：成就粒子/工具练习完成彩蛋（M3）、呼吸练习数字倒计时降级、预合成 CLI 验证。
