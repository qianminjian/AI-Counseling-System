# 13 Agent 工作流详细设计

> 来源：`doc/13_Agent工作流.docx`（原文 552 行）
> 状态：已转换 | 关联决策：BEACON #9（**Spring AI 替代 LangGraph**）、**DEC-CBT 路径1（激活世界B，钱敏健 2026-07-28）**
> ⚠️ 原文第四章基于 Python **LangGraph** 编写，本文档已按决策 #9 改写为 **Spring AI（ChatClient + Advisor 链 + 状态机）** 实现（见 §4）。原始 LangGraph 描述保留在 §4 附录供对照。
> ⚠️ **实现现状（2026-07-28 核对）**：§2/§4/§9/§10 描述的多 Agent 编排（世界B）**已全部编码但零调用**——`ConversationOrchestrator` 全仓无 counseling-ai 之外的调用者，线上对话走 `ChatServiceImpl` 单 prompt 直连（世界A）。逐组件四态判定与 DEC-CBT 接线深化设计见 **§十三**，勿将本文档误读为已生效架构。

---

## 一、Agent 架构概览

### 1.1 Agent 定义与角色

Agent 是系统核心智能单元，每个承担特定职责，协作完成心智支持任务。系统含 **7 类核心 Agent**：安全监护、情绪识别、认知行为干预、对话交互、风险升级、报告生成、记忆管理。

### 1.2 Agent 间关系

采用**星型拓扑**，Conversation Agent 作为核心入口：

| Agent | 角色 | 职责 |
|-------|------|------|
| Conversation | 用户交互入口 | 接收用户输入、生成回复 |
| Safety | 安全守护者 | 检测有害内容，触发风险评估 |
| Emotion | 情绪识别器 | 分析用户情绪状态和强度 |
| CBT | CBT 辅导引导者 | 执行结构化干预 |
| Escalation | 升级管理者 | 处理高风险情况 |
| Report | 报告生成器 | 汇总对话生成分析报告 |
| Memory | 记忆管理者 | 维护用户历史与会话上下文 |

### 1.3 消息传递机制

Agent 间通过结构化消息通信：

| 字段 | 类型 | 说明 |
|------|------|------|
| source_agent | string | 发送方 |
| target_agent | string | 接收方（* 表示广播） |
| message_type | enum | 请求/响应/事件/错误 |
| payload | object | 消息内容体 |
| timestamp | datetime | 时间戳 |
| conversation_id | string | 所属会话 ID |
| priority | enum | 普通/高优先级/紧急 |

---

## 二、核心 Agent 详细设计

### 2.1 Safety Agent（安全监护）

**职责：** 内容安全检测（自杀/自残、暴力、虐待、色情）、0-5 级风险判定、安全干预触发、儿童保护合规。

**输入：** 用户消息文本、会话上下文、历史对话
**输出：** 安全检测结果、风险等级、干预建议

**决策逻辑（多层级决策树）：**
- 内容过滤层：正则匹配 + 关键词库过滤已知风险
- 语义分析层：LLM 判断潜在风险意图
- 风险聚合层：综合当前输入与历史上下文计算风险分
- 干预决策层：按风险等级决定干预方式

**Prompt 模板：**
```
【系统提示】你是一名专业的儿童心理安全检测专家。分析用户输入，检测：
- 自杀/自残倾向（谈论死亡、绝望感、无价值感）
- 暴力倾向（攻击他人、破坏物品）
- 虐待儿童（身体虐待、性虐待、忽视）
- 色情内容
- 其他危害儿童安全的内容
以 JSON 返回：is_safe(bool)、risk_level(0-5)、risk_type(数组)、concerns(数组)、recommended_action。
```

### 2.2 Emotion Agent（情绪识别）

**职责：** 情绪分类识别、强度计算、趋势追踪、触发因素识别。

**情绪分类体系：**

| 类别 | 子类别 | 关键词示例 |
|------|--------|-----------|
| 喜悦 | 开心/兴奋/满足/乐观 | 开心、快乐、棒、太好了 |
| 悲伤 | 沮丧/失望/孤独/抑郁 | 难过、伤心、失落、孤独 |
| 愤怒 | 烦躁/挫折/敌意/报复 | 生气、气愤、讨厌、恨 |
| 恐惧 | 焦虑/担忧/害怕/恐慌 | 害怕、担心、紧张、恐怖 |
| 惊讶 | 震惊/意外/困惑 | 震惊、没想到、奇怪 |
| 厌恶 | 反感/蔑视/羞耻 | 恶心、讨厌、讨厌自己 |
| 平静 | 放松/满足/中性 | 还好、一般、平静 |

**情绪强度（0-100 分）：** 基础强度（0-60，情绪类别+表达强度词）+ 上下文加成（0-25）+ 持续时间因子（0-15）。

**输出格式：** `{ emotion_category, emotion_subcategory, intensity_score, intensity_label(轻度/中度/强度/极度), triggers, trends, confidence }`

### 2.3 CBT Agent（认知行为辅导）

**职责：** 认知重构、行为激活、情绪调节、问题解决训练。

**工作流程：** 建立关系 → 问题探索 → 认知评估 → 认知重构 → 行为实验 → 总结反馈。

**状态机设计：**

| 状态 | 描述 | 触发条件 | 转移动作 |
|------|------|---------|---------|
| IDLE | 等待任务 | 无输入 | 接收用户输入 |
| ENGAGING | 建立关系 | 新对话开始 | 确认用户问题 |
| ASSESSING | 问题评估 | 了解问题后 | 收集更多信息 |
| INTERVENING | 干预执行 | 评估完成 | 应用 CBT 技术 |
| MONITORING | 进度监控 | 干预中 | 评估效果 |
| CLOSING | 结束会话 | 目标达成或退出 | 总结反馈 |
| ESCALATING | 升级处理 | 检测到高风险 | 通知 Escalation Agent |

**Prompt 模板：**
```
【系统提示】你是一名专业的儿童心理辅导助手，运用认知行为疗法（CBT）技术做辅导引导（不做诊断和治疗）。服务对象是儿童和青少年：
- 使用简单易懂的语言，适应儿童认知水平
- 通过游戏、比喻、故事等有趣方式进行引导
- 保持温暖、支持的态度，营造安全的交流氛围
- 遵循 CBT 框架：识别思维、挑战思维、建立新思维
- 适当使用放松技巧和正念练习
当前对话状态：[STATE]，请根据状态执行相应辅导动作。
```

### 2.4 Conversation Agent（对话交互）

**职责：** 用户输入处理、回复生成、对话管理、多模态输出。

**儿童语言适配：** 词汇简化、句子简短、正向表达、情感确认、互动性。

**对话策略：**

| 策略 | 适用场景 | 示例 |
|------|---------|------|
| 共情回应 | 用户表达情绪 | "我能感觉到你现在很难过" |
| 好奇询问 | 需要更多信息 | "能告诉我发生了什么吗？" |
| 正向反馈 | 用户分享正面信息 | "你做得真好！" |
| 温和引导 | 需要转移话题 | "我们来聊聊别的吧" |
| 明确直接 | 提供指导建议 | "下次可以试试这样做" |

**Prompt 模板：**
```
【系统提示】你是友善的 AI 心理陪伴助手"心灵伙伴"，服务对象是儿童和青少年。
- 温暖、友好、耐心、支持的语气
- 儿童友好语言，避免复杂术语
- 适当使用表情符号增加亲和力
- 回复简洁明了，每次聚焦一个要点
- 鼓励表达但不过度询问
- Never 提供专业医疗诊断或药物治疗建议
```

### 2.5 Escalation Agent（升级管理）

**职责：** 风险监测、升级评估、流程协调、通知管理、记录归档。

**触发条件：**

| 风险等级 | 触发条件 | 升级目标 |
|:---:|------|------|
| L4 | 自杀/自残意念明确表达 | 立即通知老师 + 建议专业咨询 |
| L4 | 暴力行为倾向 | 立即通知老师 + 视情况报警 |
| L5 | 正在发生的危险行为 | 立即通知老师 + 可能需紧急服务 |
| L5 | 严重虐待儿童怀疑 | 通知儿童保护机构 |

**升级流程：** 接收警报 → 验证等级 → 启动通知流程 → 发送紧急通知（短信/推送/电话）→ 持续监控 → 记录详情 → 风险解除后发解除通知。

**升级事件报告：** 事件基本信息、风险评估详情、对话摘要、采取的行动、后续建议。

### 2.6 Report Agent（报告生成）

**职责：** 会话摘要、周期报告（周/月/学期）、情绪趋势分析、干预效果评估。

**摘要生成规则：** 单次摘要 ≤500 字；必含情绪状态/主要话题/干预要点；删除可识别身份信息；结构化输出。

**隐私保护：** 数据最小化、匿名化处理、访问控制、加密存储、保留期限。

**输出格式：** `{ session_id, user_id(脱敏), date, duration, emotion_summary, topic_summary, interventions_applied, outcomes, follow_up_recommendations }`

### 2.7 Memory Agent（记忆管理）

**职责：** 短期记忆、长期记忆、记忆检索、记忆整合。

**记忆分层：**

| 层级 | 内容 | 容量 | 保留时间 |
|------|------|------|---------|
| 工作记忆 | 当前对话上下文、话题状态 | 50 条消息 | 会话期间 |
| 情景记忆 | 历史会话摘要、关键事件 | 最近 100 次会话 | 6 个月 |
| 语义记忆 | 用户画像、偏好、已知问题 | 无限制 | 长期 |

**存储策略：** 向量嵌入语义化、结构化存储、时间衰减、重要性标记（升级事件永久保留）。
**检索机制：** 语义检索、时间范围检索、类型检索、相关性排序。

---

## 三、工作流编排

### 3.1 正常对话流程

| 阶段 | 执行 Agent | 输入 | 输出 |
|------|-----------|------|------|
| 用户输入 | Conversation | 用户消息 | 结构化用户意图 |
| Safety 检测 | Safety | 用户消息+上下文 | 安全检测结果 |
| Emotion 识别 | Emotion | 用户消息+上下文 | 情绪分析结果 |
| 流程路由 | Orchestrator | Safety+Emotion 结果 | 目标 Agent 和策略 |
| CBT 干预 | CBT | 用户输入+记忆 | 干预响应 |
| Response 生成 | Conversation | CBT 输出+上下文 | 最终回复 |
| Output 审查 | Safety | 生成回复 | 安全性确认 |
| 返回用户 | Conversation | 审查通过 | 最终输出 |

### 3.2 风险检测流程

Safety 检测到 L3+ 风险时：Safety 触发 → Escalation 详细评估 → 等级判定（L3/L4/L5）→ L4/L5 启动紧急升级通知 → 人工通知老师/家长 → 按等级限制/暂停对话。

### 3.3 预警生成流程

风险触发 → 数据聚合（对话+情绪+风险评估）→ Report 生成结构化预警 → 通知发送 → 老师处理 → 记录归档。

---

## 四、工作流实现（Spring AI）

> 决策 #9：以 Spring AI 的 `ChatClient` + `Advisor` 链 + 显式状态机替代 LangGraph 的图节点/边模型。落地在 `counseling-ai` 模块。

### 4.1 编排组件映射

| LangGraph 概念 | Spring AI 实现 | 落地位置 |
|------|------|------|
| Node（node_safety_check 等） | Spring Bean（`SafetyAgent`/`EmotionAgent`/... 各封装一个 `ChatClient` 调用） | `counseling-ai/agent/` |
| Edge + 条件路由 | `ConversationOrchestrator` 显式编排（if/switch + 状态机跳转） | `counseling-ai/orchestrator/` |
| State（图状态对象） | `ConversationState`（POJO，见 4.4）随请求流转，存 Redis 会话上下文 | `counseling-ai/state/` |
| 前置/后置钩子 | Spring AI `Advisor` 链（如 `SafetyInputAdvisor`、`SafetyOutputAdvisor`、`MemoryAdvisor`、`LoggingAdvisor`） | `counseling-ai/advisor/` |
| RAG 检索 | Spring AI `QuestionAnswerAdvisor` + pgvector `VectorStore` | `counseling-ai/rag/` |

### 4.2 编排流程（等价原 4.2 边定义）

```
START
 → SafetyInputAdvisor（输入侧安全预检）
 → SafetyAgent.check()
     ├─ safe        → EmotionAgent.recognize()
     └─ unsafe(L3+) → EscalationAgent.handle() → END(受限回复)
 → Orchestrator 路由：
     ├─ high_intensity → CBTAgent.intervene()
     └─ low_intensity  → ConversationAgent.reply()（支持性回复）
 → ConversationAgent.generate()
 → SafetyOutputAdvisor（输出侧安全审查）
 → END（返回用户）
异步旁路：会话结束 → ReportAgent.summarize()；全程 MemoryAdvisor 读写上下文
```

### 4.3 条件路由（Orchestrator 逻辑）

- **Safety 路由：** `safe → emotionFlow`；`unsafe → escalateFlow`
- **风险等级路由：** `L3 → 警告+限制`；`L4 → 升级+通知`；`L5 → 紧急升级`
- **情绪路由：** `high_intensity → cbtFlow`；`low_intensity → supportiveFlow`
- **会话结束路由：** `normal_close → report`；`urgent_close → escalate`

### 4.4 状态管理（ConversationState）

```java
// counseling-ai/state/ConversationState.java（字段示意）
record ConversationState(
    String conversationId,
    List<Message> messages,
    UserProfile userProfile,
    SafetyResult safetyResult,
    EmotionResult emotionResult,
    int riskLevel,
    String currentAgent,
    List<Intervention> interventionHistory,
    MemoryContext memoryContext,
    boolean shouldEscalate,
    String escalationReason
) {}
```

- 并行执行：Safety 与 Emotion 无依赖，用虚拟线程（Java 21）`StructuredTaskScope` 并行。
- 状态持久化：会话期间存 Redis（key=conversationId），结束后落 `message_summaries`。

### 4.5 附录：原始 LangGraph 描述（对照存档，不实现）

原文第四章定义 7 个节点（`node_safety_check`/`node_emotion_recognize`/`node_cbt_intervene`/`node_conversation`/`node_escalate`/`node_report`/`node_memory`）与边条件（START→safety_check→emotion→cbt→conversation→END，safety 未通过转 escalate）。这些图模型语义已由 §4.1-4.4 的 Spring AI 编排等价实现。

---

## 五、对话状态管理

### 5.1 会话上下文

会话 ID、脱敏用户标识、时间戳（开始/最后活跃）、消息历史、当前状态。

### 5.2 流程阶段状态

| 阶段 | 值 | 描述 |
|------|:---:|------|
| INIT | 0 | 对话初始化 |
| RECEIVING | 1 | 接收用户输入 |
| PROCESSING | 2 | 处理分析中 |
| RESPONDING | 3 | 生成回复中 |
| MONITORING | 4 | 监控风险中 |
| ESCALATING | 5 | 升级处理中 |
| CLOSING | 6 | 对话结束 |
| PAUSED | 7 | 对话暂停 |

### 5.3 历史记忆

短期（当前会话完整上下文）、中期（历史会话摘要）、长期（用户画像/偏好/已知风险因素）。

---

## 六、错误处理与降级

### 6.1 Agent 失败降级策略

| 失败 Agent | 降级策略 | 备选方案 |
|-----------|---------|---------|
| Safety | 使用规则引擎替代 | 基于关键词的简单过滤 |
| Emotion | 返回中性情绪 | 默认情绪分类 |
| CBT | 使用通用响应 | 标准支持性回复 |
| Memory | 使用缓存数据 | 降级到无记忆模式 |
| Escalation | 触发最高级别升级 | 自动通知所有紧急联系人 |

### 6.2 LLM 调用失败处理

首次失败等 1s 重试 → 第二次等 3s 重试并用缓存 → 第三次触发降级用规则引擎 → 持续失败记日志并通知运维。
> Java 实现：Spring Retry（`@Retryable` 指数退避）+ Resilience4j 熔断/降级；LLM 主备切换对应 BEACON 决策 #7（LLM 供应商无关 + 主备降级）。

### 6.3 超时处理

Safety 检查 30s / Emotion 识别 10s / CBT 干预 60s / 整体对话 120s，超时各触发对应降级。

---

## 七、性能优化

### 7.1 并发处理

独立 Agent 并行（Safety+Emotion）、请求队列、连接池、负载均衡。
> Java：虚拟线程 + `StructuredTaskScope` 并行；LLM 连接复用交由 Spring AI 客户端管理。

### 7.2 缓存策略

| 缓存类型 | 内容 | TTL | 更新策略 |
|---------|------|-----|---------|
| 用户画像 | 基本信息、偏好 | 24 小时 | 主动失效 |
| 情绪上下文 | 最近情绪分析结果 | 5 分钟 | 时间过期 |
| Safety 结果 | 相同内容检测结果 | 1 小时 | 内容变化失效 |
| 对话摘要 | 会话摘要 | 会话结束 | 主动失效 |

### 7.3 异步处理

通知发送、报告生成、日志记录、监控指标均异步。
> Java：`@Async` + 事件驱动（`ApplicationEventPublisher`）。

---

## 八、监控与日志

### 8.1 Agent 调用日志

请求元数据（会话 ID/时间戳/Agent 名）、输入数据（脱敏）、处理结果、性能数据（耗时/Token）、错误信息。落 `model_call_logs` 表（见 06 文档 §4.20）。

### 8.2 决策追踪

Safety 决策链、情绪识别置信度、CBT 干预路径、升级决策推理过程。

### 8.3 性能指标

| 指标类别 | 具体指标 | 告警阈值 |
|---------|---------|---------|
| 响应时间 | P50/P95/P99 延迟 | >2s / >5s / >10s |
| 可用性 | Agent 成功率 | <99% |
| 风险检测 | 漏检率 | >0.1% |
| 资源消耗 | Token 消耗速率 | >预设上限 |

> Java 可观测：Micrometer + Prometheus + Grafana；链路追踪 Spring 自带 Observation API。

---

## 九、Agent 接口定义（开发契约）

> 每个 Agent 封装为独立 Spring Bean，实现统一接口，由 `ConversationOrchestrator` 编排调用。

### 9.1 统一 Agent 接口

```java
// counseling-ai/agent/Agent.java
public interface Agent<I extends AgentInput, O extends AgentOutput> {
    String agentName();
    O execute(I input, ConversationState state);
    Duration timeout();
    O fallback(I input, ConversationState state, Throwable cause);
}
```

### 9.2 各 Agent 输入/输出定义

| Agent | 输入类型 | 输出类型 | 核心字段 |
|------|------|------|------|
| SafetyAgent | `SafetyInput` | `SafetyResult` | riskLevel(L0-L5), riskDomains[], confidence, needsHumanReview, needsImmediateEscalation, evidence[], studentSafeReplyStrategy |
| EmotionAgent | `EmotionInput` | `EmotionResult` | primaryEmotion, intensity(0-10), persistence, confidence |
| SkillRouter | `RouterInput` | `RouterResult` | targetSkill(CBT/SEL/PFA/relaxation/companionship), routeReason, scenarioId |
| CBTAgent | `CBTInput` | `CBTResult` | nextState(S0-S9), cbtFields(jsonb), microAction, balancedThought |
| ConversationAgent | `ConversationInput` | `ConversationResult` | reply, languageLevel, turnCount |
| EscalationAgent | `EscalationInput` | `EscalationResult` | escalationReason, notifiedRole, notificationStatus, crisisResources[] |
| ReportAgent | `ReportInput` | `ReportResult` | teacherSummary, riskSummary, suggestedActions[] |
| MemoryAgent | `MemoryInput` | `MemoryResult` | memoryContext, emotionTrend, riskTrend |

### 9.3 ConversationOrchestrator 编排接口

```java
// counseling-ai/orchestrator/ConversationOrchestrator.java
public interface ConversationOrchestrator {
    /**
     * 处理一轮对话，返回 AI 回复和更新后的状态。
     * 内部流程：Safety → Emotion → Router → CBT/Conversation → OutputGuard
     */
    OrchestrationResult processTurn(String sessionId, String userInput, ConversationState currentState);
}

record OrchestrationResult(
    String reply,
    ConversationState updatedState,
    SafetyResult safetyResult,
    EmotionResult emotionResult,
    RiskEvent riskEvent,      // nullable
    TeacherSummary summary     // nullable，会话结束时生成
) {}
```

---

## 十、CBT 状态机实现

### 10.1 状态机引擎

```java
// counseling-ai/state/CBTStateMachine.java
public class CBTStateMachine {
    // 状态转移表（对应 03 文档通用状态机）
    private static final Map<CBTState, List<Transition>> TRANSITIONS = Map.of(
        S0_START,    List.of(to(S1_SAFETY_PRECHECK, "user_engaged")),
        S1_SAFETY_PRECHECK, List.of(
            to(S2_EMOTION_LABEL, "risk_R0_or_R1"),
            to(S9_ESCALATE, "risk_R3_or_R4")
        ),
        S2_EMOTION_LABEL, List.of(to(S3_SCENARIO_ROUTE, "emotion_obtained")),
        S3_SCENARIO_ROUTE, List.of(to(S4_EVENT_FACT, "scenario_matched")),
        S4_EVENT_FACT, List.of(to(S5_AUTO_THOUGHT, "event_confirmed")),
        S5_AUTO_THOUGHT, List.of(to(S6_REFRAME, "thought_identified")),
        S6_REFRAME, List.of(to(S7_MICRO_ACTION, "balanced_thought")),
        S7_MICRO_ACTION, List.of(to(S8_RECHECK_CLOSE, "action_selected")),
        S8_RECHECK_CLOSE, List.of(
            to(END, "risk_stable"),
            to(S9_ESCALATE, "risk_escalated")
        ),
        S9_ESCALATE, List.of(to(END, "notification_sent"))
    );

    public TransitionResult evaluate(CBTState current, String trigger, ConversationState state) {
        // 全局风险覆盖：任意状态出现 R3/R4 → 强制转 S9
        if (state.safetyResult().riskLevel() >= R3) {
            return TransitionResult.force(S9_ESCALATE, "global_risk_override");
        }
        // 正常转移
        return TRANSITIONS.getOrDefault(current, List.of())
            .stream()
            .filter(t -> t.trigger().equals(trigger))
            .findFirst()
            .map(TransitionResult::of)
            .orElse(TransitionResult.stay(current, "no_matching_transition"));
    }
}
```

### 10.2 状态与 03 文档的映射

| 状态机状态 | 03 文档状态 | 触发条件 | 退出条件 | 记录字段 |
|------|------|------|------|------|
| S0_START | 开始 | 会话创建 | 用户确认参与 | session_id, channel, grade |
| S1_SAFETY_PRECHECK | 风险前置 | 每次用户输入后 | R0/R1 进入 CBT；R2 限制性支持；R3/R4 转人工 | initial_risk_level, risk_signals |
| S2_EMOTION_LABEL | 情绪命名 | 风险检查通过 | 获得主情绪+强度 | emotion_label, emotion_intensity |
| S3_SCENARIO_ROUTE | 场景路由 | 情绪已命名 | 匹配场景流程树 | scenario_id, route_confidence |
| S4_EVENT_FACT | 事件确认 | 场景已路由 | 知道触发事件+安全状态 | trigger_event_summary |
| S5_AUTO_THOUGHT | 自动想法 | 事件已确认 | 得到一个想法句子 | auto_thought, thinking_pattern |
| S6_REFRAME | 认知重构 | 想法已识别 | 儿童说出替代想法 | balanced_thought, evidence |
| S7_MICRO_ACTION | 微行动 | 重构完成 | 儿童选择行动 | micro_action, action_owner |
| S8_RECHECK_CLOSE | 复检结束 | 行动已选 | 情绪降/风险稳定 | final_risk_level, emotion_after |
| S9_ESCALATE | 转人工 | 任意状态 R3/R4 | 通知成功 | escalation_reason, notification_status |

---

## 十一、Advisor 实现规范

> ⚠️ **实现状态（2026-07-23 核对）**：本节（含 11.2/11.3 实现骨架）为**目标设计**，描述完整 Advisor 链架构。M1 **未采用** Advisor 链编排，实际以 Service 层显式调用实现安全管线：输入风险 `RiskDetectorServiceImpl` + `PiiDesensitizer`，输出 `OutputContentFilter`（Layer1）+ `OutputReviewService`（Layer2 异步 SAF-002）。**真实已实现类名与范围以 `design/04` §十七 为准**，本节骨架代码仅供 M2+ Advisor 链重构参考。

### 11.1 Advisor 链顺序与职责

| 序号 | Advisor | getOrder() | 职责 | 实现要点 |
|:---:|------|:---:|------|------|
| 1 | `SafetyInputAdvisor` | 100 | 输入安全预检 | 硬规则（正则+分类器）先于 LLM；命中即阻断；输出 RiskEvent |
| 2 | `MemoryReadAdvisor` | 200 | 读取会话记忆 | 从 Redis 加载 ConversationState；拼接历史摘要到 Prompt |
| 3 | `QuestionAnswerAdvisor` | 300 | RAG 检索 | pgvector 向量检索 + BM25 关键词；RRF 融合；仅检索已审核知识 |
| 4 | *LLM 生成* | — | 模型调用 | ChatClient.call() |
| 5 | `SafetyOutputAdvisor` | 400 | 输出审查 | 检查诊断/治疗承诺/保密/风险遗漏/儿童适龄；pass/rewrite/block/escalate |
| 6 | `MemoryWriteAdvisor` | 500 | 写入会话记忆 | 更新 Redis 中的 ConversationState；提取情绪/风险标签 |
| 7 | `LoggingAdvisor` | 600 | 审计日志 | 记录 prompt_version/model_version/latency/risk_level；写 model_call_logs |

### 11.2 SafetyInputAdvisor 实现骨架

```java
// counseling-ai/advisor/SafetyInputAdvisor.java
@Component
public class SafetyInputAdvisor implements CallAroundAdvisor {
    private final HardRuleMatcher hardRuleMatcher;  // 正则+关键词硬规则
    private final ChatClient safetyClassifier;       // LLM 风险分类
    private final RiskEventRepository riskEventRepo;

    @Override
    public int getOrder() { return 100; }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        String userInput = request.userText();

        // ① 硬规则快速匹配（不消耗 LLM Token）
        HardRuleResult hardResult = hardRuleMatcher.match(userInput);
        if (hardResult.isBlocked()) {
            return buildSafetyBlockResponse(hardResult);
        }

        // ② LLM 风险分类（输出结构化 JSON）
        SafetyResult safetyResult = safetyClassifier.prompt()
            .system(SAFETY_SYSTEM_PROMPT)
            .user(userInput)
            .call()
            .entity(SafetyResult.class);

        // ③ 高风险立即触发 Escalation
        if (safetyResult.riskLevel() >= L4) {
            publishRiskEvent(safetyResult);
            return buildEscalationResponse(safetyResult);
        }

        // ④ 将安全结果注入上下文供后续 Advisor 使用
        request.adviseContext().put("safetyResult", safetyResult);
        return chain.nextAroundCall(request);
    }
}
```

### 11.3 SafetyOutputAdvisor 实现骨架

```java
// counseling-ai/advisor/SafetyOutputAdvisor.java
@Component
public class SafetyOutputAdvisor implements CallAroundAdvisor {
    private final ChatClient outputGuard;  // 输出审查 LLM

    @Override
    public int getOrder() { return 400; }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        AdvisedResponse response = chain.nextAroundCall(request);
        String candidateReply = response.response().getResult().getOutput().getContent();
        SafetyResult safety = request.adviseContext().get("safetyResult");

        // 输出审查：pass / rewrite / block / escalate
        OutputGuardResult guard = outputGuard.prompt()
            .system(OUTPUT_GUARD_PROMPT)
            .user("候选回复：" + candidateReply + "\n风险上下文：" + safety)
            .call()
            .entity(OutputGuardResult.class);

        return switch (guard.decision()) {
            case PASS -> response;
            case REWRITE -> rewriteResponse(response, guard.rewrittenReply());
            case BLOCK -> buildSafetyTemplate(safety);
            case ESCALATE -> {
                publishRiskEvent(safety);
                yield buildEscalationResponse(safety);
            }
        };
    }
}
```

---

## 十二、Agent 间数据流图

```
学生输入
  │
  ▼
SafetyInputAdvisor ──硬规则──▶ 阻断/放行
  │ 放行
  ├──▶ SafetyAgent.check() ──▶ SafetyResult{riskLevel, domains[], evidence[]}
  ├──▶ EmotionAgent.recognize() ──▶ EmotionResult{emotion, intensity, confidence}
  │   （并行执行，虚拟线程）
  ▼
Orchestrator 路由
  ├─ risk ≥ R3 ──▶ EscalationAgent.handle() ──▶ 通知教师/家长
  ├─ high_intensity ──▶ CBTAgent.intervene() ──▶ CBTResult{nextState, fields}
  └─ low_intensity ──▶ ConversationAgent.reply() ──▶ 支持性回复
  ▼
ConversationAgent.generate() ──▶ 候选回复
  │
  ▼
SafetyOutputAdvisor ──▶ pass/rewrite/block/escalate
  │
  ▼
返回学生 + 异步旁路：
  ├──▶ MemoryAgent.write() ──▶ 更新 Redis 会话状态
  ├──▶ LoggingAdvisor.log() ──▶ 写 model_call_logs
  └──▶ ReportAgent（会话结束时）──▶ 教师摘要 + risk_event
```

---

## 十三、实现现状四态判定与 DEC-CBT 接线深化设计（2026-07-28）

> 背景：design/51-53 双世界分析确认本文档为「世界B」蓝图——**已实现、零调用**。DEC-CBT 已决策走路径1（激活世界B）。本节是落地锚：逐组件标注真实状态，给出流式兼容接线架构、延迟/成本门禁与分阶段里程碑。**本节为设计，未开发。**

### 13.1 逐组件四态判定（🟩已生效 / 🟧已实现零调用 / 🟫仅骨架 / ⬜未实现）

| 本文档章节 | 组件 | 代码位置 | 状态 | 说明 |
|------|------|------|:---:|------|
| §2.1/§9.2 | SafetyAgent | `counseling-ai/agent/SafetyAgent.java` | 🟧 | 已实现，仅被 Orchestrator 引用，Orchestrator 本身零调用 |
| §2.2 | EmotionAgent | `counseling-ai/agent/EmotionAgent.java` | 🟧 | 同上 |
| §2.3/§10 | CBTAgent + CbtStateMachine | `agent/CBTAgent.java`、`state/CbtStateMachine.java` | 🟧 | 状态机有单测（CbtStateMachineTest），从未驱动线上对话 |
| §2.4 | ConversationAgent | `agent/ConversationAgent.java` | 🟧 | 与线上 ChatServiceImpl 职责重叠，接线时须裁决归一 |
| §2.5 | Escalation Agent | — | ⬜ | 独立 Agent 未实现；线上等价能力由 RiskDetectorService→AlertService 链承担（世界A，🟩） |
| §2.6 | Report Agent | — | ⬜ | 线上等价能力：教师摘要生成在 service 层（世界A，🟩） |
| §2.7 | Memory Agent | — | ⬜ | 线上等价能力：历史消息窗口拼接；长期记忆见 design/50（部分实现） |
| §4.4/§5 | ConversationState + ConversationStateManager(Redis) | `state/ConversationStateManager.java` | 🟧 | Redis 会话状态管理已实现未接线（同 STATE-002 前置） |
| §9.3 | ConversationOrchestrator | `orchestrator/ConversationOrchestrator.java` | 🟧 | 全仓零外部调用者（2026-07-28 grep 复核） |
| §11 | Advisor 链 | — | ⬜ | M1 未采用，安全管线以 Service 显式调用实现（§11 已有标注，以 design/04 §十七为准） |
| §9.2 SkillRouter | 场景路由 Agent | — | ⬜ | 未实现；接线阶段2 由分诊结果承担路由职责 |

### 13.2 接线目标架构：单次分诊 + 流式回复（SSE 兼容）

世界A 的核心资产是 **SSE 流式体验**（首字快、边生成边播 TTS），世界B 原设计是「多次 LLM 串行调用后返回整段回复」——直接切换会破坏流式。接线采用**两段式**：

```
学生输入
  │
  ▼ ①分诊段（非流式，一次结构化调用，目标 ≤800ms）
TriageCall（合并 Safety+Emotion+Route 为单次轻量 LLM 调用，JSON 输出）
  ├─ 硬规则先行：关键词/正则命中 RED → 跳过 LLM 直接短路（对齐 design/04 深化）
  ├─ 输出：{riskLevel, emotion, intensity, route(CBT/支持/危机), cbtTrigger}
  └─ 期间前端展示「波波思考中」微交互（design/37），掩蔽分诊延迟
  ▼ ②回复段（流式，SSE token 直通）
按 route 选择系统 Prompt 组装（并入 design/44 StrategyProfile：情绪门控/年龄分层/合规裁决）
  → ChatClient.stream() → SSE → 前端/TTS
  ▼ ③输出审查（流式兼容）
Layer1 同步逐句过滤（现有 OutputContentFilter，🟩）
Layer2 异步全文复审 → 违规时**召回改写**（推送 correction 事件替换气泡，对齐 design/14 深化）
  ▼ ④状态推进（异步旁路）
CbtStateMachine.evaluate() 推进 S0-S9 + 阶段标记落库（对齐 design/03 深化）
ConversationStateManager 写 Redis 会话状态（替代内存态，兼做 STATE-002 铺垫）
```

设计判断（真实取舍）：
- **不采用** §4.2 的 Safety/Emotion 双 LLM 并行调用——虚拟线程并行仍是 2 次计费调用且尾延迟取 max；合并为单次 TriageCall 成本减半、尾延迟更稳。SafetyAgent/EmotionAgent 类保留，Prompt 合并。
- **不采用** §11 Advisor 链重构作为接线前提——现有 Service 显式调用管线已生效（🟩），接线只替换「Prompt 组装与路由」层，安全管线原位复用，缩小改造面。
- ConversationAgent 与 ChatServiceImpl 归一：接线后 ChatServiceImpl 退化为传输/会话壳，回复生成职责移入编排层，避免双份 Prompt 组装逻辑。

### 13.3 延迟与成本门禁（接线前置评估，未达标不得全量）

| 指标 | 世界A基线（需实测） | 接线后目标 | 超标处置 |
|------|------|------|------|
| 首 token 延迟 P95 | 待影子期实测 | ≤ 基线 + 900ms | 分诊模型降档（更小模型/更短输出）或分诊结果缓存（同会话 60s 内情绪不重判） |
| 每轮 LLM 调用次数 | 1 次 | 2 次（分诊+回复）；Layer2 复审沿用现状 | RED 硬短路轮次为 0 次（省调用） |
| 每轮 token 成本 | 待实测 | ≤ 基线 × 1.4 | 分诊 prompt 压缩至 ≤600 token、输出 ≤150 token |
| 分诊 JSON 解析失败率 | — | <0.5%，失败即降级世界A路径 | 结构化输出重试 1 次后 fallback |

### 13.4 与 design/44 编排引擎收敛（避免两套编排）

design/44 的 PromptOrchestrationService/StrategyProfile（ORCH-001~008）与本文档 Orchestrator 若各自落地会形成第三个「半世界」。收敛原则：**一个编排入口、两层职责**——
- 分诊层（本文档）：产出 riskLevel/emotion/route（「发生了什么」）；
- 策略层（design/44）：消费分诊结果产出 StrategyProfile→Prompt 组装（「怎么回应」），情绪门控 allowCbt（ORCH-002）直接决定 CbtStateMachine 是否允许进入 S5/S6。
ORCH-001/002 实施时即以本节两段式为宿主，不再单独建链。

### 13.5 分阶段接线里程碑（衍生任务，登记 TASK-TRACKER §二十三）

| 任务ID | 阶段 | 内容 | 门禁/回滚 | 优先级 | 责任人 |
|--------|------|------|------|:---:|------|
| WIRE-001 | 影子运行 | Orchestrator 旁路异步执行（不影响回复），采集分诊准确性/延迟/成本三基线 ≥2 周 | 只读旁路，无用户影响 | P0 | Agent（开发待钱敏健指令） |
| WIRE-002 | 分诊接线 | TriageCall 前置生效：RED 硬短路 + route 驱动 Prompt 组装；feature flag `orchestrator.enabled` 按租户灰度 | 13.3 门禁达标；flag 关闭即回世界A | P0 | 同上 |
| WIRE-003 | CBT 状态机接线 | CbtStateMachine 驱动 S0-S9 推进 + cbt_fields 落库 + 情绪门控（ORCH-002 并入） | 阶段标记完整率 >90% | P1 | 同上 |
| WIRE-004 | 会话状态外置 | ConversationStateManager 接管会话态（兼 STATE-002 前置） | Redis 故障降级内存态 | P1 | 同上 |
| WIRE-005 | 死角清理 | ConversationAgent/ChatServiceImpl 归一；未接线的 Escalation/Report/Memory Agent 从本文档降级为「由现有 Service 承担」或排期实现 | 文档与代码一致性复核 | P2 | Agent |

> 回滚设计：全程保留世界A完整路径，`orchestrator.enabled=false` 一键回退；影子期数据是 go/no-go 的唯一依据，未达 13.3 门禁不进入 WIRE-002。

