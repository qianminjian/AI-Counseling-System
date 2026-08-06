import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, render, screen, fireEvent, waitFor } from '@testing-library/react';

/**
 * 预警 WebSocket 推送 Hook 测试（ARCH-009 E-2 补测）
 * - 无 token 不连接；有 token 以 subprotocol 携带 JWT
 * - 30s 心跳保活
 * - risk_alert → antd notification + onAlert 回调
 * - 红色预警带 sessionId → 立即接管按钮 → takeoverSession
 * - 断开后 5s 自动重连；卸载清理
 */

const mockGetToken = vi.fn();
const mockTakeoverSession = vi.fn();
vi.mock('../api', () => ({
  getToken: () => mockGetToken(),
  takeoverSession: (sid: string) => mockTakeoverSession(sid),
}));

import { useAlertWebSocket } from '../hooks/useAlertWebSocket';
import { notification } from 'antd';

/** WebSocket 全局桩：jsdom 无 WebSocket */
class MockWebSocket {
  static instances: MockWebSocket[] = [];
  static OPEN = 1;
  readyState = 0;
  url = '';
  protocols: string[] = [];
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;

  constructor(url: string, protocols: string[]) {
    this.url = url;
    this.protocols = protocols;
    this.readyState = 1;
    MockWebSocket.instances.push(this);
  }
  send(data: string) { this.sent.push(data); }
  close() { this.readyState = 3; }
  // 测试辅助：手动触发事件
  triggerOpen() { this.onopen?.(); }
  triggerMessage(data: string) { this.onmessage?.({ data }); }
  triggerClose() { this.onclose?.(); }
  triggerError() { this.onerror?.(); }
}

const lastWs = () => MockWebSocket.instances[MockWebSocket.instances.length - 1];

describe('useAlertWebSocket 预警推送', () => {
  beforeEach(() => {
    MockWebSocket.instances = [];
    mockGetToken.mockReset().mockReturnValue('jwt-token');
    mockTakeoverSession.mockReset().mockResolvedValue(null);
    vi.stubGlobal('WebSocket', MockWebSocket);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('无 token 时不建立连接', () => {
    mockGetToken.mockReturnValue(null);
    renderHook(() => useAlertWebSocket({ onAlert: vi.fn() }));
    expect(MockWebSocket.instances).toHaveLength(0);
  });

  it('有 token 时连接 /ws/alerts 并以 subprotocol 携带 JWT', () => {
    renderHook(() => useAlertWebSocket({ onAlert: vi.fn() }));
    expect(MockWebSocket.instances).toHaveLength(1);
    expect(lastWs().url).toBe('ws://localhost:3000/ws/alerts');
    expect(lastWs().protocols).toEqual(['alerts.v1', 'auth.jwt-token']);
  });

  it('连接后 30s 心跳保活发送 ping', () => {
    vi.useFakeTimers();
    renderHook(() => useAlertWebSocket({ onAlert: vi.fn() }));
    const ws = lastWs();
    act(() => ws.triggerOpen());
    act(() => { vi.advanceTimersByTime(30000); });
    expect(ws.sent).toContain('ping');
    act(() => { vi.advanceTimersByTime(30000); });
    expect(ws.sent.filter((m) => m === 'ping').length).toBe(2);
  });

  it('收到 risk_alert 弹出通知并触发回调', () => {
    const openSpy = vi.spyOn(notification, 'open').mockImplementation(() => undefined as never);
    const onAlert = vi.fn();
    renderHook(() => useAlertWebSocket({ onAlert }));
    const ws = lastWs();
    act(() => ws.triggerMessage(JSON.stringify({ type: 'risk_alert', title: '焦虑预警', body: '小明', riskLevel: 2 })));
    expect(openSpy).toHaveBeenCalledWith(expect.objectContaining({ message: '焦虑预警' }));
    expect(onAlert).toHaveBeenCalled();
  });

  it('pong 与非 JSON 消息不触发回调', () => {
    const openSpy = vi.spyOn(notification, 'open').mockImplementation(() => undefined as never);
    const onAlert = vi.fn();
    renderHook(() => useAlertWebSocket({ onAlert }));
    const ws = lastWs();
    act(() => ws.triggerMessage('pong'));
    act(() => ws.triggerMessage('not-json'));
    act(() => ws.triggerMessage(JSON.stringify({ type: 'other' })));
    expect(openSpy).not.toHaveBeenCalled();
    expect(onAlert).not.toHaveBeenCalled();
  });

  it('红色预警渲染「立即接管」按钮并调用接管接口', async () => {
    const openSpy = vi.spyOn(notification, 'open').mockImplementation(() => undefined as never);
    const onAlert = vi.fn();
    renderHook(() => useAlertWebSocket({ onAlert }));
    const ws = lastWs();
    act(() => ws.triggerMessage(JSON.stringify({
      type: 'risk_alert', title: '红色预警', body: '小红', riskLevel: 3, sessionId: 'se-1',
    })));
    expect(openSpy).toHaveBeenCalled();
    const { btn } = openSpy.mock.calls[0][0];
    expect(btn).toBeTruthy();
    render(btn);
    fireEvent.click(screen.getByText('立即接管'));
    await waitFor(() => expect(mockTakeoverSession).toHaveBeenCalledWith('se-1'));
  });

  it('断开后 5s 自动重连', () => {
    vi.useFakeTimers();
    renderHook(() => useAlertWebSocket({ onAlert: vi.fn() }));
    act(() => lastWs().triggerOpen());
    act(() => lastWs().triggerClose());
    expect(MockWebSocket.instances).toHaveLength(1);
    act(() => { vi.advanceTimersByTime(5000); });
    expect(MockWebSocket.instances).toHaveLength(2);
  });

  it('卸载时关闭连接并清理定时器', () => {
    vi.useFakeTimers();
    const { unmount } = renderHook(() => useAlertWebSocket({ onAlert: vi.fn() }));
    const ws = lastWs();
    act(() => ws.triggerOpen());
    unmount();
    expect(ws.readyState).toBe(3); // closed
    // 重连定时器已清理：卸载后推进 10s 不新增连接
    act(() => { vi.advanceTimersByTime(10000); });
    expect(MockWebSocket.instances).toHaveLength(1);
  });

  it('enabled=false 时不连接', () => {
    renderHook(() => useAlertWebSocket({ onAlert: vi.fn(), enabled: false }));
    expect(MockWebSocket.instances).toHaveLength(0);
  });
});
