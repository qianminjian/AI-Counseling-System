import { useState } from 'react'
import { ConfigProvider, theme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import BigScreen from './pages/BigScreen'
import ChangePassword from './pages/ChangePassword'
import { getToken, clearToken } from './api'

const MUST_CHANGE_KEY = 'mindsafe_must_change_password'
const DARK_MODE_KEY = 'mindsafe_dark_mode'

function getMustChange() {
  return localStorage.getItem(MUST_CHANGE_KEY) === 'true'
}
function setMustChange(val) {
  if (val) localStorage.setItem(MUST_CHANGE_KEY, 'true')
  else localStorage.removeItem(MUST_CHANGE_KEY)
}

export default function App() {
  const [darkMode, setDarkMode] = useState(() => localStorage.getItem(DARK_MODE_KEY) === 'true')

  const toggleDark = () => {
    setDarkMode(prev => {
      const next = !prev
      localStorage.setItem(DARK_MODE_KEY, String(next))
      return next
    })
  }

  const themeConfig = {
    algorithm: darkMode ? theme.darkAlgorithm : theme.defaultAlgorithm,
  }

  const [user, setUser] = useState(() => {
    const token = getToken()
    if (!token) return null
    try {
      const payload = JSON.parse(atob(token.split('.')[1]))
      return {
        userId: payload.sub,
        userType: payload.userType,
        displayName: payload.displayName || '',
        mustChangePassword: getMustChange(),
      }
    } catch {
      return null
    }
  })

  // 数据大屏路由（全屏展示，需已登录）
  if (window.location.pathname === '/bigscreen' && getToken()) {
    return <ConfigProvider locale={zhCN} theme={{ algorithm: theme.darkAlgorithm }}><BigScreen /></ConfigProvider>
  }

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
    setUser((u) => u ? { ...u, mustChangePassword: false } : u)
  }

  return (
    <ConfigProvider locale={zhCN} theme={themeConfig}>
      {!user ? (
        <Login onLogin={handleLogin} />
      ) : user.mustChangePassword ? (
        <ChangePassword
          userName={user.displayName}
          onChanged={handlePasswordChanged}
        />
      ) : (
        <Dashboard user={user} onLogout={handleLogout} darkMode={darkMode} toggleDark={toggleDark} />
      )}
    </ConfigProvider>
  )
}
