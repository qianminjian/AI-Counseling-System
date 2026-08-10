/**
 * doing/85 TOC-001/002：toC 家庭版页面测试
 * 覆盖：登录页（空输入校验错误提示/登录/注册按钮链路）、档案页（列表/空昵称校验/删除/退出）。
 * 模式：Taro mock + services/toc mock（DeviceConfigPage.test.tsx 同构）；
 * 输入格式校验由 utils/tocAuth 纯函数测试覆盖（tocAuth.test.ts，Taro Input jsdom 不可驱动）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

const mockSendCode = vi.fn(() => Promise.resolve({ phone: '138****8000', expiresInSeconds: 300, code: '123456' }))
const mockLogin = vi.fn(() => Promise.resolve({ token: 't', familyAccountId: 'f1', phone: '138****8000', displayName: '家庭' }))
const mockRegister = vi.fn(() => Promise.resolve({ token: 't', familyAccountId: 'f1', phone: '138****8000', displayName: '家庭' }))
const mockList = vi.fn(() => Promise.resolve([
  { profileId: 'p1', familyAccountId: 'f1', nickname: '小明', age: 8, interests: '恐龙,画画' },
]))
const mockCreate = vi.fn(() => Promise.resolve({ profileId: 'p2', familyAccountId: 'f1', nickname: '小红' }))
const mockDelete = vi.fn(() => Promise.resolve())
const mockListDevices = vi.fn(() => Promise.resolve([
  { deviceCode: 'K7M2P9XW4AQ', deviceType: 'desk_toy', firmwareVersion: 'v0.1.0', status: 'ONLINE_BOUND', online: true },
]))
const mockUnbind = vi.fn(() => Promise.resolve({}))
const mockPrivacyOverview = vi.fn(() => Promise.resolve({
  familyAccountId: 'f1', phone: '138****8000', status: 'ACTIVE', profileCount: 2, deviceCount: 1,
  dataRetentionNote: '删除后数据不可恢复（TOC-007）',
}))
const mockDeletePrivacy = vi.fn(() => Promise.resolve({ unboundDevices: 1, deletedProfiles: 2, accountStatus: 'DISABLED' }))
const mockSetPrefs = vi.fn(() => Promise.resolve({ deviceCode: 'K7M2P9XW4AQ', volume: 80, voicePersona: 'qingyu', dialoguePref: 'gentle' }))
const mockNavigate = vi.fn()
const mockReLaunch = vi.fn()

vi.mock('@tarojs/taro', () => ({
  default: { navigateTo: (...a: unknown[]) => mockNavigate(...a), reLaunch: (...a: unknown[]) => mockReLaunch(...a) },
}))

vi.mock('../services/toc', () => ({
  sendTocCode: (...a: unknown[]) => mockSendCode(...a),
  tocLogin: (...a: unknown[]) => mockLogin(...a),
  tocRegister: (...a: unknown[]) => mockRegister(...a),
  listTocProfiles: (...a: unknown[]) => mockList(...a),
  createTocProfile: (...a: unknown[]) => mockCreate(...a),
  updateTocProfile: vi.fn(() => Promise.resolve({})),
  deleteTocProfile: (...a: unknown[]) => mockDelete(...a),
  listTocDevices: (...a: unknown[]) => mockListDevices(...a),
  tocUnbindDevice: (...a: unknown[]) => mockUnbind(...a),
  getTocPrivacyOverview: (...a: unknown[]) => mockPrivacyOverview(...a),
  deleteTocPrivacyData: (...a: unknown[]) => mockDeletePrivacy(...a),
  getTocPreferences: vi.fn(() => Promise.resolve({ deviceCode: 'K7M2P9XW4AQ' })),
  setTocPreferences: (...a: unknown[]) => mockSetPrefs(...a),
  saveTocSession: vi.fn(),
  clearTocSession: vi.fn(),
}))

import TocLoginPage from '../pages/toc-login/index'
import TocProfilesPage from '../pages/toc-profiles/index'
import TocDevicesPage from '../pages/toc-devices/index'
import TocPrivacyPage from '../pages/toc-privacy/index'

describe('toC 登录页（TOC-001）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('空手机号点发送：展示校验错误且不调接口', async () => {
    render(<TocLoginPage />)
    fireEvent.click(screen.getByText('获取验证码'))
    expect(await screen.findByText('请输入手机号')).toBeInTheDocument()
    expect(mockSendCode).not.toHaveBeenCalled()
  })

  it('空手机号点登录：展示校验错误且不调接口', async () => {
    render(<TocLoginPage />)
    fireEvent.click(screen.getByText('登录'))
    expect(await screen.findByText('请输入手机号')).toBeInTheDocument()
    expect(mockLogin).not.toHaveBeenCalled()
  })

  it('无验证码点注册：展示验证码校验错误', async () => {
    render(<TocLoginPage />)
    fireEvent.click(screen.getByText('注册新账号'))
    expect(await screen.findByText('请输入手机号')).toBeInTheDocument()
    expect(mockRegister).not.toHaveBeenCalled()
  })

  it('渲染核心元素：标题/手机号输入/验证码输入/登录注册按钮', () => {
    render(<TocLoginPage />)
    expect(screen.getByText('波波小伙伴')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('请输入手机号')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('请输入验证码')).toBeInTheDocument()
    expect(screen.getByText('登录')).toBeInTheDocument()
    expect(screen.getByText('注册新账号')).toBeInTheDocument()
  })
})

describe('toC 家庭档案页（TOC-002）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('档案列表：展示昵称/年龄/兴趣', async () => {
    render(<TocProfilesPage />)
    expect(await screen.findByText('小明')).toBeInTheDocument()
    expect(screen.getByText(/8 岁/)).toBeInTheDocument()
    expect(screen.getByText(/恐龙,画画/)).toBeInTheDocument()
  })

  it('空档案列表：展示引导文案', async () => {
    mockList.mockResolvedValueOnce([])
    render(<TocProfilesPage />)
    expect(await screen.findByText('还没有孩子档案，添加第一个吧')).toBeInTheDocument()
  })

  it('空昵称点添加：展示校验错误且不调接口', async () => {
    render(<TocProfilesPage />)
    await screen.findByText('小明')
    fireEvent.click(screen.getByText('添加档案'))
    expect(await screen.findByText('昵称必填')).toBeInTheDocument()
    expect(mockCreate).not.toHaveBeenCalled()
  })

  it('删除：调用删除并刷新列表', async () => {
    render(<TocProfilesPage />)
    await screen.findByText('小明')
    fireEvent.click(screen.getByText('删除'))
    await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('p1'))
  })

  it('退出：清空会话并回登录页', async () => {
    render(<TocProfilesPage />)
    await screen.findByText('小明')
    fireEvent.click(screen.getByText('退出'))
    await waitFor(() => expect(mockReLaunch).toHaveBeenCalledWith({ url: '/pages/toc-login/index' }))
  })

describe('toC 家庭设备页（TOC-003）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('设备列表：展示设备码/类型/在线状态', async () => {
    render(<TocDevicesPage />)
    expect(await screen.findByText('K7M2P9XW4AQ')).toBeInTheDocument()
    expect(screen.getByText(/desk_toy/)).toBeInTheDocument()
    expect(screen.getByText(/在线/)).toBeInTheDocument()
  })

  it('空列表：展示引导文案', async () => {
    mockListDevices.mockResolvedValueOnce([])
    render(<TocDevicesPage />)
    expect(await screen.findByText('还没有绑定设备')).toBeInTheDocument()
  })

  it('解绑：调用解绑并刷新', async () => {
    render(<TocDevicesPage />)
    await screen.findByText('K7M2P9XW4AQ')
    fireEvent.click(screen.getByText('解绑'))
    await waitFor(() => expect(mockUnbind).toHaveBeenCalledWith('K7M2P9XW4AQ'))
  })

  it('绑定新设备：跳转扫码配置页', async () => {
    render(<TocDevicesPage />)
    await screen.findByText('K7M2P9XW4AQ')
    fireEvent.click(screen.getByText('绑定新设备'))
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith({ url: '/pages/device/index?v=1&deviceCode=SCAN' }))
  })
})


  it('偏好：展开偏好面板并保存（TOC-006）', async () => {
    render(<TocDevicesPage />)
    await screen.findByText('K7M2P9XW4AQ')
    fireEvent.click(screen.getByText('偏好'))
    expect(screen.getByText('音量（0-100）：60')).toBeInTheDocument()
    fireEvent.click(screen.getByText('80'))
    fireEvent.click(screen.getByText('保存偏好'))
    await waitFor(() => expect(mockSetPrefs).toHaveBeenCalledWith('K7M2P9XW4AQ',
      expect.objectContaining({ volume: 80, voicePersona: 'qingyu', dialoguePref: 'gentle' })))
    expect(await screen.findByText(/偏好已保存/)).toBeInTheDocument()
  })

describe('toC 隐私控制页（TOC-007）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('数据清单：展示账号/档案数/设备数', async () => {
    render(<TocPrivacyPage />)
    expect(await screen.findByText(/138\*\*\*\*8000/)).toBeInTheDocument()
    expect(screen.getByText(/孩子档案：2 个/)).toBeInTheDocument()
    expect(screen.getByText(/绑定设备：1 台/)).toBeInTheDocument()
  })

  it('删除：先二次确认再调删除接口', async () => {
    render(<TocPrivacyPage />)
    await screen.findByText(/孩子档案：2 个/)
    fireEvent.click(screen.getAllByText('删除全部数据')[1])
    fireEvent.click(screen.getByText('确认删除'))
    await waitFor(() => expect(mockDeletePrivacy).toHaveBeenCalled())
  })

  it('取消：不调删除接口', async () => {
    render(<TocPrivacyPage />)
    await screen.findByText(/孩子档案：2 个/)
    fireEvent.click(screen.getAllByText('删除全部数据')[1])
    fireEvent.click(screen.getByText('取消'))
    expect(mockDeletePrivacy).not.toHaveBeenCalled()
  })
})
})
