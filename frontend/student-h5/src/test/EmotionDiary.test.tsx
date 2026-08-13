import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import EmotionDiary from '../components/EmotionDiary'

// mock api
vi.mock('../api', () => ({
  api: vi.fn(),
}))

// 主题 mock：组件用 useTheme 取 themeId + theme.companion
let mockThemeId = 'ocean'
// importOriginal 保留真实 THEMES（组件直接 import THEMES.ocean.bobo 等品牌色），仅覆写 useTheme
vi.mock('../theme/ThemeProvider', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../theme/ThemeProvider')>()
  return {
    ...actual,
    useTheme: () => ({ theme: { companion: '🐬', bobo: { body: '#38BDF8', belly: '#E0F2FE', fin: '#0284C7' }, companionName: '波波' }, themeId: mockThemeId }),
  }
})

// 场景装饰为纯展示组件，mock 掉避免干扰
vi.mock('../components/SceneDecor', () => ({ default: () => <div data-testid="scene-decor" /> }))

import { api } from '../api'

describe('EmotionDiary', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(api as any)
      .mockImplementation((url: string) => {
        if (url === '/api/v1/diary/today') return Promise.resolve({ checkedIn: false })
        if (url === '/api/v1/diary/history?days=14') return Promise.resolve([])
        if (url === '/api/v1/diary/streak') return Promise.resolve({ streak: 0, total: 0 })
        return Promise.resolve({})
      })
  })

  it('渲染标题和返回按钮', async () => {
    render(<EmotionDiary onBack={vi.fn()} />)
    expect(screen.getByText('情绪日记 📔')).toBeTruthy()
    expect(screen.getByText('← 返回')).toBeTruthy()
  })

  it('点击返回触发 onBack', () => {
    const onBack = vi.fn()
    render(<EmotionDiary onBack={onBack} />)
    fireEvent.click(screen.getByText('← 返回'))
    expect(onBack).toHaveBeenCalledTimes(1)
  })

  it('显示 5 种情绪选项（DOC-082 与首页 EmotionSelect 共享 STUDENT_EMOTION_TAGS 基线）', () => {
    render(<EmotionDiary onBack={vi.fn()} />)
    expect(screen.getByText('开心')).toBeTruthy()
    expect(screen.getByText('难过')).toBeTruthy()
    expect(screen.getByText('生气')).toBeTruthy()
    expect(screen.getByText('害怕')).toBeTruthy() // scared
    expect(screen.getByText('紧张')).toBeTruthy() // nervous
    expect(screen.queryAllByText('平静')).toHaveLength(0) // calm/neutral 同译去重
  })

  it('未选情绪时提交按钮禁用', () => {
    render(<EmotionDiary onBack={vi.fn()} />)
    const btn = screen.getByText('记录今天 ✨') as HTMLButtonElement
    expect(btn.disabled).toBe(true)
  })

  it('选择情绪后提交按钮启用', () => {
    render(<EmotionDiary onBack={vi.fn()} />)
    fireEvent.click(screen.getByText('开心'))
    const btn = screen.getByText('记录今天 ✨') as HTMLButtonElement
    expect(btn.disabled).toBe(false)
  })

  it('提交打卡后显示已记录', async () => {
    ;(api as any).mockImplementation((url: string, opts?: any) => {
      if (url === '/api/v1/diary/today') return Promise.resolve({ checkedIn: false })
      if (url === '/api/v1/diary/checkin') return Promise.resolve({})
      if (url === '/api/v1/diary/history?days=14') return Promise.resolve([])
      if (url === '/api/v1/diary/streak') return Promise.resolve({ streak: 1, total: 1 })
      return Promise.resolve({})
    })

    render(<EmotionDiary onBack={vi.fn()} />)
    fireEvent.click(screen.getByText('开心'))
    fireEvent.click(screen.getByText('记录今天 ✨'))

    await waitFor(() => {
      expect(screen.getByText('今天已记录，明天再来哦！')).toBeTruthy()
    })
  })

  it('已打卡状态直接显示已完成', async () => {
    ;(api as any).mockImplementation((url: string) => {
      if (url === '/api/v1/diary/today') return Promise.resolve({ checkedIn: true })
      if (url === '/api/v1/diary/history?days=14') return Promise.resolve([])
      if (url === '/api/v1/diary/streak') return Promise.resolve({ streak: 3, total: 10 })
      return Promise.resolve({})
    })

    render(<EmotionDiary onBack={vi.fn()} />)
    await waitFor(() => {
      expect(screen.getByText('今天已记录，明天再来哦！')).toBeTruthy()
    })
  })

  it('连续打卡 > 0 显示打卡天数', async () => {
    ;(api as any).mockImplementation((url: string) => {
      if (url === '/api/v1/diary/today') return Promise.resolve({ checkedIn: false })
      if (url === '/api/v1/diary/history?days=14') return Promise.resolve([])
      if (url === '/api/v1/diary/streak') return Promise.resolve({ streak: 5, total: 20 })
      return Promise.resolve({})
    })

    render(<EmotionDiary onBack={vi.fn()} />)
    await waitFor(() => {
      expect(screen.getByText(/已连续打卡 5 天/)).toBeTruthy()
    })
  })

  it('有历史记录时渲染趋势图', async () => {
    ;(api as any).mockImplementation((url: string) => {
      if (url === '/api/v1/diary/today') return Promise.resolve({ checkedIn: false })
      if (url === '/api/v1/diary/history?days=14') {
        return Promise.resolve([
          { emotionLabel: 'happy', intensity: 3, diaryDate: '2026-07-20' },
          { emotionLabel: 'sad', intensity: 2, diaryDate: '2026-07-21' },
        ])
      }
      if (url === '/api/v1/diary/streak') return Promise.resolve({ streak: 0, total: 0 })
      return Promise.resolve({})
    })

    render(<EmotionDiary onBack={vi.fn()} />)
    await waitFor(() => {
      expect(screen.getByText('近 14 天心情趋势')).toBeTruthy()
    })
  })

  it('强度滑块默认中等', () => {
    render(<EmotionDiary onBack={vi.fn()} />)
    expect(screen.getByText('中等')).toBeTruthy()
  })

  it('调整强度滑块更新标签', () => {
    render(<EmotionDiary onBack={vi.fn()} />)
    const slider = document.querySelector('input[type="range"]') as HTMLInputElement
    fireEvent.change(slider, { target: { value: '5' } })
    expect(screen.getByText('非常强')).toBeTruthy()
  })

  it('输入备注后提交', async () => {
    ;(api as any).mockImplementation((url: string, opts?: any) => {
      if (url === '/api/v1/diary/today') return Promise.resolve({ checkedIn: false })
      if (url === '/api/v1/diary/checkin') return Promise.resolve({})
      if (url === '/api/v1/diary/history?days=14') return Promise.resolve([])
      if (url === '/api/v1/diary/streak') return Promise.resolve({ streak: 1, total: 1 })
      return Promise.resolve({})
    })
    render(<EmotionDiary onBack={vi.fn()} />)
    fireEvent.click(screen.getByText('难过'))
    const textarea = document.querySelector('textarea')!
    fireEvent.change(textarea, { target: { value: '今天有点不开心' } })
    fireEvent.click(screen.getByText('记录今天 ✨'))
    await waitFor(() => {
      expect(screen.getByText('今天已记录，明天再来哦！')).toBeTruthy()
    })
    expect(api).toHaveBeenCalledWith('/api/v1/diary/checkin', expect.objectContaining({
      method: 'POST',
      body: expect.stringContaining('今天有点不开心'),
    }))
  })

  it('历史记录使用正确的 emoji 映射', async () => {
    ;(api as any).mockImplementation((url: string) => {
      if (url === '/api/v1/diary/today') return Promise.resolve({ checkedIn: false })
      if (url === '/api/v1/diary/history?days=14') {
        return Promise.resolve([
          { emotionLabel: '开心', intensity: 4, diaryDate: '2026-07-20' },
          { emotionLabel: 'unknown_emotion', intensity: 2, diaryDate: '2026-07-21' },
        ])
      }
      if (url === '/api/v1/diary/streak') return Promise.resolve({ streak: 0, total: 0 })
      return Promise.resolve({})
    })
    render(<EmotionDiary onBack={vi.fn()} />)
    await waitFor(() => {
      expect(screen.getByText('近 14 天心情趋势')).toBeTruthy()
    })
  })
})
