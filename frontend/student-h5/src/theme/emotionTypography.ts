/**
 * 情感化排印（Emotion-driven Typography）
 *
 * 理论依据：CMU Kinetic Typography 研究（Lee & Spasojevic, 2006）证实，
 * 动态文字（大小/字重/颜色/动效变化）能在纯文本对话中有效传递情绪。
 *
 * 设计：AI 回复气泡的排印由「AI 回复情绪」驱动（FA-09：与 emotionBus 同源，
 * design/37 §三.1 三方同源契约——同一标签驱动 TTS 语调 / 波波表情 / 气泡微动效），
 * 词表见 shared/src/replyEmotion；孩子情绪（sad/angry/scared/nervous）条目保留
 * 兼容历史消息与兜底。AI 以共情语气匹配——开心时明快略大、安抚时轻柔略小、严肃时加粗。
 *
 * 原则：正文保持深灰保证可读性，情绪通过"字号/字重 + 左侧强调条 + 柔和底色 + 入场动效"表达，
 * 不做高饱和大字（避免刺眼）。未知/无情绪一律回退 neutral，不破坏现有体验。
 */

/** 情绪 → 排印配置映射（FA-09：键集必须覆盖 shared REPLY_EMOTIONS 全 6 类，测试防回归） */
export const EMOTION_TYPO = {
  /* ===== AI 回复情绪（design/37 §三.1，与 emotionBus 同源） ===== */
  happy: {
    scale: 1.08,            // 字号缩放：略大，明快
    weight: 500,            // 字重
    accent: '#F59E0B',      // 左侧强调条颜色（暖黄）
    tint: '#FFFBEB',        // 气泡柔和底色（琥珀-50）
    anim: 'anim-pop-happy', // 入场动效：弹跳
  },
  gentle: {
    scale: 0.98,            // 温柔：略小，轻柔
    weight: 400,
    accent: '#10B981',      // 翠绿
    tint: '#ECFDF5',        // 绿-50
    anim: 'anim-fade-sad',  // 舒缓淡入
  },
  encourage: {
    scale: 1.06,            // 鼓励：明快略大
    weight: 700,            // 加粗，给力量
    accent: '#F59E0B',      // 暖黄
    tint: '#FFFBEB',        // 琥珀-50
    anim: 'anim-pop-happy', // 弹跳（打气感）
  },
  calm: {
    scale: 1.0,
    weight: 400,
    accent: '#3B82F6',      // 柔蓝
    tint: '#EFF6FF',        // 蓝-50
    anim: 'anim-fade-sad',  // 缓慢淡入
  },
  serious: {
    scale: 1.0,
    weight: 700,            // 加粗：郑重
    accent: '#6B7280',      // 灰
    tint: '#F3F4F6',        // 灰-50
    anim: 'anim-fade-in',   // 克制淡入
  },
  soothe: {
    scale: 0.96,            // 安抚：轻柔略小
    weight: 500,
    accent: '#8B5CF6',      // 紫
    tint: '#F5F3FF',        // 紫-50
    anim: 'anim-fade-sad',  // 舒缓淡入
  },
  /* ===== 孩子情绪（历史消息/兜底兼容，非总线信号） ===== */
  sad: {
    scale: 0.94,            // 略小，舒缓
    weight: 400,
    accent: '#3B82F6',      // 柔蓝
    tint: '#EFF6FF',        // 蓝-50
    anim: 'anim-fade-sad',  // 慢速淡入
  },
  angry: {
    scale: 1.05,
    weight: 700,            // 加粗，有力
    accent: '#EF4444',      // 红
    tint: '#FEF2F2',        // 红-50
    anim: 'anim-pop-angry', // 快速弹出 + 轻微抖动
  },
  scared: {
    scale: 0.94,            // 略小，保护感
    weight: 400,
    accent: '#8B5CF6',      // 紫
    tint: '#F5F3FF',        // 紫-50
    anim: 'anim-fade-scared', // 轻柔淡入 + 微颤
  },
  nervous: {
    scale: 1.0,
    weight: 500,
    accent: '#F97316',      // 橙
    tint: '#FFF7ED',        // 橙-50
    anim: 'anim-wobble-nervous', // 轻微摆动
  },
  neutral: {
    scale: 1.0,
    weight: 400,
    accent: 'var(--primary)',   // 主题色（AI 一致的身份强调条）
    tint: 'var(--bubble-ai)',   // 主题气泡底色
    anim: 'anim-fade-in',       // 普通淡入
  },
}

/**
 * 归一化不同来源的情绪标签：
 * - 语音情绪（emotion2vec）用 fearful，会话情绪（EmotionSelect）用 scared → 统一为 scared
 * - unknown / other / surprised / disgusted 等无明确排印语义的 → neutral
 */
function normalizeLabel(label) {
  if (!label) return 'neutral'
  if (label === 'fearful') return 'scared'
  return EMOTION_TYPO[label] ? label : 'neutral'
}

/**
 * 获取情绪排印配置
 * @param {Object|string|null} emotion 情绪对象（{ labelEn }）| 情绪标签字符串 | null
 * @returns {{scale:number, weight:number, accent:string, tint:string, anim:string}}
 */
export function getEmotionTypo(emotion) {
  const label = typeof emotion === 'string' ? emotion : emotion?.labelEn
  return EMOTION_TYPO[normalizeLabel(label)]
}
