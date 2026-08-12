import { useState, useEffect, useRef, useCallback, useMemo, type ComponentType, type ReactNode } from 'react'
import { Layout, Menu, Badge, message } from 'antd'
import {
  BellOutlined, WarningOutlined, TeamOutlined, LogoutOutlined,
  DashboardOutlined, AlertOutlined, SettingOutlined, DesktopOutlined,
} from '@ant-design/icons'
import { getUnreadCount } from '../api'
import { playAlertSound, sendDesktopNotification } from '../utils/notify'
import { useAlertWebSocket } from '../hooks/useAlertWebSocket'
import { usePolling } from '../hooks/usePolling'
import OverviewPanel from '../components/teacher/OverviewPanel'
import AlertQueue from '../components/teacher/AlertQueue'
import StudentPanel from '../components/teacher/StudentPanel'
import NotificationPanel from '../components/teacher/NotificationPanel'
import AdminPanel from '../components/teacher/AdminPanel'
import QualityPanel from '../components/teacher/QualityPanel'
import DeviceManagement from '../components/teacher/DeviceManagement'
import OnboardingGuide from '../components/teacher/OnboardingGuide'

const { Header, Sider, Content } = Layout

const POLL_INTERVAL = 15000

// 板块08 P1-3（2026-08-12，对齐 admin-web AD-009）：面板注册表——菜单 label / Header 标题 / 内容渲染
// 单一规则源。此前 MENU_ITEMS 与 TITLES 双表登记（key 各登记一次，漏改则标题空串），
// 现在菜单与标题均由本表派生；OverviewPanel 的 onNavigate 与 AdminPanel 的 isAdmin 守卫
// 属组件级特例参数，收敛在 renderPanel 内。新增面板只需：PanelKey 类型 + 本表登记。
type PanelKey = 'overview' | 'alerts' | 'students' | 'quality' | 'notifications' | 'devices' | 'admin'

const PANEL_REGISTRY: Record<PanelKey, { label: string; icon: ReactNode; Panel: ComponentType<any> }> = {
  overview: { label: '工作台', icon: <DashboardOutlined />, Panel: OverviewPanel },
  alerts: { label: '预警队列', icon: <AlertOutlined />, Panel: AlertQueue },
  students: { label: '学生管理', icon: <TeamOutlined />, Panel: StudentPanel },
  quality: { label: '质量监控', icon: <WarningOutlined />, Panel: QualityPanel },
  notifications: { label: '通知中心', icon: <BellOutlined />, Panel: NotificationPanel },
  // CFG-006/007/008（doing/84 §四.6）：无屏终端设备管理
  devices: { label: '终端设备', icon: <DesktopOutlined />, Panel: DeviceManagement },
  // P0 backlog ⑤ 双轨收敛：平台总览已迁 admin-web（平台角色域），业务 ADMIN 仅保留管理控制台
  admin: { label: '管理控制台', icon: <SettingOutlined />, Panel: AdminPanel },
}

export default function Dashboard({ user, onLogout, darkMode, toggleDark }: {
  user: { userType: string; displayName: string }
  onLogout: () => void
  darkMode: boolean
  toggleDark: () => void
}) {
  const [tab, setTab] = useState('overview')
  const [unreadCount, setUnreadCount] = useState(0)
  const prevUnreadRef = useRef(0)
  const isAdmin = user.userType === 'admin'
  const [isMobile, setIsMobile] = useState(window.innerWidth < 768)

  useEffect(() => {
    const onResize = () => setIsMobile(window.innerWidth < 768)
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])

  const pollUnread = useCallback(async (silent = true) => {
    try {
      const count = await getUnreadCount()
      setUnreadCount(count)
      if (count > prevUnreadRef.current && prevUnreadRef.current >= 0) {
        const newCount = count - prevUnreadRef.current
        playAlertSound()
        sendDesktopNotification('🛡️ MindSafe 新预警', `收到 ${newCount} 条新的风险预警通知。`)
      }
      prevUnreadRef.current = count
    } catch (e) {
      if (!silent) {
        const isNetwork = e.message?.includes('Failed to fetch') || e.message?.includes('NetworkError')
        message.warning(isNetwork ? '后端服务暂不可达，稍后自动重试' : '加载失败: ' + e.message)
      }
    }
  }, [])

  // 首次加载：带重试的静默探测，避免后端启动延迟导致立即报错
  useEffect(() => {
    if ('Notification' in window && Notification.permission === 'default') {
      Notification.requestPermission()
    }
    let cancelled = false
    const delays = [0, 2000, 5000] // 立即 → 2s → 5s 三次探测
    let attempt = 0
    const tryPoll = async () => {
      if (cancelled) return
      try {
        const count = await getUnreadCount()
        if (!cancelled) {
          setUnreadCount(count)
          prevUnreadRef.current = count
        }
      } catch {
        attempt++
        if (attempt < delays.length && !cancelled) {
          setTimeout(tryPoll, delays[attempt])
        } else if (!cancelled) {
          message.warning('后端服务暂不可达，请确认服务已启动')
        }
      }
    }
    tryPoll()
    return () => { cancelled = true }
  }, [])

  // 轮询收敛（F3）：15s 未读刷新；immediate=false 由上方 tryPoll 首次探测独立负责
  // AUD-047 页面不可见暂停由 usePolling 默认承担
  usePolling(() => pollUnread(true), POLL_INTERVAL, { immediate: false })

  // BUG-T-06-01（2026-08-12，UI-TEST-013）：通知中心标记已读后即时刷新未读徽标
  useEffect(() => {
    const handler = () => pollUnread(true)
    window.addEventListener('mindsafe:unread-changed', handler)
    return () => window.removeEventListener('mindsafe:unread-changed', handler)
  }, [pollUnread])

  // WebSocket 实时预警推送（补充轮询，秒级触达）
  useAlertWebSocket({
    onAlert: () => {
      playAlertSound()
      pollUnread(true) // 刷新未读数
    },
  })

  // AUD-049：menuItems 依赖 unreadCount/isAdmin，用 useMemo 缓存避免每次渲染重建
  // P1-3：从 PANEL_REGISTRY 派生（admin 面板按角色过滤；通知中心 icon 带未读徽标）
  const menuItems = useMemo(() =>
    (Object.keys(PANEL_REGISTRY) as PanelKey[])
      .filter((k) => k !== 'admin' || isAdmin)
      .map((k) => ({
        key: k,
        icon: k === 'notifications'
          ? <Badge count={unreadCount} size="small"><BellOutlined /></Badge>
          : PANEL_REGISTRY[k].icon,
        label: PANEL_REGISTRY[k].label,
      })),
    [unreadCount, isAdmin],
  )

  // 渲染当前面板（P1-3）：overview 需 onNavigate（工作台跳转），admin 需 isAdmin 守卫，
  // 其余面板从注册表取组件直接渲染；未登记 key 返回 null（防御）
  const renderPanel = useCallback((key: string) => {
    if (key === 'overview') return <OverviewPanel onNavigate={setTab} />
    if (key === 'admin') return isAdmin ? <AdminPanel /> : null
    const { Panel } = PANEL_REGISTRY[key as PanelKey] ?? { Panel: null }
    return Panel ? <Panel /> : null
  }, [isAdmin, setTab])

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <OnboardingGuide />
      {/* 桌面端侧边栏（doing/75 方案 A：深青 #163B38 + 激活项青绿软填充） */}
      {!isMobile && (
        <Sider theme="dark" width={220} style={{ background: 'var(--ms-sider-bg)' }}>
          <div style={{ padding: '20px 16px', textAlign: 'center' }}>
            {/* 品牌图形：青绿软底圆形容器（呼应登录页，克制原则） */}
            <div style={{
              width: 44, height: 44, margin: '0 auto',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              borderRadius: '50%', background: 'var(--ms-primary-soft)',
              boxShadow: 'var(--ms-shadow-tab)', fontSize: 22,
            }}>🛡️</div>
            <div style={{ fontWeight: 600, fontSize: 15, marginTop: 8, color: '#fff' }}>MindSafe</div>
            <div style={{ fontSize: 12, color: 'rgba(255,255,255,0.65)', marginTop: 2 }}>学生心理守护平台</div>
          </div>
          <Menu
            theme="dark"
            mode="inline"
            selectedKeys={[tab]}
            onClick={({ key }) => setTab(key)}
            items={menuItems}
          />
        </Sider>
      )}

      <Layout>
        <Header style={{
          background: '#fff', padding: isMobile ? '0 12px' : '0 24px', display: 'flex',
          alignItems: 'center', justifyContent: 'space-between',
          borderBottom: '1px solid var(--ms-border)', height: 56,
        }}>
          <span style={{ fontSize: 16, fontWeight: 600 }}>
            {isMobile && '🛡️ '}{PANEL_REGISTRY[tab as PanelKey]?.label ?? ''}
          </span>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span onClick={toggleDark} style={{ cursor: 'pointer', fontSize: 16 }} title={darkMode ? '切换亮色' : '切换暗色'}>
              {darkMode ? '☀️' : '🌙'}
            </span>
            {!isMobile && <span style={{ fontSize: 13, color: 'var(--ms-text-secondary)' }}>{user.displayName}</span>}
            <a onClick={onLogout} style={{ fontSize: 13, color: 'var(--ms-text-muted)' }}>
              <LogoutOutlined /> {isMobile ? '' : '退出'}
            </a>
          </div>
        </Header>

        <Content style={{
          padding: isMobile ? 12 : 24, background: 'var(--ms-bg)', overflow: 'auto',
          paddingBottom: isMobile ? 72 : 24, // 底部 Tab 栏留白
        }}>
          {renderPanel(tab)}
        </Content>
      </Layout>

      {/* 移动端底部 Tab 栏 */}
      {isMobile && (
        <div style={{
          position: 'fixed', bottom: 0, left: 0, right: 0, zIndex: 100,
          background: 'var(--ms-bg-elevated)', borderTop: '1px solid var(--ms-border)',
          display: 'flex', justifyContent: 'space-around', padding: '8px 0 env(safe-area-inset-bottom)',
        }}>
          {menuItems.map(item => (
            <button
              key={item.key}
              onClick={() => setTab(item.key)}
              style={{
                border: 'none', background: 'none', cursor: 'pointer',
                display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2,
                color: tab === item.key ? 'var(--ms-primary)' : 'var(--ms-text-muted)', fontSize: 11,
              }}
            >
              <span style={{ fontSize: 20 }}>{item.icon}</span>
              <span>{item.label}</span>
            </button>
          ))}
        </div>
      )}
    </Layout>
  )
}
