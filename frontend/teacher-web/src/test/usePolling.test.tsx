import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { usePolling } from '../hooks/usePolling';

/**
 * 统一轮询 Hook 测试（F3 收敛，doing/78 §F3）
 * - 挂载立即执行 + 周期轮询（immediate 默认开启）
 * - immediate=false 仅周期轮询
 * - pauseOnHidden 默认开启：document.hidden 时不触发
 * - pauseOnHidden=false 隐藏也轮询（WebSocket 心跳场景）
 * - 卸载清理定时器
 * - fnRef 模式：rerender 后始终调用最新闭包
 * - interval <= 0 不启动
 */

describe('usePolling 统一轮询', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('挂载后立即执行一次并按周期轮询', () => {
    const fn = vi.fn();
    renderHook(() => usePolling(fn, 30000));
    expect(fn).toHaveBeenCalledTimes(1); // immediate
    act(() => { vi.advanceTimersByTime(30000); });
    expect(fn).toHaveBeenCalledTimes(2);
    act(() => { vi.advanceTimersByTime(60000); });
    expect(fn).toHaveBeenCalledTimes(4);
  });

  it('immediate=false 时不立即执行，仅按周期轮询', () => {
    const fn = vi.fn();
    renderHook(() => usePolling(fn, 30000, { immediate: false }));
    expect(fn).not.toHaveBeenCalled();
    act(() => { vi.advanceTimersByTime(30000); });
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('页面不可见时暂停轮询（pauseOnHidden 默认开启）', () => {
    const hiddenSpy = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    const fn = vi.fn();
    renderHook(() => usePolling(fn, 30000));
    expect(fn).toHaveBeenCalledTimes(1); // 挂载立即执行不受可见性影响
    act(() => { vi.advanceTimersByTime(60000); });
    expect(fn).toHaveBeenCalledTimes(1); // 隐藏期间不轮询
    hiddenSpy.mockReturnValue(false);
    act(() => { vi.advanceTimersByTime(30000); });
    expect(fn).toHaveBeenCalledTimes(2); // 恢复可见后继续
    hiddenSpy.mockRestore();
  });

  it('pauseOnHidden=false 时页面隐藏也照常轮询', () => {
    const hiddenSpy = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    const fn = vi.fn();
    renderHook(() => usePolling(fn, 30000, { pauseOnHidden: false }));
    act(() => { vi.advanceTimersByTime(60000); });
    expect(fn).toHaveBeenCalledTimes(3); // immediate 1 + 隐藏期 2
    hiddenSpy.mockRestore();
  });

  it('卸载后清理定时器不再轮询', () => {
    const fn = vi.fn();
    const { unmount } = renderHook(() => usePolling(fn, 30000));
    unmount();
    act(() => { vi.advanceTimersByTime(60000); });
    expect(fn).toHaveBeenCalledTimes(1); // 仅挂载时一次
  });

  it('rerender 后调用最新闭包（fnRef 模式）', () => {
    const fn1 = vi.fn();
    const fn2 = vi.fn();
    const { rerender } = renderHook(({ cb }) => usePolling(cb, 30000), { initialProps: { cb: fn1 } });
    rerender({ cb: fn2 });
    act(() => { vi.advanceTimersByTime(30000); });
    expect(fn1).toHaveBeenCalledTimes(1); // 仅挂载时旧闭包
    expect(fn2).toHaveBeenCalledTimes(1); // 周期触发用新闭包
  });

  it('interval <= 0 时不启动定时器也不立即执行', () => {
    const fn = vi.fn();
    renderHook(() => usePolling(fn, 0));
    act(() => { vi.advanceTimersByTime(30000); });
    expect(fn).not.toHaveBeenCalled();
  });
});
