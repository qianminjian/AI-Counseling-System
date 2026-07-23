---
trigger: specific
globs: "*.ts,*.tsx,*.js,*.jsx,*.py,*.go,*.rs,*.java,*.vue,*.svelte,*.kt,*.swift,*.c,*.cpp,*.h,*.m"
description: 编辑源代码文件时自动生效——测试规范、Git 工作流、代码架构、错误处理、安全编码、资源管理、交付自检
---

# code-engineering.md - 源代码工程实践

> 加载方式：编辑源代码文件时自动生效（Specific Files）
> 来源：整合自 Claude Code `rules/engineering-practices.md` + 部分 `rules/ai-behavior.md` §7-8
> **不适用于**：文档/Markdown/配置文件（避免误触发）

---

## 1. 测试规范

### 1.1 测试分层

| 类型 | 覆盖目标 | 覆盖率要求 |
|------|---------|----------|
| 单元测试 | 核心业务函数 | ≥ 75% |
| 集成测试 | API 端到端流程 | 关键路径 100% |
| 回归测试 | bug 修复后必加 | 100%（每个 bug 一条） |

**验证**：`npm test` / `pytest` / `go test` 通过且覆盖率达标。

### 1.2 外部 API 必须 Mock

- ✅ `jest.mock('./api')` / `pytest-mock` / 依赖注入
- ❌ 测试中真实请求 `https://api.example.com`（禁止调用付费 API）

### 1.3 测试命名（描述行为，不描述实现）

| 好的命名 | 不好的命名 |
|---------|----------|
| `test_应该拒绝空用户名登录` | `test_checkUsername` |
| `test_超长输入应触发异常` | `test_long_input` |
| `test_支付成功时发送通知邮件` | `test_sendEmail` |

### 1.4 TDD 三步法（Red → Green → Refactor）

```
[1] 编写测试文件 → [2] 运行确认 FAIL（必须步骤）→ [3] 编写实现代码
→ [4] 运行确认 PASS → [5] 补充集成测试 → [6] 边界值与异常测试
```

详见 `@tdd-workflow`。

---

## 2. Git 工作流

### 2.1 提交格式（Angular 规范）

```
<type>(<scope>): <subject>     # subject ≤ 50 字符
```

**type 类型**：`feat` / `fix` / `docs` / `style` / `refactor` / `test` / `chore` / `perf` / `ci` / `revert` / `security` / `hotfix`

### 2.2 原子提交

- 一个 commit 只解决一个问题或实现一个功能
- 提交前跑测试 + lint
- 工作区干净后再创建分支

### 2.3 分支命名

```
feat/{简述} | fix/{简述} | refactor/{简述} | docs/{简述}
test/{简述} | chore/{简述} | perf/{简述} | hotfix/{简述}
```

### 2.4 合规审计追溯

- commit message 必须关联工单号/需求编号（如 `feat(payment): 对公转账指令拆分 JIRA-4521`）
- 涉及资金/账务的变更，commit body 必须说明业务影响范围

---

## 3. 代码架构

### 3.1 文件行数约束

| 语言类型 | 单文件行数上限 |
|---------|--------------|
| 动态语言（Python / JS / TS） | 300 行 |
| 静态语言（Java / Go / Rust） | 400 行 |
| 目录文件数上限 | 8 个 / 层 |

超出时主动询问用户是否需要拆分。

### 3.2 优先使用成熟库

- 优先调研 npm/pypi 是否有现成方案
- 避免重复造轮子（特别是解析 HTML/Markdown、日期处理、字符串处理）

| 反例 | 正例 |
|------|------|
| `html.replace(/<[^>]+>/g, '').trim()` | `new DOMParser().parseFromString(html, 'text/html').body.textContent` |

### 3.3 TypeScript 类型安全

- **禁止** `any` 绕过类型检查。不用 `: any` / `as any` / `@ts-ignore` / `@ts-nocheck`
- 用 `unknown` + type guard、明确的 `interface`、或 `declare module` 替代
- 实在无法避免时，留下一行注释说明「为什么必须用 any」和「什么条件满足后可移除」

### 3.4 依赖审计

- 新增依赖前跑安全审计：`npm audit` / `pip-audit` / `cargo audit`
- 检查 License 兼容性（避免 GPL 传染到商业项目）
- unpinned 依赖锁定到具体版本号

---

## 4. 错误处理

### 4.1 错误分类

| 类别 | 处理 |
|------|------|
| **4xx** 客户端错误 | 参数无效、权限不足 → 不重试 |
| **5xx** 服务器错误 | API 异常 → 可重试 |
| **业务错误** | 响应体 `status_code` 非 0 → 抛出给用户 |

### 4.2 错误日志格式

| 反例 | 正例 |
|------|------|
| `console.error('Error')` | `console.error('API 调用失败', { url, method, status, error })` |

### 4.3 优雅降级

- API 不可用时提供默认值或降级方案
- 用户能看到友好的错误提示

### 4.4 禁止空 catch 吞噬错误

- **禁止** `try { ... } catch {}` 空捕获
- 每个 `catch` 块必须：记录错误上下文 + 明确处理决策（重试 / 降级 / 重新抛出）

---

## 5. 安全编码（详见 core-red-lines.md §3）

仅作要点重述，完整规则以 `core-red-lines.md` §3 为准：

- 注入防护：SQL / XSS / CSRF / 路径穿越 / 命令注入
- 敏感信息：密钥 / 身份证 / 手机号 / 银行卡 / DSN（命中红线立即停止）
- 硬编码凭据：必须从环境变量读取

---

## 6. 资源管理

- `useEffect` 必须有 cleanup 函数清理事件监听器（防止内存泄漏）
- 及时释放 Blob URL、定时器等临时资源
- 用 `ref` 管理 DOM 引用

---

## 7. 调试功能管理

### 7.1 醒目标记

```typescript
// DEBUG: 临时调试 - 完成后移除
<button className="bg-yellow-50 border-2 border-dashed border-yellow-400">🔧 调试</button>
```

### 7.2 及时清理

- 部署前确认无调试代码残留
- 调试功能加功能开关（环境变量控制）

---

## 8. 修改验证（编码后必做）

使用 Edit / Write 后必须验证：

1. 用 `grep` 搜索关键函数/变量名确认代码写入
2. 检查编译错误（`tsc --noEmit` / `mypy` / `go build` / `cargo check`）
3. 必要时启动开发服务器验证功能
4. 跑测试 + lint

**反例**：改完不验证，diff 未接受，功能不生效。

---

## 9. 交付自检清单

完成一个功能模块后，逐项过一遍：

| 层面 | 检查项 |
|------|--------|
| 代码 | 无 TODO / 硬编码 / 单元测试覆盖核心 / 集成测试覆盖交互 / 边界值测试 |
| Git | 独立分支按命名规范 / 原子提交 / Commit Message Angular 格式 |
| 自动化 | Pipeline 绿灯 / 无 lint 错误 / 覆盖率达标 |
| Review | 已批准 / 评论已处理 / Merge 条件满足 |

详见 `@verification-checklist`。

---

## 10. 项目结构约定（推荐）

### 10.1 永久资产（Git 提交）

| 目录 | 用途 |
|------|------|
| `design/` | BEACON.md + decisions/（ADR）+ discussion/（定型结论）+ reference/（外部参考） |
| `docs/ai-prompt/` | AI Prompt 模板 |
| `docs/ai-logs/` | 重要功能会话日志归档 |
| `.qoder/rules/` | Qoder 项目级规则库 |

### 10.2 临时产物（gitignore）

| 目录 | 用途 |
|------|------|
| `_scratch/reports/` | 测试报告、审计报告 |
| `_scratch/buginfo/` | Bug 报告、复盘 |
| `_scratch/test-*/` | 临时测试产物（可清理） |
| `_scratch/coverage/` | 覆盖率报告 |

### 10.3 根目录允许范围

项目根目录只允许：Git 配置、核心交付物（README.md）、源码根（src/）、scripts/、docs/、design/、AGENTS.md、`.qoder/`、测试框架配置、CI 配置、pre-commit 配置。

**反向引用禁令**：核心代码不得引用 `design/` `_scratch/` 下任何文件。

---

## 11. 跨平台路径处理（macOS 符号链接陷阱）

> 触发场景：macOS 上涉及 `path.resolve` + symlink 的代码

**核心问题**：macOS `path.resolve` 不展开符号链接，`fs.realpathSync` 展开——两者混用导致路径白名单检查假阳/假阴。

**正确做法**：

```javascript
// 方案 A：两侧 realpath 归一化（推荐，文件存在时）
const stateDirReal = fs.realpathSync(STATE_DIR);
const targetReal = fs.existsSync(filepath)
  ? fs.realpathSync(filepath)
  : path.resolve(filepath);
if (!targetReal.startsWith(stateDirReal + path.sep)) die('reject');

// 方案 B：文件不存在时回退到 lexical 比较
if (!path.resolve(filepath).startsWith(stateDirReal + path.sep)) die('reject');
```

**自检清单**：

- [ ] 路径白名单用 `fs.realpathSync` 双侧归一化
- [ ] 文件不存在场景有降级路径（lexical 检查）
- [ ] 在 macOS 和 Linux 都跑过
- [ ] 测试覆盖：路径穿越、symlink 逃逸、不存在的中间目录

详见 `@macos-path`。

---

_源代码工程实践 v1.0 - 编辑源代码时自动加载_