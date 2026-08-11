---
version: 1.0
name: AI-Counseling-System-design-tokens
description: 跨端设计 token 契约，源自 design/08_系统功能概要设计.md §4.1 设计系统（青屿品牌体系 doing/75 方案 A + 风险等级色板 + 儿童端主题保留项）

tokens:
  # 青屿品牌色（家长端/老师端/管理端 --ms-* 语义 token，同名同值）
  ms-primary: "#2BA8A0"
  ms-primary-strong: "#1E7F7A"
  ms-primary-soft: "#E8F6F4"
  ms-bg: "#FAF9F6"
  ms-bg-elevated: "#F4F7F6"
  ms-card: "#FFFFFF"
  ms-text: "#22303A"
  ms-text-secondary: "#5C6B76"
  ms-text-muted: "#8A97A0"
  ms-border: "#E3E8E6"
  ms-border-soft: "#EEF2F0"
  ms-success: "#2E9E6B"
  ms-success-soft: "#E8F6EE"
  ms-warning: "#D98E32"
  ms-warning-soft: "#FDF1E3"
  ms-warning-border: "#F2DCC0"
  ms-danger: "#D9534F"
  ms-danger-soft: "#FDEBEA"

  # 风险等级色板（跨端强一致项）
  risk-r4-bg: "#FFF1F0"
  risk-r4-fg: "#CF1322"
  risk-r3-bg: "#FFF7E6"
  risk-r3-fg: "#D46B08"
  risk-r2-bg: "#FFFBE6"
  risk-r2-fg: "#D4B106"
  risk-r1-bg: "#F6FFED"
  risk-r1-fg: "#389E0D"

  # 学生端儿童自选主题（产品特性保留）
  theme-ocean: "#0EA5E9"
  theme-pink: "#EC4899"
  theme-purple: "#8B5CF6"

  # 形态 token
  ms-radius-card: "16px"
  ms-radius-control: "12px"
  ms-radius-pill: "24px"
  ms-shadow-card: "0 4px 16px rgba(43,168,160,0.08)"
  ms-shadow-tab: "0 2px 6px rgba(43,168,160,0.12)"

  # 间距刻度
  spacing-xs: "4px"
  spacing-sm: "8px"
  spacing-md: "16px"
  spacing-lg: "24px"
  spacing-xl: "32px"

  # 圆角刻度
  radius-sm: "8px"
  radius-md: "12px"
  radius-lg: "16px"
  radius-full: "9999px"

  # 字体刻度（学生端/教师端）
  font-bubble: "18px"
  font-button: "20px"
  font-title: "16px"
  font-body: "14px"
  font-caption: "12px"
---

# AI-Counseling-System 设计 token 契约

## Overview

跨端设计基准见 `design/08_系统功能概要设计.md` §4.1。家长端/老师端按青屿方案 A 统一（`--ms-*` token 同名同值）；管理端（admin-web）沿用同一品牌体系；学生端保留儿童自选主题（ocean 蓝/粉/紫，产品特性）与沉浸式主题收敛 `theme/immersiveStyles.ts` 单源。风险等级色板（R4 红/R3 橙/R2 黄/R1 绿）为跨端强一致项。

## Colors

- 青屿主色 `#2BA8A0` 用于主按钮/链接/选中态，不得在业务代码硬编码其他主色系。
- 中性灰阶走 `--ms-text` / `--ms-text-secondary` / `--ms-text-muted` / `--ms-border`。
- 语义状态（成功/警告/危险）走 `--ms-*-soft` 浅底 + 深色前景的成对 token。
- 风险等级四色仅用于风险语义，禁止新造等级名。

## Typography

- 教师端/管理端：标题 16px/1.5/600、正文 14px/1.5、辅助 12px/1.4。
- 学生端：对话气泡 18px/1.6、按钮标签 20px/1.4/600。
- 字体族 `-apple-system, "PingFang SC", "Microsoft YaHei", sans-serif`。

## Layout

- 间距刻度 xs 4 / sm 8 / md 16 / lg 24 / xl 32px。
- 卡片 16px 圆角（`--ms-radius-card`），控件 12px 圆角（`--ms-radius-control`），胶囊 24px（`--ms-radius-pill`）。

## Elevation & Depth

- 卡片阴影 `--ms-shadow-card`：0 4px 16px rgba(43,168,160,0.08)；tab 阴影 `--ms-shadow-tab`：0 2px 6px rgba(43,168,160,0.12)。

## Components

- 按钮/卡片/表格/表单等重复模式应复用组件与语义变体，不在页面级散落自定义样式。
- 儿童端大触控目标（按钮 ≥48×48px）、圆角 ≥12px、动效 200-300ms ease-out。

## Do's and Don'ts

Do:

- 业务代码使用 `--ms-*` 语义 token 或 antd ConfigProvider 主题 token。
- 风险色板只用于风险语义且跨端同值。
- 新增颜色/阴影/圆角前先确认 token 是否存在。

Don't:

- 在页面/组件代码硬编码非契约十六进制色值、自定义阴影、越级 px 值。
- 创建与青屿主色冲突的新主色（保留项除外：学生端儿童主题、BigScreen 暗色大屏）。
- 新增风险等级名或非语义等级色。

## Known Gaps

- admin-web（后台管理端）是否完整接入青屿 token 需审计确认。
- student-h5 主题色板收敛与风险四色语义一致性需审计确认。
