import '@testing-library/jest-dom';

// jsdom 未实现 matchMedia，antd 响应式观察器依赖（teacher-web setup 同款）
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

// getComputedStyle(el, '::-webkit-scrollbar') 测量滚动条 → 返回空样式对象兜底（antd Table 依赖，teacher-web setup 同款）
const originalGetComputedStyle = window.getComputedStyle;
window.getComputedStyle = (elt: Element, pseudoElt?: string | null) => {
  if (pseudoElt) return {} as CSSStyleDeclaration;
  return originalGetComputedStyle(elt);
};

// jsdom 未实现 ResizeObserver，antd 响应式布局/表格列宽测量依赖（teacher-web setup 同款）
if (!window.ResizeObserver) {
  window.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
}
