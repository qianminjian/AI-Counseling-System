import { useEffect, useState } from 'react'
import { Button, Card, Form, Input, message, Modal, Select, Table, Tag, List, Typography, Space } from 'antd'
import { fetchConfigs, updateConfig, fetchConfigHistory, type SysConfigItem, type ConfigHistoryItem } from '../api'

const { Text } = Typography

/** 配置注册表（ADMIN-P1-01，M1：分域浏览 + SECRET 掩码 + HOT 修改 + reason 必填；BUG-A-03-01：+ 变更历史） */
export default function ConfigPage() {
  const [configs, setConfigs] = useState<SysConfigItem[]>([])
  const [domain, setDomain] = useState<string | undefined>(undefined)
  const [editing, setEditing] = useState<SysConfigItem | null>(null)
  // BUG-A-03-01：变更历史弹窗状态
  const [historyKey, setHistoryKey] = useState<string | null>(null)
  const [history, setHistory] = useState<ConfigHistoryItem[]>([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [form] = Form.useForm()

  const load = () => {
    fetchConfigs(domain)
      .then(setConfigs)
      .catch((e: Error) => message.error(e.message))
  }

  useEffect(load, [domain])

  // BUG-A-03-01：打开变更历史（审计留痕：时间/操作人/变更前后/原因）
  const openHistory = async (key: string) => {
    setHistoryKey(key)
    setHistoryLoading(true)
    try {
      setHistory(await fetchConfigHistory(key))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '历史加载失败')
      setHistory([])
    } finally {
      setHistoryLoading(false)
    }
  }

  const handleUpdate = async (values: { value: string; reason: string }) => {
    if (!editing) return
    try {
      await updateConfig(editing.configKey, values.value, values.reason)
      message.success('配置已更新（HOT 即时生效）')
      setEditing(null)
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '修改失败')
    }
  }

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>配置注册表</h2>
      <Select
        allowClear
        placeholder="按配置域筛选"
        style={{ width: 200, marginBottom: 16 }}
        onChange={(v) => setDomain(v ?? undefined)}
        options={['system', 'security', 'voice', 'chat', 'alert', 'commercial'].map((d) => ({ value: d, label: d }))}
      />
      <Card style={{ borderRadius: 'var(--ms-radius-card)' }}>
        <Table<SysConfigItem>
          rowKey="configKey"
          dataSource={configs}
          pagination={false}
          size="small"
          columns={[
            { title: '配置键', dataIndex: 'configKey' },
            { title: '域', dataIndex: 'domain', width: 100 },
            {
              title: '值',
              dataIndex: 'value',
              width: 200,
              render: (v: string, record) =>
                record.sensitive === 'SECRET' ? <Tag color="orange">***已配置***</Tag> : v,
            },
            {
              title: '生效方式',
              dataIndex: 'effectMode',
              width: 100,
              render: (mode: string) => (mode === 'HOT' ? <Tag color="green">HOT</Tag> : <Tag>RESTART</Tag>),
            },
            { title: '说明', dataIndex: 'description', ellipsis: true },
            {
              title: '操作',
              width: 150,
              render: (_, record) => (
                <Space>
                  {record.effectMode === 'HOT' ? (
                    <Button
                      size="small"
                      onClick={() => {
                        setEditing(record)
                        // SECRET 值不回读（H3）：不预填，placeholder 提示输入新值
                        form.setFieldsValue({ value: '', reason: '' })
                      }}
                    >
                      修改
                    </Button>
                  ) : (
                    <Tag>只读</Tag>
                  )}
                  {/* BUG-A-03-01：变更历史入口（留痕审计） */}
                  <Button size="small" onClick={() => openHistory(record.configKey)}>历史</Button>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title={`修改配置：${editing?.configKey ?? ''}`}
        open={!!editing}
        onCancel={() => setEditing(null)}
        onOk={() => form.submit()}
        okText="确认修改"
        // BUG-A-MODAL-01（2026-08-12，UI-TEST-015）：禁用动画——antd 6.5.4 + React 19.2 的
        // CSSMotion 关闭回调不触发导致弹窗卡死（ant-zoom-leave 循环），无动画路径直接卸载。
        transitionName=""
        maskTransitionName=""
      >
        <Form form={form} layout="vertical" onFinish={handleUpdate}>
          <Form.Item name="value" label="新值" rules={[{ required: true, message: '请输入新值' }]}>
            <Input placeholder={editing?.sensitive === 'SECRET' ? '输入新密钥值' : '新值'} />
          </Form.Item>
          <Form.Item name="reason" label="变更原因" rules={[{ required: true, message: '变更原因必填（留痕）' }]}>
            <Input.TextArea placeholder="说明本次变更原因" rows={2} />
          </Form.Item>
        </Form>
      </Modal>

      {/* BUG-A-03-01：变更历史弹窗（禁用动画同 BUG-A-MODAL-01 处理） */}
      <Modal
        title={`变更历史：${historyKey ?? ''}`}
        open={historyKey !== null}
        onCancel={() => setHistoryKey(null)}
        footer={null}
        transitionName=""
        maskTransitionName=""
      >
        <List
          loading={historyLoading}
          dataSource={history}
          locale={{ emptyText: '暂无变更记录' }}
          renderItem={(h) => (
            <List.Item>
              <div style={{ width: '100%' }}>
                <Space size={8} wrap>
                  <Text type="secondary" style={{ fontSize: 12 }}>{String(h.createdAt).slice(0, 19)}</Text>
                  {h.operator && <Tag>{h.operator}</Tag>}
                </Space>
                <br />
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {String(h.valueBefore ?? '').slice(0, 40) || '（空）'} → <Text strong>{String(h.valueAfter ?? '').slice(0, 40) || '（空）'}</Text>
                </Text>
                {h.reason && (
                  <div style={{ fontSize: 12, color: 'var(--ms-text-secondary)' }}>原因：{h.reason}</div>
                )}
              </div>
            </List.Item>
          )}
        />
      </Modal>
    </div>
  )
}
