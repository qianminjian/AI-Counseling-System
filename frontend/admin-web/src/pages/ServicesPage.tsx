import { useEffect, useState } from 'react'
import { Card, Tag, message } from 'antd'
import { fetchServicesStatus } from '../api'

/** 服务状态（ADMIN-P0-05，M2：六服务实时健康，青屿 §8.3 语义色）
 * BUG-A-003（2026-08-11）：此前菜单点击渲染平台总览——本页补齐 */
export default function ServicesPage() {
  const [status, setStatus] = useState<Record<string, string> | null>(null)

  useEffect(() => {
    fetchServicesStatus()
      .then(setStatus)
      .catch((e: Error) => message.error(e.message))
  }, [])

  const tag = (s: string) =>
    s === 'UP' ? <Tag color="green">UP</Tag> : s === 'DEGRADED' ? <Tag color="orange">DEGRADED</Tag> : <Tag color="red">{s}</Tag>

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>服务状态</h2>
      <Card title="六服务实时健康（service-manager 语义：UP/DEGRADED/DOWN）" style={{ borderRadius: 'var(--ms-radius-card)' }}>
        {status ? (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}>
            {Object.entries(status).map(([service, s]) => (
              <div
                key={service}
                style={{
                  padding: '12px 20px',
                  background: 'var(--ms-bg-elevated)',
                  borderRadius: 'var(--ms-radius-control)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                }}
              >
                <span style={{ fontWeight: 600 }}>{service}</span>
                {tag(s)}
              </div>
            ))}
          </div>
        ) : null}
      </Card>
    </div>
  )
}
