# 67 合规与数据安全修复（ARCH-007）方案与 SPEC

> 关联任务：ARCH-007（深度审计 B-2/B-5/F-5 回填，登记 TASK-TRACKER §二十八）
> 状态：📝 方案定稿 → ✅ 决策闭环（D-7 路径 C 两级摘要、D-8 昵称置换注入，2026-07-28 钱敏健拍板）→ ✅ 已实施（2026-07-28 TDD 落地：MessageSummarySummarizer 7 例、DomainEntityFactoryTest 新增 2 例保真/提炼断言、ConversationContextAgentTest 新增昵称置换用例、VerifyPage/PrivacyPage 测试；counseling-domain 28 例 + counseling-service 24 例 + parent-h5 27 例全绿）
> 依据：深度审计 2026-08-05（B-2/B-5/F-5）、design/08 §5.1、design/10 中国政策与合规风险、design/26 家长端设计（PIPL 告知）
> 词汇：数据最小化 / 告知同意 / safeContent——见 [13 领域词汇表](../13_领域词汇表.md) 风险安全域

---

## 1. 背景与问题

三项合规相关缺口，均涉未成年人数据（PIPL / 未保条例敏感）：

| ID | 问题 | 证据 | 性质 |
|----|------|------|------|
| B-2 | `MessageSummary` 类注释声明「结构化摘要、不存明文」，实现为 **V7 `contentSummary` 明文截断**（非结构化、字符截断） | `MessageSummary.java` L12-13/L55/L111-114 | 声明与实现漂移 + 数据最小化合规复核点 |
| B-5 | 个人信息**明文**提取后注入 LLM 上下文（非 safeContent 脱敏），已登记 P1 待决策例外，但**未冻结未修复** | `ConversationServiceImpl.java` L304/L754-790（extractPersonalInfo）、`ConversationContextAgent.java` L84-105 | 未成年人 PII 进入第三方 LLM 链路的合规风险 |
| F-5 | 家长端 PIPL 告知链接缺失 | `parent-h5/src/pages/verify/index.tsx` 全文 | design/26 明确要求 |

## 2. 目标与非目标

**目标**：
- 会话摘要的数据最小化实现与声明对齐（二选一后落地）
- 个人信息注入 LLM 前脱敏/占位化，阻断明文 PII 出域
- 家长端补齐个人信息保护告知入口（design/26 对齐）

**非目标**：
- 字段加密体系调整（AES-256-GCM 已实证生效，不动）
- 隐私政策/用户协议正文起草（法务职责，本任务只做链接与入口）
- 等保二级差距项（frozen/31 专题管理）

## 3. 设计方案

### 3.1 B-2 · MessageSummary 原始设计需求落实（✅ D-7 已决策 2026-07-28 钱敏健：路径 C 两级摘要）

**原始设计需求**（design/08 §5.1「只存结构化摘要」）拆解为三层：
1. **不存明文原文** → ✅ 已满足（R-01 字段级 AES-256-GCM 加密，AUDIT-P0-3 实证）
2. **结构化字段承载提炼** → ✅ 已满足（emotionLabel / riskLevel / cbtFields 已落库）
3. **文本内容为提炼物而非原文切片** → ❌ 未满足（contentSummary = truncate 1024 字符的原文截断）

**路径 C · 两级摘要策略**（兑现第 3 层，保留教师逐轮展示能力，无 schema 变更）：
- 常规消息（riskLevel < L3）：contentSummary 由「原文截断 1024」改为「语义提炼 ≤200 字」——新增 `MessageSummarySummarizer`（规则抽取：去语气词/去重复、保留含情绪与 CBT 关键词句；LLM 提炼登记远期，成本考量）
- 风险消息（riskLevel ≥ L3）：contentSummary **保持原文保真**——儿童安全场景原话是关键证据，安全 > 最小化（冲突优先级第一条）
- 加密（R-01）与结构化字段不动；类注释与 design/08 §5.1 同步改为准确表述
- 教师端逐轮展示（TeacherController L488）、会话摘要提炼（MessageSummaryService L85）不受影响

**为何不是简单删除声明**：需求第 3 层是合规实质（未成年人对话内容最小化存储），删除声明只是掩盖差距；两级策略让常规消息不再存原文切片、风险消息保真取证，是「最小化与安全证据」的平衡解。

### 3.2 B-5 · 个人信息注入处置（✅ D-8 已决策 2026-07-28 钱敏健：脱敏注入，只能是昵称进入 LLM）

**现状**：`extractPersonalInfo`（4 组正则提取 realName/age/grade/class，ConversationServiceImpl L754-790）明文拼入 LLM 上下文；系统已有 `PiiDesensitizer`（SAFE-204，26 用例）与完整昵称体系（`User.pseudonym`：登录 SEC-003 / 问候语 design/28 / 教师 CSV 导出）。

**决策规则（身份置换 + 泛化占位）**：
- realName（对话中提取的真名）→ 会话学生 **pseudonym 昵称**；无昵称 → 「同学」
- class（班级）→ 「我们班」泛化（年级+班级可定位个人）
- school/address（提取面未来扩展）→ 「本校」占位
- age / grade → 保留（年龄适配功能依赖 design/29，非身份标识）
- 原始提取值不落库、不展示、不进上下文；置换失败 → 兜底占位 + 审计日志，**不得静默注入明文**

**说明**：上下文引用能力（「你上次提到……」）用昵称/占位值完全可达，功能不损失；教师端展示同样用置换值。

### 3.3 F-5 · 家长端 PIPL 告知入口（✅ 已实施 2026-07-28）

- `parent-h5` 验证页/注册页底部新增「个人信息保护告知」链接（design/26 要求）✅
- 落地：新建公开静态页 `src/pages/privacy/index.tsx`（收集范围/使用用途/未成年人保护/家长权利四要点，design/22 素材），路由 `/parent/privacy`（公开，无需登录）；验证页 `tip-text` 区追加 `<a href="/parent/privacy">个人信息保护告知</a>` ✅
- 版本化登记：与 design/22 条款版本同步（同意记录已版本化，链接版本一致）🔲（文案版本号登记待 ARCH-010 知识库/合规专项）

## 4. SPEC

```
D-7（MessageSummary）：✅ 已决策（2026-07-28 钱敏健）路径 C——常规消息语义提炼 ≤200 字（MessageSummarySummarizer 规则抽取）+ L3+ 消息原文保真；类注释与 design/08 §5.1 准确化；无 schema 变更
D-8（B-5）：✅ 已决策（2026-07-28 钱敏健）昵称置换——realName → pseudonym（fallback「同学」）；class → 「我们班」；明文不进上下文
F-5：parent-h5 注册/验证页新增「个人信息保护告知」链接（design/26）
验收基线：脱敏路径沿用 SAFE-204 的 26 用例 + 新增昵称置换用例；新增「注入上下文无明文 PII」断言测试
```

## 5. 验收标准（EARS 风格）

- **当 D-7 落地后**，常规消息 contentSummary 必须为 ≤200 字提炼物（≠ 原文截断，用例断言），L3+ 风险消息必须保持原文保真 ✅（MessageSummarySummarizerTest 7 例 + DomainEntityFactoryTest#studentMessage_truncatesLongContent/#studentMessage_highRiskKeepsFullText）
- **当 D-7 落地后**，`MessageSummary` 类注释与 design/08 §5.1 必须与实现一致（无「结构化摘要」虚假声明） ✅（类注释已准确化：两级策略 + 提炼器说明）
- **当 B-5 修复后**，注入 LLM 的上下文必须不含明文真名/班级/学校/地址；真名出现时必须以昵称（或「同学」）置换 ✅（ConversationContextAgentTest#realNamePriorityWithFullInfo 断言 doesNotContain 小明/真实名字/1班；#realNameReplacedByPseudonym 断言小星星置换张小凡）
- **当 B-5 置换失败时**，必须走兜底占位并记审计日志，不得静默注入明文 🔲（兜底已实现「小朋友」中性称呼；审计日志登记为后续项，见 doing/69 ARCH-009 门禁）
- **当 F-5 落地后**，parent-h5 注册/验证页必须可见「个人信息保护告知」入口且可访问 ✅（verify 页链接 + /parent/privacy 公开路由，PrivacyPage.test 3 例 + VerifyPage.test 1 例）
- **当全量回归运行时**，后端测试与 parent-h5 构建必须通过 ✅（counseling-domain 28 例 / counseling-service 24 例 / parent-h5 27 例）

## 6. 风险与回滚

- **风险**：B-5 修复涉及对话上下文组装（核心路径），需全量回归 + SSE 集成验证；昵称/占位值改变 LLM 上下文内容，可能影响对话自然度（冒烟验证）；D-7 提炼可能丢失常规消息细节（风险消息已保真，接受此损失）
- **红线**：D-8 合规决策已由钱敏健确认（2026-07-28）；D-7 路径 C 无 schema 变更，不触红线 3
- **回滚**：脱敏注入为局部替换可 revert；F-5 为纯前端增量

## 7. 关联与落点

- 关联任务：ARCH-004（doing/64，台账对齐组）、ARCH-009（doing/69，工程化门禁）
- 关联设计：design/08 MVP（§5.1 摘要）、design/10 合规、design/22 告知同意条款、design/26 家长端
- 词汇表：[13 领域词汇表](../13_领域词汇表.md) 风险安全域
- 登记：TASK-TRACKER §二十八 ARCH-007
