"""
MindSafe 语音分析微服务
- ASR（语音转文字）：SenseVoiceSmall
- SER（语音情感识别）：emotion2vec_plus_large
- 部署：Docker 容器，端口 10095
"""

import io
import logging
import re
import subprocess
import tempfile
import os
from concurrent.futures import ThreadPoolExecutor, as_completed

import numpy as np
import soundfile as sf
from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from funasr import AutoModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("voice-service")

app = FastAPI(title="MindSafe Voice Analysis", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ===== 模型初始化（启动时加载） =====

logger.info("正在加载 ASR 模型 (SenseVoiceSmall)...")
asr_model = AutoModel(
    model="iic/SenseVoiceSmall",
    vad_model="fsmn-vad",
    vad_kwargs={"max_single_segment_time": 30000},
    device="cpu",
)
logger.info("ASR 模型加载完成")

logger.info("正在加载 SER 模型 (emotion2vec+)...")
emotion_model = AutoModel(model="iic/emotion2vec_plus_large", device="cpu")
logger.info("SER 模型加载完成")

logger.info("✅ 语音分析服务就绪，端口 10095")


# ===== 数据模型 =====

class EmotionResult(BaseModel):
    label: str          # 情绪标签（中文）
    label_en: str       # 情绪标签（英文）
    confidence: float   # 置信度 0~1
    scores: list[float] # 9 类情绪得分


class VoiceAnalysisResponse(BaseModel):
    text: str                   # ASR 转写文字
    emotion: EmotionResult      # 情感分析结果
    duration_seconds: float     # 音频时长（秒）


# ===== 情绪映射 =====

EMOTION_LABELS = [
    ("angry", "愤怒"),
    ("disgusted", "厌恶"),
    ("fearful", "恐惧"),
    ("happy", "开心"),
    ("neutral", "中性"),
    ("other", "其他"),
    ("sad", "悲伤"),
    ("surprised", "惊讶"),
    ("unknown", "未知"),
]


def parse_emotion_result(raw: dict) -> EmotionResult:
    """解析 emotion2vec 输出"""
    scores = raw.get("scores", [0.0] * 9)
    # 确保 scores 是 float 列表
    scores = [float(s) for s in scores]
    max_idx = int(np.argmax(scores))
    label_en, label_cn = EMOTION_LABELS[max_idx]
    return EmotionResult(
        label=label_cn,
        label_en=label_en,
        confidence=scores[max_idx],
        scores=scores,
    )


# ===== API 端点 =====

@app.get("/health")
def health():
    return {"status": "UP", "models": ["SenseVoiceSmall", "emotion2vec_plus_large"]}


@app.post("/api/v1/voice/analyze", response_model=VoiceAnalysisResponse)
async def analyze_voice(file: UploadFile = File(...)):
    """
    分析语音文件：ASR 转文字 + 情感识别
    - 接收：audio/webm, audio/wav, audio/mp3 等
    - 返回：转写文字 + 情绪标签 + 置信度
    - 合规：处理完立即删除临时文件，不存储原始音频
    """
    if not file.content_type or not file.content_type.startswith("audio/"):
        raise HTTPException(status_code=400, detail="仅支持音频文件")

    # 读取音频到内存
    audio_bytes = await file.read()
    if len(audio_bytes) > 10 * 1024 * 1024:  # 10MB 限制
        raise HTTPException(status_code=400, detail="音频文件不能超过 10MB")

    tmp_path = None
    wav_path = None
    try:
        # 写入原始格式临时文件
        suffix = os.path.splitext(file.filename or "audio.webm")[1] or ".webm"
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp.write(audio_bytes)
            tmp_path = tmp.name

        # 用 ffmpeg 统一转为 16kHz 单声道 WAV
        # soundfile 不认识 webm/opus（安卓录音格式），先转 WAV 才能解码
        wav_fd, wav_path = tempfile.mkstemp(suffix=".wav")
        os.close(wav_fd)
        subprocess.run(
            ["ffmpeg", "-y", "-i", tmp_path, "-ar", "16000", "-ac", "1", wav_path],
            check=True,
            capture_output=True,
        )

        # 读取 WAV 获取时长
        audio_data, sample_rate = sf.read(wav_path)
        duration = len(audio_data) / sample_rate if sample_rate > 0 else 0.0

        # 1+2. ASR 转写 + 情感识别（并行执行，减少总延迟）
        def run_asr():
            result = asr_model.generate(input=wav_path, language="zh", use_itn=True)
            text = ""
            if result and len(result) > 0:
                text = result[0].get("text", "")
            # 清洗 SenseVoice 特殊标记
            return re.sub(r"<\|[^|]*\|>", "", text).strip()

        def run_ser():
            result = emotion_model.generate(input=wav_path)
            if result and len(result) > 0:
                return parse_emotion_result(result[0])
            return EmotionResult(label="未知", label_en="unknown", confidence=0.0, scores=[0.0] * 9)

        with ThreadPoolExecutor(max_workers=2) as executor:
            asr_future = executor.submit(run_asr)
            ser_future = executor.submit(run_ser)
            text = asr_future.result()
            emotion = ser_future.result()

        logger.info(f"分析完成: text_len={len(text)}, emotion={emotion.label_en}({emotion.confidence:.2f}), duration={duration:.1f}s")

        return VoiceAnalysisResponse(
            text=text,
            emotion=emotion,
            duration_seconds=round(duration, 2),
        )

    except subprocess.CalledProcessError as e:
        logger.error(f"音频转码失败: {e.stderr.decode(errors='ignore') if e.stderr else e}")
        raise HTTPException(status_code=500, detail="音频格式转换失败")
    except Exception as e:
        logger.error(f"语音分析失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"语音分析失败: {str(e)}")

    finally:
        # 合规（COMP-009 / 22 §6.3 转写即删）：ASR/SER 完成后立即删除原始音频临时文件，
        # 并留删除日志作审计凭据（仅记文件数，不记内容/路径以外信息）
        deleted = 0
        for p in (tmp_path, wav_path):
            if p and os.path.exists(p):
                try:
                    os.unlink(p)
                    deleted += 1
                except OSError as del_err:
                    logger.error(f"⚠️ 合规告警：音频临时文件删除失败 path={p}: {del_err}")
        logger.info(f"转写即删完成：已删除音频临时文件 {deleted} 个（不保留原始音频）")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=10095)
