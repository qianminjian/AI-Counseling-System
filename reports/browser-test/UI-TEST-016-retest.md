# UI-TEST-016 四端遍历修复复测报告（第 2 轮回归）

> 复测日期：2026-08-12 | 修复版本：953f3a2 / f72ecc2 / 05c1fe6 / c21606b / 2152c83
> 部署：backend+student+teacher 2m37s（冒烟 32/32）→ admin-web 手动同步 → backend（token 修复）2m10s → backend（401 语义）2m0s
> 执行：Browser Agent 复测 ×3 批次 + curl 接口验证

---

## 一、复测结论总览（11 项修复全部 VERIFIED）

| # | 缺陷 | 级别 | 复测结论 | 关键证据 |
|---|------|------|----------|----------|
| 1 | BUG-S-08-1 重复打卡 500 | P1 | ✅ VERIFIED | UI 拦截重复打卡；API 幂等 200 覆盖更新（同 diaryId），不再 500 |
| 2 | BUG-S-08-2 打卡失败文案误导 | P3 | ✅ VERIFIED | 全程未出现「打卡没成功，请检查网络」 |
| 3 | BUG-T-RC-01 班主任全功能 403 | P1 | ✅ VERIFIED | head_teacher 登录 200，dashboard/students/profile 全 200；档案响应体裁剪（无风险字段，服务端裁剪） |
| 4 | BUG-T-RC-02 403 误报服务不可达 | P3 | ✅ VERIFIED | 403 显式转 ApiError，不再误报「后端服务暂不可达」 |
| 5 | BUG-T-09-01 设备列表 500 | P1 | ✅ VERIFIED | 有效 ID 200 空列表；非法 ID 400 明确提示 |
| 6 | BUG-T-06-01 通知徽标不即时更新 | P2 | ✅ VERIFIED | 标记已读后徽标 4→3 即时更新（无刷新） |
| 7 | BUG-A-MODAL-01 Modal 关闭动画卡死 | P1 | ✅ VERIFIED | 4 处弹窗 7 次开-关循环全部立即关闭（降级矩阵/告警/二维码/配置） |
| 8 | BUG-A-04-01 创建提示词版本 500 | P1 | ✅ VERIFIED | 创建 200 draft → submit 200 pending_review |
| 9 | BUG-A-12-01/02/04 租户域接口 | P1 | ✅ VERIFIED | health/provision/suspend/resume 全 200；DEV 已恢复 active，TRIAL 未触碰 |
| 10 | BUG-A-02-01 租户状态恒显停用 | P2 | ✅ VERIFIED | 3/3 active 租户显示绿色「启用」Tag |
| 11 | BUG-A-TOKEN-01 过期 token 落 500 | P2 | ✅ VERIFIED | 无 token/无效 token → 401 code 20001（触发前端刷新链）；已认证无权限 → 403 code 20002 |

## 二、复测中发现并顺带修复的问题

### BUG-A-TOKEN-01 [P2] 过期/无效 token 解析失败落 500
- 发现：复测时过期 token 请求教师端/管理端接口返回 500，前端误报「后端服务暂不可达」
- 根因：JwtAuthenticationFilter.parseOnce 对过期 token 抛 JwtException 未被捕获 → 过滤器异常冒泡 → 兜底 500
- 修复（c21606b）：解析失败不建认证、继续放行（安全链统一 401）；补回归测试
- 语义收口（2152c83）：SecurityConfig 补 authenticationEntryPoint（未认证→401 code 20001）+ accessDeniedHandler（无权限→403 code 20002）
- 验证：curl 实测无 token/随机 token → 401 `{"code":20001,"message":"未认证或登录已过期"}`

## 三、遗留待办（非本轮 P1 范围，登记跟踪）

| 项 | 级别 | 说明 |
|----|------|------|
| BUG-T-04-01 escalated 会话摘要未生成 | P2 | 红色风险会话（08-09）摘要 3 天未生成，需后端异步任务排查 |
| BUG-T-03-01 WS 首连偶发失败 | P2 | 有 5s 重连兜底，可接受，排期优化 |
| OBS-P-03-01 家长周报缺区块 | P2 | 缺日期范围/风险等级/AI 建议，需设计确认 |
| BUG-A-03-01 配置变更历史 UI 缺失 | P3 | 数据层留痕存在，UI 待补 |
| P3 各项（S-04-01/S-02-01/T-06-02/03/T-04-02/03/A-04-02 等） | P3 | 排期优化 |
| admin-web 未纳入 deploy.sh 组件体系 | 改进项 | 本轮手动构建+rsync 部署；建议后续给 deploy.sh 增加 admin 组件（组件映射+构建块+nginx 校验） |

## 四、复测执行统计

- Browser Agent 批次：3（学生端打卡 / 教师端+管理端 / 管理端 Modal+状态）
- 截图工具全程故障（browser-use/chrome-devtools 超时），证据以 a11y 快照+DOM+网络请求为主
- 接口验证：curl 401/403/200 语义实测

---

_复测完成：11 项修复全部 VERIFIED ✅ | 本轮 P1 清零 | 遗留 P2×3、P3×9 登记跟踪_
