/**
 * doing/73 T3（AC-5）：认证存取平台化
 * 底层 = shared createPlatformTokens('parent_', sessionStorageImpl)（T1 注入改造），
 * 键名统一为 parent_token / parent_refresh / parent_user（历史差异 parent_refresh_token 收敛，
 * 会话级存储 + 双 token 语义不变，AUD-007）
 * 导出签名与迁移前完全等价：getToken 返回 '' 空串语义（适配共享 TokenStorage 的 null）
 */
import { createPlatformTokens } from '../../../shared/src/auth-transport/tokenStorage'
import { sessionStorageImpl } from '../platform/storage'

const USER_KEY = 'parent_user'

const tokenStorage = createPlatformTokens('parent_', sessionStorageImpl)

export interface ParentUser {
  parentId: string
  displayName: string
  children: ChildInfo[]
}

export interface ChildInfo {
  userId: string
  nickname: string
  gradeCode?: string
  classCode?: string
}

/** 获取正式 Token（无值返回 ''） */
export function getToken(): string {
  return tokenStorage.getToken() || ''
}

/** 保存正式 Token */
export function setToken(token: string): void {
  tokenStorage.setToken(token)
}

/** 获取 Refresh Token（无值返回 ''） */
export function getRefreshToken(): string {
  return tokenStorage.getRefreshToken() || ''
}

/** 保存 Refresh Token */
export function setRefreshToken(token: string): void {
  tokenStorage.setRefreshToken(token)
}

/** 清除所有认证信息（双 token + 用户信息三键） */
export function clearAuth(): void {
  tokenStorage.clear()
}

/** 保存用户信息 */
export function setUser(user: ParentUser): void {
  sessionStorageImpl.set(USER_KEY, JSON.stringify(user))
}

/** 获取用户信息（非法 JSON 返回 null） */
export function getUser(): ParentUser | null {
  const raw = sessionStorageImpl.get(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as ParentUser
  } catch {
    return null
  }
}

/** 检查是否已登录（有正式 token） */
export function isAuthenticated(): boolean {
  return !!getToken()
}
