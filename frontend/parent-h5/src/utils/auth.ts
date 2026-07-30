const TOKEN_KEY = 'parent_token'
const REFRESH_KEY = 'parent_refresh_token'
const USER_KEY = 'parent_user'

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

/** 获取正式 Token */
export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

/** 保存正式 Token */
export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

/** 获取 Refresh Token */
export function getRefreshToken(): string {
  return localStorage.getItem(REFRESH_KEY) || ''
}

/** 保存 Refresh Token */
export function setRefreshToken(token: string): void {
  localStorage.setItem(REFRESH_KEY, token)
}

/** 清除所有认证信息 */
export function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(REFRESH_KEY)
  localStorage.removeItem(USER_KEY)
}

/** 保存用户信息 */
export function setUser(user: ParentUser): void {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

/** 获取用户信息 */
export function getUser(): ParentUser | null {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null') as ParentUser | null
  } catch {
    return null
  }
}

/** 检查是否已登录（有正式 token） */
export function isAuthenticated(): boolean {
  return !!getToken()
}
