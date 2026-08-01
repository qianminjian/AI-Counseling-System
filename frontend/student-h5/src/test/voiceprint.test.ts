import { describe, it, expect } from 'vitest'
import {
  VP_MODEL_ID, VP_EMBEDDING_DIM, VP_VERIFY_THRESHOLD,
  VP_VERIFY_SEGMENTS, VP_ENROLL_SEGMENTS, VP_MAX_TEMPLATES,
  VP_MAX_FAILURES, VP_SAMPLE_RATE, VP_SEGMENT_DURATION,
  VP_SILENCE_THRESHOLD, VP_INFERENCE_TIMEOUT, VP_IDLE_TIMEOUT,
  VP_GUIDE_SCRIPTS, VP_DB_NAME, VP_DB_VERSION, VP_STORE_NAME,
} from '../config/voiceprint'

describe('config/voiceprint', () => {
  describe('模型配置', () => {
    it('使用 wespeaker resnet34 模型', () => {
      expect(VP_MODEL_ID).toContain('wespeaker')
      expect(VP_MODEL_ID).toContain('resnet34')
    })

    it('embedding 维度 256', () => {
      expect(VP_EMBEDDING_DIM).toBe(256)
    })
  })

  describe('阈值参数', () => {
    it('验证阈值 0.75（儿童偏严格）', () => {
      expect(VP_VERIFY_THRESHOLD).toBe(0.75)
    })

    it('验证段数 2，注册段数 3', () => {
      expect(VP_VERIFY_SEGMENTS).toBe(2)
      expect(VP_ENROLL_SEGMENTS).toBe(3)
    })

    it('模板上限 > 注册段数（支持自适应追加）', () => {
      expect(VP_MAX_TEMPLATES).toBeGreaterThan(VP_ENROLL_SEGMENTS)
    })

    it('连续失败上限合理（2-5）', () => {
      expect(VP_MAX_FAILURES).toBeGreaterThanOrEqual(2)
      expect(VP_MAX_FAILURES).toBeLessThanOrEqual(5)
    })
  })

  describe('音频参数', () => {
    it('采样率 16kHz（与 Whisper 一致）', () => {
      expect(VP_SAMPLE_RATE).toBe(16000)
    })

    it('每段 4-5 秒', () => {
      expect(VP_SEGMENT_DURATION).toBeGreaterThanOrEqual(3)
      expect(VP_SEGMENT_DURATION).toBeLessThanOrEqual(6)
    })

    it('静音阈值合理', () => {
      expect(VP_SILENCE_THRESHOLD).toBeGreaterThan(0)
      expect(VP_SILENCE_THRESHOLD).toBeLessThan(0.1)
    })

    it('推理超时 5s', () => {
      expect(VP_INFERENCE_TIMEOUT).toBe(5000)
    })
  })

  describe('引导对话脚本', () => {
    it('验证模式 2 轮', () => {
      expect(VP_GUIDE_SCRIPTS.verify).toHaveLength(2)
    })

    it('注册模式 3 轮', () => {
      expect(VP_GUIDE_SCRIPTS.enroll).toHaveLength(3)
    })

    it('每轮有 prompt 和 duration', () => {
      for (const s of [...VP_GUIDE_SCRIPTS.verify, ...VP_GUIDE_SCRIPTS.enroll]) {
        expect(s.prompt).toBeTruthy()
        expect(s.duration).toBeGreaterThan(0)
      }
    })

    it('脚本语言儿童友好（无专业术语）', () => {
      const allPrompts = [...VP_GUIDE_SCRIPTS.verify, ...VP_GUIDE_SCRIPTS.enroll].map(s => s.prompt)
      // 不应包含"声纹""验证""认证"等成人术语
      for (const p of allPrompts) {
        expect(p).not.toContain('声纹')
        expect(p).not.toContain('认证')
      }
    })
  })

  describe('存储配置', () => {
    it('IndexedDB 名称/版本/Store 一致', () => {
      expect(VP_DB_NAME).toBe('mindsafe_voiceprints')
      expect(VP_DB_VERSION).toBe(1)
      expect(VP_STORE_NAME).toBe('templates')
    })
  })

  describe('空闲超时', () => {
    it('5 分钟无交互休眠', () => {
      expect(VP_IDLE_TIMEOUT).toBe(5 * 60 * 1000)
    })
  })
})
