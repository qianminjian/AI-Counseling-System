/**
 * 情绪元数据单一源（F4 收编，doing/78 §F4）
 * 前端各端（student-h5 / parent-h5 / teacher-web）情绪码值 → 中文标签 + emoji 的唯一映射，
 * 镜像后端 EmotionVocabulary.ZH_LABELS（DC-008：anxious→紧张 全系统单译）。
 * 消费点只引用不定义，解决「同一码值在不同端标签/图标不一致」的漂移隐患。
 */

export interface EmotionMeta {
  /** 情绪码值（与后端 EmotionVocabulary 权威成员集对齐） */
  code: string
  /** 中文展示标签 */
  label: string
  /** 展示 emoji（unknown/other 为空串） */
  emoji: string
}

/** 情绪元数据全集：后端 ZH_LABELS 17 码值 + POSITIVE_KEYS 补充（relieved/hopeful）+ 前端未知兜底 */
export const EMOTION_META: EmotionMeta[] = [
  { code: 'happy', label: '开心', emoji: '😊' },
  { code: 'sad', label: '难过', emoji: '😢' },
  { code: 'angry', label: '生气', emoji: '😠' },
  { code: 'scared', label: '害怕', emoji: '😨' },
  { code: 'fearful', label: '恐惧', emoji: '😨' },
  { code: 'nervous', label: '紧张', emoji: '😰' },
  { code: 'anxious', label: '紧张', emoji: '😰' },
  { code: 'neutral', label: '平静', emoji: '😐' },
  { code: 'calm', label: '平静', emoji: '😌' },
  { code: 'excited', label: '兴奋', emoji: '😆' },
  { code: 'surprised', label: '惊讶', emoji: '😲' },
  { code: 'disgusted', label: '厌恶', emoji: '🤢' },
  { code: 'tired', label: '疲惫', emoji: '😪' },
  { code: 'withdrawn', label: '沉默', emoji: '🫥' },
  { code: 'lonely', label: '孤独', emoji: '😔' },
  { code: 'crisis', label: '危机', emoji: '🆘' },
  { code: 'relieved', label: '放松', emoji: '🥹' },
  { code: 'hopeful', label: '希望', emoji: '🌟' },
  { code: 'unknown', label: '', emoji: '' },
  { code: 'other', label: '', emoji: '' },
]

const META_BY_CODE = new Map(EMOTION_META.map(m => [m.code, m]))

/**
 * 情绪码值 → 中文标签（镜像后端 EmotionVocabulary.labelOf 语义：
 * null/空白 → 空串；未知码值原样返回）。
 */
export function emotionLabel(code: string): string {
  if (!code) return ''
  return META_BY_CODE.get(code)?.label ?? code
}

/**
 * 情绪码值 / 中文标签 → emoji。
 * 兼容两种入参：英文码值（sad）与中文标签（难过）——家长端周报接口按原始值分组，
 * 两种格式都可能出现；未知返回空串由调用方兜底。
 * 注意：中文标签存在歧义（neutral/calm 均译'平静'），命中数组首个匹配，码值查询无歧义。
 */
export function emotionEmoji(codeOrLabel: string): string {
  if (!codeOrLabel) return ''
  const byCode = META_BY_CODE.get(codeOrLabel)
  if (byCode) return byCode.emoji
  const byLabel = EMOTION_META.find(m => m.label === codeOrLabel)
  return byLabel?.emoji ?? ''
}

// DOC-082：学生端情绪集统一基线
// 场景：首页 EmotionSelect（开聊前选择）与 EmotionDiary 打卡面板共用同一组 5 情绪。
// 选中规则：（1）不含 calm/neutral 同译「平静」（避免打卡面板重复选项）；（2）覆盖开心/难过/生气/害怕/紧张五大基础情绪；
// （3）纯 kid-friendly 标签（DC-008 已统一）。
// 改动源：05_系统测试指导驱动生产 UI 遍历测试发现首页与打卡面板情绪集不一致。
export const STUDENT_EMOTION_TAGS = ['happy', 'sad', 'angry', 'scared', 'nervous'] as const
export type StudentEmotionTag = (typeof STUDENT_EMOTION_TAGS)[number]
