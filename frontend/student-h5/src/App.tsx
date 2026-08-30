import { useState } from 'react'
import { ThemeProvider } from './theme/ThemeProvider'
import ConsentGate from './components/ConsentGate'
import LoginPage from './components/LoginPage'
import WelcomeGuide from './components/WelcomeGuide'
import EmotionSelect from './components/EmotionSelect'
import ChatRoom from './components/ChatRoom'
import GuardianConsentGate from './components/GuardianConsentGate'
import IdleWarning from './components/IdleWarning'
import { useIdleLogout } from './hooks/useIdleLogout'
import { isAuthenticated, getUser, clearToken, isConsentDone, markConsentDone } from './api'

/**
 * 学生端认证流程（共享 Pad 适配）：
 * 1. 无 token → LoginPage（登录/注册双 tab）
 *    - 注册时：若设备未完成告知同意 → 先弹 ConsentGate
 *    - 登录时：不弹 ConsentGate（设备级标记）
 * 2. 有 token（sessionStorage）→ EmotionSelect → ChatRoom
 *    - 创建会话被 CONSENT_REQUIRED 拦截 → GuardianConsentGate 验证码闭环（AUTH-040）
 * 3. 关闭 tab/浏览器 → sessionStorage 清除 → 下次必须重新登录
 * 4. 家长周报由 parent-h5 独立部署（nginx /parent），不在本应用内路由
 */
export default function App() {
  const [authed, setAuthed] = useState(() => isAuthenticated())
  const [showConsent, setShowConsent] = useState(false)
  const [showGuardianGate, setShowGuardianGate] = useState(false)
  const [session, setSession] = useState(null)
  const [loginTab, setLoginTab] = useState('login')

  const handleLogout = () => {
    clearToken()
    setAuthed(false)
    setSession(null)
    setShowGuardianGate(false)
  }

  // 无操作超时自动退出（共享 Pad 隐私保护）：5 分钟无操作 → 60 秒倒计时 → 回登录页
  const idle = useIdleLogout({ enabled: authed, onTimeout: handleLogout })

  const user = getUser()

  const handleLogin = () => {
    setAuthed(true)
  }

  const handleRegister = () => {
    markConsentDone()
    setAuthed(true)
  }

  // 注册前检查设备级告知同意
  const handleNeedConsent = () => {
    if (!isConsentDone()) {
      setLoginTab('register') // 记住目标 tab，同意完成后回到注册页
      setShowConsent(true)
    }
  }

  return (
    <ThemeProvider>
      {showConsent ? (
        <ConsentGate onAgree={() => { markConsentDone(); setShowConsent(false) }} />
      ) : !authed ? (
        <LoginPage onLogin={handleLogin} onRegister={handleRegister} onNeedConsent={handleNeedConsent} initialTab={loginTab} />
      ) : showGuardianGate ? (
        <GuardianConsentGate onSuccess={() => setShowGuardianGate(false)} />
      ) : !session ? (
        <>
          <WelcomeGuide />
          <EmotionSelect onStart={setSession} userName={user?.pseudonym} onLogout={handleLogout}
            onConsentRequired={() => setShowGuardianGate(true)} />
        </>
      ) : (
        <ChatRoom session={session} onEnd={() => setSession(null)} onSwitchUser={handleLogout} markActivity={idle.markActivity} />
      )}
      {/* 无操作超时警告卡 */}
      {authed && idle.warning && <IdleWarning secondsLeft={idle.secondsLeft} onStay={idle.stay} />}
    </ThemeProvider>
  )
}
