# 审计报告 12 - 测试体系

- **审计时间**：2026-08-12
- **审计范围**：`tests/` 根目录（audio 11 / e2e 8 / performance 1 / unit 3+14）+ `.github/workflows/ci.yml`（6 个 job）+ 各端测试配置（前端 4 端 vitest 阈值、后端 JaCoCo 门禁、Python pytest）+ 各板块已盘点的测试规模汇总（backend / student-h5 70 / teacher-web 28 / admin-web 19 / parent-h5 16 / tts 6 / voice 4）
- **方法**：全量读取 ci.yml（350 行，覆盖率门禁实现）+ playwright.config.ts + smoke-test.sh 结构 + 四端 vitest thresholds 对比 + tests/ 目录资产盘点 + git log 热点（ARCH-009 工程化门禁批次、DA-04 E2E 移除）+ 冻结决策核对（只读，未改动任何文件）

## 1. 板块概况

**CI 矩阵**（6 个 job，ci.yml）：backend-build-test（单测+IT+JaCoCo+Schema 快照门禁+覆盖率门禁，:38-158）→ jdk25-smoke（编译冒烟，:160-180）→ python-services-test（tts/voice pytest 矩阵，:182-222）→ shell-tools-test（tests/unit/scripts 14 件套，:224-242）→ docker-build-smoke（Python 镜像构建+import 冒烟，:244-280）→ dependency-scan（Trivy 后端+前端，:282-312）→ frontend-build（三端 build+lint+test:coverage，:314-350）。

**覆盖率门禁实况**（台账已修正，design/05_系统测试指导.md:31）：80% 是**达成目标值**（后端已 84.3%），CI 门禁为**整体 ≥45% + 核心 service ≥60%**（ci.yml:123-150，报告缺失即失败）；前端四端 vitest thresholds：teacher 80/75/60/80（vitest.config.js:42-47）、parent 全 80（vitest.config.ts:54-59）、admin 70/60/55/70、**student 50/35/40/50**（见 P1-2）。

**测试资产**：tests/unit/scripts 14 个脚本测试（含 deploy 三库、prepare-funasr、verify-config-passthrough、verify-nginx-host 等，CI 已全接入）；tests/audio 音频资产（唤醒词/长语音/10MB 边界/fake.txt 后缀白名单用例）；tests/e2e（Playwright 配置 + smoke-test.sh 31 断言部署冒烟）；tests/performance/chat-load.js（k6）。

## 2. 热点与风险初判

- **ARCH-009 工程化批次**（2026-08-06）：pytest 入 CI、teacher-web 覆盖率 89.99%+阈值 80、模型自动化、parent-h5 lint。
- **DA-04**（2026-08-08）：CI 不跑 Playwright E2E（全栈成本高），部署现场冒烟走 smoke-test.sh。
- **风险初判**：①admin-web 不在 CI 构建矩阵（见 P1-1）；②student-h5（核心对话端）覆盖率阈值全端最低（见 P1-2）；③Playwright E2E 配置/依赖残留为死代码（见 P1-3）。

## 3. 发现清单

### P0（架构级）
**未发现**。CI 门禁链完整且层级合理（编译→单测→IT→覆盖率→依赖扫描→构建冒烟），覆盖率门禁"报告缺失即失败"（ci.yml:116-122）杜绝静默跳过；shell 脚本测试、Python 服务测试均从"存在但永不执行"修复为 CI 强制（:225 注释明示）；schema 快照门禁（:108-112）防迁移参照物过期。

### P1（模块级）

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试判断 |
|---|---|---|---|---|---|
| P1-1 | .github/workflows/ci.yml:318-319（frontend-build matrix 仅 student-h5/teacher-web/parent-h5） | **admin-web 零 CI 门禁**：19 页 + 19 个测试文件的平台管理端不在构建矩阵中——不 build、不 lint、不跑 test:coverage。板块08 P1-1/P1-2 已发现其端点治理与鉴权处理问题，无 CI 意味着这些问题的回归无任何自动化防线 | matrix 加入 admin-web（其 vitest.config.js 已配置阈值 70/60/55/70，npm scripts 就绪）；若构建耗时顾虑可先单独加 build+lint job | 一致性：四端统一 CI 覆盖；leverage：板块08 修复项获得回归保护 | 新增：admin-web 现有 19 测试直接进入门禁，无需改动测试本身 |
| P1-2 | frontend/student-h5/vitest.config.js:38-43（thresholds 50/35/40/50） | **核心对话端覆盖率阈值全端最低**：student-h5（心理辅导主战场、70 个测试文件为全端最多）lines 50 / branches 35 / functions 40，对比 teacher 80/75/60、parent 全 80、admin 70/60/55。阈值低≠实测低，但门禁放水意味着近半数行无断言约束，与"核心 service ≥60%"的后端门禁形成反差 | 以当前实测覆盖率为基线分两步提升（如先提至 60/50/55/60），每步跑满 CI 确认达标后推进，直至对齐 teacher-web 水平；与板块07 修复项联动 | 一致性：核心端门禁与其他端收敛；leverage：对话域改动（板块04 热点区）获得更强回归网 | 保留：student-h5 70 测试继续运行，提升阈值不删用例 |
| P1-3 | tests/e2e/playwright.config.ts:6（testDir './specs'）+ package.json/package-lock.json/node_modules/playwright-report/ 残留 | **Playwright E2E 死配置**：DA-04（2026-08-08）已移除 CI E2E，但 `testDir: './specs'` 指向的目录不存在、webServer 三端 dev server 配置（:35-57）为过期假设（端口/命令与现前端 dev 配置可能漂移）、node_modules 目录残留（需确认 .gitignore 覆盖，0.1KB 的 .gitignore 疑似未排除）。运行 `npx playwright test` 会直接报 specs 不存在 | 二选一：①删除 tests/e2e 的 Playwright 骨架（保留 smoke-test.sh + 注释登记 DA-04 决策）；②若未来恢复 E2E，按"先补 specs 再恢复 CI"顺序实施，避免半配置状态 | 一致性：仓库零死配置；避免误跑报错误导新成员 | 不适用（删除死配置，smoke-test.sh 保留） |
| P1-4 | tests/performance/chat-load.js（7.9KB k6 脚本） | **性能压测脚本无 CI 调度**：k6 脚本存在但无任何 workflow/门禁引用，只能手工运行，压测基线无法累积对比（无历史基线比对机制） | 短期：README/注释登记运行方式与基线值；中期：加 workflow_dispatch 手动触发 job 记录基线报告 | 可观测性：性能回归有据可查 | 不适用（新增调度，不改脚本） |

### P2（局部）

| 编号 | 位置 | 问题描述 | 建议方案 |
|---|---|---|---|
| P2-1 | tests/audio/big.mp3（10MB）+ 全部音频资产 | 10MB 二进制测试资产直接入 git 仓库（无 .gitattributes/git-lfs），仓库体积膨胀，clone 成本上升 | 确认各资产必要体积；big.mp3 可降采样生成（10MB 边界用例可用合成文件替代）；或引入 git-lfs |

## 4. 改进候选排序

- **Strong**：P1-1（admin-web 入 CI 矩阵——配置就绪、加一行矩阵即获得 19 测试 + 构建回归网）；P1-2（student-h5 阈值分步提升——核心端门禁收敛，与板块07 修复联动）；P1-3（Playwright 死配置清理——消除误跑误导）。
- **Worth exploring**：P1-4（k6 基线化——先登记运行方式，低成本）。
- **Speculative**：P2-1（git-lfs 引入或资产瘦身，收益主要在仓库体验，非功能）。

## 5. 设计一致性核对

| 冻结决策 | 实现核对 | 结论 |
|---|---|---|
| TEST-005：覆盖率门禁（CI 强制） | ci.yml:114-150（整体 45% / 核心 service 60% + 报告缺失即失败）；与 design/05:31 台账一致（80% 为目标值非门禁） | ✅ 一致 |
| ARCH-009 E-1：pytest 入 CI | ci.yml:182-222（matrix 双服务 + pip 缓存 + 失败阻断合并） | ✅ 一致 |
| ARCH-009 E-2：teacher-web 覆盖率阈值 80 | vitest.config.js:42-47（lines 80 / branches 75 / functions 60 / statements 80，实测 89.99） | ✅ 一致 |
| ARCH-009 E-5：模型投放自动化 + manifest 校验 | prepare-models.yml（--verify 门禁 fail-closed） | ✅ 一致 |
| DA-04（2026-08-08）：CI 不跑 Playwright E2E | playwright.config.ts:8-9 注释登记决策 | ✅ 决策一致（残留配置见 P1-3） |
| ARCH-009 E-5 附带：parent-h5 补 oxlint | ci.yml:343-344（三端 `npm run lint --if-present`） | ✅ 一致 |
| AUD-052：CI 最小权限 | ci.yml:29-31（contents: read） | ✅ 一致 |
| G1/G2/G3/G4：CI 缓存与触发优化 | ci.yml:10-27（文档变更不触发）、:83-89（maven 缓存单点）、:202-210（pip 缓存）、:45-70（DB 就绪探测 5s） | ✅ 一致 |

## 6. 修复建议

- **P0**：无。
- **P1 按收益排序**：①P1-1 admin-web 入 CI 矩阵（配置零改动、一行矩阵，建议进入集中修复）；②P1-2 student-h5 阈值分步提升（与板块07 修复项同批，风险低）；③P1-3 Playwright 死配置清理（<10 行删除 + 注释）；④P1-4 k6 运行方式登记（可选）。
- **P2**：P2-1 可选。
- **汇总引用**：P1-1 是板块08 P1-1/P1-2（admin-web 端点治理与鉴权处理）回归保护的先决条件，建议汇总时合并为一个专题；P1-2 与板块04 P1-8（对话域变更热点零测试）同属"核心域测试加固"方向。
