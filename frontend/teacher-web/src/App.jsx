import { useState } from 'react'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import { getToken, clearToken } from './api'

export default function App() {
  const [user, setUser] = useState(() => {
    const token = getToken()
    if (!token) return null
    try {
      const payload = JSON.parse(atob(token.split('.')[1]))
      return { userId: payload.sub, userType: payload.userType }
    } catch {
      return null
    }
  })

  const handleLogout = () => {
    clearToken()
    setUser(null)
  }

  return (
    <ConfigProvider locale={zhCN}>
      {user ? (
        <Dashboard user={user} onLogout={handleLogout} />
      ) : (
        <Login onLogin={setUser} />
      )}
    </ConfigProvider>
  )
}
