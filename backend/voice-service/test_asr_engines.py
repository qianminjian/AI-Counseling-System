"""
D2 ASR 引擎适配器测试（mock SDK / fake 模块注入，无真实网络与 API Key）

覆盖：
   纯函数：
     1. parse_dashscope_sentences：多句列表拼接 / 单句 dict / 无 get_sentence / 空 / strip
     2. map_dashscope_error：200 → None；非 200 带 code/message；非 200 缺字段兜底
     3. clean_sensevoice_text：SenseVoice <|...|> 标记清洗 / 无标记原样 / strip
   DashScopeASRBackend：
     4. is_available：有 key + SDK → True；无 key / SDK 缺失 → False
     5. transcribe 成功 → 拼接文本
     6. transcribe 非 200 → ASRBackendError（含上游 message）
     7. transcribe SDK 缺失 → ASRBackendError
     8. Recognition 构造参数（model/format/sample_rate/callback + file）正确
   FunASRBackend：
     9. is_available：model 为 None → False
     10. transcribe 成功 → 文本 + SenseVoice 标记清洗
     11. transcribe 空结果 → 空串
"""
import pytest
from types import SimpleNamespace

from asr_engines import (
    ASRBackendError,
    DashScopeASRBackend,
    FunASRBackend,
    clean_sensevoice_text,
    map_dashscope_error,
    parse_dashscope_sentences,
)


# ===== fake DashScope ASR SDK =====


class FakeResult:
    """可编程 Recognition.call() 返回对象（status_code / code / message / sentences）"""

    def __init__(self, status_code=200, code=None, message=None, sentences=None):
        self.status_code = status_code
        self.code = code
        self.message = message
        self._sentences = sentences

    def get_sentence(self):
        return self._sentences


class FakeRecognition:
    """捕获构造参数；call() 返回预置 result（类属性预置，测试直接赋值）"""

    result = None
    instances = []

    def __init__(self, **kwargs):
        self.kwargs = kwargs
        FakeRecognition.instances.append(self)

    def call(self, file):
        self.kwargs["file"] = file
        return self.result


class FakeASRSDK:
    """模拟真实 dashscope 顶层模块面：无 Recognition/RecognitionCallback 属性
    （实测 2026-08-08：真实包顶层不暴露这两个类，它们位于 dashscope.audio.asr 子模块）"""

    def __init__(self):
        self.api_key = None


class FakeRecognitionCallback:
    """no-op callback（构造参数校验用）"""


@pytest.fixture
def dashscope_env(monkeypatch):
    """构造 dashscope 已安装环境：
    顶层模块对象（无 Recognition，模拟真实包面）+ 子模块类注入 asr_engines 模块级
    （D2 review High-1 修复后：transcribe 经模块级 _Recognition/_RecognitionCallback 调用）"""
    sdk = FakeASRSDK()
    monkeypatch.setattr("asr_engines._dashscope", sdk)
    monkeypatch.setattr("asr_engines._Recognition", FakeRecognition)
    monkeypatch.setattr("asr_engines._RecognitionCallback", FakeRecognitionCallback)
    return sdk


def make_dashscope_backend(sdk=None, api_key="test-key", model="paraformer-realtime-v2"):
    return DashScopeASRBackend(model=model, api_key=api_key, sdk=sdk)


# ===== 纯函数 =====


class TestParseDashscopeSentences:
    def test_multiple_sentences_joined(self):
        result = FakeResult(sentences=[{"text": "你好"}, {"text": "世界"}])
        assert parse_dashscope_sentences(result) == "你好世界"

    def test_single_dict_sentence(self):
        result = FakeResult(sentences={"text": "你好"})
        assert parse_dashscope_sentences(result) == "你好"

    def test_missing_get_sentence_returns_empty(self):
        result = SimpleNamespace(status_code=200, sentences=[{"text": "x"}])
        assert parse_dashscope_sentences(result) == ""

    def test_empty_and_none_sentences(self):
        assert parse_dashscope_sentences(FakeResult(sentences=[])) == ""
        assert parse_dashscope_sentences(FakeResult(sentences=None)) == ""

    def test_strips_outer_whitespace(self):
        # 与原生产语义一致：仅 strip 首尾空白，句子间空格保留
        result = FakeResult(sentences=[{"text": "  你好  "}, {"text": "世界  "}])
        assert parse_dashscope_sentences(result) == "你好  世界"


class TestMapDashscopeError:
    def test_status_200_returns_none(self):
        assert map_dashscope_error(FakeResult(status_code=200)) is None

    def test_non_200_with_code_and_message(self):
        result = FakeResult(status_code=400, code="InvalidParameter", message="bad audio")
        assert map_dashscope_error(result) == "status=400, code=InvalidParameter, msg=bad audio"

    def test_non_200_missing_fields_falls_back(self):
        # 无 code/message 属性的对象：getattr 兜底为 "?"
        result = SimpleNamespace(status_code=502)
        assert map_dashscope_error(result) == "status=502, code=?, msg=?"


class TestCleanSensevoiceText:
    def test_removes_sensevoice_markers(self):
        assert clean_sensevoice_text("<|zh|><|HAPPY|>你好<|nospeech|>") == "你好"

    def test_plain_text_unchanged(self):
        assert clean_sensevoice_text("  你好世界  ") == "你好世界"

    def test_empty_text(self):
        assert clean_sensevoice_text("") == ""


# ===== DashScopeASRBackend =====


class TestDashScopeASRBackend:
    def test_is_available_with_key_and_sdk(self, dashscope_env):
        assert make_dashscope_backend().is_available() is True

    def test_is_available_false_without_key(self):
        backend = make_dashscope_backend(api_key="")
        assert backend.is_available() is False

    def test_is_available_false_without_sdk(self, monkeypatch):
        # 模拟 dashscope 未安装：模块级 _dashscope 置 None，且不注入 sdk
        monkeypatch.setattr("asr_engines._dashscope", None)
        backend = DashScopeASRBackend(model="paraformer-realtime-v2", api_key="test-key")
        assert backend.is_available() is False

    def test_transcribe_success_returns_joined_text(self, dashscope_env):
        FakeRecognition.result = FakeResult(
            status_code=200,
            sentences=[{"text": "今天"}, {"text": "天气不错"}],
        )
        backend = make_dashscope_backend()
        assert backend.transcribe("/tmp/a.wav") == "今天天气不错"

    def test_transcribe_uses_submodule_classes_not_sdk_top_level(self, dashscope_env):
        """D2 review High-1 回归：真实 dashscope 顶层不暴露 Recognition（实测），
        transcribe 必须经模块级 _Recognition 调用（sdk 对象无 Recognition 属性也正常）"""
        assert not hasattr(dashscope_env, "Recognition")
        FakeRecognition.result = FakeResult(status_code=200, sentences=[])
        backend = make_dashscope_backend(sdk=dashscope_env)
        backend.transcribe("/tmp/a.wav")
        rec = FakeRecognition.instances[-1]
        assert rec.kwargs["model"] == "paraformer-realtime-v2"
        assert rec.kwargs["format"] == "wav"
        assert rec.kwargs["sample_rate"] == 16000
        assert rec.kwargs["callback"] is not None
        assert rec.kwargs["file"] == "/tmp/a.wav"

    def test_transcribe_injects_api_key_into_sdk(self, dashscope_env):
        """seam 自包含：api_key 显式写入 sdk（不依赖 app.py 模块级全局赋值）"""
        FakeRecognition.result = FakeResult(status_code=200, sentences=[])
        backend = make_dashscope_backend(sdk=dashscope_env, api_key="my-secret-key")
        backend.transcribe("/tmp/a.wav")
        assert dashscope_env.api_key == "my-secret-key"

    def test_transcribe_non_200_raises_asr_backend_error(self, dashscope_env):
        FakeRecognition.result = FakeResult(
            status_code=400, code="InvalidParameter", message="bad audio"
        )
        backend = make_dashscope_backend()
        with pytest.raises(ASRBackendError, match="bad audio"):
            backend.transcribe("/tmp/a.wav")

    def test_transcribe_without_sdk_raises_asr_backend_error(self, monkeypatch):
        monkeypatch.setattr("asr_engines._dashscope", None)
        monkeypatch.setattr("asr_engines._Recognition", None)
        backend = DashScopeASRBackend(model="paraformer-realtime-v2", api_key="test-key")
        assert not backend.is_available()
        with pytest.raises(ASRBackendError, match="SDK"):
            backend.transcribe("/tmp/a.wav")


# ===== FunASRBackend =====


class FakeAutoModel:
    """模拟 FunASR AutoModel.generate() 返回结构"""

    def __init__(self, result):
        self.result = result
        self.generate_kwargs = None

    def generate(self, input, **kwargs):
        self.generate_kwargs = {"input": input, **kwargs}
        return self.result


class TestFunASRBackend:
    def test_is_available_false_without_model(self):
        assert FunASRBackend(model=None).is_available() is False

    def test_is_available_true_with_model(self):
        assert FunASRBackend(model=FakeAutoModel([])).is_available() is True

    def test_transcribe_returns_text_and_cleans_markers(self):
        model = FakeAutoModel([{"text": "<|zh|>你好世界"}])
        backend = FunASRBackend(model=model)
        assert backend.transcribe("/tmp/a.wav") == "你好世界"
        assert model.generate_kwargs == {"input": "/tmp/a.wav", "language": "zh", "use_itn": True}

    def test_transcribe_empty_result(self):
        backend = FunASRBackend(model=FakeAutoModel([]))
        assert backend.transcribe("/tmp/a.wav") == ""
