"""
MindSafe TTS 微服务（DC-011 适配器层 + 降级策略，v4）
- Level 1：阿里云百炼 CosyVoice（DashScopeBackend，SDK WebSocket 流式合成，首包 <800ms）
- Level 2：edge-tts（EdgeBackend，微软备用）
- Level 3：前端浏览器 speechSynthesis 兜底（本服务返回 503 时前端自动降级）
- 引擎实现细节全部下沉 tts_engines.py / tts_policy.py，本文件仅装配与编排
- 部署：Docker 容器，端口 10096
"""

import io
import json
import logging
import os
import time
from typing import Optional

import httpx
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response, StreamingResponse
from pydantic import BaseModel, ConfigDict

from tts_engines import DashScopeBackend, EdgeBackend
from tts_policy import DegradationPolicy, TTSSynthesisFailed
from config_loader import load_config as loader_load_config
from metrics_common import Metrics

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("tts-service")

# 全局复用 httpx 客户端（edge-tts 降级时用）
http_client: httpx.AsyncClient = None


@asynccontextmanager
async def _lifespan(_: FastAPI):
    """AUD-042：用 lifespan 替代弃用的 @app.on_event（FastAPI 推荐生命周期管理，退出时确保 http_client 回收）"""
    global http_client
    http_client = httpx.AsyncClient(timeout=20.0, limits=httpx.Limits(max_connections=20))
    yield
    if http_client:
        await http_client.aclose()


app = FastAPI(title="MindSafe TTS Service", version="4.0.0", lifespan=_lifespan)


@app.exception_handler(Exception)
async def _unhandled_exception_handler(request, exc: Exception):
    """R-6：全局兑底 handler——未捕获异常返回结构化 500，不泄漏内部细节（防敏感信息回显）"""
    logger.error("未捕获异常: %s %s -> %s: %s", request.method, request.url.path,
                 type(exc).__name__, exc, exc_info=True)
    return JSONResponse(status_code=500, content={"detail": "TTS 服务内部错误"})

# ===== Prometheus 指标（P1-10：手写文本格式，零新增依赖，供监控栈 internal 网络抓取） =====
# DA-03：counter+summary 公共结构复用 metrics_common（与 voice-service 复制共享 ），
# 指标名/标签是 alert-rules.yml 的隐式契约，改名需同步告警规则
_metrics = Metrics()
# OPS-MON-002（BUG-TTS-01 复盘）：运行期降级事件计数器——独立实例（单 label 结构限制），
# duration 传 0.0 不污染原 summary；TtsDegradeRatioHigh 规则硬依赖此指标
_degraded_metrics = Metrics()


@app.get("/metrics")
def metrics():
    """Prometheus 文本格式指标（P1-10；服务仅在 internal 网络暴露，不公网开放）"""
    return Response(
        content=_metrics.render(
            counter_name="tts_synthesize_requests_total",
            counter_help="TTS synthesize requests total by final engine/result",
            label_key="engine",
            summary_name="tts_synthesize_duration_seconds",
            summary_help="TTS synthesize duration in seconds",
            extra_lines=[
                "# HELP tts_engine_available TTS engine availability (1=ready 0=unavailable)",
                "# TYPE tts_engine_available gauge",
                f'tts_engine_available{{engine="cosyvoice"}} {1 if _TTS_POLICY.backends[0].is_available() else 0}',
                f'tts_engine_available{{engine="edge_tts"}} {1 if _TTS_POLICY.backends[1].is_available() else 0}',
            ],
        )
        + _degraded_metrics.render(
            counter_name="tts_degraded_events_total",
            counter_help="TTS runtime degradation events (primary engine failed but fallback served)",
            label_key="direction",
            summary_name="tts_degraded_duration_seconds",
            summary_help="TTS degradation event durations (counter-only, always 0)",
        ),
        media_type="text/plain; version=0.0.4; charset=utf-8",
    )

# 单次合成超时（P1-DEP：SDK 线程挂死/网络黑洞时避免请求永久挂起；超时后自动降级或 503）
TTS_SYNTHESIZE_TIMEOUT = float(os.environ.get("TTS_SYNTHESIZE_TIMEOUT", "30"))

# CORS 白名单（安全收敛）：默认拒绝跨域（本服务仅由同网络后端调用）；
# 确需前端直连时用 TTS_CORS_ORIGINS="https://a.com,https://b.com" 显式声明，禁止 *
_cors_env = os.getenv("TTS_CORS_ORIGINS", "").strip()
_allowed_origins = [o.strip() for o in _cors_env.split(",") if o.strip()]
app.add_middleware(
    CORSMiddleware,
    allow_origins=_allowed_origins,
    allow_methods=["POST", "GET"],
    allow_headers=["Content-Type"],
)

# ===== 配置加载（CFG-004：config.yaml 外置，回退内置默认值；DOC-073 D1 深合并单源化） =====

# 最小兜底配置（config.yaml 缺失时保证服务可启动；完整默认矩阵以 config.yaml 为权威单源，
# 不再与代码逐字复制——改配置只改 yaml，免重建）
# DA-14：矩阵类兜底补最小运行项——synthesize 直索引 VOICE_PERSONAS["xiaoxing"]（默认 persona）
# 与 EMOTION_INSTRUCT_MAP["neutral"]（情感直索引），空矩阵时“可启动但运行即 500”
_FALLBACK_CONFIG = {
    "model": {
        "dashscope": "cosyvoice-v3-flash",
        "edge_fallback": True,
    },
    "voice_personas": {
        "xiaoxing": {
            "name": "小星",
            "desc": "温暖的邻家姐姐",
            "gender": "female",
            "speed": 1.0,
            "dashscope_voice": "longxing_v3",
            "edge_voice": "zh-CN-XiaoxiaoNeural",
            "dialect_capable": False,
            "emotion_capable": False,
        },
    },
    "dialects": {},
    "native_dialect_voices": {},
    "emotion_instruct_map": {
        "neutral": "你正在进行闲聊互动，你说话的情感是neutral。",
    },
}


def _validate_runtime_contract(config: dict) -> None:
    """DA-14：启动契约校验（fail-fast）。

    config.yaml 缺失/损坏时由兜底矩阵兜底；但 yaml 显式置 null 或提供部分矩阵时
    （deep_merge：list/标量整体替换、dict 深合并）兜底可能被覆盖/缺键——synthesize 直索引
    键必 KeyError。启动期即拒绝，不做“带病运行”。
    """
    personas = config.get("voice_personas") or {}
    if "xiaoxing" not in personas:
        raise RuntimeError(
            "TTS 配置缺失默认音色 xiaoxing（synthesize 默认 persona 直索引键），拒绝启动")
    instructs = config.get("emotion_instruct_map") or {}
    if "neutral" not in instructs:
        raise RuntimeError(
            "TTS 配置缺失 neutral 情感指令（build_instruction 直索引键），拒绝启动")


def load_config(config_path: str = None) -> dict:
    """
    加载 TTS 配置（CFG-004 + DOC-073 D1）
    优先级：环境变量 > config.yaml > 代码兜底（深合并：嵌套结构部分配置仅覆盖指定项）
    """
    config = loader_load_config(config_path, defaults=_FALLBACK_CONFIG)

    # 环境变量覆盖（12-Factor：敏感/部署相关参数由环境变量注入）
    env_model = os.environ.get("DASHSCOPE_TTS_MODEL")
    if env_model:
        config.setdefault("model", {})["dashscope"] = env_model

    return config


# 加载配置（模块级，启动时执行一次）
_CONFIG = load_config()
# DA-14：启动契约校验（fail-fast，空矩阵拒绝启动而非带病运行）
_validate_runtime_contract(_CONFIG)

# ===== 引擎装配（DC-011：适配器层 + 降级策略；引擎实现细节见 tts_engines.py / tts_policy.py） =====

DASHSCOPE_TTS_MODEL = _CONFIG["model"]["dashscope"]
_TTS_POLICY = DegradationPolicy(
    [
        DashScopeBackend(
            model=DASHSCOPE_TTS_MODEL,
            api_key=os.environ.get("DASHSCOPE_API_KEY", ""),
            timeout=TTS_SYNTHESIZE_TIMEOUT,
        ),
        EdgeBackend(timeout=TTS_SYNTHESIZE_TIMEOUT),
    ],
    log=logger.warning,
)
# X-TTS-Engine 响应头映射（内部引擎名 → 对外契约名）
_ENGINE_HEADER_MAP = {"cosyvoice": "cosyvoice-cloud", "edge_tts": "edge-tts"}

if _TTS_POLICY.backends[0].is_available():
    logger.info("✅ 阿里云 CosyVoice TTS 就绪 (model=%s)", DASHSCOPE_TTS_MODEL)
else:
    logger.warning("阿里云 CosyVoice 不可用（API Key 缺失或 SDK 未安装）")
if _TTS_POLICY.backends[1].is_available():
    logger.info("✅ edge-tts 备用方案就绪")
else:
    logger.warning("edge-tts 未安装，备用方案不可用")


# ===== 音色人设配置（从 config.yaml 加载，见 design/56） =====

VOICE_PERSONAS = _CONFIG["voice_personas"]

# ===== 方言配置 =====

SUPPORTED_DIALECTS = _CONFIG["dialects"]

# ===== 原生方言音色 =====

NATIVE_DIALECT_VOICES = _CONFIG["native_dialect_voices"]

# ===== 情感 Instruct 映射 =====

EMOTION_INSTRUCT_MAP = _CONFIG["emotion_instruct_map"]


def build_instruction(persona_cfg: dict, dialect: Optional[str], emotion: str,
                      persona_gender: Optional[str] = None, **kwargs) -> tuple[Optional[str], Optional[str]]:
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
    # AUDIT-DEEP-010（P3-03）：废弃字段清理——language_mode 已移除，
    # extra=ignore 吸收旧请求体多余字段（兼容历史客户端，不报错）
    model_config = ConfigDict(extra="ignore")

    text: str                           # 要合成的文本
    persona: str = "xiaoxing"           # 音色人设
    emotion: str = "neutral"            # 孩子当前情绪（用于调整语气）
    speed: float = 1.0                  # 语速倍率（年龄适配）
    pitch: float = 1.0                  # 音高基调（TMATCH-001 prosody，<1 更低沉安抚）
    pause_style: int = 1                # 停顿风格（0=轻快 1=自然 2=多停顿安抚）
    dialect: Optional[str] = None       # 方言代码（可选，仅方言音色 qiqiu 生效）


class TtsInfoResponse(BaseModel):
    available: bool
    engine: str
    personas: list[dict]


# ===== API 端点 =====

@app.get("/health")
def health():
    if _TTS_POLICY.backends[0].is_available():
        engine = "cosyvoice-cloud"
    elif _TTS_POLICY.backends[1].is_available():
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
    文本 → 语音合成（DC-011：适配器层 + 降级策略：CosyVoice → edge-tts → 503）
    返回音频二进制流
    """
    if not req.text.strip():
        raise HTTPException(status_code=400, detail="文本不能为空")

    if len(req.text) > 500:
        raise HTTPException(status_code=400, detail="单次合成文本不超过500字")

    persona_cfg = VOICE_PERSONAS.get(req.persona, VOICE_PERSONAS["xiaoxing"])
    final_speed = persona_cfg["speed"] * req.speed

    # 构建 Instruct 指令 + 原生方言音色覆盖（v4：自动识别原生方言，无需 language_mode）
    # AUD-006：persona_gender 由 persona 配置（config.yaml gender 字段）传入，不再硬编码女声；
    # 无 gender 字段（如 qiqiu）时原生方言场景按 native_dialect_voices 首项兜底
    instruction, override_voice = build_instruction(
        persona_cfg, req.dialect, req.emotion,
        persona_gender=persona_cfg.get("gender"),
    )
    actual_voice = override_voice or persona_cfg["dashscope_voice"]

    # edge-tts 音色映射：仅东北/陕西有方言音色，其他方言回退普通话（policy 按引擎选 voice）
    edge_voice = persona_cfg["edge_voice"]
    if req.dialect:
        dialect_info = SUPPORTED_DIALECTS.get(req.dialect)
        if dialect_info and dialect_info.get("edge_voice"):
            edge_voice = dialect_info["edge_voice"]

    t_start = time.time()
    logger.debug(f"TTS 请求: text_len={len(req.text)}, persona={req.persona}, "
                f"emotion={req.emotion}, speed={final_speed:.2f}, dialect={req.dialect}, "
                f"voice={actual_voice}, instruction={instruction}")

    try:
        result = await _TTS_POLICY.synthesize_with_degradation(
            req.text, actual_voice, final_speed,
            instruction=instruction, pitch=req.pitch,
            voice_map={"edge_tts": edge_voice},
        )
    except TTSSynthesisFailed as e:
        # Level 3：全部引擎失败 → 503，前端自动降级到浏览器 speechSynthesis
        _metrics.record("error", time.time() - t_start)
        logger.error(f"TTS 全部引擎失败 (elapsed={time.time() - t_start:.3f}s): {e}")
        raise HTTPException(status_code=503, detail="TTS 服务不可用")

    # 指标：Instruct 失败后无指令重试成功 → cosyvoice_fallback（与 v3 指标契约一致）
    engine_label = "cosyvoice_fallback" if result.retried else result.engine
    _metrics.record(engine_label, time.time() - t_start)
    # OPS-MON-002（BUG-TTS-01）：运行期降级事件计数——主引擎失效但兜底可用
    # 判定：实际引擎 ≠ 首选引擎 且 非 retried（instruct 无指令重试成功不算降级）；全失败 503 走 except 不计数
    if result.engine != _TTS_POLICY.backends[0].name and not result.retried:
        _degraded_metrics.record(f"{_TTS_POLICY.backends[0].name}->{result.engine}", 0.0)
    logger.info(f"TTS 合成完成 [{result.engine}]: text_len={len(req.text)}, voice={actual_voice}, "
                f"elapsed={time.time() - t_start:.3f}s")
    return StreamingResponse(
        io.BytesIO(result.audio),
        media_type="audio/mpeg",
        headers={
            "X-TTS-Engine": _ENGINE_HEADER_MAP.get(result.engine, result.engine),
            "X-TTS-Voice": actual_voice,
        },
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=10096)
