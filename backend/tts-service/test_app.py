"""
TTS 微服务单元测试（TDD 先行）
覆盖：7 音色配置 + dialect 参数 + Instruct 逻辑 + API 端点
"""
import pytest
from unittest.mock import patch, MagicMock, AsyncMock
from fastapi.testclient import TestClient


# ===== Fixtures =====

@pytest.fixture(autouse=True)
def mock_dashscope():
    """Mock dashscope SDK（测试不依赖真实 API Key）"""
    with patch.dict("os.environ", {"DASHSCOPE_API_KEY": ""}):
        # 重新加载 app 模块以触发环境变量读取
        import importlib
        import app as tts_app
        importlib.reload(tts_app)
        yield tts_app


@pytest.fixture
def client(mock_dashscope):
    return TestClient(mock_dashscope.app)


# ===== 音色配置测试 =====

class TestVoicePersonas:
    """7 音色矩阵配置验证"""

    def test_seven_personas_configured(self, mock_dashscope):
        """应有 7 个音色人设"""
        assert len(mock_dashscope.VOICE_PERSONAS) == 7

    def test_persona_ids(self, mock_dashscope):
        """音色 ID 列表完整"""
        expected = {"xiaoxing", "bobo", "yueliang", "xiaotaiyang", "dashu", "doudou", "qiqiu"}
        assert set(mock_dashscope.VOICE_PERSONAS.keys()) == expected

    def test_each_persona_has_required_fields(self, mock_dashscope):
        """每个音色必须有 name/desc/speed/dashscope_voice/edge_voice"""
        for key, cfg in mock_dashscope.VOICE_PERSONAS.items():
            assert "name" in cfg, f"{key} 缺 name"
            assert "desc" in cfg, f"{key} 缺 desc"
            assert "speed" in cfg, f"{key} 缺 speed"
            assert "dashscope_voice" in cfg, f"{key} 缺 dashscope_voice"
            assert "edge_voice" in cfg, f"{key} 缺 edge_voice"

    def test_new_personas_voice_mapping(self, mock_dashscope):
        """新增音色的 CosyVoice voice 参数正确"""
        assert mock_dashscope.VOICE_PERSONAS["bobo"]["dashscope_voice"] == "longyingling_v3"
        assert mock_dashscope.VOICE_PERSONAS["dashu"]["dashscope_voice"] == "longanyun_v3"
        assert mock_dashscope.VOICE_PERSONAS["doudou"]["dashscope_voice"] == "longjielidou_v3"

    def test_no_dialect_capable_field(self, mock_dashscope):
        """方言为独立维度，无 dialect_capable 字段"""
        for key, cfg in mock_dashscope.VOICE_PERSONAS.items():
            assert "dialect_capable" not in cfg, f"{key} 不应有 dialect_capable"

    def test_speed_values_reasonable(self, mock_dashscope):
        """语速在合理范围 [0.8, 1.2]"""
        for key, cfg in mock_dashscope.VOICE_PERSONAS.items():
            assert 0.8 <= cfg["speed"] <= 1.2, f"{key} speed={cfg['speed']} 超出范围"


# ===== 方言配置测试 =====

class TestDialectConfig:
    """方言枚举与 Instruct 映射"""

    def test_supported_dialects_defined(self, mock_dashscope):
        """应定义 8 种方言"""
        assert len(mock_dashscope.SUPPORTED_DIALECTS) == 8

    def test_dialect_instruct_format(self, mock_dashscope):
        """每种方言的 Instruct 指令格式正确"""
        for code, info in mock_dashscope.SUPPORTED_DIALECTS.items():
            assert "label" in info, f"{code} 缺 label"
            assert "instruct" in info, f"{code} 缺 instruct"
            assert info["instruct"].startswith("请用"), f"{code} instruct 格式错误"
            assert info["instruct"].endswith("表达。"), f"{code} instruct 格式错误"

    def test_dialect_edge_fallback(self, mock_dashscope):
        """东北/陕西有 edge-tts 降级音色，其他为 None"""
        assert mock_dashscope.SUPPORTED_DIALECTS["northeastern"]["edge_voice"] is not None
        assert mock_dashscope.SUPPORTED_DIALECTS["shaanxi"]["edge_voice"] is not None
        assert mock_dashscope.SUPPORTED_DIALECTS["sichuan"]["edge_voice"] is None
        assert mock_dashscope.SUPPORTED_DIALECTS["cantonese"]["edge_voice"] is None


# ===== API 端点测试 =====

class TestPersonasEndpoint:
    """/api/v1/tts/personas 端点"""

    def test_returns_7_personas(self, client):
        resp = client.get("/api/v1/tts/personas")
        assert resp.status_code == 200
        data = resp.json()
        assert data["success"] is True
        assert len(data["data"]) == 7

    def test_persona_has_id_name_desc(self, client):
        resp = client.get("/api/v1/tts/personas")
        for p in resp.json()["data"]:
            assert "id" in p
            assert "name" in p
            assert "desc" in p

    def test_no_dialect_capable_in_response(self, client):
        """personas 接口不返回 dialect_capable 字段"""
        resp = client.get("/api/v1/tts/personas")
        for p in resp.json()["data"]:
            assert "dialect_capable" not in p, f"{p['id']} 不应返回 dialect_capable"


class TestSynthesizeEndpoint:
    """/api/v1/tts/synthesize 端点参数校验"""

    def test_empty_text_returns_400(self, client):
        resp = client.post("/api/v1/tts/synthesize", json={"text": "", "persona": "xiaoxing"})
        assert resp.status_code == 400

    def test_text_too_long_returns_400(self, client):
        resp = client.post("/api/v1/tts/synthesize", json={"text": "啊" * 501, "persona": "xiaoxing"})
        assert resp.status_code == 400

    def test_dialect_field_accepted(self, client):
        """请求体接受 dialect 字段（不报 422）"""
        # 由于无 TTS 引擎可用会返回 503，但不应是 422 参数校验错误
        resp = client.post("/api/v1/tts/synthesize", json={
            "text": "你好",
            "persona": "qiqiu",
            "dialect": "sichuan"
        })
        assert resp.status_code != 422

    def test_invalid_dialect_still_accepted(self, client):
        """无效 dialect 值不报错（忽略即可）"""
        resp = client.post("/api/v1/tts/synthesize", json={
            "text": "你好",
            "persona": "qiqiu",
            "dialect": "invalid_dialect"
        })
        assert resp.status_code != 422


# ===== Instruct 构建逻辑测试 =====

class TestDialectInstruct:
    """方言 Instruct 指令构建"""

    def test_build_instruct_for_valid_dialect(self, mock_dashscope):
        """有效方言 → 返回 Instruct 字符串"""
        result = mock_dashscope.build_dialect_instruct("qiqiu", "sichuan")
        assert result == "请用四川话表达。"

    def test_build_instruct_for_any_persona(self, mock_dashscope):
        """方言为独立维度，任意音色均可获取 Instruct"""
        result = mock_dashscope.build_dialect_instruct("xiaoxing", "sichuan")
        assert result == "请用四川话表达。"

    def test_build_instruct_for_invalid_dialect(self, mock_dashscope):
        """无效方言代码 → 返回 None"""
        result = mock_dashscope.build_dialect_instruct("qiqiu", "klingon")
        assert result is None

    def test_build_instruct_for_none_dialect(self, mock_dashscope):
        """dialect 为 None → 返回 None"""
        result = mock_dashscope.build_dialect_instruct("qiqiu", None)
        assert result is None


# ===== Health 端点 =====

class TestHealth:
    def test_health_no_engine(self, client):
        """无 API Key 时状态为 DEGRADED"""
        resp = client.get("/health")
        assert resp.status_code == 200
        # 测试环境无 DASHSCOPE_API_KEY，应为 DEGRADED 或依赖 edge-tts
        assert resp.json()["status"] in ("UP", "DEGRADED")
