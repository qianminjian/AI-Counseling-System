"""
DC-011 音色引擎适配器层（真实接缝：CosyVoice SDK / edge-tts 双引擎 + 前端第三级降级）
- TTSBackend：引擎统一接口（可用性探测 + 异步合成，返回 bytes）
- DashScopeBackend：阿里云百炼 CosyVoice（DashScope SDK WebSocket 流式）
- EdgeBackend：edge-tts（微软备用）

线程语义（对比 voice-service AUD-016 进程级单例池）：
- 现状每请求新建 threading.Thread(daemon=True)，wait_for 超时后 SDK 线程悬挂仍无界；
- 收敛为共享有界 daemon 线程池（并发上限 2，超限排队），超时后结果丢弃，
  且 daemon 线程不阻塞进程退出（SDK 挂死极端场景）。
"""
import asyncio
import concurrent.futures
import io
import itertools
import threading
from abc import ABC, abstractmethod
from typing import Optional

__all__ = ["TTSBackend", "TTSBackendError", "DashScopeBackend", "EdgeBackend"]


class TTSBackendError(Exception):
    """引擎合成失败（策略层捕获后降级到下一引擎）"""


class TTSBackend(ABC):
    """引擎统一接口：探测 + 异步合成（返回 mp3 bytes，失败抛 TTSBackendError）"""

    name: str = ""
    # 是否支持 Instruct 指令（仅 DashScope 支持；策略层据此决定是否传 instruction）
    supports_instruction: bool = False

    @abstractmethod
    def is_available(self) -> bool:
        """引擎可用性探测（API Key / 依赖就绪）"""

    @abstractmethod
    async def synthesize(self, text: str, voice_id: str, speed: float,
                         instruction: Optional[str] = None, pitch: float = 1.0) -> bytes:
        """合成文本为音频字节；失败抛 TTSBackendError"""


class _BoundedDaemonExecutor:
    """有界 daemon 线程池：并发上限 max_workers、超限排队；线程 daemon 不阻塞进程退出"""

    def __init__(self, max_workers: int = 2, name_prefix: str = "tts"):
        self._sem = threading.BoundedSemaphore(max_workers)
        self._counter = itertools.count(1)
        self._name_prefix = name_prefix

    def submit(self, fn, *args):
        future: concurrent.futures.Future = concurrent.futures.Future()

        def _runner():
            try:
                result = fn(*args)
            except BaseException as exc:  # 线程边界：异常转 future
                try:
                    future.set_exception(exc)
                except concurrent.futures.InvalidStateError:
                    pass  # future 已被超时取消/丢弃
            else:
                try:
                    future.set_result(result)
                except concurrent.futures.InvalidStateError:
                    pass
            finally:
                self._sem.release()

        if not self._sem.acquire(timeout=10.0):
            future.set_exception(TTSBackendError("合成并发超限（线程池已满，排队超时）"))
            return future
        threading.Thread(
            target=_runner,
            name=f"{self._name_prefix}-{next(self._counter)}",
            daemon=True,
        ).start()
        return future


# ===== Level 1：阿里云百炼 CosyVoice（DashScope SDK WebSocket 流式） =====

_dashscope = None
try:
    import dashscope as _dashscope  # noqa: F401
    from dashscope.audio.tts_v2 import (  # noqa: F401
        AudioFormat,
        ResultCallback,
        SpeechSynthesizer,
    )
except ImportError:
    _dashscope = None


class DashScopeBackend(TTSBackend):
    """CosyVoice 适配器：SDK 阻塞 call() 在共享 daemon 线程池执行，超时丢弃结果"""

    name = "cosyvoice"
    supports_instruction = True

    def __init__(self, model: str, api_key: str, timeout: float,
                 retry_without_instruction: bool = True, sdk=None):
        self._model = model
        self._api_key = api_key
        self._timeout = timeout
        # Instruct 失败 → 无指令重试一次（保音色品质，现状语义；策略层读取该标志）
        self.retry_without_instruction = retry_without_instruction
        self._sdk = sdk if sdk is not None else _dashscope
        self._executor = _BoundedDaemonExecutor(max_workers=2, name_prefix="tts-dashscope")

    def _get_sdk(self):
        return self._sdk if self._sdk is not None else _dashscope

    def is_available(self) -> bool:
        return bool(self._api_key) and self._get_sdk() is not None

    async def synthesize(self, text: str, voice_id: str, speed: float,
                         instruction: Optional[str] = None, pitch: float = 1.0) -> bytes:
        if not self.is_available():
            raise TTSBackendError("DashScope 不可用（API Key 缺失或 SDK 未安装）")
        sdk = self._get_sdk()
        sdk.api_key = self._api_key
        future = self._executor.submit(self._synthesize_blocking, text, voice_id, speed, instruction)
        try:
            return await asyncio.wait_for(asyncio.wrap_future(future), timeout=self._timeout)
        except asyncio.TimeoutError:
            raise TTSBackendError(f"CosyVoice 合成超时 ({self._timeout}s, voice={voice_id})") from None

    def _synthesize_blocking(self, text: str, voice_id: str, speed: float,
                             instruction: Optional[str]) -> bytes:
        """SDK call() 阻塞调用（线程内）；回调经线程安全 queue 收集音频"""
        from queue import Queue

        sdk = self._get_sdk()
        if sdk is None:
            raise TTSBackendError("dashscope SDK 未安装")
        q: Queue = Queue()
        errors = []

        class _Callback(sdk.ResultCallback):
            def on_data(self, data: bytes):
                q.put(("data", data))

            def on_complete(self):
                q.put(("end", None))

            def on_error(self, message):
                errors.append(str(message))
                q.put(("end", None))

            def on_close(self):
                q.put(("end", None))

        try:
            kwargs = dict(
                model=self._model,
                voice=voice_id,
                format=sdk.AudioFormat.MP3_22050HZ_MONO_256KBPS,
                speech_rate=max(0.5, min(2.0, speed)),
                callback=_Callback(),
            )
            if instruction:
                kwargs["instruction"] = instruction
            sdk.SpeechSynthesizer(**kwargs).call(text)
        except Exception as e:  # SDK call() 抛错（可能无任何回调事件）
            errors.append(str(e))
            q.put(("end", None))  # 解除收集循环死等

        chunks = []
        while True:
            kind, payload = q.get()
            if kind == "end":
                break
            chunks.append(payload)
        if errors:
            raise TTSBackendError(f"CosyVoice SDK 错误 (voice={voice_id}): {errors[0]}")
        audio = b"".join(chunks)
        if not audio:
            raise TTSBackendError(f"CosyVoice 返回空音频 (voice={voice_id})")
        return audio


# ===== Level 2：edge-tts（微软免费 TTS，备用） =====


class EdgeBackend(TTSBackend):
    """edge-tts 适配器：Communicate.stream() 异步流，wait_for 超时降级"""

    name = "edge_tts"

    def __init__(self, timeout: float):
        self._timeout = timeout

    def _get_edge(self):
        try:
            import edge_tts
            return edge_tts
        except ImportError:
            return None

    def is_available(self) -> bool:
        return self._get_edge() is not None

    async def synthesize(self, text: str, voice_id: str, speed: float,
                         instruction: Optional[str] = None, pitch: float = 1.0) -> bytes:
        edge = self._get_edge()
        if edge is None:
            raise TTSBackendError("edge-tts 未安装")
        rate_pct = int(round((speed - 1.0) * 100))  # round 消除浮点误差（1.2 → 20 而非 19）
        rate_str = f"+{rate_pct}%" if rate_pct >= 0 else f"{rate_pct}%"
        pitch_hz = int(round((pitch - 1.0) * 100))
        pitch_str = f"+{pitch_hz}Hz" if pitch_hz >= 0 else f"{pitch_hz}Hz"
        buffer = io.BytesIO()
        communicate = edge.Communicate(text, voice_id, rate=rate_str, pitch=pitch_str)

        async def _stream():
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    buffer.write(chunk["data"])

        try:
            await asyncio.wait_for(_stream(), timeout=self._timeout)
        except asyncio.TimeoutError:
            raise TTSBackendError(f"edge-tts 合成超时 ({self._timeout}s, voice={voice_id})") from None
        except Exception as e:
            raise TTSBackendError(f"edge-tts 合成失败 (voice={voice_id}): {e}") from e
        if buffer.tell() == 0:
            raise TTSBackendError("edge-tts 返回音频为空")
        return buffer.getvalue()
