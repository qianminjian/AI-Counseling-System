/**
 * 音色人设选择 Hook（design/56 7 音色矩阵 + 方言条件启用）
 * - localStorage 持久化
 * - 7 种音色：小星/波波老师/月亮/小太阳/大树/豆豆/方言
 * - 默认音色根据用户性别自动选择（男→小太阳，女→小星）
 * - 方言：仅“方言”音色（qiqiu）选中时可用，无需单独开关
 * - 原生方言（粤语/闽南话）：选中即自动使用原生音色，无需切换模式
 */
import { useState, useCallback } from 'react'
import { getUser } from '../api'

export const VOICE_PERSONAS = {
  xiaoxing: {
    id: 'xiaoxing',
    name: '小星',
    emoji: '🌟',
    desc: '温暖的邻家姐姐',
    detail: '亲切自然，像邻家姐姐一样陪你聊天',
    gender: 'female',
    dialectCapable: false,
  },
  bobo: {
    id: 'bobo',
    name: '波波老师',
    emoji: '🍎',
    desc: '温柔的女老师',
    detail: '温和共情，像班主任一样耐心听你说话',
    gender: 'female',
    dialectCapable: false,
  },
  yueliang: {
    id: 'yueliang',
    name: '月亮',
    emoji: '🌙',
    desc: '轻声讲故事',
    detail: '轻声细语，像睡前故事一样让你安心',
    gender: 'female',
    dialectCapable: false,
  },
  xiaotaiyang: {
    id: 'xiaotaiyang',
    name: '小太阳',
    emoji: '☀️',
    desc: '阳光大哥哥',
    detail: '开朗有活力，像运动场上的大哥哥一样给你加油',
    gender: 'male',
    dialectCapable: false,
  },
  dashu: {
    id: 'dashu',
    name: '大树',
    emoji: '🌳',
    desc: '暖心大叔',
    detail: '稳重可靠，像大树一样给你安全感',
    gender: 'male',
    dialectCapable: false,
  },
  doudou: {
    id: 'doudou',
    name: '豆豆',
    emoji: '⚽',
    desc: '顽皮同龄男孩',
    detail: '阳光顽皮，像同班同学一样和你一起玩',
    gender: 'male',
    dialectCapable: false,
  },
  qiqiu: {
    id: 'qiqiu',
    name: '方言',
    emoji: '🗣️',
    desc: '用家乡话聊天',
    detail: '支持多种方言，像家乡的小伙伴一样亲切',
    gender: 'female',
    dialectCapable: true,
  },
}

/** 支持的方言列表（v4：粤语/闽南话为原生音色，其余为 Instruct 实现） */
export const SUPPORTED_DIALECTS = {
  cantonese: { id: 'cantonese', label: '粤语' },
  minnan: { id: 'minnan', label: '闽南话' },
  northeastern: { id: 'northeastern', label: '东北话' },
  sichuan: { id: 'sichuan', label: '四川话' },
  henan: { id: 'henan', label: '河南话' },
  shandong: { id: 'shandong', label: '山东话' },
  hunan: { id: 'hunan', label: '湖南话' },
  shaanxi: { id: 'shaanxi', label: '陕西话' },
}

/** 拥有原生方言音色的方言（不需要 instruction，天生说方言，选中即自动生效） */
export const NATIVE_DIALECT_IDS = ['cantonese', 'minnan']

const PERSONA_KEY = 'mindsafe_voice_persona_v1'
const DIALECT_KEY = 'mindsafe_dialect_v1'

/** 根据用户性别获取默认音色 */
function getDefaultPersona() {
  const saved = localStorage.getItem(PERSONA_KEY)
  if (saved && VOICE_PERSONAS[saved]) return saved
  // 未手动选择过，根据性别自动匹配
  const user = getUser()
  if (user?.gender === 'male') return 'xiaotaiyang'
  return 'xiaoxing'
}

export function useVoicePersona() {
  const [personaId, setPersonaId] = useState(getDefaultPersona)
  const [selectedDialect, setSelectedDialect] = useState<string | null>(() => {
    const saved = localStorage.getItem(DIALECT_KEY)
    if (saved && SUPPORTED_DIALECTS[saved]) return saved
    // 默认取学生配置的 dialect
    const user = getUser()
    return (user?.dialect as string) || null
  })

  const persona = VOICE_PERSONAS[personaId] || VOICE_PERSONAS.xiaoxing

  // 方言是否启用：仅当选中“方言”音色（qiqiu）时自动启用
  const dialectEnabled = personaId === 'qiqiu'

  const changePersona = useCallback((id) => {
    if (VOICE_PERSONAS[id]) {
      setPersonaId(id)
      localStorage.setItem(PERSONA_KEY, id)
    }
  }, [])

  /** 切换方言类型 */
  const changeDialect = useCallback((dialectId) => {
    if (SUPPORTED_DIALECTS[dialectId]) {
      setSelectedDialect(dialectId)
      localStorage.setItem(DIALECT_KEY, dialectId)
    }
  }, [])

  /** 当前生效的 dialect（仅方言音色选中时返回，否则 null） */
  const activeDialect = dialectEnabled ? selectedDialect : null

  /** 当前选中的方言是否有原生音色（粤语/闽南话） */
  const hasNativeVoice = selectedDialect ? NATIVE_DIALECT_IDS.includes(selectedDialect) : false

  return {
    persona,
    personaId,
    changePersona,
    personas: VOICE_PERSONAS,
    // 方言相关
    dialectEnabled,
    selectedDialect,
    changeDialect,
    activeDialect,
    supportedDialects: SUPPORTED_DIALECTS,
    hasNativeVoice,
  }
}
