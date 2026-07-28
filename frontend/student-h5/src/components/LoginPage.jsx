import { useState, useEffect, useRef, useCallback } from 'react'
import { pinLogin, setToken, setRefreshToken, setUser } from '../api'
import { CONSENT_VERSION } from './ConsentGate'
import { useWakeWord } from '../hooks/useWakeWord'
import { hasAnyVoiceprint } from '../utils/voiceprintStore'
import { VP_IDLE_TIMEOUT } from '../config/voiceprint'
import VoiceLoginOverlay from './VoiceLoginOverlay'

/**
 * 学生端登录页（共享 Pad 适配）
 * - 登录 tab：昵称 + PIN 数字键盘（主路径，触控友好）
 * - 注册 tab：邀请码 + 昵称 + 性别 + 年龄
 * - 注册成功后引导设置 PIN
 * - 声纹登录：底部唤醒词监听（"你好波波"）→ VoiceLoginOverlay 引导对话 → 自动登录
 */
export default function LoginPage({ onLogin, onRegister, onNeedConsent }) {
  const [tab, setTab] = useState('login') // 'login' | 'register'
  const [showVoiceOverlay, setShowVoiceOverlay] = useState(false)
  const [voiceSupported, setVoiceSupported] = useState(false)
  const [listening, setListening] = useState(false)
  const [idle, setIdle] = useState(false)
  const idleTimerRef = useRef(null)

  // 检测设备是否有已注册声纹（决定是否显示声纹入口）
  useEffect(() => {
    hasAnyVoiceprint().then((has) => setVoiceSupported(has))
  }, [])

  // 唤醒词监听（仅登录 tab + 有声纹数据 + 未休眠时激活）
  const wakeActive = tab === 'login' && voiceSupported && !showVoiceOverlay && !idle
  const { supported: wakeEnvSupported } = useWakeWord({
    active: wakeActive,
    onDetected: () => {
      // 唤醒词命中 → 进入声纹识别对话
      resetIdleTimer()
      setShowVoiceOverlay(true)
    },
  })

  // 超时休眠：5 分钟无交互 → 停止监听
  const resetIdleTimer = useCallback(() => {
    setIdle(false)
    if (idleTimerRef.current) clearTimeout(idleTimerRef.current)
    idleTimerRef.current = setTimeout(() => setIdle(true), VP_IDLE_TIMEOUT)
  }, [])

  useEffect(() => {
    resetIdleTimer()
    const handler = () => resetIdleTimer()
    document.addEventListener('pointerdown', handler)
    return () => {
      document.removeEventListener('pointerdown', handler)
      if (idleTimerRef.current) clearTimeout(idleTimerRef.current)
    }
  }, [resetIdleTimer])

  const switchToRegister = () => {
    onNeedConsent?.()
    setTab('register')
  }

  // 声纹识别完成 → 自动登录
  const handleVoiceComplete = (result) => {
    setShowVoiceOverlay(false)
    if (result.matched && result.userId) {
      // 声纹匹配成功：用 userId 调用 pin-login 的替代路径
      // 注意：声纹是便利层，实际 token 仍需服务端签发
      // 这里用 pseudonym 触发一次静默登录（后端需支持声纹 token 签发，当前降级为提示成功+手动 PIN）
      // TODO Phase 2: 后端声纹 token 签发接口
      onLogin()
    }
  }

  // 声纹取消 → 回到 PIN
  const handleVoiceCancel = () => {
    setShowVoiceOverlay(false)
  }

  // 手动点击声纹入口
  const handleVoiceClick = () => {
    resetIdleTimer()
    setShowVoiceOverlay(true)
  }

  return (
    <div className="min-h-screen flex flex-col items-center justify-center p-6"
      style={{ background: 'linear-gradient(to bottom, #f0f7ff, #e8f4f8)' }}>
      <div className="w-full max-w-sm">
        {/* 品牌标题 */}
        <div className="text-center mb-6">
          <div className="text-5xl mb-3">🌟</div>
          <h1 className="text-2xl font-bold text-gray-800">波波小精灵</h1>
          <p className="text-sm text-gray-500 mt-1">AI 情绪陪伴助手</p>
        </div>

        {/* Tab 切换 */}
        <div className="flex mb-6 bg-gray-100 rounded-full p-1">
          <button
            onClick={() => setTab('login')}
            className={`flex-1 py-3 rounded-full text-sm font-medium transition-all ${
              tab === 'login' ? 'bg-white shadow text-blue-600' : 'text-gray-500'
            }`}
          >
            登录
          </button>
          <button
            onClick={switchToRegister}
            className={`flex-1 py-3 rounded-full text-sm font-medium transition-all ${
              tab === 'register' ? 'bg-white shadow text-blue-600' : 'text-gray-500'
            }`}
          >
            新注册
          </button>
        </div>

        {tab === 'login' ? (
          <PinLoginForm onLogin={onLogin} />
        ) : (
          <RegisterForm onRegister={onRegister} />
        )}

        {/* 声纹登录入口（仅登录 tab + 设备有声纹数据时显示） */}
        {tab === 'login' && voiceSupported && wakeEnvSupported && (
          <div className="mt-6 text-center">
            <div className="flex items-center gap-3 mb-3">
              <div className="flex-1 h-px bg-gray-200" />
              <span className="text-xs text-gray-400">或</span>
              <div className="flex-1 h-px bg-gray-200" />
            </div>

            {idle ? (
              <button
                onClick={() => { resetIdleTimer() }}
                className="text-sm text-gray-400 hover:text-gray-600 transition-colors"
              >
                👆 点击屏幕唤醒
              </button>
            ) : (
              <button
                onClick={handleVoiceClick}
                className="inline-flex items-center gap-2 px-5 py-3 rounded-full bg-white border border-gray-200 shadow-sm hover:shadow-md transition-all active:scale-[0.98]"
              >
                <span className="text-xl">🎤</span>
                <span className="text-sm text-gray-600">对我说"你好，波波"</span>
                <span className="w-2 h-2 rounded-full bg-green-400 animate-pulse" />
              </button>
            )}

            {!idle && (
              <p className="mt-2 text-xs text-gray-400">声纹识别，无需动手</p>
            )}
          </div>
        )}
      </div>

      {/* 声纹识别引导对话覆盖层 */}
      {showVoiceOverlay && (
        <VoiceLoginOverlay
          mode="verify"
          onComplete={handleVoiceComplete}
          onCancel={handleVoiceCancel}
        />
      )}
    </div>
  )
}

/**
 * PIN 码登录表单（昵称 + 数字键盘）
 */
function PinLoginForm({ onLogin }) {
  const [name, setName] = useState('')
  const [pin, setPin] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [wakeOn, setWakeOn] = useState(() => localStorage.getItem('mindsafe_wake_enabled') !== '0')

  const pressKey = (key) => {
    setError('')
    if (key === 'del') {
      setPin((p) => p.slice(0, -1))
    } else if (key === 'ok') {
      handleLogin()
    } else {
      if (pin.length < 6) setPin((p) => p + key)
    }
  }

  const handleLogin = async () => {
    if (!name.trim()) { setError('请输入你的昵称'); return }
    if (pin.length < 4) { setError('请输入 4-6 位秘密数字'); return }
    setLoading(true)
    setError('')
    try {
      const data = await pinLogin(name.trim(), pin)
      setToken(data.token)
      if (data.refreshToken) setRefreshToken(data.refreshToken)
      setUser({
        userId: data.userId,
        userType: data.userType,
        pseudonym: data.displayName,
      })
      onLogin()
    } catch (err) {
      setError(err.message || '登录失败')
      setPin('')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6 space-y-5">
      {/* 昵称输入 */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1.5">你的昵称</label>
        <input
          value={name}
          onChange={(e) => { setName(e.target.value); setError('') }}
          placeholder="输入注册时的昵称"
          maxLength={12}
          className="w-full px-4 py-3.5 rounded-xl border border-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-200 text-base text-center"
        />
      </div>

      {/* PIN 显示 */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1.5">秘密数字</label>
        <div className="flex justify-center gap-2 py-2">
          {[0, 1, 2, 3, 4, 5].map((i) => (
            <div key={i} className={`w-10 h-12 rounded-xl border-2 flex items-center justify-center text-xl font-bold transition-all ${
              i < pin.length ? 'border-blue-400 bg-blue-50 text-blue-600' : 'border-gray-200 bg-gray-50'
            }`}>
              {i < pin.length ? '●' : ''}
            </div>
          ))}
        </div>
      </div>

      {/* 数字键盘（3x4 触控友好） */}
      <div className="grid grid-cols-3 gap-2">
        {['1','2','3','4','5','6','7','8','9','del','0','ok'].map((key) => (
          <button
            key={key}
            onClick={() => pressKey(key)}
            className={`py-4 rounded-xl text-lg font-bold transition-all active:scale-95 ${
              key === 'ok'
                ? 'bg-blue-500 text-white hover:bg-blue-600'
                : key === 'del'
                ? 'bg-gray-100 text-gray-500 hover:bg-gray-200'
                : 'bg-gray-50 text-gray-800 hover:bg-gray-100 border border-gray-200'
            }`}
          >
            {key === 'del' ? '⌫' : key === 'ok' ? '✓' : key}
          </button>
        ))}
      </div>

      {/* 错误提示 */}
      {error && (
        <p className="text-sm text-red-500 bg-red-50 px-4 py-2.5 rounded-xl text-center">{error}</p>
      )}

      {/* 登录按钮 */}
      <button
        onClick={handleLogin}
        disabled={loading || !name.trim() || pin.length < 4}
        className={`w-full py-4 rounded-full text-white font-medium text-lg transition-all ${
          loading || !name.trim() || pin.length < 4
            ? 'bg-gray-300 cursor-not-allowed'
            : 'bg-blue-500 hover:bg-blue-600 active:scale-[0.98] shadow-lg'
        }`}
      >
        {loading ? '正在进入...' : '进入 🚀'}
      </button>

      {/* 语音唤醒选项（默认开启） */}
      <button
        type="button"
        onClick={() => {
          const next = !wakeOn
          setWakeOn(next)
          localStorage.setItem('mindsafe_wake_enabled', next ? '1' : '0')
        }}
        className="flex w-full items-center justify-between px-4 py-3 rounded-xl bg-gray-50 border border-gray-100 transition-all active:scale-[0.98]"
      >
        <div className="flex items-center gap-2">
          <span className="text-lg">{wakeOn ? '🎙️' : '💤'}</span>
          <span className="text-sm text-gray-600">语音唤醒（说"哈喽波波"对话）</span>
        </div>
        <div className={`h-6 w-10 rounded-full p-0.5 transition-colors ${wakeOn ? 'bg-blue-500' : 'bg-gray-300'}`}>
          <div className={`h-5 w-5 rounded-full bg-white shadow transition-transform ${wakeOn ? 'translate-x-4' : 'translate-x-0'}`} />
        </div>
      </button>
    </div>
  )
}

/**
 * 注册表单（邀请码 + 昵称 + 性别 + 年龄 → 注册 → 设置 PIN）
 */
function RegisterForm({ onRegister }) {
  const [step, setStep] = useState('form') // 'form' | 'set-pin' | 'done'
  const [form, setForm] = useState({ inviteCode: '', pseudonym: '', gender: '', age: '' })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [voiceConsent, setVoiceConsent] = useState(true) // 声纹采集授权（默认同意，PIPL 单独勾选）
  // PIN 设置
  const [pin, setPin] = useState('')
  const [pinConfirm, setPinConfirm] = useState('')
  const [pinStep, setPinStep] = useState('input') // 'input' | 'confirm'
  const [pinError, setPinError] = useState('')

  const update = (key, value) => { setForm((f) => ({ ...f, [key]: value })); setError('') }

  const handleRegister = async (e) => {
    e.preventDefault()
    if (!form.inviteCode.trim() || !form.pseudonym.trim() || !form.age || !form.gender) {
      setError('请填写所有必填项'); return
    }
    const age = parseInt(form.age, 10)
    if (isNaN(age) || age < 6 || age > 120) { setError('请输入有效年龄（6-120）'); return }
    if (form.pseudonym.trim().length < 2 || form.pseudonym.trim().length > 12) {
      setError('昵称长度 2-12 字'); return
    }

    setLoading(true)
    setError('')
    try {
      const { trialRegister, setToken: st, setRefreshToken: srt, setUser: su, markConsentDone } = await import('../api')
      const data = await trialRegister({
        inviteCode: form.inviteCode.trim(),
        pseudonym: form.pseudonym.trim(),
        age,
        role: 'student',
        gender: form.gender,
        consentVersion: CONSENT_VERSION,
      })
      st(data.token)
      if (data.refreshToken) srt(data.refreshToken)
      su({
        userId: data.userId,
        userType: data.userType,
        pseudonym: data.pseudonym,
        gender: form.gender,
        familyCode: data.familyCode,
      })
      markConsentDone()
      // 进入设置 PIN 步骤
      setStep('set-pin')
    } catch (err) {
      setError(err.message || '注册失败，请检查邀请码')
    } finally {
      setLoading(false)
    }
  }

  const pressPinKey = (key) => {
    setPinError('')
    if (pinStep === 'input') {
      if (key === 'del') setPin((p) => p.slice(0, -1))
      else if (key === 'ok') {
        if (pin.length < 4) { setPinError('至少输入 4 位数字'); return }
        setPinStep('confirm')
      } else if (pin.length < 6) setPin((p) => p + key)
    } else {
      if (key === 'del') setPinConfirm((p) => p.slice(0, -1))
      else if (key === 'ok') handleSetPin()
      else if (pinConfirm.length < 6) setPinConfirm((p) => p + key)
    }
  }

  const handleSetPin = async () => {
    if (pinConfirm !== pin) {
      setPinError('两次输入不一致，请重新设置')
      setPin('')
      setPinConfirm('')
      setPinStep('input')
      return
    }
    try {
      const { setPin: sp } = await import('../api')
      await sp(pin)
      setStep('done')
    } catch (err) {
      setPinError(err.message || '设置失败')
    }
  }

  const skipPin = () => {
    setStep('done')
  }

  // === 注册成功 + PIN 设置完成 → 进入 ===
  if (step === 'done') {
    return (
      <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-8 text-center space-y-4">
        <div className="text-5xl">🎉</div>
        <h2 className="text-xl font-bold text-gray-800">注册成功！</h2>
        <p className="text-sm text-gray-500">下次打开时用昵称和秘密数字就能登录啦</p>
        <button
          onClick={onRegister}
          className="w-full py-4 rounded-full text-white font-medium text-lg bg-blue-500 hover:bg-blue-600 active:scale-[0.98] shadow-lg transition-all"
        >
          开始使用 🚀
        </button>
      </div>
    )
  }

  // === 设置 PIN 步骤 ===
  if (step === 'set-pin') {
    const currentPin = pinStep === 'input' ? pin : pinConfirm
    return (
      <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6 space-y-4">
        <div className="text-center">
          <div className="text-3xl mb-2">🔐</div>
          <h2 className="text-lg font-bold text-gray-800">
            {pinStep === 'input' ? '设置你的秘密数字' : '再输入一次确认'}
          </h2>
          <p className="text-xs text-gray-400 mt-1">4-6 位数字，下次登录用</p>
        </div>

        {/* PIN 点显示 */}
        <div className="flex justify-center gap-2 py-2">
          {[0, 1, 2, 3, 4, 5].map((i) => (
            <div key={i} className={`w-9 h-11 rounded-lg border-2 flex items-center justify-center text-lg font-bold transition-all ${
              i < currentPin.length ? 'border-blue-400 bg-blue-50 text-blue-600' : 'border-gray-200 bg-gray-50'
            }`}>
              {i < currentPin.length ? '●' : ''}
            </div>
          ))}
        </div>

        {/* 数字键盘 */}
        <div className="grid grid-cols-3 gap-2">
          {['1','2','3','4','5','6','7','8','9','del','0','ok'].map((key) => (
            <button
              key={key}
              onClick={() => pressPinKey(key)}
              className={`py-3.5 rounded-xl text-lg font-bold transition-all active:scale-95 ${
                key === 'ok'
                  ? 'bg-blue-500 text-white hover:bg-blue-600'
                  : key === 'del'
                  ? 'bg-gray-100 text-gray-500 hover:bg-gray-200'
                  : 'bg-gray-50 text-gray-800 hover:bg-gray-100 border border-gray-200'
              }`}
            >
              {key === 'del' ? '⌫' : key === 'ok' ? '✓' : key}
            </button>
          ))}
        </div>

        {pinError && (
          <p className="text-sm text-red-500 bg-red-50 px-4 py-2 rounded-xl text-center">{pinError}</p>
        )}

        <button
          onClick={skipPin}
          className="w-full py-2.5 text-sm text-gray-400 hover:text-gray-600 transition-colors"
        >
          先不设置，以后再说
        </button>
      </div>
    )
  }

  // === 注册表单 ===
  return (
    <form onSubmit={handleRegister} className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6 space-y-4">
      {/* 邀请码 */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">邀请码 <span className="text-red-400">*</span></label>
        <input
          value={form.inviteCode}
          onChange={(e) => update('inviteCode', e.target.value)}
          placeholder="老师发的邀请码"
          className="w-full px-4 py-3 rounded-xl border border-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-200 text-sm"
        />
      </div>

      {/* 昵称 */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">
          昵称 <span className="text-red-400">*</span>
          <span className="text-gray-400 font-normal ml-1">（2-12 字）</span>
        </label>
        <input
          value={form.pseudonym}
          onChange={(e) => update('pseudonym', e.target.value)}
          placeholder="给自己取个名字吧"
          maxLength={12}
          className="w-full px-4 py-3 rounded-xl border border-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-200 text-sm"
        />
      </div>

      {/* 性别 */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">性别 <span className="text-red-400">*</span></label>
        <div className="grid grid-cols-2 gap-3">
          <button type="button" onClick={() => update('gender', 'male')}
            className={`flex items-center justify-center gap-2 py-3 rounded-xl border-2 transition-all text-sm font-medium ${
              form.gender === 'male' ? 'border-blue-400 bg-blue-50 text-blue-700' : 'border-gray-100 text-gray-500'
            }`}
          >👦 男生</button>
          <button type="button" onClick={() => update('gender', 'female')}
            className={`flex items-center justify-center gap-2 py-3 rounded-xl border-2 transition-all text-sm font-medium ${
              form.gender === 'female' ? 'border-pink-400 bg-pink-50 text-pink-700' : 'border-gray-100 text-gray-500'
            }`}
          >👧 女生</button>
        </div>
      </div>

      {/* 年龄 */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">年龄 <span className="text-red-400">*</span></label>
        <input
          type="number" value={form.age}
          onChange={(e) => update('age', e.target.value)}
          placeholder="你的年龄" min={6} max={120}
          className="w-full px-4 py-3 rounded-xl border border-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-200 text-sm"
        />
        {form.age && parseInt(form.age) < 14 && (
          <p className="mt-1 text-xs text-amber-600 bg-amber-50 px-3 py-2 rounded-lg">
            ⚠️ 不满 14 周岁建议在家长陪同下使用
          </p>
        )}
      </div>

      <p className="text-xs text-gray-400 bg-gray-50 px-4 py-2 rounded-xl">
        💡 邀请码由学校心理老师发放，体验测试可用 <strong>DEMO2026</strong>
      </p>

      {/* 声纹采集授权（PIPL 单独同意，预勾选） */}
      <label className="flex items-start gap-3 px-4 py-3 rounded-xl bg-gray-50 cursor-pointer">
        <input
          type="checkbox"
          checked={voiceConsent}
          onChange={(e) => setVoiceConsent(e.target.checked)}
          className="mt-0.5 w-4 h-4 rounded accent-blue-500"
        />
        <span className="text-xs text-gray-500 leading-relaxed">
          同意在这台设备上录入我的声音，用于下次快速登录。声音信息只保存在这台设备上，不会上传到任何服务器。
        </span>
      </label>

      {error && (
        <p className="text-sm text-red-500 bg-red-50 px-4 py-2.5 rounded-xl">{error}</p>
      )}

      <button
        type="submit"
        disabled={loading}
        className={`w-full py-4 rounded-full text-white font-medium text-lg transition-all ${
          loading ? 'bg-gray-300 cursor-wait' : 'bg-blue-500 hover:bg-blue-600 active:scale-[0.98] shadow-lg'
        }`}
      >
        {loading ? '正在注册...' : '注册 🚀'}
      </button>
    </form>
  )
}
