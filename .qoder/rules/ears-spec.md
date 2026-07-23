---
trigger: manual
description: EARS 验收标准格式——编写需求、测试用例、验收条件时手动引入
---

# ears-spec.md - EARS 验收标准

> 引用方式：`@ears-spec`
> 触发场景：编写需求文档 / 设计验收条件 / 写测试用例 / 编写用户故事
> 来源：整合自 Claude Code `rules/engineering-practices.md` §1.5

---

## 1. 什么是 EARS

**EARS**（Easy Approach to Requirements Syntax）—— 一种简洁、可测试的需求描述语法。

核心模板：

```
While <可选前置条件>, when <可选触发器>, the <系统名称> shall <系统响应>
```

每个需求必须能**直接翻译成测试用例**。

---

## 2. 五种需求模式

### 2.1 Ubiquitous（无前置条件的需求）

```
The <系统名称> shall <系统响应>
```

**示例**：

```
The 认证系统 shall 使用 bcrypt 哈希存储用户密码。
```

### 2.2 Event-driven（事件驱动型 - 有触发器）

```
When <触发器>, the <系统名称> shall <系统响应>
```

**示例**：

```
When 用户提交登录表单, the 认证系统 shall 验证用户名和密码非空后再调用 API。
```

### 2.3 State-driven（状态驱动型 - 有前置条件）

```
While <前置条件>, the <系统名称> shall <系统响应>
```

**示例**：

```
While 用户已登录, the 订单系统 shall 在 5 秒内返回用户订单列表。
```

### 2.4 Unwanted behavior（异常行为）

```
If <触发条件>, then the <系统名称> shall <系统响应>
```

**示例**：

```
If 余额不足, then the 支付系统 shall 返回错误码 INSUFFICIENT_BALANCE。
```

### 2.5 Optional（可选特性）

```
Where <功能包含>, the <系统名称> shall <系统响应>
```

**示例**：

```
Where 系统配置了短信网关, the 通知系统 shall 在用户注册时发送短信验证码。
```

---

## 3. 组合用法（最常用）

```
While <前置条件>, when <触发器>, the <系统名称> shall <系统响应>
```

### 3.1 典型示例

**支付场景**：

```
While 用户已登录且余额充足, when 用户点击支付按钮, the 支付系统 shall 在 5 秒内返回交易结果。
```

**登录场景**：

```
While 用户未登录, when 用户访问受保护资源, the 认证系统 shall 重定向到登录页面并保留回调 URL。
```

**对账场景**：

```
While 账务日期已切到 T+1, when 跑批任务启动, the 对账系统 shall 比对 T 日所有交易并生成差异报告。
```

---

## 4. EARS → 测试用例翻译

EARS 描述可以直接翻译为测试用例：

```
EARS：
When 用户提交空用户名, the 认证系统 shall 返回 400 错误。

测试用例：
def test_提交空用户名应返回400错误():
    response = api.post('/login', json={'username': '', 'password': 'valid'})
    assert response.status_code == 400
```

---

## 5. 编写检查清单

写完 EARS 后逐项检查：

- [ ] 每个需求都用了 EARS 模板（5 种之一）
- [ ] `<系统名称>` 是明确的主语（不是"用户"或"我们"）
- [ ] `<系统响应>` 是可观察、可验证的行为（不是实现细节）
- [ ] 没有"等"/"之类"/"等等"等模糊词
- [ ] 涉及金额/数量时显式声明精度与单位
- [ ] 异常路径都用 `If` 子句覆盖
- [ ] 都能直接翻译成测试用例

---

## 6. 反例 vs 正例

### 6.1 模糊表述（❌）

```
- 系统应该处理用户登录
- 支付要快一点
- 错误信息要友好
- 支持多种支付方式
```

### 6.2 隐含实现细节（❌）

```
- 用 Redis 缓存用户 session
- 数据库要用索引
- 前端用 React 框架
```

### 6.3 EARS 标准（✅）

```
- When 用户提交登录表单 with 有效凭证, the 认证系统 shall 在 1 秒内返回 JWT token。
- While 用户已登录, when 用户访问 /api/profile, the 认证系统 shall 在 200ms 内返回用户信息。
- If 用户提交空字段, then the 认证系统 shall 返回 400 错误并指出缺失字段。
- If 系统检测到并发登录冲突, then the 认证系统 shall 拒绝第二个登录请求。
```

---

## 7. 金融领域 EARS 特殊约定

涉及金额计算时，EARS 必须显式声明：

```
While 账户余额充足, when 用户发起转账, the 转账系统 shall
使用 BigDecimal 计算并按 HALF_UP 舍入保留 2 位小数。
```

```
When 用户发起跨境汇款, the 汇款系统 shall
按汇款金额 × (1 + 手续费率) 计算总扣款金额，费率保留 4 位小数。
```

详见 `code-finance-precision.md`。

---

## 8. 验收文档模板

```markdown
# [功能名] 验收标准

## 1. 正常路径

- While [前置], when [触发], the [系统] shall [响应1]。
- While [前置], when [触发], the [系统] shall [响应2]。

## 2. 异常路径

- If [异常条件1], then the [系统] shall [响应1]。
- If [异常条件2], then the [系统] shall [响应2]。

## 3. 边界条件

- When [边界1], the [系统] shall [响应]。
- When [边界2], the [系统] shall [响应]。

## 4. 性能要求（如适用）

- While [前置], when [触发], the [系统] shall 在 [时长] 内完成 [操作]。

## 5. 测试覆盖

- [ ] 单元测试覆盖所有 shall 子句
- [ ] 集成测试覆盖跨模块路径
- [ ] 边界测试覆盖所有 when 触发器
```

---

## 9. 与其他规则的关系

| 规则文件 | 关系 |
|---------|------|
| `tdd-workflow.md` | EARS 是 TDD 的需求层 |
| `code-engineering.md` §1 | 测试规范 |
| `work-management.md` §1 | 三问中的"成功标准"用 EARS 表达 |

---

_EARS 验收标准 v1.0 - 任何需求/验收场景手动引入_