import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import ConfirmDialog from '../components/ConfirmDialog'

describe('ConfirmDialog', () => {
  const defaultProps = {
    open: true,
    title: '确认操作',
    message: '你确定要这样做吗？',
    onConfirm: vi.fn(),
    onCancel: vi.fn(),
  }

  it('open=false 时不渲染', () => {
    const { container } = render(<ConfirmDialog {...defaultProps} open={false} />)
    expect(container.innerHTML).toBe('')
  })

  it('显示标题和消息', () => {
    render(<ConfirmDialog {...defaultProps} />)
    expect(screen.getByText('确认操作')).toBeTruthy()
    expect(screen.getByText('你确定要这样做吗？')).toBeTruthy()
  })

  it('显示默认 emoji', () => {
    render(<ConfirmDialog {...defaultProps} />)
    expect(screen.getByText('🤔')).toBeTruthy()
  })

  it('自定义 emoji', () => {
    render(<ConfirmDialog {...defaultProps} emoji="👋" />)
    expect(screen.getByText('👋')).toBeTruthy()
  })

  it('点击确认触发 onConfirm', () => {
    const onConfirm = vi.fn()
    render(<ConfirmDialog {...defaultProps} onConfirm={onConfirm} />)
    fireEvent.click(screen.getByText('确认'))
    expect(onConfirm).toHaveBeenCalledTimes(1)
  })

  it('点击取消触发 onCancel', () => {
    const onCancel = vi.fn()
    render(<ConfirmDialog {...defaultProps} onCancel={onCancel} />)
    fireEvent.click(screen.getByText('我点错了'))
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('自定义按钮文字', () => {
    render(<ConfirmDialog {...defaultProps} confirmText="确认退出" cancelText="返回" />)
    expect(screen.getByText('确认退出')).toBeTruthy()
    expect(screen.getByText('返回')).toBeTruthy()
  })

  it('danger 模式确认按钮为红色', () => {
    const { container } = render(<ConfirmDialog {...defaultProps} danger />)
    const btn = screen.getByText('确认')
    expect(btn.className).toContain('bg-red-500')
  })

  it('非 danger 模式使用主题色', () => {
    render(<ConfirmDialog {...defaultProps} />)
    const btn = screen.getByText('确认')
    expect(btn.style.background).toBe('var(--primary)')
  })

  it('点击遮罩触发 onCancel', () => {
    const onCancel = vi.fn()
    const { container } = render(<ConfirmDialog {...defaultProps} onCancel={onCancel} />)
    // 遮罩是 absolute inset-0 的 div
    const overlay = container.querySelector('.absolute.inset-0')
    fireEvent.click(overlay!)
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('无 message 时不渲染段落', () => {
    const { container } = render(
      <ConfirmDialog open={true} title="标题" onConfirm={vi.fn()} onCancel={vi.fn()} />
    )
    expect(container.querySelector('p.text-gray-500')).toBeNull()
  })

  it('children 自定义内容渲染', () => {
    render(
      <ConfirmDialog {...defaultProps}>
        <div data-testid="custom">自定义内容</div>
      </ConfirmDialog>
    )
    expect(screen.getByTestId('custom')).toBeTruthy()
  })
})
