import { useState, useEffect, useCallback, useMemo } from 'react'
import { pinLogin, setToken, setRefreshToken, setUser, issueVoiceCredential, getVoiceprintConfig, remoteVoiceprintEnroll } from '../api'
import { CONSENT_VERSION } from './ConsentGate'
import { hasAnyVoiceprint, enrollVoiceprint, saveVoiceCredential } from '../utils/voiceprintStore'
import { useTheme, THEMES } from '../theme/ThemeProvider'
import { preloadVoiceprintModel, useVoiceprintModelStatus } from '../hooks/useVoiceprint'
import { preloadWakeModel, useWakeModelStatus } from '../hooks/useWakeWord'
import VoiceLoginOverlay from './VoiceLoginOverlay'
import SceneDecor from './SceneDecor'
import ConfirmDialog from './ConfirmDialog'

/**
 * 学生端登录页（三主题 + 共享 Pad 适配）
 * - 主题跟随 ThemeProvider：ocean(海底世界) / garden(糖果乐园) / rainbow(星际探险)
 * - 登录 tab：昵称 + 彩虹键盘 PIN（0 占两格，无 ✓）
 * - 注册 tab：邀请码 + 昵称 + 性别 + 年龄
 * - 声音进入：与 PIN 并列的显式按钮触发（无被动监听，design/28 §2.4 隐私即设计）
 * - Pad 横屏：左品牌右表单
 */
export default function LoginPage({ onLogin, onRegister, onNeedConsent, initialTab = 'login' }) {
  const { themeId, changeTheme } = useTheme()
  const [tab, setTab] = useState(initialTab)
  const [showVoiceOverlay, setShowVoiceOverlay] = useState(false)
  const [hasVoiceprint, setHasVoiceprint] = useState(false)
  const [showNoVpTip, setShowNoVpTip] = useState(false)

  useEffect(() => {
    hasAnyVoiceprint().then((has) => setHasVoiceprint(has))
  }, [])

  // 预加载声纹模型 + 唤醒词模型：登录页一打开就开始下载
  useEffect(() => {
    preloadVoiceprintModel()
    preloadWakeModel()
  }, [])

  // 浏览器是否支持麦克风（决定是否显示声音进入按钮）
  const micSupported = useMemo(
    () => typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia,
    []
  )

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

  /** 点击"声音进入"：有声纹进识别流程，没有给引导提示 */
  const handleVoiceClick = useCallback(() => {
    if (hasVoiceprint) {
      setShowVoiceOverlay(true)
    } else {
      setShowNoVpTip(true)
    }
  }, [hasVoiceprint])

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

        {/* 声音进入（显式按钮触发，与 PIN 并列；无被动监听） */}
        {tab === 'login' && micSupported && (
          <div className={`voice-entry voice-entry--${themeId}`}>
            <div className="divider"><span>或</span></div>
            <p className="sub" style={{ marginTop: 0, marginBottom: 8 }}>对波波说句话，直接进入</p>
            <button className={`voice-btn voice-btn--${themeId}`} onClick={handleVoiceClick}>
              🎤 声音进入
            </button>
          </div>
        )}
      </div>

      {/* 未录声纹引导提示 */}
      {showNoVpTip && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-6" onClick={() => setShowNoVpTip(false)}>
          <div className="absolute inset-0 bg-black/40" />
          <div
            className="relative w-full max-w-xs rounded-3xl bg-white p-6 text-center shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <span className="text-5xl">🎤</span>
            <h3 className="mt-3 text-lg font-bold text-gray-800">还没录过你的声音哦</h3>
            <p className="mt-2 text-sm leading-relaxed text-gray-500">
              先用秘密数字进入，<br />再到「设置」里录一段声音，<br />下次就能喊着进来啦～
            </p>
            <button
              className="mt-5 w-full rounded-full bg-blue-500 py-3 font-medium text-white active:scale-[0.98] transition-all"
              onClick={() => setShowNoVpTip(false)}
            >
              知道啦
            </button>
          </div>
        </div>
      )}

      {/* 声纹识别覆盖层 */}
      {showVoiceOverlay && (
        <VoiceLoginOverlay mode="verify" onComplete={handleVoiceComplete} onCancel={handleVoiceCancel} />
      )}

      {/* 语音模型下载进度（底部微妙提示） */}
      <ModelDownloadProgress />
    </div>
  )
}

/* ===== PIN 码登录表单 ===== */
function PinLoginForm({ themeId, onLogin }) {
  const [name, setName] = useState('')
  const [pin, setPin] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [shakeKey, setShakeKey] = useState(0)

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
    </div>
  )
}

/* ===== 注册表单 ===== */
function RegisterForm({ themeId, onRegister }) {
  const [step, setStep] = useState('form') // form → set-pin → done
  const [form, setForm] = useState({ inviteCode: '', pseudonym: '', gender: '', age: '', guardianPhone: '' })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [voiceConsent, setVoiceConsent] = useState(true)
  const [pin, setPin] = useState('')
  const [pinConfirm, setPinConfirm] = useState('')
  const [pinStep, setPinStep] = useState('input')
  const [pinError, setPinError] = useState('')
  const [regUserId, setRegUserId] = useState(null) // 注册成功后的 userId（声纹采集用）
  const [hasVoiceprint, setHasVoiceprint] = useState(false) // 是否已完成声纹采集
  const [familyCode, setFamilyCode] = useState('') // 注册成功后展示
  const [showConfirm, setShowConfirm] = useState(false) // 注册信息二次确认
  const [vpMode, setVpMode] = useState<'local' | 'remote'>('local')

  // 获取声纹模式配置
  useEffect(() => {
    getVoiceprintConfig().then((cfg) => setVpMode(cfg.mode)).catch(() => {})
  }, [])

  const update = (key, value) => { setForm((f) => ({ ...f, [key]: value })); setError('') }

  // 第一步：表单校验通过后弹二次确认（确认后才调 API）
  const handleFormSubmit = (e) => {
    e.preventDefault()
    if (!form.inviteCode.trim() || !form.pseudonym.trim() || !form.age || !form.gender) {
      setError('请填写所有必填项'); return
    }
    const age = parseInt(form.age, 10)
    if (isNaN(age) || age < 6 || age > 120) { setError('请输入有效年龄（6-120）'); return }
    if (form.pseudonym.trim().length < 2 || form.pseudonym.trim().length > 12) {
      setError('昵称长度 2-12 字'); return
    }
    if (age < 14) {
      if (!form.guardianPhone.trim()) { setError('不满 14 周岁需填写家长手机号'); return }
      if (!/^1\d{10}$/.test(form.guardianPhone.trim())) { setError('请输入正确的 11 位手机号'); return }
    }
    setShowConfirm(true)
  }

  // 确认后真正提交注册（进入 PIN 设置；此时尚未写 PIN）
  const doRegister = async () => {
    setShowConfirm(false)
    const age = parseInt(form.age, 10)
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
      setRegUserId(data.userId)
      if (data.familyCode) setFamilyCode(data.familyCode)
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

  // 第二步：PIN 确认后，一次性调注册 API（含 PIN，后端原子写入）
  const handleSetPin = async () => {
    if (pinConfirm !== pin) {
      setPinError('两次输入不一致，请重新设置')
      setPin(''); setPinConfirm(''); setPinStep('input')
      return
    }
    setLoading(true); setPinError('')
    try {
      const { setPin: sp } = await import('../api')
      await sp(pin)
      // PIN 设置成功：若用户同意了声纹采集，进入声纹选择步骤
      if (voiceConsent && regUserId) {
        setStep('voice-choice')
      } else {
        setStep('done')
      }
    } catch (err) {
      setPinError(err.message || '注册失败，请检查邀请码')
      setStep('form') // 注册失败回到表单（可能是邀请码问题）
      setError(err.message || '注册失败，请检查邀请码')
    } finally {
      setLoading(false)
    }
  }

  const pinIndicator = themeId === 'ocean' ? 'pin-pearl' : themeId === 'garden' ? 'pin-jar' : 'pin-orb'

  // === 声纹采集选择（注册后、PIN 设置后的中间步） ===
  if (step === 'voice-choice') {
    return (
      <div className={`done-panel done-panel--${themeId}`}>
        <span className="emoji">🎤</span>
        <h2>要录入你的声音吗？</h2>
        <p style={{ margin: '12px 0', lineHeight: 1.6, fontSize: 14, opacity: 0.8 }}>
          录入后，下次登录时只要对波波说句话就能直接进入，<br />不用输秘密数字啦！
        </p>
        <p style={{ fontSize: 12, opacity: 0.5, marginBottom: 20 }}>
          🔒 声音只保存在这台设备上，不会上传到任何服务器
        </p>
        <button
          className={`btn-enter btn-enter--${themeId}`}
          onClick={() => setStep('voice-enroll')}
        >
          好呀，现在录入！🎤
        </button>
        <button
          className={`skip-pin skip-pin--${themeId}`}
          style={{ marginTop: 12 }}
          onClick={() => setStep('done')}
        >
          以后再说，先用秘密数字
        </button>
      </div>
    )
  }

  // === 声纹采集（引导对话） ===
  if (step === 'voice-enroll') {
    return (
      <VoiceLoginOverlay
        mode="enroll"
        onComplete={async (result) => {
          if (result.embeddings && result.embeddings.length > 0) {
            try {
              if (vpMode === 'remote') {
                // remote 模式：embedding 传服务端存储
                await remoteVoiceprintEnroll(result.embeddings)
              } else {
                // local 模式：存 IndexedDB + 签发设备凭证
                await enrollVoiceprint(regUserId, form.pseudonym.trim(), result.embeddings)
                try {
                  const cred = await issueVoiceCredential()
                  await saveVoiceCredential(regUserId, cred)
                } catch (e) {
                  console.warn('[声纹注册] 设备凭证签发失败（不影响本次注册）:', e)
                }
              }
              setHasVoiceprint(true)
            } catch (e) {
              console.warn('[声纹注册] 存储失败:', e)
            }
          }
          setStep('done')
        }}
        onCancel={() => setStep('done')}
      />
    )
  }

  // === 注册成功（含家庭码展示） ===
  if (step === 'done') {
    return (
      <div className={`done-panel done-panel--${themeId}`}>
        <span className="emoji">🎉</span>
        <h2>注册成功！</h2>
        <p>下次打开时用昵称和秘密数字就能登录啦</p>
        {familyCode && (
          <div style={{ margin: '16px 0', padding: '16px', borderRadius: 16, border: '2px dashed #86efac', background: '#f0fdf4' }}>
            <p style={{ fontSize: 12, color: '#6b7280', marginBottom: 6 }}>🏠 我的家庭码（告诉家长用于绑定）</p>
            <p style={{ fontSize: 28, fontWeight: 'bold', fontFamily: 'monospace', letterSpacing: '0.2em', color: '#16a34a' }}>{familyCode}</p>
          </div>
        )}
        {!hasVoiceprint && (
          <p style={{ fontSize: 12, opacity: 0.55, marginTop: 8 }}>
            💡 也可以在「设置」里录入声纹，用声音登录更方便
          </p>
        )}
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

        <button className={`skip-pin skip-pin--${themeId}`} onClick={async () => {
          // 跳过 PIN 设置：不调 setPin API，用户后续可在设置中补设
          if (voiceConsent && regUserId) setStep('voice-choice')
          else setStep('done')
        }}>
          先不设置，以后再说
        </button>
      </div>
    )
  }

  // === 注册表单 ===
  return (
    <form onSubmit={handleFormSubmit}>
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

      {/* 注册信息二次确认（防误触） */}
      <ConfirmDialog
        open={showConfirm}
        emoji="📝"
        title="确认要注册吗？"
        message="检查一下信息对不对哦"
        confirmText="没错，注册！"
        cancelText="再改改"
        onConfirm={doRegister}
        onCancel={() => setShowConfirm(false)}
      >
        <div className="mt-3 rounded-2xl bg-gray-50 p-3 text-left text-sm text-gray-600">
          <p>昵称：<strong>{form.pseudonym.trim()}</strong></p>
          <p>性别：{form.gender === 'male' ? '👦 男生' : '👧 女生'}</p>
          <p>年龄：{form.age} 岁</p>
          {form.guardianPhone.trim() && <p>家长手机：{form.guardianPhone.trim()}</p>}
        </div>
      </ConfirmDialog>
    </form>
  )
}

/** 本地模型下载进度（登录页底部，合并显示声纹 + 唤醒词模型） */
function ModelDownloadProgress() {
  const wake = useWakeModelStatus()
  const vp = useVoiceprintModelStatus()

  // 汇总两个模型的状态
  const items: { label: string; status: string; progress: number; error?: string }[] = []
  if (vp.status === 'loading' || vp.status === 'error') {
    items.push({ label: '声纹引擎', status: vp.status, progress: vp.progress, error: vp.error })
  }
  if (wake.status === 'loading' || wake.status === 'error') {
    items.push({ label: '语音引擎', status: wake.status, progress: wake.progress })
  }

  if (items.length === 0) return null

  const hasError = items.some((i) => i.status === 'error')
  const allLoading = items.filter((i) => i.status === 'loading')

  // 有失败：显示错误提示（含详细原因，方便诊断）
  if (hasError) {
    const failedItems = items.filter((i) => i.status === 'error')
    const failedNames = failedItems.map((i) => i.label).join('、')
    const detail = failedItems.find((i) => i.error)?.error || ''
    return (
      <div className="fixed bottom-4 left-1/2 -translate-x-1/2 z-40 px-4 py-1.5 rounded-full border text-xs font-medium bg-red-50 text-red-500 border-red-200 max-w-[90vw]">
        ⚠️ {failedNames}加载失败，不影响登录
        {detail && <span className="block text-[10px] text-red-400 mt-0.5 truncate">{detail}</span>}
      </div>
    )
  }

  // 正在下载：显示进度
  if (allLoading.length > 0) {
    const text = allLoading.length === 2
      ? `🎧 语音引擎准备中 ${Math.round((allLoading[0].progress + allLoading[1].progress) / 2) || 0}%`
      : `🎧 ${allLoading[0].label}准备中 ${allLoading[0].progress > 0 ? `${allLoading[0].progress}%` : '…'}`
    return (
      <div className="fixed bottom-4 left-1/2 -translate-x-1/2 z-40 px-4 py-1.5 rounded-full border text-xs font-medium bg-amber-50 text-amber-600 border-amber-200 animate-pulse">
        {text}
      </div>
    )
  }

  return null
}
