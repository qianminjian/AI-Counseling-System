import { describe, it, expect } from 'vitest'
import { decodeJwtPayload } from '../utils/jwt'

/** base64url 编码 UTF-8 JSON（与后端 JWT 签发格式一致） */
function b64url(json: string): string {
  const utf8 = new TextEncoder().encode(json)
  let bin = ''
  utf8.forEach(b => { bin += String.fromCharCode(b) })
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function token(payload: string): string {
  return `header.${b64url(payload)}.signature`
}

describe('decodeJwtPayload（F-08）', () => {
  it('三段式 token 正常解码 payload', () => {
    const t = token(JSON.stringify({ sub: 'u1', userType: 'TEACHER' }))
    const payload = decodeJwtPayload(t)
    expect(payload).not.toBeNull()
    expect(payload?.sub).toBe('u1')
    expect(payload?.userType).toBe('TEACHER')
  })

  it('UTF-8 中文 displayName 不乱码（base64url 格式）', () => {
    const t = token(JSON.stringify({ displayName: '小明老师' }))
    const payload = decodeJwtPayload(t)
    expect(payload?.displayName).toBe('小明老师')
  })

  it('非三段式 token 返回 null（不抛异常）', () => {
    expect(decodeJwtPayload('no-dots-here')).toBeNull()
    expect(decodeJwtPayload('a.b')).toBeNull()
  })

  it('空 token / 空 payload 段返回 null', () => {
    expect(decodeJwtPayload('')).toBeNull()
    expect(decodeJwtPayload('a..c')).toBeNull()
  })

  it('非法 base64 字符返回 null（不抛异常）', () => {
    expect(decodeJwtPayload('h.!!!.s')).toBeNull()
  })
})
