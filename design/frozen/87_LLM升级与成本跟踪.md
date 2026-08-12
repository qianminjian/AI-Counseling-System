# frozen/87 LLM 升级与成本跟踪（DeepSeek 涨价 + spring-ai 1.1 升级 + 思考模式差异化）

> 编号：DOC-096 | 创建：2026-08-09 | 状态：**🔒 冻结**（远期任务，触发条件未到不实施，**设计/代码侧已记录决策 D5 与涨价风险，**待触发后逐项实施）
> 创建来源：本会话（2026-08-09）讨论「DeepSeek V4-Flash 定价 + 思考模式开关合理性」时识别三个待办/风险项，合并为单一 frozen 专题统一跟踪，避免零散登记。

---

## 一、范围（远期跟踪项）

| # | 主题 | 来源 | 触发条件（解冻） | 优先级 |
|---|------|------|----------------|--------|
| **87-01** | **DeepSeek 涨价应对** | DeepSeek 官方 2026-08-06/07 公告"计划近期整体上调定价，涨幅较大"（[报道](https://finance.sina.cn/2026-08-06/detail-inimkwsv2920942.d.html)） | 涨价正式方案发布 + 单生成本 15-20 元/生/年预估被突破 | 高 |
| **87-02** | **spring-ai 升级 + 思考模式差异化（D5）** | 06 文档 D5 决策（2026-08-09）：离线/正确性优先任务（语义风险分类 SemanticRiskClassifier）应开思考模式，但 spring-ai 1.0.0 `OpenAiChatOptions` 不开放 `customBody`/`extraBody` API（[spring-projects/spring-ai#4324](https://github.com/spring-projects/spring-ai/issues/4324) 1.0.x 无法实施） | spring-ai 升级到 1.1+（issue 关闭）OR 自实现 ChatClient 包装器决策 | 中 |
| **87-03** | **DeepSeek V4-Pro 升级评估** | 官方文档"Responses API 目前仅支持 deepseek-v4-flash 模型，暂不支持 deepseek-v4-pro。我们将于 2026 年 8 月初增加对 deepseek-v4-pro 模型的支持" | V4-Pro 正式 API 开放（2026 年 8 月初已宣布） | 中 |
| **87-04** | **峰谷分时计费确认** | 报道"9:00-12:00 / 14:00-18:00 高峰时段收费为平时两倍"（itbear 报道），需以 DeepSeek 控制台账单核实 | 待 DeepSeek 官方定价页正式确认 | 低 |

---

## 二、现状快照（2026-08-09）

### 2.1 模型与定价

| 模型 | 参数 | 输入（缓存命中） | 输入（缓存未命中） | 输出 | 并发 | 本项目使用 |
|------|------|:---:|:---:|:---:|:---:|------|
| deepseek-v4-flash（284B） | 上下文 1M / 输出 384K | 0.02 元/百万 | 1 元/百万 | 2 元/百万 | 2500 | **默认**（.env）/ 生产验证过 |
| deepseek-v4-pro（1.6T） | 同上 | 0.025 元/百万 | 3 元/百万 | 6 元/百万 | 500 | 2026-08-06 生产实测过（06 文档 M6 记录） |

**来源**：[DeepSeek 官方定价](https://api-docs.deepseek.com/zh-cn/quick_start/pricing)（2026-08-08 官方文档）

### 2.2 思考模式现状

> 修订记录（2026-08-12，audit-report-02 P0-2）：调用点由“2 处”修订为代码现状 **7 处**（主对话流 1 + 辅助调用 4 + 语义分类 1 + Layer2 审查 1）；
> 辅助调用与 Layer2 审查的“期望思考模式”列为 87-02 实施时的差异化清单项，当前实际统一关闭（未实施）。

| 调用点 | 延迟敏感 | 期望思考模式 | 当前实际 | 来源 |
|--------|:---:|------|--------|------|
| `AiChatServiceImpl.streamChat` 对话主链路（chatWithPrompt/chatProactive 共用，流式） | ✅ 高 | 关闭 | **关闭**（`LlmExtraBodyConfig` 拦截器自动注入 `enable_thinking=false`） | 06 文档 D2 行为兼容 + 06 文档 D5 决策 |
| `AiChatServiceImpl.generateSessionSummary` 会话摘要（辅助，非流式） | 否 | 待 87-02 实施时评估（同步调用，建议关闭） | **关闭**（全局默认） | 审计 2026-08-12 |
| `AiChatServiceImpl.extractConversationInsights` 会话洞察提炼（辅助，非流式） | 否 | 待 87-02 实施时评估 | **关闭**（全局默认） | 审计 2026-08-12 |
| `AiChatServiceImpl.evaluateConversationQuality` 质量评估（辅助，非流式） | 否 | 待 87-02 实施时评估 | **关闭**（全局默认） | 审计 2026-08-12 |
| `AiChatServiceImpl.summarizeSessionProgress` 进展摘要（辅助，非流式） | 否 | 待 87-02 实施时评估 | **关闭**（全局默认） | 审计 2026-08-12 |
| `SemanticRiskClassifier` 语义风险分类（SAF_001 模板） | 否 | 开启 | **关闭**（全局默认） | 06 文档 D5 决策暂未实施 |
| `OutputReviewService.review` Layer2 输出审查（SAF_002，独立 reviewClient，异步） | 否 | 待 87-02 实施时评估 | **关闭**（全局默认） | 审计 2026-08-12 |

### 2.3 技术约束

- **spring-ai 1.0.0** `OpenAiChatOptions.Builder` **无** `customBody`/`extraBody` 方法（社区确认 issue #4324）
- **LLM 调用入口实际 7 处**（2026-08-12 按代码现状核查，修订审计前“仅 2 处”快照）：
  1. `AiChatServiceImpl.streamChat` 主对话流（chatWithPrompt/chatProactive 共用，经 `LlmStreamEnhancer` 超时/重试/降级）
  2. `AiChatServiceImpl.generateSessionSummary` 会话摘要（AUX_001，callWithTimeout 15s 超时）
  3. `AiChatServiceImpl.extractConversationInsights` 会话洞察提炼（AUX_002，15s 超时）
  4. `AiChatServiceImpl.evaluateConversationQuality` 质量评估（AUX_003，15s 超时）
  5. `AiChatServiceImpl.summarizeSessionProgress` 进展摘要（AUX_004，15s 超时）
  6. `SemanticRiskClassifier.doClassify` 语义风险分类（SAF_001，800ms 门禁）
  7. `OutputReviewService.review` Layer2 输出审查（SAF_002，独立 `reviewClient`，异步不阻塞主流）
  其中 2-5/7 与主对话共用同一 ChatModel（经 `ResilientChatModel` 主备路由）；主对话另经 `LlmStreamEnhancer` 首 token/整体双超时兜底。
- RedTeamRegressionRunner 是**纯静态规则**（forbidden patterns + required markers），无 LLM 调用——“动态 LLM 红队”实际不存在
- `PromptEvalScoreReader` 是**从库读数**（非 LLM 调用），“PEVAL-004 eval”实际无 LLM 评估

> **基线修正说明（登记 2026-08-12，audit-report-02 P0-2）**：
> - **87-01 单生成本基线**（15-20 元/生/年，07 文档 §2.8）按“2 入口”估算，未计入 4 个辅助调用与 Layer2 审查；实际调用量为 7 处，成本基线偏低——解冻后账单复核须按 7 调用点拆分核对（建议按 agent_name 维度统计 `model_call_log`）。
> - **87-02 思考模式差异化清单**原仅覆盖 2 入口（对话主链路关闭 + 语义分类开启）；实施时差异化开关清单须覆盖全部 7 个调用点，各辅助调用与 Layer2 审查按延迟敏感度/正确性需求逐项定档。

---

## 三、跟踪项详情

### 87-01 DeepSeek 涨价应对

**背景**：DeepSeek 官方宣布"计划近期整体上调定价，涨幅较大"。当前成本基线（07 文档 §2.8）：
- 单生 LLM 成本预估 **15-20 元/生/年**（基于 flash 定价 + 实际使用量）
- 涨价后预估突破 20 元/生/年，对 BIZ-002 商用订阅套餐（99/159/259 元/生/年）盈利空间构成压力

**解冻后行动**：
1. 拉取 DeepSeek 控制台实际账单（涨价前后对比）→ 复核单生成本预估
2. 评估切换 GLM(主) + Kimi(备) 组合（[06 文档 §3.3 已验证组合 B](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/design/06_系统配置与外部服务依赖设计.md)）的报价与质量对比
3. 同步更新 [07 文档 §2.8 成本治理](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/design/07_商业化实施合规专题方案.md) 单生成本预估
4. 评估 `rate_plans.overage_rate`（M4 计价超量单价）是否需同步上调

### 87-02 spring-ai 升级 + 思考模式差异化（D5）

**背景**：06 文档 D5 决策（2026-08-09）：
- **对话主链路保持 `enable_thinking=false`**：首 token 延迟/成本/儿童安全审查盲区四重保障
- **离线/正确性优先任务应开思考**：语义风险分类（SemanticRiskClassifier）正确性优先于延迟
- **当前实施状态**：spring-ai 1.0 API 限制，未实施差异化开关
- **暂未实施原因**：spring-ai 1.0.0 `OpenAiChatOptions.Builder` 无 `customBody`/`extraBody` 方法（[issue #4324](https://github.com/spring-projects/spring-ai/issues/4324) 确认 1.0.x 不开放）

**当前安全网**（虽然全局 false）：
- 语义风险分类出错时返回 `null` 降级为纯硬规则结果（[design/04 §18.3 失败安全机制](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/design/04_系统部署方案.md) 兜底）

**解冻后行动**（按优先级）：

| 方案 | 复杂度 | 说明 |
|------|--------|------|
| **A. 升级 spring-ai 1.1+**（待 issue 关闭） | 低 | spring-ai 1.1+ 支持 `customBody`（预期）；3 行代码实施 `OpenAiChatOptions.builder().customBody(Map.of("enable_thinking", true)).build()` |
| **B. 自实现 ChatClient 包装器** | 中 | 包装 `ChatClient`，按调用方类注入 `customBody`（约 20 行）；不依赖 spring-ai 版本 |
| **C. LlmExtraBodyConfig 拦截器 + HTTP header 路由** | 中 | 拦截器按请求头 `X-Enable-Thinking: true` 决定注入 `true` vs `false`；调用方需自建 RestTemplate 绕过 ChatClient 设置 header |

**首选 A**（最简洁），跟踪 issue #4324；如升级路径阻则降级 B。

### 87-03 DeepSeek V4-Pro 升级评估

**背景**：官方文档"Responses API 目前仅支持 deepseek-v4-flash 模型，暂不支持 deepseek-v4-pro。我们将于 2026 年 8 月初增加对 deepseek-v4-pro 模型的支持"。V4-Pro 价格是 flash 的 3 倍（输入未命中 3 元 vs 1 元，输出 6 元 vs 2 元），但推理能力更强（Agent 能力大幅增强）。

**解冻后行动**：
1. 验证 V4-Pro 质量提升（对照 flash 做红队 + eval 基准测试）
2. 成本 vs 质量权衡（单生成本上浮 3x 是否带来满意度/安全性可测提升）
3. 决定维持 flash / 切换 pro / 主备差异化（pro 跑复杂任务，flash 跑对话）方案
4. `application.yml` 模型名切换 + `LLM_PRIMARY_MODEL` 默认值更新

### 87-04 峰谷分时计费确认

**背景**：[itbear 报道](https://m.finance.itbear.com.cn/html/2026-08/447217.html)称"DeepSeek API 采用峰谷分时机制，每日 9:00-12:00 及 14:00-18:00 的高峰时段收费为平时的两倍"。但官方定价页 snippet 未直接列出（搜索结果未明示）。需以 DeepSeek 控制台账单核实。

**解冻后行动**：
1. 拉取 DeepSeek 控制台账单，对照调用时间戳验证峰谷计费
2. 若属实：评估学校使用时段（白天）与高峰重合度，必要时调度 batch 任务到 12-14 / 18-9 低谷
3. 更新 07 文档 §2.8 成本治理（按高峰价预留余量）

---

## 四、与其他专题的关联

| 专题 | 关联 |
|------|------|
| [06 文档 D5 决策](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/design/06_系统配置与外部服务依赖设计.md) | 87-02 实施入口（待 spring-ai 1.1+ 升级后） |
| [07 文档 §2.8 成本治理](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/design/07_商业化实施合规专题方案.md) | 87-01/87-04 涨价 + 峰谷计费后更新单生成本预估 |
| [his/63 LLM 主备配置通用化设计](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/design/his/63_LLM 主备配置通用化设计.md) | 历史主备配置归档（含 D1~D4 决策） |
| [frozen/38 计费配额与运营后台设计](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/design/frozen/38_计费配额与运营后台设计.md) | M4 计费层冻结（frozen/38 BILL-002/003，87-01 涨价可能解冻部分） |
| [frozen/61 外部服务接入与配置](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/design/frozen/61_外部服务接入与配置.md) | 外部服务配置通用化（DeepSeek 切换/GLM/Kimi 备用供应商验证） |

---

## 五、解冻与实施流程（解冻时）

1. 评估触发条件是否成熟（涨价方案发布 / spring-ai issue 关闭 / V4-Pro API 开放 / 峰谷计费核实）
2. 项目负责人确认解冻（按 AGENTS 红线：远期任务由决策者主动发起）
3. 复制本专题到 `design/doing/87_LLM升级与成本跟踪.md`（解冻转 doing）
4. 按本专题 §三 行动项实施
5. 完成后合并归档到主文档（[06](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/design/06_系统配置与外部服务依赖设计.md) §3.3 / [07](file:///Users/minjianq/Documents/66-Project/Qoder/AI-Counseling-System-dev/design/07_商业化实施合规专题方案.md) §2.8）并归档到 `his/87_*`

---

## 六、跟踪来源

- DeepSeek 官方定价：https://api-docs.deepseek.com/zh-cn/quick_start/pricing
- spring-ai #4324：https://github.com/spring-projects/spring-ai/issues/4324
- spring-ai #3409（extra_body 历史讨论）：https://github.com/spring-projects/spring-ai/issues/3409
- 涨价报道：https://finance.sina.cn/2026-08-06/detail-inimkwsv2920942.d.html
- 峰谷计费报道：https://m.finance.itbear.com.cn/html/2026-08/447217.html
- 06 文档 D5 决策（2026-08-09）：thinking 模式差异化讨论
- 07 文档 §2.8（2026-08-09）：单生成本 15-20 元/生/年基线
