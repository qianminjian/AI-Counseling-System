import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import ErrorBoundary from '../components/ErrorBoundary'

// 一个能触发渲染错误的组件
function Bomb({ shouldThrow }: { shouldThrow?: boolean }) {
  if (shouldThrow) throw new Error('测试爆炸')
  return <div>正常内容</div>
}

describe('ErrorBoundary', () => {
  it('正常渲染 children', () => {
    render(
      <ErrorBoundary>
        <div>页面内容</div>
      </ErrorBoundary>
    )
    expect(screen.getByText('页面内容')).toBeInTheDocument()
  })

  it('子组件抛错时显示降级 UI', () => {
    // 抑制 React 的 console.error（预期行为）
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    render(
      <ErrorBoundary>
        <Bomb shouldThrow />
      </ErrorBoundary>
    )
    expect(screen.getByText('页面出现异常')).toBeInTheDocument()
    expect(screen.getByText('请刷新页面重试')).toBeInTheDocument()
    expect(screen.getByText('刷新页面')).toBeInTheDocument()
    spy.mockRestore()
  })
})
