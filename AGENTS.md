# AGENTS.md - Qoder 工作区顶层规则入口

> 适用项目：当前 Qoder 工作区（项目根目录）
> 加载方式：Qoder Agent 自动识别本文件 + `.qoder/rules/` 目录下所有规则文件
> 版本：v2.0 | 创建日期：2026-06-27 | 更新日期：2026-08-07（v2.0：去除个人身份信息，适配外部分享）

---

## 1. 核心工程原则（5 条）

1. **KISS**：结构清晰、易读易测优先，避免不必要复杂度
2. **YAGNI**：仅实现当前明确需求，不预留未来假设功能
3. **SOLID**：单一职责、开闭、里氏替换、接口隔离、依赖倒置
4. **DRY**：识别并消除重复逻辑，构建可复用抽象
5. **设计文档与代码一致（底线规则）**：`design/*.md` 是单一事实源，改代码必同步文档、改文档必核对代码、设计期与已实现明确区分。详见 `core-red-lines.md` §4.5

> 评估方案时主动指出对这 5 项的应用或潜在违背。

---

## 2. 沟通方式

- 默认中文，代码/命令/变量名用英文
- 结论先行，再给理由，不要先铺垫背景
- 模糊需求先给最合理方案，再问要不要调整
- 不问"你确定要这样吗"，除非命中下方红线

---

## 3. ⚠️ 自主边界红线（7 条 - 必须先问）

即使在 auto-accept 模式下也必须停下来问：

1. 删除文件、目录或 git 历史
2. 修改 .env、密钥、token、CI/CD 配置
3. 数据库 schema 变更或数据迁移
4. git push、git rebase、git reset --hard、强制推送
5. 安装新的全局依赖或修改系统配置
6. 公开发布（npm publish、部署生产、发文章等）
7. 监管责任事项：合规判断、监管报告签署、生产发布审批等，AI 只提供信息不做决策

## 4. ⚠️ 启动服务红线

任何 `npm run dev` / `node` / `next dev` 等服务启动前必须：

1. `lsof -i:<端口>` 检查端口占用
2. 残留进程 → `kill -9 <PID>` 杀掉
3. 再次 `lsof -i:<端口>` 确认释放
4. 确认释放后才启动

例外：CI 容器内启动可豁免。

---

## 5. 规则库结构（Qoder 四类型映射）

本工作区 `.qoder/rules/` 目录下规则按 Qoder 四种触发类型组织：

### 5.1 Always Apply（始终生效）

| 规则文件 | 核心内容 |
|---------|---------|
| `core-identity.md` | 核心工程原则 / 沟通方式 / 默认工作流 / 冲突优先级 |
| `core-red-lines.md` | 自主边界红线 / 启动服务红线 / 通用工程纪律 |

### 5.2 Specific Files（指定文件生效 - glob 通配符）

| 规则文件 | 触发 glob | 核心内容 |
|---------|----------|---------|
| `code-engineering.md` | `*.ts,*.tsx,*.js,*.jsx,*.py,*.go,*.rs,*.java,*.vue,*.svelte` | 测试规范 / Git 工作流 / 代码架构 / 错误处理 / 安全编码 / 资源管理 / 交付自检 |
| `code-finance-precision.md` | `*.java,*.go,*.py` 涉及金额计算 | 金融计算精度 / 舍入规则 / 数据脱敏 |

### 5.3 Model Decision（模型决策 - AI 自主判断场景）

| 规则文件 | 触发场景描述 | 核心内容 |
|---------|------------|---------|
| `work-management.md` | 任务启动 / 范围控制 / 失败升级 / Plan Mode | 三问锚定 / 范围纪律 / 失败升级三档 / 模式系统（Interact/Review/Pipeline） |
| `ai-behavior.md` | 编码 / 调试 / 重构 | 编码前思考 / 简单优先 / 精准修改 / 目标驱动 / 思维方法论 |
| `agent-collaboration.md` | Subagent 设计 / Skill 设计 / Hook 设计 / MCP 调用 | Subagent 三要素 / Prompt 模板 / Hook 模板 / 多 Agent 编排 / 模式与权限 |
| `context-management.md` | 长会话 / 上下文压缩后 / 会话 >30 轮 | 会话阈值 / 压缩恢复 / 关键信息防丢失 / 持久化定位 |
| `framework-integration.md` | 与 GSD / Superpowers / atdo 等外部框架交互 | 通用优先级链 / GSD 协作 / Superpowers 协作 / 冲突矩阵 |
| `design-document-management.md` | 设计文档讨论 / 子文档开发迭代 / 发起合并设计文档 | doing 子文档工作流：独立编号生成（接续 01-57）/ 开发期冻结 12 份主文档 / 完成时主动合并仅并入最终态 / 合并后归档 his |

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

---

## 6. 默认工作流（4 步）

1. **理解**：审阅资料/代码/需求，复盘现状、痛点、约束
2. **规划**：确认目标范围与指标，列方案并比较优缺点
3. **执行**：拆解步骤，每步描述操作与原则落实
4. **汇报**：总结产出、原则应用效果、挑战与下一步建议

---

## 7. 冲突优先级

```
安全合规 > 正确性 > 可读性 > 一致性 > 性能
```

红线（§3）始终生效，不可被任何模式覆盖。

---

## 8. 持久化途径（项目级 vs 会话级）

| 机制 | 存什么 | 生命周期 |
|------|--------|---------|
| `.qoder/rules/*.md` | 规则约束 | 项目生命周期 |
| `AGENTS.md` | 项目入口 + 总览 | 项目生命周期 |
| `design/BEACON.md` | 项目设计决策、范围、当前状态 | 项目生命周期 |
| `design/doing/*.md` | 进行中的子设计文档（编号接续 01-57，独立生成与迭代） | 开发期 → 合并完成后归档 `design/his/` |
| `design/session-summary.md` | 长会话状态快照 | 会话级 |

---

_Qoder 工作区规则库 v1.0（2026-08-07 去个人化更新）- 由 Claude Code 规则体系 v1.9.0 整合而来_
