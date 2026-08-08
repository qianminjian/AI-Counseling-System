"""
D2 ASR 引擎适配器层（参照 tts-service DC-011 TTSBackend seam 模式）
- ASRBackend：引擎统一接口（可用性探测 + 转写 wav → text）
- DashScopeASRBackend：阿里云 DashScope Paraformer-V2（云端）
- FunASRBackend：本地 FunASR SenseVoiceSmall

纯函数（可单测，不依赖 SDK）：
- parse_dashscope_sentences(result)：提取句子文本拼接（dict/list/None 归一）
- map_dashscope_error(result)：status_code != 200 时返回错误消息，否则 None
- clean_sensevoice_text(text)：清洗 SenseVoice <|...|> 特殊标记

错误语义（与端点层 5xx 映射对齐）：
- 上游云端服务失败抛 ASRBackendError → app.py 映射 502
- 本地 funasr 推理失败不包装（保持原语义）→ app.py 通用异常映射 500
"""
import re
from abc import ABC, abstractmethod
from typing import Optional

__all__ = [
    "ASRBackend",
    "ASRBackendError",
    "DashScopeASRBackend",
    "FunASRBackend",
    "parse_dashscope_sentences",
    "map_dashscope_error",
    "clean_sensevoice_text",
]

# ===== 纯函数（无 SDK 依赖，单测主战场） =====


def parse_dashscope_sentences(result) -> str:
    """提取 DashScope Recognition 结果的所有句子文本并拼接（dict/list/None 归一）"""
    sentences = result.get_sentence() if hasattr(result, "get_sentence") else []
    if isinstance(sentences, dict):
        sentences = [sentences]
    return "".join(s.get("text", "") for s in (sentences or [])).strip()


def map_dashscope_error(result) -> Optional[str]:
    """status_code != 200 时返回可读错误消息（code/message 缺省兜底），否则 None"""
    if result.status_code == 200:
        return None
    code = getattr(result, "code", "?")
    message = getattr(result, "message", "?")
    return f"status={result.status_code}, code={code}, msg={message}"


def clean_sensevoice_text(text: str) -> str:
    """清洗 SenseVoice 特殊标记（<|...|> 事件/情绪标签）"""
    return re.sub(r"<\|[^|]*\|>", "", text).strip()


# ===== SDK 懒加载（未安装时不影响纯函数与 funasr 模式） =====
# 注意：Recognition/RecognitionCallback 必须从 dashscope.audio.asr 子模块导入——
# 真实包顶层不暴露这两个类（实测 2026-08-08，CodeReview High-1），经 sdk.Recognition 访问必抛 AttributeError
_dashscope = None
_Recognition = None
_RecognitionCallback = None
try:
    import dashscope as _dashscope  # noqa: F401
    from dashscope.audio.asr import Recognition as _Recognition  # noqa: F401
    from dashscope.audio.asr import RecognitionCallback as _RecognitionCallback  # noqa: F401
except ImportError:
    _dashscope = None


# ===== 引擎统一接口 =====


class ASRBackendError(Exception):
    """上游云端 ASR 服务错误（端点层映射 502）"""


class ASRBackend(ABC):
    """引擎统一接口：探测 + 转写（wav 路径 → 文本，失败抛异常）"""

    name: str = ""

    @abstractmethod
    def is_available(self) -> bool:
        """引擎可用性探测（API Key / 模型就绪）"""

    @abstractmethod
    def transcribe(self, wav_path: str) -> str:
        """转写 wav 文件为文本；云端失败抛 ASRBackendError"""


class DashScopeASRBackend(ASRBackend):
    """DashScope Paraformer-V2 适配器（同步 call() 模式）"""

    name = "dashscope"

    def __init__(self, model: str, api_key: str, sdk=None):
        self._model = model
        self._api_key = api_key
        self._sdk = sdk if sdk is not None else _dashscope

    def _get_sdk(self):
        return self._sdk if self._sdk is not None else _dashscope

    def is_available(self) -> bool:
        return bool(self._api_key) and self._get_sdk() is not None

    def transcribe(self, wav_path: str) -> str:
        sdk = self._get_sdk()
        if sdk is None or _Recognition is None:
            raise ASRBackendError("dashscope SDK 未安装")
        if self._api_key:
            # seam 自包含：显式注入 api_key（不依赖 app.py 模块级全局赋值）
            sdk.api_key = self._api_key
        recognition = _Recognition(
            model=self._model,
            format="wav",
            sample_rate=16000,
            callback=_RecognitionCallback(),  # no-op callback, call() 模式同步返回
        )
        result = recognition.call(file=wav_path)
        error = map_dashscope_error(result)
        if error:
            raise ASRBackendError(f"DashScope ASR 服务错误: {error}")
        return parse_dashscope_sentences(result)


class FunASRBackend(ASRBackend):
    """本地 FunASR SenseVoiceSmall 适配器（model 为 app.py 模块级加载的 AutoModel 实例）"""

    name = "funasr"

    def __init__(self, model, language: str = "zh", use_itn: bool = True):
        self._model = model
        self._language = language
        self._use_itn = use_itn

    def is_available(self) -> bool:
        return self._model is not None

    def transcribe(self, wav_path: str) -> str:
        result = self._model.generate(
            input=wav_path,
            language=self._language,
            use_itn=self._use_itn,
        )
        text = ""
        if result and len(result) > 0:
            text = result[0].get("text", "")
        # 清洗 SenseVoice 特殊标记
        return clean_sensevoice_text(text)
