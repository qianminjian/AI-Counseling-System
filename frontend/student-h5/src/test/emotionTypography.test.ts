import { describe, it, expect } from 'vitest'
import { EMOTION_TYPO, getEmotionTypo } from '../theme/emotionTypography'
import { REPLY_EMOTIONS } from '../../../shared/src/replyEmotion'

describe('theme/emotionTypography', () => {
  describe('EMOTION_TYPO 配置完整性', () => {
    it('包含孩子情绪 6 类 + AI 回复情绪 5 类（happy 交集，共 11 键）', () => {
      expect(Object.keys(EMOTION_TYPO)).toHaveLength(11)
      expect(Object.keys(EMOTION_TYPO)).toEqual(
        expect.arrayContaining(['happy', 'sad', 'angry', 'scared', 'nervous', 'neutral',
          'gentle', 'encourage', 'calm', 'serious', 'soothe'])
      )
    })

    it('FA-09：EMOTION_TYPO 键集覆盖 REPLY_EMOTIONS 全 6 类（气泡接总线后不静默落 neutral）', () => {
      for (const emo of REPLY_EMOTIONS) {
        expect(Object.keys(EMOTION_TYPO)).toContain(emo)
      }
    })

    it('每种配置包含 scale/weight/accent/tint/anim 五字段', () => {
      for (const [, cfg] of Object.entries(EMOTION_TYPO)) {
        expect(cfg).toHaveProperty('scale')
        expect(cfg).toHaveProperty('weight')
        expect(cfg).toHaveProperty('accent')
        expect(cfg).toHaveProperty('tint')
        expect(cfg).toHaveProperty('anim')
      }
    })

    it('scale 在合理范围（0.8~1.2）', () => {
      for (const [, cfg] of Object.entries(EMOTION_TYPO)) {
        expect(cfg.scale).toBeGreaterThanOrEqual(0.8)
        expect(cfg.scale).toBeLessThanOrEqual(1.2)
      }
    })

    it('weight 为标准字重值', () => {
      for (const [, cfg] of Object.entries(EMOTION_TYPO)) {
        expect([400, 500, 700]).toContain(cfg.weight)
      }
    })
  })

  describe('getEmotionTypo', () => {
    it('字符串输入：happy 返回对应配置', () => {
      const typo = getEmotionTypo('happy')
      expect(typo.scale).toBe(1.08)
      expect(typo.weight).toBe(500)
      expect(typo.anim).toBe('anim-pop-happy')
    })

    it('对象输入：{ labelEn: "sad" } 返回 sad 配置', () => {
      const typo = getEmotionTypo({ labelEn: 'sad' })
      expect(typo.scale).toBe(0.94)
      expect(typo.accent).toBe('#3B82F6')
    })

    it('fearful 归一化为 scared', () => {
      const typo = getEmotionTypo('fearful')
      expect(typo).toEqual(EMOTION_TYPO.scared)
    })

    it('对象 { labelEn: "fearful" } 也归一化为 scared', () => {
      const typo = getEmotionTypo({ labelEn: 'fearful' })
      expect(typo).toEqual(EMOTION_TYPO.scared)
    })

    it('null 回退 neutral', () => {
      expect(getEmotionTypo(null)).toEqual(EMOTION_TYPO.neutral)
    })

    it('undefined 回退 neutral', () => {
      expect(getEmotionTypo(undefined)).toEqual(EMOTION_TYPO.neutral)
    })

    it('未知标签回退 neutral', () => {
      expect(getEmotionTypo('surprised')).toEqual(EMOTION_TYPO.neutral)
      expect(getEmotionTypo('disgusted')).toEqual(EMOTION_TYPO.neutral)
      expect(getEmotionTypo('unknown')).toEqual(EMOTION_TYPO.neutral)
    })

    it('空对象回退 neutral', () => {
      expect(getEmotionTypo({})).toEqual(EMOTION_TYPO.neutral)
    })

    it('angry 配置：加粗 + 红色强调', () => {
      const typo = getEmotionTypo('angry')
      expect(typo.weight).toBe(700)
      expect(typo.accent).toBe('#EF4444')
    })
  })
})
