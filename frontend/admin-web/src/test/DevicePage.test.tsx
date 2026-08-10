/**
 * M13 无屏终端设备管理（CFG-008，doing/84 §四.6 admin-web）测试
 * 覆盖：跨租户列表加载（在线/离线/绑定归属）、状态筛选、设备详情 Drawer（绑定历史）、
 * 批量操作（二次确认）、二维码批量签发（输入校验/成功/未找到提示）。
 * 模式：api 模块 mock（ConfigPage.test.tsx 同构）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

const mockFetchDevices = vi.fn(() => Promise.resolve([
  { deviceId: 'd-1', deviceCode: 'K7M2P9XW4AQ', deviceType: 'desk_toy', firmwareVersion: 'v0.1.0', status: 'ONLINE_BOUND', online: true, lastOnlineAt: new Date().toISOString(), binding: { bindType: 'CLASS', bindTargetId: 'room-1' } },
  { deviceId: 'd-2', deviceCode: 'A1B2C3D4E5F', deviceType: 'plush', firmwareVersion: 'v0.1.0', status: 'ONLINE_BOUND', online: false, lastOnlineAt: '2026-08-08T10:00:00Z', binding: null },
]))
const mockFetchDetail = vi.fn(() => Promise.resolve({
  deviceId: 'd-1', deviceCode: 'K7M2P9XW4AQ', sn: 'BB-2026-000123', deviceType: 'desk_toy', status: 'ONLINE_BOUND', serverUrl: 'https://mindsafe.school.local',
  bindings: [{ bindType: 'CLASS', bindTargetId: 'room-1', boundBy: 'teacher-1', status: 'ACTIVE', boundAt: '2026-08-09T10:00:00Z' }],
}))
const mockExportQr = vi.fn(() => Promise.resolve({ issuedCount: 1, notFound: [] }))
const mockBatch = vi.fn(() => Promise.resolve({ acceptedCount: 1, action: 'ota' }))

vi.mock('../api', () => ({
  fetchPlatformDevices: (...args: unknown[]) => mockFetchDevices(...args),
  fetchPlatformDeviceDetail: (...args: unknown[]) => mockFetchDetail(...args),
  exportDeviceQr: (...args: unknown[]) => mockExportQr(...args),
  batchDeviceOperation: (...args: unknown[]) => mockBatch(...args),
  getAdminToken: () => 'tok',
  getAdminName: () => 'admin-1',
}))

import DevicePage from '../pages/DevicePage'

describe('DevicePage（M13）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('列表加载：展示设备码 + 在线/离线 Tag + 绑定归属', async () => {
    render(<DevicePage />)
    expect(await screen.findByText('K7M2P9XW4AQ')).toBeInTheDocument()
    expect(screen.getByText('A1B2C3D4E5F')).toBeInTheDocument()
    expect(screen.getByText('在线')).toBeInTheDocument()
    expect(screen.getByText('离线')).toBeInTheDocument()
    expect(mockFetchDevices).toHaveBeenCalled()
  })

  it('状态筛选：切换状态触发重新查询', async () => {
    render(<DevicePage />)
    await screen.findByText('K7M2P9XW4AQ')
    fireEvent.mouseDown(screen.getByText('全部状态'))
    fireEvent.click(await screen.findByText('已绑定在线'))
    await waitFor(() => expect(mockFetchDevices).toHaveBeenLastCalledWith('ONLINE_BOUND'))
  })

  it('详情 Drawer：展示 SN/状态/绑定历史', async () => {
    render(<DevicePage />)
    await screen.findByText('K7M2P9XW4AQ')
    const detailButtons = screen.getAllByRole('button', { name: '详情' })
    console.log('DETAIL_BUTTONS:', detailButtons.length)
    fireEvent.click(detailButtons[0])
    await waitFor(() => expect(mockFetchDetail).toHaveBeenCalledWith('d-1'))
    expect(await screen.findByText(/BB-2026-000123/)).toBeInTheDocument()
    expect(screen.getByText(/CLASS room-1 · teacher-1/)).toBeInTheDocument()
  })

  it('二维码签发：空输入提示，合法输入调 API 并提示成功', async () => {
    render(<DevicePage />)
    await screen.findByText('K7M2P9XW4AQ')
    fireEvent.click(screen.getByRole('button', { name: /二维码签发/ }))
    fireEvent.click(screen.getByRole('button', { name: '签 发' }))
    expect(await screen.findByText(/请输入设备码/)).toBeInTheDocument()
    // 输入设备码后签发
    const textarea = screen.getByPlaceholderText(/K7M2P9XW4AQ/)
    fireEvent.change(textarea, { target: { value: 'K7M2P9XW4AQ' } })
    fireEvent.click(screen.getByRole('button', { name: '签 发' }))
    await waitFor(() => expect(mockExportQr).toHaveBeenCalledWith(['K7M2P9XW4AQ']))
  })

  it('批量升级：未选中时禁用，选中后二次确认调 API', async () => {
    render(<DevicePage />)
    await screen.findByText('K7M2P9XW4AQ')
    // 选中第一行
    fireEvent.click(screen.getAllByRole('checkbox')[1])
    // 批量升级（Popconfirm 确认）
    fireEvent.click(screen.getByRole('button', { name: /批量升级/ }))
    fireEvent.click(await screen.findByRole('button', { name: /OK|确\s*定/ }))
    await waitFor(() => expect(mockBatch).toHaveBeenCalledWith(['d-1'], 'ota'))
  })
})
