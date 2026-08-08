"""
Voice 服务配置加载测试（CFG-007 配置外置 + DOC-073 D1 深合并）
覆盖：
- config.yaml 存在时从中加载
- config.yaml 不存在时回退内置默认值
- 配置结构完整性（ASR 模型名 / SER 模型名；emotion_labels 矩阵权威在 config.yaml）
- 深合并语义（嵌套 partial 覆盖 / list 整体替换）
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
        # DA-14：矩阵兑底为最小运行矩阵（parse_emotion_result 直索引键，空矩阵运行即 500）
        assert len(cfg["emotion_labels"]) == 9

    def test_default_emotion_labels_fallback_minimal(self):
        """DA-14：缺失回退时 emotion_labels 为 9 类最小运行矩阵（权威仍在 config.yaml）"""
        from config import load_config

        cfg = load_config("/nonexistent/config.yaml")
        labels = cfg["emotion_labels"]
        assert len(labels) == 9
        assert [en for en, _ in labels] == [
            "angry", "disgusted", "fearful", "happy", "neutral",
            "other", "sad", "surprised", "unknown"]

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
        assert len(cfg["emotion_labels"]) == 9  # partial yaml 未覆盖矩阵 → 保留兑底最小矩阵

    def test_nested_partial_merges_with_defaults(self, tmp_path):
        """嵌套 partial：只配 asr.funasr_model → 其余默认字段保留（浅合并缺陷修复）"""
        from config import load_config

        config_file = tmp_path / "config.yaml"
        config_file.write_text(yaml.dump({
            "asr": {"funasr_model": "custom/Model"},
        }, allow_unicode=True))

        cfg = load_config(str(config_file))
        assert cfg["asr"]["funasr_model"] == "custom/Model"
        assert cfg["asr"]["vad_model"] == "fsmn-vad"
        assert cfg["asr"]["dashscope_model"] == "paraformer-realtime-v2"

    def test_list_replaced_wholesale(self, tmp_path):
        """emotion_labels 为 list → 整体替换（非合并）"""
        from config import load_config

        config_file = tmp_path / "config.yaml"
        config_file.write_text(yaml.dump({
            "emotion_labels": [["custom", "自定义"]],
        }, allow_unicode=True))

        cfg = load_config(str(config_file))
        assert cfg["emotion_labels"] == [["custom", "自定义"]]

    def test_corrupt_yaml_falls_back_to_defaults(self, tmp_path):
        """损坏的 YAML 文件降级为内置默认值"""
        from config import load_config

        config_file = tmp_path / "config.yaml"
        config_file.write_text("{{{{invalid yaml content::")

        cfg = load_config(str(config_file))
        assert cfg["asr"]["funasr_model"] == "iic/SenseVoiceSmall"
        assert cfg["ser"]["model"] == "iic/emotion2vec_plus_large"
        assert len(cfg["emotion_labels"]) == 9  # DA-14：损坏 yaml 回退兑底最小矩阵

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
        assert len(cfg["emotion_labels"]) == 9  # DA-14：空 yaml 回退兑底最小矩阵
