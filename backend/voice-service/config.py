"""
MindSafe Voice Service 配置加载模块（CFG-007）
独立于 app.py 的重量级依赖（torch/funasr/soundfile），便于单元测试。

优先级：config.yaml > 内置默认值
注意：ASR_ENGINE / SER_ENABLED / DASHSCOPE_API_KEY 仍由环境变量控制（12-Factor）
"""
import copy
import logging
import os

import yaml

logger = logging.getLogger("voice-service")

# ===== 内置默认配置（config.yaml 缺失时的兜底值） =====

DEFAULT_CONFIG = {
    "asr": {
        "funasr_model": "iic/SenseVoiceSmall",
        "vad_model": "fsmn-vad",
        "dashscope_model": "paraformer-realtime-v2",
    },
    "ser": {
        "model": "iic/emotion2vec_plus_large",
    },
    "emotion_labels": [
        ["angry", "愤怒"],
        ["disgusted", "厌恶"],
        ["fearful", "恐惧"],
        ["happy", "开心"],
        ["neutral", "中性"],
        ["other", "其他"],
        ["sad", "悲伤"],
        ["surprised", "惊讶"],
        ["unknown", "未知"],
    ],
}


def load_config(config_path: str = None) -> dict:
    """
    加载 Voice 服务配置（CFG-007）
    优先级：config.yaml > 内置默认值
    注意：ASR_ENGINE / SER_ENABLED / DASHSCOPE_API_KEY 仍由环境变量控制（12-Factor）
    """
    config = copy.deepcopy(DEFAULT_CONFIG)

    if config_path is None:
        config_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.yaml")

    if os.path.isfile(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                file_cfg = yaml.safe_load(f) or {}
            for key, value in file_cfg.items():
                if isinstance(value, dict) and key in config and isinstance(config[key], dict):
                    config[key].update(value)
                else:
                    config[key] = value
            logger.info("✅ 配置已从 %s 加载", config_path)
        except Exception as e:
            logger.warning("配置加载失败，使用内置默认值: %s", e)

    return config
