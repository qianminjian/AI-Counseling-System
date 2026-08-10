/**
 * doing/85 TOC-001：toC 登录表单校验纯函数
 * 与 84 号 deviceBind 同构：Taro Input（stencil web component）在 jsdom 不可驱动，
 * 校验逻辑抽离为纯函数单独覆盖，UI 组件走按钮/文本可测链路。
 */

/** 手机号校验：合法返回 null，否则返回错误文案。 */
export function validateTocPhone(phone: string): string | null {
  if (!phone || phone.trim() === '') {
    return '请输入手机号'
  }
  if (!/^1\d{10}$/.test(phone.trim())) {
    return '请输入 11 位手机号'
  }
  return null
}

/** 验证码校验：合法返回 null，否则返回错误文案。 */
export function validateTocCode(code: string): string | null {
  if (!code || code.trim() === '') {
    return '请输入验证码'
  }
  if (!/^\d{6}$/.test(code.trim())) {
    return '请输入 6 位验证码'
  }
  return null
}

/** 昵称校验（TOC-002 档案）：非空返回 null，否则返回错误文案。 */
export function validateTocNickname(nickname: string): string | null {
  if (!nickname || nickname.trim() === '') {
    return '昵称必填'
  }
  return null
}
