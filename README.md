# AI 小学生心理辅导系统

面向小学的 AI 心理辅导 SaaS 系统：学生端 AI 对话（CBT 流程 + 风险识别）+ 老师后台预警，多学校数据隔离。

## 当前状态

**MVP 开发阶段**：学生端 H5 核心功能已实现（AI 对话、声纹登录、语音唤醒、情绪日记），后端 API 服务已部署 SIT 环境。

项目设计决策与状态见 [design/BEACON.md](design/BEACON.md)。

## 技术栈

| 层 | 技术 |
|------|------|
| 后端 | Java 21 + Spring Boot 3 + MyBatis-Plus + PostgreSQL 16 (pgvector) + Redis 7 |
| 前端 | React 19 + Vite + Tailwind CSS |
| AI | Spring AI + Transformers.js（端侧声纹/唤醒词） |
| 部署 | Docker Compose + Nginx |

## 目录导航

| 目录 | 内容 |
|------|------|
| [backend/](backend/) | 后端源代码（Maven 多模块：common/domain/ai/service/api/app） |
| [frontend/](frontend/) | 前端应用组（student-h5 / teacher-web / parent-h5） |
| [design/](design/) | 设计文档（01~58）+ BEACON 项目明灯 + frozen/（34/38-43/58 冻结区） |
| [deploy/](deploy/) | 部署配置（Docker Compose、Nginx、监控） |
| [doc/](doc/) | 归档层（原始 docx + 早期探索产物） |
| [scripts/](scripts/) | 工具脚本 |
| [tests/](tests/) | 跨模块测试（e2e / integration / performance） |

## 快速开始

```bash
# 基础设施（PG + Redis）
cd deploy && docker compose up -d

# 后端
cd backend && mvn spring-boot:run -pl counseling-app

# 学生端前端
cd frontend/student-h5 && npm install && npm run dev
```

启动任何服务前遵守端口检查红线（AGENTS.md §6）。

## 约定

- 目录结构与使用规则：[STRUCTURE.md](STRUCTURE.md)
- Agent 工作规则：`AGENTS.md` + `.qoder/rules/`
- 设计文档与代码一致（底线规则）：改代码必同步文档、改文档必核对代码
