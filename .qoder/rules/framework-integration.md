---
trigger: model-decision
description: 外部框架协作约定——当用户调用 GSD / Superpowers / atdo 等外部工作流框架，或多框架共存时触发
---

# framework-integration.md - 外部框架协作约定

> 加载方式：模型决策（Model Decision）
> 触发场景：与 GSD / Superpowers / atdo 等外部框架交互时
> 来源：整合自 Claude Code `rules/collaboration/framework-integration.md`

---

## §1 通用优先级链

本规则体系与任何外部框架交互时，以下优先级**始终生效**：

```
本规则红线（core-red-lines.md）> 框架工作流状态 > 框架默认行为
```

- 红线命中 → 立即停止，不由框架覆盖
- 框架状态约束 ≥ 本规则工程实践
- 框架默认行为可被本规则覆盖

---

## §2 GSD 框架协作

### 2.1 GSD 是什么

项目级工作流框架（plan → execute → verify），含约 36 agents、~50 skills、15 hooks。

### 2.2 边界约定

| 维度 | GSD 负责 | 本规则负责 |
|------|---------|----------|
| 项目工作流 | new-project → plan-phase → execute-phase | - |
| 状态管理 | `.planning/` 下所有文件 | - |
| 进度跟踪 | 阶段 / 任务 / 验证 | - |
| 用户行为 | - | 协作约定 / 红线 / 沟通方式 |
| 工程规范 | - | 代码风格 / 测试 / Git |
| 安全边界 | - | 敏感信息 / 密钥 / 目录边界 |

### 2.3 GSD agents 调用本规则的方式

GSD agents 在执行时应：

1. Read `.qoder/rules/ai-behavior.md` → 了解编码行为准则
2. Read 项目级 `.qoder/rules/*.md` → 了解项目规范
3. 在生成代码/计划时遵守上述规则

### 2.4 GSD 不修改本规则文件

GSD agents / hooks **禁止**修改：`AGENTS.md`、`.qoder/rules/*.md`、项目级 `AGENTS.md`。如需新规则 → 提示用户手动添加。

### 2.5 GSD 命令 → 规则文件映射

| GSD 命令 | 触发本规则 |
|---------|----------|
| `/gsd-new-project` | core-red-lines.md |
| `/gsd-plan-phase` | ai-behavior.md + code-engineering.md |
| `/gsd-execute-phase` | 全部 always + 项目级 specific |
| `/gsd-verify-work` | code-engineering.md（验证规范） |
| `/gsd-code-review` | 项目级 code-style.md + security |
| `/gsd-debug` | core-red-lines.md + work-management.md §2.3 |
| `/gsd-secure-phase` | core-red-lines.md + code-engineering.md §5 |

---

## §3 Superpowers 框架协作

### 3.1 Superpowers 是什么

工作流技能集合（v5.1.0），主要提供：brainstorming / TDD / debugging / verification / PR 模板 / 贡献规则。

### 3.2 边界约定

**Superpowers 已覆盖的能力，本规则不重复**：

- TDD 工作流 → 用 `test-driven-development` skill（亦可 `@tdd-workflow`）
- Debugging 流程 → 用 `systematic-debugging` skill（亦可 `@systematic-debug`）
- PR 模板 → 用 Superpowers 默认模板

**本规则补充 Superpowers 没有的**：

- 沟通与协作约定（core-identity.md）
- 自主边界红线
- 中文交流约定
- 金融行业特定要求（`code-finance-precision.md`）

### 3.3 Superpowers Skill → 规则文件映射

| Superpowers Skill | 触发本规则 |
|------------------|----------|
| `test-driven-development` | code-engineering.md §1 |
| `systematic-debugging` | work-management.md §2.3 |
| `writing-plans` | ai-behavior.md §1 |
| `executing-plans` | ai-behavior.md §3 + code-engineering.md |
| `verification-before-completion` | code-engineering.md §8-9 |
| `requesting-code-review` | 项目级 code-style.md + api-conventions.md |
| `receiving-code-review` | ai-behavior.md（反馈处理） |
| `security-review` | core-red-lines.md + code-engineering.md §5 |

---

## §4 atdo 自动化执行框架

### 4.1 atdo 是什么

完全自动化的分阶段项目执行器：读取项目计划 → 顺序执行阶段 → 自动审计 → 自动修复 → 阶段门禁跑集成测试 → 自动提交。

### 4.2 协作约定

- atdo 自动执行时仍受红线约束（自动停止不豁免红线）
- atdo 的产物（修复 commits）需用户在 phase gate 处审查
- 关键决策点 atdo 主动停下询问用户

---

## §5 三框架冲突矩阵

| 冲突场景 | 优先级 | 说明 |
|---------|--------|------|
| GSD 自动创建目录 vs 用户红线 | 红线优先 → core-red-lines.md | GSD 提示而非自动创建 |
| Superpowers 禁止第三方依赖 vs 个人项目 | 本规则优先 → code-engineering.md §3.4 | 个人项目灵活 |
| GSD plan 输出格式 vs 本规则工程规范 | GSD 格式优先 | 代码生成遵守 ai-behavior.md |
| Superpowers TDD vs 本规则 TDD | 互补 → code-engineering.md §1.3 | 不冲突 |

---

## §6 统一风险与缓解

| 风险 | 缓解 |
|------|------|
| 框架 hook 与本规则冲突 | 优先 hook 强制行为 |
| 框架 agent 不读取本规则 | 项目级 AGENTS.md 明确引用 |
| 本规则更新后框架缓存旧版 | 重启会话 |
| 框架强制创建文件超红线 | 用户红线优先，框架提示而不自动创建 |
| 框架版本升级行为变化 | CHANGELOG.md 记录 |
| 多框架界限混淆 | GSD=项目工作流 / Superpowers=技能集合 / atdo=自动化执行 / 本规则=全局约束 |

---

_外部框架协作约定 v1.0 - 与 work-management.md §5 模式系统配合：Interact 模式下主动加载本文件_