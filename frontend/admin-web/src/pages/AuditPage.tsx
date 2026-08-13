import { useEffect, useState } from 'react'
import { Card, Table, Tag, message } from 'antd'
import { fetchAuditLogs, type AuditLogItem } from '../api'

/** 审计日志（ADMIN-P0-07，M6：跨租户审计检索）
 * BUG-A-004（2026-08-11）：此前菜单点击渲染平台总览——本页补齐 */
export default function AuditPage() {
  const [logs, setLogs] = useState<AuditLogItem[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetchAuditLogs(200)
      .then(setLogs)
      .catch((e: Error) => message.error(e.message))
      .finally(() => setLoading(false))
  }, [])

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>审计日志</h2>
      <Card title="跨租户审计（平台角色：super/ops/audit 可查）" style={{ borderRadius: 'var(--ms-radius-card)' }}>
        <Table<AuditLogItem>
          rowKey="auditLogId"
          dataSource={logs}
          loading={loading}
          // A-14-01：loading 时空态文案改“加载中”
          locale={{ emptyText: loading ? '加载中...' : '暂无数据' }}
          size="small"
          pagination={{ pageSize: 20 }}
          columns={[
            { title: '时间', dataIndex: 'createdAt', width: 170, render: (v: string) => new Date(v).toLocaleString() },
            { title: '动作', dataIndex: 'action', width: 180, render: (v: string) => <Tag color="blue">{v}</Tag> },
            { title: '操作人', dataIndex: 'userId', width: 150, render: (v?: string) => (v ? String(v).slice(0, 8) : '—') },
            { title: '租户', dataIndex: 'tenantId', width: 150, render: (v?: string) => (v ? String(v).slice(0, 8) : '平台级') },
            { title: '详情', dataIndex: 'detail', ellipsis: true },
          ]}
        />
      </Card>
    </div>
  )
}
