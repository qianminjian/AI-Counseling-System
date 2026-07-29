---
kind: build_system
name: 构建与部署体系：Maven 多模块 + Docker Compose + GitHub Actions CI
category: build_system
scope:
    - '**'
source_files:
    - backend/pom.xml
    - backend/counseling-app/pom.xml
    - backend/Dockerfile
    - frontend/Dockerfile
    - deploy/docker-compose.yml
    - deploy/docker-compose.prod.yml
    - .github/workflows/ci.yml
    - deploy.sh
---

## 1. 构建系统概览

本项目采用**分层构建策略**，后端基于 Maven 多模块聚合（Spring Boot 3.4.1 + Java 21），前端使用 Vite + Node 22，容器化通过 Docker Compose 编排，CI/CD 由 GitHub Actions 驱动。

### 后端构建（Maven 多模块）
- **聚合 POM**：`backend/pom.xml` 定义 `counseling-parent` 根模块，统一管理 7 个子模块版本与依赖
- **模块划分**：`counseling-common` → `counseling-domain` → `counseling-service` → `counseling-ai` → `counseling-api` → `counseling-app`（启动入口）
- **打包产物**：`counseling-app` 通过 Spring Boot Maven Plugin 生成 fat JAR，作为唯一可执行镜像内容
- **测试分层**：Surefire（`*Test.java` 单元测试）+ Failsafe（`*IT.java` 集成测试，使用 Testcontainers 拉起 PostgreSQL + Redis）
- **覆盖率门禁**：JaCoCo 插件在 test 阶段生成报告，CI 中解析 CSV 汇总各模块覆盖率

### 前端构建（Vite + npm）
- 三个独立前端应用：`student-h5`、`teacher-web`、`parent-h5`，各自维护 `package.json` 与 `vite.config.js`
- 构建命令统一为 `npm run build`，输出到各自 `dist/` 目录
- CI 中分别对 student-h5 和 teacher-web 执行 `npm ci` + `npm run build`

### 容器化构建
- **后端多阶段镜像**：`backend/Dockerfile` 第一阶段用 `maven:3.9-eclipse-temurin-21-alpine` 拉取依赖并构建，第二阶段用 `eclipse-temurin:21-jre-alpine` 仅运行 JRE
- **前端静态资源镜像**：`frontend/Dockerfile` 用 `node:22-alpine` 构建后输出到 `alpine:3.20` 镜像的 `/app/student` 和 `/app/teacher` 目录
- **Python 微服务**：`voice-service` 和 `tts-service` 各有独立 `Dockerfile`，通过 `docker-compose.prod.yml` 编排

## 2. 核心配置文件

| 文件 | 作用 |
|------|------|
| `backend/pom.xml` | Maven 聚合根，声明所有子模块、公共依赖版本、插件配置 |
| `backend/counseling-app/pom.xml` | 启动模块，聚合所有业务依赖，引入 Flyway、Actuator、Prometheus |
| `deploy/docker-compose.yml` | 开发环境一键启动（PostgreSQL + Backend + Frontend + Nginx） |
| `deploy/docker-compose.prod.yml` | 生产环境编排，含 Redis、pgvector、Nginx、健康检查、资源限制 |
| `.github/workflows/ci.yml` | GitHub Actions 流水线：编译、测试、E2E 冒烟、依赖漏洞扫描 |
| `deploy.sh` | 本地生产部署脚本：Git 校验 → 构建 → rsync 上传 → SSH 重启容器 |

## 3. 架构与约定

### 模块依赖方向（单向依赖）
```
counseling-common ← counseling-domain ← counseling-service ← counseling-ai
    ↓
counseling-api ← counseling-app（启动入口）
```
- 领域层（domain）仅依赖 common，不感知上层 API
- AI 能力封装在独立模块，通过 service 层暴露
- API 层仅负责 HTTP/WebSocket 路由与 DTO 转换

### 数据库迁移策略
- 使用 Flyway 管理 SQL 迁移，脚本位于 `counseling-app/src/main/resources/db/migration/`，按 `V__描述.sql` 命名
- 初始化扩展（uuid-ossp、pgcrypto）通过 `deploy/init/pg-init.sql` 在 Flyway 之前执行

### 环境变量管理
- 开发环境：`.env` 文件 + docker-compose 默认值（如 `${DB_PASSWORD:-mindsafe2026}`）
- 生产环境：`.env.example` 模板 + 服务器端 `.env` 文件，敏感信息通过环境变量注入

## 4. 约束与规范

### 强制约束（代码级或 CI 级）
- **Java 版本**：`java.version=21`，所有构建必须使用 JDK 21（Temurin 发行版）
- **测试命名**：单元测试必须以 `*Test.java` 结尾，集成测试以 `*IT.java` 结尾，由 Surefire/Failsafe 自动识别
- **覆盖率门槛**：`jacoco.line.coverage=0.30`（当前 30%，目标逐步提升至 80%）
- **依赖安全**：OWASP Dependency Check 在 CI 中对 CVSS ≥ 9 的漏洞直接失败（`-DfailBuildOnCVSS=9`）
- **部署前置条件**：`deploy.sh` 要求工作区干净且已 push 到 origin/main，否则拒绝部署

### 推荐实践（约定俗成）
- 子模块 POM 继承父 POM 的 `<dependencyManagement>`，不重复声明版本号
- 生产镜像遵循最小化原则：JRE 运行时 + 单一 JAR，无额外工具链
- 容器健康检查统一通过 Actuator `/actuator/health` 端点验证
- 前端构建产物通过 Nginx 反向代理分发到不同路径（`/`、`/teacher`、`/parent`）

## 5. CI/CD 流水线

GitHub Actions 触发条件：push/PR to `main` 分支，包含以下并行任务：
1. **Backend Unit Tests**：Maven verify（跳过 IT），生成 JaCoCo 报告
2. **Backend Integration Tests**：Testcontainers 自管 PostgreSQL(pgvector) + Redis
3. **E2E Smoke Test**：启动真实后端 + 冒烟脚本，调用真实 LLM（需 Secrets）
4. **Frontend Builds**：student-h5 与 teacher-web 独立构建
5. **Dependency Scan**：OWASP 依赖漏洞扫描，报告上传为 Artifact

## 6. 部署流程

### 开发环境
```bash
docker compose -f deploy/docker-compose.yml up -d --build
# 访问：http://localhost（学生端）、http://localhost/teacher（教师端）
```

### 生产部署（本地触发）
```bash
./deploy.sh
# 自动执行：Git 校验 → Maven 构建 → npm 构建 → rsync 上传 → SSH 重启容器 → 健康检查
```

### 生产环境（服务器直接）
```bash
cp .env.example .env && vim .env
docker compose -f docker-compose.prod.yml up -d --build
```
