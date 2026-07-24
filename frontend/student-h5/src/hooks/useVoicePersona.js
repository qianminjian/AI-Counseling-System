/**
 * 音色人设选择 Hook
 * - localStorage 持久化
 * - 三种音色：小星/气球/月亮
 */
import { useState, useCallback } from 'react'

export const VOICE_PERSONAS = {
  xiaoxing: {
    id: 'xiaoxing',
    name: '小星',
    emoji: '🌟',
    desc: '温暖的大姐姐',
    detail: '亲切自然，像邻家姐姐一样陪你聊天',
  },
  qiqiu: {
    id: 'qiqiu',
    name: '气球',
    emoji: '🎈',
    desc: '活泼的小伙伴',
    detail: '俏皮可爱，像好朋友一样和你分享快乐',
  },
  yueliang: {
    id: 'yueliang',
    name: '月亮',
    emoji: '🌙',
    desc: '温柔的讲故事者',
    detail: '轻声细语，像睡前故事一样让你安心',
  },
}

const PERSONA_KEY = 'mindsafe_voice_persona_v1'

export function useVoicePersona() {
  const [personaId, setPersonaId] = useState(() => {
    return localStorage.getItem(PERSONA_KEY) || 'xiaoxing'
  })

  const persona = VOICE_PERSONAS[personaId] || VOICE_PERSONAS.xiaoxing

  const changePersona = useCallback((id) => {
    if (VOICE_PERSONAS[id]) {
      setPersonaId(id)
      localStorage.setItem(PERSONA_KEY, id)
    }
  }, [])

  return { persona, personaId, changePersona, personas: VOICE_PERSONAS }
}
