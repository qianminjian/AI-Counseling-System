import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import Achievements from '../components/Achievements'

// mock api + useTheme（可控 themeId 覆盖明暗主题分支）
let mockThemeId = 'ocean'
vi.mock('../api', () => ({ api: vi.fn() }))
// importOriginal 保留真实 THEMES（组件直接 import THEMES.ocean.bobo 等品牌色），仅覆写 useTheme
vi.mock('../theme/ThemeProvider', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../theme/ThemeProvider')>()
  return {
    ...actual,
    useTheme: () => ({ themeId: mockThemeId, theme: {}, changeTheme: vi.fn() }),
  }
})

import { api } from '../api'

describe('Achievements', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('加载时显示成就按钮（0/0）', () => {
    ;(api as any).mockResolvedValue([])
    render(<Achievements />)
    expect(screen.getByText(/我的成就/)).toBeTruthy()
  })

  it('显示已解锁/总数', async () => {
    ;(api as any).mockResolvedValue([
      { id: 1, emoji: '🌟', title: '初次见面', desc: '完成第一次对话', unlocked: true },
      { id: 2, emoji: '🔥', title: '连续三天', desc: '连续打卡3天', unlocked: false },
      { id: 3, emoji: '💎', title: '情绪大师', desc: '累计10次', unlocked: true },
    ])
    render(<Achievements />)
    await waitFor(() => {
      expect(screen.getByText(/3/)).toBeTruthy()
    })
  })

  it('点击展开徽章列表', async () => {
    ;(api as any).mockResolvedValue([
      { id: 1, emoji: '🌟', title: '初次见面', desc: '完成第一次对话', unlocked: true },
    ])
    render(<Achievements />)
    await waitFor(() => {
      fireEvent.click(screen.getByText(/我的成就/))
      expect(screen.getByText('初次见面')).toBeTruthy()
      expect(screen.getByText('完成第一次对话')).toBeTruthy()
    })
  })

  it('已解锁徽章显示 ✓ 已解锁', async () => {
    ;(api as any).mockResolvedValue([
      { id: 1, emoji: '🌟', title: '初次见面', desc: '第一次', unlocked: true },
    ])
    render(<Achievements />)
    await waitFor(() => {
      fireEvent.click(screen.getByText(/我的成就/))
      expect(screen.getByText('✓ 已解锁')).toBeTruthy()
    })
  })

  it('未解锁徽章不显示 ✓', async () => {
    ;(api as any).mockResolvedValue([
      { id: 1, emoji: '🔒', title: '神秘', desc: '？？？', unlocked: false },
    ])
    render(<Achievements />)
    await waitFor(() => {
      fireEvent.click(screen.getByText(/我的成就/))
      expect(screen.queryByText('✓ 已解锁')).toBeNull()
    })
  })

  it('再次点击折叠', async () => {
    ;(api as any).mockResolvedValue([
      { id: 1, emoji: '🌟', title: '徽章A', desc: 'desc', unlocked: true },
    ])
    render(<Achievements />)
    await waitFor(() => {
      const btn = screen.getByText(/我的成就/)
      fireEvent.click(btn)
      expect(screen.getByText('徽章A')).toBeTruthy()
      fireEvent.click(btn)
      expect(screen.queryByText('徽章A')).toBeNull()
    })
  })

  it('浅色主题（garden）下展开已解锁+未解锁徽章', async () => {
    mockThemeId = 'garden'
    ;(api as any).mockResolvedValue([
      { id: 1, emoji: '🌟', title: '已解锁徽章', desc: 'd1', unlocked: true },
      { id: 2, emoji: '🔒', title: '未解锁徽章', desc: 'd2', unlocked: false },
    ])
    render(<Achievements />)
    await waitFor(() => {
      fireEvent.click(screen.getByText(/我的成就/))
      expect(screen.getByText('已解锁徽章')).toBeTruthy()
      expect(screen.getByText('未解锁徽章')).toBeTruthy()
      expect(screen.getByText('✓ 已解锁')).toBeTruthy()
    })
    mockThemeId = 'ocean'
  })
})
