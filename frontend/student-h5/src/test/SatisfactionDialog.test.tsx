import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import SatisfactionDialog from '../components/SatisfactionDialog'

describe('SatisfactionDialog', () => {
  const defaultProps = {
    onSubmit: vi.fn(),
    onSkip: vi.fn(),
  }

  it('显示标题和提示', () => {
    render(<SatisfactionDialog {...defaultProps} />)
    expect(screen.getByText('今天的聊天对你有帮助吗？')).toBeTruthy()
    expect(screen.getByText('你的感受很重要（可以不选哦）')).toBeTruthy()
  })

  it('渲染 5 个表情评分按钮', () => {
    render(<SatisfactionDialog {...defaultProps} />)
    expect(screen.getByText('😢')).toBeTruthy()
    expect(screen.getByText('😐')).toBeTruthy()
    expect(screen.getByText('🙂')).toBeTruthy()
    expect(screen.getByText('😊')).toBeTruthy()
    expect(screen.getByText('🥰')).toBeTruthy()
  })

  it('未选择时提交按钮禁用', () => {
    render(<SatisfactionDialog {...defaultProps} />)
    const submitBtn = screen.getByText('提交')
    expect(submitBtn.hasAttribute('disabled') || submitBtn.className.includes('cursor-not-allowed')).toBe(true)
  })

  it('选择表情后提交按钮启用', () => {
    render(<SatisfactionDialog {...defaultProps} />)
    fireEvent.click(screen.getByText('😊'))
    const submitBtn = screen.getByText('提交')
    expect(submitBtn.className).not.toContain('cursor-not-allowed')
  })

  it('选择表情后显示留言输入框', () => {
    render(<SatisfactionDialog {...defaultProps} />)
    expect(screen.queryByPlaceholderText('想说的话（选填）')).toBeNull()
    fireEvent.click(screen.getByText('🙂'))
    expect(screen.getByPlaceholderText('想说的话（选填）')).toBeTruthy()
  })

  it('提交时传递评分和留言', () => {
    const onSubmit = vi.fn()
    render(<SatisfactionDialog {...defaultProps} onSubmit={onSubmit} />)
    fireEvent.click(screen.getByText('🥰'))
    fireEvent.change(screen.getByPlaceholderText('想说的话（选填）'), {
      target: { value: '很开心' },
    })
    fireEvent.click(screen.getByText('提交'))
    expect(onSubmit).toHaveBeenCalledWith(5, '很开心')
  })

  it('提交时无留言传 undefined', () => {
    const onSubmit = vi.fn()
    render(<SatisfactionDialog {...defaultProps} onSubmit={onSubmit} />)
    fireEvent.click(screen.getByText('😢'))
    fireEvent.click(screen.getByText('提交'))
    expect(onSubmit).toHaveBeenCalledWith(1, undefined)
  })

  it('点击跳过触发 onSkip', () => {
    const onSkip = vi.fn()
    render(<SatisfactionDialog {...defaultProps} onSkip={onSkip} />)
    fireEvent.click(screen.getByText('跳过'))
    expect(onSkip).toHaveBeenCalledTimes(1)
  })

  it('提供 onResume 时显示"再聊一会儿"按钮', () => {
    const onResume = vi.fn()
    render(<SatisfactionDialog {...defaultProps} onResume={onResume} />)
    const btn = screen.getByText(/再聊一会儿/)
    fireEvent.click(btn)
    expect(onResume).toHaveBeenCalledTimes(1)
  })

  it('不提供 onResume 时不显示返回按钮', () => {
    render(<SatisfactionDialog {...defaultProps} />)
    expect(screen.queryByText(/再聊一会儿/)).toBeNull()
  })
})
