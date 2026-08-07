// doing/73 T3a：auth 平台化测试（utils/auth.ts 走 createPlatformTokens + sessionStorageImpl）
// 语义：与迁移前完全等价（'' 空串语义、parent_ 前缀、双 token + 用户信息 JSON）
import { describe, it, expect, beforeEach } from 'vitest'
import {
  getToken, setToken, getRefreshToken, setRefreshToken, clearAuth,
  setUser, getUser, isAuthenticated,
} from '../utils/auth'

describe('auth（PlatformStorage 平台化）', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it("set/get token 读写一致（'' 空串语义保持）", () => {
    expect(getToken()).toBe('')
    setToken('t1')
    expect(getToken()).toBe('t1')
  })

  it('refresh token 读写一致', () => {
    setRefreshToken('r1')
    expect(getRefreshToken()).toBe('r1')
  })

  it('写入 parent_ 前缀键（token/refresh/user）', () => {
    setToken('t')
    setRefreshToken('r')
    setUser({ parentId: 'p1', displayName: '家长', children: [] })
    expect(sessionStorage.getItem('parent_token')).toBe('t')
    expect(sessionStorage.getItem('parent_refresh')).toBe('r')
    expect(sessionStorage.getItem('parent_user')).toBeTruthy()
  })

  it('setUser/getUser JSON 往返', () => {
    const user = { parentId: 'p1', displayName: '家长', children: [{ userId: 'c1', nickname: '小明' }] }
    setUser(user)
    expect(getUser()).toEqual(user)
  })

  it('getUser 遇非法 JSON 返回 null', () => {
    sessionStorage.setItem('parent_user', 'not-json')
    expect(getUser()).toBeNull()
  })

  it('clearAuth 清除 token/refresh/user 三键', () => {
    setToken('t')
    setRefreshToken('r')
    setUser({ parentId: 'p1', displayName: '家长', children: [] })
    clearAuth()
    expect(getToken()).toBe('')
    expect(getRefreshToken()).toBe('')
    expect(sessionStorage.getItem('parent_user')).toBeNull()
  })

  it('isAuthenticated 依据正式 token', () => {
    expect(isAuthenticated()).toBe(false)
    setToken('t')
    expect(isAuthenticated()).toBe(true)
  })
})
