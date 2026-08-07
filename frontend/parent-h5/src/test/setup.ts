import '@testing-library/jest-dom'

/**
 * doing/73 T0 spike（R7）：Taro 4 编译期常量注入
 * 生产构建由 Taro webpack DefinePlugin 注入；vitest 不经过 Taro 编译，
 * 需手动定义（H5 端语义 = true）
 */
const taroEnvConstants: Record<string, boolean> = {
  ENABLE_INNER_HTML: true,
  ENABLE_ADJACENT_HTML: true,
  ENABLE_CLONE_NODE: true,
  ENABLE_CONTAINS: true,
  ENABLE_SIZE_APIS: true,
  ENABLE_MUTATION_OBSERVER: true,
  ENABLE_TEMPLATE_CONTENT: true,
  // Taro 4.2 H5 端默认 false：createComponent 据此调用 defineCustomElement() 注册自定义元素
  DEPRECATED_ADAPTER_COMPONENT: false,
}
for (const [key, value] of Object.entries(taroEnvConstants)) {
  ;(globalThis as Record<string, unknown>)[key] = value
}
