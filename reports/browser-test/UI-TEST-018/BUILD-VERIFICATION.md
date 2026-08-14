# UI-TEST-018 四端本地构建验证

执行日期：2026-08-14

| 端 | 命令 | 结果 |
|---|---|---|
| student-h5 | `npm run build` | 通过；tsc + Vite build 成功 |
| teacher-web | `npm run build` | 通过；tsc + Vite build 成功 |
| parent-h5 | `npm run build` | 编译成功；存在入口体积 337 KiB 警告 |
| admin-web | `npm run build` | 通过；tsc + Vite build 成功 |

结论：工作区四端均具备本地构建产物；线上问题与本地测试/构建结果不一致，需部署同一批产物后才能判断 UAT 是否恢复。parent-h5 入口体积警告记录为非阻断项。

## 定向回归测试（2026-08-14 10:15）

| 端 | 实际命令 | 结果 |
|---|---|---|
| student-h5 | `npm test -- --run src/test/ConsentGate.test.tsx src/test/EmotionDiary.test.tsx` | 2 files / 20 tests 通过 |
| teacher-web | `npm test -- --run src/test/OnboardingGuide.test.tsx src/test/Dashboard.test.tsx` | 2 files / 20 tests 通过 |
| parent-h5 | `npm test -- --run src/test/VerifyPage.test.tsx` | 1 file / 13 tests 通过 |
| admin-web | `npm test -- --run src/test/AdminLayout.test.tsx src/test/MetricsPage.test.tsx src/test/AlertPage.test.tsx src/test/AuditPage.test.tsx` | 4 files / 14 tests 通过 |

注：首次复核使用了不存在的 `src/components/...` 路径，Vitest 返回 `No test files found`；该次结果不计入验证，随后已按仓库实际 `src/test/` 路径重新执行并通过。
## R4 统一候选批次（2026-08-14）

- 学生端：`npm test -- --run`，78 个测试文件、958 项通过；`npm run build` 通过。
- 教师端：`npm test -- --run`，35 个测试文件、221 项通过；`npm run build` 通过。
- 家长端：`npm test -- --run`，27 个测试文件、219 项通过；`npm run build` 通过；存在既有入口体积警告（337 KiB）。
- 管理端：`npm test -- --run`，23 个测试文件、64 项通过；`npm run build` 通过。
- 学生端 PIN/API 定向回归：87 项通过；后端 `TrialAuthServiceTest` 通过。
- 说明：本批次仅完成本地验证，按要求暂不部署；待发布前统一提交、推送和一次性部署。
