"""
voice-service /health 与 /metrics 单元测试（DA-02：就绪指标消费）
覆盖：/health 三态（UP/DEGRADED/DOWN）+ readiness 字段 + /metrics gauge 契约
说明：测试环境无 funasr 依赖，注入假模块避免真实模型加载；SER 失败用构造异常模拟
"""
import sys
import types

import pytest
from fastapi.testclient import TestClient


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
