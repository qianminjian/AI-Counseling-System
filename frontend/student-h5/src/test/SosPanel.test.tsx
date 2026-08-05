/**
 * SosPanel 单测（F-2，design/36 §3.4 SOS 模式）
 *
 * 契约：
 * - 纯静态三段式：立即联系（12355 一键拨号）/ 先稳住自己（接地+深呼吸）/ 我的安全小岛
 * - 打开即 fire-and-forget 上报（reportSosEvent），失败不影响渲染
 * - 断网 100% 可打开：组件不依赖任何成功接口
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import SosPanel from '../components/SosPanel'
import { reportSosEvent } from '../api/toolboxApi'

vi.mock('../api/toolboxApi', () => ({
  reportSosEvent: vi.fn(),
}))

describe('SosPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(reportSosEvent as any).mockResolvedValue(undefined)
  })

  it('渲染三段式标题', () => {
    render(<SosPanel onBack={vi.fn()} />)
    expect(screen.getByText(/立即联系/)).toBeTruthy()
    expect(screen.getByText(/先稳住自己/)).toBeTruthy()
    expect(screen.getByText(/我的安全小岛/)).toBeTruthy()
  })

  it('12355 热线为一键拨号链接', () => {
    render(<SosPanel onBack={vi.fn()} />)
    const link = screen.getByText(/12355/).closest('a')
    expect(link?.getAttribute('href')).toBe('tel:12355')
  })

  it('接地引导展示 54321 步骤', () => {
    render(<SosPanel onBack={vi.fn()} />)
    expect(screen.getByText(/5 个你看到的东西/)).toBeTruthy()
    expect(screen.getByText(/4 个你能摸到的东西/)).toBeTruthy()
  })

  it('打开时上报 SOS 事件', async () => {
    render(<SosPanel onBack={vi.fn()} />)
    await waitFor(() => {
      expect(reportSosEvent).toHaveBeenCalledTimes(1)
    })
  })

  it('上报失败不影响界面渲染（fire-and-forget）', async () => {
    ;(reportSosEvent as any).mockRejectedValue(new Error('offline'))
    render(<SosPanel onBack={vi.fn()} />)
    expect(screen.getByText(/立即联系/)).toBeTruthy()
  })

  it('点击返回触发 onBack', () => {
    const onBack = vi.fn()
    render(<SosPanel onBack={onBack} />)
    fireEvent.click(screen.getByText(/← 返回/))
    expect(onBack).toHaveBeenCalledTimes(1)
  })
})
