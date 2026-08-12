# 审计报告 09 - 家长端（parent-h5）与 shared 共享层

- **审计时间**：2026-08-12
- **审计范围**：`frontend/parent-h5`（Taro 平台抽象，非测试文件 27 个：platform 层 request/storage/redirect + services 层 endpoints/request/index/device/toc + hooks/pages/components）+ `frontend/shared`（15 文件：auth-transport 5 实现 + 5 测试 + audio-utils/emotionMeta/replyEmotion/theme）
- **方法**：git log 热点分析（doing/85 TOC 系列）+ 全量读取核心文件（services 5 个 + platform/request + shared refresh/sessionExpired/tokenStorage）+ 验证码回显登记核查（grep doing/BEACON/TASK-TRACKER）+ 测试盘点（16 文件）+ 冻结决策核对（只读，未改动任何文件）

## 1. 板块概况

**parent-h5**（Taro + Vue）：三端中唯一的跨平台端，平台差异收敛在 `platform/` 适配层（`createPlatformRequest` 注入 fetchImpl/locationRedirect，小程序端仅换实现，页面层零改动，platform/request.ts:10）。业务服务层 5 文件清晰分工：`endpoints.ts` 端点单一事实源（R-001，FRONTEND_ENDPOINTS + apiContract.test.ts 契约断言）、`request.ts` 请求工厂收敛（R-003，parent_/toc_ 双身份单例）、`index.ts` 家长业务 API、`device.ts` 设备域（CFG-001/004 + AD-008 家庭登录上下文适配）、`toc.ts` 家庭账号与孩子档案（TOC-001/002/007）。

**shared**：认证传输底座质量极高——`refresh.ts`/`sessionExpired.ts`/`apiError.ts`/`tokenStorage.ts`/`authFetch.ts` 五个实现文件**各有配对测试（10 文件 100% 配对）**，是 student/teacher/parent 三端认证语义的唯一来源（parent-h5 平台层全量 import shared，platform/request.ts:12-15，见设计一致性核对）。

**测试**：parent-h5 16 文件，含 `tocFlow.e2e.test.ts`（TOC 注册→建档→设备绑定全链路）与 `apiContract.test.ts`（端点清单契约直校验）。

## 2. 热点与风险初判

- **doing/85 TOC 系列**（7 个 commit，2026-08 上旬）：TOC-001/002 家庭账号与孩子档案 → TOC-003 家庭设备绑定（联动 doing/84 CFG-010）→ TOC-006 远程管理偏好 → TOC-007 隐私控制（X-Confirm 删除）。
- **doing/73**（AC-3/AC-5/T1/T3）：platform 适配层 + services 收敛批次（F-04 解包、R-003 工厂收敛、DC-005 消费 shared）。
- **风险初判**：①`TocSendCodeResult.code` 验证码回显无上线门禁登记（见 P1-1）；②R-001 端点清单在 parent-h5 为元组数组形态，与 teacher/student 对象表形态不统一（见 P2-1）。

## 3. 发现清单

### P0（架构级）
**未发现**。平台抽象边界清晰（platform 适配 + services 业务分层，无跨层绕过）；shared 认证传输单一来源、三端收敛无复制（对比 admin-web 的有意例外）；TOC 设备域经 AD-008 收进 device.ts 单一模块，未形成跨文件蜘蛛网。

### P1（模块级）

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试判断 |
|---|---|---|---|---|---|
| P1-1 | parent-h5/src/services/toc.ts:30-35（`TocSendCodeResult.code`） | **认证验证码回显无上线门禁登记**：send-toc-code 响应契约含 `code: string`（注释明示"演示环境回显验证码（生产接入短信通道后移除）"），但 grep design/doing、BEACON、TASK-TRACKER 均无该演示行为的登记/上线检查项。家长端注册/登录链路依赖验证码回显即意味着认证形同虚设（任意手机号可注册登录 → TOC-002 档案 CRUD、TOC-007 隐私删除），属心理辅导系统红线域；注释式备忘无触发机制，极易随短信通道接入被遗漏 | ①上线门禁登记该演示行为（接入短信通道即移除回显）；②前端类型层面 `code` 标记 `@deprecated` 并加 TODO 门禁锚点；③汇总时联动后端核对 send-code 实现（板块01/03 范围：是否真回显、有无频率/防爆破限制） | 一致性：认证语义与合规基线对齐，消除"演示行为无管控"缺口 | 保留：tocFlow.e2e.test.ts 依赖回显的用例需随短信接入同步改造（新增 mock 短信通道断言） |

### P2（局部）

| 编号 | 位置 | 问题描述 | 建议方案 |
|---|---|---|---|
| P2-1 | parent-h5/src/services/endpoints.ts:10（`Array<[path, method]>`） | R-001 端点清单形态跨端不统一：parent-h5 为元组数组（无 key，只能全量断言，无法按名消费），student-h5/teacher-web 为对象常量表（key → path，FA-15 callEndpoint 按 key 消费）。doing/73 T3 迁移时"内容原样"保留历史形态 | 对齐对象表形态（低成本纯重构，契约断言不变；或短期先在注释中登记形态差异决策） |

## 4. 改进候选排序

- **Strong**：P1-1 验证码回显门禁登记——红线域合规项，改动量极小（文档 + 类型标注），但需要在汇总报告中与后端核对联动。
- **Worth exploring**：无（parent-h5 治理质量已高，R-003/R-001/DC-005 均落地）。
- **Speculative**：P2-1 端点形态对齐（三端治理统一的收尾项，可并入板块08 P1-1 的"三端端点治理收敛"专题一并做）。

## 5. 设计一致性核对

| 冻结决策 | 实现核对 | 结论 |
|---|---|---|
| doing/94 R-003：请求工厂收敛单一事实源 | services/request.ts:1-28（parentRequest/tocRequest/tocTokens 单例，各服务文件只消费本模块导出） | ✅ 一致 |
| doing/94 R-001 / ARCH-008 F-7：端点单一事实源 | endpoints.ts 全清单 + apiContract.test.ts 契约断言（"测试断言源码全部 API 路径 ⊆ 本清单"） | ✅ 一致（形态差异见 P2-1） |
| DC-005：认证传输三端收敛（shared/auth-transport） | parent-h5 platform/request.ts:12-15 全量 import shared（refreshTokens/handleSessionExpired/toApiError/TokenStorage），Taro 适配仅换 locationRedirect/fetchImpl 注入 | ✅ 一致（Taro 平台层为适配抽象非逻辑复制，与 admin-web 有意例外形成对比） |
| doing/73 T1：P1 小程序端仅替换依赖注入 | platform/request.ts:10、37-46（PlatformRequestDeps 注入 fetchImpl/onSessionExpired） | ✅ 一致 |
| doing/85 TOC 系列（001/002/003/006/007） | toc.ts + device.ts 全部落地；TOC-007 隐私删除带 X-Confirm 二次确认（toc.ts:97-101） | ✅ 一致 |
| doing/84 CFG-010：设备域联动 | device.ts 绑定码"明文仅返回一次供设备语音播报"（:27-32）——绑定码回显为设计使然，**与 P1-1 认证验证码性质不同，不视为问题** | ✅ 一致 |
| F-04：请求解包收敛 | platform/request.ts:88-96（success 信封已抛错 → data 直接解包，与 student/teacher 语义一致） | ✅ 一致 |

## 6. 修复建议

- **P0**：无。
- **P1**：P1-1 验证码回显门禁登记——建议进入集中修复（低成本、红线域），并作为汇总报告中"后端 send-code 实现核对"的联动触发项。
- **P2**：P2-1 端点形态对齐可并入板块08 P1-1 的三端端点治理专题（同批执行成本最低）。
- **汇总引用**：P1-1 与板块10 P1-1（错误细节泄漏）同属"红线域演示/错误信息管控"主题；P2-1 与板块08 P1-1（admin-web 端点治理）同属 R-001 三端收敛收尾。
