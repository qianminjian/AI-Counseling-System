"""
DC-011 音色引擎降级策略测试（fake 引擎注入，无外部依赖）

覆盖：
   1. 首引擎成功 → 返回结果与 engine 名
   2. 首引擎失败 → 降级第二引擎
   3. 全失败 → TTSSynthesisFailed
   4. 不可用引擎跳过（is_available()=False）
   5. Instruct 失败 → 无指令重试（retry_without_instruction=True 的引擎），TtsResult.retried=True
   6. 不支持重试的引擎 → 直接降级
   7. voice_map 按引擎名覆盖 voice_id（DashScope/edge 音色不同）
"""
import asyncio

import pytest

from tts_engines import TTSBackend, TTSBackendError
from tts_policy import DegradationPolicy, TTSSynthesisFailed, TtsResult


class FakeBackend(TTSBackend):
    """可编程 fake 引擎：可用性 / 失败模式 / 重试能力 / 调用记录"""

    def __init__(self, name, *, available=True, fail_with=None, retry_without_instruction=False,
                 supports_instruction=True):
        self.name = name
        self._available = available
        self._fail_with = fail_with          # None=成功；TTSBackendError / RuntimeError
        self.retry_without_instruction = retry_without_instruction
        self.supports_instruction = supports_instruction
        self.calls = []                      # [(voice_id, instruction), ...]

    def is_available(self):
        return self._available

    async def synthesize(self, text, voice_id, speed, instruction=None, pitch=1.0):
        self.calls.append((voice_id, instruction))
        if self._fail_with is not None:
            if self._fail_with == "TTSBackendError":
                raise TTSBackendError(f"{self.name} failed")
            raise RuntimeError(f"{self.name} boom")
        return b"audio:" + voice_id.encode()


def run(coro):
    return asyncio.run(coro)


class TestDegradationPolicy:
    def test_first_success_returns_result_with_engine_name(self):
        """首引擎成功 → TtsResult(audio, engine=首引擎名)"""
        ok = FakeBackend("primary")
        policy = DegradationPolicy(backends=[ok])
        result = run(policy.synthesize_with_degradation("你好", "v1", 1.0))
        assert isinstance(result, TtsResult)
        assert result.audio == b"audio:v1"
        assert result.engine == "primary"
        assert result.retried is False

    def test_first_fails_degrades_to_second(self):
        """首引擎失败 → 降级第二引擎，engine=第二引擎名"""
        bad = FakeBackend("primary", fail_with="TTSBackendError")
        fallback = FakeBackend("fallback")
        policy = DegradationPolicy(backends=[bad, fallback])
        result = run(policy.synthesize_with_degradation("你好", "v2", 1.0))
        assert result.engine == "fallback"
        assert result.audio == b"audio:v2"
        assert bad.calls == [("v2", None)]
        assert fallback.calls == [("v2", None)]

    def test_all_fail_raises_tts_synthesis_failed(self):
        """全失败 → TTSSynthesisFailed（含错误信息）"""
        bad1 = FakeBackend("a", fail_with="TTSBackendError")
        bad2 = FakeBackend("b", fail_with="RuntimeError")
        policy = DegradationPolicy(backends=[bad1, bad2])
        with pytest.raises(TTSSynthesisFailed) as exc:
            run(policy.synthesize_with_degradation("你好", "v3", 1.0))
        assert "a failed" in str(exc.value)
        assert "b boom" in str(exc.value)

    def test_unavailable_backend_skipped(self):
        """不可用引擎不尝试，直接落到可用引擎"""
        unavailable = FakeBackend("offline", available=False)
        ok = FakeBackend("online")
        policy = DegradationPolicy(backends=[unavailable, ok])
        result = run(policy.synthesize_with_degradation("你好", "v4", 1.0))
        assert result.engine == "online"
        assert unavailable.calls == []

    def test_no_available_backend_raises(self):
        """全部不可用 → TTSSynthesisFailed（无可用引擎）"""
        a = FakeBackend("a", available=False)
        b = FakeBackend("b", available=False)
        policy = DegradationPolicy(backends=[a, b])
        with pytest.raises(TTSSynthesisFailed):
            run(policy.synthesize_with_degradation("你好", "v5", 1.0))

    def test_instruction_retry_when_supported(self):
        """引擎支持重试 + Instruct 失败 → 无指令重试一次，retried=True"""
        backend = FakeBackend("primary", fail_with="TTSBackendError",
                              retry_without_instruction=True)
        fallback = FakeBackend("fallback", supports_instruction=False)  # 模拟 edge：不支持指令
        policy = DegradationPolicy(backends=[backend, fallback])
        result = run(policy.synthesize_with_degradation("你好", "v6", 1.0, instruction="instr"))
        # 同引擎尝试两次（带指令 + 无指令），无指令失败后才降级；降级引擎收到 None（不支持指令）
        assert backend.calls == [("v6", "instr"), ("v6", None)]
        assert fallback.calls == [("v6", None)]
        assert result.engine == "fallback"

    def test_instruction_retry_success_marks_retried(self):
        """无指令重试成功 → 返回结果且 retried=True（供指标区分 cosyvoice_fallback）"""
        calls = {"n": 0}

        class Retryable(FakeBackend):
            async def synthesize(self, text, voice_id, speed, instruction=None, pitch=1.0):
                self.calls.append((voice_id, instruction))
                calls["n"] += 1
                if calls["n"] == 1:
                    raise TTSBackendError("instruct 失败")
                return b"audio:ok"

        backend = Retryable("primary", retry_without_instruction=True)
        policy = DegradationPolicy(backends=[backend])
        result = run(policy.synthesize_with_degradation("你好", "v7", 1.0, instruction="instr"))
        assert result.engine == "primary"
        assert result.retried is True
        assert backend.calls == [("v7", "instr"), ("v7", None)]

    def test_instruction_not_retried_when_unsupported(self):
        """不支持重试的引擎 Instruct 失败 → 直接降级，不二次尝试"""
        backend = FakeBackend("primary", fail_with="TTSBackendError",
                              retry_without_instruction=False)
        fallback = FakeBackend("fallback")
        policy = DegradationPolicy(backends=[backend, fallback])
        result = run(policy.synthesize_with_degradation("你好", "v8", 1.0, instruction="instr"))
        assert backend.calls == [("v8", "instr")]           # 只尝试一次
        assert fallback.calls == [("v8", "instr")]
        assert result.engine == "fallback"

    def test_instruction_passed_through_to_engine(self):
        """无重试路径下 instruction 原样传给引擎"""
        backend = FakeBackend("primary")
        policy = DegradationPolicy(backends=[backend])
        run(policy.synthesize_with_degradation("你好", "v9", 1.0, instruction="instr"))
        assert backend.calls == [("v9", "instr")]

    def test_voice_map_overrides_voice_per_engine(self):
        """voice_map 按引擎名覆盖 voice_id（dashscope 音色与 edge 音色不同）"""
        dash = FakeBackend("cosyvoice", fail_with="TTSBackendError")
        edge = FakeBackend("edge_tts")
        policy = DegradationPolicy(backends=[dash, edge])
        run(policy.synthesize_with_degradation(
            "你好", "longxing_v3", 1.0,
            voice_map={"edge_tts": "zh-CN-XiaoxiaoNeural"},
        ))
        assert dash.calls == [("longxing_v3", None)]
        assert edge.calls == [("zh-CN-XiaoxiaoNeural", None)]

    def test_voice_map_defaults_to_voice_id(self):
        """voice_map 未覆盖的引擎 → 用默认 voice_id"""
        backend = FakeBackend("solo")
        policy = DegradationPolicy(backends=[backend])
        run(policy.synthesize_with_degradation("你好", "v10", 1.0, voice_map={"other": "x"}))
        assert backend.calls == [("v10", None)]
