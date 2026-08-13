/**
 * CFG-001/004（doing/84 §六.2）：无屏终端扫码配置 API 服务层
 * 复用 parent-h5 平台请求工厂（Bearer 注入 / 401 刷新重放），与家长 API 同构。
 * 扫码入口页（/p/:v/:deviceCode）为匿名可查端点（info/status），绑定类端点需登录态。
 */
import { parentRequest, tocRequest } from './request'
import { apiPath } from './endpoints'

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
  return parentRequest<DeviceInfo>(apiPath('deviceInfo', { deviceCode }), { method: 'GET' })
}

/** 回连检查轮询（匿名，3s 间隔） */
export function getDeviceStatus(deviceCode: string) {
  return parentRequest<DeviceStatus>(apiPath('deviceStatus', { deviceCode }), { method: 'GET' })
}

/** 生成绑定验证码会话（登录态，触发设备语音播报） */
export function createBindCode(deviceCode: string) {
  return parentRequest<BindCodeResult>(apiPath('deviceBindCode', { deviceCode }), { method: 'POST' })
}

/** 绑定设备（登录态，归属 + 验证码双因子） */
export function bindDevice(
  deviceCode: string,
  data: { bindType: string; bindTargetId: string; code: string }
) {
  return parentRequest<BindResult>(apiPath('deviceBind', { deviceCode }), { method: 'POST', data })
}

// ========== 家庭登录上下文适配器（AD-008，2026-08-11） ==========
// 设备域按身份上下文收进单一模块：匿名扫码（上方 parentRequest）+ 家庭登录（本段 tocRequest），
// 共享类型语义（DeviceInfo/TocDeviceItem 同源设备概念）。原 toc.ts 设备段迁移至此。

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
  return tocRequest<TocDeviceItem[]>(apiPath('tocDevices'), { method: 'GET' })
}

/** 发起家庭绑定验证码会话（触发设备语音播报） */
export function createTocBindCode(deviceCode: string) {
  return tocRequest<{ deviceCode: string; code: string; expiresAt: string }>(
    apiPath('tocDeviceBindCode', { deviceCode }), { method: 'POST' })
}

/** 家庭绑定：验证码 + 可选孩子档案 */
export function tocBindDevice(deviceCode: string, body: { code: string; profileId?: string }) {
  return tocRequest<{ deviceCode: string; status: string; boundAt: string }>(
    apiPath('tocDeviceBind', { deviceCode }), { method: 'POST', data: body })
}

/** 解绑 */
export function tocUnbindDevice(deviceCode: string) {
  return tocRequest<Record<string, unknown>>(apiPath('tocDeviceUnbind', { deviceCode }), { method: 'POST' })
}

// ========== 远程管理偏好 API（TOC-006） ==========

export interface TocDevicePreferences {
  deviceCode: string
  volume?: number
  voicePersona?: string
  dialoguePref?: string
}

/** 查询设备偏好 */
export function getTocPreferences(deviceCode: string) {
  return tocRequest<TocDevicePreferences>(apiPath('tocDevicePreferences', { deviceCode }), { method: 'GET' })
}

/** 设置设备偏好（音量/音色/对话偏好，设备端配置拉取时下发） */
export function setTocPreferences(deviceCode: string, body: Partial<TocDevicePreferences>) {
  return tocRequest<TocDevicePreferences>(apiPath('updateTocDevicePreferences', { deviceCode }), { method: 'PUT', data: body })
}
