// doing/73 T3：PrivacyPage Taro 化测试（Taro 组件渲染 + Taro 导航 mock）
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import PrivacyPage from '../pages/privacy/index'

// Mock Taro 导航
vi.mock('@tarojs/taro', () => ({
  default: { redirectTo: vi.fn(), navigateTo: vi.fn() }
}))

import Taro from '@tarojs/taro'

const mockedTaro = vi.mocked(Taro)

describe('PrivacyPage（F-5 PIPL 个人信息保护告知）', () => {
  beforeEach(() => {
    mockedTaro.navigateTo.mockClear()
  })

  it('渲染页面标题', () => {
    render(<PrivacyPage />)
    expect(screen.getByText('个人信息保护告知')).toBeInTheDocument()
  })

  it('包含核心告知要点（收集范围/用途/未成年人保护/家长权利）', () => {
    render(<PrivacyPage />)
    expect(screen.getAllByText(/收集/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/使用/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/未成年人/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/权利/).length).toBeGreaterThanOrEqual(1)
  })

  it('提供返回登录入口，点击跳转登录页', () => {
    render(<PrivacyPage />)
    const back = screen.getByText(/返回登录/)
    expect(back).toBeInTheDocument()
    fireEvent.click(back)
    expect(mockedTaro.navigateTo).toHaveBeenCalledWith({ url: '/' })
  })
})
