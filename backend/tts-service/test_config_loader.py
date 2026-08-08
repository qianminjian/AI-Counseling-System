"""
config_loader 深合并契约测试（DOC-073 D1，doing/77 §23）
覆盖：
- 深合并语义：dict 递归合并（override 优先）、list/标量整体替换
- partial persona：只配一个方言 instruct → 默认矩阵保留 + 仅覆盖指定项
- yaml 缺失/损坏 → 回退代码默认值
"""
import pytest
import yaml

from config_loader import deep_merge, load_config

# 测试用完整默认矩阵（模拟生产 config.yaml 权威源）
FULL_MATRIX = {
    "voice_personas": {
        "bobo": {"name": "波波老师", "speed": 0.95, "dashscope_voice": "longyingling_v3", "edge_voice": "zh-CN-XiaohanNeural"},
        "qiqiu": {"name": "方言", "speed": 1.05, "dashscope_voice": "longanhuan_v3", "edge_voice": "zh-CN-XiaoyiNeural", "dialect_capable": True},
    },
    "dialects": {
        "sichuan": {"label": "四川话", "mode": "instruct", "instruct": "请用四川话表达。", "edge_voice": None},
        "henan": {"label": "河南话", "mode": "instruct", "instruct": "请用河南话表达。", "edge_voice": None},
    },
    "emotion_instruct_map": {
        "neutral": "你正在闲聊，情感是neutral。",
        "happy": "你正在闲聊，情感是happy。",
    },
}


class TestDeepMerge:
    """深合并语义"""

    def test_partial_persona_keeps_default_matrix(self):
        """只配一个方言 instruct → 默认矩阵保留，仅覆盖指定项（浅合并缺陷修复）"""
        base = deep_merge({}, FULL_MATRIX)
        partial = {"dialects": {"sichuan": {"instruct": "请用四川话表达，带点辣椒味。"}}}

        merged = deep_merge(base, partial)

        # 覆盖项生效
        assert merged["dialects"]["sichuan"]["instruct"] == "请用四川话表达，带点辣椒味。"
        # 同层未覆盖字段保留
        assert merged["dialects"]["sichuan"]["label"] == "四川话"
        assert merged["dialects"]["sichuan"]["mode"] == "instruct"
        # 其他方言保留（默认矩阵不丢）
        assert "henan" in merged["dialects"]
        # 其他顶层区块保留
        assert "bobo" in merged["voice_personas"]
        assert merged["emotion_instruct_map"]["happy"] == "你正在闲聊，情感是happy。"

    def test_deep_nested_persona_partial(self):
        """三层嵌套 partial（voice_personas → persona → 单字段）"""
        base = deep_merge({}, FULL_MATRIX)

        merged = deep_merge(base, {"voice_personas": {"qiqiu": {"speed": 1.2}}})

        assert merged["voice_personas"]["qiqiu"]["speed"] == 1.2
        assert merged["voice_personas"]["qiqiu"]["name"] == "方言"  # 未覆盖字段保留
        assert merged["voice_personas"]["bobo"]["speed"] == 0.95  # 其他 persona 不受影响

    def test_list_replaced_wholesale(self):
        """list 整体替换（非合并）"""
        base = {"emotion_labels": [["angry", "愤怒"], ["happy", "开心"]]}

        merged = deep_merge(base, {"emotion_labels": [["sad", "悲伤"]]})

        assert merged["emotion_labels"] == [["sad", "悲伤"]]

    def test_scalar_replaced(self):
        """标量整体替换"""
        merged = deep_merge({"model": {"dashscope": "a", "edge_fallback": True}},
                            {"model": {"dashscope": "b"}})

        assert merged["model"]["dashscope"] == "b"
        assert merged["model"]["edge_fallback"] is True

    def test_merge_does_not_mutate_inputs(self):
        """深合并不修改输入 dict（deepcopy 隔离）"""
        base = {"dialects": {"sichuan": {"instruct": "原值"}}}

        deep_merge(base, {"dialects": {"sichuan": {"instruct": "新值"}}})

        assert base["dialects"]["sichuan"]["instruct"] == "原值"

    def test_empty_dict_keeps_defaults(self):
        """空 dict 视为无变更（清空语义未定义，保守保留默认矩阵）"""
        base = deep_merge({}, FULL_MATRIX)

        merged = deep_merge(base, {"dialects": {}})

        assert merged["dialects"] == base["dialects"]

    def test_none_value_replaces_wholesale(self):
        """None 值整体替换（显式置空字段）"""
        merged = deep_merge(
            {"dialects": {"sichuan": {"edge_voice": "zh-CN-XiaohanNeural"}}},
            {"dialects": {"sichuan": {"edge_voice": None}}},
        )

        assert merged["dialects"]["sichuan"]["edge_voice"] is None


class TestLoadConfig:
    """load_config 加载与回退"""

    def test_yaml_missing_returns_defaults(self, tmp_path):
        """yaml 缺失 → 回退代码默认值"""
        cfg = load_config(str(tmp_path / "nonexistent.yaml"), defaults=FULL_MATRIX)

        assert cfg["voice_personas"]["bobo"]["name"] == "波波老师"
        assert len(cfg["dialects"]) == 2

    def test_yaml_corrupt_returns_defaults(self, tmp_path):
        """yaml 损坏 → 回退代码默认值"""
        config_file = tmp_path / "config.yaml"
        config_file.write_text("{{{{invalid yaml")

        cfg = load_config(str(config_file), defaults=FULL_MATRIX)

        assert len(cfg["dialects"]) == 2

    def test_yaml_partial_merges_into_defaults(self, tmp_path):
        """partial yaml 深合并进 defaults（验收场景：只配一个方言）"""
        config_file = tmp_path / "config.yaml"
        config_file.write_text(yaml.dump({
            "dialects": {"henan": {"instruct": "请用河南话表达，中不中？"}},
        }, allow_unicode=True))

        cfg = load_config(str(config_file), defaults=FULL_MATRIX)

        # 覆盖项生效 + 默认矩阵完整保留
        assert cfg["dialects"]["henan"]["instruct"] == "请用河南话表达，中不中？"
        assert cfg["dialects"]["henan"]["label"] == "河南话"
        assert "sichuan" in cfg["dialects"]
        assert "bobo" in cfg["voice_personas"]
        assert len(cfg["emotion_instruct_map"]) == 2

    def test_yaml_override_wins(self, tmp_path):
        """yaml 值优先于 defaults"""
        config_file = tmp_path / "config.yaml"
        config_file.write_text(yaml.dump({
            "voice_personas": {"bobo": {"name": "波波（新名）"}},
        }, allow_unicode=True))

        cfg = load_config(str(config_file), defaults=FULL_MATRIX)

        assert cfg["voice_personas"]["bobo"]["name"] == "波波（新名）"
        assert cfg["voice_personas"]["bobo"]["speed"] == 0.95
