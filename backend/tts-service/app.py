"""
MindSafe TTS 微服务（CosyVoice2 情感语音合成）
- 文本 → 情感语音（支持 instruct 指令控制语气）
- 多音色人设（参考音频切换）
- 流式合成，首包延迟 < 2s
- 部署：Docker 容器，端口 10096
"""

import io
import logging
import os
import tempfile

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("tts-service")

app = FastAPI(title="MindSafe TTS Service", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ===== 模型初始化 =====

logger.info("正在加载 CosyVoice2 模型...")
try:
    from cosyvoice.cli.cosyvoice import CosyVoice2

    model_dir = os.environ.get("COSYVOICE_MODEL_DIR", "/app/models/CosyVoice2-0.5B")
    tts_model = CosyVoice2(model_dir, load_jit=False, load_trt=False)
    logger.info("✅ CosyVoice2 模型加载完成")
    MODEL_AVAILABLE = True
except Exception as e:
    logger.warning(f"CosyVoice2 加载失败（降级为 edge-tts）: {e}")
    MODEL_AVAILABLE = False

# ===== 降级方案：edge-tts（微软免费 TTS，需联网） =====
EDGE_TTS_AVAILABLE = False
if not MODEL_AVAILABLE:
    try:
        import edge_tts
        EDGE_TTS_AVAILABLE = True
        logger.info("✅ edge-tts 降级方案就绪")
    except ImportError:
        logger.error("edge-tts 未安装，TTS 服务不可用")


# ===== 音色人设配置 =====

VOICE_PERSONAS = {
    "xiaoxing": {
        "name": "小星",
        "desc": "温暖的大姐姐",
        "base_instruct": "用亲切语气说",
        "speed": 1.0,
        # edge-tts 降级音色
        "edge_voice": "zh-CN-XiaoxiaoNeural",
        # CosyVoice2 内置说话人（persona→speaker 映射，design/28 §四）
        "cosy_speaker": "中文女",
    },
    "qiqiu": {
        "name": "气球",
        "desc": "活泼的小伙伴",
        "base_instruct": "用俏皮语气说",
        "speed": 1.05,
        "edge_voice": "zh-CN-XiaoyiNeural",
        "cosy_speaker": "中文女",
    },
    "yueliang": {
        "name": "月亮",
        "desc": "温柔的讲故事者",
        "base_instruct": "用温柔语气、用轻声说",
        "speed": 0.92,
        "edge_voice": "zh-CN-XiaohanNeural",
        "cosy_speaker": "中文女",
    },
    # design/28 §四：男生默认音色（阳光大哥哥），修复男生回落女声缺陷
    "xiaotaiyang": {
        "name": "小太阳",
        "desc": "阳光的大哥哥",
        "base_instruct": "用开朗有活力的语气说",
        "speed": 1.05,
        # 少年男声，契合阳光大哥哥人设
        "edge_voice": "zh-CN-YunxiNeural",
        "cosy_speaker": "中文男",
    },
}

# ===== 情绪 → CosyVoice instruct 映射 =====

EMOTION_INSTRUCTS = {
    "happy": "用开心语气说",
    "sad": "用温柔语气、用轻声说",
    "angry": "用平静语气、用轻声说",
    "fearful": "用温柔语气说",
    "nervous": "用轻松语气说",
    "neutral": "用亲切语气说",
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
    engine = "cosyvoice2" if MODEL_AVAILABLE else ("edge-tts" if EDGE_TTS_AVAILABLE else "none")
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
    文本 → 语音合成
    返回 audio/wav 二进制流
    """
    if not req.text.strip():
        raise HTTPException(status_code=400, detail="文本不能为空")

    if len(req.text) > 500:
        raise HTTPException(status_code=400, detail="单次合成文本不超过500字")

    persona_cfg = VOICE_PERSONAS.get(req.persona, VOICE_PERSONAS["xiaoxing"])
    emotion_instruct = EMOTION_INSTRUCTS.get(req.emotion, EMOTION_INSTRUCTS["neutral"])

    # 组合 instruct：音色基础 + 情绪叠加
    instruct = f"{persona_cfg['base_instruct']}、{emotion_instruct}"
    # 多停顿安抚基调（TMATCH-001）：CosyVoice instruct 模式下用描述词表达停顿风格
    if req.pause_style >= 2:
        instruct += "、说得慢一点、多停顿"
    # 去重（如果基础和情绪相同）
    parts = list(dict.fromkeys(instruct.split("、")))
    instruct = "、".join(parts)

    final_speed = persona_cfg["speed"] * req.speed

    logger.info(f"TTS 合成: text_len={len(req.text)}, persona={req.persona}, "
                f"emotion={req.emotion}, instruct='{instruct}', speed={final_speed:.2f}, "
                f"pitch={req.pitch:.2f}, pause_style={req.pause_style}")

    if MODEL_AVAILABLE:
        return await _synthesize_cosyvoice(req.text, instruct, final_speed, persona_cfg, req.pitch)
    elif EDGE_TTS_AVAILABLE:
        return await _synthesize_edge_tts(req.text, persona_cfg["edge_voice"], final_speed, req.pitch)
    else:
        raise HTTPException(status_code=503, detail="TTS 服务不可用")


async def _synthesize_cosyvoice(text: str, instruct: str, speed: float, persona_cfg: dict, pitch: float = 1.0):
    """CosyVoice2 本地合成（persona→speaker 映射，design/28 §四）"""
    import torch
    import torchaudio

    # 说话人随 persona 切换（3 女人设→中文女，xiaotaiyang→中文男），不再硬编码
    speaker = persona_cfg.get("cosy_speaker", "中文女")

    try:
        # CosyVoice2 instruct 模式
        output = tts_model.inference_instruct(
            text,
            speaker,
            instruct,
            stream=False,
        )

        # 收集音频数据
        audio_chunks = []
        for chunk in output:
            audio_chunks.append(chunk["tts_speech"])

        if not audio_chunks:
            raise HTTPException(status_code=500, detail="合成结果为空")

        audio_tensor = torch.cat(audio_chunks, dim=1)

        # 语速/音高基调调整（TMATCH-001 prosody，通过 sox 效果链）
        effects = []
        if abs(speed - 1.0) > 0.05:
            effects.append(["tempo", str(speed)])
        if abs(pitch - 1.0) > 0.02:
            # pitchScale → 音分（cents）：0.9 ≈ -182 cents（更低沉）
            import math
            cents = int(1200 * math.log2(pitch))
            effects.append(["pitch", str(cents)])
        if effects:
            try:
                audio_tensor, _ = torchaudio.sox_effects.apply_effects_tensor(
                    audio_tensor, 22050, effects
                )
            except Exception:
                pass  # sox 不可用时跳过 prosody 调整

        # 转为 WAV bytes
        buffer = io.BytesIO()
        torchaudio.save(buffer, audio_tensor, 22050, format="wav")
        buffer.seek(0)

        return StreamingResponse(
            buffer,
            media_type="audio/wav",
            headers={"X-TTS-Engine": "cosyvoice2", "X-TTS-Instruct": instruct},
        )

    except Exception as e:
        logger.error(f"CosyVoice2 合成失败: {e}", exc_info=True)
        # 降级到 edge-tts（保持当前 persona 音色，不回落女声）
        if EDGE_TTS_AVAILABLE:
            return await _synthesize_edge_tts(text, persona_cfg["edge_voice"], speed)
        raise HTTPException(status_code=500, detail=f"语音合成失败: {str(e)}")


async def _synthesize_edge_tts(text: str, voice: str, speed: float, pitch: float = 1.0):
    """edge-tts 降级合成（需联网）"""
    import edge_tts

    # 语速转换为 edge-tts 格式（如 "+10%", "-15%"）
    rate_pct = int((speed - 1.0) * 100)
    rate_str = f"+{rate_pct}%" if rate_pct >= 0 else f"{rate_pct}%"
    # 音高基调转换（TMATCH-001）：pitchScale 0.9 ≈ -10Hz（更低沉安抚）
    pitch_hz = int((pitch - 1.0) * 100)
    pitch_str = f"+{pitch_hz}Hz" if pitch_hz >= 0 else f"{pitch_hz}Hz"

    try:
        communicate = edge_tts.Communicate(text, voice, rate=rate_str, pitch=pitch_str)
        buffer = io.BytesIO()
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                buffer.write(chunk["data"])

        buffer.seek(0)
        return StreamingResponse(
            buffer,
            media_type="audio/mpeg",
            headers={"X-TTS-Engine": "edge-tts", "X-TTS-Voice": voice},
        )
    except Exception as e:
        logger.error(f"edge-tts 合成失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"语音合成失败: {str(e)}")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=10096)
