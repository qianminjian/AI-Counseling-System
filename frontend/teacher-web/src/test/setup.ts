import '@testing-library/jest-dom';
import { afterAll, vi } from 'vitest';

// 兜底 flush：coverage（v8 provider）模式下，React scheduler 遗留的 setImmediate
// 会在 jsdom environment teardown 之后才执行，触发 `window is not defined`
// 未处理错误导致 CI 失败（尽管测试本身通过）。
// 用 afterAll 而非 afterEach：确保文件内所有 afterEach（含 RTL cleanup 卸载组件）
// 已执行完，此时 flush 两轮 immediate 清空卸载触发的最后一批调度，再进入 teardown。
// fake timers 文件在 afterEach 中已 useRealTimers 恢复，此处 flush 安全。
afterAll(async () => {
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
