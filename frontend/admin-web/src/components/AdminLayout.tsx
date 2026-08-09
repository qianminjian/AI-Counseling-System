import { Layout, Menu } from 'antd'

const { Sider, Header, Content } = Layout

export type AdminView = 'overview' | 'config' | 'risk' | 'services' | 'alerts' | 'audit' | 'forbidden'

interface MenuItem {
  key: AdminView
  label: string
}

/** 角色菜单映射（doing/83 §8.5/§13.1：四角色菜单差异，P0 仅总览/服务/告警/审计可用页） */
const ROLE_MENUS: Record<string, MenuItem[]> = {
  super_admin: [
    { key: 'overview', label: '平台总览' },
    { key: 'config', label: '配置注册表' },
    { key: 'risk', label: '风险全景' },
    { key: 'services', label: '服务状态' },
    { key: 'alerts', label: '告警中心' },
    { key: 'audit', label: '审计日志' },
  ],
  ops_admin: [
    { key: 'overview', label: '平台总览' },
    { key: 'config', label: '配置注册表' },
    { key: 'risk', label: '风险全景' },
    { key: 'services', label: '服务状态' },
    { key: 'alerts', label: '告警中心' },
  ],
  finance_admin: [{ key: 'overview', label: '平台总览' }],
  audit: [
    { key: 'overview', label: '平台总览' },
    { key: 'audit', label: '审计日志' },
  ],
}

/** 角色可访问视图集合（守卫用） */
export function allowedViews(role: string): Set<AdminView> {
  return new Set((ROLE_MENUS[role] ?? []).map((item) => item.key))
}

interface AdminLayoutProps {
  role: string
  name: string
  view: AdminView
  onNavigate: (view: AdminView) => void
  onLogout: () => void
  children: React.ReactNode
}

/** 管理端主布局（ADMIN-P0-04：侧边菜单按角色渲染 + 顶栏） */
export default function AdminLayout({ role, name, view, onNavigate, onLogout, children }: AdminLayoutProps) {
  const menus = ROLE_MENUS[role] ?? []
  const selected = menus.some((item) => item.key === view) ? view : 'overview'

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider theme="dark" width={200} style={{ background: 'var(--ms-sider-bg)' }}>
        <div style={{ padding: 16, color: '#fff', fontWeight: 700, fontSize: 15 }}>MindSafe 管理后台</div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selected]}
          style={{ background: 'var(--ms-sider-bg)' }}
          onClick={({ key }) => onNavigate(key as AdminView)}
          items={menus.map((item) => ({ key: item.key, label: item.label }))}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: 'var(--ms-card)',
            padding: '0 24px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            borderBottom: '1px solid var(--ms-border-soft)',
          }}
        >
          <span style={{ color: 'var(--ms-text-secondary)', fontSize: 13 }}>
            {name || role}（{role}）
          </span>
          <a onClick={onLogout} style={{ color: 'var(--ms-text-muted)' }}>
            退出登录
          </a>
        </Header>
        <Content style={{ padding: 24 }}>{children}</Content>
      </Layout>
    </Layout>
  )
}
