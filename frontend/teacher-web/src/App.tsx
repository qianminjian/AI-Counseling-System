import { useEffect, useState } from 'react'
import { ConfigProvider, theme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import BigScreen from './pages/BigScreen'
import ChangePassword from './pages/ChangePassword'
import { getToken, clearToken } from './api'
import { defaultLandingFor, type LandingPage } from './utils/landing'
// F-07：失败安全读写（隐私模式/禁用存储下不抛 SecurityError）
import { readLocalStorageSafe, writeLocalStorageSafe, removeLocalStorageSafe } from './utils/storage'
// F-08：JWT 解码兼容 UTF-8 + base64url（裸 atob 会丢中文 displayName / 静默丢登录态）
import { decodeJwtPayload } from './utils/jwt'

const MUST_CHANGE_KEY = 'mindsafe_must_change_password'
const DARK_MODE_KEY = 'mindsafe_dark_mode'

function getMustChange() {
  return readLocalStorageSafe(MUST_CHANGE_KEY, 'false') === 'true'
}
function setMustChange(val: boolean) {
  if (val) writeLocalStorageSafe(MUST_CHANGE_KEY, 'true')
  else removeLocalStorageSafe(MUST_CHANGE_KEY)
}

// L2（CodeReview）：初始渲染前同步 <html data-theme>——useEffect 首帧后写入太晚，
// 深色偏好用户刷新会先渲染浅色再闪成深色（FOUC）；useState 初始化阶段即写 dataset，首帧即正确
function getInitialDarkMode(): boolean {
  const dark = readLocalStorageSafe(DARK_MODE_KEY, 'false') === 'true'
  document.documentElement.dataset.theme = dark ? 'dark' : 'light'
  return dark
}

export default function App() {
  const [darkMode, setDarkMode] = useState<boolean>(getInitialDarkMode)

  // F-01：暗色模式同步 <html data-theme>（--ms-* token 覆盖层生效面；切换时与初始同步双保险）
  useEffect(() => {
    document.documentElement.dataset.theme = darkMode ? 'dark' : 'light'
  }, [darkMode])

  const toggleDark = () => {
    setDarkMode(prev => {
      const next = !prev
      writeLocalStorageSafe(DARK_MODE_KEY, String(next))
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
    const payload = decodeJwtPayload(token)
    if (!payload) return null
    return {
      userId: payload.sub,
      userType: String(payload.userType ?? ''),
      displayName: String(payload.displayName ?? ''),
      mustChangePassword: getMustChange(),
    }
  })

  // 落地页差异化路由（design/35 §3.1）：管理者默认大屏，教师默认工作台
  const [view, setView] = useState<LandingPage>(() => defaultLandingFor(user?.userType))

  // 数据大屏路由（全屏展示，需已登录）
  if (window.location.pathname === '/bigscreen' && getToken()) {
    return <ConfigProvider locale={zhCN} theme={{ algorithm: theme.darkAlgorithm }}><BigScreen /></ConfigProvider>
  }

  const handleLogin = (userData: {
    userId: string
    userType: string
    displayName: string
    mustChangePassword: boolean
  }) => {
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
