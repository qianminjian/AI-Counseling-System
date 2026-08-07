# 62 - 研究数据脱敏导出（DATA-005）（冻结专题）

> 文档状态：**冻结（Frozen）** | 创建：2026-07-28 | 冻结：2026-07-28 | 关联：TASK-TRACKER §数据智能（DATA-005）
>
> 定位：远期 P2 任务（研究数据脱敏导出，IRB 兼容格式，支持学术合作）方案先行冻结存档。方案由架构审查（improve-codebase-architecture）定稿，含 4 个深化候选（导出管线/伪名化模块/加密接缝/保留豁免）与主 Seam（ExportRequest→ExportResult）；任务本身不实施。
>
> 解冻触发：启动学术合作 / IRB 合规流程前，由项目负责人解冻并登记台账后按 TDD 实施。
>
> 依据：design/07（商业化合规：PIPL/删除权/保留期）、design/02（数据库：pseudonym/metadata_redacted 设计）、design/his/06（历史数据库设计）、架构审查报告（tmp/architecture-review-20260728.html）
>
> 审查范围：TeacherController 导出接口、DataAnalyticsService/Controller、FieldEncryptionService、PiiDesensitizer、DataRetentionCleanupJob、schema 01_schema.sql

---

## 1. 背景与问题

1. **导出能力是浅模块**：4 个导出接口（会话导出 HTML、周报 HTML、预警 CSV、学生列表 CSV）100% 内联在 TeacherController（513 行、10 个构造依赖），手拼 HTML/CSV、手写转义、情绪中文映射全堆一处；每个导出方法都是 interface 与 implementation 等高的浅模块，新增格式（如 IRB 数据集）无处安放。
2. **个人级数据泄漏跨过 seam 且零审计**：`/api/v1/analytics/*` 三个接口（schoolReport/interventionEffect/growthTrajectory）中后两者明文输出 studentUserId、里程碑、风险时间线等个人级数据，但完全无审计日志；而导出类接口（EXPORT_ALERTS/EXPORT_WEEKLY_REPORT/EXPORT_SESSION）已有审计先例，审计面不一致。
3. **现无 IRB 级匿名化能力**：现有 `pseudonym` 是确定性、跨系统可联接的显示名（企微 OAuth 以其反向匹配教师账号），不满足伪匿名化不可逆要求；`PiiDesensitizer` 仅做部分掩码（`138****78`/`某同学`），保留可识别片段，二者都不能直接用于研究导出。
4. **库内存储的是原文而非脱敏文本**：`persistStudentMessageSummary` 落库的是未脱敏的原文截断（contentSummary 解密后仍含 PII），PII 掩码只保护了 LLM 上下文——IRB 导出必须在导出管线上再做脱敏，不能依赖入库侧。
5. **schema 与实体漂移**：`display_name_enc`/`mobile_enc`/`email_enc`/`external_subject_id_hash`/`student_no_hash` 五个加密/哈希列自 V2 建表后从未被读写（僵尸列）；`metadata_redacted`（脱敏上下文）仅存在于历史设计文档，从未落地。
6. **加密接线分散且不一致**：contentSummary/sessionSummary/teacher_notes 的加解密散落 MessageSummaryService、TeacherService、TeacherController 三个调用方；同类数据（干预/回访备注）resolveAlert/completeFollowUp 明文落库、addNote/transferAlert 加密——导出功能必须记住「在哪解密」，漏一处即泄漏。
7. **保留策略与 IRB 直接冲突**：DataRetentionCleanupJob 按 30/365 天物理删除数据，无豁免机制；研究数据需长期保存（学术可复现），导出后会被定期清理器无差别销毁。

## 2. 目标

为 DATA-005 建立**研究数据脱敏导出的深化架构**（KISS、零新增重型依赖），使 IRB 兼容导出成为导出管线的一个 renderer：

| 候选 | 内容 | 强度 |
|------|------|------|
| C1 导出管线 | 4 个内联导出 + analytics 个人级输出收敛为一个深模块（提取→伪名化→渲染→审计） | Strong |
| C2 伪名化模块 | pseudonym 从确定性实名变为单向不可逆映射；密钥分离；跨数据集一致假名 | Strong |
| C3 加密接缝 | 三处手动加解密收敛到仓储层，明文漏网点收口；脱敏投影列缓解加密×SQL 聚合矛盾 | Worth exploring |
| C4 保留豁免 | 导出即注册数据集（范围/期限/豁免标记），清理任务识别豁免 | Worth exploring |

> 依赖关系：C2/C4 是 C1 的内部件；C3 独立但与 C1 有交集（导出管线经 C3 接缝读取明文再伪名化）。建议实施顺序 C1 → C2 → C4 → C3。

## 3. 现状盘点

### 3.1 已有资产（可复用）

- **字段级加密**：FieldEncryptionService（AES-256-GCM + 密钥版本化，生产 fail-fast），加密点：contentSummary、sessionSummary、teacher_notes.content（部分）。
- **文本掩码**：PiiDesensitizer（手机/身份证/邮箱/姓名/地址正则掩码，21 用例），可作为导出前文本清洗层，但需扩展为 IRB 级。
- **审计先例**：AuditLogService（@Async，IP 哈希 + UA），EXPORT_ALERTS/EXPORT_WEEKLY_REPORT/EXPORT_SESSION 三动作。
- **聚合统计**：DataAnalyticsService.schoolReport 模式（Map 组装 + DataAnalyticsController 透传），DATA-001~004 已落地。
- **保留清理**：DataRetentionCleanupJob（30/365 天物理删除）。

### 3.2 敏感数据分布（导出数据源清单）

| 数据 | 存储 | 敏感度 | 加密 | 对 IRB 导出 |
|------|------|--------|------|------------|
| 学生消息摘要 contentSummary | message_summaries | 高（原文截断） | ✅ 加密 | 核心研究语料，需解密→伪名化 |
| 会话摘要 sessionSummary | counseling_sessions | 高 | ✅ 加密 | 核心研究语料 |
| 风险事件 risk_events | 表 | 高（resolution_note/follow_up_note 明文含干预细节） | ❌ | 需伪名化 + 特殊类别处理 |
| 长期记忆 long_term_memories.content | 表 | 高（跨会话事件细节） | ❌ 明文 | 需脱敏 |
| 情绪日记 emotion_diaries.note | 表 | 中 | ❌ 明文 | 需脱敏 |
| 满意度评价 satisfaction_comment | 表 | 中 | ❌ 明文 | 需脱敏 |
| 教师备注 teacher_notes.content | 表 | 高 | ⚠️ 一半 | 需脱敏 |
| 质量评审 quality_scores.raw_response | 表 | 中 | ❌ 明文 | 需脱敏 |
| 声纹 embedding | voiceprint_embeddings | 极高（生物特征） | - | 默认排除 |
| 学生身份 users | 表 | 高 | 部分僵尸列 | pseudonym 不可用 |

### 3.3 摩擦点清单（审查产出）

1. 导出内联 Controller、无模板引擎/PDF 库/文件服务（Ctrl+P 依赖浏览器）。
2. 加密接线分散、同类数据明文/密文混用。
3. 密文列无法 DB 层聚合，内容主题聚合只能全量解密到内存。
4. pseudonym 确定性可联接；PiiDesensitizer 部分掩码。
5. schema 僵尸列 5 个 + metadata_redacted 纸面化。
6. analytics 个人级输出零审计。
7. 保留清理与 IRB 长期保存冲突。

## 4. 方案设计（Seam 优先）

### 4.1 测试 Seam（原则：最高、最少，理想一个）

**主 Seam：导出管线接口 `ExportRequest(scope, format) → ExportResult`**

- 所有导出（会话/周报/预警/学生/IRB 数据集）经同一接口进入；
- 测试在此打：断言导出内容（字符串/结构化），而非 mock servlet/PrintWriter；
- Controller 只剩鉴权 + 一行委托 + 审计触发（审计移入管线内部）。

```
导出管线（深模块）:
  ExportRequest(scope, format) → 提取(解密) → 伪名化(C2) → 渲染(HTML/CSV/IRB) → 审计(范围+计数) → ExportResult
```

- **次级 Seam 1（C2）**：伪名化模块接口 `pseudonymize(record)/restore(key)`，仅导出管线调用；单元测试独立打此接口。
- **次级 Seam 2（C4）**：数据集注册表接口（注册/撤销/查询豁免），DataRetentionCleanupJob 与导出管线各持一端。
- **现有 Seam 复用**：FieldEncryptionService（C3 收敛后为仓储内部件）、AuditLogService（管线调用）。

> ⚠️ Seam 待确认：主 Seam 取「导出管线接口」而非「Controller HTTP 接口」——理由：测试面更小、格式渲染可离线测试；若偏好 HTTP 级测试（含鉴权），则主 Seam 上移到 Controller，代价是每个 renderer 都要过 HTTP。

### 4.2 C1 导出管线深化

- **接口**：`ExportRequest(scope, format, options)`；`ExportResult(content, contentType, filename, stats)`；scope 支持 `session(s)/range/student/tenant`，format 支持 `HTML/CSV/IRB_DATASET`（未来扩展）。
- **内部**：提取（查询 + 解密，经 C3 接缝）→ 伪名化（C2）→ 渲染（模板化 HTML/CSV 转义收敛一处）→ 审计（动作名 + 范围 + 行数）。
- **吸收**：TeacherController 4 个内联导出方法、情绪中文映射、CSV 转义函数。
- **收口泄漏**：analytics 三接口个人级输出并入管线审计（interventionEffect/growthTrajectory 增加 EXPORT/QUERY 审计）；schoolReport 保持聚合匿名。
- **不动**：现有导出 URL/文件格式（教师端无感知），仅实现层收敛。

### 4.3 C2 伪名化模块

- **接口**：`pseudonymize(record) → record'`（结构化替换）；`restore(key)`（仅授权导出侧）；`isPseudonymized(value)`。
- **语义**：盐化单向哈希（研究钥 ↔ 运维钥分离）；同一学生跨数据集保持同一假名（哈希一致性）；年龄/年级/班级等准标识符做 k-anonymity 分组泛化（班级→年级段、精确日期→周粒度）；自由文本过 PiiDesensitizer 扩展层（掩码升级为替换）。
- **与现状解耦**：显示用 pseudonym 字段不动（教师端/企微依赖），研究假名独立生成，两者无映射关系。
- **定案僵尸列**：display_name_enc/mobile_enc/email_enc/external_subject_id_hash/student_no_hash 五列——实施时二选一：① 启动伪名化后用于研究假名键的持久化存储；② 确认无使用者后 V 迁移删除（删除属数据迁移红线，需项目负责人决策）。
- **metadata_redacted**：若保留该设计（风险事件脱敏上下文），在本模块落地为事件级脱敏快照字段；否则从历史文档标注废弃。

### 4.4 C3 字段加密接缝（Worth exploring）

- **目标**：加解密收敛为实体/仓储接缝——写即加密、读即解密，业务调用方无感知。
- **收口**：resolveAlert/completeFollowUp 明文落库改为加密（同类数据一致化）。
- **权衡点（需确认）**：① 改动面（628 存量测试回归）；② 加密×SQL 聚合矛盾——引入脱敏投影列（低敏派生值：情绪标签、风险等级、主题类别等已明文存在，仅对需文本聚合的新维度建投影列）或维持全量解密到内存（研究导出低频，可接受）。

### 4.5 C4 研究数据集生命周期

- **接口**：数据集注册表 `register(exportId, scope, retentionUntil, exempt)/revoke(exportId)/isExempt(dataRef)`。
- **语义**：导出 IRB 数据集时自动注册（豁免标记 + 期限）；DataRetentionCleanupJob 接口不变，内部查询注册表跳过豁免数据并审计留痕；撤销豁免即恢复清理（删除权 ≤15 工作日仍可满足）。
- **契约**：注册表数据与原始数据解耦（注册表只存范围引用 + 期限，不存数据副本）。

## 5. User Stories

1. 作为研究者，我希望导出一份含脱敏会话文本与风险事件的研究数据集，以便进行学术分析且不接触学生真实身份。
2. 作为研究者，我希望同一学生在多次导出的数据集中使用同一假名，以便纵向跟踪而不暴露身份。
3. 作为研究者，我希望数据集中的年龄/年级/班级被泛化到不可再识别个体的粒度，以满足 IRB 最低识别风险要求。
4. 作为研究者，我希望自由文本中的手机号、身份证、姓名、地址被不可逆替换（而非部分掩码），以便发布级安全。
5. 作为研究者，我希望研究数据集排除声纹等生物特征数据，以便遵守数据最小化。
6. 作为平台管理员，我希望导出操作全部留痕（谁、什么范围、多少条、何时），以便合规审计可追溯。
7. 作为平台管理员，我希望 analytics 个人级接口同样受审计，以便审计面无死角。
8. 作为平台管理员，我希望研究数据集注册保留豁免并在到期后自动恢复清理，以便删除权合规仍可兑现。
9. 作为教师，我希望现有会话导出/周报导出/CSV 导出的入口与格式不变，以便日常工作零学习成本。
10. 作为教师，我希望干预/回访备注在库内加密存储，以便同文件内不再明文密文混用。
11. 作为运维人员，我希望清理任务在遇到豁免数据时跳过并留痕，以便不误删研究数据也不影响常规清理。
12. 作为合规审计员，我希望能查询「某数据集何时导出、保留到何时、撤销后何时清理」，以便应对监管问询。
13. 作为平台运营，我希望导出管线的格式是插拔的，以便未来支持 SPSS/CSV 变体等新格式而不改动主链路。
14. 作为开发者，我希望导出内容可在单元测试中直接断言（不 mock servlet），以便低摩擦测试。
15. 作为学生监护人，我希望孩子数据被导出研究前有明确的同意/通知机制（衔接 design/22 告知同意），以便知情权不被架空。
16. 作为数据负责人，我希望伪名化密钥与运维密钥分离保管，以便密钥泄漏不导致数据集再识别。

## 6. Implementation Decisions

1. **模块归属**：C1 导出管线 + C4 数据集注册表落在 counseling-service（analytics/export 包），C2 伪名化模块落在 counseling-common 或 counseling-ai（供导出管线与审计共用；PiiDesensitizer 扩展留在 counseling-ai safety 包）；C3 接缝落 counseling-domain 仓储层。
2. **主 Seam**：`ExportRequest(scope, format, options) → ExportResult(content, contentType, filename, stats)`；Controller 只保留鉴权与委托。
3. **伪名化语义**：盐化单向哈希 + 研究钥/运维钥分离 + k-anonymity 泛化（班级→年级段、日期→周粒度）+ 自由文本替换式脱敏；显示用 pseudonym 不动。
4. **审计统一**：所有导出与 analytics 个人级访问统一记 EXPORT_*/ANALYTICS_* 动作，detail 含范围与行数（沿用 IMPORT_STUDENTS 的 JSON detail 先例）。
5. **保留豁免**：数据集注册表仅存引用（范围 + 期限 + 豁免标记），不存副本；清理任务识别豁免跳过并留痕。
6. **僵尸列定案**：5 个僵尸加密列实施时二选一（伪名化持久化 vs V 迁移删除），数据迁移红线需项目负责人决策。
7. **渲染**：HTML 模板化收敛（不引入新模板引擎，KISS；若 IRB 数据集要求严格 PDF，另议 OpenPDF/IText 依赖引入，属新依赖决策）。
8. **API 契约**：IRB 数据集导出端点 `GET/POST /api/v1/admin/research/export?scope=...&format=irb`（管理员/平台角色，沿用 AdminController 权限模型）；响应为文件流（复用前端 blob → a.click() 下载模式）。
9. **实施顺序**：C1 → C2 → C4 → C3（C1 立即可做且修复现存泄漏；C3 改动面大放最后）。

## 7. Testing Decisions

- **测试哲学**：只测外部行为——导出管线的输入（scope/format）与输出（内容、脱敏后断言、审计调用），不测内部渲染细节；伪名化只测接口语义（确定性、不可逆性、密钥轮换、跨数据集一致性）。
- **主 Seam 测试**：导出管线单测——构造内存数据（或 mock 仓储），断言：导出内容含假名不含真名、含脱敏文本不含原文 PII、审计动作与行数正确、空数据边界。
- **C2 测试**：映射确定性（同输入同假名）、不可逆性（无 restore 键不可还原）、盐轮换后旧假名失效、班级泛化到年级段、文本替换不残留可识别片段、零误伤反向用例（参照 PiiDesensitizerTest 21 用例风格）。
- **C3 测试**：接缝读写一致性（写加密→读解密）、明文漏网点收口断言（resolveAlert/completeFollowUp 后落库值为密文）、存量回归（628 单测全量）。
- **C4 测试**：注册→豁免→清理跳过→撤销→恢复清理全链路；清理任务既有测试扩展豁免分支（参照 SlaEscalationScanner 定时任务测试风格）。
- **集成测试**：ContractOpenApiIT 扩展 IRB 导出端点入 OpenAPI 断言（复用 TEST-006 L1 基座）；审计动作在 TeacherControllerFullTest 风格的 verify 断言中扩展。
- **先例**：DataAnalyticsServiceTest（Mockito + MyBatis TableInfo 初始化）、TeacherControllerFullTest（25 用例含审计 verify）、PiiDesensitizerTest（@Nested 分组 + 零误伤）。

## 8. Out of Scope

- 学术合作的合同/协议、IRB 审批流程本身（监管责任事项，AI 只提供信息不做决策）。
- 研究数据集的统计模型与分析方法（DATA-001 效应量计算之外的新统计能力）。
- 教师端/家长端 UI 改造（教师端导出入口不变；仅新增管理员侧研究导出入口）。
- 声纹/生物特征数据的任何导出（默认排除）。
- C3 加密接缝若确认改动面过大，可降级为「仅收口明文漏网点」的最小修复。
- 新 PDF 引擎引入（除非 IRB 格式硬性要求，另行决策）。

## 9. 进一步说明

1. **待决策事项**（实施前需项目负责人确认）：① 主 Seam 取导出管线接口 vs Controller HTTP 接口；② 僵尸列定案（伪名化持久化 vs V 迁移删除，后者属数据迁移红线）；③ 研究导出是否需要新的同意/通知机制（衔接 design/22 告知同意与 design/07 删除权口径）；④ C3 是否全量接缝化（改动面评估后）。
2. **与既有设计的关系**：pseudonym 现有语义（显示名）不变，研究假名独立；metadata_redacted 是否落地由 C2 定案；保留期设计（design/07：普通 180 天/高风险 365 天）与 DataRetentionCleanupJob 30/365 天口径需在 C4 实施时对齐。
3. **契约流转**：导出管线接口 →(ContractOpenApiIT)→ OpenAPI 文档 →(gen-openapi-snapshot.sh)→ 前端契约测试（TEST-006 三层防线直接复用）。
4. **验收标准（EARS）**：研究数据集导出时（when），所有学生身份字段必须为假名且不可逆（shall），审计必须记录范围与行数（shall），豁免数据在保留期内不被物理删除（shall），撤销豁免后数据在下个清理周期被删除（shall）。

---

_本方案由架构审查（improve-codebase-architecture，2026-07-28）产出并冻结归档；解冻实施按 TDD 推进，实施期只改本子文档，完成时按 doing 工作流并入 12 份主文档。_
