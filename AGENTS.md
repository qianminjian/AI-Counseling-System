# AGENTS.md - Qoder 工作区顶层规则入口

> 适用项目：当前 Qoder 工作区（项目根目录）
> 加载方式：Qoder Agent 自动识别本文件 + `.qoder/rules/` 目录下所有规则文件
> 版本：v3.0 | 更新日期：2026-08-13（v3.0：收敛为纯导航，正文去重至规则文件，降低每轮 token 注入）

---

## 本文件定位（纯导航，不含规则正文）

本文件是**规则库的结构导航与指针**。所有规则正文统一收敛到 `.qoder/rules/*.md`，
避免同一内容在 AGENTS.md 与规则文件中重复、每轮被注入两遍。

**核心规则正文（每轮必注入）见：**
- `.qoder/rules/core-identity.md` —— 核心工程原则（KISS/YAGNI/SOLID/DRY）、沟通方式、默认工作流、冲突优先级
- `.qoder/rules/core-red-lines.md` —— 自主边界红线（7 条）、启动服务红线、部署纪律、安全编码红线、设计文档与代码一致底线

> ⚠️ 红线始终生效，不可被任何模式覆盖。红线正文以 `core-red-lines.md` 为唯一事实源。

---

## 规则库结构（Qoder 四类型映射）

`.qoder/rules/` 目录下规则按四种触发类型组织（**懒加载**：仅 always 每轮注入，其余按需触发）。

### 5.1 Always Apply（始终生效，每轮注入）

| 规则文件 | 核心内容 |
|---------|---------|
| `core-identity.md` | 核心工程原则 / 沟通方式 / 默认工作流 / 冲突优先级 |
| `core-red-lines.md` | 自主边界红线 / 启动服务红线 / 部署纪律 / 安全编码 / 设计文档一致底线 |

### 5.2 Specific Files（指定文件生效 - glob 通配符）

| 规则文件 | 触发 glob | 核心内容 |
|---------|----------|---------|
| `code-engineering.md` | `*.ts,*.tsx,*.js,*.jsx,*.py,*.go,*.rs,*.java,*.vue,*.svelte` | 测试规范 / Git 工作流 / 代码架构 / 错误处理 / 安全编码 / 资源管理 / 交付自检 |
| `code-finance-precision.md` | `*.java,*.go,*.py` 涉及金额计算 | 金融计算精度 / 舍入规则 / 数据脱敏 |

### 5.3 Model Decision（模型决策 - AI 自主判断场景）

| 规则文件 | 触发场景描述 | 核心内容 |
|---------|------------|---------|
| `work-management.md` | 任务启动 / 范围控制 / 失败升级 / Plan Mode | 三问锚定 / 范围纪律 / 失败升级三档 / 模式系统 |
| `ai-behavior.md` | 编码 / 调试 / 重构 | 编码前思考 / 简单优先 / 精准修改 / 目标驱动 |
| `agent-collaboration.md` | Subagent / Skill / Hook 设计 / MCP 调用 | Subagent 三要素 / Prompt 模板 / 多 Agent 编排 |
| `context-management.md` | 长会话 / 上下文压缩后 / 会话 >30 轮 | 会话阈值 / 压缩恢复 / 关键信息防丢失 |
| `design-document-management.md` | 设计文档讨论 / 子文档迭代 / 合并设计文档 | doing 子文档工作流：独立编号 / 开发期冻结 / 完成合并归档 his |

### 5.4 Apply Manually（手动引入 - @rule 触发）

| 规则文件 | 引用方式 | 适用场景 |
|---------|---------|---------|
| `design-persistence.md` | `@design-persistence` | 启动新项目 / Plan Mode / 设计讨论 / 创建 BEACON.md |
| `systematic-debug.md` | `@systematic-debug` | 任何 bug / test failure / 异常行为 |
| `tdd-workflow.md` | `@tdd-workflow` | 实现新功能 / bug fix |
| `ears-spec.md` | `@ears-spec` | 编写验收标准 / 测试用例 |
| `verification-checklist.md` | `@verification-checklist` | 交付前自检 / 准备 commit / PR 前 |
| `macos-path.md` | `@macos-path` | macOS 文件路径处理 / symlink 陷阱 |
| `think-deep.md` | `@think-deep` | `/tp` `/tt` `/ttt` `/tttt` 深度思考分层 |
| `framework-integration.md` | `@framework-integration` | 外部框架协作（GSD/Superpowers/atdo，项目未安装时无需引入） |

---

## 持久化途径（项目级 vs 会话级）

| 机制 | 存什么 | 生命周期 |
|------|--------|---------|
| `.qoder/rules/*.md` | 规则约束 | 项目生命周期 |
| `AGENTS.md` | 项目入口 + 规则导航（纯指针） | 项目生命周期 |
| `design/BEACON.md` | 项目设计决策、范围、当前状态 | 项目生命周期 |
| `design/doing/*.md` | 进行中的子设计文档 | 开发期 → 合并完成后归档 `design/his/` |
| `design/session-summary.md` | 长会话状态快照 | 会话级 |

---

## token 使用纪律（2026-08-13 新增）

为控制每轮上下文注入成本，全员（人 + AI）遵守：

1. **大文档分段读**：`design/*.md` 单文件 >50KB 时，用 `start_line/end_line` 范围读取，禁止全量 Read；优先读文件顶部索引头定位。
2. **规则不重复**：规则正文只写在 `.qoder/rules/`，AGENTS.md 不复制正文。
3. **长会话拆分**：会话 >30 轮主动开新会话，聚焦单一子目标（见 `context-management.md`）。
4. **每日监控**：运行 `scripts/token-usage-report.sh` 查看 token 消耗分布与优化建议。

---

_Qoder 工作区规则库导航 v3.0（2026-08-13 去重收敛）- 规则正文见 .qoder/rules/_
