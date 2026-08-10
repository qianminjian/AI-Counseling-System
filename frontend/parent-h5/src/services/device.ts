/**
 * CFG-001/004（doing/84 §六.2）：无屏终端扫码配置 API 服务层
 * 复用 parent-h5 平台请求工厂（Bearer 注入 / 401 刷新重放），与家长 API 同构。
 * 扫码入口页（/p/:v/:deviceCode）为匿名可查端点（info/status），绑定类端点需登录态。
 */
import { createPlatformRequest } from '../platform/request'
import { createPlatformTokens } from '../../../shared/src/auth-transport/tokenStorage'
import { sessionStorageImpl } from '../platform/storage'

const request = createPlatformRequest({
  storage: createPlatformTokens('parent_', sessionStorageImpl),
})

// ========== 设备 API ==========

export interface DeviceInfo {
  deviceCode: string
  deviceType: string
  /** 设备码尾号（脱敏展示） */
  codeTail: string
  /** 是否已绑定 */
  bound: boolean
  status: string
}

export interface DeviceStatus {
  deviceCode: string
  online: boolean
  firmwareVersion?: string
  status: string
}

export interface BindCodeResult {
  deviceCode: string
  /** 6 位验证码（明文仅返回一次，供设备语音播报/人工输入） */
  code: string
  expiresAt: string
}

export interface BindResult {
  deviceCode: string
  status: string
  boundAt: string
}

/** 扫码入口自检分流（匿名） */
export function getDeviceInfo(deviceCode: string) {
  return request<DeviceInfo>(`/device/${deviceCode}/info`, { method: 'GET' })
}

/** 回连检查轮询（匿名，3s 间隔） */
export function getDeviceStatus(deviceCode: string) {
  return request<DeviceStatus>(`/device/${deviceCode}/status`, { method: 'GET' })
}

/** 生成绑定验证码会话（登录态，触发设备语音播报） */
export function createBindCode(deviceCode: string) {
  return request<BindCodeResult>(`/device/${deviceCode}/bind-code`, { method: 'POST' })
}

/** 绑定设备（登录态，归属 + 验证码双因子） */
export function bindDevice(
  deviceCode: string,
  data: { bindType: string; bindTargetId: string; code: string }
) {
  return request<BindResult>(`/device/${deviceCode}/bind`, { method: 'POST', data })
}
