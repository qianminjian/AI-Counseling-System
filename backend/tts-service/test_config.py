"""
TTS 配置加载测试（CFG-004 配置外置 + DOC-073 D1 深合并单源化）
覆盖：
- config.yaml 存在时从中加载配置（yaml 为权威默认矩阵单源）
- config.yaml 不存在时回退到最小兜底（服务可启动）
- 环境变量覆盖 config.yaml 中的模型名
- 配置结构完整性（7 音色 / 8 方言 / 10 情感，验证 yaml 权威源）
- partial persona 深合并（只配一个方言 → 默认矩阵保留）
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

    def test_load_config_missing_file_returns_fallback(self):
        """config.yaml 不存在时返回最小兜底（服务可启动：含直索引键的最小运行矩阵）"""
        from app import load_config

        cfg = load_config("/nonexistent/path/config.yaml")
        # DA-14 最小兜底：model 必要字段 + 直索引键矩阵（xiaoxing persona / neutral 指令）
        assert cfg["model"]["dashscope"] == "cosyvoice-v3-flash"
        assert "xiaoxing" in cfg["voice_personas"]
        assert "neutral" in cfg["emotion_instruct_map"]
        assert cfg["dialects"] == {}

    def test_fallback_runtime_contract_pass(self):
        """DA-14：兜底矩阵通过启动契约校验（synthesize 直索引键齐备）"""
        from app import load_config, _validate_runtime_contract

        cfg = load_config("/nonexistent/path/config.yaml")
        _validate_runtime_contract(cfg)  # 不应抛异常

    def test_runtime_contract_rejects_missing_xiaoxing(self):
        """DA-14：缺默认音色 xiaoxing 的配置被启动校验拒绝（不待运行即 500）"""
        from app import _validate_runtime_contract

        bad = {"voice_personas": {"bobo": {}}, "emotion_instruct_map": {"neutral": "x"}}
        with pytest.raises(RuntimeError, match="xiaoxing"):
            _validate_runtime_contract(bad)

    def test_runtime_contract_rejects_missing_neutral(self):
        """DA-14：缺 neutral 情感指令的配置被启动校验拒绝"""
        from app import _validate_runtime_contract

        bad = {"voice_personas": {"xiaoxing": {}}, "emotion_instruct_map": {}}
        with pytest.raises(RuntimeError, match="neutral"):
            _validate_runtime_contract(bad)

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

    def test_partial_persona_deep_merges(self, tmp_path):
        """partial yaml 深合并：只配一个方言 instruct → 其余默认矩阵保留（D1 验收场景）"""
        from app import load_config

        config_file = tmp_path / "config.yaml"
        config_file.write_text(yaml.dump({
            "dialects": {"sichuan": {"instruct": "请用四川话表达，带点辣椒味。"}},
        }, allow_unicode=True))

        # 模拟生产：defaults 注入完整矩阵（config.yaml 权威源），yaml 只覆盖指定项
        from config_loader import load_config as loader_load_config
        defaults = loader_load_config(
            os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.yaml"))

        cfg = load_config(str(config_file))
        merged = loader_load_config(str(config_file), defaults=defaults)

        # 兜底路径（无 defaults）：partial yaml 直接生效
        assert cfg["dialects"]["sichuan"]["instruct"] == "请用四川话表达，带点辣椒味。"
        # 深合并路径：覆盖项生效 + 权威矩阵保留
        assert merged["dialects"]["sichuan"]["instruct"] == "请用四川话表达，带点辣椒味。"
        assert merged["dialects"]["sichuan"]["label"] == "四川话"
        assert "cantonese" in merged["dialects"]
        assert "bobo" in merged["voice_personas"]
        assert len(merged["emotion_instruct_map"]) == 10

    def test_default_model_is_v3_flash(self):
        """默认模型为 cosyvoice-v3-flash（非 v3.5，后者已 418）"""
        from app import load_config

        cfg = load_config("/nonexistent/config.yaml")
        assert cfg["model"]["dashscope"] == "cosyvoice-v3-flash"


class TestConfigStructure:
    """配置结构完整性（验证 config.yaml 权威单源，D1 后矩阵不再存在于代码）"""

    @pytest.fixture
    def config(self):
        from app import load_config
        actual = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.yaml")
        return load_config(actual)

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

    def test_persona_gender_valid(self, config):
        """AUD-006：gender 字段存在时必须为 female/male（qiqiu 无明确性别可缺省）"""
        for key, persona in config["voice_personas"].items():
            if "gender" in persona:
                assert persona["gender"] in ("female", "male"), f"{key} gender 非法"
            else:
                assert key == "qiqiu", f"{key} 缺 gender（仅 qiqiu 可缺省）"

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
