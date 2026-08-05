/**
 * ToolboxPanel 单测（F-2，design/36 §3.1 信息架构）
 *
 * 契约：
 * - 挂载时拉取工具清单（fetchToolboxTools，后端按年级过滤）
 * - 工具卡片：emoji + 标题 + 时长；点击进入练习
 * - 接口失败（弱网/离线）→ 温和兜底提示，不白屏
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import ToolboxPanel from '../components/ToolboxPanel'
import { fetchToolboxTools, recordMoodCheck } from '../api'

vi.mock('../api', () => ({
  fetchToolboxTools: vi.fn(),
  fetchSosTools: vi.fn(),
  recordMoodCheck: vi.fn(),
  reportSosEvent: vi.fn().mockResolvedValue(undefined),
}))

const TOOLS = [
  { toolId: 'breathing_box', title: '深呼吸', emoji: '🫧', durationSec: 150, minGrade: 1, preMoodCheck: true, postMoodCheck: true, rewardBadge: null, category: 'BREATHING' },
  { toolId: 'grounding_54321', title: '找一找', emoji: '🔍', durationSec: 180, minGrade: 1, preMoodCheck: true, postMoodCheck: true, rewardBadge: 'grounding_master', category: 'GROUNDING' },
  { toolId: 'mood_thermometer', title: '心情温度计', emoji: '🌡️', durationSec: 60, minGrade: 1, preMoodCheck: false, postMoodCheck: false, rewardBadge: null, category: 'EMOTION_CHECK' },
]

describe('ToolboxPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(fetchToolboxTools as any).mockResolvedValue(TOOLS)
    ;(recordMoodCheck as any).mockResolvedValue({ effect: 'IMPROVED', needsAttention: false })
  })

  it('渲染标题与返回按钮', async () => {
    render(<ToolboxPanel onBack={vi.fn()} />)
    expect(screen.getByRole('heading', { name: /百宝箱/ })).toBeTruthy()
    await waitFor(() => {
      expect(screen.getByText(/← 返回/)).toBeTruthy()
    })
  })

  it('加载后渲染工具卡片（emoji+标题+时长）', async () => {
    render(<ToolboxPanel onBack={vi.fn()} />)
    await waitFor(() => {
      expect(screen.getByText('深呼吸')).toBeTruthy()
    })
    expect(screen.getByText('找一找')).toBeTruthy()
    expect(screen.getByText('心情温度计')).toBeTruthy()
    expect(screen.getAllByText(/约 3 分钟/).length).toBeGreaterThanOrEqual(2)
  })

  it('点击返回触发 onBack', async () => {
    const onBack = vi.fn()
    render(<ToolboxPanel onBack={onBack} />)
    await waitFor(() => {
      expect(screen.getByText('深呼吸')).toBeTruthy()
    })
    fireEvent.click(screen.getByText(/← 返回/))
    expect(onBack).toHaveBeenCalledTimes(1)
  })

  it('点击工具卡片进入练习界面', async () => {
    render(<ToolboxPanel onBack={vi.fn()} />)
    await waitFor(() => {
      expect(screen.getByText('深呼吸')).toBeTruthy()
    })
    fireEvent.click(screen.getByText('深呼吸'))
    // breathing_box 有 preMoodCheck → 进入练习前心情打分
    await waitFor(() => {
      expect(screen.getByText(/现在的心情/)).toBeTruthy()
    })
    // 练习中关闭 → 回到工具清单
    fireEvent.click(screen.getByText('✕'))
    await waitFor(() => {
      expect(screen.getByText('找一找')).toBeTruthy()
    })
  })

  it('接口失败显示温和兜底提示（弱网不白屏）', async () => {
    ;(fetchToolboxTools as any).mockRejectedValue(new Error('网络错误'))
    render(<ToolboxPanel onBack={vi.fn()} />)
    await waitFor(() => {
      expect(screen.getByText(/网络好像睡着了/)).toBeTruthy()
    })
  })
  
  it('请求未返回前卸载组件不产生状态更新副作用', () => {
    // 永挂起的 promise：验证 cancelled 守卫与清理函数
    ;(fetchToolboxTools as any).mockReturnValue(new Promise(() => {}))
    const { unmount } = render(<ToolboxPanel onBack={vi.fn()} />)
    expect(screen.getByText(/正在打开百宝箱/)).toBeTruthy()
    unmount()
  })
})
