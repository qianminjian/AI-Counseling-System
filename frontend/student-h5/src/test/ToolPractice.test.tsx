/**
 * ToolPractice 单测（F-2，design/36 §3.2 统一工具框架）
 *
 * 契约：
 * - preMoodCheck=true 时先做练习前 1-5 心情打分，再进入练习，结束后 postMoodCheck 打分
 * - 完成后调用 recordMoodCheck(toolId, preMood, postMood)
 * - needsAttention=true（心情恶化）→ 显示温和引导话术，不指责
 * - recordMoodCheck 失败不阻塞完成界面（可用性优先）
 * - 无心情记录标记的工具 → 直接练习，完成后直接展示完成态
 * - 内容包（design/36 §3.2）：练习阶段分步展示引导文案，按建议时长自动推进，可手动下一步；
 *   无内容包的工具降级为纯倒计时
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import ToolPractice from '../components/ToolPractice'
import { recordMoodCheck } from '../api/toolboxApi'

vi.mock('../api/toolboxApi', () => ({
  recordMoodCheck: vi.fn(),
}))

const baseTool = {
  toolId: 'breathing_box',
  title: '深呼吸',
  emoji: '🫧',
  durationSec: 150,
  minGrade: 1,
  preMoodCheck: true,
  postMoodCheck: true,
  rewardBadge: null,
  category: 'BREATHING',
}

describe('ToolPractice', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(recordMoodCheck as any).mockResolvedValue({ effect: 'IMPROVED', needsAttention: false })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('preMoodCheck 工具首屏为练习前心情打分', () => {
    render(<ToolPractice tool={baseTool} onClose={vi.fn()} />)
    expect(screen.getByText(/现在的心情/)).toBeTruthy()
    expect(screen.getByText('😊')).toBeTruthy()
    expect(screen.getByText('😢')).toBeTruthy()
  })

  it('未打分时"开始"按钮禁用，打分后启用并进入练习', () => {
    render(<ToolPractice tool={baseTool} onClose={vi.fn()} />)
    const startBtn = screen.getByText(/开始/) as HTMLButtonElement
    expect(startBtn.disabled).toBe(true)
    fireEvent.click(screen.getByText('😊'))
    expect(startBtn.disabled).toBe(false)
    fireEvent.click(startBtn)
    expect(screen.getByText('🫧')).toBeTruthy()
    expect(screen.getByText('深呼吸')).toBeTruthy()
  })

  it('练习界面显示倒计时', () => {
    vi.useFakeTimers()
    render(<ToolPractice tool={{ ...baseTool, durationSec: 90 }} onClose={vi.fn()} />)
    fireEvent.click(screen.getByText('😊'))
    fireEvent.click(screen.getByText(/开始/))
    expect(screen.getByText('01:30')).toBeTruthy()
    act(() => { vi.advanceTimersByTime(1000) })
    expect(screen.getByText('01:29')).toBeTruthy()
  })

  it('完成练习后进入练习后打分，提交调用 recordMoodCheck', async () => {
    render(<ToolPractice tool={baseTool} onClose={vi.fn()} />)
    fireEvent.click(screen.getByText('😕'))
    fireEvent.click(screen.getByText(/开始/))
    fireEvent.click(screen.getByText(/完成/))
    // 练习后打分：选 4 分（🙂）
    fireEvent.click(screen.getAllByText('🙂')[0])
    fireEvent.click(screen.getByText(/完成/))
    await waitFor(() => {
      expect(recordMoodCheck).toHaveBeenCalledWith('breathing_box', 2, 4)
    })
  })

  it('心情恶化（needsAttention）显示温和引导话术', async () => {
    ;(recordMoodCheck as any).mockResolvedValue({ effect: 'WORSENED', needsAttention: true })
    render(<ToolPractice tool={baseTool} onClose={vi.fn()} />)
    fireEvent.click(screen.getByText('🙂'))
    fireEvent.click(screen.getByText(/开始/))
    fireEvent.click(screen.getByText(/完成/))
    fireEvent.click(screen.getAllByText('😢')[0])
    fireEvent.click(screen.getByText(/完成/))
    await waitFor(() => {
      expect(screen.getByText(/没有好转也没关系/)).toBeTruthy()
    })
  })

  it('recordMoodCheck 失败不阻塞完成界面', async () => {
    ;(recordMoodCheck as any).mockRejectedValue(new Error('网络错误'))
    render(<ToolPractice tool={baseTool} onClose={vi.fn()} />)
    fireEvent.click(screen.getByText('😊'))
    fireEvent.click(screen.getByText(/开始/))
    fireEvent.click(screen.getByText(/完成/))
    fireEvent.click(screen.getAllByText('😊')[0])
    fireEvent.click(screen.getByText(/完成/))
    await waitFor(() => {
      expect(screen.getByText(/太棒啦/)).toBeTruthy()
    })
  })

  it('无心情记录标记的工具跳过打分直接进入练习', () => {
    const tool = { ...baseTool, toolId: 'safe_island', preMoodCheck: false, postMoodCheck: false }
    render(<ToolPractice tool={tool} onClose={vi.fn()} />)
    expect(screen.queryByText(/现在的心情/)).toBeNull()
    expect(screen.getByText('深呼吸')).toBeTruthy()
  })

  it('点击关闭触发 onClose', () => {
    const onClose = vi.fn()
    render(<ToolPractice tool={baseTool} onClose={onClose} />)
    fireEvent.click(screen.getByText('✕'))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  // ===== 结构化内容包（design/36 §3.2） =====

  function enterPractice(tool = baseTool) {
    render(<ToolPractice tool={tool} onClose={vi.fn()} />)
    fireEvent.click(screen.getByText('😊'))
    fireEvent.click(screen.getByText(/开始/))
  }

  it('练习阶段展示内容包第一步引导文案与进度', () => {
    enterPractice()
    expect(screen.getByText(/第 1 \/ 6 步/)).toBeTruthy()
    expect(screen.getByText(/把小手轻轻放在肚子上/)).toBeTruthy()
    expect(screen.getByText(/下一步/)).toBeTruthy()
  })

  it('步骤建议时长到达后自动推进到下一步', () => {
    vi.useFakeTimers()
    enterPractice()
    expect(screen.getByText(/第 1 \/ 6 步/)).toBeTruthy()
    // breathing_box 第一步建议 20s
    act(() => { vi.advanceTimersByTime(20_000) })
    expect(screen.getByText(/第 2 \/ 6 步/)).toBeTruthy()
    expect(screen.getByText(/用鼻子慢慢吸气/)).toBeTruthy()
  })

  it('点击下一步手动推进', () => {
    enterPractice()
    fireEvent.click(screen.getByText(/下一步/))
    expect(screen.getByText(/第 2 \/ 6 步/)).toBeTruthy()
    expect(screen.getByText(/用鼻子慢慢吸气/)).toBeTruthy()
  })

  it('最后一步不显示下一步按钮', () => {
    vi.useFakeTimers()
    enterPractice()
    for (let i = 0; i < 5; i++) {
      fireEvent.click(screen.getByText(/下一步/))
    }
    expect(screen.getByText(/第 6 \/ 6 步/)).toBeTruthy()
    expect(screen.queryByText(/下一步/)).toBeNull()
    expect(screen.getByText(/我完成啦/)).toBeTruthy()
  })

  it('无内容包的工具降级为纯倒计时', () => {
    const tool = { ...baseTool, toolId: 'unknown_tool' }
    enterPractice(tool)
    expect(screen.queryByText(/第 1 \/ 6 步/)).toBeNull()
    expect(screen.getByText(/跟着波波慢慢来/)).toBeTruthy()
    expect(screen.queryByText(/下一步/)).toBeNull()
  })
})
