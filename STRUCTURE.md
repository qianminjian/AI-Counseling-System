# STRUCTURE.md - 项目目录结构约定

> 本文件是项目目录结构的**单一事实源**（Single Source of Truth）。
> 新增目录或改变目录用途前，先修改本文件，再动目录（约束先行）。
> 创建：2026-07-23 | 适用范围：AI-Counseling-System 项目根目录

---

## 1. 目录总览

```
AI-Counseling-System/
├── AGENTS.md               # [同步区] Qoder 规则入口，由 sync-rules.sh 中央库同步
├── .qoder/rules/           # [同步区] 规则库，由 sync-rules.sh 中央库同步
├── sync-rules.sh           # 规则同步脚本
├── STRUCTURE.md            # 本文件：目录结构约定
├── README.md               # 项目入口导航
│
├── design/                 # 设计层：BEACON.md + 总览/跟踪表 + 01~17 md 设计文档（单一事实源）
├── doc/                    # 归档层（只读留档，冲突以 design/*.md 为准）
│   ├── 01~15_*.docx        #   设计文档原始 docx
│   └── his/                #   早期需求/探索产物（原 prd/ + prompts/，已无活跃用途）
├── scripts/                # 工具脚本：文档生成、数据处理等一次性/辅助脚本
│
├── backend/                # 后端源代码（Java 21 + Spring Boot 3 + Spring AI，Maven 多模块，详见 §2.5）
├── apps/                   # 前端应用组（React + TS + Vite，详见 §2.6）
├── tests/                  # 跨模块测试目录（Java 单元测试在各模块 src/test/ 内，详见 §2.7）
│   ├── unit/               #   跨模块单元测试（备用；常规单元测试在模块内）
│   ├── integration/        #   集成测试：跨模块、含 DB/API/LLM 调用
│   └── e2e/                #   端到端测试：完整用户场景
│
└── tmp/                    # 唯一临时目录（gitignore，不追踪）
```

---

## 2. 各目录职责与规则

### 2.1 `doc/his/` — 早期需求与探索产物归档

- **来源**：原 `prd/`（需求初稿、3 版建设方案、架构图）+ `prompts/`（探索期 prompt 记录），2026-07-23 归档
- **性质**：只读留档，内容已被 `design/*.md` 完全取代，无活跃用途
- **现状文件**：
  - `AI心理辅导系统.docx` — 最初方案初稿（2026-05-29）
  - `AI小学生心理辅导系统建设方案.docx` / `_20260529-2019.docx` / `AI心理辅导系统建设方案_20260529-2106.docx` — 3 版建设方案（md5 不同，保留溯源）
  - `AI心理辅导系统_Prompt体系详细设计.docx` / `_CBT对话流程树设计.docx` / `架构图.png` — 前期探索成果
  - `20260529-初始探索prompt记录.md` — 探索期需求输入与 15 子主题任务拆解

### 2.2 `design/` — 设计层文档（对应 design-persistence.md 规则）

- **存什么**：
  - `BEACON.md` — 项目明灯文件（设计决策、范围、当前状态），**唯一强制**
  - `DESIGN-OVERVIEW.md` — 15 份设计文档总览入口（目录导航 + 关键摘要）
  - `TASK-TRACKER.md` — 任务跟踪表（文档整合任务 + MVP 开发任务 + 决策/风险/里程碑）
  - `01_*.md` ~ `17_*.md` — **当前设计文档（md 格式，单一事实源）**，由 `doc/*.docx` 整合而来，直接平铺在 design/ 根下；16/17 为新增（API 接口 + 前端架构）
  - `*-PLAN.md` — Plan Mode 产出的执行计划（按 design-persistence.md §6 硬性要求）
  - `discussion/`（可选）多轮设计讨论摘要；`decisions/`（可选）ADR；`reference/`（可选）外部参考
- **命名约定**：新增正式设计文档在 `design/` 根下沿用 `NN_主题.md` 编号制，接着 15 往后排
- **文档权威性**：以 `design/*.md` 为准；原始 docx 存于项目根 `doc/`，仅作溯源，二者冲突时以 md 为准（md 已按 Java 技术栈决策改写）
- **读取时机**：任何新任务启动前，先读 `BEACON.md`；查设计细节先读 `DESIGN-OVERVIEW.md` 导航

### 2.2.1 `doc/` — 原始 docx 归档

- **存什么**：15 份 `NN_主题.docx` 只读留档，供追溯早期原文
- **不存什么**：新版设计文档（去 `design/`）、临时导出（去 `tmp/`）
- **权威性**：低于 `design/*.md`；若发现 md 与 docx 有实质差异，以 md 为准（转换时已按 Java 技术栈重写）

### 2.3 （已删除，原 `prompts/` 归档至 `doc/his/`）

### 2.4 `scripts/` — 工具脚本

- **存什么**：docx 生成脚本（create_*.js/py）、数据迁移、一次性批处理
- **规则**：脚本必须自包含可独立运行；涉及写操作的脚本默认输出到 `tmp/` 预览，确认后再落正式位置
- **现状文件**：7 个 docx 生成脚本（design/ 编号文档的生成器，保留作再生成能力）

### 2.5 `backend/` — 后端源代码（Java / Maven 多模块）

技术基线：Java 21（LTS，虚拟线程利好 AI 流式响应）+ Spring Boot 3 + Spring AI + MyBatis-Plus + PostgreSQL 16（pgvector）+ Redis 7。
构建用 **Maven** 多模块，parent pom 在 `backend/pom.xml`。

```
backend/
├── pom.xml                     # parent：依赖版本管理、模块聚合
├── counseling-common/          # 通用：配置、工具、异常、常量、统一响应
├── counseling-tenant/          # 多租户：Schema 路由（AbstractRoutingDataSource）、租户上下文
├── counseling-domain/          # 领域模型：实体、MyBatis Mapper（落地 design/06 的 DDL）
├── counseling-ai/              # AI 编排：Spring AI、7 Agent、CBT 状态机、风险识别、prompt 模板
├── counseling-service/         # 业务服务：对话编排、预警通知、档案
├── counseling-api/             # Web 层：Controller、DTO、Spring Security + JWT
└── counseling-app/             # 启动模块：Spring Boot 主类、配置、打包（fat jar / Docker）
```

- **架构形态**：模块化单体（不建微服务，决策 #8）；Maven 模块边界即领域边界，未来可拆
- **多租户**：Schema 级隔离（`tenant_{tenant_id}`，按 design/07），动态数据源路由在 `counseling-tenant/`
- **LLM 接入**：供应商无关，Spring AI 配置驱动（决策 #7），任意国产合规 LLM 可接入，主备降级按 design/13 §6.2
- **Agent 编排**：Spring AI ChatClient + Advisor 链 + 状态机（决策 #9），7 Agent 职责按 design/13
- **测试**：各模块 `src/test/java`（JUnit 5 + Mockito），集成测试用 Testcontainers（PG/Redis）

### 2.6 `apps/` — 前端应用组

技术基线：React 18 + TypeScript + Vite + Tailwind CSS；包管理 **pnpm**（workspace）。

```
apps/
├── student/        # 学生端 H5（M1）：儿童友好 UI，自绘轻组件
└── teacher/        # 教师后台 Web（M2）：Ant Design
```

- 每个应用独立 `package.json`，共享配置放 `apps/shared/`（需要时再建，YAGNI）
- 前端测试就近放置（`*.test.ts`），Vitest 运行

### 2.7 测试约定

- **后端单元测试**：在各 Maven 模块的 `src/test/java`，镜像主代码包结构（主位置）
  - 单元测试：快（毫秒级）、无 IO、无网络、LLM 用 mock（JUnit 5 + Mockito）
  - 集成测试：允许真实 DB/Redis（Testcontainers），LLM 用录制/桩
- **跨模块测试**：根 `tests/`（`integration/` 跨模块集成、`e2e/` 端到端完整场景）；`tests/unit/` 仅作跨模块单元测试备用
- **前端测试**：就近放置（`*.test.ts`），Vitest 运行
- TDD 工作流见 `.qoder/rules/tdd-workflow.md`（@tdd-workflow）

### 2.8 `tmp/` — 唯一临时目录

- **铁律**：本项目所有临时产物（调试输出、脚本预览、下载暂存、实验笔记草稿）**只允许**放这里
- 已 gitignore（`tmp/*`，保留 `.gitkeep`），内容随时可清空
- 不允许在项目根或桌面等处散落临时文件；脚本默认输出路径应指向 `tmp/`

---

## 3. 同步区边界（重要）

| 路径 | 性质 | 规则 |
|------|------|------|
| `AGENTS.md`、`.qoder/rules/` | 中央库同步区 | **禁止写入项目特有内容**，会被 sync-rules.sh 覆盖/告警；改规则去中央库改 |
| `STRUCTURE.md`、`README.md`、`design/`、`doc/`（含 `doc/his/`）、`scripts/`、`backend/`、`apps/`、`tests/`、`tmp/` | 项目自留区 | 项目特有内容全部放这里 |

---

## 4. 通用约定

1. **命名**：目录一律小写；中文文档可用中文名 + 编号前缀；代码文件随技术栈惯例
2. **macOS 注意**：本机 APFS 大小写不敏感，避免仅靠大小写区分的命名；`.DS_Store` 会被 Finder 反复重建，已 gitignore，不必追删
3. **iCloud 注意**：项目位于 `~/Documents`（iCloud「桌面与文稿」同步范围），大文件/高频写入目录（如未来的 `node_modules`、`.venv`）已被 gitignore 且建议关注同步流量
4. **密钥红线**：`.env` 等密钥文件禁止入库（gitignore 已挡），示例配置用 `.env.example`；LLM API Key 一律走环境变量，禁止硬编码
5. **变更流程**：调整目录结构 → 先改本文件 → 再动目录 → commit 中说明
6. **技术栈基线**（2026-07-23 定，详见 design/BEACON.md 决策 #5/#8/#9）：后端 Java 21 + Spring Boot 3 + Spring AI + MyBatis-Plus（Maven 多模块，模块化单体）；前端 React 18 + TS + Vite + Tailwind；数据库 PostgreSQL 16 + pgvector（信创期评估达梦/人大金仓）；缓存 Redis 7；构建 Maven + pnpm。**YAGNI 清单**（MVP 禁止引入）：K8s、Kafka、微服务拆分、API 网关、ELK、Milvus、语音/生物识别组件

---

## 5. 变更日志

| 日期 | 变更 | 原因 |
|------|------|------|
| 2026-07-23 | 初始版本 | 项目从纯文档探索阶段转入开发准备阶段，建立统一结构 |
| 2026-07-23 | 技术栈由 Python 改为 Java：`src/`→`backend/`（Maven 多模块），测试约定改为模块内 `src/test/` + 跨模块 `tests/`，YAGNI 清单更新 | 钱敏健决策私有化/信创主营，直接上 Java（Spring Boot + Spring AI） |
| 2026-07-23 | 技术栈落定：`src/` 后端结构、`apps/` 前端组、YAGNI 清单 | 钱敏健确认 Schema 级隔离 + LLM 供应商无关化方案 |
| 2026-07-23 | 文档整合完成：15 份 docx 全部转为 `design/docs/*.md`，原 docx 归档 `design/his/`，§2.2 更新 docs/his 拆分约定 | M0 文档整合里程碑收尾 |
| 2026-07-23 | 目录结构纠偏：`design/docs/*.md` 拍平到 `design/`；`design/his/` 迁至项目根 `doc/`；§1/§2.2 同步 | 对齐钱敏健原意（md 直接在 design 下、docx 在 doc 下），消除多余中间层 |
| 2026-07-23 | `prd/` + `prompts/` 归档至 `doc/his/`，删除原目录；§1/§2.1/§2.3/§3/§4 同步 | 内容已被 design/*.md 完全取代，无活跃用途，统一归入只读留档 |
