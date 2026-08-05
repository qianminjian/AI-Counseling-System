# 70 后端代码质量清理（ARCH-010）方案与 SPEC

> 关联任务：ARCH-010（深度审计 P2-2/3/4/5 + B-4 + OVD-2/4 回填，登记 TASK-TRACKER §二十八）
> 状态：📝 方案定稿 → 待实施（P2 级，可排 ARCH-003 之后）
> 依据：深度审计 2026-08-05（P2-2 手写 JSON 无转义 / P2-3 魔法数散落 / P2-4 Redis key 无租户前缀 / P2-5 异常静默面；B-4 暖场链路绕过版本路由 + 模板 key 双命名；OVD-2 模板双加载路径 / OVD-4 closeSession 双接口）
> 词汇：单一事实源 / 可观测性 / 删除测试——见 [13 领域词汇表](../13_领域词汇表.md)

---

## 1. 背景与问题

| ID | 问题 | 证据 | 影响 |
|----|------|------|------|
| P2-2 | `StudentProfileService.toJson` 手写 JSON **引号不转义**（值含引号即非法 JSON）+ 三风格 JSON 并存 + 每轮对话 `new ObjectMapper` | `StudentProfileService.java` L500-520/L527 | 真实 bug 温床：特殊字符画像即 500/解析失败 |
| P2-3 | 风险评分魔法数 85/60/35/30 + 权重 `10,0,0,0,0.8` 散落三处 | ConversationRiskProcessor 等 | 已并入 **ARCH-003**（doing/63 收编到 RiskKeywordRegistry），本任务不重复 |
| P2-4 | Redis 会话 key 无租户前缀，防劫持靠每处调用点自觉调 `isSessionOwner`（注释自认） | Redis 会话存储层 | 多租户越权面依赖纪律而非结构 |
| P2-5 | LLM 摘要失败 → 画像/记忆/评估三路同时静默，无 metrics 计数 | 摘要消费链路 | 失败不可观测，劣质摘要无告警 |
| B-4 | 暖场链路 `chatProactive` 直渲 classpath，不走 `PromptVersionService` DB 优先/A-B 路由（同模板两条加载路径）+ 模板 key 双命名（`SYS-001` vs `SYS_001`） | `AiChatServiceImpl.java` L117-122；`TemplateMatrixRegistry.java` L59 | A/B 灰度数据源不全、版本管理有盲区 |
| OVD-2 | 模板双加载路径收敛一半（主链路已收，暖场链未收） | 同 B-4 | 同 B-4 |
| OVD-4 | `closeSession` 双接口 fallback（前端替后端版本兼容买单） | 会话关闭接口层 | 旧路径应设 TTL 后删除 |

## 2. 目标与非目标

**目标**：
- JSON 序列化统一（单一 ObjectMapper、注入复用、消除手写拼接）
- Redis 会话 key 加租户前缀（结构防越权 + 兼容迁移）
- 摘要/画像/记忆/评估失败可观测（metrics 计数 + 告警）
- 暖场链路接入 Prompt 版本路由 + 模板 key 命名单一源
- closeSession 旧路径 TTL 后下线

**非目标**：
- 风险评分魔法数收编（→ **ARCH-003** doing/63，本任务仅引用）
- Prompt 模板内容调整（只收敛加载路径与命名）
- Redis 数据结构变更（仅 key 前缀，value 不变）

## 3. 设计方案

### 3.1 JSON 序列化统一（P2-2）

- `StudentProfileService.toJson` 手写拼接（L500-520）替换为注入的 `ObjectMapper`（L527 每轮 `new` 一并消除）
- 全后端排查三风格 JSON（手写拼接 / 手动 map / 不同配置 ObjectMapper）→ 统一为项目唯一 ObjectMapper bean（counseling-common 提供，各模块注入）
- 序列化行为回归：既有字段序列化结果不变（配置对齐后全量回归兜底）

### 3.2 Redis 会话 key 租户前缀（P2-4）

- key 格式 `session:{tenantId}:{sessionId}`（现无租户段）
- 兼容迁移：读取时先查新格式，未命中回查旧格式并双写迁移；TTL 自然过期清理旧键
- `isSessionOwner` 保留为纵深防御（结构前缀 + 调用点检查双保险）

### 3.3 异常可观测（P2-5）

- LLM 摘要/画像回注/记忆写入/质量评估四路失败统一打点：`counter`（失败次数 + 类型 tag）+ 日志结构化（含 sessionId、阶段）
- 告警阈值：摘要失败率持续 >5% 触发（grafana 已有 alert 体系接入）
- 静默 catch 全量排查：`catch {}` 空块必须有注释+打点（审计面）

### 3.4 模板路由与命名统一（B-4/OVD-2）

- `chatProactive`（暖场）渲染改走 `PromptVersionService` 版本路由（DB 优先 + A/B 灰度），与主链路同一加载路径
- 模板 key 双命名（`SYS-001` vs `SYS_001`）→ 以 `PromptVersionService` 映射表为单一源，`TemplateMatrixRegistry` 命名对齐
- doing/61 D-2 决策项落地：C1 拆分含版本路由对齐（本任务独立实施，不捆绑上帝类拆分）

### 3.5 closeSession 旧路径下线（OVD-4）

- 旧关闭接口保留但设 TTL（如 90 天）→ 到期下线；前端切换新接口（现有 fallback 逻辑删除）
- 灰度：日志观测旧路径调用量归零后下线

## 4. SPEC

```
JSON：ObjectMapper 单例 bean（counseling-common）；toJson 改注入序列化；三风格归一并回归
Redis：key = session:{tenantId}:{sessionId}；读回查旧格式双写迁移；TTL 清理
可观测：四路失败 counter + 结构化日志 + 失败率告警阈值 5%；空 catch 清零
模板：chatProactive 走 PromptVersionService 路由；key 命名以映射表为单一源（SYS_001 统一）
closeSession：旧路径 TTL 90 天 → 调用量归零后下线；前端 fallback 删除
```

## 5. 验收标准（EARS 风格）

- 当 JSON 统一后，后端必须不存在手写 JSON 拼接（grep 断言），序列化结果必须与既有字段完全一致（回归用例）
- 当 Redis key 加前缀后，新会话 key 必须含租户段，旧会话必须可读可迁移，TTL 后无残留
- 当可观测接线后，摘要/画像/记忆/评估任一失败必须产生 metrics 计数与结构化日志（断言测试）
- 当模板路由统一后，`chatProactive` 必须走 `PromptVersionService`，模板 key 必须单一名（grep 无 `SYS-001` 残留）
- 当 closeSession 收敛后，前端必须仅调用新接口，旧接口调用量为零且已下线
- 当全量回归运行时，后端测试必须与基线一致（1529 用例全绿）

## 6. 风险与回滚

- **风险**：中——JSON 序列化统一与 Redis key 迁移涉及存量数据读取路径；模板路由切换涉及对话行为（暖场文案可能随版本路由变化）
- **红线**：Redis key 迁移与 closeSession 下线不涉 schema 变更，但生产切换须发布窗口（部署层操作按既有流程）
- **回滚**：JSON/模板/closeSession 逐文件 revert；Redis key 双写迁移天然可回退（新格式失败回读旧格式）

## 7. 关联与落点

- 关联任务：ARCH-003（doing/63，魔法数收编，本任务引用其常量）、ARCH-004（doing/64，僵尸 API 清理并行）
- 关联设计：design/12 技术架构（Redis 会话）、design/02 Prompt 体系（模板 key 权威源）、design/44（版本路由）
- 词汇表：[13 领域词汇表](../13_领域词汇表.md)
- 登记：TASK-TRACKER §二十八 ARCH-010
