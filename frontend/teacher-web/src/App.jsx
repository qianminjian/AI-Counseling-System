import { useState } from 'react'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import ChangePassword from './pages/ChangePassword'
import { getToken, clearToken } from './api'

const MUST_CHANGE_KEY = 'mindsafe_must_change_password'

function getMustChange() {
  return localStorage.getItem(MUST_CHANGE_KEY) === 'true'
}
function setMustChange(val) {
  if (val) localStorage.setItem(MUST_CHANGE_KEY, 'true')
  else localStorage.removeItem(MUST_CHANGE_KEY)
}

export default function App() {
  const [user, setUser] = useState(() => {
    const token = getToken()
    if (!token) return null
    try {
      const payload = JSON.parse(atob(token.split('.')[1]))
      return {
        userId: payload.sub,
        userType: payload.userType,
        mustChangePassword: getMustChange(),
      }
    } catch {
      return null
    }
  })

  const handleLogin = (userData) => {
    // 持久化 mustChangePassword 标记（刷新页面后仍需强制改密）
    setMustChange(userData.mustChangePassword)
    setUser(userData)
  }

  const handleLogout = () => {
    clearToken()
    setMustChange(false)
    setUser(null)
  }

  const handlePasswordChanged = () => {
    setMustChange(false)
    setUser((u) => ({ ...u, mustChangePassword: false }))
  }

  return (
    <ConfigProvider locale={zhCN}>
      {!user ? (
        <Login onLogin={handleLogin} />
      ) : user.mustChangePassword ? (
        <ChangePassword
          userName={user.displayName}
          onChanged={handlePasswordChanged}
        />
      ) : (
        <Dashboard user={user} onLogout={handleLogout} />
      )}
    </ConfigProvider>
  )
}
