/**
 * 声纹识别登录配置（design/24 身份认证优化方案 - 声纹扩展）
 *
 * 方案要点：
 * - 完全本地：Speaker Embedding 模型在浏览器内推理（ONNX WASM），声纹数据不出设备
 * - 零账号：无需任何平台注册 / AccessKey / 商业许可
 * - 模型懒加载：仅在声纹触发时下载，不影响 PIN 登录主路径
 *
 * 隐私即设计：
 * - 仅存储 256-dim 特征向量（Float32Array），不存原始音频
 * - IndexedDB 同源隔离，可选 XOR 混淆
 * - 用户可随时删除声纹数据
 */

// ===== Speaker Embedding 模型 =====

/**
 * 模型 ID（Hugging Face Hub，ONNX 量化版）
 * wespeaker-voxceleb-resnet34-LM：ECAPA-TDNN 系列，256-dim embedding
 * 量化后约 25-30MB，首次下载后浏览器 Cache API 缓存
 *
 * 降级：若 Pad 性能不足（推理 > 3s），可切换为 MFCC 特征方案（见 useVoiceprint.js）
 */
export const VP_MODEL_ID = 'onnx-community/wespeaker-voxceleb-resnet34-LM'

/** 模型下载源：同源部署（与唤醒词模型共用 /mindsafe/models/ 目录） */
export const VP_MODEL_REMOTE_HOST = 'SAME_ORIGIN' // 运行时由 hook 替换为 import.meta.env.BASE_URL + 'models/'

/** Embedding 维度 */
export const VP_EMBEDDING_DIM = 256

// ===== 比对阈值 =====

/**
 * 余弦相似度阈值（初始偏宽松，减少误拒）
 * - 成人场景通常 0.5-0.6 即可
 * - 儿童声音相似度高，初始设 0.75 偏严格防误识
 * - 后续根据实测调整
 */
export const VP_VERIFY_THRESHOLD = 0.75

/** 多段验证：引导对话采集段数（两段都通过才算成功） */
export const VP_VERIFY_SEGMENTS = 2

/** 注册时采集段数（3-5 段取全部 embedding） */
export const VP_ENROLL_SEGMENTS = 3

/** 自适应模板滑动窗口上限（每次成功登录追加，保留最近 N 个） */
export const VP_MAX_TEMPLATES = 8

/** 连续失败次数上限（达到后本次禁用声纹入口，强制 PIN） */
export const VP_MAX_FAILURES = 3

// ===== 音频采集参数 =====

/** 采集采样率（与 Whisper 一致，16kHz） */
export const VP_SAMPLE_RATE = 16000

/** 每段采集时长（秒）：引导对话中每轮回答的录音窗口 */
export const VP_SEGMENT_DURATION = 4

/** 静音 RMS 阈值：低于此值视为无效段（提示"没听到声音"） */
export const VP_SILENCE_THRESHOLD = 0.01

/** 推理超时（ms）：超过此时间放弃本次识别 */
export const VP_INFERENCE_TIMEOUT = 5000

// ===== 超时休眠 =====

/** 登录页无交互超时（ms）：停止唤醒监听，节省电量 */
export const VP_IDLE_TIMEOUT = 5 * 60 * 1000

// ===== 引导对话脚本 =====

/**
 * 唤醒后 AI 引导对话（固定脚本，非真正 AI 对话）
 * 每轮：波波 TTS 提问 → 等待孩子回答（采集音频）→ 下一轮
 */
export const VP_GUIDE_SCRIPTS = {
  /** 验证模式（已有声纹，2 轮） */
  verify: [
    { prompt: '嗨！是谁来找我玩啦？告诉我你的名字吧~', duration: 4 },
    { prompt: '今天想和我做什么呀？聊天还是做游戏？', duration: 4 },
  ],
  /** 注册模式（首次采集，3 轮） */
  enroll: [
    { prompt: '嗨！我是波波，你叫什么名字呀？', duration: 4 },
    { prompt: '你今年几岁啦？最喜欢什么呀？', duration: 4 },
    { prompt: '跟我说一句：今天天气真好，我想出去玩！', duration: 5 },
  ],
}

// ===== 存储配置 =====

/** IndexedDB 数据库名 */
export const VP_DB_NAME = 'mindsafe_voiceprints'

/** IndexedDB 版本 */
export const VP_DB_VERSION = 1

/** Object Store 名 */
export const VP_STORE_NAME = 'templates'
