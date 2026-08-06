import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { readLocalStorageSafe, writeLocalStorageSafe } from '../utils/storage'

describe('storage 失败安全工具（P0-2）', () => {
  const KEY = 'test_key'

  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('readLocalStorageSafe', () => {
    it('正常读取返回存储值', () => {
      localStorage.setItem(KEY, 'v1')
      expect(readLocalStorageSafe(KEY, 'fallback')).toBe('v1')
    })

    it('键不存在返回 fallback', () => {
      expect(readLocalStorageSafe(KEY, 'fallback')).toBe('fallback')
    })

    it('存储抛异常（隐私模式 SecurityError）返回 fallback 不崩溃', () => {
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
        throw new DOMException('denied', 'SecurityError')
      })
      expect(readLocalStorageSafe(KEY, 'fallback')).toBe('fallback')
    })
  })

  describe('writeLocalStorageSafe', () => {
    it('正常写入不抛异常', () => {
      expect(() => writeLocalStorageSafe(KEY, 'v1')).not.toThrow()
      expect(localStorage.getItem(KEY)).toBe('v1')
    })

    it('存储抛异常（隐私模式 SecurityError）静默跳过不抛', () => {
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new DOMException('denied', 'SecurityError')
      })
      expect(() => writeLocalStorageSafe(KEY, 'v1')).not.toThrow()
    })
  })
})
