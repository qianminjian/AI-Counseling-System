"""
共享覆盖键读取器（doing/87 RUNTIME-001/002 单源化，板块10 P2-1）

背景：两服务覆盖键降级覆盖此前复制实现，键格式/超时/解码语义漂移无共享约束——
- tts-service/app.py._read_tts_override()（键维度 tts）
- voice-service/app.py._read_override(point)（键维度 asr|ser）
本模块收编统一，与 config_loader/metrics_common 同属 py-common 共享单源先例。

语义（与 RUNTIME-001/002 冻结决策一致，不得改变）：
- 键：mindsafe:degradation:override:{point}；TTL 由后端写侧保证（7 天），本模块只读不写
- fail-open：Redis 不可达 / 键缺失 / 任意异常一律返回 None，调用方按配置默认运行，不阻断服务
- 读取侧行为：单客户端懒加载（redis-py 直连，1s 连接/操作超时，decode_responses 解码）
"""
import logging
import os
from typing import Callable, Optional

logger = logging.getLogger("py-common.degradation_override")

# 覆盖键前缀（读侧与写侧共享契约：管理端写侧/运维脚本与此保持一致，改键必须两端同步）
OVERRIDE_PREFIX = "mindsafe:degradation:override:"
# TTL 由后端写侧保证（7 天）；本模块只读不写，常量仅作文档/测试契约引用
OVERRIDE_TTL_SECONDS = 7 * 24 * 3600

_redis_client = None


def read_override(point: str, log: Optional[Callable[..., None]] = None) -> Optional[str]:
    """读覆盖键 mindsafe:degradation:override:{point}（fail-open）。

    :param point: 键维度（tts / asr / ser 等，由调用方定维度词表）
    :param log: 告警回调（默认 logger.warning；测试可注入 logger 或 no-op）
    :return: 覆盖值字符串；Redis 不可达/键缺失/异常返回 None（按配置默认运行）
    """
    global _redis_client
    warn = log if callable(log) else logger.warning
    try:
        if _redis_client is None:
            import redis
            _redis_client = redis.Redis(
                host=os.environ.get("REDIS_HOST", "redis"),
                port=int(os.environ.get("REDIS_PORT", "6379")),
                password=os.environ.get("REDIS_PASSWORD") or None,
                socket_connect_timeout=1, socket_timeout=1,
                decode_responses=True,
            )
        return _redis_client.get(OVERRIDE_PREFIX + point)
    except Exception as e:
        warn("覆盖键读取失败（fail-open，按配置默认）: %s", e)
        return None
