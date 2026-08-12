/**
 * M13 无屏终端设备管理（CFG-008，doing/84 §四.6 admin-web 部分）
 * 平台管理端（super_admin/ops_admin）跨租户设备视图：
 * - 设备列表（状态筛选 + 在线/离线 + 绑定归属摘要）
 * - 设备详情 Drawer（档案 + 绑定历史）
 * - 批量操作（固件升级 / 重启 / 恢复出厂，二次确认）
 * - 二维码批量签发（CSV 设备码 → 印刷包留痕）
 * 设计令牌：青屿 --ms-*（doing/75 方案 A）；危险操作二次确认（doing/83 §8.6）
 */
import { useCallback, useEffect, useState, type Key } from 'react'
import { Button, Card, Drawer, Input, message, Modal, Popconfirm, Select, Space, Table, Tag } from 'antd'
import { ReloadOutlined, QrcodeOutlined, ToolOutlined } from '@ant-design/icons'
import {
  fetchPlatformDevices,
  fetchPlatformDeviceDetail,
  exportDeviceQr,
  batchDeviceOperation,
  type PlatformDeviceItem,
} from '../api'

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'ONLINE_BOUND', label: '已绑定在线' },
  { value: 'ONLINE_UNBOUND', label: '待绑定' },
  { value: 'OFFLINE', label: '离线' },
  { value: 'UNACTIVATED', label: '未激活' },
]

const ACTION_LABEL: Record<string, string> = {
  ota: '固件升级',
  reboot: '远程重启',
  'factory-reset': '恢复出厂',
}

export default function DevicePage() {
  const [devices, setDevices] = useState<PlatformDeviceItem[]>([])
  const [status, setStatus] = useState('')
  const [loading, setLoading] = useState(false)
  // 详情
  const [detail, setDetail] = useState<Record<string, unknown> | null>(null)
  const [detailOpen, setDetailOpen] = useState(false)
  // 批量操作（rowSelection 选中，antd Key[] → 调用时转 string）
  const [selectedKeys, setSelectedKeys] = useState<Key[]>([])
  // 二维码签发
  const [qrOpen, setQrOpen] = useState(false)
  const [qrInput, setQrInput] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setDevices(await fetchPlatformDevices(status || undefined))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '设备列表加载失败')
    } finally {
      setLoading(false)
    }
  }, [status])

  useEffect(() => {
    void load()
  }, [load])

  const openDetail = async (deviceId: string) => {
    try {
      setDetail(await fetchPlatformDeviceDetail(deviceId))
      setDetailOpen(true)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '设备详情加载失败')
    }
  }

  const handleBatch = async (action: string) => {
    try {
      const result = await batchDeviceOperation(selectedKeys.map(String), action)
      message.success(`${ACTION_LABEL[action]}已受理 ${result.acceptedCount ?? 0} 台`)
      setSelectedKeys([])
      void load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '批量操作失败')
    }
  }

  const handleExportQr = async () => {
    const codes = qrInput.split(/[,\n\s]+/).map((s) => s.trim()).filter(Boolean)
    if (codes.length === 0) {
      message.warning('请输入设备码（逗号/换行分隔）')
      return
    }
    try {
      const result = await exportDeviceQr(codes)
      message.success(`已签发 ${result.issuedCount} 个二维码` + (result.notFound.length ? `；未找到：${result.notFound.join(', ')}` : ''))
      setQrOpen(false)
      setQrInput('')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '二维码签发失败')
    }
  }

  const columns = [
    { title: '设备码', dataIndex: 'deviceCode', key: 'deviceCode' },
    { title: '类型', dataIndex: 'deviceType', key: 'deviceType', width: 100 },
    { title: '固件', dataIndex: 'firmwareVersion', key: 'firmwareVersion', width: 100 },
    {
      title: '状态', dataIndex: 'online', key: 'online', width: 100,
      render: (online: boolean, record: PlatformDeviceItem) => (
        online ? <Tag color='success'>在线</Tag> : <Tag>{record.status === 'UNACTIVATED' ? '未激活' : '离线'}</Tag>
      ),
    },
    {
      title: '绑定归属', dataIndex: 'binding', key: 'binding',
      render: (binding: PlatformDeviceItem['binding']) => (
        binding ? `${binding.bindType} ${binding.bindTargetId?.slice(0, 8)}` : <Tag>未绑定</Tag>
      ),
    },
    {
      title: '操作', key: 'action', width: 120,
      render: (_: unknown, record: PlatformDeviceItem) => (
        <Button type='link' onClick={() => void openDetail(record.deviceId)}>详情</Button>
      ),
    },
  ]

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>终端设备管理</h2>
      <Card
        extra={
          // BUG-A-007：窄窗口下操作区横向溢出 → Space wrap 换行
          <Space wrap>
            <Select value={status} style={{ width: 140 }} onChange={setStatus}
              options={STATUS_OPTIONS} />
            <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
            <Button icon={<QrcodeOutlined />} onClick={() => setQrOpen(true)}>二维码签发</Button>
            <Popconfirm title='固件升级所选设备？' onConfirm={() => void handleBatch('ota')} disabled={!selectedKeys.length}>
              <Button icon={<ToolOutlined />} disabled={!selectedKeys.length}>批量升级</Button>
            </Popconfirm>
            <Popconfirm title='恢复出厂所选设备？（将解绑并重置）' onConfirm={() => void handleBatch('factory-reset')} disabled={!selectedKeys.length}>
              <Button danger disabled={!selectedKeys.length}>恢复出厂</Button>
            </Popconfirm>
          </Space>
        }
      >
        <Table
          rowKey='deviceId'
          loading={loading}
          columns={columns}
          dataSource={devices}
          rowSelection={{ selectedRowKeys: selectedKeys, onChange: setSelectedKeys }}
          pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 台设备` }}
        />
      </Card>

      {/* 设备详情（含绑定历史） */}
      <Drawer title={`设备 ${String(detail?.deviceCode ?? '')}`} width={460} open={detailOpen} onClose={() => setDetailOpen(false)}>
        {detail && (
          <>
            <p>SN：{String(detail.sn ?? '-')}　类型：{String(detail.deviceType ?? '-')}</p>
            <p>状态：{String(detail.status ?? '-')}　固件：{String(detail.firmwareVersion ?? '-')}</p>
            <p>服务器：{String(detail.serverUrl ?? '-')}</p>
            <p>最近在线：{detail.lastOnlineAt ? new Date(String(detail.lastOnlineAt)).toLocaleString() : '-'}</p>
            <h4 style={{ marginTop: 16 }}>绑定历史</h4>
            {(detail.bindings as Array<Record<string, unknown>> | undefined)?.length ? (
              (detail.bindings as Array<Record<string, unknown>>).map((b, i) => (
                <p key={i} style={{ fontSize: 13, color: 'var(--ms-text-secondary)' }}>
                  {String(b.bindType)} {String(b.bindTargetId)} · {String(b.boundBy ?? '-')} · {b.boundAt ? new Date(String(b.boundAt)).toLocaleString() : '-'}
                  {b.status === 'UNBOUND' && <Tag style={{ marginLeft: 8 }}>已解绑</Tag>}
                </p>
              ))
            ) : (
              <p style={{ color: 'var(--ms-text-muted)' }}>无绑定记录</p>
            )}
          </>
        )}
      </Drawer>

      {/* 二维码批量签发 */}
      <Modal
        title='二维码批量签发'
        open={qrOpen}
        onCancel={() => setQrOpen(false)}
        onOk={() => void handleExportQr()}
        okText='签发'
        // BUG-A-MODAL-01（2026-08-12，UI-TEST-015）：禁用动画——antd 6.5.4 + React 19.2 的
        // CSSMotion 关闭回调不触发导致弹窗卡死（ant-zoom-leave 循环），无动画路径直接卸载。
        transitionName=''
        maskTransitionName=''
      >
        <p style={{ color: 'var(--ms-text-secondary)', fontSize: 13 }}>
          输入设备码（逗号/换行/空格分隔），将生成二维码印刷包并留痕（device_qr_issuance）。
        </p>
        <Input.TextArea
          rows={5}
          placeholder={'K7M2P9XW4AQ\nA1B2C3D4E5F'}
          value={qrInput}
          onChange={(e) => setQrInput(e.target.value)}
        />
      </Modal>
    </div>
  )
}
