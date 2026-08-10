/**
 * CFG-004（doing/84 §四.3）：设备绑定表单校验纯函数
 * 抽离自 DeviceConfigPage（可单测；Taro Input 为 Stencil web component，
 * jsdom 下输入事件无法驱动受控状态，校验逻辑以纯函数形式覆盖）。
 */

/** 绑定表单校验：返回错误消息，合法返回 null */
export function validateBindInput(bindTargetId: string, code: string): string | null {
  if (!bindTargetId.trim()) {
    return '请填写归属 ID'
  }
  if (!/^\d{6}$/.test(code)) {
    return '请输入设备语音播报的 6 位验证码'
  }
  return null
}
