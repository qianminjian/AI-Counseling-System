# 63 风险知识单一规则源（ARCH-003）方案与 SPEC

> 关联任务：ARCH-003（深度审计 B-3 回填，doing/61 C2 深化为可实施 SPEC，登记 TASK-TRACKER §二十八）
> 状态：📝 方案定稿 → 待实施（儿童安全红线，Top 优先）
> 依据：深度审计 2026-08-05（B-3：风险词典 ≥5 处、负面情绪集合 6 处口径不一）、doing/61 §5 C2、design/04 风险识别规则库（单一事实源）
> 词汇：接缝 / 局域性 / 深模块 / 删除测试——见 [13 领域词汇表](../13_领域词汇表.md) 架构词汇表

---

## 1. 背景与问题

doing/61 断言风险词典 4 份、负面情绪集合 5 处；深度审计实测比断言更散：

**风险词典 ≥5 处**（同一风险概念多份拷贝）：
- `RiskDetectorServiceImpl.java` L38-63（RED_HARD/ORANGE/YELLOW 分级词）+ L76-117（类别表）
- `ConversationRiskProcessor.java` L225-240（EXPLICIT_INTENT/SELF_HARM_METHOD/PREPARATORY 意图/方法/准备词）
- `TemplateMatrixRegistry.java`（模板矩阵中的风险词表，ARC-3 证据链）
- `SafetyKeywordLibrary`（ARC-4 证据链）
- `ConversationServiceImpl.java` 私有方法内关键词表（主题词，与 `ThemeEvolutionEngine.THEME_KEYWORDS` L33-40 重叠）

**负面情绪集合 6 处口径不一**（同一 `anxious` 不同管线结论不同）：
- `SessionState.java` L125（sad/fearful/angry/disgusted）
- `ConversationRiskProcessor.java` L302-305
- `SessionEndAnalyticsService.java` L146-149（+anxious/crisis）
- `ConversationContextAgent.java` L285-290（+anxious/withdrawn、无 disgusted）
- `LongTermMemoryService.java` L390-397（中文 contains）
- 另 1 处：情绪判定消费点（ARC-4 证据链）

**评分魔法数散落**：85/60/35/30 + 权重 `10,0,0,0,0.8` 三处（`ConversationRiskProcessor` L85/L162-175 等）。

**影响**：儿童安全红线领域，同一信号（如 `anxious`）在会话内风险判定、冷场计数、会话结束分析、记忆回注得到不同结论 = **漏判危机信号的真实隐患**；词典改动一处不同步另四处即行为漂移；无任何测试能断言「集合一致」。

## 2. 目标与非目标

**目标**：
- 风险词典与情绪集合收敛为唯一只读规则源，消费点只引用不定义
- 评分魔法数收编为命名常量（与规则源同处）
- 新增「集合一致」断言测试，把漂移挡在 CI
- 行为零变更（只收敛存放位置，不调整判定标准）

**非目标**：
- 主题关键词收敛（`extractTopicHint` ↔ `ThemeEvolutionEngine`）→ **ARCH-010**（doing/70，doing/61 D-3 分步决策）
- 风险分级标准、情绪定义本身的调整（业务决策，非本任务）
- design/04 主文档修改（开发期冻结，实施前仅做规则清单核对）

## 3. 设计方案

### 3.1 规则源形态（两个只读深模块）

```
counseling-ai/src/main/java/com/mindsafe/ai/risk/
├── RiskKeywordRegistry.java   ← 四级词典 + 意图/方法/准备词 + 评分因子 + 唯一增删入口
└── EmotionVocabulary.java     ← 负面/正面情绪集合 + 中英别名 + 唯一判定入口
```

**`RiskKeywordRegistry`**（静态只读，无状态无副作用）：
- 收编：RED_HARD/ORANGE/YELLOW 分级词、EXPLICIT_INTENT/SELF_HARM_METHOD/PREPARATORY、类别表、`TemplateMatrixRegistry`/`SafetyKeywordLibrary` 中与风险判定重复的词表
- 评分因子命名常量：`SCORE_HARD=85` / `SCORE_ORANGE=60` / `SCORE_YELLOW=35` / `SCORE_ORANGE_MIN=30` 及权重 `WEIGHTS`
- 接口（只读查找）：`matchLevel(text)` / `matchMethod(text)` / `scoreFor(text)`

**`EmotionVocabulary`**（静态只读）：
- 收编 6 处负面/正面情绪集合，定义**权威成员表**（含中英别名：sad/难过、angry/生气、fearful/害怕、anxious/焦虑、disgusted/厌恶、withdrawn/退缩、crisis 等）
- 判定入口：`classify(text|emotionKey): NEGATIVE | POSITIVE | UNKNOWN`
- 明确解决「anxious 不一致」：单一权威表，各消费点结果一致

### 3.2 消费点替换

- `RiskDetectorServiceImpl` / `ConversationRiskProcessor` / `SessionState` / `SessionEndAnalyticsService` / `ConversationContextAgent` / `LongTermMemoryService` 全部改为引用注册表
- 删除各文件内嵌词表与魔法数字面值（常量引用）
- 实现顺序：先建注册表（TDD）→ 逐消费点切换（每点跑相关单测）→ 全量回归

### 3.3 一致性断言测试

新增 `RiskRegistryConsistencyTest`：
- 四级词典互斥（RED ∩ ORANGE ∩ YELLOW = ∅）
- 情绪集合三分类完备（NEGATIVE ∩ POSITIVE = ∅；UNKNOWN 为兜底）
- 关键信号抽样断言：`anxious`/`sad`/`想死`/`割腕` 在各消费管线入口返回一致结论
- 规则源只读断言（反射检查无状态字段或约定不可变）

## 4. SPEC

### 4.1 RiskKeywordRegistry

```
类：RiskKeywordRegistry（final，私有构造，静态方法）
常量：SCORE_HARD=85 / SCORE_ORANGE=60 / SCORE_YELLOW=35 / SCORE_ORANGE_MIN=30
     RISK_WEIGHTS（权重向量 10,0,0,0,0.8 的具名版本）
方法：
  matchLevel(text)      → Level { RED_HARD, ORANGE, YELLOW, NONE }
  matchMethod(text)     → 意图/方法/准备词命中列表
  scoreFor(text)        → 依据分级与权重计算风险评分（替代散落魔法数实现）
规则清单：与 design/04 逐条核对（实施前产出核对表，不改变语义）
```

### 4.2 EmotionVocabulary

```
类：EmotionVocabulary（final，私有构造，静态方法）
权威成员表：NEGATIVE = {sad, fearful, angry, anxious, disgusted, withdrawn, crisis, ...}
           POSITIVE = {calm, happy, relieved, hopeful, ...}（中英别名并入）
方法：
  classify(key|text)    → NEGATIVE | POSITIVE | UNKNOWN
  contains(key)         → 成员判断（消费点统一走此入口）
```

### 4.3 消费点替换清单

| 文件 | 现形态 | 替换为 |
|------|--------|--------|
| RiskDetectorServiceImpl L38-63/L76-117 | 内嵌词典 | `RiskKeywordRegistry` |
| ConversationRiskProcessor L225-240/L85/L162-175 | 内嵌词典+魔法数 | `RiskKeywordRegistry` |
| SessionState L125 | 内嵌集合 | `EmotionVocabulary` |
| SessionEndAnalyticsService L146-149 | 内嵌集合 | `EmotionVocabulary` |
| ConversationContextAgent L285-290 | 内嵌集合 | `EmotionVocabulary` |
| LongTermMemoryService L390-397 | 内嵌中文集合 | `EmotionVocabulary` |
| TemplateMatrixRegistry / SafetyKeywordLibrary | 重复词表 | 去重，引用注册表 |

## 5. 验收标准（EARS 风格）

- 当 `RiskKeywordRegistry` 与 `EmotionVocabulary` 建立后，全部风险/情绪判定消费点必须引用注册表，内嵌词表必须为零（grep 断言）
- 当「集合一致」断言测试运行时，四级词典互斥、情绪三分类完备、关键信号（anxious/sad/想死/割腕）跨管线结论一致必须通过
- 当全量回归运行时，后端测试必须与基线一致（1529 用例全绿，不新增失败）
- 当词典增删发生时，所有消费管线必须无需改动即传导（接口不变原则）
- 当评分计算切换为 `scoreFor` 后，既有风险分级测试的期望值必须不变（行为零变更）

## 6. 风险与回滚

- **风险**：极低——规则模块只读、纯静态数据 + 查找方法；唯一注意点是 `TemplateMatrixRegistry`/`SafetyKeywordLibrary` 若存在非风险用途的消费点，须先核对其语义再收编（实施第一步做引用分析）
- **依赖**：design/04 规则清单核对（实施前），与 ARCH-010 的模板路由收敛（B-4）无硬依赖
- **回滚**：逐文件 revert；规则源删除即回旧路径（消费点切换为独立提交）

## 7. 关联与落点

- 关联任务：ARCH-010（doing/70，主题词收敛 + 模板 key 统一）、ARCH-004（doing/64，假功能清理与之可并行）
- 关联设计：design/04 风险识别规则库（单一事实源）、design/14 儿童安全对话规范
- 词汇表：[13 领域词汇表](../13_领域词汇表.md) 风险安全域
- 登记：TASK-TRACKER §二十八 ARCH-003
