import { describe, it, expect } from 'vitest'
import {
  normalizeWakeText,
  matchesWakeWord,
  WAKE_PATTERNS,
  WAKE_MODEL_ID,
  WAKE_WINDOW_SECONDS,
  WAKE_KEEP_SECONDS,
  SILENCE_RMS_THRESHOLD,
} from '../config/wakeWord'

describe('config/wakeWord', () => {
  describe('常量配置', () => {
    it('模型 ID 为 whisper-tiny', () => {
      expect(WAKE_MODEL_ID).toBe('onnx-community/whisper-tiny')
    })

    it('滑窗参数合理（窗口 > 保留）', () => {
      expect(WAKE_WINDOW_SECONDS).toBeGreaterThan(WAKE_KEEP_SECONDS)
      expect(WAKE_WINDOW_SECONDS).toBeLessThanOrEqual(5)
    })

    it('静音阈值在合理范围', () => {
      expect(SILENCE_RMS_THRESHOLD).toBeGreaterThan(0)
      expect(SILENCE_RMS_THRESHOLD).toBeLessThan(0.1)
    })

    it('唤醒变体列表非空且包含核心词', () => {
      expect(WAKE_PATTERNS.length).toBeGreaterThan(10)
      expect(WAKE_PATTERNS).toContain('哈喽波波')
      expect(WAKE_PATTERNS).toContain('你好波波')
    })
  })

  describe('normalizeWakeText', () => {
    it('转小写', () => {
      expect(normalizeWakeText('Hello BOBO')).toBe('hellobobo')
    })

    it('去除中文标点', () => {
      expect(normalizeWakeText('哈喽，波波！')).toBe('哈喽波波')
    })

    it('去除英文标点和空白', () => {
      expect(normalizeWakeText('hello, bobo! how are you?')).toBe('hellobobohowareyou')
    })

    it('去除特殊符号', () => {
      expect(normalizeWakeText('哈喽~波波...')).toBe('哈喽波波')
      expect(normalizeWakeText('（哈喽）波波')).toBe('哈喽波波')
    })

    it('空输入返回空字符串', () => {
      expect(normalizeWakeText('')).toBe('')
      expect(normalizeWakeText(null)).toBe('')
      expect(normalizeWakeText(undefined)).toBe('')
    })

    it('纯标点返回空', () => {
      expect(normalizeWakeText('，。！？')).toBe('')
    })
  })

  describe('matchesWakeWord', () => {
    describe('精确匹配（WAKE_PATTERNS 子串）', () => {
      it('标准唤醒词', () => {
        expect(matchesWakeWord('哈喽波波')).toBe(true)
        expect(matchesWakeWord('哈罗波波')).toBe(true)
        expect(matchesWakeWord('哈喽啵啵')).toBe(true)
        // F-7：用户实际发音"哈啰波波"（luó）
        expect(matchesWakeWord('哈啰波波')).toBe(true)
        expect(matchesWakeWord('哈啰啵啵')).toBe(true)
        // F-20：Whisper 实测把"哈啰波波"转写为"哈喽伴伴"（bàn 近音）
        expect(matchesWakeWord('哈喽伴伴')).toBe(true)
        expect(matchesWakeWord('哈罗宝宝')).toBe(true)
      })

      it('带前后缀的句子中包含唤醒词', () => {
        expect(matchesWakeWord('嗯哈喽波波你好')).toBe(true)
        expect(matchesWakeWord('那个哈喽波波可以吗')).toBe(true)
      })

      it('声纹登录唤醒词', () => {
        expect(matchesWakeWord('你好波波')).toBe(true)
        expect(matchesWakeWord('你好啵啵')).toBe(true)
        expect(matchesWakeWord('你好bobo')).toBe(true)
      })

      it('英文变体', () => {
        expect(matchesWakeWord('hellobobo')).toBe(true)
        expect(matchesWakeWord('Hello Bobo')).toBe(true)
        expect(matchesWakeWord('hello波波')).toBe(true)
      })

      it('Whisper 误识别变体', () => {
        expect(matchesWakeWord('哈喽波播')).toBe(true)
        expect(matchesWakeWord('哈喽播播')).toBe(true)
        expect(matchesWakeWord('蛤喽波波')).toBe(true)
        expect(matchesWakeWord('哈喽铂铂')).toBe(true)
        expect(matchesWakeWord('哈喽伯伯')).toBe(true)
      })
    })

    describe('拼音模糊匹配', () => {
      it('哈+喽+波+波 组合', () => {
        expect(matchesWakeWord('哈喽波波')).toBe(true)
      })

      it('蛤+喽+啵+啵', () => {
        expect(matchesWakeWord('蛤喽啵啵')).toBe(true)
      })

      it('嘿+罗+播+播', () => {
        expect(matchesWakeWord('嘿罗播播')).toBe(true)
      })

      it('哎+楼+伯+伯', () => {
        expect(matchesWakeWord('哎楼伯伯')).toBe(true)
      })
    })

    describe('英文部分匹配', () => {
      it('hello + bobo 组合', () => {
        expect(matchesWakeWord('well hello there bobo')).toBe(true)
      })

      it('halo + 波波', () => {
        expect(matchesWakeWord('halo 波波')).toBe(true)
      })

      it('hello + 啵啵', () => {
        expect(matchesWakeWord('hello 啵啵')).toBe(true)
      })
    })

    describe('不匹配', () => {
      it('无关文本', () => {
        expect(matchesWakeWord('今天天气真好')).toBe(false)
        expect(matchesWakeWord('我想和你聊天')).toBe(false)
      })

      it('空输入', () => {
        expect(matchesWakeWord('')).toBe(false)
        expect(matchesWakeWord(null)).toBe(false)
        expect(matchesWakeWord(undefined)).toBe(false)
      })

      it('部分匹配不算（缺字）', () => {
        expect(matchesWakeWord('哈喽')).toBe(false)
        expect(matchesWakeWord('波波')).toBe(false)
      })

      it('纯 hello 不带 bobo', () => {
        expect(matchesWakeWord('hello world')).toBe(false)
      })
    })
  })
})
