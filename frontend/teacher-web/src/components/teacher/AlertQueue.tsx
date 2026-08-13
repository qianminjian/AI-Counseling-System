import { useState, useEffect, useCallback } from 'react'
import { Table, Tag, Button, Space, Select, Card, message, Popconfirm, Modal, Input } from 'antd'
import type { TableProps } from 'antd'
import { CheckOutlined, StopOutlined, UserOutlined, CheckCircleOutlined, DownloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { getAlerts, claimAlert, markFalsePositive, resolveAlert, exportAlertsCsv, getAlertTemplates, type AlertTemplate } from '../../api'
import type { AlertStatus, AlertVO } from '../../api'
import { evaluateSla } from '../../utils/sla'
import { riskColor, riskLabel } from '../../utils/riskLevel'

const STATUS_MAP: Record<string, { text: string; color: string }> = {
  open: { text: '待处理', color: 'red' },
  // claimed 由 render 特判为青屿主色软底（antd preset 无对应色）
  resolved: { text: '已解决', color: 'green' },
  false_positive: { text: '误报', color: 'default' },
}

export default function AlertQueue() {
  const [alerts, setAlerts] = useState<AlertVO[]>([])
  const [loading, setLoading] = useState(true)
  const [statusFilter, setStatusFilter] = useState<AlertStatus | undefined>(undefined)
  const [levelFilter, setLevelFilter] = useState<number | undefined>(undefined)
  const [resolveModal, setResolveModal] = useState<{ open: boolean; alertId: string | null }>({ open: false, alertId: null })
  const [resolveNote, setResolveNote] = useState('')
  const [templates, setTemplates] = useState<AlertTemplate[]>([])
  const [resolving, setResolving] = useState(false)

  // BUG-T-03-02：处理弹窗加载干预话术模板（后端 /teacher/templates，7 条预审核话术）
  useEffect(() => {
    getAlertTemplates().then(setTemplates).catch(() => setTemplates([]))
  }, [])

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

  const handleClaim = async (id: string) => {
    try {
      await claimAlert(id)
      message.success('已认领')
      load()
    } catch (e) {
      message.error(e.message)
    }
  }

  const handleFalsePositive = async (id: string) => {
    try {
      await markFalsePositive(id)
      message.success('已标记为误报')
      load()
    } catch (e) {
      message.error(e.message)
    }
  }

  const handleResolve = async () => {
    if (!resolveModal.alertId) return
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

  const columns: TableProps<AlertVO>['columns'] = [
    {
      title: '时间', dataIndex: 'detectedAt', width: 120,
      render: (v) => dayjs(v).format('MM-DD HH:mm'),
      sorter: (a, b) => new Date(a.detectedAt).getTime() - new Date(b.detectedAt).getTime(),
      defaultSortOrder: 'descend' as const,
    },
    {
      title: '等级', dataIndex: 'riskLevel', width: 80,
      render: (v) => <Tag color={riskColor(v)}>{riskLabel(v)}</Tag>,
      sorter: (a, b) => a.riskLevel - b.riskLevel,
    },
    {
      title: 'SLA', width: 110,
      render: (_, r) => {
        const sla = evaluateSla(r.riskLevel, r.status, r.detectedAt)
        if (!sla.hasSla) return <span className="ms-hint">无时限</span>
        if (sla.breached) {
          return <Tag color="red" className="ms-tag-strong">逾期 {sla.overdueMin}min</Tag>
        }
        if (sla.remainingMin > 0) {
          // 紧急（≤5min）保持语义橙；非紧急用青屿主色软底（替换 antd 默认蓝）
          const urgent = sla.remainingMin <= 5
          return (
            <Tag
              color={urgent ? 'orange' : undefined}
              className={urgent ? 'ms-m-0' : 'ms-tag-claim'}
            >
              剩 {sla.remainingMin}min
            </Tag>
          )
        }
        return <span className="ms-hint">已关闭</span>
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
        // 已认领：中性状态用青屿主色软底（替换 antd 默认蓝）
        if (v === 'claimed') {
          return <Tag className="ms-tag-claim">已认领</Tag>
        }
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
      <div className="ms-mb-16" style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
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
        <p style={{ marginBottom: 8, color: 'var(--ms-text-secondary)' }}>
          请记录线下干预措施（可选，将存入学生档案）：
        </p>
        {templates.length > 0 && (
          <Select
            style={{ width: '100%', marginBottom: 8 }}
            placeholder="选择干预话术模板"
            options={templates.map((t) => ({ value: t.id, label: `[${t.category}] ${t.content.slice(0, 24)}…` }))}
            onChange={(id: string) => {
              const t = templates.find((x) => x.id === id)
              if (t) setResolveNote(t.content)
            }}
            allowClear
          />
        )}
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
