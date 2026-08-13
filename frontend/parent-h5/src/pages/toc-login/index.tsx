/**
 * doing/85 TOC-001：toC 家庭账号注册/登录页
 * 路由：/toc/login
 * 手机号验证码注册/登录（独立于校园体系）；演示环境验证码回显；
 * 登录成功后跳转家庭档案页（/toc/profiles）。
 */
import { useEffect, useRef, useState } from 'react'
import { View, Text, Input, Button } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { validateTocPhone, validateTocCode } from '../../utils/tocAuth'
import {
  sendTocCode,
  tocRegister,
  tocLogin,
  saveTocSession,
} from '../../services/toc'
import './index.scss'

/** Taro Input 值兼容（detail.value / target.value，doing/73 踩坑清单同类） */
function inputValue(e: unknown): string {
  const ev = e as { detail?: { value?: string }; target?: { value?: string } }
  return ev.detail?.value ?? ev.target?.value ?? ''
}

export default function TocLoginPage() {
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [echoCode, setEchoCode] = useState('')
  const [cooldown, setCooldown] = useState(0)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  // F-11（doing/98）：倒计时 interval 挂 ref，卸载时清理（原局部变量 timer 泄漏 → 卸载后仍 setState）
  const cooldownTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  useEffect(() => () => {
    if (cooldownTimerRef.current) clearInterval(cooldownTimerRef.current)
  }, [])

  const handleSendCode = async () => {
    setError('')
    const phoneError = validateTocPhone(phone)
    if (phoneError) {
      setError(phoneError)
      return
    }
    try {
      const result = await sendTocCode(phone.trim())
      setEchoCode(result.code)
      setCooldown(60)
      cooldownTimerRef.current = setInterval(() => {
        setCooldown((c) => {
          if (c <= 1) {
            if (cooldownTimerRef.current) clearInterval(cooldownTimerRef.current)
            return 0
          }
          return c - 1
        })
      }, 1000)
    } catch (e) {
      setError(e instanceof Error ? e.message : '发送失败')
    }
  }

  const doAuth = async (mode: 'register' | 'login') => {
    setError('')
    const phoneError = validateTocPhone(phone)
    if (phoneError) {
      setError(phoneError)
      return
    }
    const codeError = validateTocCode(code)
    if (codeError) {
      setError(codeError)
      return
    }
    setLoading(true)
    try {
      const session = mode === 'register'
        ? await tocRegister(phone.trim(), code.trim())
        : await tocLogin(phone.trim(), code.trim())
      saveTocSession(session)
      Taro.navigateTo({ url: '/pages/toc-profiles/index' })
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <View className='toc-login'>
      <View className='toc-login__header'>
        <Text className='toc-login__title'>波波小伙伴</Text>
        <Text className='toc-login__subtitle'>家庭版 · 手机号注册/登录</Text>
      </View>
      <View className='toc-login__form'>
        <Input
          className='toc-login__input'
          type='number'
          maxlength={11}
          placeholder='请输入手机号'
          value={phone}
          onInput={(e) => setPhone(inputValue(e))}
        />
        <View className='toc-login__row'>
          <Input
            className='toc-login__input toc-login__input--code'
            type='number'
            maxlength={6}
            placeholder='请输入验证码'
            value={code}
            onInput={(e) => setCode(inputValue(e))}
          />
          <Button
            className='toc-login__code-btn'
            size='mini'
            disabled={cooldown > 0}
            onClick={() => void handleSendCode()}
          >
            {cooldown > 0 ? `${cooldown}s` : '获取验证码'}
          </Button>
        </View>
        {echoCode ? (
          <Text className='toc-login__echo'>演示环境验证码：{echoCode}</Text>
        ) : null}
        {error ? <Text className='toc-login__error'>{error}</Text> : null}
        <Button
          className='toc-login__submit'
          type='primary'
          loading={loading}
          onClick={() => void doAuth('login')}
        >
          登录
        </Button>
        <Button
          className='toc-login__submit toc-login__submit--ghost'
          loading={loading}
          onClick={() => void doAuth('register')}
        >
          注册新账号
        </Button>
      </View>
    </View>
  )
}
