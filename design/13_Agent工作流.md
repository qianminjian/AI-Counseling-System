# 13 Agent 工作流详细设计

> 来源：`doc/13_Agent工作流.docx`（原文 552 行）
> 状态：已转换 | 关联决策：BEACON #9（**Spring AI 替代 LangGraph**）
> ⚠️ 原文第四章基于 Python **LangGraph** 编写，本文档已按决策 #9 改写为 **Spring AI（ChatClient + Advisor 链 + 状态机）** 实现（见 §4）。原始 LangGraph 描述保留在 §4 附录供对照。

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
| CBT | 认知行为治疗师 | 执行结构化干预 |
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

### 2.3 CBT Agent（认知行为治疗）

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
【系统提示】你是一名专业的儿童认知行为治疗师(CBT)。服务对象是儿童和青少年：
- 使用简单易懂的语言，适应儿童认知水平
- 通过游戏、比喻、故事等有趣方式进行干预
- 保持温暖、支持的态度，建立安全治疗环境
- 遵循 CBT 框架：识别思维、挑战思维、建立新思维
- 适当使用放松技巧和正念练习
当前对话状态：[STATE]，请根据状态执行相应治疗动作。
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
