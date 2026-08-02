"""
MindSafe 语音分析微服务
- ASR（语音转文字）：双引擎
  - funasr 模式：SenseVoiceSmall（自部署，适合私有化/信创环境）
  - dashscope 模式：Paraformer-V2（阿里云 DashScope API，低资源占用）
- SER（语音情感识别）：emotion2vec_plus_large（仅 funasr 模式加载）
- 部署：Docker 容器，端口 10095
- 切换方式：环境变量 ASR_ENGINE=funasr|dashscope
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

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("voice-service")

# ===== 引擎选择（环境变量驱动） =====
ASR_ENGINE = os.environ.get("ASR_ENGINE", "funasr").lower()
SER_ENABLED = os.environ.get("SER_ENABLED", "true").lower() == "true"
DASHSCOPE_API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")

if ASR_ENGINE not in ("funasr", "dashscope"):
    raise ValueError(f"ASR_ENGINE 必须为 funasr 或 dashscope，当前值: {ASR_ENGINE}")

if ASR_ENGINE == "dashscope" and not DASHSCOPE_API_KEY:
    raise ValueError("ASR_ENGINE=dashscope 时 DASHSCOPE_API_KEY 不能为空")

app = FastAPI(title="MindSafe Voice Analysis", version="2.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ===== 模型初始化（ASR 与 SER 解耦） =====

asr_model = None
emotion_model = None

if ASR_ENGINE == "funasr":
    from funasr import AutoModel

    logger.info("正在加载 ASR 模型 (SenseVoiceSmall)...")
    asr_model = AutoModel(
        model="iic/SenseVoiceSmall",
        vad_model="fsmn-vad",
        vad_kwargs={"max_single_segment_time": 30000},
        device="cpu",
    )
    logger.info("ASR 模型加载完成")

elif ASR_ENGINE == "dashscope":
    import dashscope
    from dashscope.audio.asr import Recognition, RecognitionCallback, RecognitionResult

    dashscope.api_key = DASHSCOPE_API_KEY
    logger.info("DashScope Paraformer-V2 ASR 就绪")
    logger.info("  DashScope API Key: ...%s", DASHSCOPE_API_KEY[-6:] if len(DASHSCOPE_API_KEY) > 6 else "***")

# SER（emotion2vec+）独立于 ASR 引擎加载：无论 ASR 走云端还是本地，
# 只要 SER_ENABLED=true 且资源允许就加载本地情感模型
if SER_ENABLED:
    try:
        if ASR_ENGINE != "funasr":
            from funasr import AutoModel  # dashscope 模式也需要 funasr 包来加载 emotion2vec
        logger.info("正在加载 SER 模型 (emotion2vec+)...")
        emotion_model = AutoModel(model="iic/emotion2vec_plus_large", device="cpu")
        logger.info("SER 模型加载完成")
    except Exception as e:
        logger.warning(f"⚠️ SER 模型加载失败（降级为中性情绪）: {e}")
        emotion_model = None
else:
    logger.info("SER 已通过 SER_ENABLED=false 显式禁用")

logger.info(f"✅ 语音分析服务就绪 [ASR={ASR_ENGINE}, SER={'emotion2vec+' if emotion_model else 'disabled'}]，端口 10095")


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
    asr_model_name = "SenseVoiceSmall" if ASR_ENGINE == "funasr" else "DashScope-Paraformer-V2"
    ser_model_name = "emotion2vec_plus_large" if emotion_model is not None else "disabled"
    return {"status": "UP", "asr_engine": ASR_ENGINE, "asr_model": asr_model_name, "ser_model": ser_model_name}


# ===== DashScope ASR 实现 =====


def _dashscope_asr(wav_path: str) -> str:
    """通过 DashScope Recognition SDK 进行语音转写（Paraformer-V2，WebSocket 协议，同步模式）"""
    recognition = Recognition(
        model="paraformer-realtime-v2",
        format="wav",
        sample_rate=16000,
        callback=RecognitionCallback(),  # no-op callback, call() 模式同步返回
    )
    result = recognition.call(file=wav_path)

    if result.status_code != 200:
        logger.error(f"DashScope ASR 调用失败: status={result.status_code}, code={result.code}, msg={result.message}")
        raise HTTPException(status_code=502, detail=f"DashScope ASR 服务错误: {result.message}")

    # 提取所有句子文本拼接
    sentences = result.get_sentence() if hasattr(result, 'get_sentence') else []
    if isinstance(sentences, dict):
        sentences = [sentences]
    text = "".join(s.get("text", "") for s in (sentences or []))
    logger.info(f"DashScope ASR 转写完成: text_len={len(text)}, sentences={len(sentences or [])}")
    return text.strip()


# ===== FunASR 本地实现 =====

def _funasr_asr(wav_path: str) -> str:
    """本地 FunASR SenseVoiceSmall 转写"""
    result = asr_model.generate(input=wav_path, language="zh", use_itn=True)
    text = ""
    if result and len(result) > 0:
        text = result[0].get("text", "")
    # 清洗 SenseVoice 特殊标记
    return re.sub(r"<\|[^|]*\|>", "", text).strip()


def _funasr_ser(wav_path: str) -> EmotionResult:
    """本地 emotion2vec+ 情感识别"""
    result = emotion_model.generate(input=wav_path)
    if result and len(result) > 0:
        return parse_emotion_result(result[0])
    return EmotionResult(label="未知", label_en="unknown", confidence=0.0, scores=[0.0] * 9)


@app.post("/api/v1/voice/analyze", response_model=VoiceAnalysisResponse)
async def analyze_voice(file: UploadFile = File(...)):
    """
    分析语音文件：ASR 转文字 + 情感识别
    - 接收：audio/webm, audio/wav, audio/mp3 等
    - 返回：转写文字 + 情绪标签 + 置信度
    - 合规：处理完立即删除临时文件，不存储原始音频
    - 引擎：由 ASR_ENGINE 环境变量控制（funasr=本地 / dashscope=云端）
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

        # ===== ASR + SER 并行执行 =====
        asr_fn = _dashscope_asr if ASR_ENGINE == "dashscope" else _funasr_asr

        if emotion_model is not None:
            # ASR 和 SER 并行（ASR 可能是网络IO或CPU，SER 是CPU，并行提升响应速度）
            with ThreadPoolExecutor(max_workers=2) as executor:
                asr_future = executor.submit(asr_fn, wav_path)
                ser_future = executor.submit(_funasr_ser, wav_path)
                text = asr_future.result()
                emotion = ser_future.result()
        else:
            text = asr_fn(wav_path)
            emotion = EmotionResult(label="中性", label_en="neutral", confidence=0.0, scores=[0.0] * 9)

        logger.info(f"分析完成 [ASR={ASR_ENGINE}, SER={'on' if emotion_model else 'off'}]: "
                    f"text_len={len(text)}, emotion={emotion.label_en}({emotion.confidence:.2f}), duration={duration:.1f}s")

        return VoiceAnalysisResponse(
            text=text,
            emotion=emotion,
            duration_seconds=round(duration, 2),
        )

    except subprocess.CalledProcessError as e:
        logger.error(f"音频转码失败: {e.stderr.decode(errors='ignore') if e.stderr else e}")
        raise HTTPException(status_code=500, detail="音频格式转换失败")
    except HTTPException:
        raise  # DashScope 502 等已包装的异常直接抛出
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
