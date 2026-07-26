import { useState, useEffect, useCallback } from 'react'
import { Table, Tag, Button, Space, Card, message, Popconfirm, Modal, InputNumber, Typography, Upload, Divider, Alert } from 'antd'
import { PlusOutlined, CopyOutlined, StopOutlined, DeleteOutlined, UploadOutlined, InboxOutlined, DownloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { getInviteCodes, createInviteCode, deactivateInviteCode, deleteInviteCode, importStudentsCsv, getAuditLogs, downloadImportTemplate } from '../../api'

const { Text } = Typography

const STATUS_MAP = {
  active: { text: '有效', color: 'green' },
  disabled: { text: '已停用', color: 'default' },
  expired: { text: '已过期', color: 'orange' },
}

export default function AdminPanel() {
  const [codes, setCodes] = useState([])
  const [loading, setLoading] = useState(true)
  const [createModal, setCreateModal] = useState(false)
  const [maxUses, setMaxUses] = useState(10)
  const [expireDays, setExpireDays] = useState(30)
  const [creating, setCreating] = useState(false)
  const [importing, setImporting] = useState(false)
  const [importResult, setImportResult] = useState(null)
  const [auditLogs, setAuditLogs] = useState([])
  const [auditLoading, setAuditLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await getInviteCodes()
      setCodes(data)
    } catch (e) {
      message.error('加载邀请码失败: ' + e.message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const loadAuditLogs = useCallback(async () => {
    setAuditLoading(true)
    try {
      const data = await getAuditLogs()
      setAuditLogs(data || [])
    } catch { /* ignore */ }
    finally { setAuditLoading(false) }
  }, [])

  useEffect(() => { loadAuditLogs() }, [loadAuditLogs])

  const handleCreate = async () => {
    setCreating(true)
    try {
      const code = await createInviteCode(maxUses, expireDays)
      message.success(`邀请码已生成：${code.code}`)
      setCreateModal(false)
      load()
    } catch (e) {
      message.error(e.message)
    } finally {
      setCreating(false)
    }
  }

  const handleDeactivate = async (codeId) => {
    try {
      await deactivateInviteCode(codeId)
      message.success('已停用')
      load()
    } catch (e) {
      message.error(e.message)
    }
  }

  const handleDelete = async (codeId) => {
    try {
      await deleteInviteCode(codeId)
      message.success('已删除')
      load()
    } catch (e) {
      message.error(e.message)
    }
  }

  const handleImport = async (file) => {
    setImporting(true)
    setImportResult(null)
    try {
      const result = await importStudentsCsv(file)
      setImportResult(result)
      message.success(`导入完成：成功 ${result.created} 人，跳过 ${result.skipped} 人`)
    } catch (e) {
      message.error('导入失败: ' + e.message)
    } finally {
      setImporting(false)
    }
    return false // 阻止 antd 默认上传
  }

  const copyCode = (code) => {
    navigator.clipboard.writeText(code).then(() => message.success('已复制到剪贴板'))
  }

  /** 计算显示状态（active 但已过期 → expired） */
  const displayStatus = (record) => {
    if (record.status === 'active' && record.expiresAt && dayjs(record.expiresAt).isBefore(dayjs())) {
      return 'expired'
    }
    return record.status
  }

  const columns = [
    {
      title: '邀请码', dataIndex: 'code', width: 140,
      render: (v) => (
        <Space>
          <Text code strong>{v}</Text>
          <Button size="small" type="text" icon={<CopyOutlined />} onClick={() => copyCode(v)} />
        </Space>
      ),
    },
    {
      title: '使用情况', width: 120,
      render: (_, r) => `${r.usedCount ?? 0} / ${r.maxUses ?? '∞'}`,
    },
    {
      title: '状态', width: 90,
      render: (_, r) => {
        const s = STATUS_MAP[displayStatus(r)] || { text: r.status, color: 'default' }
        return <Tag color={s.color}>{s.text}</Tag>
      },
    },
    {
      title: '过期时间', dataIndex: 'expiresAt', width: 130,
      render: (v) => v ? dayjs(v).format('YYYY-MM-DD') : '永久',
    },
    {
      title: '创建时间', dataIndex: 'createdAt', width: 130,
      render: (v) => dayjs(v).format('YYYY-MM-DD'),
    },
    {
      title: '操作', width: 140, fixed: 'right',
      render: (_, record) => (
        <Space size="small">
          {record.status === 'active' && (
            <Popconfirm title="停用后该邀请码将无法注册，确认？" onConfirm={() => handleDeactivate(record.codeId)}>
              <Button size="small" icon={<StopOutlined />}>停用</Button>
            </Popconfirm>
          )}
          <Popconfirm title="删除后不可恢复，确认？" onConfirm={() => handleDelete(record.codeId)}>
            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <Card
      size="small"
      title="试用邀请码管理"
      extra={
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModal(true)}>
          生成邀请码
        </Button>
      }
    >
      <Table
        dataSource={codes}
        columns={columns}
        rowKey="codeId"
        loading={loading}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        scroll={{ x: 750 }}
        size="small"
      />

      <Modal
        title="生成邀请码"
        open={createModal}
        onOk={handleCreate}
        onCancel={() => setCreateModal(false)}
        confirmLoading={creating}
        okText="生成"
        cancelText="取消"
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16, padding: '12px 0' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span style={{ width: 100 }}>最大使用次数：</span>
            <InputNumber min={1} max={1000} value={maxUses} onChange={setMaxUses} style={{ width: 120 }} />
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span style={{ width: 100 }}>有效期（天）：</span>
            <InputNumber min={1} max={365} value={expireDays} onChange={setExpireDays} style={{ width: 120 }} />
          </div>
          <div style={{ fontSize: 12, color: '#999' }}>
            邀请码为 8 位大写字母+数字组合，学校教师使用邀请码完成试用注册。
          </div>
        </div>
      </Modal>

      <Divider />

      {/* 批量导入学生 */}
      <div style={{ marginTop: 8 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <h4 style={{ margin: 0 }}>批量导入学生</h4>
          <Button size="small" icon={<DownloadOutlined />} onClick={downloadImportTemplate}>下载模板</Button>
        </div>
        <Upload.Dragger
          accept=".csv"
          showUploadList={false}
          beforeUpload={handleImport}
          disabled={importing}
        >
          <p className="ant-upload-drag-icon"><InboxOutlined /></p>
          <p className="ant-upload-text">点击或拖拽 CSV 文件到此区域</p>
          <p className="ant-upload-hint">格式：昵称,年级,班级（每行一个学生，首行为表头可省略）</p>
        </Upload.Dragger>
        {importing && <div style={{ marginTop: 8, color: '#1890ff' }}>导入中...</div>}
        {importResult && (
          <Alert
            style={{ marginTop: 12 }}
            type={importResult.errors?.length > 0 ? 'warning' : 'success'}
            message={`成功创建 ${importResult.created} 人，跳过 ${importResult.skipped} 人`}
            description={importResult.errors?.length > 0 ? importResult.errors.join('；') : undefined}
            showIcon
          />
        )}
      </div>

      <Divider />

      {/* 审计日志 */}
      <div style={{ marginTop: 8 }}>
        <h4 style={{ marginBottom: 12 }}>操作审计日志</h4>
        <Table
          dataSource={auditLogs}
          rowKey="auditLogId"
          loading={auditLoading}
          size="small"
          pagination={{ pageSize: 10, showSizeChanger: false }}
          columns={[
            { title: '操作', dataIndex: 'action', width: 140, render: (v) => <Tag>{v}</Tag> },
            { title: '资源类型', dataIndex: 'resourceType', width: 100 },
            { title: '详情', dataIndex: 'detail', ellipsis: true },
            { title: '时间', dataIndex: 'createdAt', width: 150, render: (v) => v ? dayjs(v).format('MM-DD HH:mm') : '' },
          ]}
        />
      </div>
    </Card>
  )
}
