import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import ErrorBoundary from '../components/ErrorBoundary'

function Bomb() {
  throw new Error('boom')
}

describe('ErrorBoundary', () => {
  it('正常子组件直接渲染', () => {
    render(<ErrorBoundary><div>OK</div></ErrorBoundary>)
    expect(screen.getByText('OK')).toBeTruthy()
  })

  it('子组件抛错显示降级 UI', () => {
    // 抑制 React 错误日志
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    render(<ErrorBoundary><Bomb /></ErrorBoundary>)
    expect(screen.getByText('页面遇到了一点问题')).toBeTruthy()
    expect(screen.getByText(/别担心/)).toBeTruthy()
    spy.mockRestore()
  })

  it('降级 UI 包含重试和刷新按钮', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    render(<ErrorBoundary><Bomb /></ErrorBoundary>)
    expect(screen.getByText('重试')).toBeTruthy()
    expect(screen.getByText('刷新页面')).toBeTruthy()
    spy.mockRestore()
  })

  it('点击重试恢复渲染', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    let shouldThrow = true
    function Maybe() {
      if (shouldThrow) throw new Error('x')
      return <div>恢复</div>
    }
    const { rerender } = render(<ErrorBoundary><Maybe /></ErrorBoundary>)
    expect(screen.getByText('页面遇到了一点问题')).toBeTruthy()

    shouldThrow = false
    fireEvent.click(screen.getByText('重试'))
    // 重试后 state 重置，但需要 rerender 才能触发子组件重新渲染
    rerender(<ErrorBoundary><Maybe /></ErrorBoundary>)
    expect(screen.getByText('恢复')).toBeTruthy()
    spy.mockRestore()
  })

  it('自定义 fallback 优先', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    render(
      <ErrorBoundary fallback={<div>自定义错误</div>}>
        <Bomb />
      </ErrorBoundary>
    )
    expect(screen.getByText('自定义错误')).toBeTruthy()
    spy.mockRestore()
  })

  it('刷新按钮调用 location.reload', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const reloadSpy = vi.fn()
    Object.defineProperty(window, 'location', {
      value: { reload: reloadSpy },
      writable: true,
      configurable: true,
    })
    render(<ErrorBoundary><Bomb /></ErrorBoundary>)
    fireEvent.click(screen.getByText('刷新页面'))
    expect(reloadSpy).toHaveBeenCalled()
    spy.mockRestore()
  })
})
