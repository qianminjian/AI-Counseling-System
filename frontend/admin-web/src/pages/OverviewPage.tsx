import { useEffect, useState } from 'react'
import { Card, Descriptions, Spin } from 'antd'
import { fetchServicesStatus, type ServiceStatus } from '../api'

/** 平台运营总览（ADMIN-P0-04/05：服务拓扑只读 + 基础指标占位） */
export default function OverviewPage() {
  const [statuses, setStatuses] = useState<ServiceStatus | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchServicesStatus()
      .then(setStatuses)
      .catch((e: Error) => setError(e.message))
  }, [])

  const statusClass = (status: string) =>
    status === 'UP' ? 'status-up' : status === 'DEGRADED' ? 'status-degraded' : 'status-down'

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>平台运营总览</h2>
      <Card title="服务健康状态" style={{ borderRadius: 'var(--ms-radius-card)' }}>
        {error ? <span style={{ color: 'var(--ms-danger)' }}>{error}</span> : null}
        {!statuses && !error ? <Spin /> : null}
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
