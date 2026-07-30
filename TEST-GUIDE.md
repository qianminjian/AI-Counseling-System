# MindSafe 测试指导手册

> 版本：v2.0 | 更新日期：2026-07-28
> 适用：后端 412 单测 + 2 集成测试类 | 前端 3 组件测试 | E2E 冒烟 30+ 用例

---

## 一、测试金字塔总览

```
        ╱ E2E 冒烟 ╲           smoke-test.sh（30+ API 断言）
       ╱─────────────╲
      ╱  集成测试 (IT) ╲       Testcontainers（PG16 + Redis7）
     ╱───────────────────╲
    ╱    单元测试 (UT)     ╲   412 后端 + 3 前端
   ╱─────────────────────────╲
```

| 层级 | 工具 | 数量 | 执行环境 | 耗时 |
|------|------|------|----------|------|
| 后端单元测试 | JUnit 5 + Mockito + AssertJ | 412 | 本地 / CI | ~15s |
| 后端集成测试 | Testcontainers + TestRestTemplate | 2 类 / 18 方法 | 需 Docker | ~60s |
| 前端组件测试 | Vitest + Testing Library | 3 文件 | 本地 / CI | ~5s |
| E2E 冒烟 | Bash + curl | 30+ 用例 | 部署后 | ~30s |

---

## 二、后端单元测试

### 2.1 运行方式

```bash
# 全量（推荐，从 parent pom 执行）
cd backend && mvn test

# 指定模块
mvn test -pl counseling-service

# 指定类
mvn test -pl counseling-service -Dtest=ConversationServiceImplTest

# 指定方法
mvn test -pl counseling-service -Dtest="ConversationServiceImplTest#nudge*"
```

> **注意**：`-pl counseling-service` 单独执行时无法解析 `counseling-ai` 等模块依赖。
> 若遇 `程序包不存在` 错误，改用 `mvn test`（全 reactor）或先 `mvn install -DskipTests`。

### 2.2 模块分布

| 模块 | 测试类数 | 核心覆盖 |
|------|----------|----------|
| counseling-service | ~30 | 对话/风险/画像/通知/工具箱/知识库/实验/质量 |
| counseling-ai | ~8 | 风险检测/Prompt 模板/CBT 路由/情绪状态机/TTS |
| counseling-api | 1 | ChatController 监护人同意门禁 |
| counseling-tenant | 1 | 租户行级隔离 |

### 2.3 编写规范

- 类名：`{被测类}Test.java`（Surefire 自动识别 `*Test`）
- 嵌套结构：`@Nested` + `@DisplayName` 按功能分组
- Mock 策略：构造器注入 mock，纯规则组件用真实实例
- 断言：AssertJ `assertThat`（不用 JUnit assert）
- 命名：`方法名_条件_期望结果`（英文）

### 2.4 关键测试类说明

| 测试类 | 用例数 | 覆盖要点 |
|--------|--------|----------|
| `ConversationServiceImplTest` | ~45 | 问候/nudge/保密告知/风险/加密/年级适配 |
| `RiskDetectorServiceImplTest` | ~30 | 红/橙/黄/绿硬规则 + 分类准确性 |
| `RiskScoreCalculatorTest` | ~15 | C-SSRS 评分/强制升级/保护因素/置信度 |
| `PromptVersionServiceTest` | ~8 | A/B 分桶/版本解析/classpath 降级 |
| `KnowledgeCorpusIngestServiceTest` | ~6 | 危机缓入铁律/幂等/知识域映射 |
| `P2FinalBatchTest` | ~40 | 情绪编排/实验/画像/语音/TTS 批量覆盖 |

---

## 三、后端集成测试

### 3.1 基础设施

- 基类：`AbstractIntegrationTest`（counseling-app 模块）
- 容器：PostgreSQL 16（pgvector 镜像）+ Redis 7
- 迁移：Flyway 自动执行 `db/migration/`
- 注解：`@Testcontainers(disabledWithoutDocker = true)` — **无 Docker 自动跳过，不阻塞本地**

### 3.2 运行方式

```bash
# 需要 Docker Desktop 运行中
cd backend && mvn verify -pl counseling-app -am

# 仅运行集成测试（跳过单测）
mvn verify -pl counseling-app -am -DskipTests -Dit.test=AuthFlowIT

# 确认 Docker 可用
docker info > /dev/null 2>&1 && echo "Docker OK" || echo "Docker NOT available"
```

### 3.3 现有集成测试

| 类 | 方法数 | 覆盖链路 |
|----|--------|----------|
| `AuthFlowIT` | 6 | 试用注册 → JWT → /auth/me → 未认证拒绝 → 角色授权 |
| `ConversationRiskFlowIT` | 12 | 注册 → 监护人同意 → 创建会话 → RED 风险消息 → 风险事件持久化 → 会话升级 → 教师预警队列 → 通知 → 仪表盘 → 会话历史 |

### 3.4 编写新集成测试

```java
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MyFlowIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;       // 直接 DB 操作（绕过 SMS 等外部依赖）
    @Autowired private JwtTokenProvider jwtTokenProvider; // 生成任意角色 JWT

    @Test @Order(1)
    void step1_setup() { /* 注册/插数据 */ }

    @Test @Order(2)
    void step2_action() { /* 调用 API */ }

    @Test @Order(3)
    void step3_verify() { /* 查 DB 断言 */ }
}
```

**要点：**
- 类名必须以 `IT` 结尾（Failsafe 插件识别 `*IT`）
- 用 `@Order` 保证步骤顺序
- 外部依赖（SMS/LLM）用 JdbcTemplate 直接落库绕过
- RED 风险路径无需 LLM（硬短路跳过 AI 生成）

### 3.5 测试配置

文件：`counseling-app/src/test/resources/application-test.yml`

- LLM 指向占位地址 `http://localhost:19999`（不实际调用）
- Flyway 启用，自动建表
- 初始化脚本：`init-test-db.sql`（CREATE EXTENSION vector/uuid-ossp/pgcrypto）

---

## 四、前端测试

### 4.1 运行方式

```bash
cd frontend/student-h5
npm test           # 单次运行
npm run test:watch # 监听模式
```

### 4.2 现有测试

| 文件 | 覆盖 |
|------|------|
| `src/test/EmotionSelect.test.jsx` | 情绪选择组件交互 |
| `src/test/SpeechBubble.test.jsx` | 对话气泡渲染 |
| `src/test/WelcomeGuide.test.jsx` | 欢迎引导流程 |

### 4.3 配置

- 框架：Vitest 3.x + @testing-library/react
- 配置：`vitest.config.js`
- CI 中通过 `npm test --if-present` 执行（仅 student-h5 有测试）

---

## 五、E2E 冒烟测试

### 5.1 运行方式

```bash
# 默认 localhost:8080
./tests/e2e/smoke-test.sh

# 指定环境
BASE_URL=https://sit.example.com ./smoke-test.sh

# 指定账号
TEACHER_USER=teacher01 TEACHER_PASS=xxx ./smoke-test.sh
ADMIN_USER=admin ADMIN_PASS=xxx ./smoke-test.sh
```

### 5.2 覆盖范围（30+ 用例）

- 健康检查（/actuator/health）
- 认证流程（注册/登录/me/未认证拒绝）
- 对话链路（创建会话/发消息/结束）
- 教师端（仪表盘/预警/学生列表）
- 管理端（邀请码/租户）
- 安全（SQL 注入/XSS 探测）

---

## 六、CI 流水线

文件：`.github/workflows/ci.yml`

### 6.1 触发条件

- push / PR → `main` 或 `develop`

### 6.2 流水线阶段

```
┌─────────────────────┐  ┌──────────────────┐  ┌─────────────────────┐
│ backend-build-test  │  │ dependency-scan  │  │ frontend-build      │
│                     │  │                  │  │ (×3 matrix)         │
│ 1. mvn compile      │  │ Trivy fs scan    │  │ npm ci + build      │
│ 2. mvn verify       │  │ CRITICAL/HIGH    │  │ npm run lint        │
│ 3. Coverage gate    │  │ exit-code=1 阻断 │  │ npm test            │
│ 4. Upload report    │  │                  │  │                     │
└─────────────────────┘  └──────────────────┘  └─────────────────────┘
```

### 6.3 覆盖率门禁

- 工具：JaCoCo（counseling-app 聚合报告）
- 当前基线：~46.5%（2026-07-28）
- 门禁阈值：**≥ 40%**（低于即 CI 失败）
- 目标：逐步提升至 80%（TEST-001 规划）
- 报告产物：`backend/counseling-app/target/site/jacoco-aggregate/`

### 6.4 依赖漏洞扫描

- 工具：Trivy（替代 OWASP dependency-check）
- 策略：CRITICAL + HIGH 漏洞 → `exit-code=1` 阻断合入
- 豁免：`ignore-unfixed: true`（无修复版本的不阻断）
- 抑制文件：`.github/dependency-check-suppressions.xml`（历史遗留，Trivy 不读取）

---

## 七、测试数据与种子

### 7.1 数据库种子（Flyway 迁移自带）

| 数据 | 来源 | 用途 |
|------|------|------|
| DEMO2026 邀请码 | V6 + V8 + V17 + V26 | 试用注册（有效期至 2027-12-31） |
| 试用租户 `90000000-...-001` | V6 | 试用链路固定租户 |
| 试用学校 `90000000-...-011` | V6 | 试用学校 |

### 7.2 集成测试数据策略

- **不依赖种子用户**：测试自行注册/插入
- **监护人同意**：JdbcTemplate 直接写入 `consent_records`（绕过 SMS）
- **教师用户**：JdbcTemplate 插入 + `JwtTokenProvider.generateToken()` 生成 JWT
- **LLM 隔离**：RED 风险硬短路跳过 AI；非 RED 路径在 IT 中暂不覆盖

---

## 八、SIT 测试策略

### 8.1 已覆盖核心链路

| 链路 | 覆盖方式 | 状态 |
|------|----------|------|
| 注册 → 认证 → JWT | AuthFlowIT | ✅ |
| 对话 → RED 风险 → 预警 → 教师 | ConversationRiskFlowIT | ✅ |
| 风险硬规则（30 关键词） | RiskDetectorServiceImplTest | ✅ |
| 风险评分（C-SSRS） | RiskScoreCalculatorTest | ✅ |
| 保密告知注入 | ConversationServiceImplTest | ✅ |
| 字段加密（AES-256-GCM） | FieldEncryptionWiring | ✅ |
| 监护人同意门禁 | ChatControllerTest | ✅ |

### 8.2 SIT 期间补充方向

| 优先级 | 链路 | 说明 |
|--------|------|------|
| P1 | 对话 → ORANGE 风险 → 语义分类 | 需 mock LLM 或 WireMock |
| P1 | 教师处置闭环（认领→处理→回访） | 扩展 ConversationRiskFlowIT |
| P2 | 家长端同意撤回 | ConsentWithdrawalService 链路 |
| P2 | 多租户隔离越权 | 跨租户 selectById 应返回空 |
| P3 | 性能基线（并发对话） | tests/performance/chat-load.js（k6） |

### 8.3 本地无 Docker 时的测试策略

```bash
# 单元测试正常执行（无 Docker 依赖）
mvn test

# 集成测试自动跳过（@Testcontainers(disabledWithoutDocker=true)）
mvn verify  # IT 显示 skipped，不报错

# 冒烟测试需要运行中的服务
docker compose -f deploy/docker-compose.test.yml up -d  # 可选
./tests/e2e/smoke-test.sh
```

---

## 九、常见问题

### Q1: `mvn test -pl counseling-service` 报 `程序包 com.mindsafe.ai 不存在`

**原因**：单模块执行无法解析 reactor 内兄弟模块依赖。
**解决**：从 parent 执行 `mvn test`，或先 `mvn install -DskipTests` 安装到本地仓库。

### Q2: 集成测试本地全部跳过

**原因**：Docker Desktop 未运行。
**解决**：启动 Docker Desktop，或接受跳过（CI 中正常执行）。

### Q3: 新增 Service 后测试报 `NoSuchMethodError`

**原因**：构造器签名变更，测试中手动 `new` 的调用未同步。
**解决**：搜索测试文件中所有 `new XxxServiceImpl(` 调用，补齐新参数。

### Q4: CI 覆盖率门禁失败

**原因**：新增代码无测试覆盖，拉低整体覆盖率至 40% 以下。
**解决**：为核心逻辑补充单元测试，或调整门禁阈值（需审批）。

### Q5: Trivy 扫描阻断

**原因**：依赖存在 CRITICAL/HIGH 漏洞且已有修复版本。
**解决**：升级对应依赖版本；若为误报，在 Trivy 配置中添加 ignore 规则。

---

## 十、文件索引

| 文件/目录 | 说明 |
|-----------|------|
| `backend/*/src/test/java/` | 后端单元 + 集成测试源码 |
| `backend/counseling-app/src/test/resources/` | 测试配置 + DB 初始化脚本 |
| `frontend/student-h5/src/test/` | 前端组件测试 |
| `frontend/student-h5/vitest.config.js` | Vitest 配置 |
| `tests/e2e/smoke-test.sh` | E2E 冒烟测试脚本 |
| `tests/performance/chat-load.js` | k6 性能测试（远期） |
| `.github/workflows/ci.yml` | CI 流水线定义 |
| `deploy/docker-compose.test.yml` | 测试环境 Docker 编排 |
