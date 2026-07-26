const TOKEN_KEY = 'parent_token'
const INITIAL_TOKEN_KEY = 'parent_initial_token'

/** 获取正式 Token */
export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

/** 保存正式 Token */
export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token)
}

/** 清除 Token */
export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

/** 获取初始 Token（从 URL 参数或缓存） */
export function getInitialToken() {
  return localStorage.getItem(INITIAL_TOKEN_KEY) || ''
}

/** 保存初始 Token */
export function setInitialToken(token) {
  localStorage.setItem(INITIAL_TOKEN_KEY, token)
}

/**
 * 从 URL 参数中提取 token
 * H5: ?token=xxx
 * P2 小程序阶段：改为从 scene 参数提取
 */
export function extractTokenFromUrl() {
  const params = new URLSearchParams(window.location.search)
  const token = params.get('token')
  if (token) {
    setInitialToken(token)
    return token
  }
  return getInitialToken()
}

/** 检查是否已登录（有正式 token） */
export function isAuthenticated() {
  return !!getToken()
}
