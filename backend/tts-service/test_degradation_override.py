"""
py-common/degradation_override 共享覆盖键读取器单测（板块10 P2-1）

覆盖：
- 常量契约：键前缀 / TTL（与写侧共享，改键必须两端同步）
- 键拼接：mindsafe:degradation:override:{point}
- 键缺失 → None（fail-open 按配置默认）
- Redis 异常 → None + 告警日志（fail-open 不阻断服务）
- 懒加载：客户端按环境变量构造（REDIS_HOST/PORT/PASSWORD + 1s 超时 + decode）
"""
import sys
import types

import pytest

from degradation_override import (
    OVERRIDE_PREFIX,
    OVERRIDE_TTL_SECONDS,
    read_override,
)


class _FakeRedis:
    """fake redis 客户端：可配置 values / get 失败，记录调用与构造参数"""

    def __init__(self, values=None, fail_get=False, **kwargs):
        self.values = values or {}
        self.fail_get = fail_get
        self.kwargs = kwargs
        self.calls = []

    def get(self, key):
        self.calls.append(key)
        if self.fail_get:
            raise ConnectionError("redis down")
        return self.values.get(key)


class TestConstants:
    """键前缀/TTL 契约（与后端写侧共享，改键必须两端同步）"""

    def test_key_prefix_contract(self):
        assert OVERRIDE_PREFIX == "mindsafe:degradation:override:"

    def test_ttl_7_days(self):
        assert OVERRIDE_TTL_SECONDS == 7 * 24 * 3600


class TestReadOverride:
    """读取语义（RUNTIME-001/002 冻结：fail-open 不得改变）"""

    def test_reads_prefixed_key(self, monkeypatch):
        """值返回：get 收到的键 = 前缀 + point"""
        import degradation_override as mod
        fake = _FakeRedis(values={"mindsafe:degradation:override:tts": "cosyvoice"})
        monkeypatch.setattr(mod, "_redis_client", fake)
        assert read_override("tts") == "cosyvoice"
        assert fake.calls == ["mindsafe:degradation:override:tts"]

    def test_voice_dimension_key(self, monkeypatch):
        """voice 维度：asr/ser 键独立"""
        import degradation_override as mod
        fake = _FakeRedis(values={"mindsafe:degradation:override:ser": "disabled"})
        monkeypatch.setattr(mod, "_redis_client", fake)
        assert read_override("ser") == "disabled"
        assert read_override("asr") is None  # 键缺失

    def test_missing_key_returns_none(self, monkeypatch):
        """键缺失 → None（fail-open 按配置默认）"""
        import degradation_override as mod
        monkeypatch.setattr(mod, "_redis_client", _FakeRedis(values={}))
        assert read_override("asr") is None

    def test_fail_open_on_redis_error(self, monkeypatch, caplog):
        """Redis 异常 → None + 告警日志（fail-open 不阻断服务）"""
        import degradation_override as mod
        monkeypatch.setattr(mod, "_redis_client", _FakeRedis(fail_get=True))
        with caplog.at_level("WARNING", logger="py-common.degradation_override"):
            assert read_override("ser") is None
        assert any("fail-open" in r.message for r in caplog.records)

    def test_lazy_client_from_env(self, monkeypatch):
        """懒加载：客户端按环境变量构造（1s 超时 + decode_responses + 密码可空）"""
        import degradation_override as mod
        fake_redis_module = types.ModuleType("redis")
        fake_redis_module.Redis = _FakeRedis
        monkeypatch.setitem(sys.modules, "redis", fake_redis_module)
        monkeypatch.setattr(mod, "_redis_client", None)
        monkeypatch.setenv("REDIS_HOST", "myredis")
        monkeypatch.setenv("REDIS_PORT", "6380")
        assert read_override("tts") is None  # 键缺失
        client = mod._redis_client
        assert client.kwargs["host"] == "myredis"
        assert client.kwargs["port"] == 6380
        assert client.kwargs["password"] is None
        assert client.kwargs["socket_connect_timeout"] == 1
        assert client.kwargs["socket_timeout"] == 1
        assert client.kwargs["decode_responses"] is True
        assert client.calls == ["mindsafe:degradation:override:tts"]

    def test_custom_log_callback(self, monkeypatch):
        """log 参数可注入（无默认 logger 依赖）"""
        import degradation_override as mod
        monkeypatch.setattr(mod, "_redis_client", _FakeRedis(fail_get=True))
        warned = []
        assert read_override("tts", log=lambda msg, *a: warned.append(msg)) is None
        assert warned and "fail-open" in warned[0]
