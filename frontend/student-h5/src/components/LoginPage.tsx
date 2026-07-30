import { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import { pinLogin, setToken, setRefreshToken, setUser } from '../api'
import { CONSENT_VERSION } from './ConsentGate'
import { useWakeWord } from '../hooks/useWakeWord'
import { hasAnyVoiceprint } from '../utils/voiceprintStore'
import { VP_IDLE_TIMEOUT } from '../config/voiceprint'
import { useTheme, THEMES } from '../theme/ThemeProvider'
import VoiceLoginOverlay from './VoiceLoginOverlay'

/**
 * 学生端登录页（三主题 + 共享 Pad 适配）
 * - 主题跟随 ThemeProvider：ocean(海底世界) / garden(糖果乐园) / rainbow(星际探险)
 * - 登录 tab：昵称 + 彩虹键盘 PIN（0 占两格，无 ✓）
 * - 注册 tab：邀请码 + 昵称 + 性别 + 年龄
 * - 语音唤醒：一体化勾选框 + 声纹入口
 * - Pad 横屏：左品牌右表单
 */
export default function LoginPage({ onLogin, onRegister, onNeedConsent, initialTab = 'login' }) {
  const { themeId, changeTheme } = useTheme()
  const [tab, setTab] = useState(initialTab)
  const [showVoiceOverlay, setShowVoiceOverlay] = useState(false)
  const [voiceSupported, setVoiceSupported] = useState(false)
  const [idle, setIdle] = useState(false)
  const idleTimerRef = useRef(null)

  useEffect(() => {
    hasAnyVoiceprint().then((has) => setVoiceSupported(has))
  }, [])

  const wakeActive = tab === 'login' && voiceSupported && !showVoiceOverlay && !idle
  const { supported: wakeEnvSupported } = useWakeWord({
    active: wakeActive,
    onDetected: () => {
      resetIdleTimer()
      setShowVoiceOverlay(true)
    },
  })

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

  const handleVoiceComplete = (result) => {
    setShowVoiceOverlay(false)
    if (result.matched && result.userId) {
      onLogin()
    }
  }

  const handleVoiceCancel = () => setShowVoiceOverlay(false)
  const handleVoiceClick = () => { resetIdleTimer(); setShowVoiceOverlay(true) }

  return (
    <div className={`login-scene login-scene--${themeId}`}>
      {/* 动画背景元素 */}
      <SceneDecor themeId={themeId} />

      {/* 主题切换浮标 */}
      <div className={`theme-dock theme-dock--${themeId}`}>
        {Object.values(THEMES).map((t) => (
          <button
            key={t.id}
            onClick={() => changeTheme(t.id)}
            title={t.name}
            style={t.id === themeId ? { transform: 'scale(1.2)' } : undefined}
          >
            {t.emoji}
          </button>
        ))}
      </div>

      {/* Pad 横屏左侧品牌 */}
      <div className={`login-left-brand login-left-brand--${themeId}`}>
        <span className="mascot">{themeId === 'ocean' ? '🐬' : themeId === 'garden' ? '🍭' : '🚀'}</span>
        <h1>波波小精灵</h1>
        <p>AI 情绪陪伴助手</p>
        <span className="tagline">
          {themeId === 'ocean' ? '🌊 在海底世界找到属于你的角落' : themeId === 'garden' ? '🍬 甜蜜陪伴，快乐成长' : '✨ 探索星际，发现内心'}
        </span>
      </div>

      {/* 主卡片 */}
      <div className={`login-card login-card--${themeId}`}>
        {/* 品牌（竖屏显示，横屏由左侧品牌替代） */}
        <div className={`login-brand login-brand--${themeId}`}>
          <span className={`mascot mascot--${themeId}`}>
            {themeId === 'ocean' ? '🐬' : themeId === 'garden' ? '🍭' : '🚀'}
          </span>
          <h1>波波小精灵</h1>
          <p>AI 情绪陪伴助手</p>
        </div>

        {/* Tab 切换 */}
        <div className={`login-tabs login-tabs--${themeId}`}>
          <button className={tab === 'login' ? 'active' : ''} onClick={() => setTab('login')}>登录</button>
          <button className={tab === 'register' ? 'active' : ''} onClick={switchToRegister}>新注册</button>
        </div>

        {tab === 'login' ? (
          <PinLoginForm themeId={themeId} onLogin={onLogin} />
        ) : (
          <RegisterForm themeId={themeId} onRegister={onRegister} />
        )}

        {/* 声纹登录入口 */}
        {tab === 'login' && voiceSupported && wakeEnvSupported && (
          <div className={`voice-entry voice-entry--${themeId} ${idle ? 'disabled' : ''}`}>
            <div className="divider"><span>或</span></div>
            {idle ? (
              <button className={`voice-btn voice-btn--${themeId}`} onClick={() => resetIdleTimer()}>
                👆 点击屏幕唤醒
              </button>
            ) : (
              <button className={`voice-btn voice-btn--${themeId}`} onClick={handleVoiceClick}>
                🎤 对我说"你好，波波" <i className="dot" />
              </button>
            )}
            {!idle && <p className="sub">声纹识别，无需动手</p>}
          </div>
        )}
      </div>

      {/* 声纹识别覆盖层 */}
      {showVoiceOverlay && (
        <VoiceLoginOverlay mode="verify" onComplete={handleVoiceComplete} onCancel={handleVoiceCancel} />
      )}
    </div>
  )
}

/* ===== 动画背景装饰 ===== */
function SceneDecor({ themeId }) {
  const items = useMemo(() => {
    if (themeId === 'ocean') {
      return {
        bubbles: Array.from({ length: 8 }, (_, i) => ({
          id: i,
          size: 8 + Math.random() * 24,
          left: `${5 + Math.random() * 90}%`,
          dur: `${6 + Math.random() * 8}s`,
          delay: `${Math.random() * 5}s`,
        })),
        fish: [
          { emoji: '🐠', top: '25%', dur: '14s', delay: '0s' },
          { emoji: '🐟', top: '55%', dur: '18s', delay: '3s' },
          { emoji: '🐡', top: '72%', dur: '16s', delay: '7s' },
        ],
      }
    }
    if (themeId === 'garden') {
      return {
        candies: ['🍬', '🍭', '🧁', '🍩', '🍪', '🎀'].map((emoji, i) => ({
          id: i, emoji,
          left: `${8 + i * 16}%`,
          top: `${10 + (i % 3) * 30}%`,
          delay: `${i * 0.7}s`,
        })),
      }
    }
    // rainbow
    return {
      stars: Array.from({ length: 30 }, (_, i) => ({
        id: i,
        left: `${Math.random() * 100}%`,
        top: `${Math.random() * 100}%`,
        delay: `${Math.random() * 3}s`,
      })),
      planets: [
        { size: 40, color: 'rgba(139,92,246,0.3)', left: '12%', top: '18%' },
        { size: 24, color: 'rgba(236,72,153,0.25)', left: '80%', top: '30%' },
        { size: 16, color: 'rgba(6,182,212,0.3)', left: '65%', top: '70%' },
      ],
    }
  }, [themeId])

  if (themeId === 'ocean') {
    return (
      <>
        {items.bubbles.map((b) => (
          <div key={b.id} className="bubble" style={{ width: b.size, height: b.size, left: b.left, bottom: '-30px', animationDuration: b.dur, animationDelay: b.delay }} />
        ))}
        {items.fish.map((f, i) => (
          <span key={i} className="fish" style={{ top: f.top, animationDuration: f.dur, animationDelay: f.delay, fontSize: 22 }}>{f.emoji}</span>
        ))}
        <div className="sea-floor" />
      </>
    )
  }
  if (themeId === 'garden') {
    return (
      <>
        {items.candies.map((c) => (
          <span key={c.id} className="candy-float" style={{ left: c.left, top: c.top, animationDelay: c.delay, fontSize: 28 }}>{c.emoji}</span>
        ))}
      </>
    )
  }
  return (
    <>
      {items.stars.map((s) => (
        <div key={s.id} className="star" style={{ left: s.left, top: s.top, animationDelay: s.delay }} />
      ))}
      {items.planets.map((p, i) => (
        <div key={i} className="planet" style={{ width: p.size, height: p.size, background: p.color, left: p.left, top: p.top }} />
      ))}
      <div className="shooting-star" style={{ top: '15%', left: '20%', animationDelay: '1s' }} />
      <div className="shooting-star" style={{ top: '40%', left: '60%', animationDelay: '3.5s' }} />
    </>
  )
}

/* ===== PIN 码登录表单 ===== */
function PinLoginForm({ themeId, onLogin }) {
  const [name, setName] = useState('')
  const [pin, setPin] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [shakeKey, setShakeKey] = useState(0)
  const [wakeOn, setWakeOn] = useState(() => localStorage.getItem('mindsafe_wake_enabled') !== '0')

  const pressKey = (key) => {
    setError('')
    if (key === 'del') {
      setPin((p) => p.slice(0, -1))
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
      setUser({ userId: data.userId, userType: data.userType, pseudonym: data.displayName })
      onLogin()
    } catch (err) {
      setError(err.message || '登录失败')
      setPin('')
      setShakeKey((k) => k + 1) // 触发 PIN 指示器抖动反馈
    } finally {
      setLoading(false)
    }
  }

  // PIN 指示器
  const pinIndicator = themeId === 'ocean' ? 'pin-pearl' : themeId === 'garden' ? 'pin-jar' : 'pin-orb'

  return (
    <div>
      {/* 昵称 */}
      <div className={`login-field login-field--${themeId}`}>
        <label>你的昵称</label>
        <input
          value={name}
          onChange={(e) => { setName(e.target.value); setError('') }}
          placeholder="输入注册时的昵称"
          maxLength={12}
        />
      </div>

      {/* PIN 指示器（登录失败时抖动，key 变化重新触发动画） */}
      <div key={shakeKey} className={`pin-row ${shakeKey > 0 && error ? 'pin-row--shake' : ''}`}>
        {[0, 1, 2, 3, 4, 5].map((i) => (
          <div key={i} className={`${pinIndicator} ${i < pin.length ? 'filled' : ''}`} />
        ))}
      </div>

      {/* 彩虹键盘：1-9 + 0(占两格) + del，无 ✓ */}
      <div className={`rainbow-keypad rainbow-keypad--${themeId}`}>
        {['1','2','3','4','5','6','7','8','9'].map((k) => (
          <button key={k} className={`k${k}`} onClick={() => pressKey(k)}>{k}</button>
        ))}
        <button className="zero" onClick={() => pressKey('0')}>0</button>
        <button className="del" onClick={() => pressKey('del')}>⌫</button>
      </div>

      {/* 错误 */}
      {error && <p className={`login-error login-error--${themeId}`}>{error}</p>}

      {/* 进入按钮 */}
      <button
        className={`btn-enter btn-enter--${themeId}`}
        onClick={handleLogin}
        disabled={loading || !name.trim() || pin.length < 4}
      >
        {loading ? '正在进入...' : '进入 🚀'}
      </button>

      {/* 语音唤醒一体化勾选框 */}
      <div className="voice-row" style={{ marginTop: 14 }}>
        <label className={`wake-check wake-check--${themeId}`}>
          <input
            type="checkbox"
            checked={wakeOn}
            onChange={(e) => {
              const next = e.target.checked
              setWakeOn(next)
              localStorage.setItem('mindsafe_wake_enabled', next ? '1' : '0')
            }}
          />
          🎙️ 语音唤醒（说"哈喽波波"对话）
        </label>
      </div>
    </div>
  )
}

/* ===== 注册表单 ===== */
function RegisterForm({ themeId, onRegister }) {
  const [step, setStep] = useState('form')
  const [form, setForm] = useState({ inviteCode: '', pseudonym: '', gender: '', age: '', guardianPhone: '' })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [voiceConsent, setVoiceConsent] = useState(true)
  const [pin, setPin] = useState('')
  const [pinConfirm, setPinConfirm] = useState('')
  const [pinStep, setPinStep] = useState('input')
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
    // 未成年人保护：不满 14 周岁必须提供监护人手机号（与后端 TrialAuthStrategy 校验对齐）
    if (age < 14) {
      if (!form.guardianPhone.trim()) { setError('不满 14 周岁需填写家长手机号'); return }
      if (!/^1\d{10}$/.test(form.guardianPhone.trim())) { setError('请输入正确的 11 位手机号'); return }
    }
    setLoading(true); setError('')
    try {
      const { trialRegister, setToken: st, setRefreshToken: srt, setUser: su, markConsentDone } = await import('../api')
      const data = await trialRegister({
        inviteCode: form.inviteCode.trim(),
        pseudonym: form.pseudonym.trim(),
        age, role: 'student', gender: form.gender, consentVersion: CONSENT_VERSION,
        ...(age < 14 ? { guardianPhone: form.guardianPhone.trim() } : {}),
      })
      st(data.token)
      if (data.refreshToken) srt(data.refreshToken)
      su({ userId: data.userId, userType: data.userType, pseudonym: data.pseudonym, gender: form.gender, familyCode: data.familyCode })
      markConsentDone()
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
      else if (pin.length < 6) setPin((p) => p + key)
    } else {
      if (key === 'del') setPinConfirm((p) => p.slice(0, -1))
      else if (pinConfirm.length < 6) setPinConfirm((p) => p + key)
    }
  }

  const confirmPinStep = () => {
    if (pin.length < 4) { setPinError('至少输入 4 位数字'); return }
    setPinStep('confirm')
  }

  const handleSetPin = async () => {
    if (pinConfirm !== pin) {
      setPinError('两次输入不一致，请重新设置')
      setPin(''); setPinConfirm(''); setPinStep('input')
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

  const pinIndicator = themeId === 'ocean' ? 'pin-pearl' : themeId === 'garden' ? 'pin-jar' : 'pin-orb'

  // === 注册成功 ===
  if (step === 'done') {
    return (
      <div className={`done-panel done-panel--${themeId}`}>
        <span className="emoji">🎉</span>
        <h2>注册成功！</h2>
        <p>下次打开时用昵称和秘密数字就能登录啦</p>
        <button className={`btn-enter btn-enter--${themeId}`} onClick={onRegister} style={{ marginTop: 18 }}>
          开始使用 🚀
        </button>
      </div>
    )
  }

  // === 设置 PIN ===
  if (step === 'set-pin') {
    const currentPin = pinStep === 'input' ? pin : pinConfirm
    return (
      <div>
        <div className={`login-brand login-brand--${themeId}`} style={{ marginBottom: 10 }}>
          <span className="mascot" style={{ fontSize: 36 }}>🔐</span>
          <h1 style={{ fontSize: 17 }}>{pinStep === 'input' ? '设置你的秘密数字' : '再输入一次确认'}</h1>
          <p>4-6 位数字，下次登录用</p>
        </div>

        <div className="pin-row">
          {[0, 1, 2, 3, 4, 5].map((i) => (
            <div key={i} className={`${pinIndicator} ${i < currentPin.length ? 'filled' : ''}`} />
          ))}
        </div>

        <div className={`rainbow-keypad rainbow-keypad--${themeId}`}>
          {['1','2','3','4','5','6','7','8','9'].map((k) => (
            <button key={k} className={`k${k}`} onClick={() => pressPinKey(k)}>{k}</button>
          ))}
          <button className="zero" onClick={() => pressPinKey('0')}>0</button>
          <button className="del" onClick={() => pressPinKey('del')}>⌫</button>
        </div>

        {pinError && <p className={`login-error login-error--${themeId}`}>{pinError}</p>}

        <button
          className={`btn-enter btn-enter--${themeId}`}
          onClick={pinStep === 'input' ? confirmPinStep : handleSetPin}
          disabled={currentPin.length < 4}
        >
          {pinStep === 'input' ? '下一步' : '确认设置'}
        </button>

        <button className={`skip-pin skip-pin--${themeId}`} onClick={() => setStep('done')}>
          先不设置，以后再说
        </button>
      </div>
    )
  }

  // === 注册表单 ===
  return (
    <form onSubmit={handleRegister}>
      <div className={`login-field login-field--${themeId}`}>
        <label>邀请码 *</label>
        <input value={form.inviteCode} onChange={(e) => update('inviteCode', e.target.value)} placeholder="老师发的邀请码" />
      </div>

      <div className={`login-field login-field--${themeId}`}>
        <label>昵称 *（2-12 字）</label>
        <input value={form.pseudonym} onChange={(e) => update('pseudonym', e.target.value)} placeholder="给自己取个名字吧" maxLength={12} />
      </div>

      {/* 性别 */}
      <div className={`login-field login-field--${themeId}`}>
        <label>性别 *</label>
        <div className={`gender-btns gender-btns--${themeId}`}>
          <button type="button" className={form.gender === 'male' ? 'sel' : ''} onClick={() => update('gender', 'male')}>👦 男生</button>
          <button type="button" className={form.gender === 'female' ? 'sel' : ''} onClick={() => update('gender', 'female')}>👧 女生</button>
        </div>
      </div>

      {/* 年龄 */}
      <div className={`login-field login-field--${themeId}`}>
        <label>年龄 *</label>
        <input type="number" value={form.age} onChange={(e) => update('age', e.target.value)} placeholder="你的年龄" min={6} max={120} />
        {form.age && parseInt(form.age) < 14 && (
          <p className={`age-warn age-warn--${themeId}`}>⚠️ 不满 14 周岁建议在家长陪同下使用</p>
        )}
      </div>

      {/* 监护人手机号（不满 14 周岁必填，未成年人保护法要求） */}
      {form.age && parseInt(form.age) < 14 && (
        <div className={`login-field login-field--${themeId}`}>
          <label>家长手机号 *</label>
          <input
            type="tel"
            value={form.guardianPhone}
            onChange={(e) => update('guardianPhone', e.target.value)}
            placeholder="监护人的 11 位手机号"
            maxLength={11}
          />
        </div>
      )}

      <p className={`login-hint login-hint--${themeId}`}>
        💡 邀请码由学校心理老师发放，体验测试可用 <strong>DEMO2026</strong>
      </p>

      {/* 声纹采集授权 */}
      <label className={`consent-row consent-row--${themeId}`}>
        <input type="checkbox" checked={voiceConsent} onChange={(e) => setVoiceConsent(e.target.checked)} />
        <span>同意在这台设备上录入我的声音，用于下次快速登录。声音信息只保存在这台设备上，不会上传到任何服务器。</span>
      </label>

      {error && <p className={`login-error login-error--${themeId}`}>{error}</p>}

      <button type="submit" className={`btn-enter btn-enter--${themeId}`} disabled={loading}>
        {loading ? '正在注册...' : '注册 🚀'}
      </button>
    </form>
  )
}
