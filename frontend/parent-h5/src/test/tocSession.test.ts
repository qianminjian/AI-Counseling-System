/**
 * doing/94 R-003：toc 会话落盘走 TokenStorage 接口测试（真实模块，不 mock）
 *
 * 此前 saveTocSession 直写 sessionStorageImpl（键名靠"巧合一致"绕过 TokenStorage 契约），
 * 改造后经 tocTokens（createPlatformTokens('toc_')）接口落盘——键名不变（向后兼容），
 * 但读写路径与 request 工厂共享同一 storage 单例。
 */
import { describe, expect, it, beforeEach } from 'vitest'
import { saveTocSession, clearTocSession } from '../services/toc'

describe('toc 会话落盘（R-003：走 TokenStorage 接口）', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('saveTocSession 经 TokenStorage 写 toc_token 键（键名与改造前一致）', () => {
    saveTocSession({ token: 'tok-1', familyAccountId: 'fam-1', phone: '13800000000', displayName: '测试家长' })
    expect(sessionStorage.getItem('toc_token')).toBe('tok-1')
    expect(sessionStorage.getItem('toc_family_account_id')).toBe('fam-1')
  })

  it('clearTocSession 清除会话键', () => {
    saveTocSession({ token: 'tok-2', familyAccountId: 'fam-2', phone: '13900000000', displayName: '测试家长' })
    clearTocSession()
    expect(sessionStorage.getItem('toc_token')).toBeNull()
    expect(sessionStorage.getItem('toc_family_account_id')).toBeNull()
  })

  it('saveTocSession 不触碰 parent_ 前缀键（身份隔离）', () => {
    sessionStorage.setItem('parent_token', 'parent-tok')
    saveTocSession({ token: 'tok-3', familyAccountId: 'fam-3', phone: '13700000000', displayName: '测试家长' })
    expect(sessionStorage.getItem('parent_token')).toBe('parent-tok')
  })
})
