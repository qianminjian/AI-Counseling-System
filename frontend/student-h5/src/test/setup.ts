import '@testing-library/jest-dom';

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
