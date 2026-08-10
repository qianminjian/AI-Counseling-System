/**
 * doing/85 TOC-001/002：toC 家庭账号与孩子档案 API 服务层
 * 复用 parent-h5 平台请求工厂（Bearer 注入 / 401 刷新重放）。
 * 注册/登录为匿名端点（验证码回显，演示环境）；档案 CRUD 需 toC token。
 */
import { createPlatformRequest } from '../platform/request'
import { createPlatformTokens } from '../../../shared/src/auth-transport/tokenStorage'
import { sessionStorageImpl } from '../platform/storage'

const request = createPlatformRequest({
  storage: createPlatformTokens('toc_', sessionStorageImpl),
})

/** 登录/注册成功后持久化 toC token（storage 由工厂内部持有，此处显式落盘） */
export function saveTocSession(session: TocSession): void {
  sessionStorageImpl.set('toc_token', session.token)
  sessionStorageImpl.set('toc_family_account_id', session.familyAccountId)
}

/** 退出登录清空 toC 会话 */
export function clearTocSession(): void {
  sessionStorageImpl.remove('toc_token')
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
  return request<TocSendCodeResult>('/toc/auth/send-code', { method: 'POST', data: { phone } })
}

/** 注册成功后 token 需手动落盘（注册/登录响应含 token，request 工厂不自动写） */
export function tocRegister(phone: string, code: string) {
  return request<TocSession>('/toc/auth/register', { method: 'POST', data: { phone, code } })
}

export function tocLogin(phone: string, code: string) {
  return request<TocSession>('/toc/auth/login', { method: 'POST', data: { phone, code } })
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
  return request<TocChildProfile[]>('/toc/profiles', { method: 'GET' })
}

export function createTocProfile(body: Partial<TocChildProfile>) {
  return request<TocChildProfile>('/toc/profiles', { method: 'POST', data: body })
}

export function updateTocProfile(profileId: string, body: Partial<TocChildProfile>) {
  return request<TocChildProfile>(`/toc/profiles/${profileId}`, { method: 'PUT', data: body })
}

export function deleteTocProfile(profileId: string) {
  return request<void>(`/toc/profiles/${profileId}`, { method: 'DELETE' })
}

// ========== 家庭设备 API（TOC-003，联动 doing/84 CFG-010） ==========

export interface TocDeviceItem {
  deviceCode: string
  deviceType: string
  firmwareVersion?: string
  status: string
  online: boolean
  binding?: { bindType: string; bindTargetId: string; boundAt?: string } | null
}

/** 家庭设备列表（本人账号 FAMILY 绑定） */
export function listTocDevices() {
  return request<TocDeviceItem[]>('/toc/devices', { method: 'GET' })
}

/** 发起家庭绑定验证码会话（触发设备语音播报） */
export function createTocBindCode(deviceCode: string) {
  return request<{ deviceCode: string; code: string; expiresAt: string }>(
    `/toc/devices/${deviceCode}/bind-code`, { method: 'POST' })
}

/** 家庭绑定：验证码 + 可选孩子档案 */
export function tocBindDevice(deviceCode: string, body: { code: string; profileId?: string }) {
  return request<{ deviceCode: string; status: string; boundAt: string }>(
    `/toc/devices/${deviceCode}/bind`, { method: 'POST', data: body })
}

/** 解绑 */
export function tocUnbindDevice(deviceCode: string) {
  return request<Record<string, unknown>>(`/toc/devices/${deviceCode}/unbind`, { method: 'POST' })
}

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
  return request<TocPrivacyOverview>('/toc/privacy', { method: 'GET' })
}

/** 删除全部家庭数据（不可逆，X-Confirm 二次确认） */
export function deleteTocPrivacyData() {
  return request<Record<string, unknown>>('/toc/privacy/data', {
    method: 'DELETE',
    headers: { 'X-Confirm': 'CONFIRM' },
  })
}
