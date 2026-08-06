import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

/**
 * 错误边界测试（ARCH-009 E-2 补测）
 * - 正常子组件直接渲染
 * - 子组件抛错 → 显示降级 UI（页面出现异常 + 刷新按钮）
 * - 点击刷新按钮触发 window.location.reload
 */

import ErrorBoundary from '../components/ErrorBoundary';

function Boom() {
  throw new Error('boom');
}

function Safe() {
  return <div>正常内容</div>;
}

describe('ErrorBoundary 错误边界', () => {
  it('子组件正常时不渲染降级 UI', () => {
    render(
      <ErrorBoundary>
        <Safe />
      </ErrorBoundary>
    );
    expect(screen.getByText('正常内容')).toBeInTheDocument();
    expect(screen.queryByText('页面出现异常')).not.toBeInTheDocument();
  });

  it('子组件抛错时渲染降级 UI 并记录错误', () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>
    );
    expect(screen.getByText('页面出现异常')).toBeInTheDocument();
    expect(screen.getByText(/请刷新页面重试/)).toBeInTheDocument();
    expect(errSpy).toHaveBeenCalled();
    errSpy.mockRestore();
  });

  it('点击刷新按钮触发页面重载', () => {
    const reload = vi.fn();
    Object.defineProperty(window, 'location', {
      value: { ...window.location, reload },
      writable: true,
    });
    vi.spyOn(console, 'error').mockImplementation(() => {});
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>
    );
    fireEvent.click(screen.getByText('刷新页面'));
    expect(reload).toHaveBeenCalled();
  });
});
