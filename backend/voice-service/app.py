"""
MindSafe 语音分析微服务
- ASR（语音转文字）：双引擎
  - funasr 模式：SenseVoiceSmall（自部署，适合私有化/信创环境）
  - dashscope 模式：Paraformer-V2（阿里云 DashScope API，低资源占用）
- SER（语音情感识别）：emotion2vec_plus_large（独立于 ASR，SER_ENABLED 控制）
- ASR 与 SER 并行执行（ThreadPoolExecutor）
- 部署：Docker 容器，端口 10095
- 切换方式：环境变量 ASR_ENGINE=funasr|dashscope，SER_ENABLED=true|false
"""

import io
import logging
import re
import subprocess
import tempfile
import threading
import os
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from contextlib import asynccontextmanager

import numpy as np
import soundfile as sf
from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from pydantic import BaseModel

from config import load_config

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("voice-service")

# ===== 配置加载（CFG-007：从 config.yaml 外置，回退内置默认值） =====

_CONFIG = load_config()

# ===== 引擎选择（环境变量驱动） =====
ASR_ENGINE = os.environ.get("ASR_ENGINE", "funasr").lower()
SER_ENABLED = os.environ.get("SER_ENABLED", "true").lower() == "true"
DASHSCOPE_API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")

# 单请求超时（P1-DEP：ffmpeg 转码 / ASR / SER 挂死时避免请求永久挂起；超时返回 504）
VOICE_PROCESS_TIMEOUT = float(os.environ.get("VOICE_PROCESS_TIMEOUT", "30"))   # ffmpeg 转码超时（秒）
VOICE_ANALYZE_TIMEOUT = float(os.environ.get("VOICE_ANALYZE_TIMEOUT", "60"))   # ASR/SER 分析超时（秒）

if ASR_ENGINE not in ("funasr", "dashscope"):
    raise ValueError(f"ASR_ENGINE 必须为 funasr 或 dashscope，当前值: {ASR_ENGINE}")

if ASR_ENGINE == "dashscope" and not DASHSCOPE_API_KEY:
    raise ValueError("ASR_ENGINE=dashscope 时 DASHSCOPE_API_KEY 不能为空")

# ===== 分析线程池（AUD-016：进程级单例，避免每请求新建/销毁线程；退出时由 lifespan 回收） =====
# max_workers=4：ASR/SER 均以 CPU 推理为主（funasr/SER 本地模型），过多线程反而抢 CPU；
# 单例池让并发请求共享线程，省去每请求创建/销毁开销
_ANALYZE_EXECUTOR = ThreadPoolExecutor(max_workers=4, thread_name_prefix="voice-analyze")


@asynccontextmanager
async def _lifespan(_: FastAPI):
    """AUD-016：应用退出时回收分析线程池（不再每请求 shutdown）"""
    yield
    _ANALYZE_EXECUTOR.shutdown(wait=False, cancel_futures=True)


app = FastAPI(title="MindSafe Voice Analysis", version="2.1.0", lifespan=_lifespan)

# ===== Prometheus 指标（P1-10：手写文本格式，零新增依赖，供监控栈 internal 网络抓取） =====
_METRICS_LOCK = threading.Lock()
_VOICE_REQUESTS: dict = {}    # result -> 请求总数（result: success/error/timeout）
_VOICE_DURATION_SUM = 0.0     # 分析耗时总和（秒，summary 的 _sum）
_VOICE_DURATION_COUNT = 0     # 分析次数（summary 的 _count）


def _record_voice_request(result: str, duration_sec: float):
    """记录一次语音分析请求结果（线程安全）"""
    global _VOICE_DURATION_SUM, _VOICE_DURATION_COUNT
    with _METRICS_LOCK:
        _VOICE_REQUESTS[result] = _VOICE_REQUESTS.get(result, 0) + 1
        _VOICE_DURATION_SUM += duration_sec
        _VOICE_DURATION_COUNT += 1


@app.get("/metrics")
def metrics():
    """Prometheus 文本格式指标（P1-10；服务仅在 internal 网络暴露，不公网开放）"""
    out = [
        "# HELP voice_analyze_requests_total Voice analyze requests total by result",
        "# TYPE voice_analyze_requests_total counter",
    ]
    with _METRICS_LOCK:
        for result in sorted(_VOICE_REQUESTS):
            out.append(f'voice_analyze_requests_total{{result="{result}"}} {_VOICE_REQUESTS[result]}')
        out += [
            "# HELP voice_analyze_duration_seconds Voice analyze duration in seconds",
            "# TYPE voice_analyze_duration_seconds summary",
            f"voice_analyze_duration_seconds_sum {_VOICE_DURATION_SUM:.6f}",
            f"voice_analyze_duration_seconds_count {_VOICE_DURATION_COUNT}",
        ]
    out += [
        "# HELP voice_asr_ready ASR engine readiness (1=ready 0=unavailable)",
        "# TYPE voice_asr_ready gauge",
        f"voice_asr_ready {1 if asr_model is not None or ASR_ENGINE == 'dashscope' else 0}",
        "# HELP voice_ser_ready SER model readiness (1=ready 0=unavailable)",
        "# TYPE voice_ser_ready gauge",
        f"voice_ser_ready {1 if emotion_model is not None else 0}",
    ]
    return Response(content="\n".join(out) + "\n", media_type="text/plain; version=0.0.4; charset=utf-8")

# CORS 白名单（安全收敛）：默认拒绝跨域（本服务仅由同网络后端调用）；
# 确需前端直连时用 VOICE_CORS_ORIGINS="https://a.com,https://b.com" 显式声明，禁止 *
_cors_env = os.getenv("VOICE_CORS_ORIGINS", "").strip()
_allowed_origins = [o.strip() for o in _cors_env.split(",") if o.strip()]
app.add_middleware(
    CORSMiddleware,
    allow_origins=_allowed_origins,
    allow_methods=["POST", "GET"],
    allow_headers=["Content-Type"],
)

# ===== 模型初始化（ASR 与 SER 解耦） =====

asr_model = None
emotion_model = None

if ASR_ENGINE == "funasr":
    from funasr import AutoModel

    logger.info("正在加载 ASR 模型 (%s)...", _CONFIG["asr"]["funasr_model"])
    asr_model = AutoModel(
        model=_CONFIG["asr"]["funasr_model"],
        vad_model=_CONFIG["asr"]["vad_model"],
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
        logger.info("正在加载 SER 模型 (%s)...", _CONFIG["ser"]["model"])
        emotion_model = AutoModel(model=_CONFIG["ser"]["model"], device="cpu")
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


# ===== 情绪映射（从 config.yaml 加载） =====

EMOTION_LABELS = [tuple(item) for item in _CONFIG["emotion_labels"]]


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
        model=_CONFIG["asr"]["dashscope_model"],
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
    t_start = time.time()
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
            timeout=VOICE_PROCESS_TIMEOUT,  # P1-DEP：转码挂死时不再永久挂起
        )

        # 读取 WAV 获取时长
        audio_data, sample_rate = sf.read(wav_path)
        duration = len(audio_data) / sample_rate if sample_rate > 0 else 0.0

        # ===== ASR + SER 并行执行 =====
        asr_fn = _dashscope_asr if ASR_ENGINE == "dashscope" else _funasr_asr

        if emotion_model is not None:
            # ASR 和 SER 并行（ASR 可能是网络IO或CPU，SER 是CPU，并行提升响应速度）
            # P1-DEP：result(timeout) 防挂死 → 外层 except TimeoutError 返回 504
            # AUD-016：复用进程级单例线程池 _ANALYZE_EXECUTOR（不再每请求新建/销毁）；
            # 超时后 future.cancel()（Python 线程不可强停，但可阻止排队任务启动并释放引用）
            asr_future = _ANALYZE_EXECUTOR.submit(asr_fn, wav_path)
            ser_future = _ANALYZE_EXECUTOR.submit(_funasr_ser, wav_path)
            try:
                text = asr_future.result(timeout=VOICE_ANALYZE_TIMEOUT)
                emotion = ser_future.result(timeout=VOICE_ANALYZE_TIMEOUT)
            except TimeoutError:
                asr_future.cancel()
                ser_future.cancel()
                raise
        else:
            text = asr_fn(wav_path)
            emotion = EmotionResult(label="中性", label_en="neutral", confidence=0.0, scores=[0.0] * 9)

        logger.info(f"分析完成 [ASR={ASR_ENGINE}, SER={'on' if emotion_model else 'off'}]: "
                    f"text_len={len(text)}, emotion={emotion.label_en}({emotion.confidence:.2f}), duration={duration:.1f}s")

        _record_voice_request("success", time.time() - t_start)
        return VoiceAnalysisResponse(
            text=text,
            emotion=emotion,
            duration_seconds=round(duration, 2),
        )

    except subprocess.CalledProcessError as e:
        _record_voice_request("error", time.time() - t_start)
        logger.error(f"音频转码失败: {e.stderr.decode(errors='ignore') if e.stderr else e}")
        raise HTTPException(status_code=500, detail="音频格式转换失败")
    except subprocess.TimeoutExpired:
        _record_voice_request("timeout", time.time() - t_start)
        logger.error(f"ffmpeg 转码超时 ({VOICE_PROCESS_TIMEOUT}s)")
        raise HTTPException(status_code=504, detail="音频转码超时")
    except TimeoutError:
        # concurrent.futures.TimeoutError = builtins.TimeoutError（3.11+）
        _record_voice_request("timeout", time.time() - t_start)
        logger.error(f"ASR/SER 分析超时 ({VOICE_ANALYZE_TIMEOUT}s)")
        raise HTTPException(status_code=504, detail="语音分析超时")
    except HTTPException:
        _record_voice_request("error", time.time() - t_start)
        raise  # DashScope 502 等已包装的异常直接抛出
    except Exception as e:
        _record_voice_request("error", time.time() - t_start)
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
