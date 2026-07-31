"""
MindSafe TTS 微服务（三级降级架构）
- Level 1：阿里云百炼 CosyVoice（主力，国内低延迟）
- Level 2：edge-tts（微软，备用）
- Level 3：前端浏览器 speechSynthesis 兜底（本服务返回 503 时前端自动降级）
- 部署：Docker 容器，端口 10096
"""

import io
import json
import logging
import os

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("tts-service")

app = FastAPI(title="MindSafe TTS Service", version="2.1.0")

# 全局复用 httpx 客户端（连接池复用，减少每次请求的 TCP/TLS 握手延迟）
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

# ===== Level 1：阿里云百炼 CosyVoice（非实时 HTTP API） =====

DASHSCOPE_API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")
DASHSCOPE_TTS_URL = os.environ.get(
    "DASHSCOPE_TTS_URL",
    "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer"
)
DASHSCOPE_TTS_MODEL = os.environ.get("DASHSCOPE_TTS_MODEL", "cosyvoice-v3-flash")
CLOUD_TTS_AVAILABLE = bool(DASHSCOPE_API_KEY)

if CLOUD_TTS_AVAILABLE:
    logger.info("✅ 阿里云 CosyVoice TTS 就绪 (model=%s)", DASHSCOPE_TTS_MODEL)
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
    """Level 1：阿里云百炼 CosyVoice（非实时 HTTP API，返回音频 URL）"""
    headers = {
        "Authorization": f"Bearer {DASHSCOPE_API_KEY}",
        "Content-Type": "application/json",
    }
    params = {"voice": voice, "format": "mp3"}
    # 语速调整（CosyVoice 支持 0.5~2.0）
    if abs(speed - 1.0) > 0.05:
        params["rate"] = round(max(0.5, min(2.0, speed)), 2)

    body = {
        "model": DASHSCOPE_TTS_MODEL,
        "input": {"text": text},
        "parameters": params,
    }

    # 复用全局 httpx 客户端（连接池）
    client = http_client or httpx.AsyncClient(timeout=20.0)
    try:
        # 第一步：调用合成 API，获取音频 URL
        resp = await client.post(DASHSCOPE_TTS_URL, headers=headers, json=body)

        if resp.status_code != 200:
            detail = resp.text[:200] if resp.text else f"HTTP {resp.status_code}"
            raise RuntimeError(f"CosyVoice API 错误: {detail}")

        data = resp.json()
        audio_url = data.get("output", {}).get("audio", {}).get("url", "")
        if not audio_url:
            raise RuntimeError(f"CosyVoice 返回无音频 URL: {json.dumps(data, ensure_ascii=False)[:200]}")

        # 第二步：下载音频文件
        audio_resp = await client.get(audio_url)
        if audio_resp.status_code != 200:
            raise RuntimeError(f"音频下载失败: HTTP {audio_resp.status_code}")
    finally:
        if not http_client:
            await client.aclose()

    audio_bytes = audio_resp.content
    if not audio_bytes or len(audio_bytes) < 100:
        raise RuntimeError("CosyVoice 返回音频为空")

    logger.info(f"CosyVoice 合成成功: voice={voice}, bytes={len(audio_bytes)}")
    return StreamingResponse(
        io.BytesIO(audio_bytes),
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
