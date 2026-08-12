# doing/95 深度审计问题清单(独立第三轮)

> 状态:🧭 修复中 | 创建:2026-08-12 | 审计方式:3 路独立 agent(后端 Java / 前端四端 / Python+部署+设计一致性)+ 主审计者对全部 P0 代码级交叉复验 | 全程只读,零运行
> 关联:BEACON 演进日志 DOC-120(下次合并登记)、TASK-TRACKER 待登记
> 前置审计:his/71(AUD-001~071)、his/72(DC-001~012)、his/77/78(B/F/D 系列)、his/79(BA/FA/DA 系列)、his/80/81(第四轮)、his/90/91/92/93(Q/R/N/P 系列)

---

## 一、总体结论

治理纪律整体上乘:分层纪律无回潮、多租户行级隔离零漏网、双层安全审查真实接线、前端语音全链路零空壳、部署脚本路径锚定与回滚门禁真实。**但本轮暴露此前审计体系系统性失明地带:voice-service 双 P0**(`_funasr_ser` 未定义致 analyze 必 500 + Dockerfile 漏拷 ser_engines.py 致镜像必崩),CI 的依赖安装与 import 冒烟恰好绕开两条路径。叠加教师端 5 入口全校越权面、prod 短信默认假实现、前端测试邀请码泄漏进生产,**当前状态不具备直接商用发布条件**。"文档-代码-配置"三方对齐度被侵蚀,台账失实 5+ 处。

## 二、评分(苛刻口径)

| 维度 | 得分 |
|------|------|
| 架构合理性 | 7.5/10 |
| 代码质量 | 6.5/10 |
| 工程化规范 | 7.5/10 |
| 团队协作友好度 | 6.0/10 |
| 代码逻辑虚化度 | 7.5/10 |
| **综合** | **6.9/10** |
| **生产发布就绪度** | **5.0/10** |

## 三、问题清单

### P0 — 生产必解决(5 项)

| 编号 | 问题 | 证据 | 建议 |
|------|------|------|------|
| OPS-001 | voice `/analyze` 必 500:`_funasr_ser` 未定义(SER_ENABLED=true 生产默认即触发) | backend/voice-service/app.py:339,全仓库仅 1 处引用无定义 | 改 `ser_backend.analyze` + 补 analyze 端点单测 |
| OPS-002 | voice 镜像必崩:Dockerfile 漏拷 ser_engines.py(app.py 模块级 import) | backend/voice-service/Dockerfile:43 | COPY 补 ser_engines.py + CI import smoke 改 `import app` |
| BACK-001 | 教师端 5 入口全校越权(满意度 getSatisfactionStats / 风险事件 pageRiskEvents / 会话转写 getSessionMessages / 工作台 getDashboard / 接管 takeoverSession),仅 tenantId 维度无 classScope | backend/counseling-service/.../TeacherService.java:148/263/276/643/817 | classScope 下沉 Service 强制参数,缺省 fail-closed;会话读写/接管按所属班级校验任课权限 |
| BACK-002 | prod 短信默认假实现:provider 默认 `logging`,prod profile 无 sms 覆盖,漏配则验证码"发送成功"实为写日志 | backend/counseling-app/src/main/resources/application.yml:214 | prod 强制 aliyun + 凭证缺失 fail-fast |
| FE-001 | DEMO2026 测试邀请码硬编码进生产,破坏邀请制准入(PIPL 未成年人准入失控) | frontend/student-h5/src/components/LoginPage.tsx:677 | 删除或改环境变量 VITE_DEMO_INVITE 注入,生产不注入 |

### P1 — 高(6 项)

| 编号 | 问题 | 证据 | 建议 |
|------|------|------|------|
| OPS-003 | CI voice 单测 job 依赖缺口(模块级 import numpy/soundfile vs 仅装 pytest pyyaml)→ 门禁失真 | .github/workflows/ci.yml:215-219 | voice job 补 numpy/soundfile |
| OPS-004 | docker-build-smoke 只 import asr_engines/metrics_common,不 import app/ser_engines → COPY 遗漏无法拦截 | ci.yml:280 | 改 `import app` |
| OPS-005 | 生产宿主 nginx 无 CSP(design/03:119、04:92 声称"已生效"失实,仅容器 nginx 生效) | deploy/nginx/host/nginx.conf 全文无 CSP | 宿主 server 级 include 等效 CSP(注意 add_header 继承缺陷) |
| FE-003 | student-h5 类型防线倒挂:noImplicitAny:false + strictNullChecks:false,四端最宽松,违反 doing/80 F-03 分端推进 | frontend/student-h5/tsconfig.json:14-16 | 分批开启,先 strictNullChecks 后 noImplicitAny |
| FE-004 | admin-web 未收敛 shared auth-transport:自建 adminFetch 无 refresh、裸 sessionStorage | frontend/admin-web/src/api.ts:27-55 | 接入 shared createAuthFetch + storage 安全封装 |
| FE-005 | design/09:2662-2743 offline-first 承诺虚假:PWA disable + 工具箱/放松练习全 API 驱动(仅 SosPanel 静态) | frontend/student-h5/vite.config.js:105-108、ToolboxPanel.tsx:18-30 | 二选一:实现缓存或 design/09 显式降级标注 |

### P2 — 中(12 项)

| 编号 | 问题 | 证据 | 建议 |
|------|------|------|------|
| BACK-004 | AUD-043 声称修复残留 `.last("LIMIT 20")` + LEGACY"14 文件"清单口径失真 | StudentProfileService.java:71-77 | 改 selectPage;脚本重盘 `.last(` 全量清单 |
| BACK-005 | 异步承诺未兑现 ×3(画像更新/会话分析/摘要 persist 注释"异步"实为同步)→ endSession 事务放大 | ConversationServiceImpl.java:606-624、MessageSummaryService.java:163 | 改真 @Async 或注释如实声明 |
| BACK-006 | AiChatServiceImpl 静态线程池无 TenantContextTaskDecorator 租户传播 | AiChatServiceImpl.java:144-146 | 挂 decorator |
| BACK-007 | `@Transactional` 内 catch-all 吞异常(updateProfile 无留痕) | StudentProfileService.java:125-127 | 至少记日志/留痕 |
| BACK-008 | parent 域 permitAll + validateParentToken 未校验 tokenType==parent_report | SecurityConfig.java:117、ParentIdentityResolver.java:35-50 | 补 tokenType 校验 |
| BACK-009 | prod CORS 未覆盖(默认含 localhost:5173/5174) | application.yml:146 | prod 显式覆盖 |
| BACK-010 | 魔法值外置不彻底(enroll-segments 3/verify-segments 2、LIMIT 20、0.75 置信) | application.yml:184-185 等 | 收编配置或常量 |
| FE-006 | VoiceLoginOverlay captureSegment timer 伪清理(Promise executor 返回值被忽略) | VoiceLoginOverlay.tsx:135-158 | 独立 ref + cleanup 清除 |
| FE-007 | 声纹链路 `getVoiceprint(...) as any` 逃逸 | VoiceLoginOverlay.tsx:285 | 泛型化消除 as any |
| FE-008 | admin-web 页面缺竞态防护(AlertPage/ConfigPage load 无 cancelled 防护) | admin-web/src/pages/AlertPage.tsx:15-25 | 抽 useAsyncList 或 AbortController |
| OPS-006 | deploy.sh backend rsync 未排除 `.env`(密钥明文副本面上服务器) | deploy.sh:334-337 | rsync 增加 --exclude '.env*' |
| OPS-007 | service-manager TTS 全灭判健康(engine=none return 0)→ 部署门禁绿灯 | service-manager.sh:106 | engine=none 时 return 1 |
| OPS-008 | compose 硬编码公网 IP + 无消费者死 env(MINDSAFE_MONITORING_SERVICE_PROBES_NGINX) | docker-compose.prod.yml:66 | 去除默认值强制 .env 提供 |

### P3 / 僵死代码(8 项)

| 编号 | 问题 | 证据 | 建议 |
|------|------|------|------|
| FE-002(僵死) | main.tsx SW 更新 30 行死代码 + 模块级 60s 定时器(sw.js 不生成永不触发) | main.tsx:16-52、vite.config.js:107-108 | 整块删除 |
| BACK-011 | getDashboard `teacherUserId` 死参数 | TeacherService.java:276-278 | 删除或落地教师维度统计 |
| OPS-011 | tts-service `http_client` 僵死代码 | tts-service/app.py:33/38-43 | 删除 |
| OPS-012 | ci.yml"九件套"注释过时(实际 14 个) | ci.yml:225 | 修正注释 |
| OPS-013 | restore.sh 校验失败仅 WARNING 仍报成功 | restore.sh:109-110 | 校验失败 exit 1 |
| BACK-012 | OpsInsightsService `.last("LIMIT "+safeLimit)` 风格与 AUD-043 目标不符 | OpsInsightsService.java:77-87 | 改 selectPage 或登记豁免 |
| BACK-013 | 台账失实群:design/04 §3.2 env 透传表三处、§5.5、§14.2 FE1 模型投放语义、design/05 §2.2 缓存路径、design/15 §九b 覆盖率口径 | 见证据列 | 同步修正(归 sync-doc 批次) |
| OPS-014 | design/05 §2.2 模型缓存路径过期(/root vs /home/appuser) | 归 sync-doc 批次 | 同步修正 |
| OPS-015 | design/15 §九b 覆盖率口径与 CI 不符(≥80% vs 45/60) | 归 sync-doc 批次 | 同步修正 |

## 四、已声称修复核验(6 项)

| 声称项 | 结论 |
|--------|------|
| 声纹跨租户比对(AUD-001)+ embedding 校验(B-05) | ✅ 真实 |
| 限流 create_session(AUDIT-P0-2) | ✅ 真实 |
| JWT 枚举化+单次解析(R-016/017) | ✅ 真实 |
| 分页安全化(AUD-043) | ⚠️ 部分虚假(残留 .last LIMIT 20) |
| 导出越权(BA-02) | ⚠️ 部分虚假(另 5 入口仍全校可见) |
| 语音 SER 并行(design/05 §2.2) | ❌ 虚假(_funasr_ser 未定义) |

## 五、过度设计核查

无严重过度设计。Redis Lua 仅 nudge 原子判定(有真实并发消费者)、deploy-metrics/audit 有真实产物、RRF/画像元数据均为真实消费者。轻微过载点:monitoring env 死变量、restore 双 docker run。

## 六、Top 10 必改项(按紧迫度)

1. voice-service 双 P0(OPS-001/002)+ CI 防线(OPS-003/004)
2. 教师端 classScope 统一治理(BACK-001)
3. 移除 DEMO2026(FE-001)
4. prod 短信强制 aliyun + fail-fast(BACK-002)
5. 宿主 nginx 补 CSP(OPS-005)
6. 删除 SW 死代码块(FE-002)
7. async 承诺兑现(BACK-005/006)
8. 台账全面复核(BACK-013 群)
9. student-h5 类型防线补课(FE-003)+ admin-web 收敛 shared(FE-004)
10. 离线承诺对齐(FE-005)

---

## 修复批次规划(实施执行记录)

- [x] 批次 A(P0 必做 5 项):OPS-001(改 ser_backend.analyze + 2 单测) / OPS-002(COPY 补 ser_engines.py + smoke 改 import 四模块) / BACK-001(教师 5 入口 classScope 治理 + 2 越权拦截测试) / BACK-002(LoggingSmsService 启动警示;AliyunSmsService 已有 R-04 fail-fast) / FE-001(VITE_DEMO_INVITE 环境变量注入)
- [x] 批次 B(P1):OPS-003(voice requirements-lite.txt) / OPS-004(import smoke 补 ser_engines/config) / OPS-005(宿主 nginx 7 location 补 CSP) / FE-003(student-h5 strictNullChecks 开启,210 错误归零,942 测试全绿) / FE-004(admin-web 收敛 shared auth-transport,tsc 0 错 + 37 测试) / FE-005(design/09 第十章实现态降级标注)
- [x] 批次 C(P2):BACK-004(StudentProfileService selectPage + .last( 全量重盘:剩余均为 LIMIT 1 常量安全用法,豁免登记) / BACK-005(异步注释如实化) / BACK-006(callWithTimeout 租户上下文传播) / BACK-007(updateProfile 异常堆栈留痕) / BACK-008(parent tokenType 必须 parent_report) / BACK-009(prod CORS 显式覆盖) / BACK-010(DEFAULT_AVG_CONFIDENCE 常量) / FE-006(captureSegment timer ref 清理) / FE-007(删 as any) / FE-008(AlertPage/ConfigPage 竞态防护) / OPS-006(rsync 排除 .env*) / OPS-007(engine=none 判不健康 return 1) / OPS-008(死 env 删除)
- [x] 批次 D(P3/僵死 + 台账):FE-002(SW 死代码 30 行删除) / BACK-011(teacherUserId 死参数随 BACK-001 删除) / OPS-011(tts http_client 僵死删除) / OPS-012(ci 注释十四件套) / OPS-013(restore.sh 验证失败 exit 1) / BACK-012(OpsInsightsService selectPage) / BACK-013(design/04 §3.2 env 表、§14.2 FE1 模型投放语义、design/05 §2.2 缓存路径、design/15 §九b 覆盖率口径同步)
- [x] 全量回归:后端 mvn surefire 2190 全绿 / 前端 vitest 1385(942+214+192+37)全绿 + 四端 tsc 0 错 / python pytest 50(tts 35+voice 15)全绿 / shell 测试套件 15/15 PASS / code-review H-1/M-1/L-1 已修(2026-08-12 实跑)
- [x] 设计文档同步:design/04/05/15/09 台账失实修正完成(并入批次 D)
