import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import IdleWarning from '../components/IdleWarning'

describe('IdleWarning', () => {
  it('显示倒计时秒数', () => {
    render(<IdleWarning secondsLeft={42} onStay={vi.fn()} />)
    expect(screen.getByText('42')).toBeTruthy()
  })

  it('显示标题和提示文案', () => {
    render(<IdleWarning secondsLeft={10} onStay={vi.fn()} />)
    expect(screen.getByText('你还在吗？')).toBeTruthy()
    expect(screen.getByText(/波波等你哦/)).toBeTruthy()
  })

  it('点击"我还在！"触发 onStay', () => {
    const onStay = vi.fn()
    render(<IdleWarning secondsLeft={30} onStay={onStay} />)
    fireEvent.click(screen.getByText('我还在！'))
    expect(onStay).toHaveBeenCalledTimes(1)
  })

  it('全屏遮罩 z-index 为 70', () => {
    const { container } = render(<IdleWarning secondsLeft={5} onStay={vi.fn()} />)
    expect(container.querySelector('.z-\\[70\\]')).toBeTruthy()
  })
})
