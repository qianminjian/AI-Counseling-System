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
import subprocess
import tempfile
import os
import time
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager

import numpy as np
import soundfile as sf
from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from pydantic import BaseModel

from config import load_config
from asr_engines import ASRBackendError, DashScopeASRBackend, FunASRBackend
from metrics_common import Metrics

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("voice-service")

# ===== 配置加载（CFG-007：从 config.yaml 外置，回退内置默认值） =====

_CONFIG = load_config()

# ===== 引擎选择（环境变量唯一驱动；DA-10：默认值与 entrypoint/compose/.env.example 对齐 dashscope） =====
ASR_ENGINE = os.environ.get("ASR_ENGINE", "dashscope").lower()
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
# DA-03：counter+summary 公共结构复用 metrics_common（与 tts-service 复制共享），
# 指标名/标签是 alert-rules.yml 的隐式契约，改名需同步告警规则
_metrics = Metrics()


@app.get("/metrics")
def metrics():
    """Prometheus 文本格式指标（P1-10；服务仅在 internal 网络暴露，不公网开放）"""
    return Response(
        content=_metrics.render(
            counter_name="voice_analyze_requests_total",
            counter_help="Voice analyze requests total by result",
            label_key="result",
            summary_name="voice_analyze_duration_seconds",
            summary_help="Voice analyze duration in seconds",
            extra_lines=[
                "# HELP voice_asr_ready ASR engine readiness (1=ready 0=unavailable)",
                "# TYPE voice_asr_ready gauge",
                f"voice_asr_ready {1 if asr_model is not None or ASR_ENGINE == 'dashscope' else 0}",
                "# HELP voice_ser_ready SER model readiness (1=ready 0=unavailable)",
                "# TYPE voice_ser_ready gauge",
                f"voice_ser_ready {1 if emotion_model is not None else 0}",
                # DA-02：ser_enabled 与 ready 解耦——显式禁用（SER_ENABLED=false）不触发降级告警，
                # 仅「启用但加载失败」才告警（告警表达式 voice_ser_ready == 0 and voice_ser_enabled == 1）
                "# HELP voice_ser_enabled SER feature enabled (1=enabled 0=disabled via SER_ENABLED)",
                "# TYPE voice_ser_enabled gauge",
                f"voice_ser_enabled {1 if SER_ENABLED else 0}",
            ],
        ),
        media_type="text/plain; version=0.0.4; charset=utf-8",
    )

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

# ===== ASR 引擎装配（D2：适配器 seam，实现与测试见 asr_engines.py / test_asr_engines.py） =====
if ASR_ENGINE == "dashscope":
    _ASR_BACKEND = DashScopeASRBackend(
        model=_CONFIG["asr"]["dashscope_model"],
        api_key=DASHSCOPE_API_KEY,
    )
else:
    _ASR_BACKEND = FunASRBackend(model=asr_model)


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


# AUD-042：上传文件后缀白名单（防任意后缀写入临时目录）
_ALLOWED_AUDIO_SUFFIXES = {".webm", ".wav", ".mp3", ".m4a", ".ogg", ".opus", ".aac", ".flac"}


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
    """就绪探测（DA-02：纳入模型就绪判定，与 tts /health 降级语义同构）
    - UP：ASR 就绪，SER 就绪或显式禁用
    - DEGRADED：SER 启用但模型加载失败（情绪识别降级为中性，服务仍可用）
    - DOWN：ASR 未就绪（核心链路不可用；funasr 加载失败实际在启动期崩溃，此为防御语义）
    """
    asr_ready = asr_model is not None or ASR_ENGINE == "dashscope"
    ser_ready = emotion_model is not None
    if not asr_ready:
        status = "DOWN"
    elif not ser_ready and SER_ENABLED:
        status = "DEGRADED"
    else:
        status = "UP"
    return {
        "status": status,
        "asr_engine": ASR_ENGINE,
        "asr_model": "SenseVoiceSmall" if ASR_ENGINE == "funasr" else "DashScope-Paraformer-V2",
        "ser_model": "emotion2vec_plus_large" if emotion_model is not None else "disabled",
        "asr_ready": int(asr_ready),
        "ser_ready": int(ser_ready),
    }


# ===== ASR 实现（D2：已收敛至 asr_engines.py 适配器层，端点经 _ASR_BACKEND.transcribe 调用） =====


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
        # 写入原始格式临时文件（AUD-042：后缀白名单，拒绝任意后缀写入临时目录）
        suffix = os.path.splitext(file.filename or "audio.webm")[1].lower() or ".webm"
        if suffix not in _ALLOWED_AUDIO_SUFFIXES:
            raise HTTPException(status_code=400, detail="不支持的音频格式")
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
        asr_fn = _ASR_BACKEND.transcribe

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

        _metrics.record("success", time.time() - t_start)
        return VoiceAnalysisResponse(
            text=text,
            emotion=emotion,
            duration_seconds=round(duration, 2),
        )

    except subprocess.CalledProcessError as e:
        _metrics.record("error", time.time() - t_start)
        logger.error(f"音频转码失败: {e.stderr.decode(errors='ignore') if e.stderr else e}")
        raise HTTPException(status_code=500, detail="音频格式转换失败")
    except subprocess.TimeoutExpired:
        _metrics.record("timeout", time.time() - t_start)
        logger.error(f"ffmpeg 转码超时 ({VOICE_PROCESS_TIMEOUT}s)")
        raise HTTPException(status_code=504, detail="音频转码超时")
    except TimeoutError:
        # concurrent.futures.TimeoutError = builtins.TimeoutError（3.11+）
        _metrics.record("timeout", time.time() - t_start)
        logger.error(f"ASR/SER 分析超时 ({VOICE_ANALYZE_TIMEOUT}s)")
        raise HTTPException(status_code=504, detail="语音分析超时")
    except ASRBackendError as e:
        # D2：上游 DashScope ASR 错误（非 200 / SDK 缺失）→ 502（原 _dashscope_asr 语义）
        _metrics.record("error", time.time() - t_start)
        logger.error(f"DashScope ASR 服务错误: {e}")
        raise HTTPException(status_code=502, detail=f"DashScope ASR 服务错误: {e}")
    except HTTPException:
        _metrics.record("error", time.time() - t_start)
        raise  # 已包装的异常直接抛出
    except Exception as e:
        _metrics.record("error", time.time() - t_start)
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
