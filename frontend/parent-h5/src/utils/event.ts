/**
 * doing/73 T3（R2）：Taro Input onInput 输入值适配
 * 生产 H5：Taro 合成事件（e.detail.value，官方规范形态）
 * jsdom 测试：Stencil 未初始化，宿主收到原生 input 事件（e.target.value）
 * 统一优先 detail.value，target.value 兜底（spike A-3 验证）
 */
export function inputValue(e: unknown): string {
  const ev = e as { detail?: { value?: string }; target?: { value?: string } }
  return ev.detail?.value ?? ev.target?.value ?? ''
}
