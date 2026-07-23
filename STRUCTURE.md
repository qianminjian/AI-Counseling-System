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
├── prd/                    # 需求层：PRD、早期方案、探索产物
├── design/                 # 设计层：正式设计文档 + BEACON.md（项目明灯）
├── prompts/                # Prompt 资产：系统提示词、探索期 prompt 记录
├── scripts/                # 工具脚本：文档生成、数据处理等一次性/辅助脚本
│
├── src/                    # 后端源代码（Python + FastAPI + LangGraph，详见 §2.5）
├── apps/                   # 前端应用组（React + TS + Vite，详见 §2.6）
├── tests/                  # 后端测试代码（pytest），目录镜像 src/ 结构
│   ├── unit/               #   单元测试：单函数/单类，无外部依赖
│   ├── integration/        #   集成测试：跨模块、含 DB/API/LLM 调用
│   └── e2e/                #   端到端测试：完整用户场景
│
└── tmp/                    # 唯一临时目录（gitignore，不追踪）
```

---

## 2. 各目录职责与规则

### 2.1 `prd/` — 需求层文档

- **存什么**：原始需求、方案初稿、前期探索产物、竞品原始素材
- **不存什么**：正式设计文档（去 `design/`）
- **现状文件**：
  - `AI心理辅导系统.docx` — 最初方案初稿（2026-05-29 17:47）
  - `AI小学生心理辅导系统建设方案.docx` — 建设方案（05-29 20:57 版）
  - `AI小学生心理辅导系统建设方案_20260529-2019.docx` — 建设方案早期稿
  - `AI心理辅导系统建设方案_20260529-2106.docx` — 建设方案整合稿
  - `AI心理辅导系统_Prompt体系详细设计.docx` / `_CBT对话流程树设计.docx` / `架构图.png` — 前期探索成果（已被 design/ 编号文档迭代取代）
- **⚠️ 待办**：3 版建设方案内容有差异（md5 不同），需人工比对后确定主版本并清理

### 2.2 `design/` — 设计层文档（对应 design-persistence.md 规则）

- **存什么**：
  - `BEACON.md` — 项目明灯文件（设计决策、范围、当前状态），**唯一强制**
  - `NN_主题.docx` — 编号正式设计文档（01~15，保持编号连续）
  - `*-PLAN.md` — Plan Mode 产出的执行计划（按 design-persistence.md §6 硬性要求）
  - `discussion/`（可选）多轮设计讨论摘要；`decisions/`（可选）ADR；`reference/`（可选）外部参考
- **命名约定**：新增正式设计文档沿用 `NN_主题.docx` 编号制，接着 15 往后排
- **读取时机**：任何新任务启动前，先读 `BEACON.md`

### 2.3 `prompts/` — Prompt 资产

- **存什么**：生产用系统提示词（后续开发）、探索期 prompt 历史记录
- **命名约定**：`YYYYMMDD-用途说明.md`（历史记录）；生产 prompt 按模块命名
- **现状文件**：`20260529-初始探索prompt记录.md`（其他 agent 探索期的需求输入与 15 子主题任务拆解）

### 2.4 `scripts/` — 工具脚本

- **存什么**：docx 生成脚本（create_*.js/py）、数据迁移、一次性批处理
- **规则**：脚本必须自包含可独立运行；涉及写操作的脚本默认输出到 `tmp/` 预览，确认后再落正式位置
- **现状文件**：7 个 docx 生成脚本（design/ 编号文档的生成器，保留作再生成能力）

### 2.5 `src/` — 后端源代码（Python 包）

技术基线：Python 3.12 + FastAPI + LangGraph + SQLAlchemy 2.x + PostgreSQL 16（pgvector）+ Redis 7。
包管理用 **uv**，依赖锁定在根 `pyproject.toml`。

```
src/
├── api/            # FastAPI 路由层（请求/响应模型，不含业务逻辑）
├── agents/         # LangGraph 7 个 Agent（对应 design/13：Safety/Emotion/CBT/Conversation/Escalation/Report/Memory）
├── core/           # 配置、安全、多租户中间件（Schema 路由）、LLM Provider 抽象
├── models/         # SQLAlchemy 模型（落地 design/06 的 DDL）
├── services/       # 业务服务层（对话编排、预警通知、档案）
├── prompts/        # 生产用 prompt 模板（版本化，Jinja2/YAML）
└── main.py         # FastAPI 应用入口
```

- **架构形态**：模块化单体（不建微服务）；模块边界按上述目录即领域边界
- **多租户**：Schema 级隔离（`tenant_{tenant_id}`，按 design/07），租户路由中间件在 `core/`
- **LLM 接入**：供应商无关，`core/` 内统一 Provider 抽象（LangChain `init_chat_model` 配置驱动），任意国产合规 LLM 可经配置接入，主备降级按 design/13 §6.2

### 2.6 `apps/` — 前端应用组

技术基线：React 18 + TypeScript + Vite + Tailwind CSS；包管理 **pnpm**（workspace）。

```
apps/
├── student/        # 学生端 H5（M1）：儿童友好 UI，自绘轻组件
└── teacher/        # 教师后台 Web（M2）：Ant Design
```

- 每个应用独立 `package.json`，共享配置放 `apps/shared/`（需要时再建，YAGNI）
- 前端测试就近放置（`*.test.ts`），Vitest 运行

### 2.7 `tests/` — 后端测试（pytest）

- **镜像规则**：`tests/unit/` 目录结构镜像 `src/`（如 `src/agent/flow.py` → `tests/unit/agent/test_flow.py`）
- **分层规则**：
  - `unit/`：快（毫秒级）、无 IO、无网络、无 LLM 真实调用（mock）
  - `integration/`：允许真实 DB（测试库）、本地服务；LLM 调用用录制/桩
  - `e2e/`：完整场景，允许慢；跑在独立环境
- **命名**：`test_*.py` / `*.test.ts`（随技术栈定）
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
| `STRUCTURE.md`、`README.md`、`design/BEACON.md`、`prd/`、`prompts/`、`scripts/`、`src/`、`tests/`、`tmp/` | 项目自留区 | 项目特有内容全部放这里 |

---

## 4. 通用约定

1. **命名**：目录一律小写（`prd/` 非 `PRD/`）；中文文档可用中文名 + 编号前缀；代码文件随技术栈惯例
2. **macOS 注意**：本机 APFS 大小写不敏感，避免仅靠大小写区分的命名；`.DS_Store` 会被 Finder 反复重建，已 gitignore，不必追删
3. **iCloud 注意**：项目位于 `~/Documents`（iCloud「桌面与文稿」同步范围），大文件/高频写入目录（如未来的 `node_modules`、`.venv`）已被 gitignore 且建议关注同步流量
4. **密钥红线**：`.env` 等密钥文件禁止入库（gitignore 已挡），示例配置用 `.env.example`；LLM API Key 一律走环境变量，禁止硬编码
5. **变更流程**：调整目录结构 → 先改本文件 → 再动目录 → commit 中说明
6. **技术栈基线**（2026-07-23 定，详见 design/BEACON.md 决策 #5）：后端 Python 3.12 + FastAPI + LangGraph；前端 React 18 + TS + Vite + Tailwind；数据库 PostgreSQL 16 + pgvector；缓存 Redis 7；包管理 uv + pnpm。**YAGNI 清单**（MVP 禁止引入）：K8s、Kafka、微服务拆分、API 网关、ELK、Go、Milvus、语音/生物识别组件

---

## 5. 变更日志

| 日期 | 变更 | 原因 |
|------|------|------|
| 2026-07-23 | 初始版本 | 项目从纯文档探索阶段转入开发准备阶段，建立统一结构 |
| 2026-07-23 | 技术栈落定：`src/` 后端结构、`apps/` 前端组、YAGNI 清单 | 钱敏健确认 Schema 级隔离 + LLM 供应商无关化方案 |
