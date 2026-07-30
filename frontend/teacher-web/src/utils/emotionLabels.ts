/**
 * 情绪码值 → 中文标签统一映射
 * 覆盖学生端情绪签到（happy/sad/angry/scared/nervous）
 * 与语音情绪识别（fearful/disgusted/surprised 等）两套码值。
 */
export const EMOTION_LABELS = {
  happy: '开心',
  sad: '难过',
  angry: '生气',
  scared: '害怕',
  fearful: '恐惧',
  nervous: '紧张',
  anxious: '焦虑',
  neutral: '平静',
  calm: '平静',
  excited: '兴奋',
  surprised: '惊讶',
  disgusted: '厌恶',
  tired: '疲惫',
}

/** 码值转中文；未知码值原样返回，空值返回空串 */
export function emotionLabel(code) {
  if (!code) return ''
  return EMOTION_LABELS[code] || code
}
