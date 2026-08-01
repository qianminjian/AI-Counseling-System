import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react'

const mockApi = vi.fn()
vi.mock('../api', () => ({
  api: (...args: any[]) => mockApi(...args),
}))

// 主题 mock：组件用 useTheme 取 themeId + theme.companion
let mockThemeId = 'ocean'
vi.mock('../theme/ThemeProvider', () => ({
  useTheme: () => ({ theme: { companion: '🐬', companionName: '波波' }, themeId: mockThemeId }),
}))

// 场景装饰为纯展示组件，mock 掉避免干扰
vi.mock('../components/SceneDecor', () => ({ default: () => <div data-testid="scene-decor" /> }))

import RelaxationExercises from '../components/RelaxationExercises'

const EXERCISES = [
  { id: 'breathing_323', name: '深呼吸放松', category: 'breathing', description: '跟着圆圈一起慢慢呼吸', durationSeconds: 10 },
  { id: 'mindfulness_01', name: '正念扫描', category: 'mindfulness', description: '闭上眼睛，从头到脚感受身体的每个部位', durationSeconds: 8 },
  { id: 'breathing_478', name: '478 呼吸法', category: 'breathing', description: '吸气4秒屏住7秒呼气8秒', durationSeconds: 12 },
]

describe('RelaxationExercises', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    mockApi.mockReset()
    mockApi.mockImplementation((url: string) => {
      if (url === '/relaxation/exercises') return Promise.resolve(EXERCISES)
      if (url === '/relaxation/sessions/today') return Promise.resolve({ count: 2 })
      return Promise.resolve({})
    })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('加载并展示练习列表', async () => {
    vi.useRealTimers()
    render(<RelaxationExercises onBack={vi.fn()} />)
    await waitFor(() => {
      expect(screen.getByText('深呼吸放松')).toBeTruthy()
    })
    expect(screen.getByText('正念扫描')).toBeTruthy()
    expect(screen.getByText('478 呼吸法')).toBeTruthy()
    expect(screen.getByText('放松一下 🌿')).toBeTruthy()
  })

  it('显示今日练习计数', async () => {
    vi.useRealTimers()
    render(<RelaxationExercises onBack={vi.fn()} />)
    await waitFor(() => {
      expect(screen.getByText(/今天已完成 2 次练习/)).toBeTruthy()
    })
  })

  it('今日计数为 0 时不显示', async () => {
    vi.useRealTimers()
    mockApi.mockImplementation((url: string) => {
      if (url === '/relaxation/exercises') return Promise.resolve(EXERCISES)
      if (url === '/relaxation/sessions/today') return Promise.resolve({ count: 0 })
      return Promise.resolve({})
    })
    render(<RelaxationExercises onBack={vi.fn()} />)
    await waitFor(() => {
      expect(screen.getByText('深呼吸放松')).toBeTruthy()
    })
    expect(screen.queryByText(/今天已完成/)).toBeNull()
  })

  it('返回按钮调用 onBack', async () => {
    vi.useRealTimers()
    const onBack = vi.fn()
    render(<RelaxationExercises onBack={onBack} />)
    await waitFor(() => expect(screen.getByText('深呼吸放松')).toBeTruthy())
    fireEvent.click(screen.getByText('← 返回'))
    expect(onBack).toHaveBeenCalled()
  })

  it('点击练习进入执行器（呼吸类显示吸气）', async () => {
    render(<RelaxationExercises onBack={vi.fn()} />)
    // 等待列表加载（fake timers 下 api promise 仍能 resolve）
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    fireEvent.click(screen.getByText('深呼吸放松'))
    expect(screen.getByText('吸气')).toBeTruthy()
    expect(screen.getByText('🔊 语音引导：开')).toBeTruthy()
  })

  it('呼吸练习阶段切换：吸气→屏住→呼气', async () => {
    render(<RelaxationExercises onBack={vi.fn()} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    fireEvent.click(screen.getByText('深呼吸放松'))
    expect(screen.getByText('吸气')).toBeTruthy()

    // 3-2-3 模式：3 秒吸气后切换到屏住
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
    expect(screen.getByText('屏住')).toBeTruthy()

    // 2 秒屏住后切换到呼气
    await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
    expect(screen.getByText('呼气')).toBeTruthy()
  })

  it('练习完成后显示完成界面并记录', async () => {
    render(<RelaxationExercises onBack={vi.fn()} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    fireEvent.click(screen.getByText('正念扫描'))

    // durationSeconds=8，推进 8 秒
    await act(async () => { await vi.advanceTimersByTimeAsync(8000) })
    expect(screen.getByText(/做得好！感觉放松一些了吗/)).toBeTruthy()

    // 完成按钮 → 回到列表
    fireEvent.click(screen.getByText('完成'))
    expect(screen.getByText('放松一下 🌿')).toBeTruthy()
    // 记录完成
    expect(mockApi).toHaveBeenCalledWith('/relaxation/sessions', expect.objectContaining({ method: 'POST' }))
  })

  it('提前结束回到列表', async () => {
    render(<RelaxationExercises onBack={vi.fn()} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    fireEvent.click(screen.getByText('深呼吸放松'))
    fireEvent.click(screen.getByText('提前结束'))
    expect(screen.getByText('放松一下 🌿')).toBeTruthy()
  })

  it('语音引导开关切换', async () => {
    render(<RelaxationExercises onBack={vi.fn()} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    fireEvent.click(screen.getByText('深呼吸放松'))
    fireEvent.click(screen.getByText('🔊 语音引导：开'))
    expect(screen.getByText('🔇 语音引导：关')).toBeTruthy()
    expect(localStorage.getItem('mindsafe_relax_voice_on')).toBe('0')
    fireEvent.click(screen.getByText('🔇 语音引导：关'))
    expect(screen.getByText('🔊 语音引导：开')).toBeTruthy()
  })

  it('API 加载失败不崩溃', async () => {
    vi.useRealTimers()
    mockApi.mockRejectedValue(new Error('network'))
    render(<RelaxationExercises onBack={vi.fn()} />)
    await waitFor(() => {
      expect(screen.getByText('放松一下 🌿')).toBeTruthy()
    })
  })
})
