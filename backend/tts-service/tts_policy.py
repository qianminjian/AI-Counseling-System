"""
DC-011 音色降级策略（真实接缝的编排：可用性探测 → 降级顺序 → 重试）
- DegradationPolicy：按声明顺序尝试引擎，失败记日志并降级下一个；全失败抛 TTSSynthesisFailed
- Instruct 重试：引擎声明 retry_without_instruction=True 时，带指令失败先无指令重试一次
  （现状语义：CosyVoice Instruct 失败 → 无指令重试保音色品质 → 再失败才降级）
"""
import logging
from dataclasses import dataclass
from typing import Dict, List, Optional

from tts_engines import TTSBackend

__all__ = ["DegradationPolicy", "TTSSynthesisFailed", "TtsResult"]


class TTSSynthesisFailed(Exception):
    """全部引擎均失败（端点映射为 503）"""


@dataclass
class TtsResult:
    audio: bytes
    engine: str
    retried: bool = False  # True=Instruct 失败后无指令重试成功（指标区分 cosyvoice_fallback）


class DegradationPolicy:
    """按声明顺序尝试引擎，失败降级；全失败抛 TTSSynthesisFailed"""

    def __init__(self, backends: List[TTSBackend], log=None):
        self.backends = list(backends)
        self._log = log if callable(log) else logging.getLogger("tts-policy").warning

    async def synthesize_with_degradation(self, text: str, voice_id: str, speed: float,
                                          instruction: Optional[str] = None,
                                          pitch: float = 1.0,
                                          voice_map: Optional[Dict[str, str]] = None) -> TtsResult:
        errors = []
        for backend in self.backends:
            if not backend.is_available():
                self._log(f"{backend.name} 不可用，跳过")
                continue
            # 按引擎选择音色（dashscope/edge 音色不同；voice_map 未覆盖时用默认 voice_id）
            engine_voice = (voice_map or {}).get(backend.name, voice_id)
            # instruction 按引擎能力传递（仅 supports_instruction 引擎收到，如 DashScope）
            engine_instruction = instruction if backend.supports_instruction else None
            candidates = [engine_instruction]
            if engine_instruction and getattr(backend, "retry_without_instruction", False):
                candidates.append(None)
            for attempt_instr in candidates:
                try:
                    audio = await backend.synthesize(text, engine_voice, speed, attempt_instr, pitch)
                    return TtsResult(
                        audio=audio,
                        engine=backend.name,
                        retried=(attempt_instr is None and instruction is not None),
                    )
                except Exception as e:
                    errors.append(f"{backend.name}: {e}")
                    self._log(f"{backend.name} 失败: {e}")
        raise TTSSynthesisFailed("; ".join(errors) if errors else "无可用引擎")
