import { useEffect, useState, type ComponentType } from 'react'
import { ConfigProvider, theme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import LoginPage from './pages/LoginPage'
import OverviewPage from './pages/OverviewPage'
import ConfigPage from './pages/ConfigPage'
import RiskPage from './pages/RiskPage'
import PromptPage from './pages/PromptPage'
import SlaPage from './pages/SlaPage'
import LedgerPage from './pages/LedgerPage'
import DegradationPage from './pages/DegradationPage'
import KnowledgePage from './pages/KnowledgePage'
import ChannelPage from './pages/ChannelPage'
import InsightsPage from './pages/InsightsPage'
import UsagePage from './pages/UsagePage'
import CompliancePage from './pages/CompliancePage'
import MetricsPage from './pages/MetricsPage'
import AlertPage from './pages/AlertPage'
import DevicePage from './pages/DevicePage'
import ServicesPage from './pages/ServicesPage'
import AuditPage from './pages/AuditPage'
import ForbiddenPage from './pages/ForbiddenPage'
import AdminLayout, { allowedViews, type AdminView } from './components/AdminLayout'
import { adminLogout, getAdminName, getAdminRole, getAdminToken, UNAUTHORIZED_EVENT } from './api'

/** 平台管理端入口（ADMIN-P0-04：路由守卫 + state 路由 + 角色视图） */
/**
 * AD-009（2026-08-11）：视图→组件注册表（单一规则源，替代 17 分支三元链）。
 * 新页面只需：AdminView 类型 + 本表登记 + AdminLayout 菜单——三处集中，TS 穷尽检查
 * （Record 完整性由编译器保证，漏登记直接编译报错，不再静默渲染兜底页）。
 */
const VIEW_REGISTRY: Record<Exclude<AdminView, 'forbidden'>, ComponentType> = {
  overview: OverviewPage,
  config: ConfigPage,
  prompt: PromptPage,
  risk: RiskPage,
  sla: SlaPage,
  ledger: LedgerPage,
  degradation: DegradationPage,
  knowledge: KnowledgePage,
  channel: ChannelPage,
  insights: InsightsPage,
  usage: UsagePage,
  compliance: CompliancePage,
  metrics: MetricsPage,
  alerts: AlertPage,
  devices: DevicePage,
  services: ServicesPage,
  audit: AuditPage,
}

/** 注册表渲染器：forbidden 单独处理，未登记视图（TS 已穷尽）兜底 Overview */
function ViewRenderer({ view }: { view: AdminView }) {
  const Page = view === 'forbidden' ? ForbiddenPage : VIEW_REGISTRY[view]
  return <Page />
}

export default function App() {
  const [token, setToken] = useState<string | null>(getAdminToken)
  const [role, setRole] = useState<string>(() => getAdminRole() ?? '')
  const [name, setName] = useState<string>(getAdminName)
  const [view, setView] = useState<AdminView>('overview')

  // 青屿体系（doing/75 方案 A + doing/83 §8.1~8.9）：antd token 全局对齐，与 teacher-web 同名同值
  const themeConfig = {
    algorithm: theme.defaultAlgorithm,
    token: {
      colorPrimary: '#2BA8A0',
      colorSuccess: '#2E9E6B',
      colorWarning: '#D98E32',
      colorError: '#D9534F',
      colorTextBase: '#22303A',
      borderRadius: 12,
    },
    components: {
      // 深青 Sider + 激活项青绿软填充（与 teacher-web 工作台一致）
      Menu: {
        darkItemBg: '#163B38',
        darkSubMenuItemBg: '#163B38',
        darkItemSelectedBg: 'rgba(43, 168, 160, 0.28)',
        darkItemSelectedColor: '#FFFFFF',
      },
      Card: {
        borderRadiusLG: 16,
      },
      Table: {
        headerBg: '#F4F7F6',
      },
    },
  }

  // 401/403 登录态失效联动：回登录页（code-review M2）
  useEffect(() => {
    const handler = () => setToken(null)
    window.addEventListener(UNAUTHORIZED_EVENT, handler)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, handler)
  }, [])

  if (!token) {
    return (
      <ConfigProvider locale={zhCN} theme={themeConfig}>
        {/* 板块08 P2-1（2026-08-12）：登录成功后 platformLogin 已把真实 token 写入 sessionStorage，
            此处取真实 token 而非哨兵字面量（getAdminToken() ?? 'logged-in' 仅防御性兜底，正常路径取不到） */}
        <LoginPage onLogin={(r, n) => { setRole(r); setName(n); setToken(getAdminToken() ?? 'logged-in') }} />
      </ConfigProvider>
    )
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
    <ConfigProvider locale={zhCN} theme={themeConfig}>
      <AdminLayout role={role} name={name} view={currentView} onNavigate={setView} onLogout={handleLogout}>
        <ViewRenderer view={currentView} />
      </AdminLayout>
    </ConfigProvider>
  )
}
