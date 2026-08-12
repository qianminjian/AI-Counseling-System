"""
MindSafe Voice Service 配置加载模块（CFG-007 + DOC-073 D1 深合并单源化）
独立于 app.py 的重量级依赖（torch/funasr/soundfile），便于单元测试。

优先级：config.yaml > 内置默认值（深合并：嵌套结构部分配置仅覆盖指定项）
注意：ASR_ENGINE / SER_ENABLED / DASHSCOPE_API_KEY 仍由环境变量控制（12-Factor）
"""
import logging
import os

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
    config_path 默认取本服务目录 config.yaml（S-019 收编 config_loader 至 py-common 后，
    共享模块默认路径指向 py-common/config.yaml 不存在——必须显式传服务自身 yaml，
    否则静默回退兜底矩阵，config.yaml 定制模型名/标签丢失，板块10 收编回归）
    """
    if config_path is None:
        config_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.yaml")
    return loader_load_config(config_path, defaults=DEFAULT_CONFIG)
