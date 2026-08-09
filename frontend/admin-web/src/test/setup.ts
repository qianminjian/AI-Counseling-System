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
