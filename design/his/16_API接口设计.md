# 16 API 接口设计

> 状态：新建 | 关联：05 老师后台、06 数据库、07 SaaS 隔离、08 MVP、12 技术架构、13 Agent 工作流
> 核心：定义系统所有 REST API 端点、请求/响应 DTO、错误码与鉴权机制，是前后端并行开发的契约。

> ⚠️ **实现偏差说明（2026-07-28 核对）**：
> - 租户隔离实际通过 **JWT 内嵌 tenantId + JwtAuthenticationFilter 注入 TenantContext**（非 X-Tenant-Id Header）
> - JWT Payload 实际为 `{userId, userType, tenantId}`（非 sub/tid/rid/schoolId）
> - 统一响应实际为 `{code, message, data}`（无 traceId/timestamp 字段，traceId 在日志层）
> - **新增端点（本文档未覆盖）**：
>   - `POST /api/v1/chat/sessions/{id}/nudge` — 冷场暖场 SSE
>   - `POST /api/v1/auth/pin-login` / `POST /api/v1/auth/set-pin` — PIN 码登录
>   - `POST /api/v1/auth/refresh` / `POST /api/v1/auth/logout` — Token 刷新/登出拉黑
>   - `POST /api/v1/auth/guardian-consent/*` — 监护人同意闭环
>   - `POST /api/v1/parent/auth/register` / `login` — 家长家庭码注册/登录
>   - `POST /api/v1/parent/consent/withdraw` — 撤回同意
>   - `POST /api/v1/knowledge/documents` / `GET /search` — RAG 知识库
>   - `POST /api/v1/voice/analyze` — 语音分析（ASR+情感）
>   - `POST /api/v1/tts/synthesize` / `GET /personas` — TTS 合成/音色
>   - `GET /api/v1/diary/*` — 情绪日记全套
>   - `POST /api/v1/alerts/{id}/schedule-followup` / `complete-followup` — 预警回访闭环
>   - `GET /api/v1/teacher/students/{id}/radar` — 画像雷达图
>   - `GET /api/v1/platform/*` — 平台管理后台
> - 完整 API 清单见 `design/33_系统测试培训手册.md` §六
> - **端点四态对照与契约深化见本文 §12（2026-07-28 设计深化）**

---

## 0. 设计原则

| 原则 | 说明 |
|------|------|
| RESTful | 资源导向，HTTP 动词语义化，状态码标准化 |
| 租户隔离 | 所有请求必须携带 `X-Tenant-Id`（从 JWT 自动注入），后端 fail-fast |
| 版本控制 | URL 前缀 `/api/v1/`，大版本变更时递增 |
| 统一响应 | `{ code, message, data, traceId }` 包装 |
| 分页 | `?page=1&size=20&sort=created_at,desc`，响应含 `total/totalPages` |
| 敏感脱敏 | 学生姓名默认返回脱敏名（`张*明`），完整名需 `scope:student:full_name` 权限 |

---

## 1. 通用约定

### 1.1 统一响应格式

```json
{
  "code": 0,
  "message": "success",
  "data": { ... },
  "traceId": "abc-123-def",
  "timestamp": "2026-07-23T10:00:00Z"
}
```

- `code = 0` 成功，非 0 为业务错误码
- `traceId` 用于链路追踪，前端异常上报时携带

### 1.2 分页响应

```json
{
  "code": 0,
  "data": {
    "items": [ ... ],
    "page": 1,
    "size": 20,
    "total": 156,
    "totalPages": 8
  }
}
```

### 1.3 鉴权方式

| 方式 | 适用场景 | Header |
|------|------|------|
| JWT Bearer | 学生端/教师端/管理端用户请求 | `Authorization: Bearer <token>` |
| API Key + Secret | 服务端到服务端（SSO 回调、外部集成） | `X-Api-Key` + `X-Api-Signature` |

JWT Payload 结构：`{ sub(userId), tid(tenantId), rid(roleId), schoolId, exp, iat }`

### 1.4 业务错误码

| 错误码 | 含义 | HTTP 状态 |
|------|------|------|
| 10001 | 参数校验失败 | 400 |
| 10002 | 资源不存在 | 404 |
| 10003 | 资源状态冲突（如重复认领） | 409 |
| 20001 | 未认证 / Token 过期 | 401 |
| 20002 | 无权限（越权访问） | 403 |
| 20003 | 跨租户访问 | 403 |
| 30001 | 配额超限（API 频率） | 429 |
| 30002 | 会话时长超限 | 429 |
| 30003 | 监护人未授权 | 403 |
| 40001 | AI 服务不可用 | 503 |
| 40002 | 风险拦截（输出审查未通过） | 内部处理，不暴露前端 |

---

## 2. 认证与用户模块

### 2.1 登录

```
POST /api/v1/auth/login
```

**请求**：
```json
{
  "loginType": "password | dingtalk | wechat_work",
  "credentials": {
    "username": "student001",
    "password": "..."
  },
  "schoolCode": "Nanjing_No1"
}
```

**响应**：
```json
{
  "code": 0,
  "data": {
    "accessToken": "eyJhbGci...",
    "refreshToken": "eyJhbGci...",
    "expiresIn": 86400,
    "user": {
      "userId": "u-001",
      "name": "张*明",
      "role": "student",
      "grade": 5,
      "className": "502",
      "schoolId": "s-001"
    }
  }
}
```

### 2.2 监护人授权（首次使用前置）

```
POST /api/v1/auth/guardian-consent
```

**请求**：
```json
{
  "studentId": "u-001",
  "guardianPhone": "13800138000",
  "consentItems": ["data_collection", "counseling_session", "risk_notification"],
  "consentVersion": "1.0.0"
}
```

**响应**：
```json
{
  "code": 0,
  "data": {
    "authorizationId": "auth-001",
    "status": "pending_sms_verify",
    "consentRecordId": "cr-001"
  }
}
```

### 2.3 刷新 Token

```
POST /api/v1/auth/refresh
```

### 2.4 登出

```
POST /api/v1/auth/logout
```

### 2.5 获取当前用户信息

```
GET /api/v1/users/me
```

---

## 3. 学生端 API

### 3.1 情绪选择 & 会话启动

```
POST /api/v1/sessions
```

**请求**：
```json
{
  "emotionType": "sad | happy | angry | scared | nervous",
  "channel": "web | tablet"
}
```

**响应**：
```json
{
  "code": 0,
  "data": {
    "sessionId": "sess-001",
    "state": "S0_START",
    "aiGreeting": "你好呀，我是你的心理陪伴小助手。你今天看起来有点难过，愿意跟我说说吗？",
    "remainingTurns": 12,
    "sessionStartedAt": "2026-07-23T10:00:00Z"
  }
}
```

### 3.2 发送消息（核心对话接口）

```
POST /api/v1/sessions/{sessionId}/messages
```

**请求**：
```json
{
  "content": "我数学考差了，觉得自己好笨",
  "clientTimestamp": "2026-07-23T10:01:00Z"
}
```

**响应**（SSE 流式 / 同步两种模式）：
```json
{
  "code": 0,
  "data": {
    "messageId": "msg-001",
    "reply": "考差了确实让人难过。你能告诉我，最让你担心的是什么吗？",
    "state": "S4_EVENT_FACT",
    "emotionLabel": "sad",
    "emotionIntensity": 7,
    "riskLevel": "L1",
    "suggestedActions": [],
    "remainingTurns": 10,
    "showBreathingExercise": false
  }
}
```

**SSE 流式模式**（`Accept: text/event-stream`）：
```
event: token
data: {"content": "考差了"}

event: token
data: {"content": "确实让人"}

event: metadata
data: {"state": "S4_EVENT_FACT", "riskLevel": "L1", "remainingTurns": 10}

event: done
data: {"messageId": "msg-001"}
```

### 3.3 结束会话 & 满意度评价

```
POST /api/v1/sessions/{sessionId}/close
```

**请求**：
```json
{
  "satisfaction": 4,
  "closeReason": "natural | user_exit | turn_limit | safety_escalation"
}
```

### 3.4 放松练习

```
GET /api/v1/relaxation/exercises
```

**响应**：
```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "id": "breath_323",
        "name": "3-2-3 呼吸法",
        "type": "breathing",
        "duration": 120,
        "steps": [
          { "instruction": "慢慢吸气", "duration": 3 },
          { "instruction": "轻轻屏住", "duration": 2 },
          { "instruction": "缓缓呼出", "duration": 3 }
        ],
        "animationUrl": "/assets/animations/breath-323.json"
      }
    ]
  }
}
```

```
POST /api/v1/relaxation/sessions
```

**请求**：
```json
{
  "exerciseId": "breath_323",
  "completed": true,
  "moodBefore": 3,
  "moodAfter": 6
}
```

### 3.5 会话历史

```
GET /api/v1/sessions?status=closed&page=1&size=10
```

**响应**：
```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "sessionId": "sess-001",
        "emotionType": "sad",
        "startTime": "2026-07-23T10:00:00Z",
        "endTime": "2026-07-23T10:15:00Z",
        "turnCount": 8,
        "satisfaction": 4,
        "riskTriggered": false
      }
    ],
    "total": 12
  }
}
```

---

## 4. 教师端 API

### 4.1 工作台概览

```
GET /api/v1/teacher/dashboard
```

**响应**：
```json
{
  "code": 0,
  "data": {
    "pendingAlertCount": 3,
    "overdueSlaCount": 1,
    "highestRiskLevel": "S1",
    "todayAppointments": 2,
    "pendingTasks": 5,
    "assessmentProgress": {
      "taskName": "MHT 期中测评",
      "completionRate": 0.72,
      "totalStudents": 45,
      "completedStudents": 32
    },
    "weeklyTrend": {
      "newRisks": 4,
      "recurringCases": 1,
      "closedCases": 2
    }
  }
}
```

### 4.2 预警队列

```
GET /api/v1/alerts?level=S0,S1&status=pending&page=1&size=20&sort=risk_level,desc
```

**响应**：
```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "alertId": "alert-001",
        "studentPseudoId": "P-502-***",
        "grade": 5,
        "className": "502",
        "riskLevel": "S1",
        "source": "safety_agent | assessment | manual",
        "triggerTime": "2026-07-23T09:30:00Z",
        "summary": "连续 3 次对话表达低落，提及不想上学",
        "evidenceSnippets": ["每天都不想上学", "觉得没人喜欢我"],
        "confidence": 0.85,
        "slaDeadline": "2026-07-23T11:30:00Z",
        "status": "pending",
        "assignee": null
      }
    ],
    "total": 3
  }
}
```

### 4.3 预警操作

```
POST /api/v1/alerts/{alertId}/claim          # 认领
POST /api/v1/alerts/{alertId}/assign          # 转派
PATCH /api/v1/alerts/{alertId}/escalate       # 升级
PATCH /api/v1/alerts/{alertId}/false-positive  # 标记误报
POST /api/v1/alerts/{alertId}/create-case     # 创建个案
```

**认领请求**：
```json
{ "action": "claim" }
```

**创建个案请求**：
```json
{
  "caseType": "risk_follow_up",
  "initialRiskLevel": "S1",
  "notes": "连续低落，需持续跟进"
}
```

### 4.4 个案管理

```
GET    /api/v1/cases?page=1&size=20&status=active
GET    /api/v1/cases/{caseId}
PATCH  /api/v1/cases/{caseId}                    # 更新个案信息
POST   /api/v1/cases/{caseId}/interventions      # 添加干预记录
POST   /api/v1/cases/{caseId}/close              # 结案
GET    /api/v1/cases/{caseId}/timeline           # 个案时间线
```

**个案详情响应**：
```json
{
  "code": 0,
  "data": {
    "caseId": "case-001",
    "studentPseudoId": "P-502-***",
    "status": "active",
    "riskLevel": "S1",
    "triggerReason": "连续低落 + 预警升级",
    "assignedTo": "counselor-001",
    "createdAt": "2026-07-20T10:00:00Z",
    "interventions": [
      {
        "id": "int-001",
        "type": "face_to_face",
        "date": "2026-07-21",
        "summary": "问题：学业压力；观察：情绪低落；策略：呼吸练习；反馈：愿意尝试",
        "nextStep": "一周后复谈"
      }
    ],
    "timeline": [...]
  }
}
```

### 4.5 测评管理

```
GET    /api/v1/assessments/scales                      # 量表库
POST   /api/v1/assessments/tasks                        # 创建测评任务
GET    /api/v1/assessments/tasks/{taskId}                # 任务详情
GET    /api/v1/assessments/tasks/{taskId}/results         # 结果列表
GET    /api/v1/assessments/tasks/{taskId}/results/{resultId} # 单个结果（含风险等级）
POST   /api/v1/assessments/tasks/{taskId}/remind          # 催办
PATCH  /api/v1/assessments/tasks/{taskId}/pause            # 暂停
```

### 4.6 预约与跟进

```
GET    /api/v1/appointments?date=2026-07-23&counselorId=xxx
POST   /api/v1/appointments
PATCH  /api/v1/appointments/{appointmentId}
DELETE /api/v1/appointments/{appointmentId}
POST   /api/v1/appointments/{appointmentId}/checkin       # 签到
POST   /api/v1/appointments/{appointmentId}/no-show        # 标记缺席
```

### 4.7 家校沟通

```
POST   /api/v1/communications/guardian          # 发起家长沟通
GET    /api/v1/communications?caseId=case-001
POST   /api/v1/communications/{commId}/consent   # 记录授权
```

### 4.8 班主任协同

```
GET    /api/v1/class/{classCode}/risk-overview   # 班级风险概览
POST   /api/v1/class/{classCode}/observations    # 班主任提交观察
GET    /api/v1/tasks/assigned?role=class_teacher   # 班主任待办任务
PATCH  /api/v1/tasks/{taskId}                      # 更新任务状态
```

### 4.9 管理报表

```
GET /api/v1/reports/school-overview              # 学校概览
GET /api/v1/reports/risk-distribution            # 风险分布
GET /api/v1/reports/sla-performance              # SLA 达成率
GET /api/v1/reports/usage-statistics             # 使用统计
GET /api/v1/reports/assessment-summary           # 测评汇总
```

---

## 5. 管理端 API

### 5.1 租户/学校管理

```
GET    /api/v1/admin/tenants
POST   /api/v1/admin/tenants
GET    /api/v1/admin/tenants/{tenantId}/schools
POST   /api/v1/admin/tenants/{tenantId}/schools
PATCH  /api/v1/admin/schools/{schoolId}/settings    # 学校配置（预警阈值/通知渠道等）
```

### 5.2 用户与权限

```
GET    /api/v1/admin/users?role=student&schoolId=xxx
POST   /api/v1/admin/users
PATCH  /api/v1/admin/users/{userId}
POST   /api/v1/admin/users/{userId}/roles           # 分配角色
DELETE /api/v1/admin/users/{userId}/roles/{roleId}
```

### 5.3 知识库管理

```
GET    /api/v1/admin/knowledge/documents
POST   /api/v1/admin/knowledge/documents
PATCH  /api/v1/admin/knowledge/documents/{docId}
POST   /api/v1/admin/knowledge/documents/{docId}/submit-review   # 提交审核
POST   /api/v1/admin/knowledge/documents/{docId}/approve         # 审核通过
POST   /api/v1/admin/knowledge/documents/{docId}/reject          # 审核驳回
GET    /api/v1/admin/knowledge/chunks?documentId=xxx              # 知识切片
GET    /api/v1/knowledge/editorial/report?missedQueries=q1,q2    # 运营报表：内容缺口 + 分类覆盖（design/49 §五；实际路径无 /admin 前缀，ADMIN 角色）
```

### 5.4 审计日志

```
GET /api/v1/admin/audit-logs?actorId=xxx&action=xxx&from=xxx&to=xxx
```

### 5.5 系统配置

```
GET    /api/v1/admin/config/risk-rules           # 风险规则配置
PATCH  /api/v1/admin/config/risk-rules
GET    /api/v1/admin/config/notification-channels # 通知渠道
PATCH  /api/v1/admin/config/notification-channels
```

---

## 6. 内部 AI 服务 API（服务间调用）

### 6.1 对话编排

```
POST /internal/ai/orchestrate
```

**请求**：
```json
{
  "sessionId": "sess-001",
  "tenantId": "tenant-001",
  "studentId": "u-001",
  "gradeLevel": 5,
  "userInput": "我数学考差了",
  "conversationState": {
    "currentState": "S2_EMOTION_LABEL",
    "emotionLabel": "sad",
    "emotionIntensity": 7,
    "riskLevel": "L1",
    "turnCount": 3
  }
}
```

**响应**：
```json
{
  "reply": "考差了确实让人难过。你能告诉我，最让你担心的是什么吗？",
  "nextState": "S4_EVENT_FACT",
  "riskEvent": {
    "riskLevel": "L1",
    "riskDomains": ["academic_stress"],
    "confidence": 0.82,
    "needsHumanReview": false
  },
  "promptVersion": "system_student_companion_zh-CN_v1.2.0",
  "modelVersion": "qwen-max-2026-06",
  "latencyMs": 1200
}
```

### 6.2 风险评估（独立调用）

```
POST /internal/ai/safety-assess
```

### 6.3 教师摘要生成

```
POST /internal/ai/teacher-summary
```

**请求**：
```json
{
  "sessionId": "sess-001",
  "targetRole": "counselor | class_teacher"
}
```

---

## 7. WebSocket 实时推送

### 7.1 连接

```
WS /ws?token=<jwt>
```

### 7.2 推送事件

| 事件类型 | 接收方 | 数据 |
|------|------|------|
| `alert.new` | 教师端 | 新预警通知 |
| `alert.sla_warning` | 教师端 | SLA 即将超时 |
| `alert.escalated` | 教师端 + 管理端 | 预警升级 |
| `appointment.reminder` | 教师端 + 学生端 | 预约提醒 |
| `session.risk_change` | 教师端 | 会话中风险等级变化 |

---

## 8. 接口与数据表映射

| API 模块 | 主要操作表 | 权限要求 |
|------|------|------|
| 认证 | users, roles, user_roles, guardians | 公开/监护人 |
| 学生对话 | counseling_sessions, message_summaries, risk_events | student |
| 放松练习 | counseling_sessions（关联） | student |
| 教师工作台 | risk_events, case_records, notifications | counselor |
| 预警队列 | risk_events, notifications | counselor, admin |
| 个案管理 | case_records, case_interventions | counselor |
| 测评 | assessment_scales/results/responses | counselor, teacher |
| 预约 | appointments（counseling_sessions 扩展） | counselor |
| 家校沟通 | communications, guardian_student_authorizations | counselor |
| 班主任 | users, risk_events（脱敏） | teacher |
| 报表 | 聚合查询（多表） | admin |
| 管理 | tenants, schools, users, audit_logs | platform_admin |
| 知识库 | knowledge_documents, knowledge_chunks | platform_admin |

---

> **开发约定**：所有 API 使用 Spring MVC `@RestController` 实现，DTO 使用 `record` 类型（Java 16+），参数校验用 `jakarta.validation`，统一异常处理用 `@ControllerAdvice`。接口文档使用 SpringDoc OpenAPI 3 自动生成 Swagger UI。

---

## 9. Java DTO Record 定义（核心）

> 以下为 MVP 核心接口的 Java record 定义，开发人员可直接复制到对应模块。

### 9.1 通用

```java
// 统一响应包装
public record ApiResponse<T>(
    int code,
    String message,
    T data,
    String traceId,
    Instant timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data, MDC.get("traceId"), Instant.now());
    }
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, MDC.get("traceId"), Instant.now());
    }
}

// 分页请求
public record PageRequest(
    @Min(1) int page,
    @Min(1) @Max(100) int size,
    String sort  // 格式: "created_at,desc"
) {}

// 分页响应
public record PageResponse<T>(
    List<T> items,
    int page,
    int size,
    long total,
    int totalPages
) {}
```

### 9.2 认证模块

```java
// 登录请求
public record LoginRequest(
    @NotBlank String username,
    @NotBlank @Size(min = 6, max = 128) String password,
    @NotBlank String tenantCode  // 学校编码
) {}

// 登录响应
public record LoginResponse(
    String accessToken,
    String refreshToken,
    long expiresIn,  // 秒
    UserInfo user
) {}

public record UserInfo(
    String userId,
    String username,
    String displayName,
    String role,       // student | counselor | teacher | admin | platform_admin
    String tenantId,
    String grade,      // 学生专用
    String className   // 学生专用
) {}

// 监护人授权请求
public record GuardianConsentRequest(
    @NotBlank String studentId,
    @NotBlank String guardianName,
    @NotBlank String relationship,  // father | mother | other
    @AssertTrue boolean agreed
) {}
```

### 9.3 学生对话模块

```java
// 创建会话
public record CreateSessionRequest(
    @NotNull EmotionType initialEmotion  // HAPPY | SAD | ANGRY | SCARED | NERVOUS
) {}

public record CreateSessionResponse(
    String sessionId,
    String greetingMessage,  // AI 开场白
    int maxTurns             // 12
) {}

// 发送消息
public record SendMessageRequest(
    @NotBlank @Size(max = 200) String content
) {}

// SSE 流式消息事件
public record StreamMessageEvent(
    String type,       // "token" | "done" | "error" | "safety_alert"
    String content,    // token 内容或完整消息
    MessageMetadata metadata
) {}

public record MessageMetadata(
    String role,           // user | ai | system
    String cbtState,       // S0_START | S2_EMOTION_LABEL | ...
    String emotionLabel,
    Integer emotionIntensity,
    String riskLevel,      // L0-L5
    int remainingTurns,
    boolean safetyMode     // true 时输入框禁用
) {}

// 会话历史
public record SessionListItem(
    String sessionId,
    String emotionLabel,
    String scenarioId,
    String riskLevel,
    Instant createdAt,
    int turnCount
) {}

// 会话评价
public record SessionFeedbackRequest(
    @NotNull @Min(1) @Max(3) int rating,  // 1=没帮助 2=一般 3=有帮助
    String comment  // 可选
) {}
```

### 9.4 风险与预警模块

```java
// 风险评估结果（Safety Agent 输出）
public record RiskAssessmentResult(
    String riskLevel,          // L0-L5
    List<String> riskDomains,
    double confidence,
    boolean needsClarification,
    String clarifyingQuestion,
    boolean needsHumanReview,
    boolean needsImmediateEscalation,
    String studentSafeReplyStrategy,
    List<RiskEvidence> evidence,
    NextAction nextAction
) {}

public record RiskEvidence(
    @Size(max = 30) String quote,
    String signal,
    String severityReason
) {}

public record NextAction(
    String notifyRole,     // none | psychology_teacher | duty_teacher | guardian | emergency
    String responseLimit,  // normal | restricted | safety_only
    String loggingLevel    // anonymous | summary | full_evidence
) {}

// 预警列表项
public record AlertListItem(
    String alertId,
    String riskLevel,          // R4 | R3 | R2 | R1
    String studentDesensitizedName,
    String grade,
    String className,
    String triggerSource,
    String triggerSummary,
    List<String> evidenceSnippets,
    double confidence,
    Instant createdAt,
    Instant slaDeadline,
    long slaRemainingMinutes,
    String status,             // pending | claimed | processing | closed | false_positive
    String assignee
) {}

// 认领预警
public record ClaimAlertRequest(
    @NotBlank String alertId
) {}

// 标记误报
public record MarkFalsePositiveRequest(
    @NotBlank String alertId,
    @NotBlank @Size(max = 500) String reason
) {}

// 预警处置记录
public record AlertResolutionRequest(
    @NotBlank String alertId,
    @NotBlank @Size(max = 2000) String resolution,
    String followupAction  // create_case | schedule_appointment | notify_guardian | observe
) {}
```

### 9.5 个案管理模块

```java
// 创建个案
public record CreateCaseRequest(
    @NotBlank String studentId,
    @NotBlank String triggerReason,
    String relatedAlertId,     // 关联预警
    String initialRiskLevel,
    String interventionPlan    // 初始干预计划
) {}

// 个案列表项
public record CaseListItem(
    String caseId,
    String studentDesensitizedName,
    String grade,
    String className,
    String status,         // pending_assessment | in_intervention | observation | closed
    String riskLevel,
    String assignee,
    Instant createdAt,
    Instant lastFollowupAt
) {}

// 会谈记录
public record SessionNoteRequest(
    @NotBlank String caseId,
    @NotBlank @Size(max = 2000) String issue,       // 问题
    @Size(max = 2000) String observation,            // 观察
    @Size(max = 2000) String strategy,               // 支持策略
    @Size(max = 2000) String studentFeedback,        // 学生反馈
    @Size(max = 2000) String nextStep                // 下一步
) {}
```

### 9.6 教师摘要模块

```java
// 教师摘要（ReportAgent 输出）
public record TeacherSummary(
    String studentOverview,    // ≤80字
    String riskLevel,
    double confidence,
    List<String> keyConcerns,  // ≤3
    List<String> triggerEvidence,  // ≤2，每条≤30字
    RecommendedActions recommendedActions,
    String uncertainInfo,
    String privacyNote,
    boolean needsCollaboration,
    String collaborationNote
) {}

public record RecommendedActions(
    String today,
    String thisWeek,
    String ongoing
) {}
```

---

## 10. 字段校验规则汇总

| DTO | 字段 | 校验 | 错误码 |
|-----|------|------|--------|
| LoginRequest | username | @NotBlank | 10001 |
| LoginRequest | password | @NotBlank @Size(6,128) | 10001 |
| LoginRequest | tenantCode | @NotBlank | 10001 |
| SendMessageRequest | content | @NotBlank @Size(max=200) | 20001 |
| CreateSessionRequest | initialEmotion | @NotNull, enum 校验 | 20002 |
| GuardianConsentRequest | agreed | @AssertTrue | 10003 |
| MarkFalsePositiveRequest | reason | @NotBlank @Size(max=500) | 30001 |
| SessionNoteRequest | issue | @NotBlank @Size(max=2000) | 30002 |
| AlertResolutionRequest | resolution | @NotBlank @Size(max=2000) | 30003 |
| SessionFeedbackRequest | rating | @NotNull @Min(1) @Max(3) | 20003 |

**全局校验规则**：
- 所有 `@NotBlank` 校验失败 → 返回 `{code: 40001, message: "参数校验失败: {field}"}`
- 所有 enum 校验失败 → 返回 `{code: 40002, message: "无效枚举值: {field}"}`
- 请求体 JSON 解析失败 → 返回 `{code: 40003, message: "请求体格式错误"}`

---

## 11. 核心接口时序图

### 11.1 学生发送消息（完整链路）

```
前端                Controller          Orchestrator       SafetyInput      LLM             SafetyOutput     DB
 │                    │                    │                  │               │                  │              │
 │─POST /messages────▶│                    │                  │               │                  │              │
 │                    │─@Valid DTO────────▶│                  │               │                  │              │
 │                    │                    │─assessRisk──────▶│               │                  │              │
 │                    │                    │                  │─硬规则+LLM──▶│                  │              │
 │                    │                    │◀─RiskResult──────│◀──────────────│                  │              │
 │                    │                    │                  │               │                  │              │
 │                    │                    │─[L4/L5短路]──────────────────────────────────────▶│              │
 │                    │                    │  安全模板(§12)   │               │                  │              │
 │◀─SSE: safety_alert─│◀───────────────────│                  │               │                  │              │
 │                    │                    │                  │               │                  │              │
 │                    │                    │─[L0-L3正常]──────│               │                  │              │
 │                    │                    │  路由+组装Prompt │               │                  │              │
 │                    │                    │─generate─────────│──────────────▶│                  │              │
 │◀─SSE: token stream─│◀─SSE stream──────│◀─token stream────│──────────────│                  │              │
 │                    │                    │                  │               │                  │              │
 │                    │                    │─reviewReply──────│───────────────│─────────────────▶│              │
 │                    │                    │◀─{decision:pass}─│───────────────│─────────────────│              │
 │                    │                    │                  │               │                  │              │
 │                    │                    │─INSERT message + UPDATE session────────────────────────────────▶│
 │◀─SSE: [DONE]──────│◀───────────────────│                  │               │                  │              │
```

### 11.2 教师认领预警并创建个案

```
教师端              Controller          AlertService       CaseService      NotifyService    DB
 │                    │                    │                  │                │              │
 │─POST /alerts/{id}/claim───────────────▶│                  │                │              │
 │                    │─claim─────────────▶│                  │                │              │
 │                    │                    │─UPDATE status=processing, assignee=当前用户───▶│
 │                    │                    │─INSERT audit_log──────────────────────────────▶│
 │◀─{success}─────────│◀───────────────────│                  │                │              │
 │                    │                    │                  │                │              │
 │─POST /cases───────────────────────────▶│                  │                │              │
 │  {alertId, ...}    │─createCase─────────│─────────────────▶│                │              │
 │                    │                    │                  │─INSERT case───▶│              │
 │                    │                    │                  │─UPDATE alert.status=case_created──▶│
 │                    │                    │                  │─WS push───────▶│              │
 │◀─{caseId}──────────│◀───────────────────│◀─────────────────│◀───────────────│              │
```

---

## 12. 设计深化：契约现状对照与补全规划（2026-07-28）

> 图例：🟩 已生效 · 🟧 已实现零调用 · 🟫 仅骨架/部分实现 · ⬜ 未实现。本篇是**前后端契约文档**，深化重点是让契约与 18 个已实现 Controller 对齐，并为规划端点预留清晰的任务归口。

### 12.1 端点模块四态对照

| 模块 | 章节 | 状态 | 说明 |
|------|------|:---:|------|
| 认证（login/refresh/logout/guardian-consent） | §2 | 🟩 | 已生效；另有本文未覆盖的 pin-login/set-pin（见头部偏差说明） |
| 学生对话（会话/消息 SSE/关闭/历史） | §3.1-3.3/3.5 | 🟩 | 主链路已生效；nudge 冷场暖场为新增端点 |
| 放松练习 | §3.4 | 🟫 | 前端呼吸练习组件存在，后端练习库/记录端点未核对到独立实现 |
| 教师工作台/预警队列/认领误报处置/回访 | §4.1-4.3 部分 | 🟩 | claim/false-positive/resolve/schedule-followup/complete-followup 已生效 |
| 预警转派/升级/建个案 | §4.3 部分 | ⬜ | assign/escalate/create-case 设计期，归 WB-001/WB-003 |
| 个案管理 | §4.4 | ⬜ | 无 CaseController/实体，归 WB-003 |
| 测评管理 | §4.5 | ⬜ | 代码完全空白（无 Assessment 实体），归 SCALE-001/002；**施测接线上线门禁待项目负责人决策（见 10 §10.2）** |
| 预约/家校沟通/班主任协同 | §4.6-4.8 | ⬜ | 设计期；预约归 WB-003，班主任协同归 WB-002 字段裁剪衔接 |
| 管理报表 | §4.9 | 🟫 | teacher-web 有统计页，报表端点覆盖度未逐一核对 |
| 租户/用户管理 | §5.1-5.2 | 🟫 | platform/* 平台管理端点已实现（新增形态），与本文 admin/* 命名不一致，以实现为准 |
| 知识库审核工作流 | §5.3 | ⬜ | KnowledgeController 仅 documents/search；submit-review/approve/reject 无状态字段支撑，归 49/KB 系列（呼应 15 §12.1 审核铁律） |
| 审计日志/系统配置 | §5.4-5.5 | 🟩 | 审计日志：GET /api/v1/admin/audit-logs（AdminController，action 过滤 + limit 默认 200/上限 500，audit_logs 表）；系统配置：GET /api/v1/system/config（SystemConfigController，前端运行时下发，permitAll + SystemConfigControllerTest）；审计写入点：IMPORT_STUDENTS/ALERT_TRANSFER/EDITORIAL_*/PROMPT_VERSION_ACTIVATE 等（AuditLogService + audit_logs，免 schema 变更）；§5.5 的 risk-rules/notification-channels PATCH 端点未实现（设计期，无对应 Controller） |
| 内部 AI 服务 API | §6 | ⬜ | 编排实际在 ConversationServiceImpl 进程内完成，未拆独立内部 API；~~§6 保留为世界B目标态~~ → 世界B 已删除（DEC-CBT 2026-07-29），§6 作为远期微服务拆分参考保留，归 ORCH 系列 |
| WebSocket 推送 | §7 | 🟫 | alert.new 已生效（useAlertWebSocket + 15s 轮询兜底）；sla_warning/escalated/appointment.reminder/session.risk_change ⬜ |

### 12.2 契约基线修正（以实现为准，前后端对齐依据）

1. **统一响应**：`{code, message, data}`——§1.1 的 traceId/timestamp 字段实现中不在响应体（traceId 在日志层）。前端不得依赖响应体 traceId。
2. **JWT Payload**：`{userId, userType, tenantId}`——§1.3 的 sub/tid/rid/schoolId 结构作废。
3. **租户隔离**：JWT 内嵌 tenantId + JwtAuthenticationFilter 注入 TenantContext，**无 X-Tenant-Id Header**，§0 表述作废。
4. **错误码表内部冲突（本轮发现，需修复）**：§1.4 定义 40001=AI 服务不可用、40002=风险拦截；§10 全局校验规则又定义 40001=参数校验失败、40002=无效枚举值，且 §10 表格将 content 校验错误标为 20001（§1.4 中 20001=未认证）。**处理原则：以 §1.4 为唯一错误码登记表，§10 的校验类错误统一回归 10001，40003（JSON 解析失败）迁入 §1.4 登记**；实现侧以 GlobalExceptionHandler 实际返回值核对后修订。

### 12.3 规划新增端点契约（设计期，先归口后开发）

| 端点域 | 契约要点 | 任务归口 |
|------|------|------|
| 量表施测（学生侧） | `GET /api/v1/assessments/pending`（待测清单）、`POST /api/v1/assessments/tasks/{taskId}/answers`（提交作答，服务端计分不信任前端分值） | SCALE-001/002（上线门禁见 10 §10.2） |
| 工具箱 | 工具清单/使用记录/情绪前后对比（与 §3.4 放松练习契约合并，避免两套记录模型） | TOOL-001/002 |
| 画像元数据 | 教师侧画像字段附 provenance/confidence 元数据（呼应 23/46） | PROF-020/021 |
| 记忆管理 | 学生长期记忆查看/删除（撤回同意联动清除） | 归 46 画像闭环，暂缓独立端点（YAGNI） |
| 编排内部 API | §6 三端点作为远期微服务拆分参考保留（~~世界B 目标态~~ → 世界B 已删除）；MVP 不拆分进程 | ORCH-001/002 前置决策 |

### 12.4 WebSocket 广播深化

- 现状：单实例内 alert.new 直推 + 前端 15s 轮询兜底，**轮询兜底是当前可靠性主来源**，不应在广播完善前移除。
- 扩容到多实例后 WS 连接与事件产生不在同一进程，需引入 Redis pub/sub 广播（跨实例事件分发设计见 design/40），归 STATE 系列（远期），MVP 不做。
- §7 事件清单中 sla_warning 依赖 SLA 定时扫描（WB-001 承接）、session.risk_change 依赖会话中风险变化推送（与 05 §20 预警链路衔接），实现顺序跟随对应任务，不单独立项。

### 12.5 任务归口

| 缺口 | 归口 | 优先级 |
|------|------|:---:|
| 错误码登记表统一修复（§12.2-4） | 本文档修订 + GlobalExceptionHandler 核对（并入 WB-001 开发前置） | P1 |
| 测评端点契约细化 | SCALE-001/002 | P1（开发）/门禁待决策 |
| 个案/预约/转派升级端点 | WB-003 | P2 |
| 知识库审核工作流端点 | 49/KB 系列 | P1 |
| 内部编排 API 拆分 | ORCH 系列（远期微服务参考，~~世界B~~ 已删除） | 远期 |
| WS 跨实例广播 | STATE 系列 | 远期 |
