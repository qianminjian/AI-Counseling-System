import '@testing-library/jest-dom';
import { afterAll, afterEach, vi } from 'vitest';
import { message, notification } from 'antd';
import { act } from '@testing-library/react';

// antd 全局单例（message/notification）的自动关闭定时器（默认 3s）在测试结束后
// 才到期，触发关闭动画 → React 更新 → scheduler 排 setImmediate → jsdom teardown
// 后访问 window 抛 ReferenceError（vitest 4 记为 unhandled errors，CI 判定失败）。
// 注意：RTL cleanup 只卸载测试渲染的组件，antd 全局单例的 DOM 与定时器不受影响。
// 每个测试后立即销毁，杜绝定时器遗留（AdminPanel/Login/Dashboard 等大量使用 message）。
afterEach(() => {
  message.destroy();
  notification.destroy();
});

// 兜底 flush：coverage（v8 provider）模式下，React scheduler 遗留的 setImmediate
// 会在 jsdom environment teardown 之后才执行，触发 `window is not defined`
// 未处理错误导致 CI 失败（尽管测试本身通过）。
// 用 afterAll 而非 afterEach：确保文件内所有 afterEach（含 RTL cleanup 卸载组件）
// 已执行完，此时 act 冲刷 pending React 更新 + 双 setImmediate 清空宏任务队列。
// fake timers 文件在 afterEach 中已 useRealTimers 恢复，此处 flush 安全。
afterAll(async () => {
  await act(async () => {});
  await new Promise<void>((resolve) => {
    setImmediate(() => setImmediate(resolve));
  });
});

// jsdom 未实现伪元素样式读取，antd/rc-table 会用
// getComputedStyle(el, '::-webkit-scrollbar') 测量滚动条 → 返回空样式对象兜底
const originalGetComputedStyle = window.getComputedStyle;
window.getComputedStyle = (elt: Element, pseudoElt?: string | null) => {
  if (pseudoElt) return {} as CSSStyleDeclaration;
  return originalGetComputedStyle(elt);
};

// jsdom 未实现 ResizeObserver，antd 响应式布局/表格列宽测量依赖
if (!window.ResizeObserver) {
  window.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
}

// jsdom 未实现 matchMedia，antd 响应式观察器依赖
if (!window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }) as MediaQueryList;
}
