import { describe, it, expect, beforeEach } from 'vitest'
import {
  getToken, setToken, getRefreshToken, setRefreshToken,
  clearAuth, setUser, getUser, isAuthenticated,
} from '../utils/auth'

/**
 * AUD-022：parent-h5 覆盖率门禁提升配套测试——auth.ts 会话级 token/用户信息存取全覆盖
 * （AUD-007 后 auth 函数由 localStorage 迁 sessionStorage，行为契约在此锁定）
 */
describe('auth utils（AUD-022 补测）', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('setToken/getToken 写入会话级 storage（非 localStorage）', () => {
    setToken('token-1')
    expect(getToken()).toBe('token-1')
    expect(sessionStorage.getItem('parent_token')).toBe('token-1')
    expect(localStorage.getItem('parent_token')).toBeNull()
  })

  it('setRefreshToken/getRefreshToken 存取', () => {
    setRefreshToken('rt-1')
    expect(getRefreshToken()).toBe('rt-1')
    expect(sessionStorage.getItem('parent_refresh_token')).toBe('rt-1')
  })

  it('未设置 token 时 getToken 返回空串', () => {
    expect(getToken()).toBe('')
    expect(getRefreshToken()).toBe('')
  })

  it('isAuthenticated 依据 token 存在性', () => {
    expect(isAuthenticated()).toBe(false)
    setToken('token-2')
    expect(isAuthenticated()).toBe(true)
  })

  it('setUser/getUser 存取用户信息（含孩子列表）', () => {
    const user = {
      parentId: 'p-1',
      displayName: '家长甲',
      children: [{ userId: 'u-1', nickname: '小星', gradeCode: 'G3', classCode: 'C1' }],
    }
    setUser(user)
    expect(getUser()).toEqual(user)
  })

  it('getUser 对损坏 JSON 容错返回 null', () => {
    sessionStorage.setItem('parent_user', '{broken json')
    expect(getUser()).toBeNull()
  })

  it('getUser 未登录时返回 null', () => {
    expect(getUser()).toBeNull()
  })

  it('clearAuth 清空 token/refresh/用户信息', () => {
    setToken('t')
    setRefreshToken('r')
    setUser({ parentId: 'p', displayName: 'x', children: [] })
    clearAuth()
    expect(getToken()).toBe('')
    expect(getRefreshToken()).toBe('')
    expect(getUser()).toBeNull()
    expect(isAuthenticated()).toBe(false)
  })
})
