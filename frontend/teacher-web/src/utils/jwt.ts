/**
 * JWT 解码工具（F-08）
 *
 * 背景（深度审计 F-08）：App.tsx 直接 `JSON.parse(atob(token.split('.')[1]))`——
 * ① JWT payload 段是 base64url（- _ 字符集），原生 atob 只认标准 base64，非 ASCII 签名/填充
 *    场景直接抛错 → catch → 登录态静默丢失；
 * ② 即便侥幸解码，UTF-8 多字节字符（中文 displayName）会被 atob 按 Latin-1 截断成乱码。
 * 此处统一安全解码：base64url 归一化 + TextDecoder UTF-8 还原，非法 token 一律返回 null。
 */

/** 解析 JWT payload（UTF-8 安全，兼容 base64url）；非法 token 返回 null（不抛出） */
export function decodeJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const parts = token.split('.')
    if (parts.length !== 3 || !parts[1]) return null
    // base64url → 标准 base64（补 padding）
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = b64.padEnd(Math.ceil(b64.length / 4) * 4, '=')
    const bytes = atob(padded)
    const utf8 = new TextDecoder().decode(Uint8Array.from(bytes, c => c.charCodeAt(0)))
    return JSON.parse(utf8)
  } catch {
    return null
  }
}
