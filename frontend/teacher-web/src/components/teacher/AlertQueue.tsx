import { useState, useEffect, useCallback } from 'react'
import { Table, Tag, Button, Space, Select, Card, message, Popconfirm, Modal, Input } from 'antd'
import { CheckOutlined, StopOutlined, UserOutlined, CheckCircleOutlined, DownloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { getAlerts, claimAlert, markFalsePositive, resolveAlert, exportAlertsCsv } from '../../api'
import { evaluateSla } from '../../utils/sla'

const RISK_COLORS = { 3: 'red', 2: 'orange', 1: 'gold', 0: 'default' }
const RISK_LABELS = { 3: '红色', 2: '橙色', 1: '黄色', 0: '绿色' }
const STATUS_MAP = {
  open: { text: '待处理', color: 'red' },
  claimed: { text: '已认领', color: 'blue' },
  resolved: { text: '已解决', color: 'green' },
  false_positive: { text: '误报', color: 'default' },
}

export default function AlertQueue() {
  const [alerts, setAlerts] = useState([])
  const [loading, setLoading] = useState(true)
  const [statusFilter, setStatusFilter] = useState(undefined)
  const [levelFilter, setLevelFilter] = useState(undefined)
  const [resolveModal, setResolveModal] = useState({ open: false, alertId: null })
  const [resolveNote, setResolveNote] = useState('')
  const [resolving, setResolving] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await getAlerts({ status: statusFilter, minLevel: levelFilter, limit: 100 })
      setAlerts(data)
    } catch (e) {
      message.error('加载预警失败: ' + e.message)
    } finally {
      setLoading(false)
    }
  }, [statusFilter, levelFilter])

  useEffect(() => { load() }, [load])

  const handleClaim = async (id) => {
    try {
      await claimAlert(id)
      message.success('已认领')
      load()
    } catch (e) {
      message.error(e.message)
    }
  }

  const handleFalsePositive = async (id) => {
    try {
      await markFalsePositive(id)
      message.success('已标记为误报')
      load()
    } catch (e) {
      message.error(e.message)
    }
  }

  const handleResolve = async () => {
    setResolving(true)
    try {
      await resolveAlert(resolveModal.alertId, resolveNote.trim() || undefined)
      message.success('预警已处理完成')
      setResolveModal({ open: false, alertId: null })
      setResolveNote('')
      load()
    } catch (e) {
      message.error(e.message)
    } finally {
      setResolving(false)
    }
  }

  const columns = [
    {
      title: '时间', dataIndex: 'detectedAt', width: 120,
      render: (v) => dayjs(v).format('MM-DD HH:mm'),
      sorter: (a, b) => new Date(a.detectedAt).getTime() - new Date(b.detectedAt).getTime(),
      defaultSortOrder: 'descend' as const,
    },
    {
      title: '等级', dataIndex: 'riskLevel', width: 80,
      render: (v) => <Tag color={RISK_COLORS[v]}>{RISK_LABELS[v]}</Tag>,
      sorter: (a, b) => a.riskLevel - b.riskLevel,
    },
    {
      title: 'SLA', width: 110,
      render: (_, r) => {
        const sla = evaluateSla(r.riskLevel, r.status, r.detectedAt)
        if (!sla.hasSla) return <span style={{ fontSize: 12, color: '#999' }}>无时限</span>
        if (sla.breached) {
          return <Tag color="red" style={{ margin: 0, fontWeight: 600 }}>逾期 {sla.overdueMin}min</Tag>
        }
        if (sla.remainingMin > 0) {
          return <Tag color={sla.remainingMin <= 5 ? 'orange' : 'blue'} style={{ margin: 0 }}>剩 {sla.remainingMin}min</Tag>
        }
        return <span style={{ fontSize: 12, color: '#999' }}>已关闭</span>
      },
      sorter: (a, b) => {
        const wa = evaluateSla(a.riskLevel, a.status, a.detectedAt)
        const wb = evaluateSla(b.riskLevel, b.status, b.detectedAt)
        const ka = wa.breached ? -wa.overdueMin : (wa.hasSla ? wa.remainingMin : 9999)
        const kb = wb.breached ? -wb.overdueMin : (wb.hasSla ? wb.remainingMin : 9999)
        return ka - kb
      },
    },
    { title: '学生', dataIndex: 'studentName', width: 100 },
    { title: '类型', dataIndex: 'riskType', width: 120 },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: (v) => {
        const s = STATUS_MAP[v] || { text: v, color: 'default' }
        return <Tag color={s.color}>{s.text}</Tag>
      },
    },
    {
      title: '操作', width: 220, fixed: 'right' as const,
      render: (_, record) => (
        <Space size="small">
          {record.status === 'open' && (
            <Button size="small" icon={<UserOutlined />} onClick={() => handleClaim(record.alertId)}>
              认领
            </Button>
          )}
          {(record.status === 'open' || record.status === 'claimed') && (
            <Button
              size="small"
              type="primary"
              ghost
              icon={<CheckCircleOutlined />}
              onClick={() => setResolveModal({ open: true, alertId: record.alertId })}
            >
              处理
            </Button>
          )}
          {(record.status === 'open' || record.status === 'claimed') && (
            <Popconfirm title="确认标记为误报？" onConfirm={() => handleFalsePositive(record.alertId)}>
              <Button size="small" danger icon={<StopOutlined />}>误报</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ]

  return (
    <Card size="small">
      {/* 筛选栏 */}
      <div style={{ marginBottom: 16, display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        <Select
          placeholder="状态筛选"
          allowClear
          style={{ width: 130 }}
          value={statusFilter}
          onChange={setStatusFilter}
          options={[
            { value: 'open', label: '待处理' },
            { value: 'claimed', label: '已认领' },
            { value: 'resolved', label: '已解决' },
            { value: 'false_positive', label: '误报' },
          ]}
        />
        <Select
          placeholder="最低等级"
          allowClear
          style={{ width: 130 }}
          value={levelFilter}
          onChange={setLevelFilter}
          options={[
            { value: 1, label: '黄色及以上' },
            { value: 2, label: '橙色及以上' },
            { value: 3, label: '仅红色' },
          ]}
        />
        <Button icon={<DownloadOutlined />} onClick={exportAlertsCsv} title="导出 CSV">
          导出
        </Button>
      </div>

      <Table
        dataSource={alerts}
        columns={columns}
        rowKey="alertId"
        loading={loading}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        scroll={{ x: 760 }}
        size="small"
      />

      {/* 处理完成弹窗 */}
      <Modal
        title="预警处理完成"
        open={resolveModal.open}
        onOk={handleResolve}
        onCancel={() => { setResolveModal({ open: false, alertId: null }); setResolveNote('') }}
        confirmLoading={resolving}
        okText="确认处理"
        cancelText="取消"
      >
        <p style={{ marginBottom: 8, color: '#666' }}>
          请记录线下干预措施（可选，将存入学生档案）：
        </p>
        <Input.TextArea
          value={resolveNote}
          onChange={(e) => setResolveNote(e.target.value)}
          placeholder="例如：已与学生谈话，通知家长，安排心理老师跟进..."
          autoSize={{ minRows: 3, maxRows: 6 }}
          maxLength={500}
          showCount
        />
      </Modal>
    </Card>
  )
}
