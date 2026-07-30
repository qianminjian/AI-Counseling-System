/**
 * 音色人设选择 Hook
 * - localStorage 持久化
 * - 四种音色：小星/气球/月亮/小太阳
 * - 默认音色根据用户性别自动选择（男→小太阳，女→小星）
 */
import { useState, useCallback } from 'react'
import { getUser } from '../api'

export const VOICE_PERSONAS = {
  xiaoxing: {
    id: 'xiaoxing',
    name: '小星',
    emoji: '🌟',
    desc: '温暖的大姐姐',
    detail: '亲切自然，像邻家姐姐一样陪你聊天',
    gender: 'female',
  },
  qiqiu: {
    id: 'qiqiu',
    name: '气球',
    emoji: '🎈',
    desc: '活泼的小伙伴',
    detail: '俏皮可爱，像好朋友一样和你分享快乐',
    gender: 'female',
  },
  yueliang: {
    id: 'yueliang',
    name: '月亮',
    emoji: '🌙',
    desc: '温柔的讲故事者',
    detail: '轻声细语，像睡前故事一样让你安心',
    gender: 'female',
  },
  xiaotaiyang: {
    id: 'xiaotaiyang',
    name: '小太阳',
    emoji: '☀️',
    desc: '阳光的大哥哥',
    detail: '开朗有活力，像运动场上的大哥哥一样给你加油',
    gender: 'male',
  },
}

const PERSONA_KEY = 'mindsafe_voice_persona_v1'

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

  const persona = VOICE_PERSONAS[personaId] || VOICE_PERSONAS.xiaoxing

  const changePersona = useCallback((id) => {
    if (VOICE_PERSONAS[id]) {
      setPersonaId(id)
      localStorage.setItem(PERSONA_KEY, id)
    }
  }, [])

  return { persona, personaId, changePersona, personas: VOICE_PERSONAS }
}
