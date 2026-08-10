# doing/87 M3 运行时档位联动（独立提升小专题）

> 状态：doing（待立项） | 创建：2026-08-10 | 编号：87（doing 区接续；与 frozen/87 LLM 成本跟踪同号异题，文件名可区分）
> 来源：doing/86 部署验证清单第 5 项剥离（2026-08-10 项目负责人议决：从 doing/86 剥离为独立提升小专题，doing/86 不再跟踪）
> 关联：doing/83 后台管理端（his/83 §5.3 M3 服务切换降级监控）；doing/83 服务降级监控（his/83，OPS-MON-007 检测器）；frozen/88（企微告警推送，独立）
> 定位：**提升型小专题**（非缺陷修复）——管理端手动切换的"记录型"已上线（写 Redis 覆盖键 + manual 事件），本专题补齐**运行时消费链路**（tts/voice 实际按覆盖档位运行），使手动切换从"留痕"升级为"生效"。

---

## 一、背景与目标

### 1.1 背景

管理端 M3 降级矩阵（his/83 §5.3）已上线**记录型切换**：`DegradationMatrixService.override()` 写 Redis 运行时覆盖键（`mindsafe:degradation:override:{point}`）+ manual 事件落库 + X-Confirm 审计。但**写侧无任何运行时消费者**——tts-service / voice-service（Python）不读覆盖键，后端也无消费方：

| 降级点 | 档位词表 | 手动切换（写侧） | 运行时消费（读侧） |
|--------|---------|----------------|------------------|
| tts | cosyvoice / edge_tts | ✅ 已上线 | ❌ **缺失**（`DegradationPolicy` 只按配置+可用性选引擎） |
| asr | funasr / dashscope | ✅ 已上线 | ❌ **缺失**（进程启动时环境变量固定） |
| ser | enabled / disabled | ✅ 已上线 | ❌ **缺失**（同上） |
| llm | primary / backup | ✅ 已上线 | ❌ 缺失（Java 主备自动切换，手动覆盖价值低，本期不做） |
| voice-policy | S0/S1/S2 | ✅ 已上线 | 不纳入（VoiceDegradationPolicy 为运行时自动决策，非配置档位） |
| wake-word | local | — | 不纳入（单档位无切换语义） |

**影响**：管理端执行"强制 edge_tts / 强制 dashscope / 关闭 SER"后，实际引擎不变——操作仅留台账，运行时无效果（doing/86 验证项 5 挂远期）。

### 1.2 目标

| # | 目标 | 度量 |
|---|------|------|
| G1 | 手动切换**运行时生效**：tts/asr/ser 三降级点按覆盖键实际运行 | 切换后实际请求走覆盖档位（metrics/日志可证） |
| G2 | 档位**可观测**：/health 反映实际生效档位 | health 响应含 engine/ser 字段与覆盖一致 |
| G3 | 覆盖**可回滚**：取消覆盖回落配置默认；键带 TTL 防长期遗忘 | 取消后回落 + TTL 到期自动回落 |
| G4 | 覆盖链路**健壮**：Redis 不可达/键缺失 → 按配置默认运行（fail-open） | 不阻塞正常合成/分析 |

---

## 二、现状盘点（2026-08-10 代码实态）

| 层 | 现状 | 缺口 |
|----|------|------|
| 后端写侧 | `DegradationMatrixService.override/cancelOverride` 写 `mindsafe:degradation:override:{point}`（StringRedisTemplate）+ manual 事件 | 无 TTL（键永久，需补） |
| tts-service | `tts_policy.py` `DegradationPolicy(backends=[cosyvoice, edge_tts])`，`synthesize_with_degradation` 按配置顺序 + 可用性选择 | **不读覆盖键**；health 无档位字段 |
| voice-service | `app.py` 启动时读 `ASR_ENGINE`/`SER_ENABLED` 环境变量固定引擎 | **请求时无覆盖判定**；health 档位字段部分缺失 |
| Redis 拓扑 | compose prod 已有 redis（`mindsafe-redis`，`--requirepass ${REDIS_PASSWORD}`，internal 网络），tts/voice 同网络可达 `redis:6379` | Python 侧无 redis 客户端依赖 |
| 检测器 | `DegradationEventDetector` 跳过 manual 覆盖点（Redis 覆盖键存在时不写 auto 事件） | 无（衔接已就绪） |

---

## 三、方案设计

### 3.1 键契约（复用现有，补 TTL）

```
键名：mindsafe:degradation:override:{point}     # point ∈ tts/asr/ser
值：  档位词表内值（tts: cosyvoice|edge_tts；asr: funasr|dashscope；ser: enabled|disabled）
TTL：  7 天（新设，写侧 override 时设置；到期自动回落配置默认）
```

- 语义修正（2026-08-10 定稿）：his/83 "服务重启后回落配置默认值"指**不修改部署文件**；Redis 键持久化，重启后覆盖继续生效（7 天 TTL 兜底遗忘），管理端可随时取消。与"部署文件最小变更"原则一致。

### 3.2 tts-service 改造（tts_policy.py + app.py）

**引擎选择优先级（合成请求时）**：覆盖键 → 配置顺序 → 可用性降级

```
1. 读 mindsafe:degradation:override:tts
2. 值存在且 = cosyvoice/edge_tts：
   - 目标引擎可用 → 强制使用（跳过配置顺序）
   - 目标引擎不可用 → 按原降级链（另一引擎），并记录 degraded 事件（overridden-fallback）
3. 键不存在/Redis 不可达 → 原逻辑（配置顺序 + 可用性）
```

- 实现：`DegradationPolicy` 增加 `override_engine` 参数（或 policy 内注入覆盖读取函数）；`synthesize_with_degradation` 首步解析覆盖
- Redis 访问：`redis-py`（`pip install redis`，tts-service requirements）；连接 `redis:6379` + `REDIS_PASSWORD` 环境变量（compose 透传，同 backend 模式）；连接失败 **fail-open**（catch 后按配置默认）
- `/health`：增加 `engine` 字段 = 当前实际生效档位（覆盖值优先）

### 3.3 voice-service 改造（app.py + config 加载）

**请求时档位判定（/api/v1/voice/analyze）**：

```
ASR 引擎：覆盖键 mindsafe:degradation:override:asr 存在 → 用覆盖值；否则环境变量 ASR_ENGINE
SER：    覆盖键 mindsafe:degradation:override:ser 存在 → 用覆盖值；否则环境变量 SER_ENABLED
```

- 约束：ASR 覆盖目标引擎**必须已加载可用**（funasr 模型未加载时切 funasr 需加载 ~2GB，不做动态加载）——覆盖为 dashscope（云端）时始终可用；覆盖为 funasr 且模型未加载 → 拒绝切换（返回当前档位 + WARN 日志 + 不写 degraded 事件）
- `/health`：增加 `asr_engine` / `ser_enabled` 字段（覆盖值优先）
- Redis 访问：同 tts（redis-py + REDIS_PASSWORD；fail-open）

### 3.4 后端写侧微调（DegradationMatrixService）

- `override()` 设置键时加 `expire(7, DAYS)`（TTL）
- 无其他改动（读侧由 Python 直连）

### 3.5 管理端（admin-web）——零改动

- 矩阵视图 `overridden/overrideTo` 已消费 Redis 键；`/health` 档位由服务侧返回后矩阵 currentState 天然一致（联调验证即可）

### 3.6 取消与回落

- 管理端 `cancelOverride`（已上线）→ 删键 → 服务下次请求读不到键 → 回落配置默认
- TTL 到期（7 天）自动回落
- 回落无需重启（键驱动，无状态）

---

## 四、改动清单

| 文件 | 改动 | 量级 |
|------|------|------|
| backend/tts-service/requirements.txt | +redis | 1 行 |
| backend/tts-service/tts_policy.py | DegradationPolicy 支持覆盖引擎（读键 + fail-open + overridden-fallback 事件） | ~30 行 |
| backend/tts-service/app.py | 注入 REDIS_PASSWORD/覆盖读取；/health 加 engine 字段 | ~15 行 |
| backend/voice-service/requirements.txt | +redis | 1 行 |
| backend/voice-service/app.py | 请求时 ASR/SER 覆盖判定；/health 加档位字段 | ~30 行 |
| backend/voice-service/config.yaml（如需要） | redis 连接配置（host/port） | 注释级 |
| backend/counseling-service DegradationMatrixService | override() 加 TTL | ~3 行 |
| deploy/docker-compose*.yml | tts/voice 透传 REDIS_PASSWORD | 4 行 ×2 |
| deploy/.env.example | 注释 REDIS_PASSWORD 复用 | 注释 |
| 测试 | tts_policy 单测（覆盖/fallback/fail-open）+ voice 档位判定单测 | +8 用例 |

## 五、SPEC 验收标准

| # | 验收项 | 标准 |
|---|--------|------|
| AC-1 | tts 覆盖生效 | 写 `override:tts=edge_tts` → 合成请求实际走 edge_tts（`tts_synthesize_requests_total{engine="edge_tts"}` 增长、cosyvoice 0）；`/health` engine=edge_tts |
| AC-2 | tts 覆盖引擎不可用 | 覆盖 cosyvoice 且其不可用 → 走 edge_tts + 记录 degraded 事件（overridden-fallback）+ 不静默 |
| AC-3 | 取消覆盖回落 | `cancelOverride(tts)` → 下次请求回落配置默认（cosyvoice）；/health 同步 |
| AC-4 | voice ASR 覆盖 | 写 `override:asr=dashscope` → analyze 请求实际走 dashscope（引擎日志/指标可证） |
| AC-5 | voice SER 覆盖 | 写 `override:ser=disabled` → 情感返回中性（SER 不执行） |
| AC-6 | ASR 覆盖目标未加载 | 覆盖 funasr 但模型未加载 → 拒绝切换（当前档位 + WARN 日志，不 500） |
| AC-7 | fail-open | Redis 不可达/键缺失 → 服务按配置默认运行，不阻塞合成/分析 |
| AC-8 | 键 TTL | override 后键 TTL=7 天；到期自动回落（Redis TTL 机制） |
| AC-9 | 重启后覆盖保持 | 重启 tts/voice 容器 → 覆盖仍生效（键持久），管理端可取消 |
| AC-10 | 管理端一致性 | 矩阵视图 overridden/currentState 与 /health 实际档位一致（联调抽样） |
| AC-11 | 回归 | tts pytest 全绿（+3 覆盖用例）；voice pytest 全绿（+3）；后端 mvn 全量不回归；admin-web 无改动 |

## 六、任务归口（TASK-TRACKER 登记）

> 执行 ticket：RUNTIME-001~005（见 TASK-TRACKER §三十三，2026-08-10 登记）：
> RUNTIME-001 tts 覆盖（policy + health + 测试）→ 002 voice 覆盖（ASR/SER + health + 测试）→ 003 后端 TTL → 004 compose/env 透传 → 005 联调验收（AC-1~11）
> 执行顺序：001 → 002 → 003/004 → 005（联调依赖 001~004）

## 七、范围与后续

- 本期实现：tts/asr/ser 三降级点运行时覆盖（Redis 键驱动）+ /health 档位 + TTL + fail-open
- 不引入：llm 手动覆盖（Java 侧主备已自动，价值低）；voice-policy/wake-word 覆盖（无切换语义）；动态模型加载（funasr 未加载时拒绝切换，成本约束）
- 后续可选：覆盖键审计留痕扩展（键写入审计已有 manual 事件）；TTL 可配置化（sys_config）
