# 69 工程化与发布门禁（ARCH-009）方案与 SPEC

> 关联任务：ARCH-009（深度审计 E-1~E-5 + parent-h5 无 lint 回填，登记 TASK-TRACKER §二十八）
> 状态：📝 方案定稿 → 待实施（E-4 涉及迁移演练，须运维协作）
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

- 补齐 V01~V27 的 down 脚本（如适用）或登记「不可逆迁移清单」显式接受
- 演练计划：新迁移自 V34 起强制要求 down 脚本 + 演练记录（发布检查项）
- 运维协作：演练执行按现有 E2 体系（scripts/ 记录）

### 3.5 E-5 · 模型投放自动化

- prepare-models.sh 封装为 CI job（可手动触发 workflow_dispatch）：下载→校验和→部署到模型目录→冒烟（加载测试）
- 失败即红色门禁，阻断发布 pipeline
- 版本与 manifest 校验（模型清单文件比对）

### 3.6 parent-h5 lint

- 补 `.oxlintrc.json`（对齐两端配置）+ package.json script + CI 检查
- 存量违规分批清零（不与本次门禁捆绑，先接线后清理）

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
- **注意**：CI 配置属 AGENTS 红线 2（CI/CD 配置）——**实施前须钱敏健授权**
- **回滚**：CI job 与面板改动均为独立 revert

## 7. 关联与落点

- 关联任务：ARCH-004（doing/64，OD-013 台账联动修正）、ARCH-008（doing/68，teacher-web 测试基建复用）
- 关联设计：design/04 部署方案、design/05 测试指导、design/31 等保二级（监控合规）
- 词汇表：[13 领域词汇表](../13_领域词汇表.md)
- 登记：TASK-TRACKER §二十八 ARCH-009
