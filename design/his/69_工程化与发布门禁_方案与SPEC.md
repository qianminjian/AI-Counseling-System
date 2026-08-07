# 69 工程化与发布门禁（ARCH-009）方案与 SPEC

> 关联任务：ARCH-009（深度审计 E-1~E-5 + parent-h5 无 lint 回填，登记 TASK-TRACKER §二十八）
> 状态：✅ 已完成（2026-08-06，E-1~E-5 + parent-h5 lint 全部落地；CI 改动按已批准的 ARCH-009 方案执行）
> 依据：深度审计 2026-08-05（E-1 pytest 未入 CI / E-2 teacher-web 覆盖率 21% 贴线 / E-3 OD-013 台账失实（TTS 面板查不存在指标）/ E-4 DB 回滚演练仅 6/33 / E-5 模型投放未自动化；parent-h5 无 lint）
> 词汇：门禁 / 可观测性 / 发布就绪——见 [13 领域词汇表](../13_领域词汇表.md)

---

## 1. 背景与问题

| ID | 问题 | 证据 | 影响 |
|----|------|------|------|
| E-1 | tts/voice-service 的 pytest **未入 CI**（Python 服务无测试门禁） | `.github/workflows` 无 Python job | Python 服务改动无回归防线 |
| E-2 | teacher-web 覆盖率 21% 贴线（对比 student-h5 高覆盖） | coverage 报告实测 | 教师端核心 UI 无保护 |
| E-3 | OD-013 登记「TTS 空面板已删」与现状矛盾：llm-performance.json 仍有 2 个 TTS 面板查不存在的 `mindsafe_tts_*` 指标 | deploy/monitoring/grafana | 监控面板查询空指标 + 台账失实 |
| E-4 | DB 回滚演练仅覆盖 V28~V33（6/33 迁移无 down 脚本或未演练） | scripts/ E2 记录 | 发布回滚风险面 82% 未验证 |
| E-5 | 模型投放未自动化（prepare-models 手动执行，失败无门禁） | deploy/scripts/prepare-models.sh | 模型缺失/错误版本进生产无拦截 |
| — | parent-h5 无 lint 配置（对比两端有 .oxlintrc.json） | frontend/parent-h5 无 lint | 家长端代码质量无静态防线 |

## 2. 目标与非目标

**目标**：
- Python 服务测试进入 CI，失败阻断合并
- teacher-web 覆盖率提升至 ≥80%（新逻辑 TDD 门禁 + 存量补测分批）
- 监控面板与台账对齐（删 2 个查空指标的 TTS 面板或接指标）
- DB 回滚演练补齐（V28 前迁移 down 脚本/演练计划）
- 模型投放自动化 + 失败门禁
- parent-h5 补 lint 并接入 CI

**非目标**：
- 覆盖率口径调整（保持 jacoco/coverage 实测口径）
- 回滚机制本身改造（现有 E2 演练体系不动，只补覆盖）
- 新增监控体系（只修面板与台账）

## 3. 设计方案

### 3.1 E-1 · Python 测试入 CI

- `.github/workflows` 新增 Python job：`pip install -r requirements.txt && pytest`（tts-service/voice-service 各自跑）
- 测试文件现存于 `backend/tts-service/`、`backend/voice-service/`（test_*.py），CI 定位到两服务目录
- 失败即阻断（合并门禁，与后端 Java job 同级）

### 3.2 E-2 · teacher-web 覆盖率

- 目标：≥80%（与 student-h5 基线一致）
- 路径：存量核心模块分批补测（api 层/教师工作台/详情页/预警流程）+ 新逻辑 TDD 门禁（vitest 已就绪，WB-003 已建基建）
- 阈值接线：CI 覆盖率检查（低于阈值失败）

### 3.3 E-3 · TTS 面板台账对齐

- 决策：**删除 2 个查空指标的 TTS 面板**（`mindsafe_tts_*` 指标未埋点，接指标需语音链路埋点改造，超出本任务）
- 同步修正 OD-013 台账：登记「面板已删 + 指标未埋点，TTS 可观测性为已知缺口」（不再写「已删」结论性表述）
- 后续 TTS 埋点如立项，恢复面板为纯增量

### 3.4 E-4 · DB 回滚演练补齐

- **V01~V27 评估结论（2026-08-06 完成，逐迁移 DDL 类型核验）**：不逐一补 down 脚本——纯结构迁移 15 个（DROP 可行但属早期架构根基，实际回滚靠备份恢复），数据类不可逆 10 个（INSERT/UPDATE 改写），扩展类 2 个（V15 pgcrypto / V24 vector，生产库删除风险高）。**显式接受「不可逆迁移清单」**：V1~V27 回滚由 `deploy/restore.sh` 备份恢复承担，不补 down 脚本
- V28~V33 已有 down 脚本（rollback/ 目录 6 个）——维持
- **V34+ 强制要求**：新迁移必须带 down 脚本 + 演练记录（发布检查项，已登记 DEPLOY-GUIDE §九）

**V01~V27 逐迁移评估清单（实施时核验，2026-08-06）**：

| 迁移 | 名称 | DDL 类型 | 结论 |
|------|------|---------|------|
| V1 | init_public_schema | CREATE TABLE/INDEX | 结构·备份恢复 |
| V2 | init_tenant_template | CREATE TABLE/INDEX | 结构·备份恢复 |
| V3 | seed_data | INSERT | **数据不可逆** |
| V4 | seed_test_users | INSERT | **数据不可逆** |
| V5 | add_password_hash | ALTER ADD + UPDATE | **数据不可逆**（密码 hash 改写） |
| V6 | trial_access | CREATE + INSERT（tenants/schools/users 种子） | **数据不可逆** |
| V7 | commercial_schema | CREATE TABLE/INDEX | 结构·备份恢复 |
| V8 | commercial_enhancements | INSERT（邀请码/users） | **数据不可逆** |
| V9 | session_summary | ALTER ADD COLUMN | 结构·备份恢复 |
| V10 | emotion_diary | CREATE TABLE/INDEX | 结构·备份恢复 |
| V11 | add_gender_field | ALTER ADD COLUMN | 结构·备份恢复 |
| V12 | student_profiles | CREATE TABLE/INDEX | 结构·备份恢复 |
| V13 | invite_code_bindpin | ALTER ADD COLUMN | 结构·备份恢复 |
| V14 | password_policy | UPDATE | **数据不可逆** |
| V15 | ensure_pgcrypto | CREATE EXTENSION | 扩展类·生产库删除风险高 |
| V16 | family_code_auth | CREATE TABLE/INDEX | 结构·备份恢复 |
| V17 | fix_demo2026_tenant | UPDATE | **数据不可逆** |
| V18 | profile_personality_traits | ALTER ADD COLUMN | 结构·备份恢复 |
| V19 | quality_scores | CREATE TABLE/INDEX | 结构·备份恢复 |
| V20 | long_term_memories | CREATE TABLE/INDEX | 结构·备份恢复 |
| V21 | risk_event_lifecycle | CREATE INDEX | 结构·备份恢复 |
| V22 | prompt_versions | CREATE TABLE/INDEX | 结构·备份恢复 |
| V23 | performance_indexes | CREATE INDEX | 结构·备份恢复 |
| V24 | knowledge_base_rag | CREATE EXTENSION（vector）+ CREATE | 扩展类·生产库删除风险高 |
| V25 | disable_seed_test_users | UPDATE | **数据不可逆** |
| V26 | extend_demo2026_expiry | UPDATE | **数据不可逆** |
| V27 | cleanup_seed_data | UPDATE | **数据不可逆** |

合计：结构 15 / 数据不可逆 10 / 扩展 2 = 27。清单同时登记 DEPLOY-GUIDE §九「数据库迁移与回滚」。
- 运维协作：演练执行按现有 E2 体系（scripts/ 记录），本次仅评估不执行

### 3.5 E-5 · 模型投放自动化

- prepare-models.sh 封装为 CI job（可手动触发 workflow_dispatch）：下载→校验和→部署到模型目录→冒烟（加载测试）
- 失败即红色门禁，阻断发布 pipeline
- 版本与 manifest 校验（模型清单文件比对）

**实施完成（2026-08-06）**：

1. **`deploy/scripts/prepare-models.sh` 增强**：
   - 下载后自动生成 `MANIFEST.sha256`（`<sha256>  <相对路径>`，sha256sum/shasum -c 兼容）
   - 新增 `--verify` 模式：manifest 全量比对 + 4 个关键文件冒烟（config.json ×2 + 量化 onnx ×2），失败 exit 1
   - 已存在文件按校验和匹配才跳过（损坏文件自动删除重下）；hash 工具自动适配 Linux sha256sum / macOS shasum
   - 修复生成过程缺陷：find 扫描到重定向先创建的 `MANIFEST.sha256.tmp` 自噬入清单（已加过滤）
2. **新增 `.github/workflows/prepare-models.yml`**：workflow_dispatch 手动触发；actions/cache 缓存（key 绑定脚本 hash，未变更则命中）；下载 → verify 门禁 → 清单盘点
3. **`cd.yml deploy-frontend` 接线（发布链路门禁）**：构建 student-h5 前执行「投放 + verify」，失败即红阻断发布
   - **顺带修复发布链路缺陷**：`public/models/` 已 gitignore 不入仓，checkout 后为空——此前每次发布 dist 无模型，生产 SAME_ORIGIN 404 静默发生（无拦截）；现发布前强制投放+校验
4. **验证**：本地实测 manifest 18 行、verify 通过（exit 0）、损坏文件拦截（exit 1）、恢复后通过（exit 0）；bash -n + YAML 解析通过

### 3.6 parent-h5 lint

- 补 `.oxlintrc.json`（对齐两端配置）+ package.json script + CI 检查
- 存量违规分批清零（不与本次门禁捆绑，先接线后清理）

**实施完成（2026-08-06）**：

- `.oxlintrc.json` 已补（react + oxc 插件，规则对齐两端）
- package.json 已有 `lint: oxlint` script（无需新增）；CI 接线自动生效：`ci.yml frontend-build` matrix 已含 parent-h5 + `lint --if-present`，本次仅修正过时注释（原「parent-h5 暂无 lint」）
- 实测存量：**2 warnings / 0 errors**（exit 0，不阻断 CI）：`src/main.tsx` ProtectedRoute 与 App 同文件（only-export-components）、`src/pages/report/index.tsx` useEffect 缺依赖（exhaustive-deps）——登记待分批清理，不在本次清（SPEC：先接线后清理）
- 附带核实：parent-h5 的 vitest 配置在 `vite.config.js` 内（jsdom + setup.ts + 阈值 30/20/25/30），36 测试全绿，CI 测试门禁此前已有效

## 4. SPEC

```
CI：新增 Python job（pytest 门禁）+ parent-h5 lint job + teacher-web 覆盖率阈值 ≥80%
面板：llm-performance.json 删 2 个 TTS 面板（mindsafe_tts_* 未埋点）；OD-013 台账改「面板已删+指标未埋点」
回滚：V01~V27 逐迁移评估（down 脚本 or 不可逆登记）；V34+ 强制 down+演练
模型：prepare-models 自动化 job（校验和+冒烟+门禁）
lint：parent-h5 补 oxlint（对齐两端）
```

## 5. 验收标准（EARS 风格）

- 当 CI 配置落地后，tts/voice-service 任一测试失败必须阻断合并（pipeline 红色）
- 当 teacher-web 覆盖率检查接线后，覆盖率低于 80% 必须阻断合并
- 当面板清理后，llm-performance.json 必须不存在查询 `mindsafe_tts_*` 的面板（grep 断言）；OD-013 台账必须与现状一致
- 当回滚清单完成后，V34 及以后迁移必须带 down 脚本且演练记录在案；V01~V27 必须逐项标注（down/不可逆）
- 当模型投放自动化后，模型缺失或校验和不符必须阻断发布（不静默通过）
- 当 parent-h5 lint 接线后，lint 违规必须由 CI 检出（先接线，存量分批清零）

## 6. 风险与回滚

- **风险**：低——全部为 CI/配置/面板层改动，无业务代码变更；E-4 演练涉及数据库操作（执行演练时按红线须授权）
- **注意**：CI 配置属 AGENTS 红线 2（CI/CD 配置）——**实施前须项目负责人授权**
- **回滚**：CI job 与面板改动均为独立 revert

## 7. 关联与落点

- 关联任务：ARCH-004（doing/64，OD-013 台账联动修正）、ARCH-008（doing/68，teacher-web 测试基建复用）
- 关联设计：design/04 部署方案、design/05 测试指导、design/31 等保二级（监控合规）
- 词汇表：[13 领域词汇表](../13_领域词汇表.md)
- 登记：TASK-TRACKER §二十八 ARCH-009
