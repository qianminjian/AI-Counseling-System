---
trigger: model-decision
description: Subagent 设计、Skill 设计、Hook 设计、MCP 使用规范——当用户要求设计或调用子智能体、技能、钩子、外部 MCP 工具时触发
---

# agent-collaboration.md - Agent 协作模式

> 加载方式：模型决策（Model Decision）
> 触发场景：调用 Subagent / 设计 Skill / 编写 Hook / 多 Agent 协作 / 调用 MCP
> 来源：整合自 Claude Code `rules/agent-sdk-patterns.md`

---

## 1. Subagent 设计规范

### 1.1 三要素

| 要素 | 缺失后果 |
|------|---------|
| 角色定义（一句话解决什么问题） | 调用时机模糊 |
| 工具隔离（可用/不可用工具清单） | 越权操作 |
| Prompt 模板（背景→目标→约束→输出格式） | 返回垃圾信息 |

### 1.2 已验证可复用的 Subagent 模式

| Agent | 用途 | 工具集 | 调用时机 |
|-------|------|--------|---------|
| Explore | 代码搜索 | 只读 | 范围 >3 文件时 |
| Plan | 方案设计 | 只读 | 非琐碎实现任务 |
| Debugger | 错误调试 | 全工具 | bug / test 失败 |
| CodeReviewer | 代码审查 | 只读 + Grep | 完成功能后 |

### 1.3 预制 Prompt 模板

**Explore Agent**：

```
[角色]: 代码探索专家
[目标]: 找到 [符号] 的定义与引用
[约束]: 只读工具，返回 <150 字
[输出]: Markdown 表格：文件路径 | 行号 | 用途
```

**Plan Agent**：

```
[角色]: 软件架构师
[目标]: 基于 [需求] 设计方案
[约束]: 只读，列出 ≥2 方案
[输出]: 方案对比表 + 推荐 + 风险清单 + 文件变更列表
```

**CodeReview Agent**：

```
[角色]: 代码审查专家
[目标]: 审查 [文件列表]
[约束]: 只读 + Grep，输出分级 (P0/P1/P2)
[输出]: 问题清单（文件 + 行号 + 严重度 + 描述 + 修复建议）
```

**Debugger Agent**：

```
[角色]: 调试专家
[目标]: 复现并修复 [bug]
[约束]: 全工具，一次改一个变量
[输出]: 根因分析 (≤3 句) + 修复方案 + 验证方法
```

### 1.4 Subagent 隔离原则

- 大输出（>1KB）丢给子 agent，主上下文只收摘要
- 子 agent prompt 必须含：背景 + 目标 + 工具约束 + 输出格式
- 返回结果 < 200 字，关键信息用结构化格式

### 1.5 并行调用原则

- 独立任务必须并行（Read 多文件、多 Explore agent）
- 有依赖任务必须串行（先 BEACON → 后 PLAN）
- 并行上限 3（避免混乱）

---

## 2. Skill vs Subagent 选择

| 维度 | Skill | Subagent |
|------|--------|---------|
| 需要长上下文隔离 | 否 | 是 |
| 需要工具权限 | 通常不需要 | 经常需要 |
| 独立完成完整任务 | 否 | 是 |
| 触发方式 | description 匹配 | Agent tool 调用 |
| 适用场景 | 单次操作 / 模板填充 | 调研 / 审计 / 多步实现 |

### 2.1 description 写法

| 差 | 好 |
|----|-----|
| "use this for anything related to data" | "Extract design primitives from public websites and generate token files" |
| "helps with coding" | "Optimize Postgres queries with index recommendations and EXPLAIN analysis" |
| "general purpose" | "Generate PDF with token-based design system for client-ready documents" |

---

## 3. Hook 设计模式

### 3.1 Hook 通用模板

```bash
#!/bin/bash
# [Hook名称] — [一句话用途]
INPUT="$1"
DENY_PATTERN="[待替换]"
if echo "$INPUT" | grep -qE "$DENY_PATTERN"; then
  echo '{"decision":"block","reason":"[拦截原因]"}'
  exit 0
fi
echo '{"decision":"allow"}'
```

### 3.2 推荐的 Hook 清单

| Hook | 类型 | 用途 | 拦截模式 |
|------|------|------|---------|
| `block-rm-rf.sh` | PreToolUse | 拦截危险 Bash | `rm -rf | mkfs | dd if= | shutdown` |
| `block-secrets-write.sh` | PreToolUse | 拦截敏感信息写入 | API key / 身份证 / 手机号 正则 |
| `block-external-net.sh` | PreToolUse | 拦截外网请求 | `curl|wget` 非 localhost |
| `block-system-write.sh` | PreToolUse | 拦截系统目录写入 | `/etc/ /usr/ /var/lib/` 等路径 |
| `auto-format.sh` | PostToolUse | 自动格式化 | 根据扩展名调 prettier/black |
| `auto-test.sh` | PostToolUse | 自动跑测试 | 检测 test 文件变更 → 跑匹配 runner |
| `auto-lint.sh` | PostToolUse | 自动 Lint | `npm run lint -- <changed-file>` |

### 3.3 Hook 设计约束

- PreToolUse Hook 延迟 < 2s，超时移到 PostToolUse
- Hook 只做本地校验，禁止网络调用
- 每个 Hook 文件创建后必须 `chmod +x`

---

## 4. MCP Server 使用规范

### 4.1 何时用 MCP vs 直接调用

| 场景 | 用 MCP | 不用 MCP |
|------|--------|---------|
| 库文档查询（React/Next/Prisma） | ✅ context7 | - |
| 代码符号搜索 / 跨文件编辑 | ✅ serena | - |
| 飞书会话 / 文档 | ✅ opensquilla / lark-* | - |
| 纯本地操作 | - | ✅ Read/Write/Edit/Bash |
| 一次性网页抓取 | - | ✅ WebFetch |

### 4.2 MCP 结果处理

| 结果特征 | 处理方式 |
|---------|---------|
| 大输出（>1KB） | Subagent 隔离，主上下文只接摘要 |
| 错误（连接失败/超时） | 降级到 WebFetch 或直接放弃 |
| 部分成功 | 标记 partial success，不假装全成功 |

---

## 5. 多 Agent 编排模板

### 5.1 串行四角色模式

```
[1] Plan agent    → 方案         标记：【分析完成】
[2] Code agent    → 代码         标记：【编码完成】
[3] Test agent    → 测试+报告    标记：【测试完成】
[4] Code-reviewer → 审核意见     标记：【全部任务结束】
```

- 角色 2/3 遵守 TDD（@tdd-workflow）
- 角色 2/4 遵守精准修改（ai-behavior §3）
- 角色权限受 Profile 约束

### 5.2 并行探索模式

同时启动 3 个 Explore agent 搜索不同维度，主 agent 合并输出对比表。

### 5.3 角色切换协议

- 每角色结束用任务列表更新状态
- 输出统一到 `_scratch/<role-id>/` 或 `output/`
- 全自动流转仅在 Pipeline 模式启用

---

## 6. 反模式清单

| 反模式 | 后果 | 正确做法 |
|--------|------|---------|
| Skill 包装长任务 | 主上下文被污染 | 改用 Subagent |
| PreToolUse Hook 延迟 >2s | 每次操作都慢 | 移到 PostToolUse 或后台 |
| MCP 调用不带 timeout | 主流程卡死 | 必带 timeout + 降级方案 |
| Subagent prompt 缺输出格式 | 返回大段垃圾 | 强制 < 200 字 + 结构化 |
| 多 Agent 串行无任务列表 | 不知道卡在哪 | 每角色结束更新 TaskList |
| Hook 中调用网络 | Hook 变慢点 | Hook 只做本地校验 |
| 用 Bash 模拟 Subagent | 失去上下文隔离 | 直接用 Agent tool |

---

## 7. 与现有规则的关系

| 规则文件 | 关系 |
|---------|------|
| `core-red-lines.md` | 红线不被任何模式覆盖，始终生效 |
| `work-management.md` §5 | 模式系统 canonical 定义 |
| `ai-behavior.md` | 编辑工具优先级，Pipeline 下并行被覆盖 |
| `context-management.md` | 长会话对 Subagent/MCP 结果的隔离策略 |

---

_Agent 协作模式 v1.0 - 由 Claude Code `rules/agent-sdk-patterns.md` 整合_