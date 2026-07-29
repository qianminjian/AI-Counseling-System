---
kind: adr
name: 采用 Agent 编排架构替代单 Prompt 对话引擎
slug: adr
category: adr
---

# 采用 Agent 编排架构替代单 Prompt 对话引擎

_来源：7fc75ef → ea0abe4 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
M1 原型仅使用单一硬编码 System Prompt，缺乏可插拔的 AI 能力扩展；商用版本需要支持安全检测、情绪识别、CBT 干预、最终回复生成等多个专业 Agent 协作。

## 决策驱动
- 可插拔的 AI 能力扩展
- 并行执行提升响应速度
- LLM 失败时降级到关键词规则兜底

## 备选方案
- **Agent 编排架构（Safety/Emotion/CBT/Conversation）** — 优点：职责分离、可独立测试和替换、Safety+Emotion 并行执行、LLM 失败可降级到 RiskDetectorService
- **单体 Prompt 链式调用** _（已否决）_ — 优点：实现简单；缺点：无法并行、难以替换单个能力、LLM 失败无优雅降级

## 决策
在 counseling-ai/agent 包定义统一 Agent 接口（execute/timeout/fallback），实现 SafetyAgent、EmotionAgent、CBTAgent、ConversationAgent，由 ConversationOrchestrator 通过 Java 21 StructuredTaskScope 编排执行，LLM 失败时回退到现有 RiskDetectorService。

## 影响
新增约 8 个 Agent 类和一个 Orchestrator，重构 ConversationServiceImpl；Prompt 模板从 classpath 加载（prompts/SYS-001~TSK-003 共 12 个文件）；ChatMemory 从内存迁移到 Redis（TTL=2h）。