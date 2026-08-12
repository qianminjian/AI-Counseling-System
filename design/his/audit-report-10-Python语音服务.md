# 审计报告 10 - Python 语音服务（tts-service / voice-service / py-common）

- **审计时间**：2026-08-12
- **审计范围**：`backend/tts-service`（10 py：app.py 407 行 / tts_policy.py / tts_engines.py / config.py / config.yaml）+ `backend/voice-service`（9 py：app.py 404 行 / asr_engines.py / ser_engines.py / config.py / config.yaml）+ `backend/py-common`（config_loader.py / metrics_common.py）
- **方法**：git log 热点分析（RUNTIME-001/002 降级覆盖、S-017/S-018 收敛）+ 全量读取核心文件（两服务 app.py + tts_policy + ser_engines）+ 错误处理模式对比（全局 handler vs 局部 except）+ 合规路径测试核查（grep unlink/删除）+ 测试盘点（tts 6 / voice 4 文件）+ 冻结决策核对（只读，未改动任何文件）

## 1. 板块概况

两个同构的 Python 微服务 + 一个共享库：

- **tts-service**（FastAPI，版本 4.0.0）：三级降级 TTS（DashScope → CosyVoice → Edge，tts_policy.py:65-88）+ DC-011 适配器层（TTSBackend 接口 + DegradationPolicy 编排）+ RUNTIME-001 覆盖键（tts_policy.py:90-99，fail-open）+ Instruct 重试（:73-75）+ 500 字限制 + 30s 超时 + CORS 白名单。
- **voice-service**（FastAPI）：双引擎 ASR（funasr/dashscope）+ SER 并行，RUNTIME-002 覆盖键（app.py:50-104）+ AUD-042 后缀白名单（:243）+ 10MB 限制（:299）+ ffmpeg 30s/分析 60s 超时（:108-109）+ 线程池单例（:120）+ **COMP-009 转写即删**（:387-398，finally 删除临时音频 + 审计日志）。
- **py-common**：config_loader.py（DA-14 契约校验）、metrics_common.py（指标渲染）已跨服务共享——是两服务收敛的既有先例。

**测试**：tts-service 6 文件（test_app 16.7KB / test_tts_policy 10KB / test_tts_engines 13.1KB / test_config / test_config_loader）、voice-service 4 文件（test_app 6.9KB / test_asr_engines 9.1KB / test_config / test_dashscope_asr_e2e）。

## 2. 热点与风险初判

- **doing/87 RUNTIME-001/002**（2026-08-11）：两服务覆盖键降级覆盖——故障期强制指定引擎，运维手段完备。
- **S-017/S-018 收敛**：ASR/SER 后端适配器（ser_engines.py:60-78 装配工厂）、TTS 位置索引收敛命名属性（tts_policy.py:40-47）。
- **风险初判**：①voice-service 错误处理无全局 handler，500 detail 泄漏 `str(e)`（与 tts-service 不一致，见 P1-1）；②COMP-009 转写即删为合规红线路径但无回归测试（见 P1-2）；③覆盖键读取器两服务复制（见 P2-1）。

## 3. 发现清单

### P0（架构级）
**未发现**。两服务均满足 BEACON #16 音频不出设备语义（音频仅在服务端瞬时处理）、DA-14 启动 fail-fast、AUD-042 文件后缀白名单；模块边界清晰（app 装配 + 引擎后端适配 + 策略编排），无分层违规。

### P1（模块级）

| 编号 | 位置 | 问题描述 | 建议方案 | 预期收益 | 删除测试判断 |
|---|---|---|---|---|---|
| P1-1 | voice-service/app.py:374-385 | **错误细节泄漏不一致**：`except Exception` 分支 500 detail 含 `str(e)`（:385），且 ASRBackendError 分支 502 detail 也含 `{e}`（:378）——上游 SDK 错误（可能含 requestId/内部地址）回显客户端；而 tts-service 有全局 handler（app.py:49-54，返回结构化 500 不泄漏内部细节，注释明示"防敏感信息回显"）。心理辅导系统红线域：两服务对外错误口径不一致，且 voice-service 泄漏面更大 | 对齐 tts-service：补 `@app.exception_handler(Exception)` 全局 handler（:382-385 分支降级为固定文案"语音分析失败"，ASRBackendError 分支改固定文案"上游语音识别服务错误"，细节仅落日志） | 一致性：两服务对外错误口径统一，消除 PII/基础设施细节回显面；leverage：与 tts-service 模式复制成本极低 | 保留：test_app.py 错误码断言适配（现断言 500/502 状态码，detail 断言需同步收窄） |
| P1-2 | voice-service/app.py:387-398（COMP-009 转写即删） | **合规关键路径无回归测试**：grep 全部 test_*.py，`unlink/删除/tmp_path/cleanup` 仅命中 config 加载与 e2e 正弦波生成（test_config.py:17-146、test_dashscope_asr_e2e.py:34-50），**无任何用例断言 finally 中临时音频删除路径**（含删除失败告警分支 :396-397）。转写即删是 COMP-009 冻结决策（音频不落盘红线），测试缺口意味着回归无感 | 补 test_app.py 用例：①成功路径断言 tmp/wav 两文件已删 + 审计日志"转写即删完成"；②`os.unlink` 抛 OSError 断言告警日志"合规告警"（monkeypatch 注入） | 一致性：红线路径回归保护落地（对照 80% 覆盖率门禁，finally 分支属关键覆盖缺口） | 新增：独立用例补充，现有 4 文件测试不动 |
| P1-3 | voice-service/app.py:374-378 | **ASRBackendError 语义与 D2 注释矛盾风险**：detail 复用 `f"DashScope ASR 服务错误: {e}"`，若未来接入非 DashScope 引擎，文案失真；且该分支与 P1-1 泄漏问题同源 | 随 P1-1 一并收敛为固定文案 + 结构化错误码（错误码区分引擎归属，文案不携带异常细节） | 一致性：错误语义与引擎解耦 | 同 P1-1 |

### P2（局部）

| 编号 | 位置 | 问题描述 | 建议方案 |
|---|---|---|---|
| P2-1 | tts_policy.py:90-99 vs voice-service/app.py:50-71 | **覆盖键读取器两处复制**：`_read_override()`（tts，无参）与 `_read_override(point)`（voice，带 asr/ser 维度）均为"Redis 直连 + fail-open + 键前缀 + 校验"同构实现，键格式/超时语义漂移风险无共享约束。py-common 已有共享先例（config_loader/metrics_common） | 收编 `py-common/degradation_override.py` 共享模块（键前缀/TTL 常量 + fail-open 读取 + 单测），两服务消费；RUNTIME-001/002 语义保持 |

## 4. 改进候选排序

- **Strong**：P1-1（voice-service 错误细节泄漏 + 全局 handler 对齐——红线域、改动 <20 行、模式现成）；P1-2（转写即删回归测试——红线路径保护、改动 <50 行）。
- **Worth exploring**：P2-1（覆盖键读取器收编 py-common——两服务 + 共享库均有测试，重构有保护网）。
- **Speculative**：P1-3 随 P1-1 顺带收敛，不单独排期。

## 5. 设计一致性核对

| 冻结决策 | 实现核对 | 结论 |
|---|---|---|
| BEACON #24：TTS 7 音色 8 方言 v4 矩阵 | tts-service config.yaml 音色矩阵 + voice_map 按引擎映射（tts_policy.py:70） | ✅ 一致 |
| doing/87 RUNTIME-001/002：降级覆盖键 fail-open | tts_policy.py:54-64（覆盖键优先 + overridden 标记）；voice-service app.py:76-96（asr/ser 双维度档位判定） | ✅ 一致 |
| COMP-009（doing/22 §6.3）：转写即删 | voice-service app.py:387-398（finally 删除 + 审计日志，删除失败走合规告警） | ✅ 实现一致（测试缺口见 P1-2） |
| DA-14：启动契约校验 fail-fast | 两服务 config 加载均经 py-common config_loader（缺键即启动失败，不静默降级） | ✅ 一致 |
| OPS-MON-002：降级事件计数指标 | tts-service 独立计数器实例（app.py:60-62，TtsDegradeRatioHigh 规则硬依赖）；voice-service 同构 | ✅ 一致 |
| DC-011：适配器层 + 降级策略 | TTSBackend 接口 + DegradationPolicy（tts_policy.py:28-32）；ASRBackend/SERBackend 接口 + 装配工厂（ser_engines.py:60-78） | ✅ 一致 |
| S-017/S-018：后端装配收敛 / 命名属性 | ser_engines.py:60-78；tts_policy.py:40-47（primary/secondary 属性） | ✅ 一致 |
| AUD-042：上传文件后缀白名单 + lifespan 校验 | voice-service app.py:243（后缀白名单）+ :108-109（超时） | ✅ 一致 |

## 6. 修复建议

- **P0**：无。
- **P1 按收益排序**：①P1-1 voice-service 全局 handler 对齐（红线域、<20 行，建议进入集中修复）；②P1-2 转写即删回归测试（红线路径保护，建议与①同批）；③P1-3 随 P1-1 顺带。
- **P2**：P2-1 覆盖键读取器收编 py-common（有共享先例 + 测试保护网，收益中等，可选）。
- **汇总引用**：P1-1 与板块09 P1-1（验证码回显）同属"红线域信息管控"主题；P2-1 属"py-common 共享库扩张"方向，可与板块06 领域数据层/部署层共享模块盘点合并评估。
