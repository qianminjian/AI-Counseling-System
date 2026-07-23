---
trigger: manual
description: TDD 三步法工作流——实现任何新功能或修复 bug 时手动引入
---

# tdd-workflow.md - TDD 三步法

> 引用方式：`@tdd-workflow`
> 触发场景：实现新功能 / 修复 bug / 任何涉及修改源码的任务
> 来源：整合自 Claude Code `rules/engineering-practices.md` §1.3 + Superpowers `test-driven-development` skill

---

## 核心理念

**测试不是验证代码的手段，而是定义行为规范的手段。**

---

## 1. TDD 三步法（Red → Green → Refactor）

### 步骤 1：Red（写失败的测试）

- 写**描述目标行为**的测试
- 运行确认 **FAIL**（必须步骤，绿了 = 测试写错）
- 跳过这一步直接写实现 → 红线

### 步骤 2：Green（让测试通过）

- 写**最少量代码**让测试通过
- 不做推测性实现
- 顺手加"以后可能用到"的功能 → 红线

### 步骤 3：Refactor（重构）

- 重构代码，保持测试绿
- 不添加新功能
- 重构时顺带加新功能 → 红线

---

## 2. 完整测试编写顺序

```
[1] 编写测试文件
[2] 运行确认 FAIL（必看错误信息符合预期）
[3] 编写实现代码（最小集）
[4] 运行确认 PASS
[5] 补充集成测试（跨模块/端到端）
[6] 边界值与异常测试（空、null、超大、并发）
[7] 跑全套测试 + lint
```

---

## 3. 测试分层

| 类型 | 覆盖目标 | 覆盖率要求 | 何时编写 |
|------|---------|----------|---------|
| **单元测试** | 核心业务函数 | ≥ 75% | TDD 主战场 |
| **集成测试** | API 端到端流程 | 关键路径 100% | 步骤 [5] |
| **回归测试** | bug 修复 | 100%（每个 bug 一条） | 修 bug 时 |

---

## 4. 测试命名原则（描述行为）

| 好的命名 | 不好的命名 |
|---------|----------|
| `test_应该拒绝空用户名登录` | `test_checkUsername` |
| `test_超长输入应触发异常` | `test_long_input` |
| `test_支付成功时发送通知邮件` | `test_sendEmail` |

**格式**：`test_{模块}_{行为}_{预期}` 或 `test_应该{行为}当{条件}`。

---

## 5. EARS 验收标准格式

在测试或需求描述中使用 EARS 语法，让每个条件**可测试**：

```
While <可选前置条件>, when <可选触发器>, the <系统名称> shall <系统响应>
```

### 示例

```
When 用户提交登录表单, the 认证系统 shall 验证用户名和密码非空后再调用 API。
While 用户已登录, when 点击支付按钮, the 支付系统 shall 在 5 秒内返回交易结果。
While 余额不足, when 用户尝试支付, the 支付系统 shall 返回错误码 INSUFFICIENT_BALANCE。
```

详见 `@ears-spec`。

---

## 6. 测试陷阱（必看）

| 陷阱 | 表现 | 后果 |
|------|------|------|
| 跳过 Red 直接实现 | 测试从绿开始 | 测试写错也不知道 |
| 实现比测试多 | 测试通过但做了"额外功能" | 推测性实现（违反 YAGNI） |
| 重构时加功能 | 测试绿但代码变了 | 测试不是安全网 |
| 测试只测实现 | `test_internalMethod` | 重构即失败 |
| 测试依赖外部 | 真实调用 API/DB | 测试不稳定 |

---

## 7. 反模式 vs 正例

| 反例 | 正例 |
|------|------|
| ❌ 实现后再补测试（事后验证） | ✅ 先写测试再写实现（事前规范） |
| ❌ 一个测试覆盖整个流程 | ✅ 单个测试一个行为断言 |
| ❌ 测试里写 `console.log` 调试 | ✅ 用 assert + 错误信息诊断 |
| ❌ 跳过 edge case（觉得"不会发生"） | ✅ 至少覆盖：空/null/边界值/异常 |
| ❌ Mock 掉所有依赖 | ✅ Mock 外部 API，保留内部依赖 |

---

## 8. TDD 工作流与 Qoder 集成

### 8.1 红绿节奏

- Red：使用 Qoder 的"测试运行"功能确认测试 FAIL
- Green：使用 Qoder 的代码编辑功能实现
- Refactor：使用 Qoder 的 lint + format 功能

### 8.2 测试覆盖率验证

```bash
# JavaScript/TypeScript
npm test -- --coverage

# Python
pytest --cov=src --cov-report=term

# Go
go test -cover ./...

# Java
mvn test jacoco:report
```

覆盖率 < 75% → 不算完成。

---

## 9. 与其他规则的关系

| 规则文件 | 关系 |
|---------|------|
| `code-engineering.md` §1 | 测试规范（覆盖率、Mock、TDD 三步法） |
| `systematic-debug.md` | 调试流程（修复 bug 时也走 TDD） |
| `@ears-spec` | 验收标准格式 |
| `ai-behavior.md` §2 | 简单优先（写最少代码） |

---

_TDD 工作流 v1.0 - 任何编码任务第一步引用本规则_