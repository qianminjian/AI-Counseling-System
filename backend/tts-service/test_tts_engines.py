"""
DC-011 音色引擎适配器测试（mock SDK / fake 模块注入，无真实网络与 API Key）

覆盖：
   DashScopeBackend：
     1. 有 key + SDK 就绪 → is_available=True；无 key → False
     2. 合成成功 → bytes（含 SDK 回调多 chunk 顺序）
     3. SDK 回调 on_error → TTSBackendError
     4. SDK call() 抛异常 → TTSBackendError
     5. 返回空音频 → TTSBackendError
     6. 超时（SDK 挂死 + 短 timeout）→ TTSBackendError，不无限等待
     7. speech_rate 被夹取到 [0.5, 2.0]
   EdgeBackend：
     8. 合成成功（fake edge 模块）→ bytes
     9. 网络异常（stream 抛错）→ TTSBackendError
     10. 空音频 → TTSBackendError
     11. 超时 → TTSBackendError
     12. rate/pitch 字符串格式正确
"""
import asyncio
import io
import time
import types

import pytest

from tts_engines import DashScopeBackend, EdgeBackend, TTSBackendError


def run(coro):
    return asyncio.run(coro)


# ===== fake DashScope SDK =====

class FakeResultCallback:
    """模拟 dashscope.audio.tts_v2.ResultCallback 基类"""

    def __init__(self):
        self.calls = []


class FakeSynthesizer:
    """可编程 SpeechSynthesizer：subclass 覆写 call() 实现 success/error/empty/slow 模式"""

    def __init__(self, **kwargs):
        self.kwargs = kwargs
        self.call_count = 0

    def call(self, text):
        self.call_count += 1
        cb = self.kwargs["callback"]
        cb.on_data(b"chunk1")
        cb.on_data(b"chunk2")
        cb.on_complete()


class _ErrorSynth(FakeSynthesizer):
    """SDK 回调 on_error 模式"""

    def call(self, text):
        self.kwargs["callback"].on_error("fake-sdk-error")


class _EmptySynth(FakeSynthesizer):
    """SDK 成功回调但无音频数据"""

    def call(self, text):
        self.kwargs["callback"].on_complete()


class _SlowSynth(FakeSynthesizer):
    """SDK 挂死模拟（超时测试用，daemon 线程可丢弃）"""

    def call(self, text):
        time.sleep(0.3)
        cb = self.kwargs["callback"]
        cb.on_data(b"late")
        cb.on_complete()


class FakeDashScopeSDK:
    api_key = None

    class AudioFormat:
        MP3_22050HZ_MONO_256KBPS = "MP3_22050HZ_MONO_256KBPS"

    class ResultCallback(FakeResultCallback):
        pass

    SpeechSynthesizer = FakeSynthesizer


def make_backend(sdk=None, timeout=5.0, **kwargs):
    return DashScopeBackend(
        model="cosyvoice-v3-flash",
        api_key="test-key",
        timeout=timeout,
        sdk=sdk or FakeDashScopeSDK,
        **kwargs,
    )


class TestDashScopeBackend:
    def test_is_available_with_key_and_sdk(self):
        assert make_backend().is_available() is True

    def test_is_available_false_without_key(self):
        backend = make_backend()
        backend._api_key = ""
        assert backend.is_available() is False

    def test_synthesize_success_returns_bytes_in_order(self):
        backend = make_backend()
        audio = run(backend.synthesize("你好", "longxing_v3", 1.0))
        assert audio == b"chunk1chunk2"

    def test_synthesize_passes_model_voice_format_and_speed(self):
        captured = {}
        sdk = FakeDashScopeSDK

        class _CaptureSynth(FakeSynthesizer):
            def __init__(self, **kwargs):
                super().__init__(**kwargs)
                captured.update(kwargs)

        sdk.SpeechSynthesizer = _CaptureSynth
        backend = make_backend(sdk=sdk)
        run(backend.synthesize("你好", "longxing_v3", 1.0))
        assert captured["model"] == "cosyvoice-v3-flash"
        assert captured["voice"] == "longxing_v3"
        # BUG-TTS-01 修复后：format 传 tts_v2 导入的 AudioFormat 枚举（.value 是 tuple，用 .name 比较）
        assert getattr(captured["format"], "name", captured["format"]) == "MP3_22050HZ_MONO_256KBPS"
        assert captured["speech_rate"] == 1.0

    def test_speech_rate_clamped(self):
        captured = {}
        sdk = FakeDashScopeSDK

        class _CaptureSynth(FakeSynthesizer):
            def __init__(self, **kwargs):
                super().__init__(**kwargs)
                captured.update(kwargs)

        sdk.SpeechSynthesizer = _CaptureSynth
        backend = make_backend(sdk=sdk)
        run(backend.synthesize("你好", "v", 5.0))
        assert captured["speech_rate"] == 2.0
        run(backend.synthesize("你好", "v", 0.1))
        assert captured["speech_rate"] == 0.5

    def test_synthesize_passes_instruction(self):
        captured = {}
        sdk = FakeDashScopeSDK

        class _CaptureSynth(FakeSynthesizer):
            def __init__(self, **kwargs):
                super().__init__(**kwargs)
                captured.update(kwargs)

        sdk.SpeechSynthesizer = _CaptureSynth
        backend = make_backend(sdk=sdk)
        run(backend.synthesize("你好", "v", 1.0, instruction="请用东北话表达。"))
        assert captured["instruction"] == "请用东北话表达。"

    def test_sdk_error_raises_tts_backend_error(self):
        sdk = FakeDashScopeSDK
        sdk.SpeechSynthesizer = _ErrorSynth
        backend = make_backend(sdk=sdk)
        with pytest.raises(TTSBackendError) as exc:
            run(backend.synthesize("你好", "v", 1.0))
        assert "fake-sdk-error" in str(exc.value)

    def test_sdk_call_exception_raises_tts_backend_error(self):
        class _BoomSynth(FakeSynthesizer):
            def call(self, text):
                raise RuntimeError("sdk-crash")

        sdk = FakeDashScopeSDK
        sdk.SpeechSynthesizer = _BoomSynth
        backend = make_backend(sdk=sdk)
        with pytest.raises(TTSBackendError) as exc:
            run(backend.synthesize("你好", "v", 1.0))
        assert "sdk-crash" in str(exc.value)

    def test_empty_audio_raises_tts_backend_error(self):
        sdk = FakeDashScopeSDK
        sdk.SpeechSynthesizer = _EmptySynth
        backend = make_backend(sdk=sdk)
        with pytest.raises(TTSBackendError) as exc:
            run(backend.synthesize("你好", "v", 1.0))
        assert "空音频" in str(exc.value)

    def test_timeout_raises_tts_backend_error_quickly(self):
        """SDK 挂死 + timeout=0.05 → 快速抛错（不等 0.3s 的假死线程）"""
        sdk = FakeDashScopeSDK
        sdk.SpeechSynthesizer = _SlowSynth
        backend = make_backend(sdk=sdk, timeout=0.05)
        t0 = time.monotonic()
        with pytest.raises(TTSBackendError) as exc:
            run(backend.synthesize("你好", "v", 1.0))
        elapsed = time.monotonic() - t0
        assert "超时" in str(exc.value)
        assert elapsed < 0.25, f"超时应快速返回，实际 {elapsed:.2f}s"

    def test_real_sdk_module_uses_tts_v2_synth_class(self, monkeypatch):
        """BUG-TTS-03 回归：sdk=真实 dashscope 模块时必须用 tts_v2 子模块类。

        顶层 dashscope.SpeechSynthesizer 指向旧版 tts 兼容壳 (self, /, *args, **kwargs)，
        恒抛 "SpeechSynthesizer() takes no arguments" → CosyVoice 每次降级 edge-tts
        （与 BUG-TTS-01 ResultCallback/AudioFormat 顶层缺失同模式）。
        """
        import dashscope
        import tts_engines

        captured = {}

        class _V2Synth(FakeSynthesizer):
            def __init__(self, **kwargs):
                super().__init__(**kwargs)
                captured["used"] = _V2Synth

        monkeypatch.setattr(tts_engines, "_SpeechSynthesizerV2", _V2Synth)
        backend = make_backend(sdk=dashscope)
        try:
            audio = run(backend.synthesize("你好", "longxing_v3", 1.0))
        finally:
            dashscope.api_key = None  # 恢复全局（synthesize 内会赋值）
        assert audio == b"chunk1chunk2"
        assert captured["used"] is _V2Synth  # 走 tts_v2 类而非顶层旧版壳


# ===== EdgeBackend =====

class FakeEdgeCommunicate:
    """模拟 edge_tts.Communicate：chunk 流 / 抛错 / 空流"""

    def __init__(self, text, voice, rate="", pitch=""):
        self.text = text
        self.voice = voice
        self.rate = rate
        self.pitch = pitch

    async def stream(self):
        yield {"type": "audio", "data": b"edge-chunk1"}
        yield {"type": "WordBoundary", "data": None}
        yield {"type": "audio", "data": b"edge-chunk2"}


def make_edge_backend(timeout=5.0, communicate_cls=FakeEdgeCommunicate):
    backend = EdgeBackend(timeout=timeout)
    fake_edge = types.SimpleNamespace(Communicate=communicate_cls)
    backend._get_edge = lambda: fake_edge
    return backend


class TestEdgeBackend:
    def test_synthesize_success_returns_bytes(self):
        backend = make_edge_backend()
        audio = run(backend.synthesize("你好", "zh-CN-XiaoxiaoNeural", 1.0))
        assert audio == b"edge-chunk1edge-chunk2"

    def test_rate_and_pitch_format(self):
        captured = {}

        class _Capture(FakeEdgeCommunicate):
            def __init__(self, text, voice, rate="", pitch=""):
                super().__init__(text, voice, rate, pitch)
                captured["rate"] = rate
                captured["pitch"] = pitch
                captured["voice"] = voice

        backend = make_edge_backend(communicate_cls=_Capture)
        run(backend.synthesize("你好", "v", 1.2, pitch=0.9))
        assert captured["rate"] == "+20%"
        assert captured["pitch"] == "-10Hz"
        assert captured["voice"] == "v"

    def test_negative_rate_format(self):
        captured = {}

        class _Capture(FakeEdgeCommunicate):
            def __init__(self, text, voice, rate="", pitch=""):
                super().__init__(text, voice, rate, pitch)
                captured["rate"] = rate

        backend = make_edge_backend(communicate_cls=_Capture)
        run(backend.synthesize("你好", "v", 0.8))
        assert captured["rate"] == "-20%"

    def test_stream_exception_raises_tts_backend_error(self):
        class _Boom(FakeEdgeCommunicate):
            async def stream(self):
                yield {"type": "audio", "data": b"partial"}
                raise RuntimeError("network-down")

        backend = make_edge_backend(communicate_cls=_Boom)
        with pytest.raises(TTSBackendError) as exc:
            run(backend.synthesize("你好", "v", 1.0))
        assert "network-down" in str(exc.value)

    def test_empty_audio_raises_tts_backend_error(self):
        class _Empty(FakeEdgeCommunicate):
            async def stream(self):
                yield {"type": "WordBoundary", "data": None}

        backend = make_edge_backend(communicate_cls=_Empty)
        with pytest.raises(TTSBackendError) as exc:
            run(backend.synthesize("你好", "v", 1.0))
        assert "音频为空" in str(exc.value)

    def test_timeout_raises_tts_backend_error(self):
        class _Slow(FakeEdgeCommunicate):
            async def stream(self):
                await asyncio.sleep(0.3)
                yield {"type": "audio", "data": b"late"}

        backend = make_edge_backend(timeout=0.05, communicate_cls=_Slow)
        with pytest.raises(TTSBackendError) as exc:
            run(backend.synthesize("你好", "v", 1.0))
        assert "超时" in str(exc.value)
