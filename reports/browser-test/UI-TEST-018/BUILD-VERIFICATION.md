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

## R6 统一部署记录（2026-08-14）

- 四端构建成功，学生/教师/家长/管理端静态资源同步成功。
- tts/voice rsync 因远端 SSH connection reset 失败，部署脚本最终失败；详见 `logs/deploy/deploy-20260814-113058.log`。
- 四端入口复测可加载，但后端重启与 smoke gate 未完成，不能将本次部署标记为完整发布成功。

## R24 统一候选批次部署（2026-08-14）

- 候选提交：`19b239e4`（学生端聊天响应头阶段超时修复）。
- 四端全量门禁：student 78 files/959 tests；teacher 35 files/221 tests；parent 27 files/219 tests；admin 23 files/64 tests；四端构建退出码均为 0。
- 部署命令：`SKIP_CI_GATE=1 ./deploy.sh --backend --student --teacher --parent --admin`。
- 结果：backend + 四个 Web 端统一部署成功；服务健康检查通过；发布后 E2E 冒烟 32/32；四端 nginx 路径校验通过。
- 部署日志：`logs/deploy/deploy-20260814-121904.log`；审计：`logs/deploy/audit-20260814-121904.md`。
- Browser Agent 发布后四端入口均可加载并已保存首屏截图；学生端登录后续自动化会话无响应，风险修复的完整 UI 复测仍待新会话重试，不能提前标记为 VERIFIED。
