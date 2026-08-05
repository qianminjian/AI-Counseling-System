"""
TTS 配置加载测试（CFG-004 配置外置）
覆盖：
- config.yaml 存在时从中加载配置
- config.yaml 不存在时回退到内置默认值
- 环境变量覆盖 config.yaml 中的模型名
- 配置结构完整性（7 音色 / 8 方言 / 10 情感）
"""
import os
import tempfile
import pytest
import yaml


# ===== 配置加载函数测试 =====

class TestConfigLoading:
    """config.yaml 加载逻辑"""

    def test_load_config_from_file(self, tmp_path):
        """config.yaml 存在时正确加载"""
        from app import load_config

        config_file = tmp_path / "config.yaml"
        config_file.write_text(yaml.dump({
            "model": {"dashscope": "test-model", "edge_fallback": True},
            "voice_personas": {
                "test_persona": {
                    "name": "测试",
                    "desc": "测试音色",
                    "speed": 1.0,
                    "dashscope_voice": "test_voice",
                    "edge_voice": "zh-CN-TestNeural",
                    "dialect_capable": False,
                    "emotion_capable": False,
                }
            },
            "dialects": {},
            "native_dialect_voices": {},
            "emotion_instruct_map": {},
        }, allow_unicode=True))

        cfg = load_config(str(config_file))
        assert cfg["model"]["dashscope"] == "test-model"
        assert "test_persona" in cfg["voice_personas"]

    def test_load_config_missing_file_returns_defaults(self):
        """config.yaml 不存在时返回内置默认值"""
        from app import load_config

        cfg = load_config("/nonexistent/path/config.yaml")
        # 应回退到内置默认值
        assert len(cfg["voice_personas"]) == 7
        assert "xiaoxing" in cfg["voice_personas"]
        assert len(cfg["dialects"]) == 8
        assert len(cfg["emotion_instruct_map"]) == 10

    def test_env_var_overrides_model(self, tmp_path, monkeypatch):
        """环境变量 DASHSCOPE_TTS_MODEL 覆盖 config.yaml 中的模型名"""
        from app import load_config

        monkeypatch.setenv("DASHSCOPE_TTS_MODEL", "env-override-model")

        config_file = tmp_path / "config.yaml"
        config_file.write_text(yaml.dump({
            "model": {"dashscope": "yaml-model"},
        }, allow_unicode=True))

        cfg = load_config(str(config_file))
        assert cfg["model"]["dashscope"] == "env-override-model"

    def test_default_model_is_v3_flash(self):
        """默认模型为 cosyvoice-v3-flash（非 v3.5，后者已 418）"""
        from app import load_config

        cfg = load_config("/nonexistent/config.yaml")
        assert cfg["model"]["dashscope"] == "cosyvoice-v3-flash"


class TestConfigStructure:
    """配置结构完整性"""

    @pytest.fixture
    def config(self):
        from app import load_config
        return load_config("/nonexistent/config.yaml")

    def test_seven_personas(self, config):
        """默认配置包含 7 个音色"""
        assert len(config["voice_personas"]) == 7

    def test_persona_required_fields(self, config):
        """每个音色包含必要字段"""
        required = {"name", "desc", "speed", "dashscope_voice", "edge_voice",
                    "dialect_capable", "emotion_capable"}
        for key, persona in config["voice_personas"].items():
            for field in required:
                assert field in persona, f"{key} 缺 {field}"

    def test_eight_dialects(self, config):
        """默认配置包含 8 种方言"""
        assert len(config["dialects"]) == 8

    def test_dialect_has_label_and_mode(self, config):
        """每种方言有 label 和 mode"""
        for code, info in config["dialects"].items():
            assert "label" in info, f"{code} 缺 label"
            assert "mode" in info, f"{code} 缺 mode"
            assert info["mode"] in ("native", "instruct"), f"{code} mode 无效"

    def test_ten_emotions(self, config):
        """默认配置包含 10 种情感映射"""
        assert len(config["emotion_instruct_map"]) == 10

    def test_native_dialect_voices_structure(self, config):
        """原生方言音色结构正确"""
        native_voices = config["native_dialect_voices"]
        assert "cantonese" in native_voices
        assert "minnan" in native_voices
        assert "female" in native_voices["cantonese"]

    def test_edge_fallback_enabled(self, config):
        """edge-tts 降级默认开启"""
        assert config["model"]["edge_fallback"] is True
