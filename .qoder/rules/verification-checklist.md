---
trigger: manual
description: 交付前自检清单——完成功能模块、准备 commit/PR 前手动引入
---

# verification-checklist.md - 交付自检清单

> 引用方式：`@verification-checklist`
> 触发场景：完成功能模块 / 准备 commit / 准备 PR / 用户说"做完了"
> 来源：整合自 Claude Code `rules/engineering-practices.md` §8 + `rules/ai-behavior.md` §8

---

## 1. 完成 ≠ 验证完成

**编辑文件 ≠ 任务完成**。

只有跑完所有验证项，才能说"完成"。

---

## 2. 编码后 5 检查（最小集）

```
[1] 跑测试确认通过
[2] 跑 lint 确认无新错误
[3] 检查无调试语句残留（console.log / print）
[4] 检查无密钥 / 硬编码
[5] 自问「这一行修改能追溯到用户需求吗？」
```

---

## 3. 完整交付清单（按层面）

### 3.1 代码层

| 检查项 | 通过标准 |
|--------|---------|
| 无 TODO 残留 | `grep -rn "TODO\|FIXME" src/` 仅包含已记录的 |
| 无硬编码密钥 | `grep -rn "password\|token\|api_key" src/` 无明文 |
| 单元测试覆盖核心 | 覆盖率达标（本项目 CI 门禁：核心 service 层 ≥60%、整体 ≥45%；其余项目 ≥75%） |
| 集成测试覆盖交互 | 关键 API 端到端跑通 |
| 边界值测试 | 空/null/超长/并发都有覆盖 |
| 无调试代码残留 | 无 `console.log` / `print` / 注释掉的代码 |
| 类型检查通过 | `tsc --noEmit` / `mypy` 无错误 |

### 3.2 Git 层

| 检查项 | 通过标准 |
|--------|---------|
| 分支命名规范 | `feat/xxx` / `fix/xxx` 等 |
| 原子提交 | 一个 commit 只解决一个问题 |
| Commit message 格式 | Angular 规范，subject ≤ 50 字符 |
| 关联工单号 | 金融项目 commit 必须含工单号 |
| 工作区干净 | `git status` 无未跟踪/未提交 |

```bash
# 提交前快速验证
git status                              # 工作区干净
git log --oneline -5                    # commit 历史合理
git diff --stat HEAD~1                  # 单次改动范围合理
```

### 3.3 自动化层

| 检查项 | 通过标准 |
|--------|---------|
| Pipeline 绿灯 | CI 通过 |
| 无 lint 错误 | `npm run lint` / `pylint` 通过 |
| 覆盖率达标 | 与 CI 门禁一致（本项目：核心 service 层 ≥60%、整体 ≥45%） |
| 无新增警告 | `tsc` / `mypy` 无新增 warning |

### 3.4 文档层

| 检查项 | 通过标准 |
|--------|---------|
| README 更新 | 新功能/参数有文档 |
| API 文档同步 | OpenAPI/Swagger 已更新 |
| CHANGELOG 更新 | 记录变更 |
| BEACON.md 更新 | 当前状态反映最新进展（@design-persistence） |

### 3.5 评审层

| 检查项 | 通过标准 |
|--------|---------|
| PR 描述完整 | 业务影响 / 改动范围 / 测试情况 |
| 自审通过 | 完成 3.1-3.4 全部检查 |
| 评论已处理 | 团队评审意见已 reply |
| Merge 条件满足 | CI 绿 + 批准 ≥ N 人 |

---

## 4. 验证命令清单（按技术栈）

### 4.1 JavaScript / TypeScript

```bash
npm test                  # 跑测试
npm run test:coverage     # 覆盖率
npm run lint              # lint
npm run type-check        # tsc --noEmit
npm run build             # 构建
```

### 4.2 Python

```bash
pytest                    # 测试
pytest --cov=src          # 覆盖率
pylint src/               # lint
mypy src/                 # 类型检查
python -m build           # 构建
```

### 4.3 Go

```bash
go test ./...             # 测试
go test -cover ./...      # 覆盖率
golangci-lint run         # lint
go build ./...            # 构建
```

### 4.4 Java (Maven)

```bash
mvn test                  # 测试
mvn verify                # 全套验证
mvn checkstyle:check      # 风格检查
mvn package               # 打包
```

### 4.5 Rust

```bash
cargo test                # 测试
cargo clippy              # lint
cargo build               # 构建
```

---

## 5. 提交前最后检查（30 秒）

```bash
# 1. 看一眼改动范围
git diff --stat

# 2. 确认无敏感信息
git diff | grep -iE "password|token|api_key|secret"

# 3. 确认无调试代码
git diff | grep -E "console\.log|print\(|TODO|FIXME"

# 4. 跑测试 + lint
npm test && npm run lint
```

**任一项不通过 → 不要 commit，先修复。**

---

## 6. 反模式

| 反模式 | 后果 |
|--------|------|
| 改完不验证直接说"完成" | diff 未生效或引入 bug |
| 跑测试失败就注释掉 | CI 假绿 |
| 跳过 lint 警告 | 风格债累积 |
| 提交含调试代码 | 部署后产生无意义日志 |
| 提交含硬编码 | 红线 #2 |
| 一个 commit 改多个功能 | 难以 revert |

---

## 7. 验证失败时的处理

参考 `@systematic-debug`：

1. 跑测试 FAIL → 不要急着改，先读错误
2. 修复后必须重跑全套验证
3. 多次失败 → 升级到根因分析（5 Why）

---

## 8. 与其他规则的关系

| 规则文件 | 关系 |
|---------|------|
| `code-engineering.md` §8 | 修改验证（编码后必做） |
| `ai-behavior.md` §8 | 编码后 5 检查 |
| `systematic-debug.md` | 验证失败的根因分析 |
| `core-red-lines.md` §4.2 | 不绕过报错 |

---

_交付自检清单 v1.0 - 任何交付前手动引入_