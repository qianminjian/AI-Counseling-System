# 59 前后端契约测试（TEST-006）方案与 SPEC

> 关联任务：TEST-006（前后端契约测试：OpenAPI + mock 校验，原 P2 远期，2026-08-06 启动实施）
> 状态：📝 方案定稿 → 实施中（TDD）
> 依据：design/16（his/16 API 接口设计）、design/05（系统测试）、springdoc 2.7.0 现状、student-h5 api.ts 现状

---

## 1. 背景与问题

1. **后端 OpenAPI 能力已有但无守卫**：springdoc 2.7.0 已接入（`OpenApiConfig.java`），`GET /api-docs` 输出 OpenAPI 3.0 JSON，SecurityConfig 已放行 `/api-docs/**`。但没有任何测试断言「OpenAPI 文档与 Controller 实际端点一致」——新增/改动端点若未文档化（缺 `@Operation`/响应模型）不会暴露。
2. **前端调用无契约校验**：student-h5 `src/api.ts` 直接以字符串路径 `fetch('/api/v1/...')`，接口路径/方法/响应结构与后端契约脱钩，后端改 DTO 或路径时前端仅在联调期暴露。
3. **mock 数据无 schema 依据**：前端测试大量使用 mock 响应（`vi.stubGlobal('fetch')`），mock 字段与后端 DTO 漂移无法检测。

## 2. 目标

建立**三层契约防线**（KISS、零新增依赖）：

| 层 | 位置 | 职责 | 防漂移方向 |
|----|------|------|-----------|
| L1 后端契约完整性 | `ContractOpenApiIT`（failsafe） | 所有业务端点 100% 入 OpenAPI、响应文档化、operationId 唯一 | 代码 → OpenAPI 文档 |
| L2 契约快照 | `scripts/gen-openapi-snapshot.sh` | 从运行中后端拉取 `/api-docs`，精简为快照 `src/__contract__/openapi.json` 入库 | OpenAPI 文档 → 前端测试资产 |
| L3 前端契约校验 | `src/test/apiContract.test.ts` | 请求方向：前端端点清单 ⊆ 快照 paths；响应方向：mock 样例数据按快照 schemas 校验 | 前端调用/测试 mock → OpenAPI 文档 |

> 契约流转：Controller 代码 →(L1)→ OpenAPI 文档 →(L2 快照)→ 前端契约测试 →(L3)→ 前端代码与 mock。

## 3. 现状盘点

- 后端：springdoc 2.7.0（parent pom 统一版本），`springdoc.api-docs.path: /api-docs`（application.yml），`OpenApiConfig` 提供 info/security 元信息；集成测试基座 `AbstractIntegrationTest`（Testcontainers PG16+Redis7，CI 走外部 DB）；failsafe 匹配 `*IT.java`。
- 前端：student-h5 vitest（jsdom，include `src/**/*.{test,spec}.{ts,tsx}`）；`api.ts` 388 行，端点统一 `fetch('/api/v1...')` 或 `api('/...')`（自动拼前缀）；测试 mock 用 `vi.stubGlobal('fetch')`；tsconfig 未开 `resolveJsonModule` → 快照以 `?raw` 导入（vite/client 自带类型）。
- 测试资产：`tests/unit/scripts/` 已有 5 套 bash 测试（check-commit/db-rollback/gen-changelog/prepare-funasr/verify-doc-numbers）。

## 4. 方案设计

### 4.1 L1 后端契约完整性（ContractOpenApiIT）

位置：`backend/counseling-app/src/test/java/com/mindsafe/ContractOpenApiIT.java`（继承 AbstractIntegrationTest，TestRestTemplate RANDOM_PORT）

断言（TDD 用例）：

1. `GET /api-docs` 返回 200 且 body 为合法 JSON（含 `paths`/`components`）。
2. **端点全量覆盖**：反射 `RequestMappingHandlerMapping.getHandlerMethods()` 收集全部 `@RequestMapping` 路径模板，过滤业务路径（排除 `/error`、`/actuator/**`、`/swagger-ui/**`、`/api-docs/**`、`/ws/**`）后，断言每条路径都出现在 OpenAPI `paths`（路径模板 `{id}` 形式一致）。
3. **响应文档化**：每个 path 的每个 HTTP 方法都有非空 `responses`，且包含成功码（2xx）。
4. **operationId 唯一**：全文档 operationId 无重复。
5. **组件模型非空**：`components.schemas` 至少包含核心 DTO（`ApiResponse` 包装类等），数量 ≥ 阈值。

> 说明：该测试不是「新功能」，而是契约完整性的**回归护栏**——任一新 Controller 端点漏文档化即红。若实施中发现既有端点未入文档（红），按序修复（补 `@Operation` 或模型注解），不降级断言。

### 4.2 L2 契约快照（gen-openapi-snapshot.sh）

位置：`scripts/gen-openapi-snapshot.sh`（仓库根 scripts/，与 check-commit 等同目录）

```
用法：bash scripts/gen-openapi-snapshot.sh [BASE_URL]          # 默认 http://localhost:8080
可选：-o <输出路径>                                             # 默认 frontend/student-h5/src/__contract__/openapi.json
```

逻辑：

1. `curl -sf <BASE_URL>/api-docs`（springdoc path=/api-docs）→ 非 200/网络失败 → stderr 报错 exit 1。
2. python3 校验 JSON 合法且含 `paths`（不依赖 jq，与 S5 后 prepare-funasr 一致）。
3. python3 精简：仅保留 `paths` + `components.schemas` + `info.title/version`（缩小快照体积，前端只需这两块）。
4. 写入目标路径（默认 `frontend/student-h5/src/__contract__/openapi.json`），输出摘要（paths 数 / schemas 数）。

配套测试：`tests/unit/scripts/gen-openapi-snapshot-test.sh`（bash 用例，mock curl/python3）：
- 缺 BASE_URL 参数时使用默认值
- curl 失败（非 200）→ 退出非 0 且不产出文件
- 响应非 JSON → 退出非 0
- JSON 缺 paths → 退出非 0
- 正常响应 → 产出精简文件且含 paths/components
- -o 自定义输出路径生效
- 摘要输出格式（paths 数/schemas 数）

### 4.3 L3 前端契约校验

**4.3.1 轻量 schema 校验器** `frontend/student-h5/src/__contract__/schemaValidator.ts`

零依赖纯函数（~80 行），支持 JSON Schema draft-07 子集：

- `type`（string/number/boolean/object/array/integer/null）
- `required` / `properties`（object）
- `items`（array）
- `enum`
- `$ref`（`#/components/schemas/X` 解析）
- `additionalProperties` 忽略、未知关键字忽略（宽容模式）

接口：`validateSchema(value: unknown, schema: Schema, resolveRef: (ref: string) => Schema | undefined): string[]`（返回错误信息数组，空数组 = 通过）。

**4.3.2 契约测试** `frontend/student-h5/src/test/apiContract.test.ts`

- 快照导入：`import openapiRaw from '../__contract__/openapi.json?raw'` → `JSON.parse`。
- **请求方向用例**（端点清单 = api.ts 全部 fetch 目标，显式数组避免 AST 扫描复杂度）：
  - `POST /auth/trial/register`、`POST /auth/pin-login`、`POST /auth/refresh`、`POST /auth/set-pin`、`POST /auth/voice-credential`、`POST /auth/voice-login`、`POST /auth/guardian-consent/request`、`POST /auth/guardian-consent/confirm`、`GET /voiceprint/config`、`POST /voiceprint/verify`、`POST /voiceprint/enroll`、`GET /toolbox`、`GET /toolbox/sos`、`POST /toolbox/mood-check`、`POST /sos/events` 全部存在于快照 `paths` 且 method 匹配。
- **响应方向用例**（mock 样例数据按快照 schemas 校验）：
  - `AuthResult` mock → `ApiResponse` data 内联对象 schema（或对应 DTO schema）校验通过
  - `VoiceprintConfig` mock → 对应 schema 校验
  - `ToolboxTool[]` mock → 对应 schema（items 校验）通过
  - `MoodCheckResult` mock → 对应 schema 校验通过
  - 负例：故意破坏字段类型 → 校验返回错误（证明校验器生效）
- **校验器单测**：`src/test/schemaValidator.test.ts`（type/required/nested/$ref/enum/items/负例，≥12 用例）

> mock 样例数据与 api.ts 接口字段一致（AuthResult/VoiceprintConfig/ToolboxTool/MoodCheckResult），schema 名称以实际快照 `components.schemas` 为准（生成后核对，如 `ToolboxToolDTO`）。

## 5. 实施里程碑（TDD）

| 里程碑 | 内容 | 验证 |
|--------|------|------|
| M1 | ContractOpenApiIT（先写断言 → 跑红 → 修复漏文档化端点 → 绿） | `mvn verify -pl counseling-app`（failsafe） |
| M2 | gen-openapi-snapshot.sh + bash 测试 | `tests/unit/scripts/gen-openapi-snapshot-test.sh` 全绿 |
| M3 | 前端 schemaValidator + apiContract.test（TDD：校验器先写测试） | student-h5 vitest 全量绿 + tsc 干净 |
| M4 | 首次快照生成（本地起后端一次）+ 全量回归 + 文档同步 + 台账登记（DOC-058） | 后端全量 / student-h5 全量 / scripts 全量 |

## 6. 验收标准（Done）

1. `ContractOpenApiIT`：5 组断言全绿（端点全量覆盖为硬门禁）。
2. 前端新增用例：schemaValidator ≥12 + apiContract ≥15 = ≥27 用例全绿。
3. `gen-openapi-snapshot-test.sh` ≥8 用例全绿。
4. 快照 `src/__contract__/openapi.json` 已入库（含全部业务 paths）。
5. 全量回归：后端（surefire+failsafe）全绿、student-h5 685+ 用例全绿、scripts 53+ 用例全绿。
6. 文档同步：doing/59 实施记录、TASK-TRACKER TEST-006 ✅（DOC-058 登记）、CHANGELOG。

## 7. 范围外（YAGNI）

- 不引入 Pact / openapi-generator / ajv 等新依赖（轻量校验器足够）。
- 不做运行时契约校验（线上请求不打契约校验，避免性能损耗）。
- 不做 teacher-web/parent-h5 契约测试（脚本 -o 参数已支持，后续端复用同款，本期只做学生端）。
- CI 不做快照新鲜度强制刷新（后端 L1 守卫已保证文档与代码一致；快照刷新为发布前手动步骤）。

## 8. 实施记录（实施完成后追加）

### 实施完成（2026-08-05，TDD）

**M1 后端契约完整性** ✅
- `backend/counseling-app/src/test/java/com/mindsafe/ContractOpenApiIT.java`（继承 AbstractIntegrationTest）：5 组断言全绿（文档合法 / 端点全量覆盖 / 响应文档化 / operationId 唯一 / schemas ≥10）。
- 结果：springdoc 2.7.0 已完整生成全部业务端点，无漏文档化修复项；探针输出 93 个 schema（`LoginResponse`/`TrialRegisterResponse`/`ToolDefinition`/`ApiResponseMapStringObject` 等）。

**M2 契约快照脚本** ✅
- `scripts/gen-openapi-snapshot.sh`：curl 拉取 → python3 校验（非 JSON/缺 paths fail-fast）→ 精简（info/paths/components.schemas）→ 写入（默认 `frontend/student-h5/src/__contract__/openapi.json`）；`-o` 支持绝对/相对路径；摘要输出 `paths=N, schemas=M`。
- `tests/unit/scripts/gen-openapi-snapshot-test.sh`：11 用例全绿（fake curl 覆盖失败/非 JSON/缺 paths/正常/自定义路径/摘要格式/components 缺失容错）。
- 真机验证：临时起后端（Testcontainers 同款 pgvector/pg16 + redis:7）→ 快照生成 123 paths / 93 schemas。

**M3 前端契约校验** ✅
- `src/__contract__/schemaValidator.ts`：零依赖 draft-07 子集校验器（type 含数组 / required / properties / items / enum / $ref）；`validateSchema`（正向）与 `validateMock`（反向：mock ⊆ schema、不强制 required、null 跳过、DTO 未知字段报错、Map 容器 `additionalProperties` 宽容）。
- `src/test/schemaValidator.test.ts`：22 用例全绿；`src/test/apiContract.test.ts`：26 用例全绿（请求方向 15 端点 it.each + 响应方向 mock 校验 + 负例 3 组）。
- **契约漂移修复（mock 校验驱动）**：`MoodCheckResult` 原声明 `effect` 字段在后端不存在（后端返回 toolId/preMood/postMood/delta/level/needsAttention），已对齐 api.ts 接口 + toolboxApi.test.ts mock 数据。

**M4 快照入库 + 全量回归** ✅
- `src/__contract__/openapi.json` 已入库（123 paths / 93 schemas，含全部学生端 15 端点）。
- 回归：后端 `mvn verify` BUILD SUCCESS（surefire + failsafe 全绿）；student-h5 733 用例全绿（基线 685 + 新增 48）；tsc 干净；scripts 6 套测试全绿（64 用例，含新增 11）。
- 台账：TEST-006 ✅（DOC-058）；CHANGELOG 已登记。
