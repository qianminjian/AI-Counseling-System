# 线上 Browser Agent 与本地前端对照证据

日期：2026-08-14

## 本地测试结果

| 端 | 测试文件 | 结果 |
|---|---|---|
| student-h5 | `ConsentGate.test.tsx`、`EmotionDiary.test.tsx` | 2 files / 20 tests passed |
| teacher-web | `OnboardingGuide.test.tsx`、`Dashboard.test.tsx` | 2 files / 20 tests passed |
| parent-h5 | `VerifyPage.test.tsx` | 1 file / 13 tests passed |
| admin-web | `AdminLayout.test.tsx`、`MetricsPage.test.tsx`、`AlertPage.test.tsx`、`AuditPage.test.tsx` | 4 files / 14 tests passed |

## 对照结论

- 本地 `ConsentGate` 测试覆盖滚动/勾选后触发 `onAgree`；线上 Browser Agent 在补做滚动后已进入注册表单，原学生端同意问题关闭。
- 本地 `EmotionDiary` 测试覆盖选择情绪、提交后显示“今天已记录”；线上在将提交按钮滚动到可见区域后提交成功，原日记问题关闭。
- 本地 `OnboardingGuide` 覆盖跳过关闭；线上关闭/跳过未生效，且设置完成标记后菜单仍不切换，说明需要核对 UAT 发布版本/静态资源缓存/运行时版本，而不能直接以本地绿测替代线上缺陷。
- 本地 `AdminLayout` 覆盖 `onNavigate` 菜单回调；线上指标看板、告警中心、审计日志、终端设备仍停留在服务状态，优先怀疑 UAT 前端包未包含当前代码或运行时资源不一致。
- 本地 `VerifyPage` 覆盖家长表单提交；线上注册页视觉快照未暴露 button，需继续核对 Taro H5 产物版本与实际 DOM。

本文件只证明本地组件测试通过，不证明 UAT Browser Agent 通过；发布前仍需重新构建、部署并按四端遍历复测。
