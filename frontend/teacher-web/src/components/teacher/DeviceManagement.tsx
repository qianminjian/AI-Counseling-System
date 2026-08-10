/**
 * CFG-006/007/008（doing/84 §四.5/§四.6）：无屏终端设备管理（toB 老师端）
 * - 设备列表（按绑定归属筛选，在线/离线状态）
 * - 绑定设备（扫码/输入设备码 → 验证码双因子）
 * - 声纹录入编排（发起 → 设备语音引导 → 3s 轮询进度 → 完成/重试）
 * 设计令牌：青屿 --ms-*（doing/75 方案 A），写操作二次确认对齐 83 规范
 */
import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Divider, Drawer, Form, Input, Modal, Select, Space, Steps, Table, Tag, message } from 'antd'
import { DesktopOutlined, ReloadOutlined, SoundOutlined } from '@ant-design/icons'
import {
  getDeviceList,
  createBindCode,
  bindDevice,
  createVoiceprintTask,
  getVoiceprintTask,
  type DeviceItem,
  type VoiceprintTask,
} from '../../api'

const POLL_INTERVAL_MS = 3000

/** 声纹任务阶段文案（对齐后端 DeviceVoiceprintService 阶段常量） */
const PHASE_LABEL: Record<string, string> = {
  INITIATED: '等待设备就绪',
  COLLECTING: '设备采集中（请学生朗读引导语）',
  UPLOADED: '声音已上传，正在处理…',
  COMPLETED: '录入完成',
  FAILED: '录入失败，可重试',
}

export default function DeviceManagement() {
  const [bindType, setBindType] = useState('CLASS')
  const [bindTargetId, setBindTargetId] = useState('')
  const [devices, setDevices] = useState<DeviceItem[]>([])
  const [loading, setLoading] = useState(false)
  // 绑定 Modal
  const [bindOpen, setBindOpen] = useState(false)
  const [bindDeviceCode, setBindDeviceCode] = useState('')
  const [bindCode, setBindCode] = useState('')
  const [generatedCode, setGeneratedCode] = useState('')
  const [binding, setBinding] = useState(false)
  // 详情 Drawer + 声纹录入
  const [detail, setDetail] = useState<DeviceItem | null>(null)
  const [vpOpen, setVpOpen] = useState(false)
  const [vpStudentId, setVpStudentId] = useState('')
  const [vpTask, setVpTask] = useState<VoiceprintTask | null>(null)
  const [vpPolling, setVpPolling] = useState(false)

  const loadDevices = useCallback(async () => {
    if (!bindTargetId.trim()) return
    setLoading(true)
    try {
      setDevices(await getDeviceList(bindType, bindTargetId.trim()))
    } catch {
      message.error('设备列表加载失败')
    } finally {
      setLoading(false)
    }
  }, [bindType, bindTargetId])

  useEffect(() => {
    void loadDevices()
  }, [loadDevices])

  /** 绑定：生成验证码 → 设备语音播报 → 人工输入 → 提交（AC-84-10/23） */
  const handleGenerateCode = async () => {
    if (!bindDeviceCode.trim()) {
      message.warning('请输入设备码（机身二维码下方文字）')
      return
    }
    try {
      const result = await createBindCode(bindDeviceCode.trim())
      setGeneratedCode(result.code)
      message.success(`验证码已生成：${result.code}（设备将语音播报）`)
    } catch {
      message.error('验证码生成失败，请确认设备在线且未绑定')
    }
  }

  const handleBind = async () => {
    if (!bindDeviceCode.trim() || !/^\d{6}$/.test(bindCode)) {
      message.warning('请填写设备码与 6 位验证码')
      return
    }
    setBinding(true)
    try {
      await bindDevice(bindDeviceCode.trim(), { bindType, bindTargetId: bindTargetId.trim(), code: bindCode })
      message.success('绑定成功')
      setBindOpen(false)
      setBindDeviceCode('')
      setBindCode('')
      setGeneratedCode('')
      void loadDevices()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '绑定失败')
    } finally {
      setBinding(false)
    }
  }

  /** 声纹录入：发起任务 → 3s 轮询（AC-84-13/14/15） */
  const startVoiceprint = async () => {
    if (!detail || !vpStudentId.trim()) {
      message.warning('请选择设备并填写学生 ID')
      return
    }
    setVpTask(null)
    setVpPolling(true)
    try {
      const task = await createVoiceprintTask(detail.deviceCode, vpStudentId.trim())
      setVpTask(task)
    } catch {
      message.error('声纹任务发起失败')
      setVpPolling(false)
    }
  }

  useEffect(() => {
    if (!vpPolling || !vpTask) return
    const check = async (): Promise<void> => {
      try {
        const task = await getVoiceprintTask(vpTask.deviceCode, vpTask.taskId)
        setVpTask(task)
        if (task.phase === 'COMPLETED' || task.phase === 'FAILED') {
          setVpPolling(false)
          if (task.phase === 'COMPLETED') message.success('声纹录入完成')
        }
      } catch {
        setVpPolling(false)
      }
    }
    void check()
    const timer = setInterval(() => {
      void check()
    }, POLL_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [vpPolling, vpTask])

  const columns = [
    { title: '设备码', dataIndex: 'deviceCode', key: 'deviceCode' },
    { title: '类型', dataIndex: 'deviceType', key: 'deviceType', width: 120 },
    { title: '固件', dataIndex: 'firmwareVersion', key: 'firmwareVersion', width: 110 },
    {
      title: '状态', dataIndex: 'online', key: 'online', width: 110,
      render: (online: boolean) => (online ? <Tag color='success'>在线</Tag> : <Tag>离线</Tag>),
    },
    {
      title: '操作', key: 'action', width: 140,
      render: (_: unknown, record: DeviceItem) => (
        <Button type='link' onClick={() => setDetail(record)}>查看/声纹</Button>
      ),
    },
  ]

  return (
    <Card
      title='终端设备管理'
      extra={
        <Space>
          <Button icon={<DesktopOutlined />} onClick={() => setBindOpen(true)}>绑定设备</Button>
          <Button icon={<ReloadOutlined />} onClick={() => void loadDevices()}>刷新</Button>
        </Space>
      }
    >
      {/* 归属筛选（老师租户级：班级/咨询室） */}
      <Space style={{ marginBottom: 16 }}>
        <Select
          value={bindType}
          style={{ width: 140 }}
          onChange={setBindType}
          options={[
            { value: 'CLASS', label: '班级' },
            { value: 'ROOM', label: '咨询室' },
            { value: 'SCHOOL', label: '学校' },
          ]}
        />
        <Input
          placeholder='归属 ID（学校/班级/咨询室）'
          value={bindTargetId}
          onChange={(e) => setBindTargetId(e.target.value)}
          style={{ width: 240 }}
        />
        <Button type='primary' onClick={() => void loadDevices()}>查询</Button>
      </Space>

      <Table
        rowKey='deviceCode'
        loading={loading}
        columns={columns}
        dataSource={devices}
        pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 台设备` }}
      />

      {/* 绑定 Modal（AC-84-10/11/23） */}
      <Modal
        title='绑定设备'
        open={bindOpen}
        onCancel={() => setBindOpen(false)}
        onOk={() => void handleBind()}
        confirmLoading={binding}
        okText='确认绑定'
      >
        <Form layout='vertical'>
          <Form.Item label='设备码（机身二维码下方）'>
            <Input value={bindDeviceCode} onChange={(e) => setBindDeviceCode(e.target.value)} placeholder='如 K7M2P9XW4AQ' />
          </Form.Item>
          <Form.Item label='归属类型'>
            <Select
              value={bindType}
              style={{ width: '100%' }}
              onChange={setBindType}
              options={[
                { value: 'CLASS', label: '班级' },
                { value: 'ROOM', label: '咨询室' },
              ]}
            />
          </Form.Item>
          <Form.Item label='归属 ID'>
            <Input value={bindTargetId} onChange={(e) => setBindTargetId(e.target.value)} />
          </Form.Item>
          <Form.Item label='绑定验证码（设备语音播报 6 位）'>
            <Space.Compact style={{ width: '100%' }}>
              <Input
                value={bindCode}
                maxLength={6}
                onChange={(e) => setBindCode(e.target.value)}
                placeholder='6 位验证码'
              />
              <Button onClick={() => void handleGenerateCode()}>获取验证码</Button>
            </Space.Compact>
          </Form.Item>
          {generatedCode && (
            <div style={{ color: 'var(--ms-primary)', fontSize: 13 }}>
              验证码 {generatedCode} 已生成，设备将语音播报
            </div>
          )}
        </Form>
      </Modal>

      {/* 详情 Drawer + 声纹录入（CFG-007/006，AC-84-13~17） */}
      <Drawer
        title={`设备 ${detail?.deviceCode ?? ''}`}
        width={420}
        open={!!detail}
        onClose={() => setDetail(null)}
      >
        {detail && (
          <>
            <p>
              状态：{detail.online ? <Tag color='success'>在线</Tag> : <Tag>离线</Tag>}
              固件：{detail.firmwareVersion ?? '-'}　类型：{detail.deviceType}
            </p>
            <p>最近在线：{detail.lastOnlineAt ? new Date(detail.lastOnlineAt).toLocaleString() : '-'}</p>
            <Divider />
            <Space direction='vertical' style={{ width: '100%' }}>
              <Input
                placeholder='学生 ID（声纹录入对象）'
                value={vpStudentId}
                onChange={(e) => setVpStudentId(e.target.value)}
              />
              <Button
                type='primary'
                icon={<SoundOutlined />}
                loading={vpPolling && !vpTask}
                onClick={() => void startVoiceprint()}
              >
                发起声纹录入
              </Button>
              {vpTask && (
                <>
                  <Steps
                    size='small'
                    current={phaseIndex(vpTask.phase)}
                    items={[
                      { title: '发起' },
                      { title: '采集' },
                      { title: '处理' },
                      { title: '完成' },
                    ]}
                  />
                  <p style={{ color: 'var(--ms-text-secondary)' }}>
                    {PHASE_LABEL[vpTask.phase] ?? vpTask.phase}
                    {vpPolling && '（轮询中…）'}
                  </p>
                  {vpTask.phase === 'FAILED' && (
                    <Button onClick={() => void startVoiceprint()}>重试（新建任务）</Button>
                  )}
                </>
              )}
            </Space>
          </>
        )}
      </Drawer>
    </Card>
  )
}

/** 阶段 → 步骤条索引（INITIATED=1, COLLECTING=1, UPLOADED=2, COMPLETED=3, FAILED=1） */
function phaseIndex(phase: string): number {
  if (phase === 'UPLOADED') return 2
  if (phase === 'COMPLETED') return 3
  return 1
}
