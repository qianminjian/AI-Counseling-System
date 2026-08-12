import '@testing-library/jest-dom';

/**
 * 存量环境兜底（2026-08-12 发现）：部分 jsdom 配置下 window.localStorage 缺失，
 * 大量用例 beforeEach 直接访问 localStorage 报 undefined。jsdom 正常时本段不生效。
 */
if (typeof globalThis.localStorage === 'undefined') {
  const store = new Map<string, string>();
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => { store.set(k, String(v)); },
      removeItem: (k: string) => { store.delete(k); },
      clear: () => { store.clear(); },
      key: (i: number) => Array.from(store.keys())[i] ?? null,
      get length() { return store.size; },
    },
  });
}

/**
 * PointerEvent polyfill（jsdom 26 尚未实现 PointerEvent）：
 * 支持 clientX/clientY/pointerId/button 初始化，供 fireEvent.pointer* 与按住说话上滑判定使用。
 * 缺失时 testing-library 会 fallback 到 Event 构造器，坐标与 pointerId 全部丢失。
 */
if (typeof (globalThis as any).PointerEvent === 'undefined') {
  class PointerEventPolyfill extends Event {
    pointerId: number
    clientX: number
    clientY: number
    button: number
    constructor(type: string, init: any = {}) {
      super(type, init)
      this.pointerId = init.pointerId ?? 0
      this.clientX = init.clientX ?? 0
      this.clientY = init.clientY ?? 0
      this.button = init.button ?? 0
    }
  }
  ;(globalThis as any).PointerEvent = PointerEventPolyfill
}
