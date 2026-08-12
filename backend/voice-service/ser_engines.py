"""SER（语音情绪识别）后端抽象（S-017，doing/93）。

与 ASRBackend 同构——加载/就绪/推理三职责收敛到后端对象，app.py 只做装配：
- is_available()：就绪探测（模型加载失败 → False，调用方降级中性情绪）
- analyze(wav_path)：推理返回 dict（label/label_en/confidence/scores），由 app 层组装响应模型
此前 emotion_model 生命周期散在 app.py 四处（加载/就绪/health/metrics 各写一遍），
加云端 SER 引擎需重抄整条内联路径；本抽象后新增引擎 = 新后端类 + 装配工厂分支。
"""
from abc import ABC, abstractmethod

import numpy as np


class SERBackendError(Exception):
    """SER 推理错误（调用方按降级中性情绪处理）"""


class SERBackend(ABC):
    """SER 后端接口（与 ASRBackend 同构：可用性探测 + 推理）"""

    @abstractmethod
    def is_available(self) -> bool:
        """模型就绪判定（加载失败/禁用 → False）"""

    @abstractmethod
    def analyze(self, wav_path: str) -> dict:
        """情感推理：返回 {label, label_en, confidence, scores}"""


class FunasrSERBackend(SERBackend):
    """emotion2vec+ 本地 SER（模型实例由装配工厂加载注入，生命周期单点）"""

    def __init__(self, model, emotion_labels):
        self._model = model
        self._emotion_labels = emotion_labels

    def is_available(self) -> bool:
        return self._model is not None

    def analyze(self, wav_path: str) -> dict:
        if not self.is_available():
            raise SERBackendError("SER 模型不可用")
        result = self._model.generate(input=wav_path)
        if result and len(result) > 0:
            return self._parse(result[0])
        return {"label": "未知", "label_en": "unknown", "confidence": 0.0, "scores": [0.0] * len(self._emotion_labels)}

    def _parse(self, raw: dict) -> dict:
        scores = [float(s) for s in raw.get("scores", [0.0] * len(self._emotion_labels))]
        max_idx = int(np.argmax(scores))
        label_en, label_cn = self._emotion_labels[max_idx]
        return {
            "label": label_cn,
            "label_en": label_en,
            "confidence": scores[max_idx],
            "scores": scores,
        }


def load_ser_backend(config: dict, enabled: bool, asr_engine: str):
    """SER 后端装配工厂（S-017）：模型加载 + 失败降级——app.py 只调用本工厂，不再内联生命周期。

    :param config: 全量配置（含 ser.model / emotion_labels）
    :param enabled: SER_ENABLED 档位（显式禁用 → 返回 None，不触发降级告警）
    :param asr_engine: ASR 引擎（非 funasr 时也需 funasr 包加载 emotion2vec）
    :return: SERBackend 或 None（禁用/加载失败）
    """
    if not enabled:
        return None
    try:
        # emotion2vec+ 经 funasr 包加载（与 ASR 引擎无关；云端 ASR 模式同样依赖 funasr 包）
        from funasr import AutoModel
        model = AutoModel(model=config["ser"]["model"], device="cpu")
        labels = [tuple(item) for item in config["emotion_labels"]]
        return FunasrSERBackend(model, labels)
    except Exception:
        # 降级为中性情绪：调用方经 is_available()==False 处理（DA-02：仅"启用但加载失败"触发告警）
        return None
