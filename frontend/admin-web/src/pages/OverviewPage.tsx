import { useEffect, useState } from 'react'
import { Card, Col, Descriptions, Row, Spin, Statistic, Table, Tag } from 'antd'
import {
  fetchServicesStatus,
  fetchPlatformOverview,
  fetchPlatformTenants,
  type ServiceStatus,
  type PlatformTenant,
} from '../api'

/**
 * 平台运营总览（ADMIN-P0-04/05 + P0 backlog ⑤ 双轨收敛）
 * - 平台总览指标（租户/学校/学生/教师/会话/预警，自 teacher-web PlatformPanel 迁入）
 * - 租户列表（跨租户，自 teacher-web PlatformPanel 迁入）
 * - 服务健康状态（P0-05 服务拓扑只读）
 */
export default function OverviewPage() {
  const [statuses, setStatuses] = useState<ServiceStatus | null>(null)
  const [overview, setOverview] = useState<Record<string, unknown> | null>(null)
  const [tenants, setTenants] = useState<PlatformTenant[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    Promise.all([fetchServicesStatus(), fetchPlatformOverview(), fetchPlatformTenants()])
      .then(([svc, ov, ts]) => {
        setStatuses(svc)
        setOverview(ov)
        setTenants(ts)
        setError(null)
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  const statusClass = (status: string) =>
    status === 'UP' ? 'status-up' : status === 'DEGRADED' ? 'status-degraded' : 'status-down'

  const metrics = [
    { key: 'tenantCount', label: '租户数' },
    { key: 'schoolCount', label: '学校数' },
    { key: 'studentCount', label: '学生数' },
    { key: 'teacherCount', label: '教师数' },
    { key: 'totalSessions', label: '累计会话' },
    { key: 'totalAlerts', label: '预警总数' },
    { key: 'openAlerts', label: '未处置预警' },
  ] as const

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>平台运营总览</h2>
      {loading ? <Spin /> : null}
      {error ? <span style={{ color: 'var(--ms-danger)' }}>{error}</span> : null}

      {/* 平台总览指标（自 teacher-web 迁入） */}
      {overview ? (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          {metrics.map((m) => (
            <Col span={3} key={m.key}>
              <Card style={{ borderRadius: 'var(--ms-radius-card)' }}>
                <Statistic title={m.label} value={Number(overview[m.key] ?? 0)} valueStyle={{ fontSize: 22 }} />
              </Card>
            </Col>
          ))}
        </Row>
      ) : null}

      {/* 租户列表（自 teacher-web 迁入） */}
      <Card title="租户列表" style={{ borderRadius: 'var(--ms-radius-card)', marginBottom: 16 }}>
        <Table
          rowKey='tenantCode'
          size='small'
          pagination={{ pageSize: 10 }}
          dataSource={tenants}
          columns={[
            { title: '租户', dataIndex: 'tenantName', key: 'tenantName' },
            { title: '编码', dataIndex: 'tenantCode', key: 'tenantCode', width: 120 },
            {
              title: '状态', dataIndex: 'status', key: 'status', width: 100,
              render: (s: string) => (s === 'ACTIVE' ? <Tag color='success'>启用</Tag> : <Tag>停用</Tag>),
            },
            { title: '学校', dataIndex: 'schoolCount', key: 'schoolCount', width: 80 },
            { title: '学生', dataIndex: 'studentCount', key: 'studentCount', width: 80 },
            { title: '教师', dataIndex: 'teacherCount', key: 'teacherCount', width: 80 },
            { title: '会话', dataIndex: 'sessionCount', key: 'sessionCount', width: 80 },
          ]}
        />
      </Card>

      {/* 服务健康状态（P0-05） */}
      <Card title="服务健康状态" style={{ borderRadius: 'var(--ms-radius-card)' }}>
        {statuses ? (
          <Descriptions column={3} size="small">
            {Object.entries(statuses).map(([service, status]) => (
              <Descriptions.Item key={service} label={service}>
                <span className={statusClass(status)}>{status}</span>
              </Descriptions.Item>
            ))}
          </Descriptions>
        ) : null}
      </Card>
    </div>
  )
}
