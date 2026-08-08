"""
D2 DashScope ASR 端到端冒烟测试（pytest 版，替代原 print 脚本 dashscope_asr_e2e.py）
用法：
  export DASHSCOPE_API_KEY=sk-xxx
  pytest test_dashscope_asr_e2e.py -v

- 无 DASHSCOPE_API_KEY 时自动 skip（CI/本地默认不联网）
- 复用 asr_engines.DashScopeASRBackend（与生产同一条转写路径，不再重复 Recognition 逻辑）
- 无真实音频文件时用合成正弦波 WAV 验证 API 可达性（无语音，文本可能为空）
"""
import math
import os
import struct
import tempfile

import pytest

from asr_engines import ASRBackendError, DashScopeASRBackend

pytestmark = pytest.mark.skipif(
    not os.environ.get("DASHSCOPE_API_KEY"),
    reason="DASHSCOPE_API_KEY 未设置（需要真实 API Key 的联网冒烟测试）",
)


def _make_backend() -> DashScopeASRBackend:
    return DashScopeASRBackend(
        model="paraformer-realtime-v2",
        api_key=os.environ["DASHSCOPE_API_KEY"],
    )


@pytest.fixture()
def sine_wav(tmp_path):
    """生成 2 秒 440Hz 正弦波 WAV（16kHz 16-bit mono），用于验证 API 可达性"""
    sample_rate = 16000
    duration_secs = 2
    num_samples = sample_rate * duration_secs
    data_size = num_samples * 2
    header = struct.pack(
        '<4sI4s4sIHHIIHH4sI',
        b'RIFF', 36 + data_size, b'WAVE',
        b'fmt ', 16, 1, 1, sample_rate, sample_rate * 2, 2, 16,
        b'data', data_size,
    )
    samples = b''.join(
        struct.pack('<h', int(16000 * math.sin(2 * math.pi * 440 * i / sample_rate)))
        for i in range(num_samples)
    )
    path = tmp_path / "sine.wav"
    path.write_bytes(header + samples)
    return str(path)


def test_dashscope_asr_api_reachable(sine_wav):
    """真实调用 DashScope Paraformer-V2：合成音频（无语音）应返回 200 且不抛错"""
    backend = _make_backend()
    assert backend.is_available()
    try:
        text = backend.transcribe(sine_wav)
    except ASRBackendError as e:
        pytest.fail(f"DashScope ASR 冒烟失败: {e}（检查 Key 是否开通实时语音识别服务）")
    # 合成音频无语音，允许空文本；但必须走完 200 分支（非 200 已由 ASRBackendError 拦截）
    assert isinstance(text, str)
