/**
 * doing/85 TOC-001/002：toC 家庭账号与孩子档案 API 服务层
 * 复用 parent-h5 平台请求工厂（Bearer 注入 / 401 刷新重放）。
 * 注册/登录为匿名端点（验证码回显，演示环境）；档案 CRUD 需 toC token。
 */
import { tocRequest, tocTokens } from './request'
import { sessionStorageImpl } from '../platform/storage' // familyAccountId 为 toC 特有键，不经通用 TokenStorage 接口

/** 登录/注册成功后持久化 toC token（经 TokenStorage 接口落盘，与 request 工厂共享 tocTokens 单例） */
export function saveTocSession(session: TocSession): void {
  tocTokens.setToken(session.token)
  sessionStorageImpl.set('toc_family_account_id', session.familyAccountId)
}

/** 退出登录清空 toC 会话 */
export function clearTocSession(): void {
  tocTokens.clear()
  sessionStorageImpl.remove('toc_family_account_id')
}

// ========== toC 账号 API（TOC-001） ==========

export interface TocSession {
  token: string
  familyAccountId: string
  phone: string
  displayName: string
}

export interface TocSendCodeResult {
  phone: string
  expiresInSeconds: number
  /** 演示环境回显验证码（生产接入短信通道后移除） */
  code: string
}

export function sendTocCode(phone: string) {
  return tocRequest<TocSendCodeResult>('/toc/auth/send-code', { method: 'POST', data: { phone } })
}

/** 注册成功后 token 需手动落盘（注册/登录响应含 token，request 工厂不自动写） */
export function tocRegister(phone: string, code: string) {
  return tocRequest<TocSession>('/toc/auth/register', { method: 'POST', data: { phone, code } })
}

export function tocLogin(phone: string, code: string) {
  return tocRequest<TocSession>('/toc/auth/login', { method: 'POST', data: { phone, code } })
}

// ========== 孩子档案 API（TOC-002） ==========

export interface TocChildProfile {
  profileId: string
  familyAccountId: string
  nickname: string
  age?: number
  gender?: string
  interests?: string
  createdAt?: string
}

export function listTocProfiles() {
  return tocRequest<TocChildProfile[]>('/toc/profiles', { method: 'GET' })
}

export function createTocProfile(body: Partial<TocChildProfile>) {
  return tocRequest<TocChildProfile>('/toc/profiles', { method: 'POST', data: body })
}

export function updateTocProfile(profileId: string, body: Partial<TocChildProfile>) {
  return tocRequest<TocChildProfile>(`/toc/profiles/${profileId}`, { method: 'PUT', data: body })
}

export function deleteTocProfile(profileId: string) {
  return tocRequest<void>(`/toc/profiles/${profileId}`, { method: 'DELETE' })
}

// ========== 家庭设备 API（TOC-003，联动 doing/84 CFG-010） ==========

// ========== 隐私控制 API（TOC-007） ==========

export interface TocPrivacyOverview {
  familyAccountId: string
  phone: string
  status: string
  profileCount: number
  deviceCount: number
  dataRetentionNote: string
}

/** 数据清单预览 */
export function getTocPrivacyOverview() {
  return tocRequest<TocPrivacyOverview>('/toc/privacy', { method: 'GET' })
}

/** 删除全部家庭数据（不可逆，X-Confirm 二次确认） */
export function deleteTocPrivacyData() {
  return tocRequest<Record<string, unknown>>('/toc/privacy/data', {
    method: 'DELETE',
    headers: { 'X-Confirm': 'CONFIRM' },
  })
}
