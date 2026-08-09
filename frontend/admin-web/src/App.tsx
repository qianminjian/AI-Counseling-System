import { useEffect, useState } from 'react'
import LoginPage from './pages/LoginPage'
import OverviewPage from './pages/OverviewPage'
import ConfigPage from './pages/ConfigPage'
import RiskPage from './pages/RiskPage'
import DegradationPage from './pages/DegradationPage'
import ForbiddenPage from './pages/ForbiddenPage'
import AdminLayout, { allowedViews, type AdminView } from './components/AdminLayout'
import { adminLogout, getAdminName, getAdminRole, getAdminToken, UNAUTHORIZED_EVENT } from './api'

/** 平台管理端入口（ADMIN-P0-04：路由守卫 + state 路由 + 角色视图） */
export default function App() {
  const [token, setToken] = useState<string | null>(getAdminToken)
  const [role, setRole] = useState<string>(() => getAdminRole() ?? '')
  const [name, setName] = useState<string>(getAdminName)
  const [view, setView] = useState<AdminView>('overview')

  // 401/403 登录态失效联动：回登录页（code-review M2）
  useEffect(() => {
    const handler = () => setToken(null)
    window.addEventListener(UNAUTHORIZED_EVENT, handler)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, handler)
  }, [])

  if (!token) {
    return <LoginPage onLogin={(r, n) => { setRole(r); setName(n); setToken('logged-in') }} />
  }

  // 路由守卫：角色无权访问 → 403（§13.1 前端菜单双保险之一）
  const allowed = allowedViews(role)
  const currentView: AdminView = allowed.has(view) ? view : 'forbidden'

  const handleLogout = () => {
    adminLogout()
    setToken(null)
    setRole('')
  }

  return (
    <AdminLayout role={role} name={name} view={currentView} onNavigate={setView} onLogout={handleLogout}>
      {currentView === 'forbidden' ? <ForbiddenPage /> : currentView === 'config' ? <ConfigPage /> : currentView === 'risk' ? <RiskPage /> : currentView === 'degradation' ? <DegradationPage /> : <OverviewPage />}
    </AdminLayout>
  )
}
