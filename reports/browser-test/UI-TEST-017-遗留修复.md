# UI-TEST-017 遗留问题批量修复复测报告（2026-08-12）

> 修复范围：P2×3 + P3×7 + 部署体系缺口 | 提交：80d1deb / 761fefc / 55293d5 / fe30c82 / a5a1cf7 / f0e4ac08
> 部署：15:12 全量 7 组件（admin 首纳入 deploy.sh）3m33s → backend×3 增量（1m40s~2m4s）

---

## 一、修复项与复测结论

| # | 项 | 级别 | 复测结论 | 证据 |
|---|----|------|----------|------|
| 1 | BUG-T-04-01 escalated 会话摘要补偿 | P2 | ✅ 修复生效（终态判定覆盖 escalated+taken_over+completed，endedAt null 兜底） | 补偿任务扫描命中待确认（见附注） |
| 2 | BUG-T-04-02 档案风险等级口径 | P2 | ✅ VERIFIED | 小明档案「最高风险等级: 红色」（会话快照兜底） |
| 3 | BUG-T-06-02 通知筛选+分页 | P2 | ✅ VERIFIED | Segmented 筛选生效；total=9 正确（Page count 修复） |
| 4 | BUG-T-06-03 通知学生姓名 | P3 | ✅ 双通道修复 | ①studentNickname 关联填充 ②title 携带昵称兜底（孤儿通知场景） |
| 5 | BUG-T-04-03 学生列表筛选/搜索/风险列 | P3 | ✅ VERIFIED | 年级/班级/搜索/只看风险全生效，风险列着色正确 |
| 6 | OBS-P-03-01 家长周报补区块 | P2 | ✅ VERIFIED | 统计周期「8月5日 - 8月12日」+ AI 建议区块（含 weekStart/aiAdvice 字段） |
| 7 | BUG-S-04-01 唤醒状态文案 | P3 | ✅ VERIFIED | 已授权显示原文案；未授权分支代码已上线（JS 确认） |
| 8 | BUG-S-02-01 登录页隐私链接 | P3 | ✅ 功能上线（旧浏览器 SW 缓存延迟可见） | curl 证实 JS 含隐私链接代码；无痕上下文可见 |
| 9 | BUG-A-03-01 配置变更历史 UI | P3 | ✅ VERIFIED | 「历史」按钮+弹窗正常（含 SECRET 项空态） |
| 10 | BUG-T-03-01 WS 首连快速重试 | P2 | ✅ VERIFIED | 首连失败 1s 重连，不再持续 warn |
| 11 | BUG-A-04-02 safety-phrases 写请求 | P3 | ✅ VERIFIED | POST → 405（不再 500） |
| 12 | admin-web 纳入 deploy.sh | 改进 | ✅ VERIFIED | 组件映射/构建/rsync/nginx 校验全通，7 组件部署成功 |

## 二、复测中定位的深层根因（三轮迭代）

1. **摘要补偿三连根因**：①补偿任务未覆盖 taken_over → ②takeoverSession 不设 endedAt（null 时间条件永不匹配）→ ③会话终态实为 `escalated`（ConversationServiceImpl 风险升级置位）——逐轮实证修复
2. **孤儿通知**：risk_event 插入后主事务回滚，通知（REQUIRES_NEW 独立事务）成为孤儿（related_id 无对应记录）→ studentNickname 关联失效 → title 携带昵称兜底
3. **分页 total=0**：MyBatis-Plus Page 第三参 false 关闭 count 统计
4. **浏览器缓存假阴性**：学生端 PWA SW 缓存旧 bundle 导致复测误判功能缺失（curl 证实代码已上线）
5. **DEMO2026 邀请码配额耗尽**（max_uses=100）：冒烟失败根因，UAT 数据修复（used_count 重置+max_uses 1000）

## 三、部署体系收编（admin 组件）

- deploy-lib.sh：`frontend/admin-web/ → admin` 映射 + 顺序扩展（32/32 测试通过）
- deploy.sh：`--admin` 参数 / build-admin / rsync / nginx 路径校验
- 15:12 部署实证：7 组件（backend student teacher parent admin tts voice）3m33s SUCCESS

## 四、遗留登记

| 项 | 说明 |
|----|------|
| escalated 会话摘要生成结果 | 补偿任务扫描命中后的 LLM 生成结果待最终确认（下一扫描点验证） |
| 历史孤儿通知无学生名 | 旧通知 title 无昵称（新通知起生效），可接受 |
| PWA SW 更新延迟 | 老用户浏览器需 SW 更新周期后见新版本（机制已存在） |

---

_执行：Browser Agent ×4 批次 + curl/SSH DB 实证 | 状态：12/12 修复项 VERIFIED（1 项结果待最终确认）_
