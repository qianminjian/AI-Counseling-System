---
trigger: specific
globs: "*.java,*.go,*.py"
description: 金融计算精度与脱敏规则——涉及金额/账务/支付/银行核心系统的文件自动生效
---

# code-finance-precision.md - 金融计算精度与数据脱敏

> 加载方式：编辑 Java / Go / Python 文件且涉及金额计算时自动生效
> 来源：整合自 Claude Code `rules/engineering-practices.md` §3.5 + §4.5 金融数据脱敏
> **适用领域**：银行 IT、支付清算、账务系统、保险核心、任何涉及金额的代码

---

## 1. 金融计算精度（金额计算强制规则）

### 1.1 必须使用定点数

| 语言 | 类型/库 |
|------|---------|
| Java | `java.math.BigDecimal` |
| Python | `decimal.Decimal` |
| Go | `github.com/shopspring/decimal` 或 `math/big` |
| JavaScript | `big.js` / `decimal.js` / `bignumber.js` |

**禁止**：`float` / `double` / `number` 用于金额存储和计算。

```java
// ❌ 反例：double 累加
double total = 0;
for (Transaction tx : txs) {
    total += tx.getAmount();
}

// ✅ 正例：BigDecimal 累加
BigDecimal total = BigDecimal.ZERO;
for (Transaction tx : txs) {
    total = total.add(tx.getAmount());
}
```

### 1.2 舍入规则必须显式声明

每笔计算必须标注舍入策略，禁止隐式截断：

```java
// Java 示例
BigDecimal result = a.divide(b, 2, RoundingMode.HALF_UP);

// Python 示例
from decimal import Decimal, ROUND_HALF_UP
result = (a / b).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
```

### 1.3 精度一致性

- 计算前统一精度位
- 结果精度与输入精度一致
- 跨系统对接时显式声明精度转换规则

### 1.4 比较运算注意

```java
// ❌ 反例：BigDecimal equals（比较 scale）
new BigDecimal("1.0").equals(new BigDecimal("1.00"))  // false!

// ✅ 正例：compareTo（忽略 scale）
a.compareTo(b) == 0
```

---

## 2. 金融数据脱敏（强制）

### 2.1 脱敏格式

| 数据类型 | 脱敏格式 | 示例 |
|---------|---------|------|
| 身份证号（18 位） | `3201**********1234` | 前 4 位 + 10 星 + 后 4 位 |
| 手机号（11 位） | `138****1234` | 前 3 位 + 4 星 + 后 4 位 |
| 银行卡号（13-19 位） | `6222****1234` | 前 4 位 + 4 星 + 后 4 位 |
| CVV | 完全屏蔽 | `***` |
| 密码 | 完全屏蔽 | `******` |

### 2.2 禁止场景

- 日志（任何级别）
- 错误消息
- 测试数据
- API 响应体（含完整卡号/手机号）
- 监控/链路追踪

### 2.3 AI 生成测试数据

```java
// ✅ 正例
String mockIdCard = "3201**********1234";
String mockPhone = "138****1234";
String mockBankCard = "6222****1234";

// ❌ 反例
String idCard = "320105199001011234";  // 真身份证格式
String phone = "13800138000";          // 真手机号
```

### 2.4 生产数据分析

- 生产数据分析需确认脱敏后再交 AI
- 数据库导出 → 脱敏脚本 → 导入测试库 → AI 分析
- 不接受"快速看一眼"直接给 AI

---

## 3. 账务一致性约束

### 3.1 借贷必相等

- 每笔会计分录借贷方总额必须相等
- 校验逻辑必须在事务内执行（不允许先提交再校验）

```java
@Transactional
public void postEntry(JournalEntry entry) {
    BigDecimal debitSum = entry.getDebitLines().stream()
        .map(Line::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal creditSum = entry.getCreditLines().stream()
        .map(Line::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (debitSum.compareTo(creditSum) != 0) {
        throw new AccountingUnbalancedException(debitSum, creditSum);
    }
    // ... 入账逻辑
}
```

### 3.2 幂等性

- 支付/转账/对账接口必须支持幂等（idempotency key）
- 重复请求返回相同结果，不重复扣款

### 3.3 金额单位统一

| 场景 | 单位约定 |
|------|---------|
| 存储 | 分（整数）或 元（BigDecimal）二选一，文档明确 |
| 传输 | 显式声明单位（建议分） |
| 显示 | 元，保留 2 位小数 |

---

## 4. 合规与审计

### 4.1 commit message 强制关联工单

```bash
# 格式
feat(payment): 对公转账指令拆分 JIRA-4521

业务影响：原单笔转账限额 100 万 → 拆分为多笔
影响范围：对公转账接口（上游 3 个系统）
回滚方案：保留原接口，灰度切换
```

### 4.2 代码评审保留

- PR 评论 + 批准记录必须保留
- 监管要求的留痕期限默认 ≥ 5 年

### 4.3 敏感操作审计日志

```java
// 必须记录
auditLog.record(AuditEvent.builder()
    .operator(userId)
    .operation("对公转账")
    .amount(amount)
    .counterparty(counterparty)
    .timestamp(Instant.now())
    .requestId(requestId)
    .build());
```

---

## 5. 反例清单（高频错误）

| 反例 | 后果 |
|------|------|
| `double amount = 0.1 + 0.2;` → `0.30000000000000004` | 金额精度丢失 |
| `BigDecimal a = new BigDecimal(0.1)` | 精度不可控，必须用 `BigDecimal.valueOf(0.1)` 或 `new BigDecimal("0.1")` |
| 余额计算后未校验借贷相等 | 账务不平衡 |
| 测试用例写真实身份证格式 | 命中红线 #7（监管） |
| 日志打印完整卡号 | 命中红线 #2（密钥/隐私） |
| 接口无幂等校验 | 重复扣款风险 |

---

## 6. 与其他规则的关系

| 规则文件 | 关系 |
|---------|------|
| `core-red-lines.md` §3 | 安全编码总则（密钥/PII/凭据） |
| `code-engineering.md` | 通用代码架构 / Git / 测试规范 |
| `engineering-practices.md` §3.5 原文 | 本文件是其精简适配版 |

---

_金融计算精度规则 v1.0 - 涉及金额的源代码自动加载_