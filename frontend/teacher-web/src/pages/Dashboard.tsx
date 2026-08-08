import { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import { Layout, Menu, Badge, message, notification } from 'antd'
import {
  BellOutlined, WarningOutlined, TeamOutlined, LogoutOutlined,
  DashboardOutlined, AlertOutlined, SettingOutlined,
} from '@ant-design/icons'
import { getUnreadCount } from '../api'
import { useAlertWebSocket } from '../hooks/useAlertWebSocket'
import { usePolling } from '../hooks/usePolling'
import OverviewPanel from '../components/teacher/OverviewPanel'
import AlertQueue from '../components/teacher/AlertQueue'
import StudentPanel from '../components/teacher/StudentPanel'
import NotificationPanel from '../components/teacher/NotificationPanel'
import AdminPanel from '../components/teacher/AdminPanel'
import PlatformPanel from '../components/teacher/PlatformPanel'
import QualityPanel from '../components/teacher/QualityPanel'
import OnboardingGuide from '../components/teacher/OnboardingGuide'

const { Header, Sider, Content } = Layout

const POLL_INTERVAL = 15000

function playAlertSound() {
  try {
    const ctx = new (window.AudioContext || (window as any).webkitAudioContext)()
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.connect(gain)
    gain.connect(ctx.destination)
    osc.frequency.value = 880
    osc.type = 'sine'
    gain.gain.setValueAtTime(0.3, ctx.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.5)
    osc.start(ctx.currentTime)
    osc.stop(ctx.currentTime + 0.5)
  } catch { /* silent */ }
}

function sendDesktopNotification(title, body) {
  // 桌面通知不可用或未授权时，降级为页内通知（不静默丢弃）
  if ('Notification' in window && Notification.permission === 'granted') {
    try {
      new Notification(title, { body, icon: '🛡️' })
      return
    } catch { /* 部分移动浏览器构造器不可用，落入页内通知 */ }
  }
  notification.warning({ message: title, description: body, placement: 'topRight', duration: 6 })
}

const MENU_ITEMS = [
  { key: 'overview', icon: <DashboardOutlined />, label: '工作台' },
  { key: 'alerts', icon: <AlertOutlined />, label: '预警队列' },
  { key: 'students', icon: <TeamOutlined />, label: '学生管理' },
  { key: 'quality', icon: <WarningOutlined />, label: '质量监控' },
  { key: 'notifications', icon: <BellOutlined />, label: '通知中心' },
]

const ADMIN_MENU_ITEMS = [
  { key: 'platform', icon: <DashboardOutlined />, label: '平台总览' },
  { key: 'admin', icon: <SettingOutlined />, label: '管理控制台' },
]

export default function Dashboard({ user, onLogout, darkMode, toggleDark }) {
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

  // WebSocket 实时预警推送（补充轮询，秒级触达）
  useAlertWebSocket({
    onAlert: () => {
      playAlertSound()
      pollUnread(true) // 刷新未读数
    },
  })

  // AUD-049：menuItems 依赖 unreadCount/isAdmin，用 useMemo 缓存避免每次渲染重建
  const menuItems = useMemo(() => [
    ...MENU_ITEMS.map((item) =>
      item.key === 'notifications'
        ? { ...item, icon: <Badge count={unreadCount} size="small"><BellOutlined /></Badge> }
        : item
    ),
    ...(isAdmin ? ADMIN_MENU_ITEMS : []),
  ], [unreadCount, isAdmin])

  const TITLES = {
    overview: '工作台',
    alerts: '预警队列',
    students: '学生管理',
    quality: '质量监控',
    notifications: '通知中心',
    platform: '平台总览',
    admin: '管理控制台',
  }

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
            {isMobile && '🛡️ '}{TITLES[tab]}
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
          {tab === 'overview' && <OverviewPanel onNavigate={setTab} />}
          {tab === 'alerts' && <AlertQueue />}
          {tab === 'students' && <StudentPanel />}
          {tab === 'quality' && <QualityPanel />}
          {tab === 'notifications' && <NotificationPanel />}
          {tab === 'platform' && isAdmin && <PlatformPanel />}
          {tab === 'admin' && isAdmin && <AdminPanel />}
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
