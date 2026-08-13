import { useEffect, useRef, useState } from 'react'
import { Button, Card, Input, Modal, Select, Table, Tag, message } from 'antd'
import { ackAlertEvent, fetchAlertEvents, type AlertEventItem } from '../api'
import { getAdminRole } from '../api'

/** 告警中心（ADMIN-P1-08/09，M2：alert_events 落库台账 + ack 确认，仅 ops/super 可操作） */
export default function AlertPage() {
  const [alerts, setAlerts] = useState<AlertEventItem[]>([])
  const [status, setStatus] = useState<string | undefined>(undefined)
  const [loading, setLoading] = useState(true)
  const [ackTarget, setAckTarget] = useState<AlertEventItem | null>(null)
  const [reason, setReason] = useState('')
  const canAck = getAdminRole() === 'super_admin' || getAdminRole() === 'ops_admin'
  // FE-008：请求序号防竞态（旧响应不得覆盖新列表）
  const loadSeqRef = useRef(0)

  const load = (st?: string) => {
    // FE-008（doing/95）：竞态防护——状态筛选快速切换时旧响应不得覆盖新列表
    const seq = ++loadSeqRef.current
    setLoading(true)
    fetchAlertEvents(st)
      .then((data) => {
        if (seq !== loadSeqRef.current) return
        setAlerts(data)
      })
      .catch((e: Error) => {
        if (seq !== loadSeqRef.current) return
        message.error(e.message)
      })
      .finally(() => {
        if (seq === loadSeqRef.current) setLoading(false)
      })
  }

  useEffect(() => {
    load()
  }, [])

  const doAck = () => {
    if (!ackTarget || !reason.trim()) {
      message.warning('确认原因必填')
      return
    }
    ackAlertEvent(ackTarget.eventId, reason.trim())
      .then(() => {
        message.success('告警已确认')
        setAckTarget(null)
        setReason('')
        load(status)
      })
      .catch((e: Error) => message.error(e.message))
  }

  const severityTag = (s: string) =>
    s === 'CRITICAL' ? <Tag color="red">CRITICAL</Tag> : s === 'WARNING' ? <Tag color="orange">WARNING</Tag> : <Tag color="blue">{s}</Tag>

  const statusTag = (s: string) => {
    const colors: Record<string, string> = { firing: 'red', resolved: 'green', ack: 'blue', closed: 'default' }
    return <Tag color={colors[s] ?? 'default'}>{s}</Tag>
  }

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>告警中心</h2>
      <Card
        title={
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span>告警事件台账（alert_events 落库，聚合 AlertManager + 业务告警）</span>
            <Select
              style={{ width: 140 }}
              size="small"
              placeholder="状态筛选"
              allowClear
              value={status}
              onChange={(v) => {
                setStatus(v)
                load(v)
              }}
              options={[
                { value: 'firing', label: 'firing（触发中）' },
                { value: 'resolved', label: 'resolved（已恢复）' },
                { value: 'ack', label: 'ack（已确认）' },
                { value: 'closed', label: 'closed（已关闭）' },
              ]}
            />
          </div>
        }
        style={{ borderRadius: 'var(--ms-radius-card)' }}
      >
        <Table<AlertEventItem>
          rowKey="eventId"
          dataSource={alerts}
          loading={loading}
          // A-14-01：loading 时空态文案改“加载中”（antd Table loading 时仍渲染 emptyText）
          locale={{ emptyText: loading ? '加载中...' : '暂无数据' }}
          size="small"
          columns={[
            { title: '规则', dataIndex: 'ruleName', ellipsis: true, width: 220 },
            { title: '级别', dataIndex: 'severity', width: 100, render: severityTag },
            { title: '状态', dataIndex: 'status', width: 100, render: statusTag },
            {
              title: '推送',
              dataIndex: 'notifyStatus',
              width: 90,
              render: (v?: string) => (v ? <Tag color={v === 'SUCCESS' ? 'green' : v === 'FAILED' ? 'red' : 'default'}>{v}</Tag> : '—'),
            },
            { title: '来源', dataIndex: 'source', width: 110 },
            { title: '摘要', dataIndex: 'summary', ellipsis: true },
            { title: '触发时间', dataIndex: 'firedAt', width: 170, render: (v: string) => new Date(v).toLocaleString() },
            {
              title: '操作',
              width: 90,
              render: (_, record) =>
                canAck && record.status === 'firing' ? (
                  <Button size="small" type="primary" ghost onClick={() => setAckTarget(record)}>
                    确认
                  </Button>
                ) : (
                  <span style={{ color: 'var(--ms-text-muted)' }}>—</span>
                ),
            },
          ]}
        />
      </Card>
      <Modal
        open={ackTarget !== null}
        title="确认告警（firing → ack）"
        onOk={doAck}
        onCancel={() => {
          setAckTarget(null)
          setReason('')
        }}
        okText="确认"
        cancelText="取消"
        okButtonProps={{ disabled: !reason.trim() }}
        // BUG-A-MODAL-01（2026-08-12，UI-TEST-015）：禁用动画——antd 6.5.4 + React 19.2 的
        // CSSMotion 关闭回调不触发导致弹窗卡死（ant-zoom-leave 循环），无动画路径直接卸载。
        transitionName=""
        maskTransitionName=""
      >
        <p style={{ color: 'var(--ms-text-secondary)' }}>规则：{ackTarget?.ruleName}（{ackTarget?.summary}）</p>
        <Input.TextArea
          placeholder="确认原因（必填，审计留痕）"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          rows={3}
        />
      </Modal>
    </div>
  )
}
