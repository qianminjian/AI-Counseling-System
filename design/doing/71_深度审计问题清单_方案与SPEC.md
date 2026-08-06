# doing/71 深度审计问题清单（方案与 SPEC）

> 编号：DOC-061 | 创建：2026-08-06 | 状态：⏳ 待处理（问题清单已定稿，修复任务后续统一排期实施）
> 来源：独立架构深度审计（4 路并行 agent 交叉印证：后端 / 前端三端 / 工程化部署 / 设计一致性）
> 审计基线：develop @ 27ad409（design 合并归档完成后），工作区干净
> 关联：ARCH-001~010 治理系列（his/61~70，本审计为其延续轮次）；frozen/38 计费套餐、frozen/62 数据脱敏导出

---

## §1 审计概述

### 1.1 审计范围与方法

| 维度 | 审计面 | 方法 |
|---|---|---|
| 后端 | counseling-common/domain/ai/service/api/app（Java 21 target，Spring Boot 3.5.12）+ tts-service/voice-service（FastAPI） | 关键链路抽查（认证/多租户/AI 对话/上传），全库扫描 TODO/空实现/僵尸调用 |
| 前端 | student-h5（19.5k 行）/ teacher-web / parent-h5，三端约 2 万行源码全读 | 三端同能力对比（请求封装/错误处理/token 策略/构建链） |
| 工程化部署 | 15 个 shell（bash -n 全过）+ 10 个 YAML（safe_load 全过）+ 3 个 workflow + 34 个 Flyway 迁移 + nginx 双配置 + 备份恢复链路 | 逐行审阅 + DEPLOY-GUIDE 对照实战走查 |
| 设计一致性 | 13 份主文档 + DESIGN-OVERVIEW/TASK-TRACKER/BEACON vs 全量实现 | 抽查 20+ 功能点（含 15 个图例实证点） |

### 1.2 综合评分（0-10 悲观口径）

| 维度 | 后端 | 前端 | 工程化 | 设计一致性 | 综合 |
|---|---|---|---|---|---|
| 架构合理性 | 6.5 | 7.0 | 7.0 | 7.0 | 6.5 |
| 代码质量 | 6.5 | 6.5 | 7.0 | 8.0 | 6.5 |
| 工程化规范 | 7.5 | 6.0 | 7.0 | 7.0 | 6.5 |
| 团队协作友好度 | 7.0 | 5.5 | 5.0 | 6.0 | 6.0 |
| 代码逻辑虚化度（越高越实） | 8.0 | 7.0 | 5.5 | 7.0 | 6.5 |

**发布就绪度：约 4/10**——功能代码层已达试点交付水准，但 4 项 P0 阻断（声纹跨租户漏洞 + CD 链路三处硬断裂）与契约文档层（design/08）系统性失实未解决，**当前状态不可直接发布生产**。

### 1.3 总体结论

- 骨架真实成型：模块依赖方向正确（common←domain←ai←service←api←app）、多租户行级隔离 fail-fast 完整、风险事件 fail-fast + outbox 双保险、日志全局 PII 脱敏、ARCH-001~010 治理有效（台账抽查基本属实）
- 主要风险集中在三处：① 安全（声纹 verify 跨租户）；② 发布链路（手册→部署→CD 端到端未闭环）；③ 契约文档（08 错误码/响应字段失实 + 主文档内部互斥）

---

## §2 审计裁决记录（钱敏健，2026-08-06）

| 项 | 裁决 | 处理 |
|---|---|---|
| 声纹免密登录系统过度设计质疑（OD-声纹） | ❌ 撤销质疑——**非过度设计**：对小朋友是最合适的入口，后续默认本地声纹模式 | 从过度设计质疑清单移除；声纹相关安全缺陷（AUD-001）**不受此裁决影响，仍为 P0 必修**；「双模式收敛」建议并入 AUD-001 修复方向（与默认本地决策一致） |
| EntitlementFilter 套餐矩阵过度设计质疑（OD-Entitlement） | ⚪ 冻结项——已纳入 frozen/38，不纳入当前分析 | 从过度设计质疑清单移除，本报告不再讨论 |

---

## §3 P0 阻断生产（4 项，发布前必须解决，不允许延后）

### AUD-001【P0】声纹验证公开端点跨租户全库 1:N 比对 + 阈值偏低 + 长期凭证

- 位置：`backend/counseling-api/src/main/java/com/mindsafe/api/config/SecurityConfig.java` L73（`/api/v1/voiceprint/verify` permitAll）；`VoiceprintController.doVerify`
- 证据：verify 为免认证登录端点；`doVerify` 在**系统作用域**下 `selectList` 全表加载声纹记录（无 `tenant_id` 过滤）后与请求 embedding 全量余弦比对，阈值 **0.55**（远低于 local 端 0.70）；命中即签发 voice token（**有效 90 天**）；`resolveClientIp` 直接信任最右 XFF 可伪造绕过 10 次/分 IP 限流。攻击面：抓包或设备侧拿到他人 embedding 重放即命中 → **跨租户越权登录**
- 建议：verify 强制携带租户维度并 `tenant_id` 过滤（禁止系统作用域全表扫描）；服务端阈值对齐 local 0.70；凭证有效期缩短 + 二次因素；启用请求级声纹指纹限流。与「默认本地声纹」决策同向：remote 链路收敛或加固

### AUD-002【P0】setup-server.sh 目录布局与 cd.yml 期望不符（按手册初始化后 CD 必失败）

- 位置：`deploy/setup-server.sh` L73-80；`.github/workflows/cd.yml` L167-181
- 证据：setup-server.sh 仅复制 `docker-compose.test.yml` + `nginx/` + `.env.example` 到 `/guju/mindsafe/`（无 `deploy/` 子目录，不含 prod.yml/monitoring.yml/backup.sh/restore.sh）；cd.yml deploy job 执行 `cd $REMOTE_DIR/deploy && docker compose -f docker-compose.prod.yml pull`。按 DEPLOY-GUIDE Step 3→7 走完后 CD 首跑必失败
- 建议：setup-server.sh 完整同步 `deploy/` 目录到 `/guju/mindsafe/deploy/`（与 deploy.sh rsync 路径对齐），或 cd.yml 部署前先 rsync compose 文件

### AUD-003【P0】DEPLOY-GUIDE 用 test.yml 首启，与 prod.yml 容器名/网络名冲突且无切换步骤

- 位置：`DEPLOY-GUIDE.md` L183-190；`docker-compose.test.yml` L9；`docker-compose.prod.yml` L7
- 证据：两个 compose 共用 `container_name`（mindsafe-pg/backend/nginx）与网络 `mindsafe-internal`；手册称"之后每次 git push main 自动触发部署"但从未提及需先 down test 环境 → CD `up` 报 container name already in use
- 建议：手册补「首次 prod 切换」步骤（down test + 确认容器清理）；或 CD 部署前幂等 down 冲突容器

### AUD-004【P0】CD 健康检查/冒烟在 IP + 域名证书场景必然失败

- 位置：`cd.yml` L183-191、L354-357
- 证据：DEPLOY-GUIDE Step 6 定义 DEPLOY_HOST 为 ECS 公网 IP、SMOKE_URL 示例 `http://<IP>`；deploy job 硬编码 `curl https://$DEPLOY_HOST/actuator/health`（无 `-k`），smoke 默认 https。prod 强制 TLS 且证书绑定域名时，对 IP 的 curl 证书校验必然失败 → 轮询 30 次后红
- 建议：健康检查改用 `SMOKE_URL`/域名变量，或 `curl -k`（内网信任域）；手册明确 DEPLOY_HOST 应填域名

---

## §4 P1 高优（9 项）

### AUD-005【P1】guardian-consent/confirm permitAll 配置与实现严重矛盾（注释声称 confirmToken 流程，实现实为 JWT 身份 + 短信验证码）

- 位置：`SecurityConfig.java` L81-82；`AuthController.java` L456-464
- 证据：SecurityConfig 注释「SMS 链接触发，无 JWT，靠 confirmToken 校验」并 permitAll；实现内 `(TenantContext) auth.getDetails()` + `(UUID) auth.getPrincipal()` 要求已认证 JWT。未认证调用（SMS 链接直开场景）→ NPE → 500；confirmToken 流程从未实现
- 建议：二选一——按注释实现真正免登录 confirmToken 确认链路（对账监护人手机 + 一次性 token），或删除 permitAll 与注释（要求学生登录后确认）；同时为 permitAll 端点补全局空 Authentication 保护

### AUD-006【P1】tts-service 音色性别硬编码 `persona_gender="female"`，与设计 #17 男女音色分离矛盾

- 位置：`backend/tts-service/app.py` L327
- 证据：`build_instruction` 支持按 `persona_gender` 选 native_voices（L248），调用方恒传 "female"——男性音色 persona（如 dashu/xiaotaiyang）方言场景被映射为原生女声，用户配置失效
- 建议：persona_gender 由 persona 配置（请求体/DB）传入；无字段时按 persona 名推导，不默认女声

### AUD-007【P1】三端 Token 存储策略不一致，teacher/parent 双 token 明文落 localStorage

- 位置：`frontend/teacher-web/src/api.ts` L5-22；`frontend/parent-h5/src/utils/auth.ts`；`frontend/student-h5/src/api.ts` L11-31
- 证据：teacher/parent `setToken/setRefreshToken` 写 localStorage（refreshToken 亦明文）；student 写 sessionStorage（共享 Pad 防残留）。XSS 单点突破即全量泄露；CSP（script-src 'self'）为唯一防线，属单点防御
- 建议：统一「会话级 sessionStorage + 内存双保险」；teacher/parent 至少将 refreshToken 移出 localStorage

### AUD-008【P1】student-h5 登录页无条件预下载两个端侧模型（约 40MB+）

- 位置：`frontend/student-h5/src/components/LoginPage.tsx` L31-35
- 证据：`useEffect` 挂载即 `preloadVoiceprintModel() + preloadWakeModel()`；Whisper-tiny 约 20MB + WeSpeaker 约 20MB。儿童用家长流量被一次性消耗
- 建议：改为「已开启语音功能/点击声音入口后下载」或「WiFi 预下载、4G 延迟到首次使用」；下载前给流量提示

### AUD-009【P1】CD 手动回滚（workflow_dispatch）与常规发布 job 并发竞态写 .env

- 位置：`cd.yml` L12-19、L154、L282
- 证据：rollback job 无 `needs`、条件仅 `workflow_dispatch`；deploy 条件 `refs/heads/main`。在 main 上手动 dispatch 回滚时，deploy（sed 写新 SHA）与 rollback（sed 写旧 tag）并行修改同一 .env → 结果不确定
- 建议：deploy/deploy-frontend/smoke 增加 `if: github.event_name == 'push' && github.ref == 'refs/heads/main'`；rollback 用 `concurrency` 组串行化

### AUD-010【P1】监护人同意开关变量名漂移：.env 配置对 prod 不生效

- 位置：`docker-compose.prod.yml` L74（读 `CONSENT_TRIAL_AUTO_GRANT`）；`docker-compose.yml` L97（读 `MINDSAFE_CONSENT_TRIAL_AUTO_GRANT`）；`.env.example` L41（只定义 MINDSAFE_ 前缀，注释强调必须带前缀）
- 证据：AUTH-040 合规开关在 prod 上永远走默认 false——试运行想开启会静默失败。违反 design/04 §11.2 铁律 1（.env 定义但 compose 未透传 = 死变量）
- 建议：统一变量名（含本地与 prod）并同步 .env.example

### AUD-011【P1】design/08 §5.2 错误码登记表与代码系统性错位（7/11 错位）

- 位置：`design/08_系统功能概要设计.md` L764-780（自称"业务错误码唯一登记表，以本节为准"）；`counseling-common/.../dto/ErrorCode.java` L14-50
- 证据：文档 `10001=参数校验` vs 代码 `10001=INTERNAL_ERROR`；`10002=资源不存在` vs 代码 `PARAM_INVALID`；`10003=资源状态冲突` vs `RESOURCE_NOT_FOUND`；`20003=跨租户访问` vs `CONSENT_REQUIRED`；`30001=配额超限` vs `SESSION_NOT_FOUND`；`40001=AI服务不可用` vs `RISK_ESCALATED`（AI 不可用实际 60002）；`40002=风险拦截` 代码无此项。L780 自注"登记表统一修复并入 WB-001"但至今未修
- 建议：以 ErrorCode.java 为基准重写 §5.2 并删"以本节为准"字样，或标注"已失效，见 OpenAPI 快照"

### AUD-012【P1】design/08 §5.4 创建会话/SSE 响应字段与代码 DTO 不一致

- 位置：`design/08` L830-847；`SessionInfo.java`（仅 sessionId/greeting/createdAt 三字段）；`StreamMessageEvent.java`（type/content/metadata 四事件类型）
- 证据：文档宣称 `remainingTurns`/`suggestedActions`/`showBreathingExercise`/`emotionLabel`/`state` 等字段代码全工程 grep 零匹配。作为"前后端并行开发契约"（§五自述）失实，新成员按此联调必踩坑
- 建议：§5.4 响应示例改写为真实形态，或文档头标注"响应字段以契约测试 + gen-openapi-snapshot.sh 为准"

### AUD-013【P1】`/api/v1/analytics/*` 三端点反向幽灵：零登记、零消费者、个人级数据明文零审计

- 位置：`DataAnalyticsController.java`（`/analytics/intervention-effect`、`/growth-trajectory`、`/school-report`）
- 证据：13 份主文档零登记（仅 frozen/62 提及）；teacher-web 前端零调用；frozen/62 已审计标注"后两者明文输出 studentUserId/里程碑/风险时间线等个人级数据但完全无审计日志"——明知缺口既未修复也未登记
- 建议：三端点要么接审计 + 挂前端，要么按 ARCH-004 方式裁决删除；至少回填 08 §5.5 与审计登记

---

## §5 P2 中等问题（汇总）

### 后端
- **AUD-014【P2】** 多路降级 fail-open 且无告警：Redis 限流异常 `return true` 放行（`RateLimiter.java` L69-75）；EntitlementFilter 查库异常降级 STANDARD；AiChatServiceImpl 四个 LLM 辅助方法（generateSessionSummary/extractConversationInsights/evaluateConversationQuality/summarizeSessionProgress）失败一律 `return null`，仅日志无指标（ARCH-010 D3 未覆盖）。建议：fail-open 保留但配失败计数 + Prometheus 告警；LLM 方法返回 Optional 并打计数
- **AUD-015【P2】** BizException 全局返回 HTTP 200（`GlobalExceptionHandler`）：业务错误以 200 + body.code 返回（含 API_GONE 410 语义被 200 承载），nginx/网关无法按状态码告警重试。建议：按 ErrorCode 映射 4xx/5xx，body 保留 code 兼容前端
- **AUD-016【P2】** voice-service 每请求新建线程池且超时后线程不取消（`app.py` L289）：建议进程级单例线程池（FastAPI lifespan），超时 `future.cancel()` 并记录

### 前端
- **AUD-017【P2】** 定时器未清理：`ChatRoom.tsx` L104（setTimeout setVoiceNotice 无 cleanup）、`SettingsPanel.tsx` L77（copyCode 后 setTimeout 无 cleanup）→ 卸载后 setState
- **AUD-018【P2】** teacher 下载类接口 401 处理 5 处重复模板 + `window.open` 无 await（`api.ts` L234/296/304/316/329）：建议收敛 `downloadBlob(url, filename)` 封装
- **AUD-019【P2】** teacher OverviewPanel 加载失败静默（`OverviewPanel.tsx` L53-57 catch 后仅 setLoading(false)，面板留白无错误态/重试，与 BigScreen 不一致）
- **AUD-020【P2】** EmotionDiary 打卡无错误处理/无 loading（`EmotionDiary.tsx` L107-117 无 try/catch，unhandled rejection；L98-105 三处 `.catch(() => {})` 静默吞错）
- **AUD-021【P2】** 三端工程版本鸿沟：student/teacher 用 react 19.2.7/vite 8/oxlint 1.71，parent 用 react 19.0.0/vite 6/oxlint 0.15——建议 parent 对齐消灭 2 个 major 构建器差
- **AUD-022【P2】** parent-h5 覆盖率门禁形同虚设：`vite.config.js` L32-37 阈值 lines:30/branches:20/functions:25 vs teacher 80/75/60——建议提升至 teacher 同等（60+）
- **AUD-023【P2】** a11y：student/parent 禁用页面缩放（`index.html` maximum-scale=1.0, user-scalable=no）违反 WCAG 1.4.4，儿童/老年家长受影响——建议移除 maximum-scale
- **AUD-024【P2】** ModelDownloadProgress 把错误堆栈渲染给终端用户（`LoginPage.tsx` L669，detail 源自 `useVoiceprint.ts` L142-144 的 `err.stack` 切片）——向儿童界面泄露内部文件路径；建议仅友好文案
- **AUD-025【P2】** VoiceLoginOverlay 人为假延迟（L223/229/252/285 `setTimeout 3000/1500` 模拟动画）：建议绑定真实推理耗时下限，弱网下避免假等待
- **AUD-026【P2】** copyOnnxWasm 隐式依赖未声明 + dev 预发布版本：`vite.config.js` L30 从 node_modules/onnxruntime-web/dist 复制，但 package.json 未声明（来自 transformers 传递依赖，锁定 `1.26.0-dev.20260416`）——建议显式加入生产依赖并固定稳定版
- **AUD-027【P2】** useWakeWord 调试日志残留（约 8 处 console.debug + 6 处 console.warn）：建议 DEV 条件包裹或移除

### 工程化部署
- **AUD-028【P2】** 全仓无 .dockerignore：backend/frontend 构建上下文含 target/（数百 MB）、node_modules——建议各服务补 .dockerignore（target/node_modules/dist/__pycache__/.pytest_cache/wheels）
- **AUD-029【P2】** 监控栈三个镜像 `:latest` 未固定版本（docker-compose.monitoring.yml prometheus/alertmanager/grafana）：建议固定主版本
- **AUD-030【P2】** nginx 层无限流：登录/注册/验证码等公开端点无 nginx 层 limit_req（仅 /api/v1/chat/** 应用层拦截）——建议 default.conf/default-ssl.conf 加 limit_req_zone 并对 /api/v1/auth/ 限流
- **AUD-031【P2】** Grafana 公网暴露 3002 + HTTP 明文 + 仅密码保护（docker-compose.monitoring.yml L66-72），等保二级传输加密红线——建议绑定 127.0.0.1 + ssh 隧道或置于 nginx 443 后
- **AUD-032【P2】** 备份 cron 未自动化接入：手册称"宿主机 cron 02:00 daily/weekly/monthly 分层"但 setup-server.sh 不配置 cron，backup.sh 仅注释给命令；恢复演练无证据——建议 setup-server.sh/deploy.sh 幂等写入 crontab + 补一次 restore.sh 演练记录
- **AUD-033【P2】** setup-server.sh 两种执行方式行为不一致：stdin 方式下 `$0=bash` 导致 SCRIPT_DIR 解析错误、配置复制被静默跳过——建议删 stdin 用法说明统一 `./setup-server.sh`，或 BASE_DIR 硬编码
- **AUD-034【P2】** prod nginx 挂载不存在的 dist 目录时静默创建空目录（docker-compose.prod.yml L173-175），前端空白无 fail-fast——建议 prod 首次启动前置检查 dist 存在，缺失 exit 1
- **AUD-035【P2】** cd.yml sed 循环含 frontend 但 prod compose 无 frontend 服务（L170-177 死写入 FRONTEND_IMAGE；notify 注释自认"生产不消费镜像"仍构建推送）——建议 IMAGES 剔除 frontend，镜像是否继续构建另行决策

### 设计一致性
- **AUD-036【P2】** design/08 工具箱 ⬜ vs design/09 + 代码 🟩（三处标 ⬜/TOOL-001/002，实际 ToolboxController + ToolboxPanel/ToolPractice/SosPanel 已实现）——08 三处 ⬜ 改 🟩
- **AUD-037【P2】** design/08 知识库审核流 ⬜ vs 09 KB-102/G-2 🟩（实际 ReviewWorkflowStateMachine + PUT /review + editorial 报表已接线）——08 改 🟩 并更正端点形态
- **AUD-038【P2】** DESIGN-OVERVIEW L158-159 "7 Agent 协同" 残留（世界 B 已删，03 L185 已声明删除，代码零匹配）——改述为"世界 A 单 prompt + CTX-Agent 4 段式简报"
- **AUD-039【P2】** 审计日志 11 ⬜ vs 08 🟩 互斥（实际 AuditLogService 命令式埋点 72 处 + /admin/audit-logs 查询；AOP 切面确实无）——11 拆两行：留痕/查询 🟩 + AOP 切面 ⬜
- **AUD-040【P2】** WS HEARTBEAT 协议漂移：08 §2.9 宣称 `{type:'HEARTBEAT'}` JSON 心跳，实际后端处理裸文本 ping/pong，前端无心跳实现——文档改"ping/pong 文本心跳（后端已支持，前端未启用）"或前端补心跳

---

## §6 P3 低优先级（汇总）

| 编号 | 问题 | 位置 |
|---|---|---|
| AUD-041 | `ratelimit:` Redis key 无租户前缀（ARCH-010 D2 仅覆盖 session:state:） | RateLimiter.java |
| AUD-042 | tts-service 用弃用 `@app.on_event("startup")`；voice-service 文件后缀未白名单化 | tts/voice app.py |
| AUD-043 | MyBatis-Plus `.last("LIMIT " + Math.min(...))` 字符串拼接（值已钳制无注入面，但用法不安全） | 分页相关 Mapper |
| AUD-044 | JDK 25 运行时未实测（jacoco/byte-buddy/mockito 版本已覆盖）——CI 加 JDK 25 冒烟 | ci.yml |
| AUD-045 | parent privacy 页 `<Link to="/parent/">` 与 basename 重复（依赖 catch-all Navigate 回写） | parent-h5 privacy/index.tsx |
| AUD-046 | ThemeProvider 裸用 localStorage 无失败安全（与 useWakeEnabled 不一致） | student-h5 ThemeProvider.tsx |
| AUD-047 | teacher Dashboard 多层轮询叠加（15s+30s+30s 无节流聚合） | Dashboard.tsx/TodayTodoPanel/BigScreen |
| AUD-048 | teacher StudentPanel 2 处 `any` 残留 | StudentPanel.tsx L183/223 |
| AUD-049 | teacher Dashboard 每次渲染重建 menuItems（应模块级常量/useMemo） | Dashboard.tsx L135-169 |
| AUD-050 | MessageBubble TTS 播放按钮无 aria-label（读屏器不读 title） | MessageBubble.tsx L66-84 |
| AUD-051 | DEPLOY-GUIDE 要求 GITHUB_OWNER 必填但 .env.example 无该变量 | .env.example |
| AUD-052 | ci.yml 未显式收敛 permissions（建议 contents: read） | ci.yml L16 |
| AUD-053 | cd.yml workflow_dispatch image_tag 输入无格式校验（注入面，建议正则校验） | cd.yml L15 |
| AUD-054 | 镜像浮 tag 未 pin digest（可接受，Trivy 已入 CI） | 各 Dockerfile |
| AUD-055 | postgres/redis/nginx 无 mem_limit（2C2G 主机 pg 可吃满内存） | docker-compose 各文件 |
| AUD-056 | Grafana dashboards.yml editable: true（建议 false） | monitoring/grafana |
| AUD-057 | design/02 版本头未登记 V33（risk_event_structured_score） | design/02 L4/L334/L370 |
| AUD-058 | 波波小精灵 08 四态 vs 10/代码五态（waitingWake） | design/08 L36 |
| AUD-059 | 08 保留 L1-L5 风险等级表述，代码为四档色级 GREEN/YELLOW/ORANGE/RED + 整数 riskLevel | design/08 §2.1/§5.4 |

---

## §7 过度设计质疑（保留项，供后续统一议决）

> 已移除：声纹免密登录系统（§2 裁决，非过度设计，后续默认本地模式）；EntitlementFilter 套餐矩阵（frozen/38 冻结，不纳入当前分析）。

### AUD-060【质疑】deploy.sh 与 cd.yml 双部署通道

- 证据：deploy.sh 的 git-diff 增量检测 + `.deploy-state` 状态文件 + 本地构建/服务器重建，与 cd.yml 的"CI 全绿→镜像→SSH"完全重叠且回滚语义不同（源码重建 vs 旧 tag 回退）
- 候选方向：明确"CD 为主、deploy.sh 仅限紧急热修"并文档化，或冻结 deploy.sh

### AUD-061【质疑】备份三层（daily/weekly/monthly）+ 异地 rsync + 恢复前快照

- 证据：对试点项目偏重，但针对儿童数据合规与 PIPL 属合理成本——真正缺口是 cron 未接线（AUD-032），本质疑仅提示成本，不强制收敛

### AUD-062【观察】声纹双模式比对维护成本（local 0.70 前端 + remote 0.55 服务端两套链路）

- 观察（非质疑，§2 裁决后方向已定）：后续默认本地模式，remote 链路的去留随 AUD-001 修复一并裁决

---

## §8 僵死/僵尸代码清单（后续统一清理任务）

| 编号 | 项 | 位置 | 判定 |
|---|---|---|---|
| AUD-063 | RecurrenceCalculator | counseling-service/.../assessment/ | **零生产调用且不在台账暂缓清单（台账失实项）**——补登记并移除或显式冻结 |
| AUD-064 | assessment 包其余 3 类（AssessmentScoringEngine/BuiltinScales/ScoringResult） | 同上 | 零调用，已登记「保留·暂缓」属已知项（frozen/59 施测接线），与本表 063 区分 |
| AUD-065 | storage.ts 安全封装（readLocalStorageSafe/writeLocalStorageSafe） | student-h5/src/utils/storage.ts | 建而未用（仅测试引用），ThemeProvider/ConsentKeys 仍裸用 localStorage——接入或删除 |
| AUD-066 | LoginPage 动态 import 重复 | LoginPage.tsx L311/L357 | `await import('../api')` 与顶层静态 import 重复，无分 chunk 收益 |
| AUD-067 | wakeWord.ts 过时注释 | student-h5/src/config/wakeWord.ts L22-24 | 「现回退 hf-mirror」与代码值 SAME_ORIGIN 矛盾，误导排障 |
| AUD-068 | scripts/archive/ 空目录 | scripts/archive/ | DOC-055/04 §12.12 声称已删 7 个一次性脚本，空目录残留 |
| AUD-069 | tts-service/wheels/（44 文件） | backend/tts-service/wheels/ | 已被 gitignore（不入库），OD-012 已裁决在线安装，工作区残留 |
| AUD-070 | tmp/*.patch（14 个） | tmp/ | 历史修复补丁，gitignore 拦截不入库，可归档清理 |
| AUD-071 | tests/e2e/node_modules、playwright-report/、test-results/ | tests/e2e/ | 本地运行残留（未跟踪），保持工作区干净 |

---

## §9 台账抽查验证（ARCH-001~010）

| 台账项 | 抽查结果 | 判定 |
|---|---|---|
| ARCH-001 C1 编排拆分 | ConversationServiceImpl 759 行（台账 844→758 吻合）；PersonalInfoExtractor/PromptAssemblyService 存在且配测试 | ✅ 属实 |
| ARCH-002/005/006/008（前端） | useSilenceNudge 走 authFetch；SSE consumeSseStream 仅一处；useVoiceInputPipeline 边界清晰；authFetch 移植 teacher + CSP/COOP/COEP + console 归零 + BigScreen 错误态 | ✅ 属实 |
| ARCH-003 风险词典单一规则源 | RiskKeywordRegistry（279 行）被 RiskDetectorServiceImpl/ConversationRiskProcessor 统一引用 | ✅ 属实 |
| ARCH-004 僵尸 API 清理 | **RecurrenceCalculator 零生产调用且不在台账保留清单** | ⚠️ 失实（见 AUD-063） |
| ARCH-007 两级摘要 + PII 置换 | MessageSummaryService 主链路调用；PiiDesensitizer 含姓名/地址脱敏 | ✅ 属实 |
| ARCH-009 工程化门禁（7 项全查） | pytest 入 CI 真实；teacher 覆盖率配置链路完整（89.99% 实测值无法复跑）；TTS 面板删除零命中；rollback V28~V33 六文件真实非空；prepare-models.sh --verify + MANIFEST 接线；限流拦截器已注册 | ✅ 全部属实 |
| ARCH-010 D1 ObjectMapper | OutputReviewService 已注入 | ✅ 属实 |
| ARCH-010 D2 租户前缀 | `session:state:` 已带前缀 + 双读兼容；**`ratelimit:` 未覆盖** | ⚠️ 部分（见 AUD-041） |
| ARCH-010 D4/D5 | chatProactive 预渲染版本路由；旧 endSession TTL 到期 410 | ✅ 属实 |

**结论：台账整体回归真实（对比 2026-07-29 审计 2.8/10 时期不可同日而语），仅 2 处失实（AUD-063/AUD-041），均已纳入本清单。**

---

## §10 修复优先级路线（后续统一处理）

> 本报告为问题清单定稿，修复任务后续统一排期（TDD 方式，每项实施前在 doing 下补方案与 SPEC 细化）。建议批次：

1. **批次 A（安全与发布阻断，P0 全量 + 关联项）**：AUD-001（声纹，含 remote 链路裁决）、AUD-002~004（CD 闭环）、AUD-009（rollback 竞态）、AUD-010（CONSENT 漂移）——完成标准：按 DEPLOY-GUIDE 全流程 dry-run 一次成功
2. **批次 B（契约文档止血 + 高风险数据项）**：AUD-011/012（08 错误码/响应字段重写）、AUD-013（analytics 三端点裁决）、AUD-036~040（文档互斥修正）、AUD-057~059（文档口径）
3. **批次 C（工程化加固）**：AUD-005~008（后端/前端 P1）、AUD-014~035（P2 各项）
4. **批次 D（清理与收敛）**：AUD-063~071（僵死代码清理）、AUD-060~062（过度设计议决）
5. **批次 E（P3 收尾）**：AUD-041~059 剩余项

---

## §11 审计过程记录

- 审计方式：4 路并行独立 agent（GeneralPurpose，只读），交叉印证后由主 agent 汇总
- 审计日期：2026-08-06
- 审计基线 commit：27ad409（develop）
- 用户裁决：2026-08-06（§2）
- 审计全文未落库部分（各维度细节证据链）以本报告为准；his/61~70 各文档头部引用对应历史审计项
