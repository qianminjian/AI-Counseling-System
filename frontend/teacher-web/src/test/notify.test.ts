import { describe, it, expect, vi, afterEach } from 'vitest'
import { notification } from 'antd'

/**
 * 板块08 P2-2：预警触达工具独立测试（playAlertSound / sendDesktopNotification）
 * 此前两函数嵌在 Dashboard.tsx 页内（含 alertAudioCtx 单例），无法独立测试；
 * 移入 utils/notify.ts 后行为用例在此覆盖，Dashboard 侧仅保留组件级集成断言。
 *
 * 隔离策略：utils/notify 持有模块级 alertAudioCtx 单例，用例间共享会互相污染
 * （如“构造失败”用例要求单例为空），故每个用例 vi.resetModules + 动态 import 取新模块实例。
 */

/** AudioContext 桩（playAlertSound 使用） */
class FakeAudioContext {
  currentTime = 0
  destination = {}
  createOscillator() {
    return { connect: vi.fn(), frequency: {}, type: '', start: vi.fn(), stop: vi.fn() }
  }
  createGain() {
    return { connect: vi.fn(), gain: { setValueAtTime: vi.fn(), exponentialRampToValueAtTime: vi.fn() } }
  }
}

/** 取全新模块实例（单例隔离） */
async function freshNotify() {
  vi.resetModules()
  return await import('../utils/notify')
}

describe('playAlertSound 提示音', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('AudioContext 构造失败时静默降级（不抛错，单例保持未初始化）', async () => {
    class BoomAudioContext {
      constructor() { throw new Error('NotSupported') }
    }
    vi.stubGlobal('AudioContext', BoomAudioContext)
    const { playAlertSound } = await freshNotify()
    expect(() => playAlertSound()).not.toThrow()
  })

  it('AudioContext 可用时播放不抛错（880Hz 振荡器创建）', async () => {
    // vitest 4：vi.fn(箭头函数) 不可作为构造器 new（同下方 FakeNotificationClass 注释），function 表达式可构造
    const ctor = vi.fn(function () { return new FakeAudioContext() })
    vi.stubGlobal('AudioContext', ctor)
    const { playAlertSound } = await freshNotify()
    expect(() => playAlertSound()).not.toThrow()
    expect(ctor).toHaveBeenCalledTimes(1)
  })

  it('重复调用复用单例 AudioContext（不重复 new）', async () => {
    const ctor = vi.fn(function () { return new FakeAudioContext() })
    vi.stubGlobal('AudioContext', ctor)
    const { playAlertSound } = await freshNotify()
    playAlertSound()
    playAlertSound()
    playAlertSound()
    expect(ctor).toHaveBeenCalledTimes(1)
  })
})

describe('sendDesktopNotification 桌面通知', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('权限 granted 时走 Notification（不触发页内降级）', async () => {
    const notifySpy = vi.fn()
    // 真实 class 桩：vi.fn 包装的箭头实现作为构造器（new）调用会抛错
    class FakeNotificationClass {
      static permission = 'granted'
      constructor(title: string, opts?: object) {
        notifySpy(title, opts)
      }
    }
    vi.stubGlobal('Notification', FakeNotificationClass)
    const warnSpy = vi.spyOn(notification, 'warning').mockImplementation(() => undefined as never)
    const { sendDesktopNotification } = await freshNotify()
    sendDesktopNotification('标题', '内容')
    expect(notifySpy).toHaveBeenCalledWith('标题', expect.objectContaining({ body: '内容' }))
    expect(warnSpy).not.toHaveBeenCalled()
  })

  it('权限未授权/不支持时降级页内通知', async () => {
    const warnSpy = vi.spyOn(notification, 'warning').mockImplementation(() => undefined as never)
    // jsdom 无 Notification → 直接落入页内降级
    const { sendDesktopNotification } = await freshNotify()
    sendDesktopNotification('标题', '内容')
    expect(warnSpy).toHaveBeenCalledWith(expect.objectContaining({ message: '标题', description: '内容' }))
  })

  it('Notification 构造器抛错时降级页内通知（不静默丢弃）', async () => {
    class BoomNotificationClass {
      static permission = 'granted'
      constructor() { throw new Error('NotSupported') }
    }
    vi.stubGlobal('Notification', BoomNotificationClass)
    const warnSpy = vi.spyOn(notification, 'warning').mockImplementation(() => undefined as never)
    const { sendDesktopNotification } = await freshNotify()
    sendDesktopNotification('标题', '内容')
    expect(warnSpy).toHaveBeenCalledTimes(1)
  })
})
