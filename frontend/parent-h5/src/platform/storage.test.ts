// doing/73 T1（AC-3）：platform 适配层——PlatformStorage H5 实现
// 语义：sessionStorage 包装（AUD-007 会话级，键名透传由调用方控制）
import { describe, it, expect, beforeEach } from 'vitest'
import { sessionStorageImpl } from './storage'

describe('sessionStorageImpl（PlatformStorage H5 实现）', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('set/get 读写一致', () => {
    sessionStorageImpl.set('k', 'v')
    expect(sessionStorageImpl.get('k')).toBe('v')
  })

  it('未设置的键返回 null', () => {
    expect(sessionStorageImpl.get('missing')).toBeNull()
  })

  it('remove 删除键', () => {
    sessionStorageImpl.set('k', 'v')
    sessionStorageImpl.remove('k')
    expect(sessionStorageImpl.get('k')).toBeNull()
    expect(sessionStorage.getItem('k')).toBeNull()
  })

  it('底层确实写入 sessionStorage（会话级语义保持）', () => {
    sessionStorageImpl.set('k', 'v')
    expect(sessionStorage.getItem('k')).toBe('v')
  })
})
