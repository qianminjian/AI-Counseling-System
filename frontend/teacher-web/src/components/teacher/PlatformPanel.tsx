import { useState, useEffect } from 'react'
import { Card, Row, Col, Statistic, Table, Tag, Spin, Button, Alert } from 'antd'
import type { TableProps } from 'antd'
import {
  BankOutlined, TeamOutlined, MessageOutlined, AlertOutlined,
  HomeOutlined, UserOutlined,
} from '@ant-design/icons'
import { getPlatformOverview, getPlatformTenants } from '../../api'

/** 租户列表（getPlatformTenants 契约） */
interface TenantVO {
  tenantName: string
  status: string
  tenantCode: string
  schoolCount: number
  studentCount: number
  teacherCount: number
  sessionCount: number
  createdAt: string
}

/** 平台管理后台面板（仅 ADMIN 可见） */
export default function PlatformPanel() {
  const [overview, setOverview] = useState(null)
  const [tenants, setTenants] = useState<TenantVO[]>([])
  const [loading, setLoading] = useState(true)
  // F-09：加载失败不静默——console.error + 局部错误条 + 重试（与 QualityPanel 同构）
  const [error, setError] = useState<string | null>(null)
  const [retryKey, setRetryKey] = useState(0)

  useEffect(() => {
    setLoading(true)
    Promise.all([getPlatformOverview(), getPlatformTenants()])
      .then(([ov, ts]) => { setOverview(ov); setTenants(ts); setError(null) })
      .catch((e) => {
        console.error('[PlatformPanel] 加载平台概览失败:', e)
        setError('平台数据加载失败，请检查网络后重试')
      })
      .finally(() => setLoading(false))
  }, [retryKey])

  if (loading) return <div className="ms-empty-lg"><Spin size="large" /></div>

  // F-09：加载失败展示错误条（不再渲染空面板，提供重试入口）
  if (error) {
    return (
      <Alert type="error" showIcon message={error}
        action={<Button size="small" onClick={() => setRetryKey(k => k + 1)}>重试</Button>} />
    )
  }

  const tenantColumns: TableProps<TenantVO>['columns'] = [
    { title: '学校/机构', dataIndex: 'tenantName', key: 'name', render: (t, r) => (
      <span><BankOutlined className="ms-text-primary" style={{ marginRight: 6 }} />{t}
        <Tag style={{ marginLeft: 8 }} color={r.status === 'active' ? 'green' : 'default'}>{r.status}</Tag>
      </span>
    )},
    { title: '编码', dataIndex: 'tenantCode', key: 'code', width: 120 },
    { title: '学校数', dataIndex: 'schoolCount', key: 'schools', width: 80, align: 'center' as const },
    { title: '学生数', dataIndex: 'studentCount', key: 'students', width: 80, align: 'center' as const },
    { title: '教师数', dataIndex: 'teacherCount', key: 'teachers', width: 80, align: 'center' as const },
    { title: '会话数', dataIndex: 'sessionCount', key: 'sessions', width: 80, align: 'center' as const },
    { title: '注册时间', dataIndex: 'createdAt', key: 'created', width: 120,
      render: v => v ? new Date(v).toLocaleDateString('zh-CN') : '-' },
  ]

  return (
    <div>
      {/* 平台总览指标 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={12} sm={8} md={4}>
          <Card size="small"><Statistic title="合作学校" value={overview?.tenantCount || 0}
            prefix={<BankOutlined />} valueStyle={{ color: 'var(--ms-primary)' }} /></Card>
        </Col>
        <Col xs={12} sm={8} md={4}>
          <Card size="small"><Statistic title="校区数" value={overview?.schoolCount || 0}
            prefix={<HomeOutlined />} /></Card>
        </Col>
        <Col xs={12} sm={8} md={4}>
          <Card size="small"><Statistic title="学生总数" value={overview?.studentCount || 0}
            prefix={<TeamOutlined />} valueStyle={{ color: 'var(--ms-success)' }} /></Card>
        </Col>
        <Col xs={12} sm={8} md={4}>
          <Card size="small"><Statistic title="教师总数" value={overview?.teacherCount || 0}
            prefix={<UserOutlined />} /></Card>
        </Col>
        <Col xs={12} sm={8} md={4}>
          <Card size="small"><Statistic title="累计会话" value={overview?.totalSessions || 0}
            prefix={<MessageOutlined />} valueStyle={{ color: 'var(--ms-primary)' }} /></Card>
        </Col>
        <Col xs={12} sm={8} md={4}>
          <Card size="small"><Statistic title="待处理预警" value={overview?.openAlerts || 0}
            prefix={<AlertOutlined />} valueStyle={{ color: overview?.openAlerts > 0 ? 'var(--ms-danger)' : 'var(--ms-success)' }} /></Card>
        </Col>
      </Row>

      {/* 租户列表 */}
      <Card title="合作学校 / 机构" size="small">
        <Table
          dataSource={tenants}
          columns={tenantColumns}
          rowKey="tenantId"
          size="small"
          pagination={false}
        />
      </Card>
    </div>
  )
}
