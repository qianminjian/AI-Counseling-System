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

    def test_dialect_capable_only_qiqiu(self, mock_dashscope):
        """仅 qiqiu 拥有 dialect_capable=True，其他均为 False"""
        for key, cfg in mock_dashscope.VOICE_PERSONAS.items():
            assert "dialect_capable" in cfg, f"{key} 缺 dialect_capable"
            if key == "qiqiu":
                assert cfg["dialect_capable"] is True
            else:
                assert cfg["dialect_capable"] is False

    def test_emotion_capable_only_xiaotaiyang(self, mock_dashscope):
        """仅 xiaotaiyang 拥有 emotion_capable=True"""
        for key, cfg in mock_dashscope.VOICE_PERSONAS.items():
            assert "emotion_capable" in cfg, f"{key} 缺 emotion_capable"
            if key == "xiaotaiyang":
                assert cfg["emotion_capable"] is True
            else:
                assert cfg["emotion_capable"] is False

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
        """instruct 模式方言的 Instruct 指令格式正确"""
        for code, info in mock_dashscope.SUPPORTED_DIALECTS.items():
            assert "label" in info, f"{code} 缺 label"
            assert "mode" in info, f"{code} 缺 mode"
            # 仅 instruct 模式方言需要有 instruct 字段
            if info["mode"] == "instruct":
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

class TestBuildInstruction:
    """指令构建（方言 + 情感 + 原生方言音色）"""

    def test_dialect_instruct_for_qiqiu(self, mock_dashscope):
        """方言音色 qiqiu + 方言 → 返回 Instruct"""
        cfg = mock_dashscope.VOICE_PERSONAS["qiqiu"]
        instruction, voice = mock_dashscope.build_instruction(cfg, "sichuan", "neutral")
        assert instruction == "请用四川话表达。"
        assert voice is None

    def test_dialect_ignored_for_non_capable(self, mock_dashscope):
        """非方言音色 + 方言 → 无 Instruct"""
        cfg = mock_dashscope.VOICE_PERSONAS["xiaoxing"]
        instruction, voice = mock_dashscope.build_instruction(cfg, "sichuan", "neutral")
        assert instruction is None
        assert voice is None

    def test_emotion_instruct_for_xiaotaiyang(self, mock_dashscope):
        """小太阳 + 情感 → 情感 Instruct"""
        cfg = mock_dashscope.VOICE_PERSONAS["xiaotaiyang"]
        instruction, voice = mock_dashscope.build_instruction(cfg, None, "happy")
        assert instruction == "你正在进行闲聊互动，你说话的情感是happy。"
        assert voice is None

    def test_native_dialect_voice_override(self, mock_dashscope):
        """原生方言音色 + language_mode=dialect → 覆盖 voice"""
        cfg = mock_dashscope.VOICE_PERSONAS["qiqiu"]
        instruction, voice = mock_dashscope.build_instruction(
            cfg, "cantonese", "neutral", language_mode="dialect", persona_gender="female"
        )
        assert instruction is None  # 原生音色无需 instruction
        assert voice == "longjiayi_v3"  # 粤语女声

    def test_native_dialect_voice_male(self, mock_dashscope):
        """原生方言音色男声匹配（粤语男声）"""
        cfg = mock_dashscope.VOICE_PERSONAS["qiqiu"]
        instruction, voice = mock_dashscope.build_instruction(
            cfg, "cantonese", "neutral", persona_gender="male"
        )
        assert instruction is None  # 原生音色无需 instruction
        assert voice == "longanyue_v3"  # 粤语男声

    def test_persona_gender_default_none(self, mock_dashscope):
        """AUD-006：persona_gender 默认值为 None（不硬编码女声）"""
        cfg = mock_dashscope.VOICE_PERSONAS["qiqiu"]
        # 不传 persona_gender → 按 native_dialect_voices 首项兜底（粤语 female=longjiayi_v3）
        instruction, voice = mock_dashscope.build_instruction(cfg, "cantonese", "neutral")
        assert instruction is None
        assert voice == "longjiayi_v3"  # 兜底到首项，而非代码层默认女声

    def test_synthesize_passes_persona_gender(self, client, mock_dashscope, monkeypatch):
        """AUD-006：synthesize 按 persona 配置传 gender，不再硬编码 female"""
        captured = {}

        def fake_build_instruction(persona_cfg, dialect, emotion, **kwargs):
            captured["persona_gender"] = kwargs.get("persona_gender")
            return None, None

        monkeypatch.setattr(mock_dashscope, "build_instruction", fake_build_instruction)
        resp = client.post("/api/v1/tts/synthesize", json={"text": "你好", "persona": "dashu"})
        assert captured.get("persona_gender") == "male"  # dashu 配置 gender=male
        assert resp.status_code != 422

    def test_no_dialect_no_emotion(self, mock_dashscope):
        """无方言无情感 → 无指令"""
        cfg = mock_dashscope.VOICE_PERSONAS["xiaoxing"]
        instruction, voice = mock_dashscope.build_instruction(cfg, None, "neutral")
        assert instruction is None
        assert voice is None


# ===== Health 端点 =====

class TestHealth:
    def test_health_no_engine(self, client):
        """无 API Key 时状态为 DEGRADED"""
        resp = client.get("/health")
        assert resp.status_code == 200
        # 测试环境无 DASHSCOPE_API_KEY，应为 DEGRADED 或依赖 edge-tts
        assert resp.json()["status"] in ("UP", "DEGRADED")
