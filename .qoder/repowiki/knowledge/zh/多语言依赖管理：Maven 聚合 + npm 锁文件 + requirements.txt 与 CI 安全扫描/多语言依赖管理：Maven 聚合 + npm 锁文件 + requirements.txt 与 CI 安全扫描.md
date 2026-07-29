---
kind: dependency_management
name: 多语言依赖管理：Maven 聚合 + npm 锁文件 + requirements.txt 与 CI 安全扫描
category: dependency_management
scope:
    - '**'
source_files:
    - backend/pom.xml
    - backend/counseling-app/pom.xml
    - backend/counseling-ai/pom.xml
    - frontend/student-h5/package.json
    - frontend/teacher-web/package.json
    - frontend/parent-h5/package.json
    - backend/tts-service/requirements.txt
    - backend/voice-service/requirements.txt
    - .github/workflows/ci.yml
    - .github/dependency-check-suppressions.xml
---

本仓库采用多语言、多模块的依赖管理策略，后端 Java 使用 Maven 聚合工程统一管理版本，前端各子项目使用 npm + package-lock.json 锁定依赖，Python 微服务通过 requirements.txt 声明最低版本，并通过 GitHub Actions 集成 OWASP 依赖漏洞扫描。

### 1. 后端（Java）— Maven 聚合工程
- **聚合根 POM**：`backend/pom.xml` 继承 `spring-boot-starter-parent:3.4.1`，定义 `<modules>` 聚合 counseling-common/tenant/domain/ai/service/api/app 七个子模块。
- **集中版本管理**：通过 `<dependencyManagement>` 统一声明 Spring AI BOM、MyBatis-Plus、SpringDoc、JWT、pgvector 等第三方库版本，子模块仅引用 groupId/artifactId 不写 version。
- **内部模块版本**：所有 `com.mindsafe:*` 内部模块均通过 `${project.version}` 引用，保证多模块版本一致。
- **构建插件**：Surefire（单元测试）、Failsafe（集成测试 *IT.java）、JaCoCo（覆盖率，门禁阈值 30% 逐步提升至 80%）在父 POM 中统一配置。
- **运行时依赖**：counseling-app 聚合所有业务模块，并引入 PostgreSQL、Flyway、Actuator、Prometheus、Logstash 等运行时依赖。
- **AI 模块依赖**：counseling-ai 模块通过 Spring AI Starter（OpenAI 兼容协议）接入多种 LLM，并使用 pgvector 向量存储与 Redis 缓存。

### 2. 前端（JavaScript/TypeScript）— npm + package-lock.json
- **三个独立前端项目**：student-h5、teacher-web、parent-h5 各自维护独立的 `package.json` 和 `package-lock.json`。
- **包管理器**：统一使用 npm（CI 中通过 `npm ci` 安装），Node 版本固定为 22。
- **依赖锁定**：每个前端项目都提交 `package-lock.json`，确保构建可重现。
- **技术栈差异**：student-h5 使用 React 19 + Tailwind CSS + Vite PWA；teacher-web 使用 Ant Design + ECharts；parent-h5 相对精简。

### 3. Python 微服务 — requirements.txt
- **tts-service** 和 **voice-service** 分别使用 `requirements.txt` 声明依赖，采用 `>=` 语义化版本约束（如 `fastapi>=0.104.0`、`torch>=2.0.0`）。
- **语音能力**：tts-service 支持 CosyVoice2 或 edge-tts 降级方案；voice-service 基于 funasr + modelscope 实现语音识别。

### 4. CI/CD 中的依赖管理
- **GitHub Actions**（`.github/workflows/ci.yml`）：
  - Maven 构建缓存：`cache: 'maven'`
  - Node 构建缓存：`cache: 'npm'`，指定 `cache-dependency-path` 指向各项目的 `package-lock.json`
  - 集成测试使用 Testcontainers 拉起 PostgreSQL(pgvector) + Redis
- **安全扫描**：OWASP Dependency Check 在 CI 中运行，CVSS ≥ 9 阻断构建，支持 `.github/dependency-check-suppressions.xml` 抑制误报。

### 5. 约定与约束
- **Maven 子模块不得自行声明第三方依赖版本**，必须通过父 POM 的 `<dependencyManagement>` 统一管理。
- **前端项目必须提交 package-lock.json**，CI 使用 `npm ci` 而非 `npm install` 保证可重现构建。
- **Python 微服务使用宽松版本约束**（`>=`），便于自动升级但可能带来兼容性风险。
- **无私有仓库配置**：未发现 `.m2/settings.xml`、`~/.gradle/gradle.properties`、`pip.conf` 等私有源配置，默认使用中央仓库。
- **无依赖锁定文件用于 Java**：Maven 未生成 `pom.lock` 或类似文件，依赖解析依赖本地 Maven 缓存。