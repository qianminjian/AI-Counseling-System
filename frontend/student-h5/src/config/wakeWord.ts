/**
 * 语音唤醒配置（Transformers.js + Whisper 本地离线方案，见 design/28 §1.3）
 *
 * 方案要点：
 * - 完全本地：Whisper 模型在浏览器内推理（WASM），唤醒检测音频不出设备
 * - 零账号：无需任何平台注册 / AccessKey / 商业许可（模型 Apache 2.0 / MIT）
 * - 模型加载：首次约 20MB 下载（HF 镜像），之后走浏览器 Cache API 缓存
 *
 * 模型说明：
 * - onnx-community/whisper-tiny：多语言（含中文），量化后约 20MB，唤醒场景够用
 * - 若真机测试中文唤醒率不理想，可升级为 onnx-community/whisper-base（约 45MB，中文更好）
 */

/** Whisper 模型 ID（Hugging Face Hub，ONNX 量化版） */
export const WAKE_MODEL_ID = 'onnx-community/whisper-tiny'

/**
 * 模型下载源：默认 HF 国内镜像（学校网络可能无法直连 huggingface.co）
 * - 国内：https://hf-mirror.com/
 * - 海外/可直连：https://huggingface.co/
 * - 离线部署：将模型文件放入 public/models/ 并改用本地加载（见 useWakeWord.js 注释）
 */
export const WAKE_MODEL_REMOTE_HOST = 'https://hf-mirror.com/'

/**
 * 唤醒词匹配变体（Whisper 中文转写可能输出同音字，做容错匹配）
 * 匹配前先经 normalizeWakeText 归一化（小写 + 去标点空白）
 */
export const WAKE_PATTERNS = [
  '哈喽波波',
  '哈罗波波',
  '哈喽啵啵',
  '哈罗啵啵',
  '哈楼波波',
  '哈喽bobo',
  '哈罗bobo',
  'hello波波',
  'halo波波',
  'hellobobo',
  'halobobo',
  // 声纹登录唤醒词扩展（“你好波波” / “你好bobo”）
  '你好波波',
  '你好啵啵',
  '你好bobo',
  'nihao波波',
  'nijaobobo',
  // Whisper-tiny 中文常见误识别变体（同音字/近音字）
  '哈喽波播',
  '哈喽播播',
  '哈罗波播',
  '哈喍波波',
  '哈喍啵啵',
  '蛤喽波波',
  '哈喽铂铂',
  '哈喽伯伯',
  'hello bobo',
  'hello 波波',
]

/** 滑窗长度（秒）：累积满后送一次 Whisper 转写 */
export const WAKE_WINDOW_SECONDS = 2.5

/** 滑窗重叠保留（秒）：每次转写后保留尾部作为下一窗前缀，避免唤醒词被窗口边界切断 */
export const WAKE_KEEP_SECONDS = 1.0

/**
 * 静音 RMS 阈值（Float32 采样）：低于此值的窗口直接跳过转写
 * 作用：① 省 CPU  ② 抑制 Whisper 对静音的幻觉输出（如"谢谢观看"类文本）
 */
export const SILENCE_RMS_THRESHOLD = 0.008

/** 归一化转写文本：小写 + 去标点/空白，便于容错匹配 */
export function normalizeWakeText(text) {
  return (text || '')
    .toLowerCase()
    .replace(/[\s，。！？、,.!?~·"'"':：;；…\-—()（）]/g, '')
}

/** 判断转写文本是否命中唤醒词（任一变体子串匹配 + 拼音模糊匹配） */
export function matchesWakeWord(text) {
  const t = normalizeWakeText(text)
  if (!t) return false
  // 精确匹配（已归一化）
  if (WAKE_PATTERNS.some((p) => t.includes(p))) return true
  // 拼音模糊匹配：Whisper 可能输出其他同音字，用“哈/蛤/嘿” + “喽/罗/楼/喍” + “波/啵/播/伯/铂” + “波/啵/播/伯/铂” 容错
  const fuzzy = /^[\u54c8\u86e4\u563f\u54ce][\u55bd\u7f57\u697c\u558d\u565c][\u6ce2\u5575\u64ad\u4f2f\u94c2][\u6ce2\u5575\u64ad\u4f2f\u94c2]/
  if (fuzzy.test(t)) return true
  // 英文部分匹配："hello"/"halo" + "bobo"/"波波"
  if (/hello|halo/.test(t) && /bobo|波波|啵啵/.test(t)) return true
  return false
}
