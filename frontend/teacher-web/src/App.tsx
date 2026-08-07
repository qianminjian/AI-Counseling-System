import { useState } from 'react'
import { ConfigProvider, theme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import BigScreen from './pages/BigScreen'
import ChangePassword from './pages/ChangePassword'
import { getToken, clearToken } from './api'
import { defaultLandingFor, type LandingPage } from './utils/landing'

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

  // doing/75 方案 A 青屿：两端统一品牌色（antd token 全局生效）
  const themeConfig = {
    algorithm: darkMode ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: {
      colorPrimary: '#2BA8A0',
      colorSuccess: '#2E9E6B',
      colorWarning: '#D98E32',
      colorError: '#D9534F',
      borderRadius: 8,
    },
    components: {
      // 工作台深青 Sider + 激活项青绿软填充（doing/75 方案 A）
      Menu: {
        darkItemBg: '#163B38',
        darkSubMenuItemBg: '#163B38',
        darkItemSelectedBg: 'rgba(43, 168, 160, 0.28)',
        darkItemSelectedColor: '#FFFFFF',
        darkItemColor: 'rgba(255, 255, 255, 0.75)',
        darkItemHoverColor: '#FFFFFF',
        darkItemHoverBg: 'rgba(255, 255, 255, 0.08)',
      },
      Layout: { siderBg: '#163B38' },
      // 卡片：大圆角 + 极浅青绿阴影（doing/75 方案 A 组件规格）
      Card: {
        borderRadiusLG: 16,
        boxShadowTertiary: '0 4px 16px rgba(43, 168, 160, 0.08)',
      },
    },
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

  // 落地页差异化路由（design/35 §3.1）：管理者默认大屏，教师默认工作台
  const [view, setView] = useState<LandingPage>(() => defaultLandingFor(user?.userType))

  // 数据大屏路由（全屏展示，需已登录）
  if (window.location.pathname === '/bigscreen' && getToken()) {
    return <ConfigProvider locale={zhCN} theme={{ algorithm: theme.darkAlgorithm }}><BigScreen /></ConfigProvider>
  }

  const handleLogin = (userData) => {
    // 持久化 mustChangePassword 标记（刷新页面后仍需强制改密）
    setMustChange(userData.mustChangePassword)
    setUser(userData)
    setView(defaultLandingFor(userData.userType))
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
      ) : view === 'bigscreen' ? (
        <div style={{ background: '#0a1628' }}>
          <BigScreen onExit={() => setView('dashboard')} />
        </div>
      ) : (
        <Dashboard user={user} onLogout={handleLogout} darkMode={darkMode} toggleDark={toggleDark} />
      )}
    </ConfigProvider>
  )
}
