# 73 - 家长端 Taro 跨端迁移（H5 架构先行）

> 创建：2026-08-07 | 状态：方案与 SPEC 待评审，实施统一安排 | 对应专题：frozen/43（家长端小程序化设计）、design/30 §方向五（PARENT-WX-001~006）
>
> 定位：frozen/43 判定家长端（Vite + React H5 SPA）技术栈不符合后续演进规划（微信小程序渠道），需 Taro 跨端迁移。本文按钱敏健指令「**先对现有 H5 进行技术架构迁移，输出仍保持 H5 单页应用**」输出**两阶段路线**：**P0 = H5 架构迁移（本方案主体）**——把 parent-h5 原地改造为 Taro 工程，H5 产物行为/URL/部署完全等价；**P1 = 微信小程序编译与认证接入（后续专题，依赖企业主体）**。本文只做设计，不含实现代码。

---

## 〇、执行摘要（结论先行）

| 问题 | 结论 |
|------|------|
| **可行性** | ✅ 可行。家长端规模极小（19 个源文件、4 页面、7 个测试文件 + setup 共 8 文件 564 行），Taro H5 产物本身即 React SPA，`h5.router.basename` 可保持 `/parent/` URL 与 nginx 部署完全不变 |
| **关键风险** | Taro 4 官方支持 React 18（依赖 `react ^18.3.1`），当前三端均为 React 19.2.7 → **Taro 工程需锁定 React 18**；家长端代码未使用 React 19 独有特性（无 `use()`/Actions/Server Components），降级成本 ≈ 0 |
| **迁移策略** | **原地演进 parent-h5 → Taro 工程**（不新建双工程）：git 历史连续、部署路径不变、用户指令「先对现有 H5 迁移」天然契合 |
| **P0 工作量** | 2~3 人日（工程改造 + 4 页面组件化 + 平台适配层 + 测试对齐 + CI 命令替换） |
| **frozen/43 基线修正** | frozen/43（2026-07-28）基于旧基线（3 页、send-code/verify-phone、localStorage、无 shared 依赖）——**已过时**，本文以 2026-08-07 代码为基线全面更新（见 §一） |

---

## 一、现状深度分析（2026-08-07 代码基线核对）

### 1.1 技术栈清单（与 frozen/43 的差异）

| 维度 | frozen/43 记录（2026-07-28） | **实际代码（2026-08-07）** | 差异说明 |
|------|------------------------------|---------------------------|----------|
| 构建 | Vite 6 | **Vite 8.1.1** | 版本演进 |
| 框架 | React 19 | **React 19.2.7** | 一致 |
| 路由 | react-router-dom 7 | **react-router 8.3.0**（BrowserRouter basename=`/parent`） | 版本演进 |
| 页面数 | 3（verify/report/consent） | **4（+privacy 个人信息保护告知，F-5 PIPL 合规页）** | 2026-07-28 新增 |
| 认证 | 手机验证码（send-code/verify-phone） | **账号密码体系（parent/auth/register + parent/auth/login，家庭码绑定）** | 认证模式已演进 |
| 存储 | localStorage | **sessionStorage（AUD-007：双 token + 用户信息会话级，XSS 单点不再获持久凭证）** | 安全加固演进 |
| 共享模块 | 无 | **接入 DC-005 `frontend/shared/src/auth-transport/`（refresh/sessionExpired/apiError/tokenStorage 四能力，三端共用）** | 2026-08-06 架构深化落地 |
| 测试 | — | vitest + testing-library，**564 行 7 测试文件**（+setup.ts），覆盖率门禁 70/65/50/70 | 完整测试基线 |
| 契约防线 | — | `src/api/endpoints.ts` 单一事实源 + apiContract.test.ts 断言 | ARCH-008 F-7 |

### 1.2 代码结构盘点（19 个源文件）

```
frontend/parent-h5/src/
├── main.tsx                    # 入口：ErrorBoundary + BrowserRouter(basename=/parent) + 4 路由 + ProtectedRoute
├── app.scss                    # 全局样式 401 行（含 * / body 元素选择器 + SCSS 嵌套 + BEM 类名）
├── api/
│   ├── index.ts                # 请求封装（fetch + 401 刷新 + DC-005 shared 接入）+ 5 个业务 API
│   └── endpoints.ts            # 端点契约单一事实源（5 端点）
├── utils/auth.ts               # sessionStorage Token/User 存取（parent_token/parent_refresh_token/parent_user）
├── components/ErrorBoundary.tsx# 类组件错误边界（window.location.reload）
├── pages/
│   ├── verify/index.tsx        # 登录/注册双模式（家庭码+手机号+密码+关系选择）
│   ├── privacy/index.tsx       # PIPL 告知（公开路由）
│   ├── report/index.tsx        # 情绪周报（多孩子切换 + 概览 + 情绪分布）
│   └── consent/index.tsx       # 数据授权管理（撤回不可逆 + 二次确认）
└── test/                       # 8 文件 564 行（5 页面组件测试 414 行 + auth 69 + 契约 80 + setup）
```

### 1.3 平台耦合点盘点（迁移工作量主体）

| # | 耦合点 | 现状实现 | 文件 | 迁移动作（P0） | 后续（P1 小程序） |
|---|--------|----------|------|---------------|------------------|
| C1 | 路由 | `BrowserRouter basename="/parent"` + `useNavigate`/`Link`/`Navigate` | main.tsx + 4 页面 | **Taro 路由**（app.config pages 注册）+ `h5.router { mode: 'browser', basename: '/parent' }`，URL 不变 | 小程序页面路由（同 pages 注册） |
| C2 | 存储 | `sessionStorage`（getItem/setItem/removeItem） | utils/auth.ts + shared/tokenStorage.ts | **PlatformStorage 接口**，H5 实现 = sessionStorage（保持 AUD-007 会话语义） | Taro storage（无会话级 → 键名 + 7 天过期策略，对齐后端 Token 生命周期） |
| C3 | 网络 | `fetch`（含 401 刷新） | api/index.ts + shared/refresh.ts | **PlatformRequest 接口**，H5 实现 = fetch 包装（或 Taro.request H5 实现） | `Taro.request`（小程序无 fetch） |
| C4 | 跳转 | `window.location.href/reload` | shared/sessionExpired.ts + ErrorBoundary | **PlatformRedirect 接口**，H5 = location | `Taro.reLaunch` |
| C5 | DOM 元素 | `div/span/button/input/form/a` | 4 页面 | **Taro 组件**（View/Text/Button/Input/Form/Link） | 同左（Taro 编译） |
| C6 | 样式 | SCSS 全局 `*`/`body` + 嵌套 + BEM | app.scss（401 行） | 移除元素级全局选择器改类名；保留嵌套与 BEM（sass 编译后扁平化，双端可用） | WXSS 子集（无标签选择器穿透） |

### 1.4 共享模块（DC-005）平台耦合详情

`frontend/shared/src/auth-transport/` 三端共用（student-h5 2 处、teacher-web 1 处、parent-h5 1 处引用），**改造必须向后兼容，student/teacher 零改动**：

| 文件 | 平台耦合 | 注入改造（P0） |
|------|----------|----------------|
| `tokenStorage.ts` `createSessionStorageTokens(prefix)` | `sessionStorage` 硬编码 | 新增 `createPlatformTokens(prefix, storage)`（storage 注入）；原函数保持（默认注入 sessionStorage 实现） |
| `refresh.ts` `refreshTokens(storage, baseUrl?)` | `fetch` 硬编码 | 新增可选 `fetchImpl` 参数（默认全局 fetch）；签名向后兼容 |
| `sessionExpired.ts` `handleSessionExpired(storage, loginPath?)` | `window.location` 硬编码 | 新增可选 `redirect` 参数（默认 window.location 实现） |
| `apiError.ts` | 纯逻辑（无耦合） | 不动 |

---

## 二、可行性评估

### 2.1 有利因素（H5 阶段可行性高）

1. **规模极小**：19 个源文件、4 页面全部为只读展示 + 表单，无复杂交互、无图表库、无第三方 UI 依赖（仅 react/react-dom/react-router）。
2. **Taro H5 产物即 React SPA**：Taro H5 端本身就是 React 应用，编译产物形态与现状一致（入口 + 路由 + 组件树），差异仅在「路由体系、DOM 组件、平台 API」三层。
3. **URL 与部署零变化**：Taro `h5.router.basename: '/parent'` + `publicPath: '/parent'` 可完整对齐现状（browser 模式）；nginx 配置、`/api` 代理、静态资源路径全部不动。
4. **共享模块注入点收敛**：平台耦合仅 3 处（storage/fetch/location），且 shared 模块已按「storage 接口注入」设计（TokenStorage），改造为显式注入成本低。
5. **测试基座可复用**：vitest 保留；Taro H5 组件渲染真实 DOM，testing-library 断言模式大部分可延续。

### 2.2 风险与障碍（如实评估）

| # | 风险 | 等级 | 影响 | 缓解 |
|---|------|------|------|------|
| R1 | **React 19 → 18 降级**：Taro 4 官方依赖 React ^18.3.1，无 React 19 官方支持 | 中 | Taro 编译/运行时与 React 19 兼容性问题不可控 | 代码审计确认未用 React 19 独有 API（use/Actions/ref-as-prop 等）；降级后全量回归验证；**Taro 工程锁定 18.3.x** |
| R2 | Taro H5 组件行为差异（Input/Button/Form 事件模型） | 中 | 表单页（verify/consent）交互回归 | P0 验收以「四路由功能等价 + 表单提交/校验行为一致」为硬标准；Form 改用 `onSubmit` 与 button `onClick` 双路径兜底 |
| R3 | 样式元素选择器（`*`/`body`）小程序端无效 | 低 | 小程序阶段样式错乱 | P0 即类名化（双端受益）；小程序阶段再核 WXSS 子集 |
| R4 | 路由守卫（ProtectedRoute）在 Taro 的等价实现 | 低 | 未登录访问 /report、/consent 失守 | P0 用「页面组件内 `Taro.getCurrentInstance().router` + 未登录 `Taro.redirectTo('/pages/verify/index')`」等价实现（4 页面仅 2 个受保护） |
| R5 | Taro 4 版本选择（4.0.x 系列维护节奏） | 低 | 生态风险 | 锁定 4.0.13+（2025-05 正式版）；不使用实验特性 |
| R6 | sessionStorage 会话语义（AUD-007）在 Taro storage 无对应 | 低（P1） | 小程序端凭证持久化语义变化 | P0 不涉及（H5 保持 sessionStorage）；P1 设计「storage 键 + 7 天过期 + 退出即清」策略并评审 |

### 2.3 结论

- **P0（H5 架构迁移）可行且风险可控**，工作量 2~3 人日，产出 = 行为等价的 Taro 工程 + 未来小程序编译通道（weapp 配置就绪但暂不启用）。
- **P1（微信小程序）技术面可行**，但硬门槛在企业主体认证/类目审核（frozen/43 §1.3 合规前置，红线 §7 提示不代办），且需后端补 openid 绑定端点（`parent_bindings` 表已设计）。

---

## 三、目标架构设计（P0 完成后形态）

### 3.1 工程形态：原地演进（决策 D1）

**决策**：`frontend/parent-h5/` 原地改造为 Taro 工程（保留目录名与 git 历史），**不新建** parent-mini 双工程。

理由：
1. 用户指令「先对现有 H5 进行技术架构迁移」——原地演进最直接；
2. Taro H5 产物直接替代现有 Vite 产物，部署路径 `/parent/`、nginx、CI 均无需结构性变更；
3. 双工程并行 = 双份测试/依赖/契约维护（YAGNI），且 H5 链接入口必须长期可用，原地演进天然保证；
4. 与 frozen/43 §3.1「或原地改造 parent-h5」选项一致，本文落定「原地」方案。

### 3.2 目标目录结构

```
frontend/parent-h5/
├── config/
│   ├── index.ts                # Taro 编译配置：h5.router{browser, basename:'/parent'}、publicPath、alias(@shared)、sass
│   └── dev.ts / prod.ts        # 环境差异（dev 代理 /api → localhost:8080；prod publicPath）
├── project.config.json         # 微信开发者工具工程配置（P1 启用，P0 占位）
├── babel.config.js             # Taro 标准 babel 预设
├── package.json                # react 18.3.x + @tarojs/* 4.0.13+；vite/vitest 保留（测试用）
├── tsconfig.json               # paths: @shared → ../shared/src
├── src/
│   ├── app.config.ts           # pages 注册（verify/privacy/report/consent）+ window 配置
│   ├── app.tsx                 # 入口（Taro App 壳 + ErrorBoundary 包裹），替换 main.tsx
│   ├── app.scss                # 全局样式（类名化改造，见 §3.6）
│   ├── platform/               # ★ 平台适配层（新增，P0 核心产出）
│   │   ├── storage.ts          #   PlatformStorage：H5 实现 = sessionStorage（AUD-007 语义保持）
│   │   ├── request.ts          #   PlatformRequest：fetch 包装（Content-Type/Bearer/401 刷新编排）
│   │   └── redirect.ts         #   PlatformRedirect：H5 实现 = window.location
│   ├── pages/
│   │   ├── verify/index.tsx    # Taro 组件化（View/Input/Button/Form）
│   │   ├── privacy/index.tsx
│   │   ├── report/index.tsx
│   │   └── consent/index.tsx
│   ├── services/               # 原 api/ 目录（业务 API 签名不变）
│   │   ├── index.ts            #   业务 API（parentRegister/parentLogin/getReport/withdrawConsent）
│   │   └── endpoints.ts        #   契约单一事实源（不变）
│   ├── utils/auth.ts           # 改走 PlatformStorage（对外 API 不变）
│   └── components/ErrorBoundary.tsx  # reload 改走 PlatformRedirect
└── src/test/                   # vitest 保留：逻辑/契约测试不动，组件测试适配（见 §3.7）
```

> 注：`frontend/shared/src/auth-transport/` **保持原位不动**（三端共用），仅做注入式向后兼容改造（§1.4）；Taro 工程通过 `@shared` alias（webpack alias + tsconfig paths）引用。

### 3.3 平台适配层设计（P0 核心）

**原则**：业务代码零平台 API 直调；三接口均「接口 + 平台实现」结构，P1 小程序端各新增一个实现文件即可。

```ts
// platform/storage.ts —— 接口与 H5 实现
export interface PlatformStorage {
  get(key: string): string | null
  set(key: string, value: string): void
  remove(key: string): void
}
export const sessionStorageImpl: PlatformStorage = { /* sessionStorage 包装，保持 AUD-007 会话级语义 */ }

// platform/request.ts —— 请求底层（H5 实现 = fetch）
export interface PlatformRequest {
  <T>(path: string, opts: { method?: string; headers?: Record<string,string>; data?: unknown }): Promise<PlatformResponse<T>>
}
// 实现要点：Bearer 注入、JSON 编解码、401 时触发 refresh 编排（复用 DC-005 refreshTokens 注入 fetchImpl）

// platform/redirect.ts —— 跳转
export type PlatformRedirect = (to: string) => void   // H5: location.href = to；P1: Taro.reLaunch
```

**shared 模块注入改造（向后兼容）**：
- `createPlatformTokens(prefix, storage: PlatformStorage)`：新增，parent 用（sessionStorageImpl）；
- `refreshTokens(storage, baseUrl?, fetchImpl?)`：新增可选参数，parent 传入 PlatformRequest 的 fetch 包装；
- `handleSessionExpired(storage, loginPath?, redirect?)`：新增可选参数，parent 传入 PlatformRedirect；
- **student-h5 / teacher-web 调用零改动**（默认参数保持现状行为）。

### 3.4 路由设计（URL 完全兼容）

| 现状（react-router） | Taro（P0） | URL（不变） |
|---------------------|------------|-------------|
| `/` → VerifyPage | `pages/verify/index`（pages[0] 默认页） | `/parent/` |
| `/privacy` → PrivacyPage | `pages/privacy/index` | `/parent/privacy` |
| `/report` → ReportPage（Protected） | `pages/report/index` + 页面内守卫 | `/parent/report` |
| `/consent` → ConsentPage（Protected） | `pages/consent/index` + 页面内守卫 | `/parent/consent` |
| `*` → Navigate `/` | 未注册路径由 h5.router 兜底（customRoutes 或 404 重定向 verify） | — |

配置：`h5: { router: { mode: 'browser', basename: '/parent' }, publicPath: '/parent' }`。
跳转：页面内 `Taro.navigateTo({ url: '/pages/report/index' })`；守卫：`isAuthenticated()` 为假时 `Taro.redirectTo({ url: '/pages/verify/index' })`（report/consent 两页顶部守卫，语义同现状 ProtectedRoute）。
privacy 页「← 返回登录」：`Taro.navigateBack()` 或跳 verify（等价现状 `<Link to="/">`）。

### 3.5 API 层设计（业务签名不变）

- `services/index.ts`：5 个业务 API（register/login/getReport/withdrawConsent）+ refresh 编排，**函数签名与返回结构不变**（页面层零改动）；底层从 `fetch` 直调改为 `platform/request.ts`；
- `services/endpoints.ts`：契约清单**原样保留**，apiContract.test.ts 断言继续生效（架构迁移不破坏契约防线）；
- 401 刷新：复用 DC-005 `refreshTokens`（注入 fetchImpl）+ `handleSessionExpired`（注入 redirect），行为与现状一致。

### 3.6 样式策略

| 项 | 动作 |
|----|------|
| 元素选择器 | `* { margin/padding/box-sizing }`、`body { ... }` → 迁移为 `.page` 根类名包裹（双端安全） |
| SCSS 嵌套 | 保留（sass 编译后扁平化，H5 与 WXSS 均可用） |
| BEM 类名 | 保留（现状已是类名驱动，穿透风险低） |
| 单位 | H5 阶段保持 px（视觉零变化）；P1 小程序阶段设计稿 rpx 换算另行评审（frozen/43 §3.2 参考） |
| 动态样式 | 现状 `style={{ textTransform: 'uppercase', ... }}` 内联对象：H5 保留；P1 核对 Taro 内联样式支持（string 形态受限，对象形态 H5 可用） |

### 3.7 测试策略

| 层 | 现状 | P0 动作 |
|----|------|---------|
| 逻辑/工具单测（auth.test.ts 69 行） | vitest + jsdom + sessionStorage mock | **保留**；auth.ts 改走 PlatformStorage 后，mock 目标换为注入对象（断言等价） |
| 契约测试（apiContract.test.ts 80 行） | 断言源码端点 ⊆ endpoints.ts | **原样保留**（services 目录路径变化同步 import 路径） |
| 页面组件测试（5 文件 414 行） | testing-library + MemoryRouter（HTML 元素断言） | **适配**：Taro 组件（View 渲染 div 等）仍可用 testing-library 查询文本/占位符/角色；MemoryRouter 替换为 Taro 路由 mock 或组件直渲染；断言锚点改为「文本/占位符/aria」而非具体标签 |
| ErrorBoundary 测试 | — | reload 改 PlatformRedirect 后 mock 注入函数 |
| 覆盖率门禁 | 70/65/50/70（vitest.config） | **阈值不降**，迁移后实测并登记（对齐 AUD-022 惯例） |

### 3.8 CI / 构建 / 部署影响

| 项 | 现状 | P0 后 |
|----|------|-------|
| dev | `vite`（5174，/api 代理 8080） | `taro build --type h5 --watch`（dev server 端口/代理在 config/dev.ts 对齐 5174 + /api 代理） |
| build | `tsc && vite build` → dist/ | `taro build --type h5` → dist/（publicPath `/parent/`，资源路径不变） |
| lint/test | oxlint / vitest run | 保留（Taro 工程内 oxlint 可加 eslint-config-taro，可选） |
| 部署 | nginx `/parent/` 静态托管 | **nginx 零改动**（产物结构等价）；DEPLOY-GUIDE 构建命令段同步 |
| CI | workflow 中 parent-h5 build + test 步骤 | 命令替换（`taro build` + vitest 不变），门禁（覆盖率/契约）不变 |

---

## 四、阶段路线

### 4.1 P0：H5 架构迁移（本方案实施范围，2~3 人日）

| 任务 | 内容 | 产出 | 依赖 |
|------|------|------|------|
| T0 工程基线 | Taro 4.0.13+ 工程骨架铺入 parent-h5（config/、babel、tsconfig paths）；**React 降级 18.3.x**；依赖裁剪（移除 react-router，保留 react-dom 供 H5 测试）；dev/build 链路打通 | 可编译的空 Taro H5 工程 | — |
| T1 平台适配层 | `platform/` 三接口 + shared 模块注入改造（createPlatformTokens/fetchImpl/redirect 参数）；**student-h5/teacher-web 全量回归确认零影响** | 适配层 + 共享模块兼容改造 | T0 |
| T2 路由与入口 | app.config.ts pages 注册 + app.tsx 替换 main.tsx；basename/publicPath 验证四 URL 等价；守卫等价实现 | 四路由 URL 兼容可访问 | T0 |
| T3 页面迁移 | 4 页面组件化（View/Text/Button/Input/Form/Link 替换）；services/ 目录重构；auth.ts 走 PlatformStorage；app.scss 类名化 | 功能等价的 Taro H5 页面 | T1+T2 |
| T4 测试对齐 | 逻辑/契约测试保留；4 个页面测试适配；ErrorBoundary 测试更新；覆盖率实测登记 | 测试全绿 + 门禁不降 | T3 |
| T5 验证与文档 | dev 冒烟（登录/注册/周报/撤回/隐私五流程）+ build 产物核验；CI 命令替换；DEPLOY-GUIDE 构建段同步；frozen/43 基线更新登记 | 验收通过 + 台账同步 | T4 |

### 4.2 P1：微信小程序（后续专题，不在本方案实施范围）

沿用 frozen/43 W-1~W-7 路线，**基线更新**为：
- 前置（W-1）：企业主体认证 + 小程序注册 + AppID（红线 §7，非 AI 代办）；
- 平台层：Taro storage 实现 + `Taro.request` 实现 + `Taro.reLaunch` 实现（各新增 1 文件，业务零改动）；
- 认证增强：`wx.login` → openid → `parent_bindings` 绑定端点（后端新增，`parent_bindings` 表已设计）；
- 手机号：`getPhoneNumber`（企业认证 + 主动授权，不静默）；
- 订阅消息（PARENT-WX-006）：后置，YAGNI；
- 提审：隐私协议对齐 design/22、类目审核。

---

## 五、SPEC（验收标准，EARS 格式）

| # | EARS | 验收点 |
|---|------|--------|
| AC-1 | The 迁移后 H5 shall 保持单页应用形态，四路由 URL（`/parent/`、`/parent/privacy`、`/parent/report`、`/parent/consent`）与现状完全一致 | 手动导航 + 刷新 + 直达链接均可达 |
| AC-2 | While 运行于 H5，the 登录/注册（家庭码+手机号+密码+关系）、情绪周报（多孩子切换/概览/情绪分布/无数据态）、同意撤回（选择孩子/二次确认/结果态）、隐私告知 shall 与迁移前行为一致 | 全流程冒烟（T5） |
| AC-3 | The parent-h5 源码 shall 不再直接调用 `fetch`/`sessionStorage`/`window.location`（平台 API 全部经 platform/ 适配层） | grep 断言零残留（ErrorBoundary 刷新按钮除外，改经 redirect 注入） |
| AC-4 | The shared/auth-transport 注入改造 shall 不破坏 student-h5 与 teacher-web 现有调用（默认参数向后兼容） | 两工程测试全绿 |
| AC-5 | The 契约防线 shall 保持有效（endpoints.ts 单一事实源 + apiContract.test 断言） | 契约测试通过 |
| AC-6 | The 测试覆盖率门禁 shall 不降低（lines 70 / branches 65 / functions 50 / statements 70），全量测试通过 | vitest 实测登记（对齐 AUD-022） |
| AC-7 | The Taro 工程 shall 以 React 18.3.x 锁定（不引入 React 19 依赖），编译无警告 | package.json + build 输出核验 |
| AC-8 | While P0 完成后，the 微信小程序编译通道 shall 保持配置就绪但未启用（weapp 产物不进入交付） | config 保留 weapp 支持，CI 不构建 weapp |
| AC-9 | The 部署 shall 不改变 `/parent/` nginx 托管与 `/api` 代理（产物结构等价） | T5 构建产物 + nginx 配置 diff 核验 |
| AC-10 | The 迁移 shall 不改变后端 API 契约（无后端变更，P0 不引入新端点） | 契约测试 + 后端零 diff |

---

## 六、任务归口与台账

| 项 | 归口 |
|----|------|
| frozen/43 | **基线更新**：§二/§三/§四 按本文更新（4 页/register-login/sessionStorage/shared 依赖/H5 先行路线）；本文为 frozen/43 的 P0 细化设计，P1 仍以 frozen/43 为主体 |
| design/26 | 不动（其 Taro 表述已由 frozen/43 §〇 修正，本文不再重复） |
| design/30 §方向五 | PARENT-WX-001~006 归口不变，本文增加「P0 H5 架构先行」前置任务 |
| DEPLOY-GUIDE | P0 T5 同步构建命令段（vite build → taro build --type h5） |
| 后端 | P0 零改动；P1 需新增 openid 绑定端点（`parent_bindings`，design/26 已设计） |

---

## 七、风险与约束总表

| 风险 | 等级 | 缓解 |
|------|------|------|
| React 19→18 降级兼容性 | 中 | 代码审计无 19 独有 API；降级后全量回归；锁定 18.3.x |
| Taro H5 表单组件事件差异 | 中 | Form onSubmit + button onClick 双路径；AC-2 全流程冒烟 |
| 样式元素选择器双端约束 | 低 | P0 类名化，双端受益 |
| Taro 4 生态维护节奏 | 低 | 锁定 4.0.13+ 正式版，不用实验特性 |
| 企业主体认证（P1） | 阻塞项 | 红线 §7：非技术事项，尽早启动（frozen/43 W-1） |
| 未成年人数据类目审核（P1） | 中 | 对齐 design/22 隐私协议 + 最小必要（frozen/43 §1.3） |
| 工作量估计偏差 | 低 | 规模 19 文件可完整盘点；2~3 人日为上限估计 |

---

_本文基于 2026-08-07 代码基线（Vite 8 + React 19.2.7 + react-router 8 + DC-005 shared 模块）深度分析，结论：P0 H5 架构迁移可行（Taro H5 产物即 React SPA，URL/部署零变化，React 降级 18 成本≈0）；P1 小程序技术面可控、门槛在企业主体。Taro 版本兼容性事实依据 Taro 官方文档（h5.router.basename/mode、publicPath、React 18 支持）。_
