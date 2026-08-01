/**
 * 音色人设选择 Hook（design/56 7 音色矩阵 + 方言条件启用）
 * - localStorage 持久化
 * - 7 种音色：小星/波波老师/月亮/小太阳/大树/豆豆/气球
 * - 默认音色根据用户性别自动选择（男→小太阳，女→小星）
 * - 方言：仅气球（dialectCapable）支持，学生主动开启，默认值取 student.dialect
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
    name: '气球',
    emoji: '🎈',
    desc: '欢脱元气伙伴',
    detail: '俘皮可爱，像好朋友一样和你分享快乐',
    gender: 'female',
    dialectCapable: true, // 唯一支持方言 Instruct 的音色（longanhuan_v3）
  },
}

/** 支持的方言列表（design/56 §三，与 CosyVoice Instruct 严格对应） */
export const SUPPORTED_DIALECTS = {
  cantonese: { id: 'cantonese', label: '广东话' },
  northeastern: { id: 'northeastern', label: '东北话' },
  sichuan: { id: 'sichuan', label: '四川话' },
  henan: { id: 'henan', label: '河南话' },
  shandong: { id: 'shandong', label: '山东话' },
  hunan: { id: 'hunan', label: '湖南话' },
  shaanxi: { id: 'shaanxi', label: '陕西话' },
  anhui: { id: 'anhui', label: '安徽话' },
}

const PERSONA_KEY = 'mindsafe_voice_persona_v1'
const DIALECT_ENABLED_KEY = 'mindsafe_dialect_enabled_v1'
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
  // 方言状态：会话级（新会话重置为关闭，默认值取 student.dialect）
  const [dialectEnabled, setDialectEnabled] = useState(() => {
    return localStorage.getItem(DIALECT_ENABLED_KEY) === 'true'
  })
  const [selectedDialect, setSelectedDialect] = useState(() => {
    const saved = localStorage.getItem(DIALECT_KEY)
    if (saved && SUPPORTED_DIALECTS[saved]) return saved
    // 默认取学生配置的 dialect
    const user = getUser()
    return user?.dialect || null
  })

  const persona = VOICE_PERSONAS[personaId] || VOICE_PERSONAS.xiaoxing

  const changePersona = useCallback((id) => {
    if (VOICE_PERSONAS[id]) {
      setPersonaId(id)
      localStorage.setItem(PERSONA_KEY, id)
      // 切换到非方言音色时自动关闭方言
      if (!VOICE_PERSONAS[id].dialectCapable) {
        setDialectEnabled(false)
        localStorage.setItem(DIALECT_ENABLED_KEY, 'false')
      }
    }
  }, [])

  /** 切换方言开关（仅 dialectCapable 音色生效） */
  const toggleDialect = useCallback((enabled) => {
    setDialectEnabled(enabled)
    localStorage.setItem(DIALECT_ENABLED_KEY, String(enabled))
  }, [])

  /** 切换方言类型 */
  const changeDialect = useCallback((dialectId) => {
    if (SUPPORTED_DIALECTS[dialectId]) {
      setSelectedDialect(dialectId)
      localStorage.setItem(DIALECT_KEY, dialectId)
    }
  }, [])

  /** 当前生效的 dialect（仅方言开启 + 音色支持时返回，否则 null） */
  const activeDialect = persona.dialectCapable && dialectEnabled ? selectedDialect : null

  return {
    persona,
    personaId,
    changePersona,
    personas: VOICE_PERSONAS,
    // 方言相关
    dialectEnabled,
    toggleDialect,
    selectedDialect,
    changeDialect,
    activeDialect,
    supportedDialects: SUPPORTED_DIALECTS,
  }
}
