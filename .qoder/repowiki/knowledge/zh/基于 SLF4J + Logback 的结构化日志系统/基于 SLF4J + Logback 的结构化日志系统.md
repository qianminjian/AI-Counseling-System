---
kind: logging_system
name: 基于 SLF4J + Logback 的结构化日志系统
category: logging_system
scope:
    - '**'
source_files:
    - backend/counseling-app/src/main/resources/logback-spring.xml
    - backend/counseling-app/src/main/resources/application.yml
    - backend/counseling-api/src/main/java/com/mindsafe/api/filter/TraceFilter.java
    - backend/counseling-app/pom.xml
---

### 1. 使用的框架与工具
- 日志门面：SLF4J（所有业务模块通过 `org.slf4j.Logger` / `LoggerFactory.getLogger()` 获取 logger）
- 日志实现：Logback（由 Spring Boot 默认引入，并通过 `logback-spring.xml` 统一配置）
- 结构化输出：生产环境使用 `net.logstash.logback:logstash-logback-encoder` 输出 JSON（Logstash 兼容格式），便于 ELK/日志采集器解析
- 链路追踪：通过自定义 `TraceFilter` 将 `traceId` 注入 MDC，并在日志 pattern 中输出 `%X{traceId:-}`

### 2. 核心文件与位置
- 日志配置：`backend/counseling-app/src/main/resources/logback-spring.xml`（按 profile 切换控制台彩色 vs JSON 结构化）
- 应用配置：`backend/counseling-app/src/main/resources/application.yml`（logging.level 全局开关、MyBatis-Plus 的 Slf4jImpl 绑定）
- 链路追踪过滤器：`backend/counseling-api/src/main/java/com/mindsafe/api/filter/TraceFilter.java`
- 依赖声明：`backend/counseling-app/pom.xml` 中引入 `logstash-logback-encoder`

### 3. 架构与约定
- **多环境差异化输出**：
  - `default` / `test` profile：ConsoleAppender + 彩色 pattern（含 traceId），com.mindsafe 包 DEBUG，Spring AI INFO。
  - `prod` profile：ConsoleAppender + LogstashEncoder 输出 JSON，包含自定义字段 `{"app":"mindsafe-counseling","env":"prod"}`，并启用 MDC 中的 `traceId` 字段。
- **MDC 链路追踪**：`TraceFilter` 以最高优先级拦截请求，优先从 `X-Trace-Id` 请求头读取（支持网关透传），否则生成 16 位 UUID；写入 MDC 后在 finally 中清理，同时把 traceId 回写到响应头供前端排查。
- **日志级别策略**：
  - 开发/测试：`com.mindsafe` 为 DEBUG，方便调试；Spring AI 为 INFO。
  - 生产：`com.mindsafe` 降为 INFO，Spring AI / MyBatis-Plus 等第三方组件设为 WARN，减少噪音。
- **MyBatis-Plus SQL 日志**：通过 `mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.slf4j.Slf4jImpl` 将 SQL 执行走 SLF4J，便于统一收集。
- **AI 调用链日志**：各 Agent（CBTAgent、ConversationAgent、EmotionAgent、SafetyAgent）、AiChatServiceImpl、ResilientChatModel 等均通过 SLF4J 记录 LLM 调用成功/失败、降级、关键词命中等关键路径，便于问题定位。

### 4. 约定与约束
- **Logger 获取方式**：所有类统一使用 `private static final Logger log = LoggerFactory.getLogger(ClassName.class)`，未使用 Lombok `@Slf4j`，保持显式依赖。
- **结构化字段**：生产环境日志必须包含 `ts`（时间戳）、`level`、`logger`、`msg`、`traceId`（来自 MDC）以及自定义 `app`、`env` 字段，符合 Logstash 标准 schema。
- **敏感信息**：日志中避免直接输出用户隐私数据；安全相关告警（如 SafetyAgent 高风险关键词命中）仅记录 level/category 等元数据。
- **外部依赖日志控制**：通过 `logback-spring.xml` 中的 `<logger>` 标签对 `org.springframework.ai`、`org.apache.ibatis` 等第三方包单独设置级别，避免污染主业务日志。
- **部署集成**：生产容器 stdout 输出 JSON 日志，配合 deploy 目录下的 Prometheus/Grafana 监控栈，实现日志与指标的统一可观测性。