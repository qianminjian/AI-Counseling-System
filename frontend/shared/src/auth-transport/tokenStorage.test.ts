// @vitest-environment jsdom
/**
 * DC-005 共享认证传输模块：createSessionStorageTokens 测试
 * （SPEC §19：tokenStorage CRUD/clear）
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { createSessionStorageTokens } from './tokenStorage'

describe('createSessionStorageTokens', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('set/get 读写一致（token/refresh）', () => {
    const s = createSessionStorageTokens('app_')
    s.setToken('t1')
    s.setRefreshToken('r1')
    expect(s.getToken()).toBe('t1')
    expect(s.getRefreshToken()).toBe('r1')
  })

  it('写入 sessionStorage 且键名带前缀', () => {
    const s = createSessionStorageTokens('app_')
    s.setToken('t1')
    s.setRefreshToken('r1')
    expect(sessionStorage.getItem('app_token')).toBe('t1')
    expect(sessionStorage.getItem('app_refresh')).toBe('r1')
  })

  it('不同前缀互相隔离', () => {
    const a = createSessionStorageTokens('a_')
    const b = createSessionStorageTokens('b_')
    a.setToken('ta')
    b.setToken('tb')
    expect(a.getToken()).toBe('ta')
    expect(b.getToken()).toBe('tb')
    expect(sessionStorage.getItem('a_token')).toBe('ta')
    expect(sessionStorage.getItem('b_token')).toBe('tb')
  })

  it('初始状态返回 null', () => {
    const s = createSessionStorageTokens('app_')
    expect(s.getToken()).toBeNull()
    expect(s.getRefreshToken()).toBeNull()
  })

  it('clear 清除 token/refresh/user 三键（user 键为前缀 + user 形态）', () => {
    const s = createSessionStorageTokens('app_')
    s.setToken('t')
    s.setRefreshToken('r')
    sessionStorage.setItem('app_user', '{"id":1}')
    s.clear()
    expect(s.getToken()).toBeNull()
    expect(s.getRefreshToken()).toBeNull()
    expect(sessionStorage.getItem('app_user')).toBeNull()
  })
})
