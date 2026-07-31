#!/usr/bin/env python3
"""
DashScope ASR 端到端验证脚本
用法：
  export DASHSCOPE_API_KEY=sk-xxx
  python test_dashscope_asr.py [audio_file.wav]

无音频文件时自动生成一段包含简单音频的 WAV 验证 API 可达性。
有音频文件时验证实际转写结果。
"""

import os
import sys
import struct
import tempfile
import math

# ===== 配置 =====
API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")

if not API_KEY:
    print("❌ 请设置环境变量 DASHSCOPE_API_KEY")
    print("   export DASHSCOPE_API_KEY=sk-xxx")
    sys.exit(1)


def generate_test_wav(duration_secs=2, sample_rate=16000) -> str:
    """生成一段测试 WAV 文件（440Hz 正弦波，用于验证 API 可达性）"""
    num_samples = sample_rate * duration_secs
    data_size = num_samples * 2  # 16-bit mono
    header = struct.pack(
        '<4sI4s4sIHHIIHH4sI',
        b'RIFF', 36 + data_size, b'WAVE',
        b'fmt ', 16, 1, 1, sample_rate, sample_rate * 2, 2, 16,
        b'data', data_size
    )
    # Generate 440Hz sine wave (A4 note)
    samples = b''
    for i in range(num_samples):
        value = int(16000 * math.sin(2 * math.pi * 440 * i / sample_rate))
        samples += struct.pack('<h', value)

    fd, path = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    with open(path, 'wb') as f:
        f.write(header + samples)
    return path


def test_api_reachable():
    """测试 1：验证 DashScope ASR SDK 可达（使用合成音频）"""
    import dashscope
    from dashscope.audio.asr import Recognition, RecognitionCallback

    dashscope.api_key = API_KEY

    print("=" * 60)
    print("测试 1: DashScope Paraformer-V2 ASR SDK 验证")
    print(f"  Model: paraformer-realtime-v2")
    print(f"  Key: ...{API_KEY[-6:]}")
    print("-" * 60)

    wav_path = generate_test_wav()

    try:
        recognition = Recognition(
            model="paraformer-realtime-v2",
            format="wav",
            sample_rate=16000,
            callback=RecognitionCallback(),
        )
        result = recognition.call(file=wav_path)

        print(f"  Status Code: {result.status_code}")
        if hasattr(result, 'code'):
            print(f"  Code: {result.code}")
        if hasattr(result, 'message'):
            print(f"  Message: {result.message}")

        if result.status_code == 200:
            sentences = result.get_sentence() if hasattr(result, 'get_sentence') else []
            if isinstance(sentences, dict):
                sentences = [sentences]
            text = "".join(s.get("text", "") for s in (sentences or []))
            print(f"\n  ✅ API 可达！转写结果: '{text}' (合成音频无语音，可能为空)")
            if hasattr(result, 'usage') and result.usage:
                print(f"  用量: {result.usage}")
            return True
        else:
            print(f"\n  ❌ API 返回非 200: code={getattr(result, 'code', '?')}, msg={getattr(result, 'message', '?')}")
            return False

    except Exception as e:
        print(f"\n  ❌ 调用失败: {type(e).__name__}: {e}")
        return False
    finally:
        os.unlink(wav_path)


def test_with_audio_file(file_path: str):
    """测试 2：使用实际音频文件验证转写"""
    import dashscope
    from dashscope.audio.asr import Recognition, RecognitionCallback

    dashscope.api_key = API_KEY

    print("\n" + "=" * 60)
    print(f"测试 2: 实际音频转写验证")
    print(f"  文件: {file_path}")
    print("-" * 60)

    if not os.path.exists(file_path):
        print(f"  ❌ 文件不存在: {file_path}")
        return False

    file_size = os.path.getsize(file_path)
    print(f"  大小: {file_size / 1024:.1f} KB")

    try:
        recognition = Recognition(
            model="paraformer-realtime-v2",
            format="wav",
            sample_rate=16000,
            callback=RecognitionCallback(),
        )
        result = recognition.call(file=file_path)

        print(f"  Status Code: {result.status_code}")

        if result.status_code == 200:
            sentences = result.get_sentence() if hasattr(result, 'get_sentence') else []
            if isinstance(sentences, dict):
                sentences = [sentences]
            text = "".join(s.get("text", "") for s in (sentences or []))
            print(f"\n  ✅ 转写成功！")
            print(f"  文本: {text}")
            print(f"  句子数: {len(sentences or [])}")
            return True
        else:
            print(f"\n  ❌ 转写失败: code={getattr(result, 'code', '?')}, msg={getattr(result, 'message', '?')}")
            return False

    except Exception as e:
        print(f"\n  ❌ 调用失败: {type(e).__name__}: {e}")
        return False


if __name__ == "__main__":
    print("DashScope Paraformer-V2 ASR 端到端验证")
    print("=" * 60)

    # 测试 1: API 可达性
    reachable = test_api_reachable()

    # 测试 2: 如果提供了音频文件，验证实际转写
    if len(sys.argv) > 1:
        test_with_audio_file(sys.argv[1])

    print("\n" + "=" * 60)
    if reachable:
        print("🎉 验证通过！DashScope ASR 模式可用。")
        print("   部署时设置 ASR_ENGINE=dashscope 即可启用。")
    else:
        print("⚠️  验证未通过，请检查：")
        print("   1. DASHSCOPE_API_KEY 是否正确")
        print("   2. 该 Key 是否已开通语音识别（实时语音识别）服务")
        print("   3. 网络是否可达 dashscope.aliyuncs.com")
        print("   开通地址：https://nls-portal.console.aliyun.com/overview")
    print("=" * 60)
