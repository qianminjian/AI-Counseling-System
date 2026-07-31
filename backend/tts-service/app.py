"""
MindSafe TTS 微服务（三级降级架构 v3）
- Level 1：阿里云百炼 CosyVoice（DashScope SDK WebSocket 流式合成，首包 <800ms）
- Level 2：edge-tts（微软，备用）
- Level 3：前端浏览器 speechSynthesis 兖底（本服务返回 503 时前端自动降级）
- 部署：Docker 容器，端口 10096
"""

import asyncio
import io
import json
import logging
import os
import threading

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("tts-service")

app = FastAPI(title="MindSafe TTS Service", version="3.0.0")

# 全局复用 httpx 客户端（edge-tts 降级时用）
http_client: httpx.AsyncClient = None


@app.on_event("startup")
async def _startup():
    global http_client
    http_client = httpx.AsyncClient(timeout=20.0, limits=httpx.Limits(max_connections=20))


@app.on_event("shutdown")
async def _shutdown():
    if http_client:
        await http_client.aclose()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ===== Level 1：阿里云百炼 CosyVoice（DashScope SDK WebSocket 流式） =====

DASHSCOPE_API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")
DASHSCOPE_TTS_MODEL = os.environ.get("DASHSCOPE_TTS_MODEL", "cosyvoice-v3-flash")
CLOUD_TTS_AVAILABLE = False

if DASHSCOPE_API_KEY:
    try:
        import dashscope
        from dashscope.audio.tts_v2 import SpeechSynthesizer, AudioFormat, ResultCallback
        dashscope.api_key = DASHSCOPE_API_KEY
        # 使用默认北京地域 WebSocket 端点
        CLOUD_TTS_AVAILABLE = True
        logger.info("✅ 阿里云 CosyVoice TTS 就绪 (model=%s, SDK WebSocket 流式)", DASHSCOPE_TTS_MODEL)
    except ImportError:
        logger.warning("dashscope SDK 未安装，CosyVoice 不可用")
else:
    logger.warning("DASHSCOPE_API_KEY 未配置，阿里云 CosyVoice 不可用")

# ===== Level 2：edge-tts（微软免费 TTS，备用） =====

EDGE_TTS_AVAILABLE = False
try:
    import edge_tts  # noqa: F401
    EDGE_TTS_AVAILABLE = True
    logger.info("✅ edge-tts 备用方案就绪")
except ImportError:
    logger.warning("edge-tts 未安装，备用方案不可用")


# ===== 音色人设配置 =====
# dashscope_voice: 阿里云 CosyVoice 音色名（cosyvoice-v3-flash 音色列表）
# edge_voice: edge-tts 备用音色

VOICE_PERSONAS = {
    "xiaoxing": {
        "name": "小星",
        "desc": "温暖的大姐姐",
        "speed": 1.0,
        "dashscope_voice": "longxing_v3",
        "edge_voice": "zh-CN-XiaoxiaoNeural",
    },
    "qiqiu": {
        "name": "气球",
        "desc": "活泼的小伙伴",
        "speed": 1.05,
        "dashscope_voice": "longanhuan_v3",
        "edge_voice": "zh-CN-XiaoyiNeural",
    },
    "yueliang": {
        "name": "月亮",
        "desc": "温柔的讲故事者",
        "speed": 0.92,
        "dashscope_voice": "longwan_v3",
        "edge_voice": "zh-CN-XiaohanNeural",
    },
    "xiaotaiyang": {
        "name": "小太阳",
        "desc": "阳光的大哥哥",
        "speed": 1.05,
        "dashscope_voice": "longanyang",
        "edge_voice": "zh-CN-YunxiNeural",
    },
}


# ===== 数据模型 =====

class TtsRequest(BaseModel):
    text: str                           # 要合成的文本
    persona: str = "xiaoxing"           # 音色人设
    emotion: str = "neutral"            # 孩子当前情绪（用于调整语气）
    speed: float = 1.0                  # 语速倍率（年龄适配）
    pitch: float = 1.0                  # 音高基调（TMATCH-001 prosody，<1 更低沉安抚）
    pause_style: int = 1                # 停顿风格（0=轻快 1=自然 2=多停顿安抚）


class TtsInfoResponse(BaseModel):
    available: bool
    engine: str
    personas: list[dict]


# ===== API 端点 =====

@app.get("/health")
def health():
    if CLOUD_TTS_AVAILABLE:
        engine = "cosyvoice-cloud"
    elif EDGE_TTS_AVAILABLE:
        engine = "edge-tts"
    else:
        engine = "none"
    return {"status": "UP" if engine != "none" else "DEGRADED", "engine": engine}


@app.get("/api/v1/tts/personas")
def list_personas():
    """返回可用音色人设列表"""
    personas = []
    for key, cfg in VOICE_PERSONAS.items():
        personas.append({
            "id": key,
            "name": cfg["name"],
            "desc": cfg["desc"],
        })
    return {"success": True, "data": personas}


@app.post("/api/v1/tts/synthesize")
async def synthesize(req: TtsRequest):
    """
    文本 → 语音合成（三级降级：阿里云 CosyVoice → edge-tts → 503）
    返回音频二进制流
    """
    if not req.text.strip():
        raise HTTPException(status_code=400, detail="文本不能为空")

    if len(req.text) > 500:
        raise HTTPException(status_code=400, detail="单次合成文本不超过500字")

    persona_cfg = VOICE_PERSONAS.get(req.persona, VOICE_PERSONAS["xiaoxing"])
    final_speed = persona_cfg["speed"] * req.speed

    logger.info(f"TTS 合成: text_len={len(req.text)}, persona={req.persona}, "
                f"emotion={req.emotion}, speed={final_speed:.2f}")

    # Level 1：阿里云 CosyVoice
    if CLOUD_TTS_AVAILABLE:
        try:
            return await _synthesize_dashscope(req.text, persona_cfg["dashscope_voice"], final_speed)
        except Exception as e:
            logger.warning(f"阿里云 CosyVoice 失败，降级 edge-tts: {e}")

    # Level 2：edge-tts
    if EDGE_TTS_AVAILABLE:
        try:
            return await _synthesize_edge_tts(req.text, persona_cfg["edge_voice"], final_speed, req.pitch)
        except Exception as e:
            logger.error(f"edge-tts 也失败: {e}")

    # Level 3：返回 503，前端自动降级到浏览器 speechSynthesis
    raise HTTPException(status_code=503, detail="TTS 服务不可用")


async def _synthesize_dashscope(text: str, voice: str, speed: float):
    """
    Level 1：阿里云 CosyVoice（DashScope SDK WebSocket 流式合成）
    相比旧版 HTTP 两步调用（POST→URL→download），延迟降低 50%+：
    - 单 WebSocket 连接，音频边合成边返回
    - 无第二次 HTTP 下载往返
    """
    loop = asyncio.get_event_loop()
    queue: asyncio.Queue = asyncio.Queue()
    error_holder = [None]  # 用列表包装以便在回调中修改

    class _Callback(ResultCallback):
        def on_open(self):
            pass

        def on_data(self, data: bytes):
            # 从 SDK 线程桥接到 asyncio 事件循环
            loop.call_soon_threadsafe(queue.put_nowait, data)

        def on_complete(self):
            loop.call_soon_threadsafe(queue.put_nowait, None)  # 哨兵：合成完成

        def on_error(self, message):
            error_holder[0] = str(message)
            loop.call_soon_threadsafe(queue.put_nowait, None)

        def on_close(self):
            # 确保即使异常关闭也能结束
            loop.call_soon_threadsafe(queue.put_nowait, None)

    def _run_synthesis():
        """SDK 的 call() 是阻塞的，在线程中执行"""
        try:
            synthesizer = SpeechSynthesizer(
                model=DASHSCOPE_TTS_MODEL,
                voice=voice,
                format=AudioFormat.MP3_22050HZ_MONO_256KBPS,
                speech_rate=max(0.5, min(2.0, speed)),
                callback=_Callback(),
            )
            synthesizer.call(text)
        except Exception as e:
            error_holder[0] = str(e)
            loop.call_soon_threadsafe(queue.put_nowait, None)

    # 启动合成线程
    t = threading.Thread(target=_run_synthesis, daemon=True)
    t.start()

    # 异步生成器：从 queue 中取音频块，流式返回给客户端
    async def audio_stream():
        total = 0
        while True:
            chunk = await queue.get()
            if chunk is None:
                break
            total += len(chunk)
            yield chunk
        if error_holder[0]:
            logger.error(f"CosyVoice SDK 错误: {error_holder[0]}")
            if total == 0:
                raise RuntimeError(error_holder[0])
        else:
            logger.info(f"CosyVoice 流式合成完成: voice={voice}, total_bytes={total}")

    return StreamingResponse(
        audio_stream(),
        media_type="audio/mpeg",
        headers={"X-TTS-Engine": "cosyvoice-cloud", "X-TTS-Voice": voice},
    )


async def _synthesize_edge_tts(text: str, voice: str, speed: float, pitch: float = 1.0):
    """Level 2：edge-tts 备用合成（需联网访问微软）"""
    import edge_tts

    rate_pct = int((speed - 1.0) * 100)
    rate_str = f"+{rate_pct}%" if rate_pct >= 0 else f"{rate_pct}%"
    pitch_hz = int((pitch - 1.0) * 100)
    pitch_str = f"+{pitch_hz}Hz" if pitch_hz >= 0 else f"{pitch_hz}Hz"

    communicate = edge_tts.Communicate(text, voice, rate=rate_str, pitch=pitch_str)
    buffer = io.BytesIO()
    async for chunk in communicate.stream():
        if chunk["type"] == "audio":
            buffer.write(chunk["data"])

    if buffer.tell() == 0:
        raise RuntimeError("edge-tts 返回音频为空")

    buffer.seek(0)
    logger.info(f"edge-tts 合成成功: voice={voice}, bytes={buffer.tell()}")
    return StreamingResponse(
        buffer,
        media_type="audio/mpeg",
        headers={"X-TTS-Engine": "edge-tts", "X-TTS-Voice": voice},
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=10096)
