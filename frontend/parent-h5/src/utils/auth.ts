const TOKEN_KEY = 'parent_token'
// AUD-007：双 token + 用户信息移出 localStorage，改存 sessionStorage（会话级）
// 与 student-h5 策略对齐：XSS 单点突破不再获得持久凭证；关闭浏览器自动清除
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
  return sessionStorage.getItem(TOKEN_KEY) || ''
}

/** 保存正式 Token */
export function setToken(token: string): void {
  sessionStorage.setItem(TOKEN_KEY, token)
}

/** 获取 Refresh Token */
export function getRefreshToken(): string {
  return sessionStorage.getItem(REFRESH_KEY) || ''
}

/** 保存 Refresh Token */
export function setRefreshToken(token: string): void {
  sessionStorage.setItem(REFRESH_KEY, token)
}

/** 清除所有认证信息 */
export function clearAuth(): void {
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(REFRESH_KEY)
  sessionStorage.removeItem(USER_KEY)
}

/** 保存用户信息 */
export function setUser(user: ParentUser): void {
  sessionStorage.setItem(USER_KEY, JSON.stringify(user))
}

/** 获取用户信息 */
export function getUser(): ParentUser | null {
  try {
    return JSON.parse(sessionStorage.getItem(USER_KEY) || 'null') as ParentUser | null
  } catch {
    return null
  }
}

/** 检查是否已登录（有正式 token） */
export function isAuthenticated(): boolean {
  return !!getToken()
}
