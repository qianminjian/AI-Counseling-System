# doing/63 · LLM 主备配置通用化设计（任意双供应商 + key 主/备命名）

> ⚠️ **doing 子文档（开发期）**：本文件为进行中的子设计文档，未完成合并前不构成正式设计约束。完成后并入主文档（06_系统配置与外部服务依赖设计 §LLM 章节）并归档 `design/his/`。
>
> 状态：🟩 已实施（代码+测试+配置，2026-08-06 13:35）｜ 编号：doing/63（接续 doing/59 后独立编号）｜ 创建：2026-08-06
>
> 实施记录：counseling-ai 全量 65 用例绿（AiConfigTest 2 + LlmExtraBodyConfigTest 8）；counseling-domain 59 / counseling-service 314 / counseling-app 9 全绿；3 份 compose `config` 校验通过；`DeepSeekThinkingConfig` 已删除（LLM-GEN-009）；DEP-001 修复随同落地（见 doing/65）。运行时冒烟（§6.2-2~4）待部署环境执行。

---

## 1. 背景与问题

### 1.1 现状：LLM 主备配置深度绑定 DeepSeek

当前系统 LLM 主备能力（AI-004 多模型降级）已实现，但配置层存在 4 处 DeepSeek 绑定，无法切换到任意两个 OpenAI 兼容供应商：

| # | 绑定点 | 位置 | 现状 |
|---|--------|------|------|
| B1 | 主模型默认值写死 | `application.yml` spring.ai.openai 段 | `base-url: ${LLM_BASE_URL:https://api.deepseek.com}`、`model: ${LLM_MODEL:deepseek-v4-flash}` |
| B2 | 备模型默认值写死 | `application.yml:121` + `application-prod.yml` mindsafe.ai.fallback 段 | `base-url: https://dashscope.aliyuncs.com/compatible-mode/v1`、`model: qwen-plus`；**注意：base 层 application.yml 也有同段定义（:121），非仅 prod** |
| B3 | 供应商专属参数硬编码 | `DeepSeekThinkingConfig` | **全局 RestClient 拦截器**向所有 `/chat/completions` 请求注入 `enable_thinking: false`（DeepSeek 专属参数），换主模型后仍注入，对 OpenAI/GLM/Kimi 等无效或可能报错 |
| B4 | 变量名语义不通用 | 环境变量层 | 主=`LLM_API_KEY`/`LLM_BASE_URL`/`LLM_MODEL`（语义是"LLM"不是"主"）、备=`LLM_FALLBACK_API_KEY`（FALLBACK 语义）、遗留 `DEEPSEEK_API_KEY` 旧名嵌套回退；**四个旧名需统一处置（见 §4.1.1）** |

### 1.2 触发场景

- 试点期可能切换主模型（如 DeepSeek → GLM/Kimi/OpenAI）或调整主备组合
- 新学校/新客户要求指定供应商
- 当前 `.env` 中 `LLM_API_KEY` 与冗余 `DEEPSEEK_API_KEY` 并存造成混乱（部署审计发现）

### 1.3 用户需求

1. 主/备两个 LLM 配置**不写死 DeepSeek**，可以是任意两个供应商
2. 配置项命名通用化：**key 主、key 备**的命名方式
3. 参考业界同类产品配置实现，深度分析后输出设计方案 + spec

---

## 2. 业界实践调研

### 2.1 同类产品配置方式对比

| 产品 | 多模型配置方式 | 主备/降级 | 供应商专属参数 | 命名风格 |
|------|---------------|----------|---------------|---------|
| **LiteLLM**（LLM 网关，业界事实标准） | `model_list` 数组，每个模型含 `litellm_params: {model, api_key, api_base}`，三者一组独立可配 | 同一 `model_name` 映射多个 deployment → 主失败自动切换；`fallbacks` 支持跨模型降级链 | `drop_params: true` 丢弃不支持的参数，避免供应商报错 | 每个模型独立 env（`OPENAI_API_KEY`、`ANTHROPIC_API_KEY`），网关 `master_key` 统一鉴权 |
| **one-api / new-api**（自建网关） | 渠道（channel）= 供应商 + 模型映射 + key；令牌统一鉴权 | 渠道优先级/权重轮询，失败切换下一渠道 | 渠道类型决定参数格式 | 渠道级独立配置 |
| **Dify**（LLMOps 平台） | 模型供应商管理界面，每个供应商独立 API Key，可配多 key 轮询 | 应用级"默认模型" + 手动切换 | 供应商类型驱动 | 供应商名命名（openai_api_key 等） |
| **spring-ai**（本项目底层） | 多 provider 前缀：`spring.ai.openai.*`、`spring.ai.dashscope.*`、`spring.ai.ollama.*` 各自独立 | **无内置主备**，需应用层包装（本项目已自研 ResilientChatModel） | provider 专属配置段 | provider 前缀 |
| **HugeGraph-AI** | 按用途命名：`OPENAI_TEXT2GQL_API_BASE/API_KEY/LANGUAGE_MODEL`、`LITELLM_CHAT_*` | 无主备 | — | `用途_角色_属性` 三段式 |

### 2.2 提炼的 4 条可借鉴原则

1. **三元组独立**（LiteLLM/HugeGraph）：每个模型 = `base-url + api-key + model` 三个配置项，三者独立可配、任意组合 → 解绑供应商的核心
2. **不支持的参数要能丢弃**（LiteLLM `drop_params`）：供应商专属参数（如 DeepSeek `enable_thinking`）不应硬编码注入，而应可配置、可关闭 → 对应 B3 的解法
3. **主备语义命名**（Grafana/one-api 惯例）：`primary/backup`（主/备）是最通用的角色命名，比 `fallback`（降级动作语义）更清晰 → 对应 B4 的解法
4. **YAGNI 边界**：业界网关（LiteLLM/one-api）支持 N 个模型 + 热更新，但本项目当前只需 2 个（主+备），引入网关是过度设计；保持应用层双模型 + 环境变量配置即可

---

## 3. 设计目标与非目标

### 3.1 目标

- G1：主/备模型可为**任意两个 OpenAI 兼容供应商**（文档给出已验证组合示例）
- G2：环境变量命名通用化——主 = `LLM_PRIMARY_*`，备 = `LLM_BACKUP_*`（key 主、key 备）
- G3：供应商专属参数通用化注入，DeepSeek 场景行为与现状等价
- G4：兼容现有部署（旧变量名回退），不破坏线上运行

### 3.2 非目标（YAGNI）

- ❌ 不做 N 个模型列表/数组配置（业界网关模式）——当前只需 2 个
- ❌ 不做配置热更新/运行时切换——环境变量 + 重启即可
- ❌ 不引入 LiteLLM/one-api 网关——应用层双模型已满足，新增中间件是过度设计
- ❌ 不改动 embedding 配置独立性——`EMBEDDING_*` 维持现状（维度 vector(1536) 约束，换 embedding 模型需重新摄入知识库，属独立变更）

---

## 4. 设计方案

### 4.1 环境变量命名（核心：key 主、key 备）

```
# ===== 主模型（LLM Primary，key 主） =====
LLM_PRIMARY_API_KEY=          # 必填（主模型）
LLM_PRIMARY_BASE_URL=         # 可选，默认 https://api.deepseek.com
LLM_PRIMARY_MODEL=            # 可选，默认 deepseek-v4-flash
LLM_PRIMARY_TEMPERATURE=0.7   # 可选
LLM_PRIMARY_MAX_TOKENS=2048   # 可选
LLM_PRIMARY_EXTRA_BODY=       # 可选：供应商专属请求体参数（JSON 字符串），如 {"enable_thinking":false}

# ===== 备模型（LLM Backup，key 备） =====
LLM_BACKUP_API_KEY=           # 可选：为空 = 单模型模式（现状行为）
LLM_BACKUP_BASE_URL=          # 可选
LLM_BACKUP_MODEL=             # 可选
LLM_BACKUP_TEMPERATURE=0.7    # 可选
LLM_BACKUP_MAX_TOKENS=2048    # 可选
LLM_BACKUP_EXTRA_BODY=        # 可选：备模型供应商专属请求体参数
```

**兼容别名（迁移期，deprecated，新名优先）：**

| 新名（正式） | 旧名（回退） | 说明 |
|-------------|-------------|------|
| `LLM_PRIMARY_API_KEY` | `LLM_API_KEY` → `DEEPSEEK_API_KEY` | 保留现有嵌套回退链 |
| `LLM_PRIMARY_BASE_URL` | `LLM_BASE_URL` | — |
| `LLM_PRIMARY_MODEL` | `LLM_MODEL` | — |
| `LLM_BACKUP_API_KEY` | `LLM_FALLBACK_API_KEY` | — |
| `LLM_BACKUP_BASE_URL` | `LLM_FALLBACK_BASE_URL` | — |
| `LLM_BACKUP_MODEL` | `LLM_FALLBACK_MODEL` | — |

**命名理由**：业界主备惯例 `primary/backup`（对比 secondary/standby）；`PRIMARY` 与现有 `spring.ai` 无冲突，`BACKUP` 语义贴合降级场景；`EXTRA_BODY` 借鉴 LiteLLM `drop_params` 思路但更通用（可注入任意专属参数）。

#### 4.1.1 旧变量名统一处置（DEEPSEEK_API_KEY / LLM_FALLBACK_API_KEY / LLM_BASE_URL / LLM_MODEL 整体纳入）

四个旧名不是简单"兼容别名"，需按三层命名时代统一处置：

| 命名时代 | 主 key | 备 key | 说明 |
|---------|--------|--------|------|
| 第一代（老套部署，2026-07 前） | `DEEPSEEK_API_KEY` | —（无备） | 老套 compose（服务器 `/guju/mindsafe/docker-compose.yml`）唯一消费方：`LLM_API_KEY: ${DEEPSEEK_API_KEY}` |
| 第二代（当前代码，2026-07~08） | `LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL` | `LLM_FALLBACK_API_KEY` | 代码引用点：主——`application.yml:51-55` + `application-prod.yml:35`（key/base-url/model）、**embedding 嵌套回退 `application.yml:61` + `application-prod.yml:40`（`${EMBEDDING_BASE_URL:${LLM_BASE_URL:...}}`）**；备——`application.yml:121` + `application-prod.yml:71`；透传——`docker-compose.yml:80-84` / `docker-compose.prod.yml:58-61` / `docker-compose.test.yml:60-61`、`.env.example:22-25` |
| 第三代（本设计，目标态） | `LLM_PRIMARY_API_KEY` / `LLM_PRIMARY_BASE_URL` / `LLM_PRIMARY_MODEL` | `LLM_BACKUP_API_KEY` / `LLM_BACKUP_BASE_URL` / `LLM_BACKUP_MODEL` | 本设计正式命名 |

**处置决策（与部署统一专题联动，见 doing/62 冻结·外部服务接入上下文）：**

| 旧变量名 | 处置 | 依据 |
|---------|------|------|
| `DEEPSEEK_API_KEY` | **代码层保留嵌套回退**（三级链末级）；**部署层清理**：新套转正后从服务器 `.env` 删除（当前与 `LLM_API_KEY` 同值冗余）；老套 compose 是唯一消费方，老套退役后彻底废弃 | 老套 compose 不在仓库内（服务器遗留），无法随仓库删除；回退保留零成本且防止漏配启动失败 |
| `LLM_FALLBACK_API_KEY` | **代码层保留回退**（备 key 的二级别名）；**部署层不再使用**：`.env`/`.env.example` 一律写 `LLM_BACKUP_API_KEY` | 备 key 已是独立最小权限设计（P2），别名仅保证既有部署不破 |
| `LLM_API_KEY` | **代码层保留回退**（主 key 二级别名）；**部署层**：`.env.example` 标注 deprecated，新部署只教 `LLM_PRIMARY_API_KEY` | 试点期已写入服务器 .env，回退保证不破 |
| `LLM_BASE_URL` | **代码层保留回退**（主 base-url 二级别名 + **embedding 二级回退链同步更新**：`${EMBEDDING_BASE_URL:${LLM_PRIMARY_BASE_URL:${LLM_BASE_URL:...}}}`，旧名部署 embedding 不破）；**部署层**：`.env.example` 标注 deprecated | 3 份 compose（dev/prod/test）+ 本地 backend/.env 均在消费；embedding 复用主供应商端点的设计逻辑不变 |
| `LLM_MODEL` | **代码层保留回退**（主 model 二级别名）；**部署层**：`.env.example` 标注 deprecated；注意 test compose 当前用 `deepseek-chat`（与 prod `deepseek-v4-flash` 不同，测试环境独立模型，迁移时保留差异） | 3 份 compose + 本地 backend/.env 消费；测试环境独立模型值属有意差异 |

> 验收底线：**新部署（.env 新装）不得再出现 `DEEPSEEK_API_KEY`、`LLM_FALLBACK_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`**；代码回退链保留但文档只教新名（LLM-GEN-012）。

### 4.2 application.yml 结构变更

```yaml
spring:
  ai:
    openai:
      # 主模型（供应商无关）：嵌套回退链 LLM_PRIMARY_* → LLM_* → DEEPSEEK_* → 占位
      api-key: ${LLM_PRIMARY_API_KEY:${LLM_API_KEY:${DEEPSEEK_API_KEY:sk-placeholder}}}
      base-url: ${LLM_PRIMARY_BASE_URL:${LLM_BASE_URL:https://api.deepseek.com}}
      chat:
        options:
          model: ${LLM_PRIMARY_MODEL:${LLM_MODEL:deepseek-v4-flash}}
          temperature: ${LLM_PRIMARY_TEMPERATURE:0.7}
          max-tokens: ${LLM_PRIMARY_MAX_TOKENS:2048}
      # Embedding 独立配置（不受主备改造影响）：回退链含主模型二级别名（LLM_PRIMARY_BASE_URL → LLM_BASE_URL），
      # 确保旧名部署（仅配 LLM_*）embedding 端点/凭证不破
      embedding:
        base-url: ${EMBEDDING_BASE_URL:${LLM_PRIMARY_BASE_URL:${LLM_BASE_URL:https://api.deepseek.com}}}
        api-key: ${EMBEDDING_API_KEY:${LLM_PRIMARY_API_KEY:${LLM_API_KEY:sk-placeholder}}}
        options:
          model: ${EMBEDDING_MODEL:text-embedding-ada-002}

mindsafe:
  llm:
    backup:                        # 原 mindsafe.ai.fallback 段改名为 backup（内部属性名同步通用化）
      enabled: true                # 由 LLM_BACKUP_API_KEY 非空派生（见 4.3）
      base-url: ${LLM_BACKUP_BASE_URL:${LLM_FALLBACK_BASE_URL:}}
      api-key: ${LLM_BACKUP_API_KEY:${LLM_FALLBACK_API_KEY:}}
      model: ${LLM_BACKUP_MODEL:${LLM_FALLBACK_MODEL:}}
      temperature: ${LLM_BACKUP_TEMPERATURE:0.7}
      max-tokens: ${LLM_BACKUP_MAX_TOKENS:2048}
```

> 注：`mindsafe.ai.fallback.*` 旧属性名保留为别名（Spring relaxed binding 或显式双读），避免破坏已有外部配置；`mindsafe.ai` 超时参数（first-token-timeout-ms 等）不动。**改造范围含 base 层 `application.yml:121` 的 fallback 段**（非仅 prod），两处保持同一命名。

### 4.3 代码改造

**4.3.1 AiConfig —— 主模型改为手动构建（与备对称，摆脱自动配置依赖）**

- 新增主模型构建逻辑：`OpenAiApi.builder().baseUrl(primaryBaseUrl).apiKey(primaryApiKey).build()` + `OpenAiChatModel.builder().openAiApi().defaultOptions().build()`（与现有 fallback 构建方式一致，Spring AI 1.0.0 已验证可用）
- `resilientChatModel` 不再注入 `autoConfiguredChatModel`，改为「手动构建主模型 + 手动构建备模型」→ `ResilientChatModel(primaryChatModel, backupChatModel, ...)`
- 启用条件不变：`LLM_BACKUP_API_KEY` 非空才启用双模型；为空返回主模型单例（行为兼容）
- spring.ai 自动配置的 ChatModel 仍存在（占位 key 不 fail），但不再作为业务主模型；embedding 自动配置不受影响

**4.3.2 DeepSeekThinkingConfig → LlmExtraBodyConfig（通用化，解绑 B3）**

- 删除硬编码 `enable_thinking: false` 注入逻辑
- 新增 `LlmExtraBodyConfig`：读取 `LLM_PRIMARY_EXTRA_BODY` / `LLM_BACKUP_EXTRA_BODY`（JSON 字符串）与主/备 base-url
- 单个 RestClient 拦截器：请求 URI 命中主 base-url → 注入主 EXTRA_BODY；命中备 base-url → 注入备 EXTRA_BODY；均不命中 → 不注入
- **DeepSeek 行为兼容**：EXTRA_BODY 为空且 base-url 含 `deepseek` 时，自动注入 `{"enable_thinking":false}`（保持现状首 token 延迟优化，不再影响其他供应商）
- JSON 解析失败安全降级（沿用现有逻辑），C4 留痕

**4.3.3 ResilientChatModel —— 零改动**

主备包装/降级/监控指标（`mindsafe.llm.model_fallback`）已供应商无关，不动。

**4.3.4 compose / .env.example 同步**

- `docker-compose.prod.yml` backend 段：环境变量透传改为 `LLM_PRIMARY_*` / `LLM_BACKUP_*`（保留旧名透传也行，但统一后只传新名 + 旧名回退由 yml 处理）
- `.env.example`：新增 `LLM_PRIMARY_*` / `LLM_BACKUP_*` 注释块（含 EXTRA_BODY 示例），旧名标注 deprecated

### 4.4 行为兼容矩阵

| 配置组合 | 行为 |
|---------|------|
| 仅 `LLM_PRIMARY_API_KEY` | 单模型模式（现状等价） |
| 仅旧名 `LLM_API_KEY` | 单模型模式（回退生效，兼容） |
| `LLM_PRIMARY_*` + `LLM_BACKUP_*` | 双模型降级（主失败 → 备 → 安全话术） |
| 新旧名混配 | 新名优先（如只设 `LLM_BACKUP_API_KEY` + 旧 `LLM_API_KEY` = 主旧名 + 备新名） |
| 主 base-url 含 deepseek | EXTRA_BODY 为空时自动注入 enable_thinking=false（现状等价） |
| 主 base-url 非 deepseek | 不注入任何专属参数（除非显式配置 EXTRA_BODY） |

### 4.5 配置示例（已验证组合）

```bash
# 示例 A：DeepSeek（主）+ 阿里云百炼（备）—— 现状迁移
LLM_PRIMARY_API_KEY=sk-deepseek...
LLM_PRIMARY_BASE_URL=https://api.deepseek.com
LLM_PRIMARY_MODEL=deepseek-v4-flash
LLM_BACKUP_API_KEY=sk-bailian...
LLM_BACKUP_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_BACKUP_MODEL=qwen-plus

# 示例 B：GLM（主）+ Kimi（备）—— 任意双供应商
LLM_PRIMARY_API_KEY=sk-glm...
LLM_PRIMARY_BASE_URL=https://open.bigmodel.cn/api/paas/v4
LLM_PRIMARY_MODEL=glm-4-plus
LLM_BACKUP_API_KEY=sk-kimi...
LLM_BACKUP_BASE_URL=https://api.moonshot.cn/v1
LLM_BACKUP_MODEL=moonshot-v1-32k

# 示例 C：DeepSeek + 显式专属参数（不依赖自动注入）
LLM_PRIMARY_EXTRA_BODY={"enable_thinking":false}
```

---

## 5. Spec（验收标准，EARS 风格）

| ID | 优先级 | 验收标准 |
|----|--------|---------|
| LLM-GEN-001 | 必须 | 当部署只配置 `LLM_PRIMARY_*`（或旧名回退）且不配置 `LLM_BACKUP_*` 时，系统以单模型模式运行，行为与改造前完全一致 |
| LLM-GEN-002 | 必须 | 当部署配置 `LLM_PRIMARY_*` 与 `LLM_BACKUP_*`（任意两个 OpenAI 兼容供应商）时，系统启用双模型降级：主模型调用失败 → 自动切换备模型，且 `mindsafe.llm.model_fallback` 指标计数 +1 |
| LLM-GEN-003 | 必须 | 主/备模型各自读取独立的 base-url、api-key、model、temperature、max-tokens，任意组合可互换（文档 §4.5 三组示例全部通过） |
| LLM-GEN-004 | 必须 | 环境变量命名遵循 key 主/key 备：`LLM_PRIMARY_*`（主）、`LLM_BACKUP_*`（备）；旧名 `LLM_API_KEY`/`LLM_BASE_URL`/`LLM_MODEL`/`LLM_FALLBACK_*`/`DEEPSEEK_API_KEY` 回退兼容，新名优先 |
| LLM-GEN-005 | 必须 | 主 base-url 含 `deepseek` 且未配置 `LLM_PRIMARY_EXTRA_BODY` 时，请求体自动注入 `enable_thinking:false`，首 token 延迟与现状等价（≤3s 实测） |
| LLM-GEN-006 | 必须 | 主 base-url 为 DeepSeek 以外供应商时，请求体**不包含**任何 DeepSeek 专属参数；显式配置 `LLM_PRIMARY_EXTRA_BODY` 时按 JSON 原样注入 |
| LLM-GEN-007 | 必须 | `LLM_PRIMARY_EXTRA_BODY` 为非法 JSON 时安全降级（不注入、不阻断请求），日志留痕（C4） |
| LLM-GEN-008 | 必须 | embedding 配置独立于主备改造：仅修改 `LLM_PRIMARY_*` 不影响 `EMBEDDING_*` 生效 |
| LLM-GEN-009 | 应该 | 代码中不再存在 `DeepSeekThinkingConfig` 类名与硬编码 `enable_thinking` 注入（迁移至 LlmExtraBodyConfig 通用实现） |
| LLM-GEN-010 | 应该 | `.env.example`、`docker-compose.prod.yml`、主文档 06 §LLM 章节三处同步更新，无残留旧命名文档 |
| LLM-GEN-011 | 应该 | AiConfig 新增单元测试覆盖：主备 api-key 条件分支（备空 → 单模型）、EXTRA_BODY 注入（base-url 匹配/不匹配）、JSON 非法降级 |
| LLM-GEN-012 | 必须 | **新装部署（.env 新建）不得再出现 `DEEPSEEK_API_KEY`、`LLM_FALLBACK_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`**：`.env.example` 只教新名；服务器统一后的 `.env` 删除 `DEEPSEEK_API_KEY`（冗余值）与 `LLM_FALLBACK_API_KEY`（未使用），`LLM_BASE_URL`/`LLM_MODEL` 同步迁移为 `LLM_PRIMARY_*`；代码回退链保留（旧部署不破） |

---

## 6. 影响面与回归清单

### 6.1 改动文件

| 文件 | 改动 |
|------|------|
| `backend/counseling-app/src/main/resources/application.yml` | spring.ai.openai 段读 `LLM_PRIMARY_*`；embedding 回退链更新；**:121 fallback 段同步改名 backup** |
| `backend/counseling-app/src/main/resources/application-prod.yml` | fallback 段改名 backup + 读 `LLM_BACKUP_*`；旧键别名 |
| `backend/counseling-ai/.../AiConfig.java` | 主模型手动构建 + 双模型装配 |
| `backend/counseling-ai/.../DeepSeekThinkingConfig.java` | 改造为 `LlmExtraBodyConfig`（按 base-url 匹配注入） |
| `deploy/docker-compose.prod.yml` | backend env 透传 `LLM_PRIMARY_*` / `LLM_BACKUP_*`；**删除 `DEEPSEEK_API_KEY` 相关残留** |
| `deploy/docker-compose.yml` / `deploy/docker-compose.test.yml` | 同步透传 `LLM_PRIMARY_*`（dev/test 环境，模型默认值保留各自差异：test 用 `deepseek-chat`） |
| `deploy/.env.example` | 新增主备命名块 + deprecated 注释（**旧名只注释不示例**） |
| 测试：`AiConfigTest`（新增）、`ResilientChatModelTest`（已有，回归） | 见 §5 |

### 6.2 回归验证

1. `mvn test`（counseling-ai 模块全量，重点 ResilientChatModel / LlmStreamEnhancer / OutputReviewService）
2. 本地单模型模式启动 + 对话流冒烟
3. 双模型模式：主模型指向错误 key 模拟失败 → 验证自动降级备模型 + 指标
4. DeepSeek 主模型首 token 延迟实测（回归现状 ≤3s）
5. 部署侧：`.env` 迁移后 compose 配置校验（`docker compose config` 不报错）

### 6.3 文档同步（合并时执行）

- 主文档 `06_系统配置与外部服务依赖设计.md` §LLM 配置章节（环境变量表 + fallback 说明）
- `design/his/57_配置统一纳管设计.md`（E2 冗余条目、§4.4 变量表含旧命名，需标注迁移）与 `design/04_系统部署方案.md`（§LLM 环境变量示例：LLM_API_KEY/LLM_BASE_URL/LLM_MODEL → 新命名）
- `design/his/12_技术架构.md`（§LLM 配置示例含 LLM_API_KEY/LLM_BASE_URL）与 `design/03_系统整体技术架构设计.md`（LLM_BASE_URL/LLM_MODEL 引用）同步迁移标注
- `05_系统测试指导.md` 若涉及 LLM 配置示例则同步
- BEACON 设计演进日志登记本专题

---

## 7. 决策记录（实施时已全部定案）

| # | 决策 | 选项 | 实施结论 |
|---|------|------|---------|
| D1 | 备模型命名 `BACKUP` vs `SECONDARY` | BACKUP（降级语义）/ SECONDARY（并列语义） | **BACKUP** ✅：贴合降级场景，避免与数据库主从（primary/secondary）混淆 |
| D2 | DeepSeek 自动注入 enable_thinking 是否保留 | 保留（base-url 含 deepseek 时自动注入）/ 删除（全部显式配置 EXTRA_BODY） | **保留** ✅：行为兼容，免配置回归；已由 LlmExtraBodyConfig 按 base-url 匹配实现（LLM-GEN-005） |
| D3 | 旧变量名兼容期 | 无限期保留回退 / 仅保留一个发布周期 | **无限期保留回退** ✅（yml 嵌套回退零成本），.env.example 标注 deprecated |
| D4 | 主模型是否彻底脱离 spring.ai.openai 自动配置 | 彻底（手动构建主备对称）/ 保留自动配置作默认 | **彻底** ✅：单一事实源，避免双份配置歧义；embedding 仍走自动配置（独立） |

---

## 8. 参考

- LiteLLM model_list / master_key / drop_params / fallbacks（config.yaml 官方文档，2026-06 检索）
- one-api / new-api 渠道与令牌模型
- Dify 模型供应商管理
- spring-ai 1.0.0 多 provider 前缀配置（本项目 pom 已锁定 1.0.0）
- HugeGraph-AI 配置参考（用途_角色_属性命名）
- 本项目现状：`AiConfig.java` / `ResilientChatModel.java` / `DeepSeekThinkingConfig.java` / `application.yml` / `application-prod.yml` / `docker-compose.prod.yml`（2026-08-06 部署审计核实）
