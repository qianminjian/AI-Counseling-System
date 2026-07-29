---
kind: configuration_system
name: Spring Boot 多环境配置体系（YAML + 环境变量 + Docker Compose）
category: configuration_system
scope:
    - '**'
source_files:
    - backend/counseling-app/src/main/resources/application.yml
    - backend/counseling-app/src/test/resources/application-test.yml
    - backend/counseling-app/src/main/resources/logback-spring.xml
    - backend/.env
    - deploy/.env.example
    - deploy/docker-compose.yml
    - backend/counseling-ai/src/main/java/com/mindsafe/ai/config/AiConfig.java
    - backend/counseling-api/src/main/java/com/mindsafe/api/config/SecurityConfig.java
---

本系统采用 Spring Boot 标准配置机制，结合 YAML 配置文件、环境变量注入与 Docker Compose 环境变量传递，形成分层清晰的运行时配置体系。

**1. 配置来源与加载顺序**
- 核心配置文件：`backend/counseling-app/src/main/resources/application.yml`，定义所有业务与中间件默认值
- 测试覆盖配置：`backend/counseling-app/src/test/resources/application-test.yml`，通过 Testcontainers 动态替换数据源与 Redis
- 环境变量：`backend/.env`（本地开发，已 gitignore）、`deploy/.env.example`（部署模板），通过 `${VAR:default}` 语法在 application.yml 中引用
- Docker Compose 环境变量：`deploy/docker-compose.yml` 将容器环境变量映射为 `SPRING_DATASOURCE_*`、`JWT_SECRET` 等 Spring 标准属性

**2. 配置分组与约定**
- `spring.*`：框架配置（datasource、redis、ai、flyway、management、logging、springdoc）
- `mybatis-plus.*`：ORM 配置（类型处理器、驼峰映射、逻辑删除）
- `mindsafe.*`：业务配置（AI 降级、JWT、CORS、安全策略、语音/TTS/短信服务）
- `wecom.*`：企业微信 OAuth（可选集成）
- 日志：`logback-spring.xml` 通过 `<springProfile>` 区分 default/test（彩色控制台）与 prod（JSON Logstash 格式）

**3. 配置绑定方式**
- `@Value("${...}")`：在 `AiConfig`、`SecurityConfig`、`WeComOAuthController` 等类中直接注入单个属性
- Spring AI 自动装配：`spring.ai.openai.*` 由 Spring Boot 自动绑定到 OpenAiChatModel
- 未使用 `@ConfigurationProperties`，全部通过 `@Value` 逐字段注入

**4. 关键设计决策**
- 敏感信息一律通过环境变量注入（`${DB_PASSWORD:mindsafe_dev}` 形式），禁止硬编码
- 生产环境 JWT secret 为空时 fail-fast（注释明确说明）
- CORS 允许域名支持逗号分隔的多值，便于多前端环境共存
- AI 模型支持主备双写降级（`mindsafe.ai.fallback.enabled` 开关），失败自动切换通义千问
- 数据库迁移通过 Flyway 管理，启用 `baseline-on-migrate` 兼容已有库
- Actuator 暴露 health/info/metrics/prometheus，Prometheus 指标带 application 标签

**5. 环境隔离**
- 开发：application.yml 默认值 + `.env` 覆盖
- 测试：application-test.yml + Testcontainers 动态容器
- 部署：docker-compose.yml 通过 environment 注入，Nginx 反向代理统一端口 80