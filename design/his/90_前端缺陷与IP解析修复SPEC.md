# doing/90 前端缺陷与 IP 解析修复 SPEC（P-005 + P-001）

> **已合并归档（2026-08-11，DOC-113）**：AC-90-01~06 全部验收通过（8894382 实施）——doing→his。
> 编号：DOC-111 | 创建：2026-08-11 | 来源：doing/90 第三轮架构深化候选清单——首要建议批次（P-005 student-h5 真实 UI 缺陷 + P-001 客户端 IP 解析安全语义）
> 状态：SPEC（待议决实施）| doing 区接续编号
> 关联：doing/90 候选清单（P-001~010）、doing/89（认证域 SPEC）、AUDIT-DEEP（main 合入审计）

---

## 1. 问题陈述

### 1.1 现状（证据）

| # | 现状 | 证据 |
|---|------|------|
| 1 | **LoginPage 主题类名不插值**：`className="login-field login-field--${themeId}"` 用双引号而非反引号——`${themeId}` 不插值，主题类名永远字面量 `login-field--${themeId}`，主题样式静默失效（同文件其余处均用反引号，回归遗漏） | student-h5/src/components/LoginPage.tsx L254（引入 f02a0fb4，2026-07-30） |
| 2 | **PIN 上限与声明不符**：`pin.length < 7` 允许输入第 7 位，而文案与 disabled 条件声明"4-6 位"——修复未达声明 | LoginPage.tsx L224/L375 |
| 3 | **客户端 IP 解析两套相反语义**：AuditLogService.extractClientIp 取 XFF **最左**（split(",")[0]），VoiceprintDomain.resolveClientIp 取**最右**（注释论证最右不可伪造）——审计 IP 哈希可被伪造 XFF 前缀污染 | AuditLogService.java L113 vs VoiceprintDomain.java L108 |

### 1.2 影响

- 主题切换无声失败（用户看到默认主题，无报错——最坏的 UI bug 形态）
- PIN 上限 7 位与 UX 文案 4-6 位不一致（输入边界歧义）
- 安全审计 IP 失真（XFF 伪造覆盖真实来源，合规审计不可信）

## 2. 解决方案

### 2.1 LoginPage 修复（P-005）

- L254：`className="login-field login-field--${themeId}"` → 反引号模板串 `` className={`login-field login-field--${themeId}`} ``
- PIN 上限：`pin.length < 7` → `< 6`（与文案"4-6 位"、disabled 条件一致），检查所有 PIN 输入边界（L224/L375 + 其他 PIN 引用）

### 2.2 ClientIpResolver 公共组件（P-001）

```
class ClientIpResolver {
    String resolve(HttpServletRequest request);
    // 语义：取 X-Forwarded-For 最右条目（不可伪造——代理追加在右，客户端伪造在左被忽略）
}
```

- 新建于 counseling-api（依赖 HttpServletRequest）或 counseling-common（纯函数解析字符串）——**取 common 纯函数 + api 薄封装**
- AuditLogService.extractClientIp 与 VoiceprintDomain.resolveClientIp 收敛复用
- 语义统一为"最右不可伪造"

## 3. 实施决策

| # | 决策 | 理由 |
|---|------|------|
| D1 | ClientIpResolver 放 counseling-common（纯函数 parse(String xff) → 最右 IP）+ api 层薄适配 | 纯函数可单测，两消费方（audit/voiceprint 跨包）共用 |
| D2 | XFF 语义统一为最右（对齐 VoiceprintDomain 论证） | 最右不可伪造（代理追加），最左可伪造（客户端控制） |
| D3 | PIN 上限收敛为 6 | 与文案/disabled 声明一致（原修复 5→6 未达声明） |
| D4 | 不改主题切换整体逻辑（仅修类名插值） | 最小修复面（YAGNI） |

## 4. 验收标准（AC-90-xx，EARS）

| # | 需求（EARS） | 验收 |
|---|-------------|------|
| AC-90-01 | WHEN 切换主题 THEN login-field 类名含对应 themeId AND 样式生效 | 类名插值修复（L254） |
| AC-90-02 | WHEN 输入 PIN THEN 上限 6 位（第 7 位不可输入）AND 文案显示 4-6 位 | 上限与声明一致 |
| AC-90-03 | WHEN 请求带 XFF 头 THEN ClientIpResolver 返回最右条目（不可伪造语义） | 单测覆盖（多级 XFF） |
| AC-90-04 | WHEN 审计日志落库 THEN 客户端 IP 哈希基于最右语义 | AuditLogService 收敛 |
| AC-90-05 | WHEN 声纹登录 IP 限流 THEN 基于最右语义（行为不变） | VoiceprintDomain 收敛，回归零变化 |
| AC-90-06 | WHEN 全量回归 THEN student-h5 测试全绿 AND 后端无行为回归 | 兼容性 |

## 5. 边界与依赖

- **不在本批次**：P-002 空壳域清理 / P-006 nginx 限流补层（独立小项可并行）
- **前端联动**：LoginPage 修复无 API 变更；IP 解析收敛无对外行为变化（语义对齐最右）
- **测试策略**：ClientIpResolver 纯函数单测（单级/多级/无 XFF/空 XFF 4 用例）+ LoginPage 主题类名断言 + PIN 边界断言 + 回归

## 6. 实施顺序

1. ClientIpResolver（common 纯函数 + 单测）
2. AuditLogService / VoiceprintDomain 收敛复用
3. LoginPage L254 反引号 + PIN 上限 6
4. 全量回归（student-h5 + 后端 audit/voiceprint 相关）
