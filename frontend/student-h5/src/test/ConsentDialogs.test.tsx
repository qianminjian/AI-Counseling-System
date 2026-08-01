import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { renderHook, act } from '@testing-library/react'
import VoiceConsentDialog, { useVoiceConsent } from '../components/VoiceConsentDialog'
import VoiceCallConsentDialog, { useVoiceCallConsent } from '../components/VoiceCallConsentDialog'

describe('VoiceConsentDialog', () => {
  it('渲染标题和说明', () => {
    render(<VoiceConsentDialog onGrant={vi.fn()} onDeny={vi.fn()} />)
    expect(screen.getByText('语音功能说明')).toBeTruthy()
    expect(screen.getByText(/仅用于实时转文字和情绪分析/)).toBeTruthy()
    expect(screen.getByText(/不会被保存/)).toBeTruthy()
  })

  it('点击同意触发 onGrant', () => {
    const onGrant = vi.fn()
    render(<VoiceConsentDialog onGrant={onGrant} onDeny={vi.fn()} />)
    fireEvent.click(screen.getByText('我知道了，同意使用'))
    expect(onGrant).toHaveBeenCalledTimes(1)
  })

  it('点击暂不使用触发 onDeny', () => {
    const onDeny = vi.fn()
    render(<VoiceConsentDialog onGrant={vi.fn()} onDeny={onDeny} />)
    fireEvent.click(screen.getByText('暂不使用'))
    expect(onDeny).toHaveBeenCalledTimes(1)
  })

  it('包含法规提示', () => {
    render(<VoiceConsentDialog onGrant={vi.fn()} onDeny={vi.fn()} />)
    expect(screen.getByText(/未成年人网络保护条例/)).toBeTruthy()
  })
})

describe('useVoiceConsent', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('初始无授权', () => {
    const { result } = renderHook(() => useVoiceConsent())
    expect(result.current.hasConsent()).toBe(false)
    expect(result.current.showDialog).toBe(false)
  })

  it('requestConsent 未授权时弹窗并返回 false', () => {
    const { result } = renderHook(() => useVoiceConsent())
    let ret: boolean
    act(() => { ret = result.current.requestConsent() })
    expect(ret!).toBe(false)
    expect(result.current.showDialog).toBe(true)
  })

  it('grantConsent 持久化到 localStorage', () => {
    const { result } = renderHook(() => useVoiceConsent())
    act(() => { result.current.grantConsent() })
    expect(localStorage.getItem('mindsafe_voice_consent_v1')).toBe('granted')
    expect(result.current.showDialog).toBe(false)
    expect(result.current.hasConsent()).toBe(true)
  })

  it('已授权时 requestConsent 直接返回 true', () => {
    localStorage.setItem('mindsafe_voice_consent_v1', 'granted')
    const { result } = renderHook(() => useVoiceConsent())
    let ret: boolean
    act(() => { ret = result.current.requestConsent() })
    expect(ret!).toBe(true)
    expect(result.current.showDialog).toBe(false)
  })

  it('denyConsent 关闭弹窗不持久化', () => {
    const { result } = renderHook(() => useVoiceConsent())
    act(() => { result.current.requestConsent() })
    act(() => { result.current.denyConsent() })
    expect(result.current.showDialog).toBe(false)
    expect(result.current.hasConsent()).toBe(false)
  })
})

describe('VoiceCallConsentDialog', () => {
  it('渲染标题和说明', () => {
    render(<VoiceCallConsentDialog onGrant={vi.fn()} onDeny={vi.fn()} />)
    expect(screen.getByText('语音唤醒说明')).toBeTruthy()
    expect(screen.getByText(/只在你的手机\/电脑上处理/)).toBeTruthy()
  })

  it('点击开启触发 onGrant', () => {
    const onGrant = vi.fn()
    render(<VoiceCallConsentDialog onGrant={onGrant} onDeny={vi.fn()} />)
    fireEvent.click(screen.getByText('我知道了，开启'))
    expect(onGrant).toHaveBeenCalledTimes(1)
  })

  it('点击暂不使用触发 onDeny', () => {
    const onDeny = vi.fn()
    render(<VoiceCallConsentDialog onGrant={vi.fn()} onDeny={onDeny} />)
    fireEvent.click(screen.getByText('暂不使用'))
    expect(onDeny).toHaveBeenCalledTimes(1)
  })
})

describe('useVoiceCallConsent', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('初始无授权', () => {
    const { result } = renderHook(() => useVoiceCallConsent())
    expect(result.current.hasConsent()).toBe(false)
  })

  it('grantConsent 持久化', () => {
    const { result } = renderHook(() => useVoiceCallConsent())
    act(() => { result.current.grantConsent() })
    expect(localStorage.getItem('mindsafe_voicecall_consent_v1')).toBe('granted')
    expect(result.current.hasConsent()).toBe(true)
  })

  it('requestConsent 已授权返回 true', () => {
    localStorage.setItem('mindsafe_voicecall_consent_v1', 'granted')
    const { result } = renderHook(() => useVoiceCallConsent())
    expect(result.current.requestConsent()).toBe(true)
  })

  it('requestConsent 未授权弹窗', () => {
    const { result } = renderHook(() => useVoiceCallConsent())
    act(() => { result.current.requestConsent() })
    expect(result.current.showDialog).toBe(true)
  })
})
