# UI-STYLE-001 全端页面风格审计与优化报告

> 日期：2026-08-11 | 范围：student-h5 / teacher-web / parent-h5 / admin-web 全部页面
> 方法：design-debt-review 自动化扫描（165 文件）+ 设计文档 08 §4.1 契约比对 + 人工分类
> 基线：design/08_系统功能概要设计.md §4.1 设计系统（青屿品牌体系 doing/75 方案 A + 风险等级色板跨端强一致 + 学生端儿童主题保留项）

---

## 一、Token 契约（审计基准）

- **青屿品牌色**：`--ms-primary #2BA8A0` 系 18 项 + 形态 token（`--ms-radius-card 16px` / `--ms-radius-control 12px` / `--ms-shadow-card` 等）
- **风险等级色板（跨端强一致）**：R4 红 `#FFF1F0/#CF1322`、R3 橙 `#FFF7E6/#D46B08`、R2 黄 `#FFFBE6/#D4B106`、R1 绿 `#F6FFED/#389E0D`
- **学生端主题（产品特性保留）**：ocean 蓝 `#0EA5E9` / 粉 `#EC4899` / 紫 `#8B5CF6` + 沉浸式主题 `theme/immersiveStyles.ts` 单源
- **保留项（doing/75 §7.3）**：BigScreen 暗色大屏配色、情绪分类色 EMOTION_COLORS、中性灰阶

## 二、各端现状评估

| 端 | Token 基础设施 | 总体评价 |
|----|---------------|---------|
| teacher-web | ✅ `:root --ms-*` 全套 + antd ConfigProvider 青屿 token + 暗色覆盖 + 大屏 `--ms-bs-*` 收编 | 良好，发现 1 处跨端强一致项值漂移 |
| parent-h5 | ✅ `:root --ms-*` 全套（Taro SCSS） | 良好，发现 2 处微漂移 |
| student-h5 | ✅ 集中式主题样式表 + 三主题场景化（ocean/garden/rainbow）+ 语义色 F-06 收编 | 良好，发现情绪色双套、未定义 CSS 变量等 4 处 |
| admin-web | ✅ `:root --ms-*` 同值引入 + antd ConfigProvider 青屿（doing/83 §8.1~8.9） | 良好，无强一致项漂移 |

扫描量：165 文件，findings 1000 条（上限截断）。分类后**真正影响设计风格的漂移为 9 项**，其余为合法集中定义（样式表/主题文件）与维护性 debt（布局类 inline style）。

## 三、发现与修复记录

### P0 跨端强一致项漂移（已修复）

**1. [major] 风险等级色板值漂移 — teacher-web riskLevel.ts**
- Evidence：`RISK_LEVEL_META.hex` 用 antd 色板（`#52c41a/#ffd54f/#ff9800/#f44336`），与契约（`#389E0D/#D4B106/#D46B08/#CF1322`）不一致；工作台 StatsCharts 柱图浅黄 `#ffd54f` 在白色背景对比度不足
- Fix：`hex` 改为契约值（工作台亮底消费）；新增 `hexBright` 收大屏暗底亮色（保留项 doing/75 §7.3）；BigScreen 图例改用 `riskHexBright`；测试同步断言
- Files：`teacher-web/src/utils/riskLevel.ts`、`teacher-web/src/test/riskLevel.test.ts`、`teacher-web/src/pages/BigScreen.tsx`
- Doc：08 §4.1 FA-01 记录已同步（hex 契约化 2026-08-11）

**2. [minor] 页面背景色与 token 不同值 — parent-h5 app.config.ts**
- Evidence：`window.backgroundColor: '#f5f6fa'` ≠ `--ms-bg #FAF9F6`
- Fix：改为 `#FAF9F6`（Taro window 配置不支持 CSS 变量，注释说明与 token 同值）

### P1 语义色重复/未定义（已修复）

**3. [major] 情绪分类色两套实现 — EmotionDiary vs EmotionSelect**
- Evidence：同一 5 情绪两套色表：EmotionDiary hex（`#52c41a/#722ed1/#ff4d4f/#9254de/#fa8c16`，含已收编旧紫 `#722ed1`）vs EmotionSelect Tailwind 类（yellow/blue/red/purple/orange）——sad 紫 vs 蓝、scared 浅紫 vs 紫，数据语义映射色不一致
- Fix：shared `emotionMeta.ts` 新增 `STUDENT_EMOTION_COLORS` 单源（strong/text 两值，Tailwind 400/800 级）；EmotionDiary 引用（选中文字改用 text 深色保证浅底可读）；EmotionSelect 注释说明 JIT 限制与同源保证；emotionMeta.test 新增键集/色相锚点断言防回潮
- Files：`shared/src/emotionMeta.ts`(+test)、`student-h5/src/components/EmotionDiary.tsx`、`EmotionSelect.tsx`

**4. [minor] 引用未定义 CSS 变量 — student-h5 ChatRoom.tsx**
- Evidence：`var(--warning, #d97706)` 全项目未定义 `--warning`，回退值散落
- Fix：改为 Tailwind `text-amber-600`，与胶囊（`bg-amber-50/border-amber-200/bg-amber-400/500`）同色系一致

**5. [major] 错误边界硬编码色 — parent-h5 / student-h5 ErrorBoundary**
- Evidence：`#555/#888/#4f8ef7/#ddd`（parent：蓝按钮非青屿主色；student：非主题色）
- Fix：parent 改用 `--ms-text/--ms-text-secondary/--ms-text-muted/--ms-primary/--ms-radius-control`；student 改用主题 `--primary/--card-bg` + 新增中性语义变量 `--text-strong #262626`、`--border #E5E7EB`（index.css :root，F-06 收编同族）

### P2 维护性 debt（本次不批量改，登记在案）

- **布局类 inline style 274 条**（flex/gap/padding/fontSize/textAlign）：四端均存在，视觉无漂移；批量 class 化回归风险高、收益低，建议后续按面板渐进式（doing/75 §7.5-1 模式）
- **WelcomeGuide 渐变背景**（`#667eea→#764ba2` 等 4 组）：引导页装饰性场景渐变，与登录三主题场景化同类，**文档化为可接受例外**
- **BoBoAvatar/BoBoPet SVG 部件色**（sky 系）：组件原始实现 + `colors={theme.bobo}` 注入覆盖，随主题生效，属 component primitive
- **antd JS token 注入**（`#2BA8A0/#163B38` 等）：antd ConfigProvider 不支持 CSS 变量，与 `--ms-*` 同值并已注释，合法例外

## 四、验证结果

| 验证项 | 结果 |
|-------|------|
| teacher-web riskLevel.test（6 用例） | ✅ 全过 |
| shared emotionMeta.test（6 用例） | ✅ 全过 |
| student-h5 相关（EmotionDiary/EmotionSelect/ErrorBoundary/ChatRoom 77 用例） | ✅ 全过 |
| parent-h5 ErrorBoundary.test（2 用例） | ✅ 全过 |
| 三端 `tsc --noEmit` | ✅ 0 错误 |
| 重扫 hard-coded-color（组件层） | 268 → 256（-12，与修复量吻合） |

## 五、后续建议

1. **防护**：将 `STUDENT_EMOTION_COLORS` 色相锚点断言纳入 shared 测试（已加）；`--warning` 类未定义变量可加 stylelint 规则
2. **渐进式 inline-style class 化**：teacher-web 面板已建立 `.ms-*` 工具类模式（index.css），后续新面板遵循；存量按 doing/75 §7.5-1 分批
3. **契约一致性**：DESIGN.md（本仓库根）已建立 token 契约 frontmatter，后续可用 `audit-design-debt.mjs` 持续扫描（`design.qa.yaml` 已配置）

## 六、产物

- `DESIGN.md` — 跨端 token 契约（design/08 §4.1 结构化）
- `design.qa.yaml` — 设计债扫描配置
- `.design-qa/reports/design-debt.json` — 扫描报告
