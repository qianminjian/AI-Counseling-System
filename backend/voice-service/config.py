"""
MindSafe Voice Service 配置加载模块（CFG-007 + DOC-073 D1 深合并单源化）
独立于 app.py 的重量级依赖（torch/funasr/soundfile），便于单元测试。

优先级：config.yaml > 内置默认值（深合并：嵌套结构部分配置仅覆盖指定项）
注意：ASR_ENGINE / SER_ENABLED / DASHSCOPE_API_KEY 仍由环境变量控制（12-Factor）
"""
import logging

from config_loader import load_config as loader_load_config

logger = logging.getLogger("voice-service")

# ===== 内置默认配置（config.yaml 缺失时的兜底值） =====
# DOC-073 D1（doing/77 §23）：矩阵类数据（emotion_labels）以 config.yaml 为权威单源，
# 代码兜底仅保留运行必需字段（asr/ser 模型名，与 tts model 兜底对称）；
# 避免双源漂移（改 yaml 后代码兜底静默过期）。
# DA-14：emotion_labels 为 parse_emotion_result 直索引键（EMOTION_LABELS[max_idx]），
# 空矩阵时“可启动但运行即 500”——兜底补 9 类最小矩阵（与 config.yaml 一致）。

DEFAULT_CONFIG = {
    "asr": {
        "funasr_model": "iic/SenseVoiceSmall",
        "vad_model": "fsmn-vad",
        "dashscope_model": "paraformer-realtime-v2",
    },
    "ser": {
        "model": "iic/emotion2vec_plus_large",
    },
    # 9 类情绪标签矩阵：权威在 config.yaml，缺失时兜底为最小运行矩阵
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
    加载 Voice 服务配置（CFG-007 + DOC-073 D1）
    优先级：config.yaml > 内置默认值（深合并：嵌套结构部分配置仅覆盖指定项）
    注意：ASR_ENGINE / SER_ENABLED / DASHSCOPE_API_KEY 仍由环境变量控制（12-Factor）
    """
    return loader_load_config(config_path, defaults=DEFAULT_CONFIG)
