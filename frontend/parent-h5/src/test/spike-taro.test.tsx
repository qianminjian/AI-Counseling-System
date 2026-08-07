/**
 * doing/73 T0 spike（R7）：Taro 组件在 vitest+jsdom 渲染可行性验证
 * 路径 A（已验证）：@tarojs/components/lib/react 入口 + DEPRECATED_ADAPTER_COMPONENT=false
 * 结论（2026-08-07，登记 §九）：
 * - jsdom 下 Stencil shadow DOM 不初始化，宿主元素为属性透传 + children 槽位
 * - children 渲染可用（getByText 文本/结构断言）✅
 * - 宿主原生事件绑定可用（click 可用 userEvent；input/submit 需 fireEvent 显式派发）✅
 * - 事件回调收到的是原生 Event（页面代码按 R2 适配 e.detail.value → e.target.value）
 * - 组件内部结构/样式不可测（jsdom 限制，页面测试不覆盖 Taro 组件内部）
 * 本文件为 R7 spike 验证，结论登记 §九后保留为回归守护（防 alias/常量注入被破坏）
 */
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { View, Text, Button, Input, Form } from '@tarojs/components'
import { describe, it, expect, vi } from 'vitest'

describe('Taro 组件 vitest+jsdom spike（R7）', () => {
  it('路径 A-1：View/Text 渲染文本（getByText 可查）', () => {
    render(
      <View className="spike">
        <Text>Hello Taro</Text>
      </View>
    )
    expect(screen.getByText('Hello Taro')).toBeInTheDocument()
  })

  it('路径 A-2：Button 渲染并触发 onClick', async () => {
    const user = userEvent.setup()
    const onClick = vi.fn()
    render(<Button onClick={onClick}>点击</Button>)
    expect(screen.getByText('点击')).toBeInTheDocument()
    await user.click(screen.getByText('点击'))
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('路径 A-3：Input 渲染占位符并触发 onInput（jsdom 下 fireEvent 派发原生 input）', () => {
    const onInput = vi.fn()
    render(<Input placeholder="请输入手机号" onInput={onInput} />)
    const input = screen.getByPlaceholderText('请输入手机号')
    expect(input).toBeInTheDocument()
    // jsdom 下 Stencil 不初始化，宿主无内部 input；onInput 绑定宿主原生 'input' 事件
    fireEvent.input(input, { target: { value: '13800138000' } })
    expect(onInput).toHaveBeenCalled()
    // 页面代码适配（R2）：事件对象为原生 Event，取 e.target.value（H5 端 Taro 转 detail.value）
    const call = onInput.mock.calls[0]?.[0] as { target: { value: string } }
    expect(call.target.value).toBe('13800138000')
  })

  it('路径 A-4：Form 渲染并触发 onSubmit（fireEvent.submit 派发）', () => {
    const onSubmit = vi.fn()
    render(
      <Form data-testid="taro-form" onSubmit={onSubmit}>
        <Button formType="submit">提交</Button>
      </Form>
    )
    const form = screen.getByTestId('taro-form')
    fireEvent.submit(form)
    expect(onSubmit).toHaveBeenCalledTimes(1)
  })
})
