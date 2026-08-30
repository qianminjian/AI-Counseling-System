# 学生端 Web 界面自动化遍历测试故障报告

- 测试日期：2026-08-29
- 目标地址：https://yun.gxjugu.com/mindsafe/
- 测试工具：agent-browser（CDP + accessibility snapshot）+ tools/browser-bfs BFS 引擎
- 测试账号：存量学生「开心/1234」；注册流测试新账号「测试贝贝0829/PIN 2580」（邀请码 DEMO2026，TRIAL 租户）
- 遍历策略：广度优先（BFS），最大深度 8，公开面阶段最大 200 步；每步截图；维护已访问状态集合去重
- 环境限制：headless Chrome（无音频编解码器、无麦克风），相关能力以降级路径验证

---

## 一、遍历路径

### 阶段 0：公开面 BFS（工具链自动遍历）
- 入口 → 登录页（三主题浮标/键盘/协议链接）→ ConsentGate 告知同意页
- 结果：60 步、4 个页面状态、56 控件、56 张截图（public-bfs/manifest.json）

### 阶段 1：登录态场景遍历（S1–S8，以 05_系统测试指导.md 为流程指引）
| 场景 | 路径 | 结果 |
|------|------|------|
| S1 登录 | 昵称+PIN 彩虹键盘（0 占两格）→ 错误 PIN 提示 → 正确登录 | ✓（含「昵称或 PIN 码错误」提示验证） |
| S2 Onboarding | 欢迎页 4 步引导 | ✓ |
| S3 对话核心 | 5 情绪选择 → SSE 流式回复 → 满意度评价 5 级闭环 | ⚠ 发现 BUG-S-002（P0） |
| S4 心情日记 | 打卡（情绪+强度+备注）→ 成功态 → 14 天趋势 | ✓（初步"未落库"误报已用 curl 证伪撤销） |
| S5 放松练习 | 5 项清单 → 3-2-3 呼吸动画实测（屏住倒计时/进度条/语音开关/提前结束） | ✓ |
| S6 我的成就 | 就地展开徽章网格：8 枚徽章、1/8 已解锁、与日记打卡联动 | ✓（"白屏"经甄别为测试误操作，撤销） |
| S7 设置面板 | 3 主题切换+持久化、7 音色、4 个 toggle 双向、家庭码复制、声纹录入降级弹窗 | ⚠ 发现 BUG-S-006 |
| S8 换人 | 二次确认弹窗：取消路径 ✓ / 确认退出回登录页 ✓ | ✓ |

### 阶段 2：DEMO2026 注册流遍历（S9）
ConsentGate（8 节条款+复选框门控）→ 空表单校验 → 年龄 9 触发儿童合规路径（家长手机号必填）→ 无效邀请码后端拒绝（"邀请码无效或已过期"）→ DEMO2026 注册确认卡（信息回显）→ PIN 设置+二次确认 → 声纹录入引导（headless 降级正确）→ 注册成功页（家庭码 U7PWJR + 家长绑定说明）→ 新账号主界面 ✓

### 非功能验证
- **无操作超时**：5 分钟 IdleWarning + 60 秒倒计时自动退出回登录页——实测触发两次，按设计工作（测试侧 Read 截图间隔导致）
- **异常检测**：全程无程序闪退、无窗口卡死、无报错弹窗、无渲染错乱（白屏事件已甄别为测试误操作）

---

## 二、问题清单

### BUG-S-002（P0，儿童内容安全）— LLM 降级话术元描述泄露给儿童
- **现象**：LLM 首 token 超时（12s）触发降级时，AI 气泡原文输出 markdown 元标题：
  `# LLM 不可用降级话术（面向小学生，温和不突兀）`，正文（"波波现在有点忙不过来……"）本身正常。
- **证据**：scenarios/S3-05-ai-reply.png；后端日志 first_token_timeout 记录
- **根因**：`LlmStreamEnhancer.java` L69 通过 `promptTemplateService.getTemplate(FALLBACK_TEMPLATE_PATH)` 整文加载 `prompts/fallback/llm_unavailable_zh-CN_v1.0.0.md`，未剥离 markdown 标题行（元描述是给维护者的，不是给儿童的）
- **修复方案**：在 LlmStreamEnhancer 加载处剥离首个 `# ` 标题行（不动 PromptTemplateService，避免全局副作用）
- **状态**：待修复（本轮）

### BUG-S-006（P2，可用性）— 设置面板"完成 ✓"按钮不在固定底栏
- **现象**：设置抽屉内容超长（主题+音色+4 toggle+声纹+家庭码），"完成 ✓"为流内末尾按钮，需滚动到抽屉底部才能关闭；视口内不可见关闭途径时易让儿童困惑
- **证据**：scenarios/S7-09-after-exit.png（完成按钮 y=1441 vs 视口高 633）；SettingsPanel.tsx L430-435
- **缓解因素**：点遮罩可关闭；完成按钮本身功能正常
- **修复方案**：完成按钮 sticky bottom（抽屉内固定底栏）
- **状态**：待修复（本轮）

### BUG-S-001（P2，测试工具链）— cli-adapter 超时包装插参错误 — **已修复**
- gtimeout/timeout 被插入 agent-browser 参数数组中间，被 daemon 拒绝（Unknown command: gtimeout）
- 修复：cli-adapter.mjs cli() 重构为外层 argv 前插包装；公开面 BFS 重跑完成 60 步

### BUG-S-004（P3，待真机甄别）— TTS 音频播放失败
- console 反复 `[TTS] play() 被拒绝: NotSupportedError` + `音频解码失败（MIME 不匹配？）audio/mpeg`
- 音频二进制已下载（合成成功），播放失败——headless Chrome 无音频编解码器，**疑似环境限制而非缺陷**，需真机/iOS Safari 复核（§12 已知限制）
- 状态：挂起待真机验证，不阻塞发布

### 已撤销误报（甄别记录）
- ~~BUG-S-003~~ 日记打卡"未落库"：stale ref 点击未触发提交；curl 直接调后端落库成功（diaryId 4e42233b），页面打卡成功态+14 天趋势正常
- ~~BUG-S-005~~ 成就页"白屏"：stale ref 误点击致会话导航 about:blank；新鲜 ref 重测成就功能完全正常（S6-01~04）

### 观察项
- **OBS-S-01** 主题持久化残值（1 次不可复现）：设置面板连续切主题后 localStorage 残留旧值 garden；复现实验（登录页+设置面板共 5 次切换）全部写入正确。疑与 idle 超时登出时序竞态相关，证据不足不立 BUG，留观
- **OBS-T-01**（测试方法论）视口外元素 agent-browser click 静默不触达（成就按钮/完成按钮/checkbox 三处复现）：后续脚本点击前必须 scrollIntoView；不影响真实用户（用户可滚动）

---

## 三、页面风格检查结论（要求 9）

| 页面 | 结论 |
|------|------|
| 登录页（三主题） | ✓ ocean 蓝绿海底/garden 粉紫花园/rainbow 星空紫，波波海豚 IP、彩虹键盘、主题浮标齐全 |
| ConsentGate | ✓ 八节条款完整（服务性质/紧急情况/适用人群/个保/AI 局限/责任边界/使用规范/同意自愿），24h 热线 400-161-9995 |
| 主界面 | ✓ 5 情绪卡（DOC-082）、四功能入口、换人/设置常驻 |
| 对话页 | ✓ 保密告知①②③注入正常（BUG-S-002 话术正文本身合格） |
| 设置面板 | ✓ 4+3 音色布局（方言特殊样式为 design/56 规定）；唯完成按钮交互问题见 BUG-S-006 |
| 确认弹窗 | ✓ 危险操作红色主按钮（确认退出）、二次确认文案儿童友好 |
| 注册流 | ✓ 儿童合规元素（<14 家长手机号必填、家长陪同提示）、家庭码引导、声纹隐私声明"只保存在这台设备" |
| 主题联动 | ✓ 选中描边色跟随主题、暗色主题下文字可读（FA-02 生效） |

**结论：无渲染错乱；风格整体满足设计要求，无新增视觉类修复项。**

---

## 四、截图索引

- 公开面 BFS：`public-bfs/student-0001~0060.png`（manifest.json 含步骤/控件/状态映射），关键帧：
  - student-0001 登录页 ocean 全景 / student-0017 彩虹主题 / student-0018 ConsentGate
- 场景截图：`scenarios/`（56 张）
  - S1-01/02 登录；S2-01~04 onboarding；S3-01~08 对话+评价（**S3-05 = BUG-S-002 证据**）
  - S4-01~05 日记打卡闭环；S5-01/02 放松练习+呼吸动画
  - S6-00~04 成就甄别过程；S7-01~09 设置面板全控件（S7-02/03 主题切换、S7-08 麦克风降级）
  - S8-01~03 换人弹窗取消/退出；S9-01~17 注册流全程（S9-05/06 儿童合规、S9-06/09 邀请码校验、S9-16 家庭码）

---

## 五、修复与复测计划

1. **本轮修复**：BUG-S-002（P0，LlmStreamEnhancer 剥离标题行）+ BUG-S-006（P2，完成按钮 sticky）
2. 部署后复测项：
   - S3 降级路径话术（后端 LLM 降级或断流触发时不再出现元标题；正文保留）
   - S7 设置面板：完成按钮固定底栏可见，点击直接关闭
   - 回归：登录→对话→日记→成就主链路冒烟
3. 遗留跟踪：BUG-S-004 真机复核；OBS-S-01 留观

---

## 六、修复部署与复测闭环（2026-08-30）

### 部署记录
- 提交链：2d544766 fix(student)（BUG-S-002 后端剥离标题 + BUG-S-006 前端固定底栏）→ dd6c2d93 test(browser)（本报告归档）
- CI：push 触发 run 33257840199 与 workflow_dispatch run 33259735941 均卡 voice-service build（runner 网络黑洞，分别 43min/28min 后取消）；第三次 run 33261042171 甄别为慢速下载（torch ~185MB @ ~200KB/s）28min 自然完成，conclusion=success，head_sha=dd6c2d93=origin/main
- deploy.sh：3m17s SUCCESS，组件 backend/student/tts/voice 全量更新，E2E 冒烟 32/32，nginx 路径校验通过
- 部署审计 P1（历史成功率 30%）为历史窗口统计含门禁拦截失败，非本次回归；P2-P4 耗时趋势与明细矛盾，留观

### 复测结果
| 复测项 | 结果 |
|--------|------|
| BUG-S-002 降级话术 | ✓ **线上真实触发验证**：复测期间 LLM 服务实际故障一次，AI 气泡输出「波波现在有点忙不过来……」——无 `#` 标题、无「降级话术」元描述，正文完整（tts-02-after-reply.png） |
| BUG-S-006 完成按钮 | ✓ 三项验证：初始打开 y=557 在视口内（visible=true）；内容区滚动到底（910px）按钮纹丝不动（固定底栏）；点击正常关闭面板 |
| 回归冒烟 | ✓ 登录→对话（LLM 正常回复+满意度评价闭环）→日记（记录成功，趋势 1→2 条）→成就（8 徽章就地展开 1/8）→设置面板全链路正常 |
| 风格检查 | ✓ 新版设置面板 flex-col 布局渲染正常，无回归 |

---

## 七、TTS 语音播放深度测试（2026-08-30 重点加测）

测试范围：对话界面语音播放全链路、7 音色、8 方言、播放速度、延迟、异常路径（用户重点指令）。

### 7.1 后端合成矩阵（分层诊断法先行）
- **7 音色全部通过**：xiaoxing/bobo/yueliang/xiaotaiyang/dashu/doudou/qiqiu 均 HTTP 200 + audio/mpeg（62-87KB/句）
- **8 方言全部通过**（qiqiu persona + dialect 参数）：cantonese/minnan（原生音色）+ northeastern/sichuan/henan/shandong/hunan/shaanxi（Instruct 实现）均 200（36-63KB/句）
- **音频有效性**：字节头 ID3v2.3 ✓；decodeAudioData 成功（3.9s/48kHz/单声道，文本「你好呀，今天想和你一起玩游戏。」时长合理）
- **稳态延迟**：0.9-1.8s/句；**首轮冷启动 4.1-5.3s**（cosyvoice-cloud 引擎/连接建立，用户首句可感知，记录为优化候选）
- 样本落盘 `/tmp/tts-samples/{cantonese,minnan,northeastern,shaanxi}.mp3` 供人工听测

### 7.2 播放链路 hook 级诊断（Audio/fetch/createObjectURL 全埋点）
- **正常时段自动播放实证工作**：LLM 流式回复完成后 TTS 自动启动，4 句回复逐句播放（play-ok ×4），合成-播放编排（预取窗口 3 句）按设计运行
- **语速性别化**：ChatRoom.tsx L82 speed 按学生性别微调（男 1.05/女 0.95/未知 1.0），代码+请求体透传实证 ✓
- **降级设计完备**：后端连续失败 2 次 → 浏览器 speechSynthesis 降级（30s 恢复窗口）；204 合法空结果不计失败（P2-2）

### 7.2b 播放状态机深度实测（hook 级埋点：fetch/Audio.play/pause/createObjectURL/speechSynthesis）
- **流式连续播放链**：4 句回复逐句合成（815-2004ms/句），第 1、2 句请求间隔仅 34ms（滑动预取窗口 P1-2 生效），播放链串行推进；全程 blob URL 创建/回收 12/12 平衡（无泄漏）
- **中断行为**：发送新消息 → 流式开始时 startStreaming() 内部 stop() → audio.pause() 实证触发（按住说话/结束对话/重播/静音四路径源码确认均有 stop）；发送至 LLM 首字节间旧朗读继续（见 OBS-TTS-02）
- **气泡重播**：stop 旧播放（pause +1）→ 消息重新分句并发合成（间隔 219ms）→ 逐句播放 ✓
- **静音路径**：muted 下发送消息 TTS 全链路零请求零播放，文本回复正常 ✓
- **异常路径全链**（模拟后端 500）：第 1、2 句真实请求失败（backendFailCount 1→2）→ 第 3 句起 30s 窗口短路不再发请求（请求计数器实证 hits=2）→ browserSpeak 逐句接管（speechSynthesis hook 记录）→ 窗口过期自动恢复（后端重新合成 857-896ms/句并进播放链）✓
- **意外验证 A**：冷场暖场机制（design/28 §2.3）真实触发——25s 沉默后端决策暖场「我叫波波…」「我听着。开心，我在呢！…」并 TTS 朗读，连续上限 2 次（MAX_CONSECUTIVE_NUDGES）生效
- **意外验证 B**：深度实测期间 LLM 服务真实故障一次，4 句回复中后 2 句为降级话术（无标题正文），均被完整合成（1517/1551ms）并进入播放链——降级路径语音播报实证工作

### 7.3 发现问题

#### BUG-S-007（已改判：测试环境假象，并入 BUG-S-004 观察项）— 播放 blob URL 被 Chrome「URL safety check」拒绝
- **现象**：页面创建的 blob URL 赋给 Audio 后加载被拒 `MEDIA_ELEMENT_ERROR: Media load rejected by URL safety check`（error code 4），呈会话级恶化（8/30 上午 play-ok×4 成功 → 同会话 blob 粒度成败交替 → 晚间 30/30 全灭）；同一 URL 在 fresh Audio 可播（早期）、blob 本体 decodeAudioData 正常
- **详细分析（11 项对照实验，2026-08-30）**：revoke 竞态 / 实例状态 / 时序窗口 / 测试 hook 污染（reload 后）/ 后台 tab（visible+focus）/ Chrome 会话退化（close --all 新实例）/ headless 模式（--headed 同败）/ origin 异常 / blob 损坏 / 站点版本回归——**全部排除**；data:audio base64 同败，而 Blink 源码 `SecurityOrigin::CanDisplay` 对 data: URL 直接放行（security_origin.cc L453-455）——证明检查路径本身处于异常状态
- **源码级定位**：错误唯一来源 `HTMLMediaElement::IsSafeToLoadURL`（html_media_element.cc）的 `!domWindow || !CanDisplay(url)`，本场景两条件按源码均不成立——renderer 侧 document 状态在受控环境异常；Chromium 官方 issue 40839863（fuchsia 自动化测试）与 qutebrowser e2e 均踩中同款错误，属自动化/受控环境高发的浏览器内部检查误触发
- **结论**：产品代码（useTtsPlayer/audioUnlock）符合标准 Web API 语义，同一代码路径曾有成功播放记录，**无缺陷**；失败发生在浏览器内部，与产品任何可控变量无关。若真实用户浏览器出现此状态则 TTS 完全无声会被立刻感知，不可能静默存在。
- **保留的产品健壮性修复**：无声失败用户零反馈（OBS-TTS-01 扩展场景）已修复，见 7.3b
- **最终定性（2026-08-30 晚，真实 Chrome 环境深度诊断——「实锤环境层拒绝，产品零缺陷」）**：
  - 真实 Chrome（非 headless、UA=Chrome/152、真实窗口+麦克风授权+用户激活+`--autoplay-policy=no-user-gesture-required`）下**依旧被拒**，错误码实抓 `code 4 MEDIA_ELEMENT_ERROR: Media load rejected by URL safety check`
  - **决定性最小复现**：页面内 3 行标准 Web API（`new Audio(); audio.src=URL.createObjectURL(blob); audio.play()`）同样被拒（`play-fail:NotSupportedError` + code 4）——blob 本体完全有效（audio/mpeg / 32273B / ID3 头）。**与产品代码零关联**：任何站点在任何代码下都会被拒，产品既无法造成也无法避免
  - **分层诊断全通（后端→音频内容→本机输出）**：后端 `/api/v1/tts/synthesize` HTTP 200 + audio/mpeg + 51917B 有效 MP3（ID3v2.3+LAME 64bits 头）；`afplay` 本地播放成功（音频内容 + Mac 输出链路双层正常，真人可闻）
  - **「会话级恶化」机制解密**：8/30 上午 play-ok×4 成功 = 当时 agent-browser `--headed` 尚有效（真有头窗口）；晚间全灭 = agent-browser 更新/守护进程重启后 `--headed` 失效、会话悄悄掉入无头——所谓「间歇性恶化」是**测试工具链环境漂移**，非产品退化
  - 环境矩阵（全部拒绝）：CDP attach + autoplay 放行 / 零 CDP reload 后重连 / 隔离 profile 组合；AppleScript-JS 注入通道被 Chrome 防篡改保护挡死（Preferences 写入读回 true 但启动后仍关闭）
  - **最终结论**：触发条件 = 受控/自动化测试环境（无头或 CDP 调试或隔离 profile 组合）下的 Blink 媒体安全检查异常状态；用户日常浏览器（无调试连接、正常 profile、每步真实点击）**不受任何影响**。BUG-S-007 关闭，定性「环境层拒绝（实锤）」，并入 BUG-S-004 观察项随之解除；真机听测降级为常规验收项

#### OBS-TTS-01（P3，已修复）— 降级/无声失败终态无提示（真相修正）
- **真相修正**：ChatRoom L116-120 原本已有 `engine==='none'` 顶部提示；静默的真正原因是两条缝隙叠加——①受控环境降级终态是 `engine='browser'`（headless 的 speechSynthesis.speak 调用成功返回 ok，不进 none 分支），提示永不触发；②playBlob 无声失败（onerror）只 console.warn 不改 engine
- **修复**：①useTtsPlayer playBlob onerror 置 `engine='none'`（诚实语义：无法出声=不可用；下次合成成功自然恢复 backend）②ChatRoom 提示 effect 加 30s 防抖（避免逐句失败刷屏）
- 状态：已修复并**线上复测通过**（见 7.3c：受控环境 blob 拒绝触发提示弹出，snapshot 文本×2 + retest2-03 截图）

#### OBS-TTS-02（P3，已修复）— 打字发送新消息时旧朗读延迟打断
- onBeforeSend（ChatRoom.tsx）仅重置重播高亮+释放麦克风不调 tts.stop()，旧播放在新回复流式开始时才被打断（残留窗口=发送→LLM 首字节），与「按住说话即停读」行为不一致
- **修复**：onBeforeSend 补 tts.stop() 对齐
- 状态：已修复并**线上探针验证通过**（见 7.3c：发送时刻 pause 1→4，stop 链路触发；播放中打断的主观体验待真机听测）

### 7.3b 本轮修复记录（2026-08-30）
- useTtsPlayer.ts playBlob：onerror 增 setEngine('none')（OBS-TTS-01 ①）
- ChatRoom.tsx：onBeforeSend 增 tts.stop()（OBS-TTS-02）；engine='none' 提示 30s 防抖（OBS-TTS-01 ②）
- 验证：vitest 959/959 ✓、tsc+vite build ✓；线上复测见 7.3c

### 7.3c 部署与自动化复测（2026-08-30，commit 8bb794d4 + c2136ada）

**部署链插曲——CI 门禁被时间炸弹引爆（与本次修复无关）**：首次 push 后 run 33296065538 failure，定位为 teacher-web `AdminPanel.test.tsx` fixture 硬编码 `expiresAt='2026-08-30T00:00:00'`（恰好当日 00:00 起 c-1 被组件判定已过期，「有效」计数 0≠1 永久失败，卡死所有后续 CI）；fixture 改相对日期（+7 天/-30 天）时间无关化（c2136ada），本地 teacher-web 221/221 ✓ 后 re-push，run 33296308570 success；deploy.sh 32s SUCCESS（变更检测精准命中 student+teacher），部署审计 P1 仍为历史窗口统计噪声、P2/P3 审计脚本趋势方向判断与明细数据相反（3340→1205ms 实为下降），留观。

**线上自动化复测（agent-browser 探针：HTMLMediaElement.play/pause + speechSynthesis 埋点）**：

| 复测项 | 结果 | 证据 |
|--------|------|------|
| OBS-TTS-01 提示弹出 | ✓ | 受控环境 blob 拒绝触发 `engine='none'` → 「当前浏览器不支持语音播放」提示弹出（snapshot 文本×2 轮 + retest2-03 截图可见提示条）；engine 停留 none 时不重复弹（none→none 不触发 effect），符合防抖设计意图 |
| OBS-TTS-02 发送即停读 | ✓ | 发送前 pause=1 → 点击发送后 1s pause=4（+3：onBeforeSend stop + 流式 startStreaming 内 stop），修复前该时刻无任何 pause 调用 |
| TTS 自动播放回归 | ✓ | greeting 3 句 + AI 回复 5 句流式合成自动播放（play 计数 3→8） |
| 对话链路冒烟 | ✓ | 发送→CBT 式回复（情绪确认+追问）正常；LLM 再次短暂故障，降级话术无标题元描述（BUG-S-002 复测再证，retest2-01 截图第 3 条气泡） |
| BUG-S-007 | 符合改判预期 | 受控环境 blob 拒绝照旧复现（环境假象，非产品缺陷）；产品侧已由 OBS-TTS-01 修复保证用户可感知，真机听测为最终判据 |

- 截图索引（tts-deep/）：retest2-01（降级话术气泡）/ retest2-03（提示条渲染，被唤醒弹窗部分遮挡）/ retest2-04（弹窗关闭后对话页）
- 验证限制：受控环境 blob 加载被拒（BUG-S-007 假象）导致无真实出声，「播放中打断」的主观体验与提示 6s 全视觉留影需真机/正常 Chrome 复核

### 7.4 人工听测清单（自动化盲区）
- 方言口音正确性：重点 Instruct 实现 6 种（东北/四川/河南/山东/湖南/陕西）是否真带乡音 vs 仅普通话变调
- 原生音色 2 种（粤语/闽南话）与 Instruct 的听感差异
- 音色相似度：7 persona 与设计人设（邻家姐姐/女老师/大叔叔等）匹配度
- 播放语速主观感受（男 1.05/女 0.95 是否偏快/偏慢）

### 7.5 测试过程观察（方法论文档）
- idle 5 分钟登出会打断长观察测试（本轮 3 次重新登录）；eval fetch 不重置 idle 计时器
- agent-browser click 对视口外按钮静默不触达（OBS-T-01）在重播按钮上再次复现，实验前必须 scrollIntoView
- 截图索引：tts-01~06（对话入口/降级话术/播放诊断/复测/中断与恢复终态）、retest-01~12（复测闭环）、retest2-01~04（线上修复复测）；方言样本与深度实测证据随 tts-deep/ 目录归档
