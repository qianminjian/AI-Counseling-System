/**
 * jwt 安全解码工具测试（F-08）
 * 覆盖：base64url 归一化（-/_）、UTF-8 中文还原、非法 token 返回 null（不抛出）。
 */
import { describe, it, expect } from 'vitest'
import { decodeJwtPayload } from '../utils/jwt'

describe('decodeJwtPayload（JWT UTF-8 安全解码）', () => {
  it('标准 JWT：解析 payload 字段', () => {
    // header.payload.signature（payload: {"sub":"u1","userType":"teacher"}）
    const payload = btoa(JSON.stringify({ sub: 'u1', userType: 'teacher' }))
    const token = `h.${payload}.sig`
    const decoded = decodeJwtPayload(token)
    expect(decoded).toEqual({ sub: 'u1', userType: 'teacher' })
  })

  it('中文 displayName：UTF-8 多字节正确还原（base64url - _）', () => {
    const raw = JSON.stringify({ displayName: '钱老师', pseudonym: '钱_老师-1' })
    // 模拟 base64url：标准 btoa 后替换 +/ → -_，去掉 padding
    const b64url = btoa(unescape(encodeURIComponent(raw))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
    const token = `h.${b64url}.sig`
    const decoded = decodeJwtPayload(token)
    expect(decoded?.displayName).toBe('钱老师')
    expect(decoded?.pseudonym).toBe('钱_老师-1')
  })

  it('非法 token：段数不足 → null', () => {
    expect(decodeJwtPayload('abc')).toBeNull()
    expect(decodeJwtPayload('a.b')).toBeNull()
  })

  it('非法 token：空 payload 段 → null', () => {
    expect(decodeJwtPayload('h..sig')).toBeNull()
  })

  it('非法 token：非 JSON payload → null（不抛出）', () => {
    expect(decodeJwtPayload(`h.${btoa('not-json')}.sig`)).toBeNull()
  })

  it('非法 token：乱码 base64 → null（不抛出）', () => {
    expect(decodeJwtPayload('h.!!!.sig')).toBeNull()
  })
})
