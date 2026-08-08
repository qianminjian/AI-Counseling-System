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
├── design/                 # 设计层：BEACON.md + 总览/跟踪表 + 12 份合并设计文档（单一事实源）+ doing/（进行中子设计文档）+ his/（历史归档）+ frozen/（34/38-43/58 冻结区）
├── doc/                    # 历史物料归档层（只读留档，全部在 doc/his/，冲突以 design/*.md 为准）
│   └── his/                #   全量历史物料：15 份原始 docx + 早期需求/探索产物 + 警示说明
├── scripts/                # 工具脚本：文档生成、数据处理等一次性/辅助脚本
│
├── backend/                # 后端源代码（Java 21 + Spring Boot 3 + Spring AI，Maven 多模块，详见 §2.5）
├── frontend/               # 前端应用组（React + Vite，详见 §2.6）
├── tests/                  # 跨模块测试（常规测试在模块/应用内，详见 §2.7）
│   ├── unit/               #   跨模块单元测试（备用；常规单元测试在模块内）
│   ├── integration/        #   集成测试：跨模块、含 DB/API/LLM 调用
│   └── e2e/                #   端到端测试：Playwright，完整用户场景
│
├── deploy/                 # 部署配置：Docker Compose、生产编排、基础设施初始化（详见 §2.9）
├── data/                   # 内容资产：知识库语料等待审/已审内容（详见 §2.14）
├── reports/                # 报告输出目录（gitignore：测试/覆盖率/E2E 汇总报告，详见 §2.11）
└── tmp/                    # 唯一临时目录（gitignore，不追踪，详见 §2.13）
```

---

## 2. 各目录职责与规则

### 2.1 `doc/his/` — 全量历史物料归档

- **来源**：① 原 `prd/`（需求初稿、3 版建设方案、架构图）+ `prompts/`（探索期 prompt 记录），2026-07-23 归档；② 15 份原始 docx（`01~15_*.docx`）+ 归档区警示说明 `README.md`，2026-07-29 归档（doc/ 根目录清空，仅存 his/）
- **性质**：只读留档（废弃，不再维护），内容已被 `design/*.md` 完全取代，无活跃用途
- **现状文件**：
  - `AI心理辅导系统.docx` — 最初方案初稿（2026-05-29）
  - `AI小学生心理辅导系统建设方案.docx` / `_20260529-2019.docx` / `AI心理辅导系统建设方案_20260529-2106.docx` — 3 版建设方案（md5 不同，保留溯源）
  - `AI心理辅导系统_Prompt体系详细设计.docx` / `_CBT对话流程树设计.docx` / `架构图.png` — 前期探索成果
  - `20260529-初始探索prompt记录.md` — 探索期需求输入与 15 子主题任务拆解

### 2.2 `design/` — 设计层文档（对应 design-persistence.md 规则）

- **存什么**：
  - `BEACON.md` — 项目明灯文件（设计决策、范围、当前状态），**唯一强制**
  - `DESIGN-OVERVIEW.md` — 设计文档总览 v5.1（12 份合并文档目录导航 + 旧编号对照表 + 关键摘要 + doing 区说明）
  - `TASK-TRACKER.md` — 任务跟踪表（文档整合任务 + MVP 开发任务 + 决策/风险/里程碑）
  - `01_系统概述与产品功能说明.md` ~ `12_家长端功能详细设计.md` — **12 份合并设计文档（md 格式，当前单一事实源）**：01 概述/02 数据库/03 技术架构/04 部署/05 测试/06 配置与外部服务/07 商业化合规/08 概要设计（三端+接口）/09 学生端上卷（对话引擎与安全）/10 学生端下卷（个性化与情感交互）/11 老师端/12 家长端；每份含合并来源标注与任务归口总表
  - `his/` — **历史设计文档归档**（50 份旧编号文档 + 已合并的 doing 子文档，只读溯源，编号对照见 DESIGN-OVERVIEW §二）
  - `doing/` — **进行中子设计文档**（独立主题文档，编号接续 01-57 如 `58_xxx.md`；开发期只迭代本区，12 份主文档冻结；开发完成由开发人员主动发起「合并设计文档」，仅并入最终态后归档 his，规则见 `.qoder/rules/design-document-management.md`）
  - `frozen/` — **冻结区**（真正冻结的远期任务，触发条件未到不实施、不合并：34/38-43/58，待后续开发时整合）
  - `*-PLAN.md` — Plan Mode 产出的执行计划（按 design-persistence.md §6 硬性要求）
  - `demo/` — 交互原型（独立 HTML Demo，供设计选型/现场预览，不参与前端构建，与旧 design/19 界面详设配套）
  - `discussion/`（可选）多轮设计讨论摘要；`decisions/`（可选）ADR；`reference/`（可选）外部参考
- **命名约定（doing 工作流，2026-08-05 起）**：独立主题设计文档一律生成在 `design/doing/`，沿用 `NN_主题.md` 编号制、接续旧编号（58 起）；开发迭代只改 doing 子文档或新增子文档，**不允许修改 12 份主设计文档**；开发完成由开发人员主动发起「合并设计文档」→ 按归属并入 12 份主文档（仅最终态）→ doing 归档 `design/his/`
- **文档权威性**：以 12 份合并 `design/*.md` 为准；历史文档（`design/his/`）与原始 docx（`doc/his/`）仅作溯源，冲突时以合并文档为准
- **读取时机**：任何新任务启动前，先读 `BEACON.md`；查设计细节先读 `DESIGN-OVERVIEW.md` 导航

### 2.2.1 `doc/` — 历史物料归档（已并入 `doc/his/`）

- **存什么**：15 份 `NN_主题.docx` + 警示说明 `README.md`（COMP-010），2026-07-29 已全部移入 `doc/his/`；doc/ 根目录仅保留 `his/` 子目录
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
├── counseling-domain/          # 领域模型：实体、MyBatis Mapper（落地 design/02 的 DDL）
├── counseling-ai/              # AI 编排：Spring AI、Agent、CBT 状态机、风险识别、prompt 模板
├── counseling-service/         # 业务服务：对话编排、预警通知、档案
├── counseling-api/             # Web 层：Controller、DTO、Spring Security + JWT、WebSocket
├── counseling-app/             # 启动模块：Spring Boot 主类、配置、打包（fat jar / Docker）
├── tts-service/                # TTS 微服务（Python，可选）
└── voice-service/              # 语音情感分析微服务（Python，可选）
```

- **架构形态**：模块化单体（不建微服务，决策 #8）；Maven 模块边界即领域边界，未来可拆
- **多租户**：当前通过 tenant_id 字段级隔离（行级），Schema 级隔离待后续升级
- **LLM 接入**：供应商无关，Spring AI 配置驱动（决策 #7），任意国产合规 LLM 可接入
- **Agent 编排**：Spring AI ChatClient + Advisor 链 + 状态机（决策 #9），7 Agent 职责按 design/03 §4
- **测试**：各模块 `src/test/java`（JUnit 5 + Mockito），集成测试用 Testcontainers（PG/Redis），命名与管理规则见 §2.7
- **DB 脚本**：`backend/scripts/sql/`，Flyway 版本化命名（`V{n}__desc.sql`），详见 §2.10
- **Prompt 资源**：`counseling-ai/src/main/resources/prompts/`（classpath 加载，与 design/09 一一对应），详见 §2.12

### 2.6 `frontend/` — 前端应用组

技术基线：React 18 + Vite + Tailwind CSS；包管理 **npm**（各应用独立）。

```
frontend/
├── student-h5/     # 学生端 H5（M1）：儿童友好 UI，自绘轻组件
├── teacher-web/    # 教师后台 Web（M2）：数据看板 + 预警管理
├── parent-h5/      # 家长端 H5：家庭报告 + 亲子建议
└── Dockerfile      # 前端统一构建镜像
```

- 每个应用独立 `package.json`，独立构建
- 前端测试就近放置（`src/test/`），Vitest 运行

### 2.7 测试约定（命名 / 目录 / 管理）

#### 2.7.1 测试金字塔与位置

| 层级 | 位置 | 框架 | 运行时机 |
|------|------|------|----------|
| 单元测试 | 各模块 `src/test/java/` | JUnit 5 + Mockito | 每次 commit 前 |
| 集成测试 | 各模块 `src/test/java/`（`*IT.java` 后缀区分） | Testcontainers + Spring Boot Test | merge 前 / CI |
| E2E | `tests/e2e/` | Playwright | 发布前 |
| 前端单元/组件 | `frontend/*/src/test/`（就近放置） | Vitest + React Testing Library | 每次 commit 前 |

#### 2.7.2 后端测试命名规则

| 类型 | 文件命名 | 示例 |
|------|----------|------|
| 单元测试 | `{被测类名}Test.java` | `SafetyAdvisorTest.java` |
| 集成测试 | `{被测类名}IT.java` | `TenantRoutingIT.java` |
| 测试方法 | `should_{预期行为}_when_{条件}()` | `should_returnSafetyResponse_when_L5Detected()` |
| 测试 Fixture | `src/test/resources/fixtures/{module}/` | `fixtures/risk/l5_suicide_plan.json` |

- 包结构镜像主代码：主 `com.mindsafe.ai.advisor.SafetyAdvisor` → 测 `com.mindsafe.ai.advisor.SafetyAdvisorTest`
- 测试资源目录：`src/test/resources/fixtures/`（JSON/SQL 测试数据）、`src/test/resources/prompts/`（Prompt 测试变体）

#### 2.7.3 测试运行与报告

| 命令 | 作用 | 报告输出 |
|------|------|----------|
| `mvn test` | 单元测试（surefire，匹配 `*Test`） | `target/surefire-reports/` |
| `mvn verify` | 单元 + 集成（failsafe，匹配 `*IT`） | `target/failsafe-reports/` |
| `mvn test -Dtest=XxxTest` | 单个测试类 | 同上 |
| `npm test`（各 frontend/* 目录） | 前端单元/组件（Vitest） | `frontend/*/coverage/` |
| `tests/e2e/smoke-test.sh` | E2E 冒烟（curl 脚本，需先启动服务） | 终端输出 |

- 覆盖率：JaCoCo，报告 `target/site/jacoco/index.html`
- 报告均为 gitignore 产物，CI 通过 artifacts 保留，本地直查 target 目录

#### 2.7.4 测试数据管理

- **铁律**：禁止使用生产数据或共享数据库跑测试
- 集成测试：Testcontainers 启动临时 PG 16 + Redis 7 实例，测试类级别隔离
- LLM 测试：录制回放（WireMock / fixtures/llm/ 目录），禁止测试消耗真实 API 调用
- 数据库初始化：`@Sql` + Flyway 迁移脚本自动建表
- 测试间无顺序依赖，可并行执行

#### 2.7.5 前端测试命名规则

| 类型 | 文件命名 | 位置 |
|------|----------|------|
| 单元/工具函数 | `{source}.test.ts` | 与源码同目录 |
| 组件 | `{Component}.test.tsx` | 与源码同目录 |
| E2E | `{scenario}.spec.ts` | `tests/e2e/`（项目根） |

- E2E 按用户场景命名：`student-chat.spec.ts`、`alert-claim.spec.ts`
- E2E 测试数据由 seed 脚本初始化（`tests/e2e/fixtures/`）

#### 2.7.6 跨模块测试（`tests/`）

- 跨模块集成：位于 backend/counseling-app/src/test/java/（`*IT.java`，如 AuthFlowIT/ConversationRiskFlowIT；`tests/integration/` 空壳已删除，DA-05）
- `tests/e2e/`：Playwright 驱动浏览器的完整用户场景
- `tests/unit/`：仅作备用，常规单元测试必须在模块内
- `tests/` 不含可构建代码，仅为脚本/配置/fixture 容器
- TDD 工作流见 `.qoder/rules/tdd-workflow.md`（@tdd-workflow）

### 2.8 构建产物与依赖管理

| 产物 | 位置 | gitignore |
|------|------|-----------|
| Maven 构建 | `backend/**/target/` | ✅ |
| Vite 构建 | `frontend/*/dist/` | ✅ |
| npm 依赖 | `**/node_modules/` | ✅ |

- 构建产物永不入库，一律可通过 `mvn package` / `npm run build` 再生
- 依赖版本：后端由 parent pom `<dependencyManagement>` 统一管控，子模块不得自行指定版本；前端三个应用各自独立 npm 管理（无 workspace，各含 package-lock.json）
- 新增依赖：须更新 design/03 技术架构文档并说明理由；YAGNI 清单（§4.6）内技术禁止引入

### 2.9 Docker / 基础设施配置（`deploy/`）

```
deploy/
├── docker-compose.yml          # 本地开发环境：PG 16 + pgvector、Redis 7、tts/voice、nginx
├── docker-compose.prod.yml     # 生产 All-in-One（私有化交付用，TLS + 自动备份）
├── docker-compose.test.yml     # 轻量测试/演示环境（不含 voice/tts）
├── docker-compose.monitoring.yml # Prometheus + Grafana 监控栈
├── nginx/                      # default.conf（HTTP）/ default-ssl.conf（TLS）
├── scripts/                    # prepare-funasr.sh / prepare-models.sh（模型投放）
├── backup.sh / restore.sh      # 宿主机备份/恢复（dbbackups volume）
├── setup-server.sh             # 服务器一键初始化
├── init-school.sh              # 学校租户初始化
├── .env.example                # 环境变量模板
└── init/                       # 基础设施初始化
    └── pg-init.sql             # 创建扩展（vector）、公共 schema
```

- 应用 Dockerfile 随模块：`backend/Dockerfile`、`frontend/Dockerfile`
- 容器命名前缀 `mindsafe-`（如 `mindsafe-pg`、`mindsafe-redis`、`mindsafe-app`）
- 本地开发端口约定：PG 5432、Redis 6379、后端 8080、学生端 5173、教师端 5174
- 启动服务前必须执行端口检查（红线，见 AGENTS.md §4）

### 2.10 数据库脚本（`backend/scripts/sql/`）

- 命名：Flyway 规范 `V{版本}__{描述}.sql`（双下划线分隔）
- 初始脚本：
  - `V1__init_public_schema.sql` — 公共 Schema（租户注册表、全局配置）
  - `V2__init_tenant_template.sql` — 租户 Schema 模板（design/02 全部 DDL）
  - `V3__seed_data.sql` — 种子数据（角色、风险规则、情绪标签）
- 后续变更：版本递增，由 Flyway 在应用启动时自动执行
- 多租户隔离：`tenant_template` 为共享 schema，靠 tenant_id 列隔离（无每租户 schema 迁移执行器，counseling-tenant 模块从未存在）
- **红线**：DDL 变更必须走版本化脚本，禁止手工改库；脚本须向后兼容（支持灰度发布）

### 2.11 日志与报告输出

| 类型 | 输出位置 | gitignore | 说明 |
|------|----------|-----------|------|
| 应用日志 | 控制台（dev）/ 容器内 `/app/logs/`（prod） | — | logback-spring.xml 配置 |
| 单元/集成测试报告 | `target/surefire-reports/`、`target/failsafe-reports/` | ✅ | Maven 自动生成 |
| 覆盖率报告 | `target/site/jacoco/` | ✅ | JaCoCo |
| 前端覆盖率 | `frontend/*/coverage/` | ✅ | Vitest c8 |
| E2E 报告 | `tests/e2e/playwright-report/` | ✅ | Playwright HTML 报告 |
| 汇总报告 | `reports/`（项目根） | ✅ | CI 聚合产物、手工分析报告暂存 |

- `reports/` 仅存机器生成产物，人工分析文档归 `design/` 或 `tmp/`
- 日志/报告均不允许 commit（gitignore 已拦截）

### 2.12 Prompt 资源文件

- 位置：`backend/counseling-ai/src/main/resources/prompts/`
- 目录结构与 design/09 上卷 Prompt 体系一一对应：

```
prompts/
├── system/         # SYS-001 系统提示词
├── safety/         # SAF-001 风险识别、SAF-002 输出审查
├── language/       # LANG-001~003 年级语言规则
├── skills/         # SKL-001~003 CBT/SEL/PFA 微技能
└── tasks/          # TSK-001~003 教师摘要/RAG改写/会话收束
```

- 格式：`.st`（Spring AI StringTemplate）或 `.txt`，由 PromptTemplateLoader 统一加载
- 版本管理：随代码 git 管理；M3+ 管理端上线后可迁移至 DB 存储（热更新）
- 禁止在 Java 代码中硬编码 Prompt 文本（安全回复模板除外，见 design/09 §二）

### 2.13 `tmp/` — 唯一临时目录

- **铁律**：本项目所有临时产物（调试输出、脚本预览、下载暂存、实验笔记草稿）**只允许**放这里
- 已 gitignore（`tmp/*`，保留 `.gitkeep`），内容随时可清空
- 不允许在项目根或桌面等处散落临时文件；脚本默认输出路径应指向 `tmp/`
- Agent 工作中间产物（调研草稿、对比分析等）也归此目录

### 2.14 `data/` — 内容资产（知识库语料等）

- **存什么**：`data/knowledge-base/` 知识库语料文件（待审/已审），如首批入库语料、后续补全语料；未来其他内容资产（如量表题库文案）按子目录扩展
- **性质**：**入库源文件**（人工审核 → 经 KnowledgeBaseService 摄入 pgvector），非临时产物（区别于 tmp/）、非设计文档（区别于 design/）
- **审核状态约定**：文件头部标注 `审核状态：待审核/已审核`；**未经项目负责人审核的语料禁止入库**（design/09 上卷知识库章节定稿流程与审核门禁）
- **命名**：`NN-主题_vN.md`，语料条目格式遵循 design/09 上卷知识库章节元数据规范

---

## 3. 同步区边界（重要）

| 路径 | 性质 | 规则 |
|------|------|------|
| `AGENTS.md`、`.qoder/rules/` | 中央库同步区 | **禁止写入项目特有内容**，会被 sync-rules.sh 覆盖/告警；改规则去中央库改 |
| `STRUCTURE.md`、`README.md`、`design/`、`doc/`（含 `doc/his/`）、`scripts/`、`backend/`、`frontend/`、`tests/`、`deploy/`、`data/`、`reports/`、`tmp/` | 项目自留区 | 项目特有内容全部放这里 |

---

## 4. 通用约定

1. **命名**：目录一律小写；中文文档可用中文名 + 编号前缀；代码文件随技术栈惯例
2. **macOS 注意**：本机 APFS 大小写不敏感，避免仅靠大小写区分的命名；`.DS_Store` 会被 Finder 反复重建，已 gitignore，不必追删
3. **iCloud 注意**：项目位于 `~/Documents`（iCloud「桌面与文稿」同步范围），大文件/高频写入目录（如未来的 `node_modules`、`.venv`）已被 gitignore 且建议关注同步流量
4. **密钥红线**：`.env` 等密钥文件禁止入库（gitignore 已挡），示例配置用 `.env.example`；LLM API Key 一律走环境变量，禁止硬编码
5. **变更流程**：调整目录结构 → 先改本文件 → 再动目录 → commit 中说明
6. **技术栈基线**（2026-07-23 定，详见 design/BEACON.md 决策 #5/#8/#9）：后端 Java 21 + Spring Boot 3 + Spring AI + MyBatis-Plus（Maven 多模块，模块化单体）；前端 React 18 + TSX + Vite + Tailwind；数据库 PostgreSQL 16 + pgvector（信创期评估达梦/人大金仓）；缓存 Redis 7；构建 Maven + npm。**YAGNI 清单**（MVP 禁止引入）：K8s、Kafka、微服务拆分、API 网关、ELK、Milvus
7. **构建产物红线**：`target/`、`dist/`、`node_modules/`、`coverage/`、`playwright-report/` 等机器产物永不入库（gitignore 已拦截）
8. **报告输出红线**：所有测试/覆盖率/E2E 报告只输出到 §2.11 约定位置，汇总归 `reports/`，禁止散落到其他目录
9. **包名约定**：后端统一 `com.mindsafe.{module}.*`（如 `com.mindsafe.ai`、`com.mindsafe.tenant`、`com.mindsafe.api`）；前端包名 `@mindsafe/student`、`@mindsafe/teacher`

---

## 5. 变更日志

| 日期 | 变更 | 原因 |
|------|------|------|
| 2026-07-23 | 初始版本 | 项目从纯文档探索阶段转入开发准备阶段，建立统一结构 |
| 2026-07-23 | 技术栈由 Python 改为 Java：`src/`→`backend/`（Maven 多模块），测试约定改为模块内 `src/test/` + 跨模块 `tests/`，YAGNI 清单更新 | 项目负责人决策私有化/信创主营，直接上 Java（Spring Boot + Spring AI） |
| 2026-07-23 | 技术栈落定：`src/` 后端结构、`apps/` 前端组、YAGNI 清单 | 项目负责人确认 Schema 级隔离 + LLM 供应商无关化方案 |
| 2026-07-23 | 文档整合完成：15 份 docx 全部转为 `design/docs/*.md`，原 docx 归档 `design/his/`，§2.2 更新 docs/his 拆分约定 | M0 文档整合里程碑收尾 |
| 2026-07-23 | 目录结构纠偏：`design/docs/*.md` 拍平到 `design/`；`design/his/` 迁至项目根 `doc/`；§1/§2.2 同步 | 对齐项目负责人原意（md 直接在 design 下、docx 在 doc 下），消除多余中间层 |
| 2026-07-23 | `prd/` + `prompts/` 归档至 `doc/his/`，删除原目录；§1/§2.1/§2.3/§3/§4 同步 | 内容已被 design/*.md 完全取代，无活跃用途，统一归入只读留档 |
| 2026-07-23 | 开发规范制定：§2.7 测试命名/目录/管理规则、§2.8 构建产物、§2.9 deploy/、§2.10 DB 脚本、§2.11 报告输出、§2.12 Prompt 资源、§2.13 tmp 扩展；新增 deploy/ + reports/ 目录；§4 新增包名/产物/报告红线 | 项目负责人确认 MVP 范围 + Maven + MyBatis-Plus，开发启动前约束先行 |
| 2026-07-28 | §2.2 新增 `design/demo/` 子目录约定（交互原型 HTML Demo） | 学生端登录页三风格 Demo 需纳入版本管理，从 tmp/ 迁入正式目录 |
| 2026-07-28 | 目录结构纠偏补漏：`design/docs/` 残留 2 份 md（16_语音情感分析/17_全感官交互）迁入 `design/` 并重编号为 54/55，删除空 `design/docs/` 目录 | 完成 2026-07-23 “拍平”决策的遗漏收尾 |
| 2026-07-29 | 新增 §2.14 `data/` 内容资产目录（`data/knowledge-base/` 知识库语料，待审/已审约定） | KB-101 首批语料落档需正式位置（非 tmp 非 design），约束先行 |
| 2026-07-28 | 重大修订：`apps/`→`frontend/`、`student/`→`student-h5/`、`teacher/`→`teacher-web/`、新增 `parent-h5/`；移除 counseling-tenant 描述（未进入构建）；pnpm→npm；YAGNI 清单移除“语音/生物识别”（已实现） | 审计发现文档与实际严重不符，纠正失实描述 |
| 2026-07-29 | 幽灵项清偿收尾：上条修订声称完成但正文残留未改——pnpm 命令/workspace 表述改 npm（三应用各自独立管理）、redis.conf 幽灵条目删除、counseling-tenant 多租户迁移执行器表述改为 tenant_template 共享 schema 事实、§2.9 deploy/ 树补齐实际文件 | 审计复核发现变更记录虚标（声称已改实际未改），如实补完 |
| 2026-07-28 | 设计文档整体规整：§2.2 全面更新——58 份旧文档合并为 **12 份合并文档**（01 概述/02 数据库/03 架构/04 部署/05 测试/06 配置/07 商业化合规/08 概要/09 学生端上卷/10 学生端下卷/11 老师端/12 家长端），旧文档（50 份）归档 `design/his/`，frozen/（34/38-43/58）不合并待后续整合；§2.5/§2.10/§2.12/§2.14 旧编号引用全部更新为新合并文档编号 | 用户指令：设计文档整体规整（合并分类、最终设计方法输出、旧文档归档、冻结文档不动） |
| 2026-07-29 | doc/ 根目录 15 份原始 docx + README.md 全量归档至 `doc/his/`（git mv），doc/ 仅存 his/；§1 目录树/§2.1/§2.2/§2.2.1 同步 | 用户指令：doc/ 文档全部归档至 his/，废弃作历史材料 |
