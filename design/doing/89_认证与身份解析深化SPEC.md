# doing/89 认证与身份解析深化 SPEC（N-001 + N-003）

> 编号：DOC-110 | 创建：2026-08-11 | 来源：doing/89 非 device 域架构深化候选清单——首要建议批次（N-001 认证域 + N-003 家长域 withdrawn 旁路）
> 状态：SPEC（6/8 AC 闭环，待收尾）| 与 frozen/89 同号异题（文件名可区分）
> 核验（2026-08-11）：AC-89-01/02/03/06/07/08 已闭环（805eacc/7de2b0a/f57c6af + 四端回归 28+61 全绿）；**AC-89-04（ParentIdentityResolver 统一）与 AC-89-05（AuthProvider 统一签发）未实施**——暂不归档
> 关联：doing/89 候选清单（全量 N-001~013）、his/83 §7.6（平台认证）、design/13（领域词汇表）

---

## 1. 问题陈述

### 1.1 现状（证据）

| # | 现状 | 证据 |
|---|------|------|
| 1 | **4 套认证体系并存**：业务端 AuthController（11 端点 token 内联 4 处）/ 平台端 PlatformAuthController（PLATFORM_ 前缀）/ toC 端 TocAuthController（手机号验证码，token 签发在 Controller）/ 家长端 ParentAuthService（手机号+密码） | AuthController.java、PlatformAuthController.java、TocAuthController.java L87-88、ParentAuthService.java |
| 2 | **锁定向 3 种实现**：LoginLockoutService（按用户名，Redis）/ PlatformLoginGuard（按 IP）/ TocAuthService.rateLimitCheck（按手机号） | 三文件独立实现 |
| 3 | **家长端零锁定向防护**：ParentAuthService.doLogin 无失败计数/锁定/限速——家长账号是唯一可无限暴力尝试的入口 | ParentAuthService.java L129-147 |
| 4 | **双解析器并存（潜在旁路）**：resolveParentToken（旧链接，resolver 内含 withdrawn 拦截）vs resolveParentIdentity（新登录，withdrawn 拦截在端点层 requireLinkedStudent(true)）——**现存拦截已生效**（2026-08-11 验证：ParentControllerTest L195 getWeeklyReport 撤回→410 通过），潜在旁路 = 未来新端点漏传 true 时才会出现 | ParentController.java L162-241、L245-256 |
| 5 | **周报聚合留在 Controller**：doGetWeeklyReport 手工聚合情绪/风险；ParentService 83 行空壳；TenantContextHolder 模板重复 4 处 | ParentController.java L71-115 |

### 1.2 影响

- 家长端口令可被无限穷举（安全红线，发布前必修）
- 同意撤回（PIPL §47）可被新登录路径绕过（合规漏洞）
- 威胁模型无法统一审查（3 种锁定向各说各话）
- 周报统计口径与 TeacherService/DataAnalyticsService 三处漂移（N-004 关联）

## 2. 解决方案

### 2.1 LoginRateLimiter 接口（统一锁定向语义）

```
interface LoginRateLimiter {
    void checkLockout(String identifier);      // 超限抛 BizException
    void recordFailure(String identifier);
    void clearFailures(String identifier);
}
实现：UsernameLimiter（现有 LoginLockoutService 适配）/ IpLimiter（PlatformLoginGuard 适配）/ PhoneLimiter（TocAuthService 限速逻辑迁移）
```

- 三实现并排保留（计数模型不同不强行合并），统一接口面
- **家长端接入 UsernameLimiter**（补零防护缺口）

### 2.2 AuthProvider 接缝（统一认证入口）

```
interface AuthProvider { AuthPrincipal authenticate(Credentials credentials); }
实现：BusinessAuthProvider / PlatformAuthProvider / TocAuthProvider / ParentAuthProvider
```

- **token 签发统一在 provider 内**（修复 TocAuthController 分层倒挂——token 签发从 Controller 下沉）
- 各体系存储不动（平台表/用户表/toc 表各自保留），只统一入口语义

### 2.3 ParentIdentityResolver（统一家长身份解析 + withdrawn 拦截）

```
class ParentIdentityResolver {
    ParentIdentity resolve(Authentication auth);  // 统一解析两种 token 语义
    // withdrawn 拦截在 resolver 内统一执行（不再依赖旧路径私有校验）
}
```

- 旧链接（sub=studentUserId）与新登录（sub=parentId）两种语义统一出口
- **withdrawn 校验统一收进 resolver（消除潜在旁路）**——现存拦截经验证已生效（AC-89-03 已测），统一收口后新端点无需记挂"传 true"
- Controller 移除 resolveParentToken/resolveParentIdentity 双实现

### 2.4 WeeklyReportService（周报下沉，N-003 附带）

- 周报聚合从 ParentController 下沉独立 Service（口径与 N-004 Metric VO 族共享）
- TenantContextHolder 模板封装为 `TenantBoundary.withContext(tenantId, runnable)`（与 N-005 共用）

## 3. 实施决策

| # | 决策 | 理由 |
|---|------|------|
| D1 | 不合并各认证体系的存储（平台表/用户表/toc 表各自保留） | 存储合并是超大重构，非本批次目标（YAGNI） |
| D2 | 锁定向三实现并排（不强行合并为单实现） | 用户名/IP/手机号计数模型不同，强行合并转移为适配层复杂度 |
| D3 | 家长端接入 UsernameLimiter（复用 LoginLockoutService 语义） | 家长账号=手机号标识，用户名锁定模型直接适用 |
| D4 | withdrawn 拦截统一在 ParentIdentityResolver | 单一规则源，消除旁路 |
| D5 | token 签发统一在 AuthProvider | 修复 TocAuthController 分层倒挂（"service 不依赖 api" 注释暴露的设计缺陷） |

## 4. 验收标准（AC-89-xx，EARS）

| # | 需求（EARS） | 验收 |
|---|-------------|------|
| AC-89-01 | WHEN 家长账号连续 5 次密码错误 THEN 锁定 15 分钟 AND 锁定期间登录被拒 | ✅ 805eacc（doLogin 接入 LoginLockoutService） |
| AC-89-02 | WHEN 家长账号锁定期间调用登录 THEN 返回 401/锁定提示 AND 不校验密码 | ✅ 805eacc（checkLockout 前置拦截） |
| AC-89-03 | WHEN 已撤回同意的家长通过新登录路径访问 THEN 请求被拒（withdrawn 拦截生效） | ✅ f57c6af 验证（requireLinkedStudent(true) 现存生效，测试通过） |
| AC-89-04 | WHEN 旧链接（sub=studentUserId）与登录（sub=parentId）访问家长端点 THEN 统一经 ParentIdentityResolver 解析 AND withdrawn 校验一致 | ❌ 未实施（双路径仍在 ParentController L128/L190；withdrawn 靠端点传 true/false） |
| AC-89-05 | WHEN 任一体系登录成功 THEN token 由对应 AuthProvider 签发 AND 格式不变（PLATFORM_/业务/toc） | ❌ 未实施（仅 TocAuthProvider；无 AuthProvider 接口，业务/平台/家长未接入） |
| AC-89-06 | WHEN TocAuthController.login/register 调用 THEN token 签发在 Service 层（非 Controller） | ✅ 7de2b0a（TocAuthProvider.buildSession 承载签发） |
| AC-89-07 | WHEN 周报端点调用 THEN 聚合逻辑在 WeeklyReportService AND Controller 仅编排 | ✅ 7de2b0a（WeeklyReportService 下沉） |
| AC-89-08 | WHEN 全量回归 THEN 四端登录（业务/平台/toc/家长）零回归 | ✅ 2026-08-11 四端登录测试 28+61 全绿 |

## 5. 边界与依赖

- **不在本批次**：各认证体系存储合并（D1）；统计口径全量收敛（N-004 单独批次，本批次仅周报下沉）
- **依赖**：N-005 TenantBoundary（runAsSystem 封装）可先行或并行（独立小项）
- **前端联动**：家长端无 API 变更（token 格式不变），无需前端配合；旧链接兼容性由 resolver 统一保证
- **测试策略**：LoginRateLimiter 接口单测（3 实现各自保留原测试）+ ParentIdentityResolver 单测（withdrawn 两路径断言）+ 回归（四端登录）

## 6. 实施顺序

1. LoginRateLimiter 接口 + 三实现适配（现有逻辑包接口，行为不变）
2. 家长端接入 UsernameLimiter（补零防护）
3. ParentIdentityResolver（统一解析 + withdrawn 拦截修复）
4. AuthProvider 接缝 + TocAuthController token 签发下沉
5. WeeklyReportService 下沉（周报聚合）
6. 全量回归 + 测试补充
