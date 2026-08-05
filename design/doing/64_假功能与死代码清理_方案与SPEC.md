# 64 假功能与死代码清理（ARCH-004）方案与 SPEC

> 关联任务：ARCH-004（深度审计 B-1/B-6/B-7 + 僵尸 API + OVD-1/3 回填，doing/61 C3 深化，登记 TASK-TRACKER §二十八）
> 状态：📝 方案定稿 → ✅ 决策闭环（D-1 路径 B、D-6 台账修正，2026-07-28 钱敏健拍板）→ 待实施
> 依据：深度审计 2026-08-05（B-1/B-6/B-7、P2-1、OVD-1/OVD-3）、doing/61 §6 C3
> 词汇：假功能 / 僵尸代码 / 删除测试 / 台账一致性——见 [13 领域词汇表](../13_领域词汇表.md)

---

## 1. 背景与问题

审计确认「测试全绿 ≠ 功能存在」模式仍在，且台账存在失实：

**假功能（B-1/OVD-1）**：ORCH-006 性格微调——`PromptOrchestrationService` L117-137 为 `ProfileSignals` 6 字段（sensitivityUsable/curiosityUsable/copingSkillsUsable 等）实现了消费分支且单测通过，生产端 `StudentProfileService.getProfileSignals` L296-298 只填 4 字段（introversion/dominantInterests 等）、其余**恒 null** → 分支永远走默认路径。台账 L574/L614 登记「ORCH-001~008 已全部落地接线」名不副实。

**僵尸 API（7 处，P2-1）**：
| 项 | 位置 | 状态 |
|----|------|------|
| `CounselingSession.end()` | entity L82-86 | 零调用；硬编码 "completed" 不用自己的 `STATUS_COMPLETED` 常量 |
| `CounselingSession.upgradeRiskLevel()` | entity L88-93 | 零调用 |
| `PromptOrchestrationService.resolve()` | orchestrator L52-54 | 向后兼容死路径，零调用 |
| `SessionEndAnalyticsService.fuseEmotions()` | L128-130 | 薄转发，零调用 |
| `AiChatService.profilePrompt` | ai/chat 接口 | 僵尸参数，生产唯一调用点恒传 null |
| `contentHash` | 画像/记忆字段 | 从未赋值 |
| `OrchestrationContext` 6 参构造 | orchestrator | 仅测试使用 |

**台账虚标（B-6/B-7）**：
- MEM-103 登记「已接线」但 `LongTermMemoryService.evictOldMemories` L376-388 实际仅敏感度/时效/数量 3 维，**学生意愿维度恒 false**（TASK-TRACKER L379）
- ConversationServiceImpl 登记「777 行改善中」，实测 826 行（TASK-TRACKER L834）
- doing/61 三处断言不准确：C4 裸 fetch 实为 5 处（非 4 处）、C5 测试 mock 实为 18 个（非 15 个）、toolboxApi.test.ts 非空壳

**过度设计（OVD-3）**：groundedness 3 文件组件栈（GroundednessResult/effective/feedback + evaluateRetrievalEffectiveness + ContentGap）只写日志，`identifyContentGaps` 生产无消费者。

## 2. 目标与非目标

**目标**：
- 消灭「测试绿、生产死」假功能：ORCH-006 显式二选一并兑现，不留隐性债务
- 删除全部 7 处僵尸 API 与僵尸参数，接口与实际行为对齐
- 台账与代码事实对齐（MEM-103、行数、doing/61 断言）
- 过度设计收敛（OVD-3 组件栈砍到 1 文件）

**非目标**：
- 行为变更（除 ORCH-006 决策外，删除均为零行为损失）
- groundedness 的回收指标接入（评估口径调整，另议）
- design/44/50 主文档修改（合并归档时统一并入）

## 3. 设计方案

### 3.1 ORCH-006 取舍（待决策 D-1，doing/61 决策项）

**路径 A · 补全**（兑现设计意图）：
- `getProfileSignals` 提取 sensitivity/curiosity/copingSkills：从画像 JSONB（personality_traits/behavior_indicators）读取并映射
- 性格微调成为真实对话杠杆（高敏感降速、探索式引导、技能唤起）
- 工作量：画像字段核对 + 映射 + 消费分支联调；收益：PROF-021 设计闭环

**路径 B · 删除**（YAGNI 承认）：
- 删 `PromptOrchestrationService` 消费分支 + `ProfileSignals` 收缩到实际 4 字段 + 删除向后兼容构造
- 台账 ORCH-006 改为「按设计未接线，目标态保留 design/44」

**建议**：路径 B 先行（风险最低、立即消除认知税），路径 A 作为独立产品决策再评估——画像 P2 字段是否值得兑现由产品目标决定，不应与清理任务捆绑。**✅ 2026-07-28 钱敏健拍板：路径 B**。

### 3.2 僵尸 API 删除

- `CounselingSession.end()`：删除或改用 `STATUS_COMPLETED` 常量（若框架反射需要则保留但修硬编码——实施时按引用分析结果定）
- `upgradeRiskLevel()` / `resolve()` / `fuseEmotions()`：直接删除（含测试内引用同步清理）
- `AiChatService.profilePrompt`：接口签名减 1 参，调用点同步
- `contentHash`：删除字段或接线（实施时按引用分析；建议删除）
- `OrchestrationContext` 6 参构造：删至生产实际使用的构造（保留测试可用的最小面，或测试改用生产构造）

### 3.3 台账对齐（B-6/B-7）

- MEM-103（✅ D-6 已决策 2026-07-28）：台账修正为「敏感度/时效/数量 3 维 + 学生意愿未接线（待画像字段就绪）」+ **同步修正 L207 注释**（现声明 4 维排序与实现不符）
- TASK-TRACKER L834 行数修正 777→826
- doing/61 三处断言修正（C4 裸 fetch 5 处、C5 mock 18 个、toolboxApi 非空壳）
- OD-013（TTS 面板台账）归 ARCH-009 修复后联动修正，本任务不处理

### 3.4 OVD-3 groundedness 收敛

- 3 文件组件栈合并为 1 个文件（`GroundednessResult` 及 effective/feedback 归并），`evaluateRetrievalEffectiveness` 保留实际调用面，删除 `identifyContentGaps` 无消费者路径
- 只写日志 → 增加 metrics 计数（与 ARCH-010 异常可观测一致，可后置）

## 4. SPEC

```
D-1（ORCH-006）：✅ 已决策（2026-07-28 钱敏健）路径 B——删消费分支 + ProfileSignals 收缩 4 字段 + 台账改「目标态保留」
D-6（MEM-103）：✅ 已决策（2026-07-28 钱敏健）台账修正——evictOldMemories 保持 3 维，标注「学生意愿未接线（待画像字段就绪）」；**同步修正 L207 注释**（4 维声明 → 3 维事实 + 未接线标注）
删除清单：upgradeRiskLevel / resolve / fuseEmotions / profilePrompt / contentHash / 6 参构造
断言：删除后 grep 上述符号必须为零调用（含测试）
台账修正：L834（777→826）/ L379（MEM-103 状态更正）/ doing/61 三处
```

## 5. 验收标准（EARS 风格）

- 当清理完成后，`upgradeRiskLevel()`/`resolve()`/`fuseEmotions()` 必须为零调用（grep 断言，含测试）
- 当 `profilePrompt` 删除后，`AiChatService` 接口签名必须减少 1 参
- 当 `ProfileSignals` 收缩（路径 B）后，接口字段必须与生产实际赋值一一对应（无恒 null 字段）
- 当 L207 注释修正后，`evictOldMemories` 注释必须与实际淘汰维度一致（3 维 + 学生意愿未接线标注）
- 当台账修正完成后，TASK-TRACKER L834 行数、L379 MEM-103、doing/61 三处断言必须与代码事实一致
- 当全量回归运行时，后端测试必须与基线一致（1529 用例全绿，删除项相关用例同步更新后仍全绿）
- 当 OVD-3 收敛后，groundedness 相关文件必须 ≤1 个且无「只写日志」的组件栈

## 6. 风险与回滚

- **风险**：低——删除路径全部为集中复杂度；唯一决策风险是 D-1 路径选择（补全为产品决策，不捆绑清理）
- **注意**：删除前须做引用分析（LSP findReferences）确认零调用，防止审计盲区
- **回滚**：删除项可单提交 revert；D-1 路径 B 后如需补全按路径 A 独立立项

## 7. 关联与落点

- 关联任务：ARCH-009（doing/69，OD-013 修复后联动台账）、ARCH-010（doing/70，魔法数与异常可观测）
- 关联设计：design/44（ORCH-006 目标态）、design/50（MEM-103 目标态）、design/49（groundedness）
- 词汇表：[13 领域词汇表](../13_领域词汇表.md)
- 登记：TASK-TRACKER §二十八 ARCH-004
