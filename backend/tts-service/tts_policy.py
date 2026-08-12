"""
DC-011 音色降级策略（真实接缝的编排：可用性探测 → 降级顺序 → 重试）
- DegradationPolicy：按声明顺序尝试引擎，失败记日志并降级下一个；全失败抛 TTSSynthesisFailed
- Instruct 重试：引擎声明 retry_without_instruction=True 时，带指令失败先无指令重试一次
  （现状语义：CosyVoice Instruct 失败 → 无指令重试保音色品质 → 再失败才降级）
"""
import logging
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional

from tts_engines import TTSBackend

__all__ = ["DegradationPolicy", "TTSSynthesisFailed", "TtsResult"]


class TTSSynthesisFailed(Exception):
    """全部引擎均失败（端点映射为 503）"""


@dataclass
class TtsResult:
    audio: bytes
    engine: str
    retried: bool = False  # True=Instruct 失败后无指令重试成功（指标区分 cosyvoice_fallback）
    overridden: bool = False  # True=覆盖键生效（doing/87 RUNTIME-001：覆盖目标不可用走兜底时标记）


class DegradationPolicy:
    """按声明顺序尝试引擎，失败降级；全失败抛 TTSSynthesisFailed
    doing/87 RUNTIME-001（2026-08-11）：override_reader 注入覆盖键读取（Redis），
    优先级 = 覆盖键 → 配置顺序 → 可用性降级；覆盖目标不可用走兜底并标记 overridden。"""

    def __init__(self, backends: List[TTSBackend], log=None,
                 override_reader: Optional[Callable[[], Optional[str]]] = None):
        self.backends = list(backends)
        self._log = log if callable(log) else logging.getLogger("tts-policy").warning
        self._override_reader = override_reader

    @property
    def primary(self) -> TTSBackend:
        """首选引擎（装配顺序首位；S-018：位置索引收敛为命名属性，加引擎/换序消费点零改动）"""
        return self.backends[0]

    @property
    def secondary(self) -> Optional[TTSBackend]:
        """备用引擎（装配顺序次位；无备用时返回 None）"""
        return self.backends[1] if len(self.backends) > 1 else None

    async def synthesize_with_degradation(self, text: str, voice_id: str, speed: float,
                                          instruction: Optional[str] = None,
                                          pitch: float = 1.0,
                                          voice_map: Optional[Dict[str, str]] = None) -> TtsResult:
        errors = []
        # RUNTIME-001：覆盖键优先——目标可用强制使用；不可用走兜底链并标记 overridden
        override = self._read_override()
        fallback_due_override = False
        if override:
            target = next((b for b in self.backends if b.name == override), None)
            if target is not None and target.is_available():
                return await self._synthesize_single(target, text, voice_id, speed, instruction, pitch,
                                                     voice_map, errors, overridden=True)
            if target is not None:
                self._log(f"覆盖引擎 {override} 不可用，overridden-fallback 走降级链")
                fallback_due_override = True
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
                        overridden=fallback_due_override,
                    )
                except Exception as e:
                    errors.append(f"{backend.name}: {e}")
                    self._log(f"{backend.name} 失败: {e}")
        raise TTSSynthesisFailed("; ".join(errors) if errors else "无可用引擎")

    def _read_override(self) -> Optional[str]:
        """读取覆盖键（fail-open：Redis 不可达/异常返回 None，按配置默认运行）"""
        if self._override_reader is None:
            return None
        try:
            value = self._override_reader()
            return value if value in {b.name for b in self.backends} else None
        except Exception as e:
            self._log(f"覆盖键读取失败（fail-open）: {e}")
            return None

    async def _synthesize_single(self, backend: TTSBackend, text: str, voice_id: str, speed: float,
                                 instruction: Optional[str], pitch: float,
                                 voice_map: Optional[Dict[str, str]], errors: List[str],
                                 overridden: bool) -> TtsResult:
        """单引擎合成（覆盖路径专用：无 instruct 重试，直接走引擎能力）"""
        engine_voice = (voice_map or {}).get(backend.name, voice_id)
        engine_instruction = instruction if backend.supports_instruction else None
        try:
            audio = await backend.synthesize(text, engine_voice, speed, engine_instruction, pitch)
            return TtsResult(audio=audio, engine=backend.name, overridden=overridden)
        except Exception as e:
            errors.append(f"{backend.name}: {e}")
            self._log(f"{backend.name} 失败: {e}")
            raise TTSSynthesisFailed("; ".join(errors))
