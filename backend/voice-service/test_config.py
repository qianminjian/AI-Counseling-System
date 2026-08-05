"""
Voice 服务配置加载测试（CFG-007 配置外置）
覆盖：
- config.yaml 存在时从中加载
- config.yaml 不存在时回退内置默认值
- 配置结构完整性（9 类情绪标签 / ASR 模型名 / SER 模型名）
"""
import os
import pytest
import yaml


class TestVoiceConfigLoading:
    """config.yaml 加载逻辑"""

    def test_load_config_from_file(self, tmp_path):
        """config.yaml 存在时正确加载"""
        from config import load_config

        config_file = tmp_path / "config.yaml"
        config_file.write_text(yaml.dump({
            "asr": {
                "funasr_model": "test/SenseVoice",
                "dashscope_model": "test-paraformer",
            },
            "ser": {
                "model": "test/emotion2vec",
            },
            "emotion_labels": [
                ["angry", "愤怒"],
                ["happy", "开心"],
            ],
        }, allow_unicode=True))

        cfg = load_config(str(config_file))
        assert cfg["asr"]["funasr_model"] == "test/SenseVoice"
        assert cfg["asr"]["dashscope_model"] == "test-paraformer"
        assert cfg["ser"]["model"] == "test/emotion2vec"
        assert len(cfg["emotion_labels"]) == 2

    def test_load_config_missing_file_returns_defaults(self):
        """config.yaml 不存在时返回内置默认值"""
        from config import load_config

        cfg = load_config("/nonexistent/path/config.yaml")
        assert cfg["asr"]["funasr_model"] == "iic/SenseVoiceSmall"
        assert cfg["asr"]["dashscope_model"] == "paraformer-realtime-v2"
        assert cfg["ser"]["model"] == "iic/emotion2vec_plus_large"
        assert len(cfg["emotion_labels"]) == 9

    def test_default_emotion_labels_structure(self):
        """默认情绪标签包含 9 类，每项为 (en, cn) 二元组"""
        from config import load_config

        cfg = load_config("/nonexistent/config.yaml")
        labels = cfg["emotion_labels"]
        assert len(labels) == 9
        for item in labels:
            assert len(item) == 2
            assert isinstance(item[0], str)  # 英文标签
            assert isinstance(item[1], str)  # 中文标签

    def test_default_asr_vad_model(self):
        """默认 VAD 模型为 fsmn-vad"""
        from config import load_config

        cfg = load_config("/nonexistent/config.yaml")
        assert cfg["asr"]["vad_model"] == "fsmn-vad"

    def test_partial_config_merges_with_defaults(self, tmp_path):
        """部分配置仅覆盖指定字段，其余保留默认值"""
        from config import load_config

        config_file = tmp_path / "config.yaml"
        config_file.write_text(yaml.dump({
            "asr": {"funasr_model": "custom/Model"},
        }, allow_unicode=True))

        cfg = load_config(str(config_file))
        # 覆盖的字段
        assert cfg["asr"]["funasr_model"] == "custom/Model"
        # 未覆盖的字段保留默认值
        assert cfg["asr"]["vad_model"] == "fsmn-vad"
        assert cfg["asr"]["dashscope_model"] == "paraformer-realtime-v2"
        assert cfg["ser"]["model"] == "iic/emotion2vec_plus_large"
        assert len(cfg["emotion_labels"]) == 9

    def test_corrupt_yaml_falls_back_to_defaults(self, tmp_path):
        """损坏的 YAML 文件降级为内置默认值"""
        from config import load_config

        config_file = tmp_path / "config.yaml"
        config_file.write_text("{{{{invalid yaml content::")

        cfg = load_config(str(config_file))
        assert cfg["asr"]["funasr_model"] == "iic/SenseVoiceSmall"
        assert cfg["ser"]["model"] == "iic/emotion2vec_plus_large"
        assert len(cfg["emotion_labels"]) == 9

    def test_actual_config_yaml_loads(self):
        """集成测试：实际 config.yaml 文件可正确加载"""
        from config import load_config

        actual_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.yaml")
        if not os.path.isfile(actual_path):
            pytest.skip("config.yaml 不存在")

        cfg = load_config(actual_path)
        assert cfg["asr"]["funasr_model"] == "iic/SenseVoiceSmall"
        assert cfg["asr"]["vad_model"] == "fsmn-vad"
        assert cfg["asr"]["dashscope_model"] == "paraformer-realtime-v2"
        assert cfg["ser"]["model"] == "iic/emotion2vec_plus_large"
        assert len(cfg["emotion_labels"]) == 9

    def test_empty_yaml_returns_defaults(self, tmp_path):
        """空 YAML 文件返回默认值"""
        from config import load_config

        config_file = tmp_path / "config.yaml"
        config_file.write_text("")

        cfg = load_config(str(config_file))
        assert cfg["asr"]["funasr_model"] == "iic/SenseVoiceSmall"
        assert len(cfg["emotion_labels"]) == 9
