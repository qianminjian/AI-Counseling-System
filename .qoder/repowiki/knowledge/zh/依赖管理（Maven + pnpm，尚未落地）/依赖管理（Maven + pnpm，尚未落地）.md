---
kind: dependency_management
name: 依赖管理（Maven + pnpm，尚未落地）
slug: dependency_management
category: dependency_management
scope:
    - '**'
---

当前仓库处于「设计文档 + PRD 生成脚本」阶段，后端与前端源码目录 `backend/`、`apps/` 均为空占位，尚未出现任何依赖声明文件。已确认的依赖管理规划如下：

- **后端**：Java 21 + Spring Boot 3 + Spring AI，采用 Maven 多模块单体架构，parent pom 约定在 `backend/pom.xml`，通过 `<dependencyManagement>` 集中锁定版本，各子模块仅声明坐标不写版本。
- **前端**：React 18 + TypeScript + Vite + Tailwind，使用 pnpm workspace，每个应用独立 `package.json`，共享配置放 `apps/shared/`（YAGNI 暂不建）。
- **构建产物**：`node_modules/`、`dist/`、`.next/`、`target/`、`.m2/` 等均已写入 `.gitignore`，不会被提交。
- **私有化部署**：STRUCTURE.md §4 明确「密钥红线」——LLM API Key 走环境变量，禁止硬编码；`.env` 类文件 gitignore 拦截。

由于实际代码尚未创建，目前不存在 lockfile、vendor 目录、私有仓库配置或 CI 依赖缓存策略，属于「规划中、未实现」状态。后续开发启动后应在 `backend/pom.xml` 和 `apps/*/package.json` 中补齐上述约定。