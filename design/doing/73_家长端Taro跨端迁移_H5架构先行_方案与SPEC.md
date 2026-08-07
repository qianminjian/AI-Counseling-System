# 73 - 家长端 Taro 跨端迁移（H5 架构先行）

> 创建：2026-08-07 | 状态：**实施中（TDD，2026-08-07 启动）** | 对应专题：frozen/43（家长端小程序化设计）、design/30 §方向五（PARENT-WX-001~006）
>
> 定位：frozen/43 判定家长端（Vite + React H5 SPA）技术栈不符合后续演进规划（微信小程序渠道），需 Taro 跨端迁移。本文按钱敏健指令「**先对现有 H5 进行技术架构迁移，输出仍保持 H5 单页应用**」输出**两阶段路线**：**P0 = H5 架构迁移（本方案主体）**——把 parent-h5 原地改造为 Taro 工程，H5 产物行为/URL/部署完全等价；**P1 = 微信小程序编译与认证接入（后续专题，依赖企业主体）**。本文只做设计，不含实现代码。

---

## 〇、执行摘要（结论先行）

| 问题 | 结论 |
|------|------|
| **可行性** | ✅ 可行。家长端规模极小（19 个源文件、4 页面、7 个测试文件 + setup 共 8 文件 564 行），Taro H5 产物本身即 React SPA，`h5.router.basename` 可保持 `/parent/` URL 与 nginx 部署完全不变 |
| **关键风险** | Taro 4 官方支持 React 18（依赖 `react ^18.3.1`），当前三端均为 React 19.2.7 → **Taro 工程需锁定 React 18**；家长端代码未使用 React 19 独有特性（无 `use()`/Actions/Server Components），降级成本 ≈ 0 |
| **迁移策略** | **原地演进 parent-h5 → Taro 工程**（不新建双工程）：git 历史连续、部署路径不变、用户指令「先对现有 H5 迁移」天然契合 |
| **P0 工作量** | 2~3 人日（工程改造 + 4 页面组件化 + 平台适配层 + 测试对齐 + CI 命令替换）；**实施前评估已出**：代码改造低复杂度（758 行源码、样式元素选择器仅 2 处、fetch 直调 1 处、React 19 独有 API 零使用），风险集中 T4 测试适配（R7） |
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
| R7 | **Taro 组件在 vitest+jsdom 渲染无官方承诺**：Taro 官方测试体系是 Jest+Enzyme；`@tarojs/components` 与 `@tarojs/taro` API 在 vitest 下渲染/mock 均需自行验证 | **高** | **T0 spike 前置**（红-绿循环）：最小 Taro 页面 + vitest+jsdom 验证 View/Button/Input 渲染与文本查询；受阻则 T4 降级「页面逻辑测试 + Taro 组件层 mock」，覆盖率门禁维持（以逻辑覆盖为主） |
| R8 | Taro 构建器选择（webpack5 默认 vs vite 实验） | 中 | **P0 锁定 `compiler.type='webpack5'`**（官方默认、稳定优先）；vite 模式后置为优化项 |
| R9 | 产物体积膨胀（Taro 运行时注入） | 低 | **AC-11：迁移前后 dist 体积对比基线**，偏差 >30% 需说明 |
| R10 | React 18 版本漂移（npm 解析到 19） | 低 | `react`/`react-dom`/`@types/react` 锁 **exact 版本 18.3.1**（非 ^） |
| R11 | shared 注入改造后 student/teacher 回归遗漏 | 低 | T1 完成后先跑两工程全量测试再进 T3（顺序约束） |

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

**组件事件模型差异（R2 实施要点，Taro 与 React DOM 的关键差异）**：

| DOM（现状） | Taro（P0） | 说明 |
|-------------|-----------|------|
| `input onChange`（`e.target.value`） | `Input onInput`（`e.detail.value`） | 核心差异，verify 页 3 个输入框全部涉及 |
| `button type="submit"` | `Button` + `Form onSubmit` | Taro Button 无 submit 语义，表单提交走 `Form` 事件 |
| `form onSubmit` | `Form onSubmit`（需 `e.preventDefault()`） | 事件对象形态差异 |
| `a href`（隐私页链接） | `Text`/`View` + `onClick` + `navigateTo` | 跨端无 `<a>`，H5 等价跳转 |
| `style={{}}` 内联对象 | H5 支持（对象形态） | 保持现状 |

**Taro API 使用（页面内）**：`Taro.navigateTo({ url: '/pages/xxx/index' })` / `Taro.redirectTo` / `Taro.navigateBack`；守卫用 `isAuthenticated()` + `redirectTo('/pages/verify/index')`（report/consent 两页顶部，语义同现状 ProtectedRoute）；测试中统一 `vi.mock('@tarojs/taro')`。

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
| 页面组件测试（5 文件 414 行） | testing-library + MemoryRouter（HTML 元素断言） | **spike 双路径（R7）**：路径 A = Taro 组件 jsdom 直渲染（`@tarojs/components` H5 端渲染真实 DOM，testing-library 文本/占位符查询可用）→ 保留现有断言模式；路径 B = spike 受阻 → 页面逻辑测试 + Taro 组件层 mock；导航断言统一改为 `vi.mock('@tarojs/taro')` 断言 navigateTo/redirectTo/navigateBack 调用 |
| ErrorBoundary 测试 | — | reload 改 PlatformRedirect 后 mock 注入函数 |
| 覆盖率门禁 | 70/65/50/70（vite.config） | **提升至 80/80/80/80（AC-12，用户指令）**，迁移后实测并登记 |

### 3.8 CI / 构建 / 部署影响

| 项 | 现状 | P0 后 |
|----|------|-------|
| dev | `vite`（5174，/api 代理 8080） | `taro build --type h5 --watch`（**`compiler.type='webpack5'`，R8 决策**；dev server 端口/代理在 config/dev.ts 对齐 5174 + /api 代理） |
| build | `tsc && vite build` → dist/ | `taro build --type h5` → dist/（publicPath `/parent/`，资源路径不变） |
| lint/test | oxlint / vitest run | oxlint 保留；**vitest 配置独立 `vitest.config.js`**（Taro 工程无 vite.config.js，student/teacher 同型先例）；**覆盖率门禁提升至 80/80/80/80（用户指令，AC-12）** |
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
| AC-11 | The 迁移后 H5 产物体积 shall 相对迁移前基线不膨胀超过 30%（dist 体积对比） | T0 前记录基线体积 + T5 实测对比登记 |
| AC-12 | The 测试覆盖率 shall 四维度均达到 80% 以上（lines/branches/functions/statements） | vitest coverage 实测登记 |

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

## 八、TDD 实施计划（2026-08-07 评估后定稿）

### 8.1 实施顺序（评估结论：不确定性前置）

| 阶段 | 内容 | TDD 循环（红→绿→重构） | 验收 |
|------|------|------------------------|------|
| T0 工程基线 | Taro 4.0.13+ 骨架铺入 parent-h5（config/、babel、tsconfig paths）；**React 降级 18.3.1（exact，R10）**；依赖裁剪（移除 react-router）；**spike（R7）**：最小 Taro 页面 + vitest+jsdom 渲染验证 | 红：spike 测试先写（View/Button/Input 渲染 + 文本查询断言）；绿：Taro 组件渲染通过；重构：骨架清理 | 可编译空 Taro H5 工程 + spike 结论记录（路径 A/B） |
| T1 平台适配层 | `platform/` 三接口 + shared 注入改造（createPlatformTokens/fetchImpl/redirect）；**student/teacher 全量回归（R11）** | 红：platform 三接口测试 + shared 兼容测试（默认参数行为不变断言）；绿：实现；重构：收敛重复 | 适配层 + 共享模块兼容 + 三端测试全绿 |
| T2 路由与入口 | app.config.ts pages 注册 + app.tsx 替换 main.tsx；basename/publicPath 四 URL 等价；守卫等价 | 红：路由映射断言（4 URL → 页面）；绿：实现；重构：— | 四路由 URL 兼容可访问 |
| T3 页面迁移 | 4 页面组件化（View/Text/Button/Input/Form 替换，**onInput/onSubmit 事件模型 R2**）；services/ 目录重构；auth.ts 走 PlatformStorage；app.scss 类名化（2 处元素选择器） | 红：现有 5 页面测试改写先行（Taro 化断言 + @tarojs/taro mock）；绿：页面实现；重构：样式类名化 + 重复逻辑收敛 | 功能等价的 Taro H5 页面 |
| T4 测试对齐 | 覆盖率 80% 达成（AC-12）：api 层不整模块 mock（mock fetch 走真实 request 逻辑）、platform 专项测试、页面交互分支补齐 | 红：缺覆盖用例逐个写；绿：补测通过；重构：断言收敛 | 四维度 ≥80% 实测登记 |
| T5 验证与台账 | 冒烟（登录/注册/周报/撤回/隐私五流程）+ 产物体积对比（AC-11）+ CI 命令替换 + DEPLOY-GUIDE 同步 + 台账登记（DOC-072） | — | 验收全过 + 台账同步 |

### 8.2 覆盖率 80% 达成策略（现状 73.62/72.72/54.76/73.62 的差距分析）

| 缺口 | 现状原因 | 达成手段 |
|------|----------|----------|
| functions 54.76 失真 | `api/index.ts` 被整模块 `vi.mock` 替换，v8 统计不到真实函数体 | 迁移后 **api 层不整模块 mock**：mock 全局 `fetch` + storage 注入，`request()` 真实执行（401 刷新/错误分支/成功分支全覆盖） |
| 页面分支覆盖 | 现有测试覆盖主路径，loading/空孩子/无 data 分支少 | 按交互分支补齐用例（verify 校验分支 × 3、report 无孩子/加载中、consent 空孩子） |
| platform/ 新增层 | 无测试 | 三接口专项测试（storage 语义、request 编排、redirect 调用） |
| auth.ts 改造后 | 测试目标变化 | auth.test.ts 改为注入 mock storage 断言等价 |

### 8.3 决策记录（2026-08-07 实施前评估）

| 决策 | 结论 | 依据 |
|------|------|------|
| D2 构建器 | `compiler.type='webpack5'`（R8） | Taro 4 默认、稳定优先；vite 模式实验性后置 |
| D3 测试生态 | vitest + testing-library 保留，spike 双路径（R7） | 现有 564 行测试资产复用优先；Taro 官方 Jest 体系迁移成本高 |
| D4 React 版本 | 18.3.1 exact 锁定（R10） | Taro 4 官方依赖 ^18.3.1；代码审计无 React 19 独有 API |
| D5 覆盖门禁 | 80/80/80/80（AC-12） | 用户指令（本专题）高于既有 70/65/50/70 门禁 |

---

## 九、实施记录

| 日期 | 事件 | 状态 |
|------|------|------|
| 2026-08-07 | 方案输出（bd0a407） | ✅ |
| 2026-08-07 | 实施前复杂度/风险评估：改造低复杂度、风险集中 T4（R7），spike 前置 | ✅ |
| 2026-08-07 | 文档更新：R7~R11、构建器决策、组件事件差异、AC-11/12、TDD 计划 | ✅ |
| 2026-08-07 | **产物体积基线记录（AC-11）**：迁移前 dist = 256K（2026-08-07 12:54 构建） | ✅ |
| 2026-08-07 | **T0 工程基线 + spike（R7）路径 A 判定成功**：Taro 4.2.1 骨架铺入（config/、babel.config.cjs、project.config.json、app.config.ts、app.tsx）；React 18.3.1 exact 降级；react-router 移除；vitest alias `@tarojs/components` → `lib/react`（主入口为 Stencil ES class，react-dom 无法直接调用）；setup.ts 注入 8 个编译期常量（7 个 ENABLE_* + DEPRECATED_ADAPTER_COMPONENT=false）；spike 4/4 通过——jsdom 下 Stencil shadow DOM 不初始化（宿主为属性透传+children 槽位），children 渲染/宿主原生事件可用，Input/Form 事件需 fireEvent 显式派发，回调收到原生 Event（页面按 R2 取 e.target.value）；tsc 通过（react-router 4 处预期错误待 T3 消除）；全量回归 45/45；旧 4 页面测试临时 exclude（T4 移除） | ✅ |
| 2026-08-07 | **T1 平台适配层完成**：`platform/` 三接口落地（storage.ts 的 sessionStorageImpl / request.ts 的 createPlatformRequest 工厂 / redirect.ts 的 locationRedirect）；shared 注入改造（createPlatformTokens(prefix, storage)、refreshTokens 第三参 fetchImpl、handleSessionExpired 第三参 redirect），三端调用零改动；TDD 红→绿（platform 15 用例 + shared 注入兼容 5 用例，其中修复 createTokens 重构时 sessionStorage 直接传入导致的 get/set 缺失 bug）；三端全量回归全绿（parent 66 / student 854 / teacher 211）；tsc 通过 | ✅ |
| 2026-08-07 | **T2 路由与入口完成**：`src/routing/route-map.ts` 单一事实源（PAGES 注册表 + ROUTE_MAP 四 URL 映射，app.config.ts 与 config/index.ts h5.router.customRoutes 均引用，4 用例 TDD 绿）；scripts 切换 Taro CLI（dev/build/preview）；main.tsx 删除（入口切换 app.tsx）；vitest coverage exclude 修正（main→app、vite.config.js）；页面内守卫/跳转迁移与 404 通配行为验证留 T3/T5；全量回归 70/70；tsc 通过 | ✅ |
| 2026-08-07 | **T3 页面迁移完成**（3 原子提交 ec67fba/6fd2035/3cc5e45）：T3a 服务层——`src/services/`（createPlatformRequest + parent_ token 存储 + 5 业务 API）替换 `api/` 死代码（apiContract 测试指向 services/）；auth.ts 平台化（createPlatformTokens('parent_', sessionStorageImpl)，refresh 键收敛 `parent_refresh`）；request.ts doFetch 解析移至请求调用时（修复测试 stubGlobal 失效，生产语义不变）。T3b 页面 Taro 化——4 页面重写（View/Text/Button/Form/Input、onInput + inputValue 双形态取值、formType="submit"、password 布尔属性、nav.ts/event.ts 工具）；report/consent 登录守卫 useEffect、verify 渲染期重定向；ErrorBoundary Taro 化 + app.tsx 包裹；4 页面测试 Taro mock 化（Stencil 宿主 fireEvent 派发）106/106 全绿 + tsc 零错误。构建链路——compile.include + webpackChain 编译 `../../shared/src`（**config/ 目录相对路径坑**：__dirname=parent-h5/config/，需两层回退）；Taro 4 H5 模板在 **src/index.html**（非根目录）；补装 webpack@5.91 + @babel/preset-react（Taro webpack5 runner peer，--legacy-peer-deps）；删旧 Vite 入口；生产构建成功 331 KiB（AC-11 待 T5 终值对比） | ✅ |
