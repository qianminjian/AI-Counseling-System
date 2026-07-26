import { useState } from 'react'
import { ThemeProvider } from './theme/ThemeProvider'
import ConsentGate from './components/ConsentGate'
import TrialRegister from './components/TrialRegister'
import EmotionSelect from './components/EmotionSelect'
import ChatRoom from './components/ChatRoom'
import ParentReport from './components/ParentReport'
import { isAuthenticated, getUser, clearToken } from './api'

/**
 * 学生端认证流程：
 * 1. 未登录 → ConsentGate（告知同意）→ TrialRegister（试用注册）
 * 2. 已登录 → EmotionSelect → ChatRoom
 * 3. /parent?token=xxx → ParentReport（家长周报）
 */
export default function App() {
  // 家长周报路由（无需登录）
  if (window.location.pathname === '/parent') {
    return <ParentReport />
  }

  const [authed, setAuthed] = useState(() => isAuthenticated())
  const [consentVersion, setConsentVersion] = useState(null)
  const [session, setSession] = useState(null)
  const user = getUser()

  const handleAgree = (version) => {
    setConsentVersion(version)
  }

  const handleRegistered = () => {
    setAuthed(true)
  }

  const handleLogout = () => {
    clearToken()
    setAuthed(false)
    setSession(null)
    setConsentVersion(null)
  }

  return (
    <ThemeProvider>
      {!authed ? (
        consentVersion ? (
          <TrialRegister consentVersion={consentVersion} onRegistered={handleRegistered} />
        ) : (
          <ConsentGate onAgree={handleAgree} />
        )
      ) : !session ? (
        <EmotionSelect onStart={setSession} userName={user?.pseudonym} onLogout={handleLogout} />
      ) : (
        <ChatRoom session={session} onEnd={() => setSession(null)} />
      )}
    </ThemeProvider>
  )
}
