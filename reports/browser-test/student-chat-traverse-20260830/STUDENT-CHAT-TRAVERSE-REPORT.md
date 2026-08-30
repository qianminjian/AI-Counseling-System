# 学生端对话窗口多场景遍历测试报告

> 日期：2026-08-30 | 场景代号：C 系列（C0~C9）| 测试人：Qoder Agent（全自动，用户零操作）
> 被测：https://yun.gxjugu.com/mindsafe/（学生端 H5，线上生产环境）
> 账号：存量学生 开心 / PIN 1234（TRIAL 租户）
> 功能指引：design/05_系统测试指导.md（§4 学生端功能测试要点）
> 工具链：真实有头 Chrome（CDP 9222 + /tmp/cdp.mjs 驱动 + `--autoplay-policy=no-user-gesture-required` + 隔离 profile /tmp/chrome-wake-test）
> 背景关联：BUG-S-007 历史定性（环境层拒绝，产品零缺陷）见 reports/browser-test/student-traverse-20260829/STUDENT-TEST-REPORT.md §7.3——**已被本报告 §6.1 第三次改判推翻（实为生产 nginx CSP 缺陷）**

---

## 1. 遍历路径总览

```
C0 落地页 → 登录（开心/1234）→ 跳过引导
  ├─ C1 情绪选择页：五情绪入场全验证（开心/难过/生气/害怕/紧张 × 各自开场白）
  │    └─ 开心 → 对话室
  │         ├─ C2 CBT 多轮对话（3 轮：画画小红花→一家人海边→沙滩排球）
  │         ├─ C3 重播按钮（30s 防抖期内提示不重复）
  │         ├─ C4 🆘 SOS 帮助（12355 热线 + 54321 接地 + 深呼吸 + 安全小岛）
  │         ├─ C5 🧰 百宝箱（5 练习卡片 + 心情温度计 3 步全流程 → 庆祝页）
  │         ├─ C6 ⚙️ 设置（主题切换花园精灵 + 音色切换豆豆 +「你还在吗」弹窗）
  │         └─ C7 结束 → 满意度评价（5 级 → 提交 → 回情绪页）
  │    └─ C1 续：难过/生气/害怕/紧张 四情绪循环入场+结束（验证 5 开场白适配）
  ├─ C8 主界面菜单（无操作自动退出后）：心情日记 → 我的成就(1/8) → 放松练习
  └─ C9 🔄换人：确认弹窗（取消路径 + 确认退出路径）→ 登录页
```

- 最大深度 8 / 步数上限 200：实际 9 个场景组 32 步有效操作，未触限
- BFS 防循环：已操作控件集合维护（每场景组内按钮 title/text 去重），无重复遍历
- 弹窗策略：所有弹窗（SOS/评价/超时/换人确认）均进入遍历内部控件后关闭回原页面

## 2. 验证通过项（25 项）

### C0/C1 登录与情绪入场
| # | 验证点 | 结果 | 证据 |
|---|---|---|---|
| 1 | 登录页元素齐全（昵称/彩虹键盘/声音进入/隐私协议） | ✓ | C0-00 |
| 2 | 开心 → 「看起来你今天心情不错呀！…😊」 | ✓ | C1-01~03 |
| 3 | 难过 → 共情开场「我感觉到你今天有点难过…💙」 | ✓ | 对话实录 |
| 4 | 生气 → 正常化命名「生气是很正常的感受哦」 | ✓ | 对话实录 |
| 5 | 害怕 → 安全感「这里很安全，我会一直陪着你。🌟」 | ✓ | 对话实录 |
| 6 | 紧张 → 接地呼吸「深呼吸一下，我们慢慢聊。🌈」 | ✓ | 对话实录 |

五情绪开场白适配策略 5/5 与 DOC-082 定稿一致（§4.3 创建会话）。

### C2 对话核心链路
| # | 验证点 | 结果 | 证据 |
|---|---|---|---|
| 7 | 发送→SSE 流式回复到达 | ✓ | C2-01 |
| 8 | CBT 引导式追问（画画→画里有什么→最喜欢哪部分） | ✓ | C2-01/02 |
| 9 | thinking 三颗水泡动画（回复等待期） | ✓ | C2-02 |
| 10 | 朗读状态气泡（水泡话语同步） | ✓ | C2-02 |
| 11 | OBS-TTS-01 修复生效：降级时「当前浏览器不支持语音播放，可阅读文字内容 📖」提示弹出 | ✓ | C2-01 可见提示条 |

### C3 重播
| # | 验证点 | 结果 | 证据 |
|---|---|---|---|
| 12 | 重播按钮 aria-label 正确（播放语音/正在播放语音切换） | ✓ | C3-01 |
| 13 | 30s 防抖：engine=none 期间提示不重复弹 | ✓ | C3-01 |

### C4/C5 安全与工具
| # | 验证点 | 结果 | 证据 |
|---|---|---|---|
| 14 | SOS 弹窗：12355 热线固化 + 54321 接地法 + 深呼吸 + 安全小岛入口 | ✓ | C4-01/02 |
| 15 | SOS 返回对话不中断（会话保持） | ✓ | C4-02 → 回对话室 |
| 16 | 百宝箱 5 练习卡片齐全 | ✓ | C5-01 |
| 17 | 心情温度计 3 步流程（选情绪→强度滑条→原因→庆祝页） | ✓ | C5-02/03 |

### C6 设置
| # | 验证点 | 结果 | 证据 |
|---|---|---|---|
| 18 | 设置面板 6 区块（3 主题/7 音色/语音播报/语音唤醒/动效触感/声纹） | ✓ | C6-01 |
| 19 | 主题切换花园精灵 → 波波即时变粉 + 持久化（登录页描述同步「花园里的小精灵」） | ✓ | C6-02/C9-02 |
| 20 | 音色切换豆豆 → 设置即生效 | ✓ | C6-03 |

### C7/C8/C9 结束流程与主界面
| # | 验证点 | 结果 | 证据 |
|---|---|---|---|
| 21 | 评价弹窗 5 级 + 可跳过 +「再聊一会儿」链接 | ✓ | C7-01 |
| 22 | 提交评价 → 回情绪选择页 | ✓ | C7-02 |
| 23 | 心情日记：连续打卡 2 天（累计 3 天）/今日已记录/14 天趋势图 | ✓ | C8-02/03 |
| 24 | 我的成就 8 徽章（初次记录已解锁；「三天坚持」未解锁与连续 2 天数据一致） | ✓ | C8-04 |
| 25 | 换人：确认弹窗（我点错了→取消 / 确认退出→回登录页） | ✓ | C9-01/02 |

### 无操作超时链路（设计 L188：5 分钟→60s 倒计时→自动退出）
三次真实触发全部按设计工作：
1. C6 设置遍历中弹「你还在吗？」（55s 倒计时可见）→ 点「我还在！」续期 ✓
2. C8 日记页再次弹出（27s 倒计时）→ 点「我还在！」关闭 ✓
3. C8 前段倒计时走完 → 自动退出回主界面（未丢数据，主界面正常）✓

## 3. 问题清单（按优先级）

| # | 级别 | 问题 | 复现 | 建议 |
|---|---|---|---|---|
| 1 | P3-UX | **子页面交互不重置无操作计时器**：进入日记/成就/放松/设置/百宝箱等子页面及阅读停留期间，idle 计时器继续走（本轮实测 3 次弹窗，其中 1 次发生在打开日记页约 60s 内）。学生在设置里录声纹、在百宝箱做 120s 身体扫描等场景会被误弹窗打断 | C6-01~04、C8-03 | 所有页面切换/弹窗交互统一调用 resetIdle；或子页面停留期暂停计时 |
| 2 | P3-Doc | **满意度评价文档-实现不一致**：文档 §4.3 L186 写「满意度评价（😊/😐/😢）」3 级；实现为 5 级（不太好😢/一般般😐/还不错🙂/挺好的😊/特别好🥰） | C7-01 | 更新文档为 5 级——实现与教师后台质量监控「评分 1-5」（L326）对齐，粒度更合理 |
| 3 | P4-UX | **engine=none 时点重播静默跳过**：TTS 不可用时点击消息重播按钮无任何反馈（30s 防抖的顶部提示仅部分缓解） | C3-01 | 重播点击时若 engine=none 给一次性轻提示（如 toast「暂时无法播放语音」） |
| 4 | P4-数据 | **日记统计口径疑点**：「累计 3 天」但 14 天趋势图仅 2 根柱（08-29 + 今天）；若 08-28 有记录应显示 | C8-03 | 核实累计天数统计与趋势图数据源是否同表同过滤条件 |
| 5 | P4-代码 | **styled-components style 标签文本泄漏进 body.innerText**（@keyframes 内容出现在 DOM 文本抓取中）——无渲染影响，纯代码洁净度问题 | 全程 DOM 检查 | 低优先级；可在自动化断言中过滤，或评估 styled-components 升级 |

## 4. 风格检查（青屿设计体系）

结论：**全部通过，无新增问题**。

- 五情绪页：彩虹渐变按钮 + 副文案（如「有好事发生」）布局一致，间距统一
- 对话室：气泡（AI 米黄左/学生主题色右）+ 波波形象 + 头部工具栏（静音/SOS/百宝箱/设置/换人/结束）排布正常
- 花园精灵主题：切换后情绪页/主界面/日记/成就/登录页全局粉色系一致（C8-02~C9-02 系列截图佐证）
- 弹窗家族：SOS（警示红）、评价（白卡+emoji 行）、超时（倒计时）、换人确认——风格统一、按钮左右对（取消左/确认右）
- 全程未发现：文字溢出、按钮错位、渲染错乱、白屏、字体回退异常

## 5. 截图索引（shots/，24 张）

| 文件 | 内容 |
|---|---|
| C0-00-landing.png | 落地页（波波小精灵+登录表单） |
| C1-01~03 | 情绪选择页 / 开心选中 / 对话室开场 |
| C1-03b-chatroom-8s.png | 对话室 8s 稳态 |
| C2-01-after-send.png | 第 1 轮发送+回复+TTS 降级提示条 |
| C2-02-round2.png | 第 2 轮 thinking 水泡+朗读状态 |
| C3-01-replay.png | 重播点击后状态 |
| C4-01/02-sos-*.png | SOS 弹窗顶部/底部（12355+练习入口） |
| C5-01~03 | 百宝箱卡片 / 心情温度计流程 / 完成庆祝 |
| C6-01~04 | 设置面板 / 花园主题生效 / 豆豆音色 / 关闭后 |
| C7-01/02 | 评价弹窗（5 级+再聊一会儿）/ 提交后回情绪页 |
| C8-01~05 | 超时退出后主界面 / 日记页 / 日记+保活后 / 成就徽章 / 放松列表 |
| C9-01/02 | 换人确认弹窗 / 退出后登录页（花园主题延续） |
| V1-wake-attempt.png | 唤醒 standby 提示（叫我“哈喽波波”） |
| VP1-enroll-overlay.png | 声纹录入 overlay |
| VP2-wake-standby-final.png | 唤醒最后一轮 standby 稳态 |

## 6. 语音三件套专项验证（2026-08-30 下午，用户指认最高优先级）

> 背景：用户反馈「语音播放没听到 / 唤醒没成功 / 声纹登录没验证」，三大语音功能为产品核心。
> 方法：CDP 全自动 + AnalyserNode 客观声能量采样（替代人耳听测）+ Worker 日志探针（tmp/wake-probe.mjs）+ say -v Flo 外放→麦克风物理回采闭环。

### 6.1 TTS 语音播放 —— 【P0 生产缺陷，已修复待部署】

**BUG-S-007 第三次改判（终版）**：非「环境层拒绝」，实为**生产 nginx CSP 缺陷**。

- 决定性证据：Worker 日志探针抓到 `Loading media from 'blob:...' violates CSP: "default-src 'self'"`（media-src 未设置，回落 default-src）
- 生产响应头实锤（`curl -sI https://yun.gxjugu.com/mindsafe/`）：`script-src 'self' 'wasm-unsafe-eval'` 无 `blob:`、无 `media-src` → **blob 音频对所有用户 100% 静音**（此前「play-ok×4」是 play() 调用计数假象：play 不被 CSP 阻止，加载异步失败）
- 时间线吻合：宿主 nginx CSP 于 **08-12（ac69668a OPS-005）** 上线，与「08-10 语音专项 TTS 出声实证 → 之后全静音」精确吻合（详见 §6.5）；当时加了 `wasm-unsafe-eval`（ONNX 必需）却漏了 blob 媒体/worklet
- **Web Audio 降级路径客观验证通过**：fetch TTS(5643ms/49KB) → decodeAudioData(7ms) → AudioBufferSourceNode + AnalyserNode → 60 帧采样 52 帧有声能量、峰值 237 = **扬声器真实发声**（HTMLMediaElement 被 CSP 拦的同一环境，Web Audio 绕过 URL 加载安全检查）
- **修复**：deploy/nginx/host/nginx.conf（9 处）+ security-headers.conf（1 处）新增 `media-src 'self' blob: data:;`、script-src 加 `blob:`、新增 `worker-src 'self' blob:`；未部署（待用户指令）

### 6.2 语音唤醒 —— 链路存活，端到端命中受测试方法限制

| 环节 | 结果 | 证据 |
|---|---|---|
| 引擎加载（Whisper Tiny 本地） | ✓ | 二次访问缓存命中，session_created 267ms，ready 0s；首装时延已量化（下表） |
| PCM 采集 | ✓（降级） | AudioWorklet blob 模块被 CSP 拦 → ScriptProcessor 降级保命，RMS 有信号（say 期间 peakRms=0.0789 > 0.03 阈值） |
| 滑窗+转写管线 | ✓ | Worker 持续转写 id=1~30，2s 滑窗正常提交/复位，无卡死 |
| 端到端唤醒命中 | ✗ | say 物理回采下 Whisper 输出单字碎片（「我/好/嗯」），无法匹配「哈喽波波」 |

**模型首装下载时延实测**（生产同源 /mindsafe/models/，带宽 ~610KB/s）：

| 模型 | 体积 | 耗时 | 说明 |
|---|---|---|---|
| whisper encoder | 10.1MB | 16.9s | 实际加载 encoder+decoder_merged ≈45MB ≈75s + ORT session 30~60s = **首次唤醒准备 2~2.5 分钟**；二次访问走 CacheAPI 秒级 |
| whisper decoder_merged | 30.7MB | 51.2s | 同上 |
| wespeaker 声纹 | 6.7MB | 10.9s | 登录页预加载，体感可接受 |

**真实用户场景发现**：系统输入音量 33% 时 say 期间 peakRms=0.0015（≈底噪）→ 被静音检测吞掉且无任何提示；调至 76% 后 0.0789 正常。建议产品加「麦克风声音太小」低能量提示（P3）。

**结论**：唤醒词命中在本测试环境（Mac 扬声器→麦克风物理回采频谱失真）无法验证，属测试方法限制而非产品缺陷；CSP 部署恢复 AudioWorklet 后需真人真机验收。

### 6.3 声纹登录 —— 【P0 生产缺陷：voice-login 500，已修复待部署】

| 环节 | 结果 | 证据 |
|---|---|---|
| 录入（enroll） | ✓ | 采集 3 段 embedding → IndexedDB 记录完整（userId/时间戳/模板数） |
| 设备凭证签发 | ✓ | /auth/voice-credential JWT 签发成功（iat/exp/tokenType=voice_credential 全对，TTL 7 天，录入 12 分钟后仍有效） |
| 本地比对（verify） | ✓ | 余弦相似度过阈值，matched=true |
| **voiceLogin 换 token** | **✗ 500** | `POST /api/v1/auth/voice-login` 返回 **HTTP 500 code:10001「系统内部错误」**（浏览器内直接 fetch 复现实锤） |

**根因（代码级实锤）**：AuthController.voiceLogin L227 `authUserService.touchLastLogin(...)` 裸调——该端点 permitAll 无租户上下文，UPDATE users 撞上 M1-003 租户拦截器 fail-fast（MindSafeTenantLineHandler L88-92 抛 IllegalStateException）→ 全局兜底 500。对比：pin-login 的最后登录更新包在 callAsSystem 内（正常）、/voiceprint/verify 的 doVerify 整体包 callAsSystem（正常）——**唯一裸奔点就是 voice-login**。单测全 mock 了 service，SQL 拦截器不执行 → 单测盲区（31 个用例全绿但生产 100% 失败）。

**修复（已完成，未部署）**：
1. backend AuthController.voiceLogin：touchLastLogin 包 TenantContextHolder.callAsSystem（对齐 VoiceprintController.verify 模式）+ AuthControllerTest 31 用例全绿
2. frontend VoiceLoginOverlay：catch 文案「登录钥匙过期啦」→「声音登录暂时打不开，先用秘密数字进入吧」（原文案把 500 归因为凭证过期，误导排查方向）+ VoiceLoginOverlay.test.tsx 17 用例全绿

### 6.4 语音三件套收敛

| 功能 | 状态 | 待部署后动作 |
|---|---|---|
| TTS 播放 | CSP 缺陷已修（nginx 2 文件 10 处）+ 降级链缺口已补（播放失败接通 browser 降级，前端语音 42 用例全绿） | 部署后 blob 主路径线上复测出声 + 真实验证降级链 |
| 语音唤醒 | 链路全环节存活，引擎时延量化完成 | 部署恢复 AudioWorklet + 真人真机验收命中 |
| 声纹登录 | 录入+比对闭环通，voice-login 500 已修（1 行根因修复） | 部署后重跑声纹登录闭环 |

**三项全部卡在同一个 gate：生产部署**（CSP + voice-login 修复）。待用户明确指令后 commit/push/deploy。

### 6.5 前后版本差异深度分析（用户三问终版答案）

#### ① TTS AI 音色转换与逐级降级是否按设计实现？

**AI 音色转换：按设计实现且当前健康**。实测 `POST /api/v1/tts/login-prompt`（白名单引导语 + persona）→ 200，84.9KB MP3（22.05kHz，5.4s）；`/tts/status` → `engine: cosyvoice-cloud, available: true`。声纹录入/验证引导语与对话 TTS 均走 CosyVoice 云端合成——**音频合成一直可用，坏的是前端播放层（CSP）**。

**逐级降级：框架已实现，但「播放失败」分支有执行缺口**——CSP 恰好精确踩中：

| 链路 | 设计降级链 | 实际缺口 |
|---|---|---|
| 对话 TTS（useTtsPlayer） | backend → browser → none | 合成失败 ✓ 正常降 browser；**播放失败（blob 加载被拦）直接 setEngine('none')，跳过 browser**（[useTtsPlayer.ts](frontend/student-h5/src/hooks/useTtsPlayer.ts) onerror 分支） |
| 声纹引导语（VoiceLoginOverlay.speakPrompt） | CosyVoice → speechSynthesis → 字幕 | 降级仅 catch「获取失败」（res 不 ok/blob 空）；**「获取成功但播放失败」只 done() 继续流程，永不降级** → 用户既听不到 AI 音色也无任何声音 |

修复建议（部署 CSP 后仍建议补）：两处 onerror 均接通 browser speechSynthesis 降级（P2）。

**→ 降级缺口已补（同日，未部署）**：
1. useTtsPlayer：playBlob 改为返回失败信号（resolve(boolean)），playSentence（FA-11 降级收敛单点）接通 backend→browser→none 完整链——blob 播放失败（CSP/MIME）降级 speechSynthesis 出声，不再静默 setEngine('none')
2. VoiceLoginOverlay.speakPrompt：audio.onerror 补接 browserSpeak 降级（原仅「获取失败」降级，「播放失败」静默）
3. 测试同步：useTtsPlayer.test.ts 两个 playBlob 错误路径用例按新语义补 utter.onend mock + 降级断言 → 语音相关前端 42 用例（useTtsPlayer 25 + VoiceLoginOverlay 17）全绿，tsc 0 错误

#### ② 那次审计是否改坏了很多东西？为什么覆盖测试没发现？

**破坏点精确锁定：08-12 ac69668a（doing/95 深度审计的部署/CI 批次，OPS-005）**——「宿主 nginx 7 个 MindSafe location 补 CSP（与容器 security-headers.conf 同策略）」。不是审计「改坏了代码」，而是：
1. **策略内容不完整**：OPS-005 从容器 security-headers.conf 复制策略，该策略 08-05（e173df71）为 ONNX 加了 wasm-unsafe-eval 但漏了 media-src/script-src blob:/worker-src——复制时把缺口也复制了
2. **无功能回归验证**：OPS-005 验证只做了「安全头合规」，没有「前端媒体功能回归」

容器 CSP（08-05）不影响宿主 nginx 直接 alias 服务的静态页面 → 08-10 语音专项测试仍全 PASS；08-12 宿主 CSP 上线 → 静态页面首次带 CSP → TTS 静音 + AudioWorklet 被拦。

**测试为什么没拦住（四层盲区）**：
- **单测盲区**：voice-login 31 用例全 mock，MyBatis 租户拦截器不执行 → 500 不可见
- **E2E 环境盲区**：Playwright 冒烟跑 dev/preview，无 nginx → 无 CSP 头 → 复现不了
- **断言盲区**：冒烟用例不含「音频真实出声」断言（需 AnalyserNode 级别客观验证，当时未建）
- **流程盲区**：声纹登录 E2E 从未做过（UI-TEST-012 明确「SKIP-硬件：无麦克风硬件，入口可见性已验证」）；08-12 部署后未重跑 08-10 那样的语音专项回归

#### ③ 前后版本差异时间线（完整重建）

| 日期 | 提交 | 事件 | 对语音三件套的影响 |
|---|---|---|---|
| 07-30 | f02a0fb4 | 审计全量修复：SecurityConfig CSP 收紧（仅 API 路径）+ M1-003 fail-fast 引入 | 静态页面不受影响；fail-fast 上线 |
| 07-31 | 7df53ef3 | **voice-login 创建**：裸 selectById+updateById | **创建即违反同期 fail-fast → 声纹登录从第一天起 500**（无人发现：声纹登录从未 E2E 测过） |
| 08-05 | e173df71 | 容器 nginx security-headers.conf 加 CSP（含 wasm-unsafe-eval） | 只覆盖容器反代的 API 路径，静态页面不受影响 |
| 08-08 | 7bc4d6ab | voice-login 重构（T4）：SELECT 下沉 findByIdAsSystem（修复）**UPDATE 下沉 touchLastLogin 裸调（漏修）** | 半修复：查询好了，500 仍在 |
| 08-10 | UI-TEST-011 | **语音引擎专项全 PASS**：TTS 欢迎语出声实证 + 唤醒近音命中实证（真人转写「哈喽,伯伯」）+ 引擎时延 109s | 最后的好时光——用户「前面都通过了完整测试」属实 |
| **08-12** | **ac69668a** | **OPS-005：宿主 nginx 7 location 补 CSP（复制了不完整策略）** | **破坏点：TTS blob 全静音 + AudioWorklet 被拦（唤醒降级 ScriptProcessor）** |
| 08-29/30 | — | 用户发现「听不到/唤醒没成功/声纹没验证」 | 三个症状两个根因 + 降级链缺口放大 |

**结论**：①「前面没问题」属实（08-10 实证）；②破坏源 = 08-12 OPS-005 复制了不完整的 CSP 策略且无功能回归；③声纹登录 500 与 08-12 无关，是 07-31 创建时的原生缺陷，因「从未 E2E 测过」而潜伏 30 天；④降级容灾设计存在但「播放失败」分支未按设计接通，把「静音」放大成「无声且无降级」。

## 7. 结论

学生端对话窗口及主界面菜单 9 个场景组 **32 步有效操作全部收敛**；25 项功能验证点全部通过，5 项低优先级问题登记待修复。五情绪开场白适配、CBT 多轮引导、SOS 内容合规（12355 固化）、无操作超时完整链路、主题/音色持久化为本次重点确认项。

**语音三件套专项（§6）发现 2 个 P0 生产缺陷（TTS 全量静音 + 声纹登录 100% 失败），均已代码级定位并修复；TTS/声纹引导语「播放失败不降级」缺口已按设计补通（§6.5①），语音相关前端 42 用例全绿，等部署验收**。BUG-S-007 最终定性改为「生产 nginx CSP 缺陷」，此前「环境层拒绝、产品零缺陷」结论作废。

---
*执行方式：全程 CDP 自动化（用户零操作）；异常检测项（闪退/卡死/报错弹窗/渲染错乱）全程监控未触发。*
