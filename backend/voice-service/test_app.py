"""
voice-service /health 与 /metrics 单元测试（DA-02：就绪指标消费）
覆盖：/health 三态（UP/DEGRADED/DOWN）+ readiness 字段 + /metrics gauge 契约
说明：测试环境无 funasr 依赖，注入假模块避免真实模型加载；SER 失败用构造异常模拟
"""
import asyncio
import json
import math
import struct
import subprocess
import sys
import types
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from asr_engines import ASRBackendError


class _FakeAutoModel:
    """假 funasr.AutoModel：ASR 构造（带 vad_model）成功，SER 构造（无 vad_model）可配置失败"""

    ser_fail = False

    def __init__(self, model=None, vad_model=None, **kwargs):
        if vad_model is None and _FakeAutoModel.ser_fail:
            raise RuntimeError("SER 模型加载失败（模拟）")
        self.model = model

    def generate(self, input, **kwargs):
        return [{"scores": [0.9] + [0.0] * 8}]


@pytest.fixture(autouse=True)
def fresh_app(monkeypatch):
    """注入假 funasr 模块 + 重载 app（funasr 模式 + SER 启用，默认全部加载成功）"""
    _FakeAutoModel.ser_fail = False
    fake_funasr = types.ModuleType("funasr")
    fake_funasr.AutoModel = _FakeAutoModel
    monkeypatch.setitem(sys.modules, "funasr", fake_funasr)
    monkeypatch.setenv("ASR_ENGINE", "funasr")
    monkeypatch.setenv("SER_ENABLED", "true")
    import importlib

    import app as voice_app

    importlib.reload(voice_app)
    yield voice_app


@pytest.fixture
def client(fresh_app):
    return TestClient(fresh_app.app)


# ===== /health 就绪判定（DA-02） =====

class TestHealth:
    def test_health_up(self, client):
        """ASR + SER 均就绪 → UP"""
        resp = client.get("/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "UP"
        assert data["asr_ready"] == 1
        assert data["ser_ready"] == 1
        assert data["asr_engine"] == "funasr"

    def test_health_ser_degraded(self, client, fresh_app):
        """SER 启用但加载失败 → DEGRADED（服务可用，情绪识别降级）"""
        _FakeAutoModel.ser_fail = True
        import importlib

        importlib.reload(fresh_app)
        resp = client.get("/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "DEGRADED"
        assert data["ser_ready"] == 0
        assert data["ser_model"] == "disabled"

    def test_health_ser_disabled(self, client, fresh_app, monkeypatch):
        """SER 显式禁用 → UP（禁用≠降级，ser_ready=0 但不告警）"""
        monkeypatch.setenv("SER_ENABLED", "false")
        import importlib

        importlib.reload(fresh_app)
        resp = client.get("/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "UP"
        assert data["ser_ready"] == 0
        assert data["asr_ready"] == 1

    def test_health_asr_down(self, client, fresh_app):
        """ASR 未就绪 → DOWN（防御语义：funasr 加载失败实际在启动期崩溃）"""
        fresh_app.asr_model = None
        resp = client.get("/health")
        assert resp.status_code == 200
        assert resp.json()["status"] == "DOWN"
        assert resp.json()["asr_ready"] == 0


# ===== /metrics gauge 契约（DA-02：voice_ser_enabled 新增，告警规则依赖） =====

class TestMetrics:
    def test_gauge_contract(self, client):
        """就绪 gauge 三件套 + 请求计数必须存在（alert-rules.yml 硬依赖）"""
        resp = client.get("/metrics")
        assert resp.status_code == 200
        body = resp.text
        assert "voice_asr_ready" in body
        assert "voice_ser_ready" in body
        assert "voice_ser_enabled" in body
        assert "voice_analyze_requests_total" in body
        assert "voice_analyze_duration_seconds" in body

    def test_ser_enabled_label(self, client, fresh_app, monkeypatch):
        """SER_ENABLED=false → voice_ser_enabled 0（告警表达式排除显式禁用）"""
        monkeypatch.setenv("SER_ENABLED", "false")
        import importlib

        importlib.reload(fresh_app)
        resp = client.get("/metrics")
        assert resp.status_code == 200
        assert "voice_ser_enabled 0" in resp.text

    def test_ser_enabled_default_on(self, client):
        """默认 SER_ENABLED=true → voice_ser_enabled 1"""
        resp = client.get("/metrics")
        assert "voice_ser_enabled 1" in resp.text


# ===== doing/87 RUNTIME-002（2026-08-11）：请求时覆盖档位判定 =====

class TestOverrideResolution:
    """AC-4/5/6/7：覆盖键 → ASR/SER 档位判定（fresh_app 提供已装配模块，monkeypatch _read_override）"""

    def test_asr_override_dashscope(self, fresh_app, monkeypatch):
        """AC-4：覆盖 asr=dashscope → 档位 dashscope（环境变量 funasr 时也切换）"""
        monkeypatch.setattr(fresh_app, "_read_override", lambda point: "dashscope" if point == "asr" else None)
        assert fresh_app._resolve_asr_engine() == "dashscope"

    def test_asr_override_funasr_model_not_loaded_rejected(self, fresh_app, monkeypatch):
        """AC-6：覆盖 asr=funasr 但模型未加载（asr_model=None）→ 拒绝切换保持当前档位"""
        monkeypatch.setattr(fresh_app, "_read_override", lambda point: "funasr" if point == "asr" else None)
        monkeypatch.setattr(fresh_app, "asr_model", None)
        monkeypatch.setattr(fresh_app, "ASR_ENGINE", "dashscope")
        assert fresh_app._resolve_asr_engine() == "dashscope"  # 保持当前，不 500

    def test_ser_override_disabled(self, fresh_app, monkeypatch):
        """AC-5：覆盖 ser=disabled → SER 关闭"""
        monkeypatch.setattr(fresh_app, "_read_override", lambda point: "disabled" if point == "ser" else None)
        assert fresh_app._resolve_ser_enabled() is False

    def test_ser_override_enabled(self, fresh_app, monkeypatch):
        """覆盖 ser=enabled → SER 开启（即使环境变量禁用）"""
        monkeypatch.setattr(fresh_app, "_read_override", lambda point: "enabled" if point == "ser" else None)
        monkeypatch.setattr(fresh_app, "SER_ENABLED", False)
        assert fresh_app._resolve_ser_enabled() is True

    def test_no_override_falls_back_to_env(self, fresh_app, monkeypatch):
        """AC-7：键缺失 → 环境变量档位（fail-open）"""
        monkeypatch.setattr(fresh_app, "_read_override", lambda point: None)
        monkeypatch.setattr(fresh_app, "ASR_ENGINE", "funasr")
        monkeypatch.setattr(fresh_app, "SER_ENABLED", True)
        assert fresh_app._resolve_asr_engine() == "funasr"
        assert fresh_app._resolve_ser_enabled() is True

    def test_override_reader_fail_open(self, fresh_app, monkeypatch):
        """AC-7：Redis 不可达（reader 内部捕获）→ 返回 None → 环境变量档位"""
        def broken(point):
            raise RuntimeError("redis down")
        monkeypatch.setattr(fresh_app, "_read_override", broken)
        monkeypatch.setattr(fresh_app, "ASR_ENGINE", "dashscope")
        assert fresh_app._resolve_asr_engine() == "dashscope"


# ===== 板块10 P1-1/P1-3：错误细节防泄漏（错误口径对齐 tts-service 全局 handler） =====

def _sine_wav_bytes() -> bytes:
    """合成 1 秒 440Hz 正弦波 WAV（16kHz 16-bit mono），供 ffmpeg 转码链路使用"""
    sample_rate = 16000
    duration_secs = 1
    num_samples = sample_rate * duration_secs
    data_size = num_samples * 2
    header = struct.pack(
        '<4sI4s4sIHHIIHH4sI',
        b'RIFF', 36 + data_size, b'WAVE',
        b'fmt ', 16, 1, 1, sample_rate, sample_rate * 2, 2, 16,
        b'data', data_size,
    )
    samples = b''.join(
        struct.pack('<h', int(16000 * math.sin(2 * math.pi * 440 * i / sample_rate)))
        for i in range(num_samples)
    )
    return header + samples


class _FailingASRBackend:
    """模拟 ASR 后端抛错：异常携带敏感细节（requestId/内部地址/密钥），断言不回显客户端"""

    def __init__(self, exc):
        self._exc = exc

    def transcribe(self, path):
        raise self._exc


class TestAnalyzeErrorHandling:
    """错误口径收敛（板块10 P1-1/P1-3）：500/502 detail 固定文案，异常细节仅落日志"""

    @pytest.fixture
    def upload_ok(self, fresh_app, monkeypatch):
        """伪造 ffmpeg 转码成功（写合法 wav），跳过真实 ffmpeg/网络依赖，直达 ASR 调用链"""
        def fake_run(cmd, **kwargs):
            with open(cmd[-1], "wb") as f:
                f.write(_sine_wav_bytes())
            return subprocess.CompletedProcess(cmd, 0)
        monkeypatch.setattr(fresh_app.subprocess, "run", fake_run)
        return fresh_app

    def test_asr_backend_error_returns_fixed_502(self, client, upload_ok, monkeypatch):
        """ASRBackendError（含 requestId/内部地址）→ 502 固定文案，detail 不携带异常细节"""
        # 强制 SER 关闭走串行路径（聚焦错误口径；并行路径由 TestSerParallelPath 单独覆盖）
        monkeypatch.setattr(upload_ok, "_resolve_ser_enabled", lambda: False)
        monkeypatch.setattr(upload_ok, "_resolve_asr_backend",
                            lambda engine: _FailingASRBackend(
                                ASRBackendError("requestId=r-abc123, host=10.0.0.8")))
        resp = client.post("/api/v1/voice/analyze",
                           files={"file": ("a.wav", _sine_wav_bytes(), "audio/wav")})
        assert resp.status_code == 502
        assert resp.json()["detail"] == "上游语音识别服务错误"
        assert "requestId" not in resp.text
        assert "10.0.0.8" not in resp.text

    def test_unexpected_exception_returns_fixed_500(self, client, upload_ok, monkeypatch):
        """未预期异常（含疑似密钥）→ 500 固定文案，detail 不携带 str(e)"""
        monkeypatch.setattr(upload_ok, "_resolve_ser_enabled", lambda: False)
        monkeypatch.setattr(upload_ok, "_resolve_asr_backend",
                            lambda engine: _FailingASRBackend(RuntimeError("内部密钥: sk-xxx")))
        resp = client.post("/api/v1/voice/analyze",
                           files={"file": ("a.wav", _sine_wav_bytes(), "audio/wav")})
        assert resp.status_code == 500
        assert resp.json()["detail"] == "语音分析失败"
        assert "sk-xxx" not in resp.text
        assert "RuntimeError" not in resp.text

    def test_global_handler_returns_fixed_message(self, fresh_app):
        """全局兑底 handler：未捕获异常 → 结构化 500 固定文案，异常细节仅落日志（对齐 tts-service）"""
        request = SimpleNamespace(method="POST", url=SimpleNamespace(path="/api/v1/voice/analyze"))
        resp = asyncio.run(fresh_app._unhandled_exception_handler(
            request, RuntimeError("内部密钥: sk-xxx")))
        assert resp.status_code == 500
        assert json.loads(resp.body)["detail"] == "语音分析失败"
        assert "sk-xxx" not in resp.body.decode()

    def test_http_exception_preserved(self, client):
        """显式 HTTPException（400）不被全局 handler 吞掉（状态码语义不变）"""
        # 用非音频 content-type 触发真实 400 校验（audio/* 会放行进入 ffmpeg 转码，本机无 ffmpeg 会误入 500）
        resp = client.post("/api/v1/voice/analyze",
                           files={"file": ("a.txt", b"hello", "text/plain")})
        assert resp.status_code == 400
        assert resp.json()["detail"] == "仅支持音频文件"


# ===== 板块10 范围外既有 bug 修复：SER 并行路径（_funasr_ser 无定义 → ser_backend.analyze） =====

class _OkASRBackend:
    """成功 ASR 后端：transcribe 返回固定文本"""

    def __init__(self, text="今天心情不错"):
        self._text = text

    def transcribe(self, path):
        return self._text


class _ReadyChain:
    """成功链路共用 fixture：fake ffmpeg 转码（写合法 wav）+ fake ASR 后端（返回固定文本）"""

    @pytest.fixture
    def ready(self, fresh_app, monkeypatch):
        def fake_run(cmd, **kwargs):
            with open(cmd[-1], "wb") as f:
                f.write(_sine_wav_bytes())
            return subprocess.CompletedProcess(cmd, 0)
        monkeypatch.setattr(fresh_app.subprocess, "run", fake_run)
        monkeypatch.setattr(fresh_app, "_resolve_asr_backend", lambda engine: _OkASRBackend())
        return fresh_app


class TestSerParallelPath(_ReadyChain):
    """S-017 收敛后 SER 并行路径回归：ser_on=True 时不再因 _funasr_ser 无定义 NameError，
    SER 结果经 ser_backend.analyze 返回并组装进响应"""

    def test_ser_parallel_analyze_succeeds(self, ready, client):
        """SER 并行路径：ASR+SER 并行执行成功 → 200，emotion 来自 ser_backend.analyze（scores 首类 0.9）"""
        # fresh_app 环境 SER_ENABLED=true + fake funasr 模型可加载 → ser_backend 可用 → 走并行分支
        resp = client.post("/api/v1/voice/analyze",
                           files={"file": ("a.wav", _sine_wav_bytes(), "audio/wav")})
        assert resp.status_code == 200
        data = resp.json()
        assert data["text"] == "今天心情不错"
        # fake funasr generate 返回 [{"scores": [0.9] + [0.0]*8}] → argmax=0 → emotion_labels[0]=angry/愤怒
        assert data["emotion"]["label_en"] == "angry"
        assert data["emotion"]["label"] == "愤怒"
        assert data["emotion"]["confidence"] == 0.9
        assert len(data["emotion"]["scores"]) == 9


# ===== 板块10 P1-2：COMP-009 转写即删回归测试（红线路径保护） =====

class TestDeleteAfterTranscribe(_ReadyChain):
    """COMP-009（doing/22 §6.3）转写即删回归：
    ①成功路径：finally 删除 tmp/wav 两临时文件 + 审计日志"转写即删完成"；
    ②删除失败（os.unlink 抛 OSError）→ 合规告警日志，不影响请求结果"""

    def test_success_deletes_both_temp_files_and_audit_log(self, ready, client, caplog, monkeypatch):
        """成功路径：tmp 与 wav 两临时文件均被 unlink（跟踪调用），磁盘确实无残留，审计日志含"转写即删完成"""
        unlinked = []
        real_unlink = ready.os.unlink

        def tracking_unlink(path):
            unlinked.append(path)
            return real_unlink(path)

        monkeypatch.setattr(ready.os, "unlink", tracking_unlink)
        with caplog.at_level("INFO", logger="voice-service"):
            resp = client.post("/api/v1/voice/analyze",
                               files={"file": ("a.wav", _sine_wav_bytes(), "audio/wav")})
        assert resp.status_code == 200
        # 两个临时文件（原始后缀 + .wav）都被删除且磁盘上确实不存在
        assert len(unlinked) == 2
        for p in unlinked:
            assert not ready.os.path.exists(p), f"临时文件未删除: {p}"
        assert any("转写即删完成" in r.message and "2 个" in r.message for r in caplog.records)

    def test_unlink_failure_logs_compliance_warning(self, ready, client, caplog, monkeypatch):
        """os.unlink 抛 OSError → 合规告警日志（红线：删除失败不静默），请求结果不受影响"""
        def failing_unlink(path):
            raise OSError(f"permission denied: {path}")

        monkeypatch.setattr(ready.os, "unlink", failing_unlink)
        with caplog.at_level("ERROR", logger="voice-service"):
            resp = client.post("/api/v1/voice/analyze",
                               files={"file": ("a.wav", _sine_wav_bytes(), "audio/wav")})
        assert resp.status_code == 200  # 删除失败仅告警，不改变请求结果
        assert any("合规告警" in r.message and "删除失败" in r.message for r in caplog.records)
