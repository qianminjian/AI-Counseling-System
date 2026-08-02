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
from typing import Optional

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
DASHSCOPE_TTS_MODEL = os.environ.get("DASHSCOPE_TTS_MODEL", "cosyvoice-v3.5-flash")
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


# ===== 音色人设配置（7 音色差异化矩阵，见 design/56） =====
# dashscope_voice: 阿里云 CosyVoice 音色名（cosyvoice-v3.5-flash 音色列表）
# edge_voice: edge-tts 备用音色
# dialect_capable: 支持方言 Instruct（仅 longanhuan_v3）
# emotion_capable: 支持情感 Instruct（仅 longanyang）

VOICE_PERSONAS = {
    "xiaoxing": {
        "name": "小星",
        "desc": "温暖的邻家姐姐",
        "speed": 1.0,
        "dashscope_voice": "longxing_v3",
        "edge_voice": "zh-CN-XiaoxiaoNeural",
        "dialect_capable": False,
        "emotion_capable": False,
    },
    "bobo": {
        "name": "波波老师",
        "desc": "温柔的女老师",
        "speed": 0.95,
        "dashscope_voice": "longyingling_v3",
        "edge_voice": "zh-CN-XiaoxiaoNeural",
        "dialect_capable": False,
        "emotion_capable": False,
    },
    "yueliang": {
        "name": "月亮",
        "desc": "轻声讲故事",
        "speed": 0.92,
        "dashscope_voice": "longwan_v3",
        "edge_voice": "zh-CN-XiaohanNeural",
        "dialect_capable": False,
        "emotion_capable": False,
    },
    "xiaotaiyang": {
        "name": "小太阳",
        "desc": "阳光大哥哥",
        "speed": 1.05,
        "dashscope_voice": "longanyang",
        "edge_voice": "zh-CN-YunxiNeural",
        "dialect_capable": False,
        "emotion_capable": True,
    },
    "dashu": {
        "name": "大树",
        "desc": "暖心大叔",
        "speed": 0.95,
        "dashscope_voice": "longanyun_v3",
        "edge_voice": "zh-CN-YunyangNeural",
        "dialect_capable": False,
        "emotion_capable": False,
    },
    "doudou": {
        "name": "豆豆",
        "desc": "同龄小伙伴",
        "speed": 1.05,
        "dashscope_voice": "longjielidou_v3",
        "edge_voice": "zh-CN-YunxiaNeural",
        "dialect_capable": False,
        "emotion_capable": False,
    },
    "qiqiu": {
        "name": "方言",
        "desc": "方言伙伴",
        "speed": 1.05,
        "dashscope_voice": "longanhuan_v3",
        "edge_voice": "zh-CN-XiaoyiNeural",
        "dialect_capable": True,
        "emotion_capable": False,
    },
}

# ===== 方言配置 =====
# 两类实现方式：
#   native：原生方言音色（不需要 instruction，天生说方言，直接替换 voice）
#   instruct：通过 longanhuan_v3 Instruct 指令实现（"请用XX话表达。"）

SUPPORTED_DIALECTS = {
    # 原生方言音色（无需 Instruct，直接使用专属音色）
    "cantonese":    {"label": "粤语", "mode": "native", "edge_voice": None},
    "minnan":       {"label": "闽南话", "mode": "native", "edge_voice": None},
    # Instruct 方言（通过 longanhuan_v3 指令实现）
    "northeastern": {"label": "东北话", "mode": "instruct", "instruct": "请用东北话表达。", "edge_voice": "zh-CN-liaoning-XiaobeiNeural"},
    "sichuan":      {"label": "四川话", "mode": "instruct", "instruct": "请用四川话表达。", "edge_voice": None},
    "henan":        {"label": "河南话", "mode": "instruct", "instruct": "请用河南话表达。", "edge_voice": None},
    "shandong":     {"label": "山东话", "mode": "instruct", "instruct": "请用山东话表达。", "edge_voice": None},
    "hunan":        {"label": "湖南话", "mode": "instruct", "instruct": "请用湖南话表达。", "edge_voice": None},
    "shaanxi":      {"label": "陕西话", "mode": "instruct", "instruct": "请用陕西话表达。", "edge_voice": "zh-CN-shaanxi-XiaoniNeural"},
}

# ===== 原生方言音色（不需要 instruction，天生说方言） =====
# 仅 cantonese / minnan 走此路径，选中即自动使用对应音色，无需用户切换模式
# 性别匹配：female 优先女声，male 优先男声

NATIVE_DIALECT_VOICES = {
    "cantonese": {"female": "longjiayi_v3", "male": "longanyue_v3"},
    "minnan": {"male": "longanmin_v3"},
}

# ===== 情感 Instruct 映射（仅 longanyang / 小太阳支持） =====
# 格式严格遵循官方文档："你正在进行闲聊互动，你说话的情感是<情感值>。"

EMOTION_INSTRUCT_MAP = {
    "neutral": "你正在进行闲聊互动，你说话的情感是neutral。",
    "happy": "你正在进行闲聊互动，你说话的情感是happy。",
    "sad": "你正在进行闲聊互动，你说话的情感是sad。",
    "angry": "你正在进行闲聊互动，你说话的情感是angry。",
    "fearful": "你正在进行闲聊互动，你说话的情感是fearful。",
    "surprised": "你正在进行闲聊互动，你说话的情感是surprised。",
    "disgusted": "你正在进行闲聊互动，你说话的情感是disgusted。",
    # 非标准情感值回退到 neutral
    "calm": "你正在进行闲聊互动，你说话的情感是neutral。",
    "anxious": "你正在进行闲聊互动，你说话的情感是fearful。",
    "excited": "你正在进行闲聊互动，你说话的情感是happy。",
}


def build_instruction(persona_cfg: dict, dialect: Optional[str], emotion: str,
                      persona_gender: str = "female", **kwargs) -> tuple[Optional[str], Optional[str]]:
    """
    构建 Instruct 指令 + 实际使用的 voice。
    返回 (instruction, override_voice)：
    - instruction: 传给 CosyVoice 的指令（None = 不传）
    - override_voice: 覆盖 persona 默认音色（原生方言音色，None = 用默认）

    规则（v4：取消 language_mode，原生方言自动生效）：
    1. 原生方言音色（cantonese/minnan）：dialect 命中 NATIVE_DIALECT_VOICES 时直接替换 voice，无需 instruction
    2. Instruct 方言：仅 dialect_capable 音色（qiqiu/longanhuan_v3）+ instruct 模式方言
    3. 情感 Instruct：仅 emotion_capable 音色（xiaotaiyang/longanyang），方言和情感互斥——方言优先
    """
    instruction = None
    override_voice = None

    # 原生方言音色自动生效（粤语/闽南话，无需用户切换模式）
    if dialect and dialect in NATIVE_DIALECT_VOICES:
        native_voices = NATIVE_DIALECT_VOICES[dialect]
        override_voice = native_voices.get(persona_gender) or next(iter(native_voices.values()))
        return None, override_voice

    # Instruct 方言（仅 dialect_capable 音色 + instruct 模式方言）
    if dialect and persona_cfg.get("dialect_capable"):
        dialect_info = SUPPORTED_DIALECTS.get(dialect)
        if dialect_info and dialect_info.get("mode") == "instruct":
            instruction = dialect_info["instruct"]

    # 情感 Instruct（仅 emotion_capable 音色，方言和情感互斥——方言优先）
    if not instruction and persona_cfg.get("emotion_capable") and emotion:
        instruction = EMOTION_INSTRUCT_MAP.get(emotion, EMOTION_INSTRUCT_MAP["neutral"])

    return instruction, None


# ===== 数据模型 =====

class TtsRequest(BaseModel):
    text: str                           # 要合成的文本
    persona: str = "xiaoxing"           # 音色人设
    emotion: str = "neutral"            # 孩子当前情绪（用于调整语气）
    speed: float = 1.0                  # 语速倍率（年龄适配）
    pitch: float = 1.0                  # 音高基调（TMATCH-001 prosody，<1 更低沉安抚）
    pause_style: int = 1                # 停顿风格（0=轻快 1=自然 2=多停顿安抚）
    dialect: Optional[str] = None       # 方言代码（可选，仅方言音色 qiqiu 生效）
    language_mode: str = "mandarin"     # [已废弃 v4] 保留向后兼容，不再生效


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

    # 构建 Instruct 指令 + 原生方言音色覆盖（v4：自动识别原生方言，无需 language_mode）
    instruction, override_voice = build_instruction(
        persona_cfg, req.dialect, req.emotion,
        persona_gender="female",  # qiqiu（方言载体）为女声
    )
    actual_voice = override_voice or persona_cfg["dashscope_voice"]

    logger.info(f"TTS 合成: text_len={len(req.text)}, persona={req.persona}, "
                f"emotion={req.emotion}, speed={final_speed:.2f}, dialect={req.dialect}, "
                f"voice={actual_voice}, instruction={instruction}")

    # Level 1：阿里云 CosyVoice
    if CLOUD_TTS_AVAILABLE:
        try:
            return await _synthesize_dashscope(req.text, actual_voice, final_speed, instruction)
        except Exception as e:
            if instruction:
                # Instruct 失败 → 无指令重试（保留音色品质）
                logger.warning(f"CosyVoice Instruct 失败，无指令重试: voice={actual_voice}, instruction={instruction}")
                try:
                    return await _synthesize_dashscope(req.text, actual_voice, final_speed, None)
                except Exception as e2:
                    logger.warning(f"CosyVoice 无指令重试也失败，降级 edge-tts: {e2}")
            else:
                logger.warning(f"阿里云 CosyVoice 失败，降级 edge-tts: {e}")

    # Level 2：edge-tts（方言降级：仅东北/陕西有对应音色，其他回退普通话）
    if EDGE_TTS_AVAILABLE:
        try:
            edge_voice = persona_cfg["edge_voice"]
            # 方言降级：检查是否有对应 edge-tts 方言音色
            if req.dialect:
                dialect_info = SUPPORTED_DIALECTS.get(req.dialect)
                if dialect_info and dialect_info.get("edge_voice"):
                    edge_voice = dialect_info["edge_voice"]
                # 无对应 edge 方言音色 → 用默认普通话 edge_voice
            return await _synthesize_edge_tts(req.text, edge_voice, final_speed, req.pitch)
        except Exception as e:
            logger.error(f"edge-tts 也失败: {e}")

    # Level 3：返回 503，前端自动降级到浏览器 speechSynthesis
    raise HTTPException(status_code=503, detail="TTS 服务不可用")


async def _synthesize_dashscope(text: str, voice: str, speed: float, instruction: Optional[str] = None):
    """
    Level 1：阿里云 CosyVoice（DashScope SDK WebSocket 流式合成）
    内部缓冲全部音频后返回，确保 SDK 错误能被上层 try/except 捕获并降级到 edge-tts。
    （单句 TTS 音频通常 20-50KB，缓冲无性能问题）
    """
    loop = asyncio.get_event_loop()
    queue: asyncio.Queue = asyncio.Queue()
    error_holder = [None]  # 用列表包装以便在回调中修改

    class _Callback(ResultCallback):
        def on_open(self):
            pass

        def on_data(self, data: bytes):
            loop.call_soon_threadsafe(queue.put_nowait, data)

        def on_complete(self):
            loop.call_soon_threadsafe(queue.put_nowait, None)

        def on_error(self, message):
            error_holder[0] = str(message)
            loop.call_soon_threadsafe(queue.put_nowait, None)

        def on_close(self):
            loop.call_soon_threadsafe(queue.put_nowait, None)

    def _run_synthesis():
        """SDK 的 call() 是阻塞的，在线程中执行"""
        try:
            kwargs = dict(
                model=DASHSCOPE_TTS_MODEL,
                voice=voice,
                format=AudioFormat.MP3_22050HZ_MONO_256KBPS,
                speech_rate=max(0.5, min(2.0, speed)),
                callback=_Callback(),
            )
            # 方言/情感 Instruct 指令（仅部分音色支持，不支持时 SDK 返回 428）
            if instruction:
                kwargs["instruction"] = instruction
            synthesizer = SpeechSynthesizer(**kwargs)
            synthesizer.call(text)
        except Exception as e:
            error_holder[0] = str(e)
            loop.call_soon_threadsafe(queue.put_nowait, None)

    # 启动合成线程
    t = threading.Thread(target=_run_synthesis, daemon=True)
    t.start()

    # 缓冲全部音频（等待合成完成或出错）
    chunks = []
    while True:
        chunk = await queue.get()
        if chunk is None:
            break
        chunks.append(chunk)

    # 错误检查：在返回前抛出异常，使上层 try/except 能捕获并降级到 edge-tts
    if error_holder[0]:
        logger.warning(f"CosyVoice SDK 错误 (voice={voice}, instruction={instruction}): {error_holder[0]}")
        raise RuntimeError(error_holder[0])

    audio_bytes = b"".join(chunks)
    if not audio_bytes:
        raise RuntimeError(f"CosyVoice 返回空音频 (voice={voice})")

    logger.info(f"CosyVoice 合成完成: voice={voice}, instruction={instruction}, bytes={len(audio_bytes)}")
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
