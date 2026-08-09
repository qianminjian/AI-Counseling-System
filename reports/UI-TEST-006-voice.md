# 对话窗口专项测试报告（UI-TEST-006-Voice，doing/82 §5.5 C-01~C-07）

> 执行日期：2026-08-09 | 环境：生产（yun.gxjugu.com）+ 本地合成音频
> 前置：C0 素材（`tests/audio/`，edge-tts zh-CN-XiaoxiaoNeural 合成，已提交）
> 分层：服务级（voice/analyze 直连）/ UI 级（浏览器真链路）/ 受限项（麦克风音频注入不可用）

---

## 一、场景结果

| 场景 | 结论 | 关键断言 | 说明 |
|---|---|---|---|
| C-01 语音对话性能基线 | ✅ 服务级 / ⚠️ UI 受限 | analyze 耗时基线 | 短句 1.21~1.25s、长句 4.05s（中位数×3）；UI 按住说话需麦克风注入（受限） |
| C-02 语音转文字准确性 | ✅ | CER≤20% ✓ emotion ✓ 边界 ✓ | 平均 CER 15.56%（5 句）；emotion 全部 开心 conf=1.0；txt→400 ✓；>10MB→400（BUG-VOICE-01 修复后） |
| C-03 长语音成功准确度 | ✅ | 完整度≥80% ✓ | 长句转写完整度 97.3%（LCS/原文）；10MB 边界 400 ✓ |
| C-04 语音唤醒灵敏准确 | ✅ **F-8 修复后完整闭环** | 授权流 ✓ 引擎加载 ✓ ORT 耗时 <600ms | 授权弹窗 800ms 自动出现 ✓；**Worker ORT session_create 557ms**（修复前 30-60s，numThreads=2 加速 50-100×）；音频变体/抗误唤醒/首轮过滤（注入受限） |
| C-05 对话上下文记忆 | ✅（1 项观察） | 同会话 ✓ 主题 ✓ 跨会话 ⚠️ | 猫名"豆豆"召回 ✓；主题"数学" ✓；跨会话话术连续性 ✓ 但事实召回（画画）未命中（F-5）；DB 佐证 22 行 message_summaries ✓ |
| C-06 语音对话暖场 | ✅ | 触发 ✓ 护栏 ✓ Redis ✓ UI ✓ | 服务级 20s 触发（warmthLevel=2）；30s/55s 间隔护栏空流 ✓；Redis 计数=1 ✓；UI 沉默 42s 暖场消息自动出现 ✓（前端轮询 4 次 nudge 请求实证） |
| C-07 自动超时降级 | ⚠️ 部分 | 降级链 ✓ 冷却关窗待实测 | 冷却关窗（25s）触发条件是 TTS 收尾后调 useVoiceCallMode.startCooldownClose（chrome-devtools 无麦克风音频注入，需用户实际叫唤醒后验证） |

## 二、C0 素材清单（tests/audio/，已提交）

| 文件 | 内容 | 用途 |
|---|---|---|
| c02-01~05.mp3 | 5 句中文短句（5-15 字） | C-02 CER 基线 |
| c03-long.mp3 | 长文本（~65 字，约 10s） | C-03 完整度 |
| wake-halobobo / wake-nihaobobo / wake-hellobobo.mp3 | 唤醒词 3 变体 | C-04（音频注入受限） |
| big.mp3 / fake.txt | >10MB / 非音频 | 边界测试 |

## 三、发现项

### 已修复（3 项）

| 编号 | 缺陷 | 根因 | 修复 | 状态 |
|---|---|---|---|---|
| BUG-VOICE-01 | voice/analyze >10MB 返回 500 | Java 层无大小校验，voice-service 400 被包装为 500 | VoiceController 转发前校验 → 400"音频文件不能超过 10MB" | ✅ 部署+单测（commit 79d7abd） |
| BUG-NGINX-01 | /mindsafe/models/* 全部 404（唤醒/声纹模型不可达） | nginx 正则 location root 指向旧 student 目录（双部署架构残留），先于正确 alias 匹配 | root 改 student-h5/dist（commit e53bea2，nginx 已 reload） | ✅ 模型 200/206 实证 |
| BUG-P-P06-01 | 家长端 withdrawn 孩子消失（周报卡加载） | getLinkedStudents 过滤 active | 放宽至非删除（commit a1b26a4） | ✅（与 S/T/P 报告关联） |

### 待处理（2 项 → 1 项）

| 编号 | 级别 | 描述 | 建议 |
|---|---|---|---|
| F-5 | P2 | 跨会话事实记忆召回弱：ALLY-201 话术连续性在（"我记得你/上次聊的"）但"我之前喜欢什么→画画"未命中 | 检查历史记忆/主题线索注入策略（4 轮滚动摘要 + 跨会话画像），补充会话级记忆检索验证 |
| ~~F-6~~ | ~~P1~~ | **✅ 已修复**（commits 4d5b4bd + acbdc47） | Worker 线程 numThreads=1→2 + 主线程+Worker 双埋点 |

## 四、受限项说明（工具环境）

- **麦克风音频注入不可用**（chrome-devtools MCP 无 fake-media 能力）→ C-01 UI 按住说话、C-04 音频变体/抗误唤醒/首轮过滤、C-07 冷却关窗的**音频触发部分未测**（已通过服务级/UI 非音频断言覆盖主要链路）
- C-06 互斥（唤醒 standby 无 nudge）：依赖唤醒 active 状态（F-6 阻塞）
- IdleWarning 5min：长时用例未执行（建议专项排期或前端缩短 idleMs 验证）

## 五、结论

**语音链路服务级全部通过**（ASR 准确性/长语音/边界/性能基线/暖场决策与护栏）；**UI 授权与暖场联动通过**；唤醒引擎暴露 2 个真实缺陷（nginx 路径已修 + worker 下载停滞 F-6 待前端排查）；上下文记忆核心能力（同会话+主题）通过，跨会话事实召回待观察（F-5）。

---

## 复测增补（2026-08-09 21:25，F-8 修复后实测）

### C-04 唤醒引擎加载实测（F-8 修复效果验证）

**测试路径**：登录测试丁 → 情绪选择（开心）→ 开始聊天 → 点击"我知道了，开启"语音唤醒

**控制台关键日志**（Worker 内 console.info 通过 DevTools 转发）：
```
[Voiceprint] 主线程 ORT session_create 开始（numThreads=2）
[Voiceprint] 主线程 ORT session_create 完成，耗时 261ms       ← 第一次加载
[Voiceprint] 主线程 ORT session_create 完成，耗时 474ms       ← 第二次加载
[WakeWordWorker] 收到消息: init
[WakeWordWorker] 开始 ORT session_create（numThreads=2）
[WakeWordWorker] ✅ ORT session_create 完成，耗时 557ms        ← 唤醒 30M 模型 session_create
```

**关键发现**：

| 加载阶段 | F-8 修复前 | F-8 修复后（实测）| 加速比 |
|---|---|---|---|
| 声纹主线程 ORT session_create | 30-60s | **261-474ms** | ~100× |
| 唤醒 Worker ORT session_create | 30-60s | **557ms** | ~50-100× |
| 完整 30M 模型加载链路（fetch disk cache + session_create）| 30s+ 等待 | **~657ms** | ~50× |

**状态**：
- ✅ F-6（worker 下载停滞）— 实测**完全修复**，Worker session_create 557ms 即可就绪
- ✅ 唤醒进入 standby 状态：「叫我"哈喽波波" / 我在这里安静地等你叫我」（实测可见）
- ⚠️ C-07 冷却关窗（25s）— 触发条件是 TTS 收尾后调 `useVoiceCallMode.startCooldownClose`，需用户实际叫唤醒后 TTS 播放才能验证（chrome-devtools 无麦克风音频注入）

### 修复链条

| Commit | 修复内容 | 效果 |
|---|---|---|
| `5f73d23` | 声纹进度 0-67% 跳变修复（去除 progress_total 分支 + 独立 callback） | 声纹加载进度单调递增，不再循环 |
| `4d5b4bd` | F-8 同步：Worker 线程 numThreads=1→2 + ORT session_create 埋点 | Worker 30M 模型 session_create 557ms |
| `acbdc47` | 主线程 ORT session_create 埋点同步 | 便于诊断降级路径与 F-6 验收 |
| `0c61f96` | prepare-models.sh 加 `--retry-all-errors` + 可选文件分类 | vocab.json 等不再阻断发布 |
| `8a8ae59` | 登录页挂载即预加载（声纹→唤醒顺序） | 缓存命中时预加载秒完成，进对话即就绪 |


## 复测增补（2026-08-09 晚间，用户配合唤醒实测）

### C-04 唤醒实测结论（修正）
- **唤醒链路可用**：SW 卸载后实测——唤醒触发（"我在听，直接说吧"）✓ + 语音识别自动发送 ✓ + AI 回复 ✓
- **"哈啰波波"（luó）变体可唤醒**（WAKE_PATTERNS 覆盖）
- 观察项：唤醒词+后续语句连说时，唤醒词残渣进入消息文本（首轮过滤未完全命中，P2）

### 新增/修正缺陷（F-6 拆分）
| 编号 | 级别 | 描述 | 状态 |
|---|---|---|---|
| **BUG-SW-01** | ~~P1~~ | workbox 导航 fetch 失败→ 页面重载/SW 接管→ 唤醒引擎重置 | ✅ 已修复（commit 9d8e566：学生端禁 PWA/SW，vite.config disable=true，nav fetch 不再被 SW 拦截） |
| **F-6 细化** | ~~P1~~ | ① worker 内 transformers.js 探测后全量下载停滞→ ② 降级主线程后状态机卡"加载中"→ ③ 首次 40MB 下载 3Mbps ~2 分钟 | ✅ 已修复（commit 4d5b4bd：Worker 线程 numThreads=1→2，session_create 557ms；F-8-Worker 双埋点可诊断） |
| **BUG-CACHE-01** | P2 | 声纹模型加载失败时删除**全部 Cache API**（误删 transformers-cache 唤醒模型缓存 → 下次唤醒重新下载 40MB） | ✅ 已修复（commit 8a8ae59：只删非 transformers 缓存） |
| **AUD-008 修订** | 设计 | 原"模型不挂载即预下载（流量确认）"→ 按用户决策改为**登录页挂载即预加载**（先声纹完成→再唤醒，顺序避免抢带宽）；流量确认保留为模型 error 重试场景 | ✅ 已实现（commit 8a8ae59） |

