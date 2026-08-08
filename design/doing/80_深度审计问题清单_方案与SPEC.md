# doing/80 深度审计问题清单（第四轮交叉审计）

> 登记：2026-08-08 | 审计方式：3 路独立 agent 交叉审计（后端 Java / 前端三端 / 工程化·部署·设计一致性），全部只读
> 排除区：AUD-001~071（his/71）、DC-001~012（his/72）、T1-T5（his/76）、B/F/D 系列（his/77+78）、BA/FA/DA 系列（his/79）、ARCH-001~010（his/61~70）、取消 CD（his/72_取消CD）——仅在发现「假闭环/台账失实」时重复报告
> 状态：**已实施完成（2026-08-08）**：批次 A（SPEC-A1~A6 P1 六项）+ 批次 B（SPEC-B1~B7 台账失实群）+ 批次 C（P2 十项）+ 批次 D（P3 十九项）+ R-1/R-2/R-4/R-7 + FA-16（承接 R-3）全部闭环，待合并归档（登记 DOC-079 + DOC-081）

---

## §1 审计概况

| 路 | 审计范围 | 方法 | 结论 |
|---|---|---|---|
| 后端 | 6 模块 248 文件 58153 行；精读租户三件套/安全链/教师端/声纹/TTS/统计/清理任务等 20+ 核心链路 | 设计文档（03/06）先行 → 全文精读 → 模式扫描（.last LIMIT/空 catch/TODO/Mapper 注入/僵死调用点交叉验证/@Test 计数 1599） | 无 P0；P1×1、P2×8、P3×10；台账失实 2 处 |
| 前端 | student-h5/teacher-web/parent-h5/shared 四包约 60 核心文件 + 110 测试文件 | 文档（09/10/11/12）先行 → 逐文件精读 → 交叉 grep（引用链/any 逃逸/硬编码色值） | 无 P0；P1×2、P2×7、P3×7 |
| 工程化 | deploy/（4 compose+nginx+备份三件套+setup/init）+ ci.yml + deploy.sh + scripts/ + tests/ + DEPLOY-GUIDE/README/STRUCTURE + design/02~12 一致性抽查 | 文档基准 → 逐一核对 → 声明逐点代码验证 → git ls-files/log 核查僵死资产 | 无 P0；P1×3、P2×9、P3×10；一致性 15 项中 10 项失实 |

**问题总数：P0×0 / P1×6 / P2×24 / P3×27（合并三路交叉项后）**

**综合评分：6.9/10（苛刻悲观口径）｜生产发布就绪度：7.2/10**

---

## §2 P1 问题清单（生产应尽快修，6 项）

| 编号 | 位置 | 问题 | 修复建议 |
|---|---|---|---|
| B-01 | `TeacherService.java:277-286` | getAlerts 先全校分页 `selectPage(1, min(limit,100))` 再内存过滤班级学生：班主任本班低风险/较旧事件滑出前 100 窗口即漏报；exportAlerts 传 limit=500 被钳为 100 | 班级范围下推 SQL（`in(班级学生集合)`），导出路径独立上限并显式提示截断 |
| D-01 | `deploy/docker-compose.prod.yml:54` | prod backend `"18082:8080"` 全 0.0.0.0 绑定，违反 design/04 §3.1 铁律「端口仅绑定 127.0.0.1」（dev compose:125 正确）；安全组误开 18082 即可绕过 nginx 直连 | 改 `"127.0.0.1:18082:8080"` + design/04 同步最终态 |
| D-02 | `deploy/restore.sh:78-91` | 恢复链路未停 backend 直接 `pg_restore --clean`：活跃连接阻塞 DROP，灾难应急路径在需要时可能不可用 | 恢复前 `stop backend`（或 pg_terminate_backend），验证通过再 up；写入演练指引 |
| D-03 | `DEPLOY-GUIDE.md:376` | 备份 cron 文档路径 `/guju/mindsafe/backup.sh` 与 setup-server.sh:107 实际 `/guju/mindsafe/deploy/backup.sh` 不符——运维排查第一入口引向不存在的文件 | 文档改实际路径 + backup-common 测试加对文档文本的断言防回潮 |
| F-01 | `teacher-web/src/App.tsx:23-28` + `index.css:4-30` | 暗色模式半实现：antd 组件走 darkAlgorithm，全部 `--ms-*` token 无暗色变体/无 [data-theme] 覆盖——侧边栏/卡片/SLA 徽标恒浅色，视觉割裂 | index.css 增 `[data-theme='dark']` 覆盖层，App 切换时写 `document.documentElement.dataset.theme` |
| F-02 | BEACON「前端 1221 测试」 | 静态统计实测约 1112（student 828 + teacher 161 + parent 87 + shared 33 + each 变体 3），出入约 109 | 以 CI 实跑 vitest run 计数回写 BEACON，注明口径 |

---

## §3 P2 问题清单（24 项）

| 编号 | 位置 | 问题 |
|---|---|---|
| B-02 | `TtsController.java:57-109` + `WebMvcConfig.java:22-23` | synthesize 无文本长度上限/字数校验，且限流拦截器仅注册 chat 路径——CosyVoice 按量计费存在成本滥用向量 |
| B-03 | `TeacherService.java:167-168,207-208,222,767-783` | 今日/趋势统计用 `Instant.truncatedTo(DAYS)`（UTC 日边界），UTC+8 每天 08:00 前数据归入前一天，与 CSV 导出显式 Asia/Shanghai 口径矛盾 |
| B-04 | `TeacherController.java:433-441` | exportSession 把解密后的 contentSummary 无 HTML 转义拼入导出 HTML——学生消息含 `<img onerror>` 时教师打开导出文件即 XSS；同文件 CSV 有转义、HTML 路径遗漏 |
| B-05 | `VoiceprintEnrollService.java:39-59` + `VoiceprintVerifyService.java:89-107` | 声纹 enroll/verify 对 embedding 无维度/范数校验，可写入零向量/噪声向量，1:N 匹配存在退化模板风险 |
| B-06 | `design/his/79:455` vs `TtsController.java:40-44,75` | **BA-01 台账失实**：his/79 归档声称删除「TtsPipelineScheduler/VoiceEffectivenessTracker/VoiceDegradationPolicy 三类」，实际 VoiceDegradationPolicy 在用（S0/S1/S2 风险场景语音降级 TTSFX-001）——照档案执行将误删真实安全功能 |
| B-07 | `design/06 §3.3` + `BEACON.md:3,78` | 声纹阈值 0.55 文档残留（与 D-04 同源）：代码全链 0.70，06/BEACON 仍写「remote 0.55 权威值/0.70 与 0.55 双值」 |
| B-08 | `TeacherController.java:264` + `TeacherService.java:278` | exportAlerts 传 limit=500 实际被钳为 100，租户超 100 条时导出静默截断 |
| B-09 | `TeacherService.java`（925 行） | 单服务承载工作台/预警/转派/个案/档案/备注/摘要/看板 8 类职责 + 内嵌 12 个 VO——T4 下沉后的上帝类倾向 |
| F-03 | 三端（LoginPage/BoBoPet/useTtsPlayer/useWakeWord/BigScreen/useAlertWebSocket 等 13+ 处显式 any） | noImplicitAny:false + oxlint 零配置 = 隐式 any 系统性逃逸无任何静态门禁 |
| F-04 | `parent-h5/src/platform/request.ts:88` vs 两端 api() | parent request() 返回完整信封，两端返回解包 data——页面被迫二次解包且写法不一致（verify 页还加了 `res.data ?? res` 防御兜底） |
| F-05 | frontend/ 根目录无 package.json，shared 以 `../../../shared/src` 相对路径被 12 文件引用 | shared 无构建边界/无版本/无类型通知面，签名变更无编译期告警 |
| F-06 | BigScreen.tsx 约 15 处 + LoginPage.tsx:440-496 + SpeechBubble.tsx + index.css 主题装饰色 | 硬编码 hex 色值未收编 token（对照 doing/75 青屿方案已生效的 --ms-*） |
| F-07 | `teacher-web/src/App.tsx:15-20,23,28` 裸 localStorage | teacher 端无 storage 安全封装（student 端 storage.ts 已闭环 AUD-065），隐私模式/禁用存储下会抛 SecurityError |
| F-08 | `teacher-web/src/App.tsx:67` | JWT `JSON.parse(atob(...))` 手动解码：UTF-8 payload 中文乱码、非三段式 token 静默丢登录态 |
| F-09 | QualityPanel:18 / OverviewPanel:219 `.catch(() => {})` 等 | 面板静默吞错无重试无提示（AUD-019 只覆盖主加载） |
| D-04 | `design/06 §3.3` + `design/05`（§3.3/§4.2/§7/§12.16 四处）+ `BEACON.md:54,73` | **声纹阈值假闭环**：his/71 声称修复 0.55 失实，实际只修 design/03 一处，06/05/BEACON 三处未同步，全链代码 0.70 无 0.55 消费点 |
| D-05 | BEACON:77「8 规则」/ design/04:415「9 条」/ `alert-rules.yml` 实际 10 条 | 告警规则数字三处漂移（T1 数字修正漏掉可机器校验项） |
| D-06 | `design/05:31,376` vs `ci.yml:127,138` | 覆盖率门禁失实：文档「CI 上调至 80%」，实际门禁整体 ≥45%/核心 service ≥60%（80% 是达成值，三概念混写） |
| D-07 | `deploy/docker-compose.test.yml:45,120` | test compose 仍拉 GHCR 镜像——CD 已取消无人推送，测试/演示环境 up 必失败（死链） |
| D-08 | `design/04:37,400` + `DEPLOY-GUIDE.md:426` | Grafana 声明 `http://<IP>:3002` 公网访问，实际 monitoring compose 已 127.0.0.1（AUD-031 改隧道）——文档引导运维访问故意不开放的地址 |
| D-09 | `deploy/setup-server.sh:36-37` | 镜像加速器用第三方源（docker.1ms.run/docker.xuanyuan.me）与 DEPLOY-GUIDE「阿里云已自动配置」不符：文档失实 + 供应链风险 + 与脚本内 aliyun mirror 自相矛盾 |
| D-10 | `design/04:126` + `scripts/verify-config-passthrough.sh` | **文档虚报**：声称「脚本 + tests/unit 配套用例」，实际无配套测试、ci.yml 无接线——契约校验脚本写了不跑，契约漂移无人知晓 |
| D-11 | `deploy.sh:303` | deploy/ rsync 无 `--delete`（后端/前端均有），服务器端废弃配置陈旧化累积 |
| D-12 | `design/02:51` | **多租户声明失实**：「每租户独立 Schema（tenant_{code}）」，实际共享 tenant_template 行级隔离（TASK-TRACKER:96/STRUCTURE:101 已校正，02 未同步——数据库单一事实源最后漏网处，BEACON 决策 #6 同步待修订） |

---

## §4 P3 问题清单（27 项，摘要）

| 编号 | 位置 | 问题 |
|---|---|---|
| B-10 | MybatisPlusConfig:15 注释 vs 8 处 `.last("LIMIT ...")`（TrialAuthService/ParentAuthService/WeComOAuthService/PromptVersionService/StudentProfileService） | **AUD-043 台账失实**：注释声称「业务代码不再 .last LIMIT」，实际仍 8 处（常量无注入面，安全无损但声明不符） |
| B-11 | DataRetentionCleanupJob Javadoc | 声称「30 天物理删除」，实际 180/365（application.yml:155-156） |
| B-12 | `ParentController.java:192-195` | resolveStudentUserId @deprecated 零调用——僵死方法 |
| B-13 | `EntitlementFilter.java:42,45,127` | filter 直连 TenantMapper（T4 只禁 Controller），分层纪律灰色区 + fail-open 耦合在 filter |
| B-14 | `TeacherController.java:94-102` | 7 条干预话术模板硬编码 Controller 静态常量（B4 文案模板化未覆盖教师端） |
| B-15 | `TeacherService.java:735` | getStats 租户全量历史风险事件加载内存分组，无时间窗，表增长后不可持续 |
| B-16 | `TeacherService.java:320,345,487,538` | 裸 IllegalArgumentException + 中文消息，丢失 ErrorCode 语义 |
| B-17 | `TeacherService.java:342,392,402,437` | 4 个写操作缺 @Transactional（同文件其他写操作有，规范不一致） |
| B-18 | `TeacherService.java:323,642` | 缩进/换行错位 |
| B-19 | `RateLimiter.java:16-20,82` | Javadoc 声称「滑动窗口」，实现为固定窗口 INCR+EXPIRE |
| F-10 | Dashboard.tsx:25 | playAlertSound 每次 new AudioContext 不 close()，预警高频资源泄漏 |
| F-11 | parent verify/index.tsx:29-32 | render 期副作用 redirectTo，应改 effect |
| F-12 | parent platform/storage.ts:13-21 | sessionStorage 无 try/catch（student 端已覆盖）；student api.ts:39 setUser 有 get 无 set 不对称 |
| F-13 | useWakeWord.ts:150,237 / useTtsPlayer.ts:225 | `null as any`、chunks 推断 any[]、降级计数语义混淆 |
| F-14 | BigScreen.tsx:199 | 使用 React.CSSProperties 未 import React（UMD 全局类型隐式依赖） |
| F-15 | LoginPage 704 行 / SettingsPanel 456 行 | 大组件观察项 |
| F-16 | student-h5/src/index.css 642 行 | 单主题装饰块大量，建议随 ThemeProvider 收敛清理 |
| D-13 | deploy.sh:76-97 | rollback 模式无健康检查/冒烟验证（部署路径有完整门禁） |
| D-14 | docker-compose.prod.yml:216 | dbbackups 僵尸卷声明（无服务挂载，backup 实际用自动命名卷） |
| D-15 | scripts/archive/ 空目录 | BEACON 声称已删，实际残留空壳；STRUCTURE.md:78 声称 create_*.js/py 仍存在（git log 证实已删） |
| D-16 | STRUCTURE.md/README.md | 多处过时：DESIGN-OVERVIEW v5.1（实际 v6.6）、frozen 列表过时、README「MVP 阶段」实际已生产 |
| D-17 | deploy/.env.example | 缺 MINDSAFE_CRISIS_HOTLINE / MINDSAFE_VOICEPRINT_MODE 两个可配置开关 |
| D-18 | deploy/.env.example vs DEPLOY-GUIDE.md:156 | LLM_PRIMARY_MODEL 模板不一致（deepseek-v4-flash vs deepseek-v4-pro） |
| D-19 | DEPLOY-GUIDE.md:274,287 + design/06 §6.2 + BEACON #26 | 「rsync --exclude models/」过时：DA-06 已反转（模型随 dist 上传 + 前置 verify），文档未同步；DEPLOY-GUIDE:245 仍引导 test compose 操作 nginx，与宿主 nginx 自相矛盾 |
| D-20 | design/frozen/73_实体机器人...方案_方 案与SPEC.md | frozen/73 与 his/73 同号异题 + 文件名含空格，对照表未登记 |
| D-21 | setup-server.sh:77 | 步骤号错乱（[5/7] 其余 [1/6]~[6/6]） |
| D-22 | tts-service/app.py | 异常处理单薄（仅 1 处 try），引擎崩溃/网络异常可能 500 非 JSON；voice-service 有 4 处 try + 超时分层 |
| D-23 | tests/e2e/playwright.config.ts + specs/ | Playwright 半成品：有配置无执行（CI 不跑），STRUCTURE/design/05 未登记「预留态」决策 |

---

## §5 台账失实/假闭环专项（本轮最高价值发现）

| 项 | 声明 | 事实 | 风险 |
|---|---|---|---|
| BA-01（B-06） | his/79：删除 VoiceDegradationPolicy 等三类 | 仅删两类，VoiceDegradationPolicy 在用（风险场景语音降级） | 照档案执行将误删真实安全功能 |
| AUD-043（B-10） | 「不再使用 .last('LIMIT')」 | 仍 8 处（常量无注入面） | 安全无损，纪律声明失真 |
| 声纹阈值（B-07/D-04） | AUD-001 修复「0.55 失实→0.70」 | 只修 design/03 一处；06/05/BEACON 三处仍 0.55 | 文档引导错误配置 |
| 告警数字（D-05） | BEACON 8 / design/04 9 | 实际 10 条 | 可机器校验项漂移 |
| 覆盖率门禁（D-06） | 「CI 上调至 80%」 | 门禁 45/60（80% 是达成值） | 三概念混写 |
| verify-config-passthrough（D-10） | 「+ tests/unit 配套用例」 | 无配套用例、无 CI 接线 | 契约校验虚报 |
| 多租户 Schema（D-12） | design/02：「每租户独立 Schema」 | 行级隔离（多处已校正） | 数据库事实源最后漏网 |

**模式总结**：失实集中在「删除类声明」（BA-01/AUD-043/scripts/archive）与「部署配置类数字」（阈值/告警/覆盖率/Grafana/cron）——实现快、文档跟的节奏断裂点全部落在工程化文档侧；功能文档侧（08/09/12 四态标注）抽查一致率高。

---

## §6 僵死代码/垃圾资产清单

| 项 | 位置 | 判定 |
|---|---|---|
| resolveStudentUserId | ParentController.java:192-195 | 僵死方法（零调用） |
| scripts/archive/ | 空目录 | 垃圾残留（BEACON 声称已删） |
| dbbackups 卷声明 | docker-compose.prod.yml:216 | 僵死配置 |
| parent-h5/src/api/ | 空目录（services/index.ts 注释声称已迁移） | 死目录 |
| Playwright specs+config | tests/e2e/ | 半成品资产（有意图保留但未登记） |
| create_*.js/py + db-backup.sh | 已删除 | STRUCTURE.md:78 文档残影 |

---

## §7 过度设计质疑点（先质疑后保留）

1. **文档治理体系成本**（元层面）：12 主文档 + BEACON + TRACKER + OVERVIEW + doing/his/frozen 三层，每改动要求指令+提交号+回归证据，仍出现 7 处台账失实——文档越多失实越隐蔽。建议对「删除类」声明建可执行校验（CI grep 断言类不存在），而非纯人工登记
2. **TeacherService 925 行**：T4 下沉方向正确但收敛粒度过粗，制造新上帝类（B-09）
3. **verify-config-passthrough 写了不跑**：写脚本成本已付、接线成本未付 = 过度设计产物（D-10）
4. **frozen/73/74 巨型方案文档**（28KB/74KB）堆料冻结区：建议只留决策摘要 + 指向全文
5. **单机单栈 Prometheus+Grafana+Alertmanager 三件套**：Grafana 已收口 127.0.0.1，消费方存疑
6. 保留项（不判过度）：useVoiceCallMode 状态机（多阶段生产验证）、声纹/唤醒/WASM（已验证生产功能）

---

## §8 评分（苛刻悲观口径）

| 维度 | 后端 | 前端 | 工程化 | 综合 |
|---|---|---|---|---|
| 架构合理性 | 7.5 | 6.5 | 6.0 | 6.7 |
| 代码质量 | 6.5 | 6.0 | 7.0 | 6.5 |
| 工程化规范 | 7.0 | 6.5 | 7.5 | 7.0 |
| 团队协作友好度 | 7.5 | 7.5 | 5.5 | 6.8 |
| 代码逻辑虚化度 | 8.0 | 7.0 | 7.5 | 7.5 |
| **综合分** | **7.3** | **6.7** | **6.7** | **6.9** |
| **生产发布就绪度** | 7.5 | 7.0 | 7.0 | **7.2** |

**发布判定**：无 P0，主链路（对话/预警/备份/发布/冒烟）真实可用，可继续生产运营；但 P1 六项（预警漏报面/端口绑定/恢复链路/cron 文档/暗色半实现/测试台账）应在下次发布窗口前闭环，P2 台账失实群随发随修。

---

## §9 修复 SPEC 批次 A（P1 六项，发布前必做，逐项 SPEC）

### SPEC-A1【B-01】预警查询班级范围下推 SQL

- **问题**：`TeacherService.getAlerts` 先全校 `selectPage(1, min(limit,100))` 再内存过滤班级学生，本班低风险/较旧事件滑出 100 窗口即漏报；`exportAlerts` 传 500 被钳为 100 静默截断
- **方案**：①先查班主任班级学生 ID 集合，`in(集合)` 条件下推分页查询（保留 riskLevel+detectedAt 排序）；②导出路径独立上限（如 5000 分页流式）并显式返回截断提示
- **涉及文件**：`TeacherService.java:277-286`、`TeacherController.java:264`（exportAlerts 参数对齐）
- **验收标准**：构造 >100 条预警数据，班主任可见本班全部条目；导出 500 条完整；teacher 相关测试回归通过

### SPEC-A2【D-01】prod 端口收口 127.0.0.1

- **问题**：`docker-compose.prod.yml:54` `"18082:8080"` 全 0.0.0.0 绑定，违反 design/04 §3.1 铁律（dev 正确）；安全组误开 18082 可绕过 nginx 直连（限流/安全头/HTTPS 全失效）
- **方案**：改 `"127.0.0.1:18082:8080"`（nginx 反代目标不变），design/04 §3.1 同步「宿主机收口 127.0.0.1:18082」最终态
- **涉及文件**：`deploy/docker-compose.prod.yml:54`、`design/04_系统部署方案.md` §3.1
- **验收标准**：本机 curl 18082 通；服务器外网 IP 访问 18082 不可达；nginx 代理路径冒烟通过

### SPEC-A3【D-02】restore 恢复链路停服

- **问题**：`restore.sh:78-91` 未停 backend 直接 `pg_restore --clean`，活跃连接阻塞 DROP，灾难恢复路径在需要时可能不可用
- **方案**：恢复前 `docker compose -f docker-compose.prod.yml stop backend`（或 `pg_terminate_backend`），恢复校验通过后再 up；步骤写入脚本与 DEPLOY-GUIDE 演练指引
- **涉及文件**：`deploy/restore.sh`、`DEPLOY-GUIDE.md`（演练章节）
- **验收标准**：在存在活跃连接时执行恢复 dry-run 成功；演练记录更新

### SPEC-A4【D-03】DEPLOY-GUIDE cron 路径修正 + 防回潮断言

- **问题**：`DEPLOY-GUIDE.md:376` cron 路径 `/guju/mindsafe/backup.sh` 与 setup-server.sh:107 实际 `/guju/mindsafe/deploy/backup.sh` 不符，运维排查第一入口引向不存在的文件
- **方案**：文档改实际路径；`tests/unit/backup-common.sh` 增加对 DEPLOY-GUIDE 文本的断言（防回潮，DC-002 模式）
- **涉及文件**：`DEPLOY-GUIDE.md:376`、`tests/unit/backup-common.sh`
- **验收标准**：全仓 grep `/guju/mindsafe/backup.sh`（无 deploy/ 前缀形态）零命中；backup 测试通过

### SPEC-A5【F-01】暗色模式 token 覆盖层

- **问题**：教师端暗色半实现——antd 走 darkAlgorithm 而全部 `--ms-*` token 无暗色变体，侧边栏/卡片/SLA 徽标恒浅色，视觉割裂
- **方案**：`index.css` 增 `[data-theme='dark']` 下 `--ms-*` 覆盖层（含 BigScreen 深色板收编）；`App.tsx` 切换时写 `document.documentElement.dataset.theme`
- **涉及文件**：`teacher-web/src/index.css`、`teacher-web/src/App.tsx:23-28`、`pages/Dashboard.tsx`
- **验收标准**：暗色下侧边栏/卡片/徽标/Statistic 同步变暗；亮暗切换无闪烁残留；teacher 测试 + tsc 全绿

### SPEC-A6【F-02】测试计数核验回写

- **问题**：BEACON「前端 1221 测试」vs 静态实测约 1112，出入约 109
- **方案**：CI 实跑 `vitest run` 取权威计数（含参数化展开口径说明），回写 BEACON 头部
- **涉及文件**：`design/BEACON.md`
- **验收标准**：BEACON 数字与 CI 实跑一致，并注明口径

---

## §10 修复 SPEC 批次 B（台账失实群，低成本高价值）

### SPEC-B1【B-06】his/79 BA-01 归档修正

- **方案**：按 DOC-064 台账修正模式——his/79 追加修正记录（不改历史结论）：实际删除「TtsPipelineScheduler/VoiceEffectivenessTracker 两类」，**VoiceDegradationPolicy 保留在用**（TTSFX-001 风险场景降级）；design/03 补 BA-01 最终态落点
- **验收**：his/79 有修正记录；design/03 可检索 BA-01 落点；照档案执行不会误删 VoiceDegradationPolicy

### SPEC-B2【B-07/D-04】声纹阈值四文档统一 0.70

- **方案**：`design/06 §3.3`（删「remote 0.55 权威值」）、`design/05`（§3.3/§4.2/§7/§12.16 四处）、`BEACON.md`（#23/L73 双值表述）全部改为 0.70 单值，删「两套阈值有意不同」叙述；可选加 verify-doc-numbers.sh 断言
- **验收**：除历史归档外，全仓活跃文档 grep 0.55 阈值语义零命中

### SPEC-B3【D-05】告警规则数字统一为 10

- **方案**：`BEACON.md:77`（8 规则）、`design/04:415`（9 条）改为 10 条（以 `alert-rules.yml` 实际为准）
- **验收**：三处数字一致，可 grep 校验

### SPEC-B4【D-06】覆盖率口径区分目标/达成/门禁

- **方案**：`design/05:31,376` 改「目标 ≥80%（达成 84.3%）｜CI 门禁：整体 ≥45% / 核心 service ≥60%」三概念分行表述
- **验收**：文档无「CI 上调至 80%」误导表述

### SPEC-B5【D-10】verify-config-passthrough 接线或下架

- **方案（建议接线）**：补 `tests/unit/scripts/verify-config-passthrough-test.sh`（参照 DA-05 shell-tools-test 六件套模式）挂入 ci.yml shell-tools-test job；若议决下架则删脚本 + design/04:126 改述
- **验收**：CI shell-tools-test 含该用例并通过；design/04 声明与事实一致

### SPEC-B6【D-12】design/02 多租户隔离表述修正

- **方案**：design/02 §3 改「共享 tenant_template 行级隔离（当前实现）+ Schema 级（tenant_{code}）为远期升级路线」；BEACON 决策 #6 同步注记（与 TASK-TRACKER:96、STRUCTURE:101 对齐）
- **验收**：02/BEACON/TRACKER/STRUCTURE 四处口径一致

### SPEC-B7【D-15/D-16】垃圾资产清理与新人文档对齐

- **方案**：删 `scripts/archive/` 空目录 + `parent-h5/src/api/` 死目录；STRUCTURE.md（DESIGN-OVERVIEW v6.6、frozen 14 份清单、实际活跃脚本清单）、README.md（生产状态）一次性对齐现状
- **验收**：空目录清零；新人按 README/STRUCTURE 操作与实际一致

---

## §11 修复 SPEC 批次 C/D（P2/P3，实施要点表）

### 批次 C 实施要点（P2）

| 编号 | 实施要点 |
|---|---|
| B-02 | TTS synthesize 加文本上限（≤500 字）+ 频率限流（并入 RateLimitInterceptor 或独立注册）+ **R-6 合并项**（D-22 tts 全局异常 handler + dashscope 显式超时） |
| B-03 | 统计日边界统一 `Asia/Shanghai`（抽取时区工具类，趋势日期序列化同步） |
| B-04 | exportSession HTML 转义 contentSummary（复用 CSV 转义模式或模板引擎自动转义） |
| B-05 | enroll/verify 加 embedding 维度（256）与范数下限校验，拒绝退化向量；design/06 补契约 |
| B-08 | 导出路径独立上限 + 显式截断提示（不再静默） |
| F-03 | noImplicitAny:true 分端推进：先 api.ts 泛型 + VO 接口，再页面组件 props（LoginPage/BoBoPet/useTtsPlayer/useWakeWord/BigScreen） |
| F-04 | parent request() 收敛为 data 解包（信封 success 已抛错，解包安全），删 verify 页防御兜底 |
| F-05 | npm workspaces + `@mindsafe/shared` 包收编 12 处相对路径引用（**R-3 已议决 DOC-080：选 B 改良，工作项合并入 FA-16 跟踪**） |
| F-06 | BigScreen/LoginPage/SpeechBubble 硬编码 hex 收编 token（--ms-* 暗色 token / THEMES[].vars） |
| F-07 | teacher 端复用/镜像 storage.ts 安全封装（替换 App.tsx 裸 localStorage） |
| F-08 | JWT 解码兼容 UTF-8（decodeURIComponent(escape(atob)) 或 jose），抽 utils/jwt.ts |
| F-09 | 静默 catch 补 console.error + 局部错误提示（QualityPanel/OverviewPanel/ChatRoom） |

### 批次 D（P3，TDD 逐项实施）

B-10~B-19（.last LIMIT 收敛或注释修正/DataRetention Javadoc/僵死方法删除/filter 下沉/话术模板/统计时间窗/ErrorCode/事务注解/格式化/RateLimiter 文档）、F-10~F-16（AudioContext 单例/render 副作用/存储 try-catch/类型收敛/import 修复/大组件观察/CSS 收敛）、D-13~D-23（rollback 验证/僵尸卷/空目录/文档对齐/env 模板/模型排除同步/编号冲突/步骤号/tts 异常/Playwright 裁决）

---

## §12 待议决项（需项目负责人拍板）

| 编号 | 议题 | 选项 | 议决（DOC-080，2026-08-08） |
|---|---|---|---|
| R-1（D-09） | 第三方镜像加速源（docker.1ms.run/docker.xuanyuan.me，供应链风险 + 文档失实） | A 改阿里云官方 ACR 地址（推荐）；B 保持现状 + 文档如实登记 + 供应链评审 | **选 A**：改阿里云官方 ACR 加速地址（setup-server.sh + 服务器 daemon.json 现场同步）；独立任务待排期（S） |
| R-2（D-07） | test compose 拉 GHCR 死链（CD 取消后无人推送，up 必失败） | A 改 `mindsafe/*:local` 本地构建（推荐）；B 删除 test compose | **选 A**：镜像改 `mindsafe/*:local` 本地构建（先 build 后 up，顺带查 AUD-003 容器名冲突）；独立任务待排期（S） |
| R-3（F-05） | shared 无构建边界（相对路径跨包耦合） | A npm workspaces 收编（ 成本中等）；B 维持相对路径 + shared 补自测 | **选 B 改良**：维持相对路径（单仓同版本发布）+ 删 emotionLabels.ts 垫片 + shared 测试入 CI；**合并 FA-16** |
| R-4（D-23） | Playwright 半成品（有配置无执行） | A 删 specs+config 减负；B 登记「预留态」保留（推荐，规划有 E2E 需求） | **选 B**：预留态登记保留（审计结论过时——smoke-test.sh 已按 DA-04 接线发布后置门禁）；待排期（S） |
| R-5（B-09） | TeacherService 925 行上帝类 | A 拆 3 Service（预警处置/学生档案/看板统计）；B 维持观察（推荐，拆分需配套回归） | **选 B 有条件**：维持观察（无行为缺陷证据），统计域改造时顺势拆 DashboardStatsService；**合并 BA-12/BA-13** |
| R-6（D-22） | tts-service 异常处理单薄 | A 补全局异常 handler + dashscope 超时（推荐）；B 随发布迭代 | **选 A**：全局异常 handler + dashscope 显式超时（成本极低，TTS 主链路收益真实）；**合并 B-02** |
| R-7（B-14） | 教师端 7 条干预话术硬编码 Controller | A 迁配置/DB；B 维持 + B4 覆盖范围标注 | **选 B 改良**：维持代码内维护（心理干预话术属预审核合规内容，走发布评审更可控），TEMPLATES 下沉 service 层；独立任务待排期（S） |

---

## §13 审计过程记录

- 审计方式：3 路并行独立 agent（GeneralPurpose，只读），交叉印证后主 agent 汇总，证据均文件:行号双源核实
- 审计日期：2026-08-08
- 排除区：AUD-001~071（his/71）、DC-001~012（his/72）、T1-T5（his/76）、B/F/D 系列（his/77+78）、BA/FA/DA 系列（his/79）、ARCH-001~010（his/61~70）、取消 CD（his/72_取消CD）
- 状态：报告 + SPEC 登记完成（doing/80），修复批次 A~D 待排期；**2026-08-08 实施完成：批次 A（SPEC-A1~A6）+ 批次 B（SPEC-B1~B7）闭环（DOC-079）；R-1~R-7 已议决（DOC-080）——R-3 合并 FA-16、R-6 合并 B-02，R-1/R-2/R-4/R-7 独立任务待排期（S）；批次 C/D（P2/P3）待排期**；**2026-08-08 第四轮全面闭环（DOC-081）**：批次 C（P2，10 项：B-02 TTS 文本上限+限流注册 / B-03 统计日边界 Asia/Shanghai 时区工具 / B-04 exportSession HTML 转义 contentSummary / B-05 声纹 embedding 维度·范数校验 + design/06 契约 / F-03 noImplicitAny 分端推进 / F-04 parent request 收敛 data 解包 / F-06 硬编码 hex 收编 token / F-07 teacher storage 安全封装 / F-08 JWT UTF-8 解码抽 utils / F-09 静默 catch 补 console.error）+ 批次 D（P3，19 项：后端 B-10~B-19（last LIMIT/Javadoc/僵死代码/ErrorCode/事务等）/ 前端 F-10~F-16（AudioContext/render 副作用/存储/类型等）/ 工程化 D-13~D-21（rollback 健康检查/僵尸卷/env 模板/文档同步等））+ R-1（镜像加速源改阿里云 ACR）/ R-2（test compose 本地构建，AUD-003 确认闭环）/ R-4（Playwright 预留态登记 STRUCTURE+design/05）/ R-7（干预话术 TEMPLATES 下沉 service 层）+ FA-16（承接 R-3：删 emotionLabels 垫片 + shared 测试入 CI）全部闭环
