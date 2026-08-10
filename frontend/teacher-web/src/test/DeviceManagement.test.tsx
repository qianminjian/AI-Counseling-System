/**
 * CFG-006/007/008（doing/84 §四.5/§四.6）：无屏终端设备管理面板测试
 * 覆盖：归属筛选加载列表、在线/离线状态 Tag、绑定流程（验证码双因子）、
 * 声纹录入编排（发起 → 轮询 → 完成）。
 * 模式：api 模块 mock（QualityPanel.test.tsx 同构）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

const mockGetDeviceList = vi.fn(() => Promise.resolve([
  { deviceCode: 'K7M2P9XW4AQ', deviceType: 'desk_toy', firmwareVersion: 'v0.1.0', status: 'ONLINE_BOUND', online: true, lastOnlineAt: new Date().toISOString() },
  { deviceCode: 'A1B2C3D4E5F', deviceType: 'plush', firmwareVersion: 'v0.1.0', status: 'ONLINE_BOUND', online: false, lastOnlineAt: '2026-08-08T10:00:00Z' },
]))

const mockCreateBindCode = vi.fn(() => Promise.resolve({ code: '123456', expiresAt: new Date().toISOString() }))
const mockBindDevice = vi.fn(() => Promise.resolve({ status: 'ONLINE_BOUND', boundAt: new Date().toISOString() }))
const mockCreateVoiceprintTask = vi.fn(() => Promise.resolve({
  taskId: 't-1', deviceCode: 'K7M2P9XW4AQ', studentId: 'stu-1', phase: 'INITIATED',
}))
const mockGetVoiceprintTask = vi.fn(() => Promise.resolve({
  taskId: 't-1', deviceCode: 'K7M2P9XW4AQ', studentId: 'stu-1', phase: 'COMPLETED',
}))

vi.mock('../api', () => ({
  getDeviceList: (...args: unknown[]) => mockGetDeviceList(...args),
  createBindCode: (...args: unknown[]) => mockCreateBindCode(...args),
  bindDevice: (...args: unknown[]) => mockBindDevice(...args),
  createVoiceprintTask: (...args: unknown[]) => mockCreateVoiceprintTask(...args),
  getVoiceprintTask: (...args: unknown[]) => mockGetVoiceprintTask(...args),
}))

import DeviceManagement from '../components/teacher/DeviceManagement'

describe('DeviceManagement（CFG-006/007/008）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('CFG-008：输入归属 ID 查询 → 展示设备列表与在线/离线状态', async () => {
    render(<DeviceManagement />)
    // 输入归属 ID 并查询
    fireEvent.change(screen.getByPlaceholderText('归属 ID（学校/班级/咨询室）'), { target: { value: 'room-1' } })
    fireEvent.click(screen.getByRole('button', { name: /查\s*询/ }))
    expect(await screen.findByText('K7M2P9XW4AQ')).toBeInTheDocument()
    expect(screen.getByText('A1B2C3D4E5F')).toBeInTheDocument()
    // 在线/离线 Tag
    expect(screen.getByText('在线')).toBeInTheDocument()
    expect(screen.getByText('离线')).toBeInTheDocument()
    expect(mockGetDeviceList).toHaveBeenCalledWith('CLASS', 'room-1')
  })

  it('CFG-004/008：绑定流程——获取验证码 + 6 位码提交', async () => {
    render(<DeviceManagement />)
    fireEvent.click(screen.getByRole('button', { name: /绑定设备/ }))
    // 输入设备码 → 获取验证码
    fireEvent.change(screen.getByPlaceholderText('如 K7M2P9XW4AQ'), { target: { value: 'K7M2P9XW4AQ' } })
    fireEvent.click(screen.getByRole('button', { name: '获取验证码' }))
    await waitFor(() => expect(mockCreateBindCode).toHaveBeenCalledWith('K7M2P9XW4AQ'))
    // 输入 6 位验证码 → 确认绑定
    fireEvent.change(screen.getByPlaceholderText('6 位验证码'), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: '确认绑定' }))
    await waitFor(() => expect(mockBindDevice).toHaveBeenCalledWith('K7M2P9XW4AQ', {
      bindType: 'CLASS', bindTargetId: '', code: '123456',
    }))
  })

  it('CFG-006：声纹录入——发起任务 + 轮询完成（AC-84-13/14）', async () => {
    render(<DeviceManagement />)
    fireEvent.change(screen.getByPlaceholderText('归属 ID（学校/班级/咨询室）'), { target: { value: 'room-1' } })
    fireEvent.click(screen.getByRole('button', { name: /查\s*询/ }))
    // 打开详情
    fireEvent.click((await screen.findAllByRole('button', { name: /查看\/声纹/ }))[0])
    // 输入学生 ID 并发起
    fireEvent.change(screen.getByPlaceholderText('学生 ID（声纹录入对象）'), { target: { value: 'stu-1' } })
    fireEvent.click(screen.getByRole('button', { name: /发起声纹录入/ }))
    await waitFor(() => expect(mockCreateVoiceprintTask).toHaveBeenCalledWith('K7M2P9XW4AQ', 'stu-1'))
    // 轮询命中 COMPLETED → 完成文案
    expect(await screen.findByText('录入完成')).toBeInTheDocument()
  })
})
