import { useEffect, useState } from 'react'
import { Button, Card, Form, Input, message, Modal, Select, Table, Tag } from 'antd'
import {
  cancelDegradationOverride,
  degradationOverride,
  fetchDegradationEvents,
  fetchDegradationMatrix,
  type DegradationEventItem,
  type DegradationRow,
} from '../api'

/** 降级矩阵（ADMIN-P2-01/02，M3：矩阵 + 手动切换（影响面提示 + reason + 二次确认）+ 事件时间线） */
export default function DegradationPage() {
  const [matrix, setMatrix] = useState<DegradationRow[]>([])
  const [events, setEvents] = useState<DegradationEventItem[]>([])
  const [editing, setEditing] = useState<DegradationRow | null>(null)
  const [form] = Form.useForm()

  const load = () => {
    fetchDegradationMatrix().then(setMatrix).catch((e: Error) => message.error(e.message))
    fetchDegradationEvents().then(setEvents).catch(() => setEvents([]))
  }

  useEffect(load, [])

  const handleCancel = async (point: string) => {
    try {
      await cancelDegradationOverride(point, '运维恢复默认档位')
      message.success(`已取消 ${point} 覆盖（回配置默认）`)
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '取消失败')
    }
  }

  const handleOverride = async (values: { to: string; reason: string }) => {
    if (!editing) return
    try {
      await degradationOverride(editing.point, values.to, values.reason)
      message.success(`已切换 ${editing.point} → ${values.to}（运行时生效，重启回落默认）`)
      setEditing(null)
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '切换失败')
    }
  }

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>降级矩阵</h2>
      <p style={{ color: 'var(--ms-text-muted)', fontSize: 13 }}>
        手动切换 = 意图登记 + 事件留痕 + 抑制自动事件（记录型切换）；运行时档位联动（tts/voice 读覆盖键）后续批次接通，当前不改变真实运行档位。
      </p>
      <Card title="能力降级矩阵（DEGRADED ≠ DOWN，黄色降级非红色故障）" style={{ borderRadius: 'var(--ms-radius-card)' }}>
        <Table<DegradationRow>
          rowKey="point"
          dataSource={matrix}
          size="small"
          pagination={false}
          columns={[
            { title: '降级点', dataIndex: 'point', width: 120 },
            { title: '当前档位', dataIndex: 'currentState', width: 160 },
            {
              title: '覆盖状态',
              dataIndex: 'overridden',
              width: 110,
              render: (v: boolean, r) =>
                v ? <Tag color="orange">已覆盖 → {r.overrideTo}</Tag> : <Tag color="green">默认</Tag>,
            },
            {
              title: '最近事件',
              render: (_, r) =>
                r.latestEvent
                  ? `${r.latestEvent.triggerType} ${r.latestEvent.from}→${r.latestEvent.to}（${String(r.latestEvent.occurredAt).slice(5, 16)}）`
                  : '-',
            },
            {
              title: '操作',
              width: 170,
              render: (_, record) => (
                <>
                  <Button
                    size="small"
                    onClick={() => {
                      setEditing(record)
                      form.setFieldsValue({ to: undefined, reason: '' })
                    }}
                  >
                    手动切换
                  </Button>
                  {record.overridden ? (
                    <Button size="small" danger style={{ marginLeft: 8 }} onClick={() => handleCancel(record.point)}>
                      取消覆盖
                    </Button>
                  ) : null}
                </>
              ),
            },
          ]}
        />
      </Card>

      <Card title={`降级事件时间线（${events.length}）`} style={{ marginTop: 16, borderRadius: 'var(--ms-radius-card)' }}>
        <Table<DegradationEventItem>
          rowKey={(r) => `${r.point}-${r.occurredAt}`}
          dataSource={events}
          size="small"
          pagination={false}
          columns={[
            { title: '点', dataIndex: 'point', width: 120 },
            { title: '从 → 到', render: (_, r) => `${r.fromState} → ${r.toState}` },
            {
              title: '触发',
              dataIndex: 'triggerType',
              width: 90,
              render: (t: string) => (t === 'manual' ? <Tag color="orange">manual</Tag> : <Tag>auto</Tag>),
            },
            { title: '操作人', dataIndex: 'operator', width: 100, render: (v: string) => v ?? '-' },
            { title: '时间', dataIndex: 'occurredAt', render: (v: string) => String(v).slice(0, 19) },
          ]}
        />
      </Card>

      <Modal
        title={`手动切换：${editing?.point ?? ''}`}
        open={!!editing}
        onCancel={() => setEditing(null)}
        onOk={() => form.submit()}
        okText="确认切换（二次确认）"
        // BUG-A-MODAL-01（2026-08-12，UI-TEST-015）：禁用动画——antd 6.5.4 + React 19.2 的
        // CSSMotion 关闭回调不触发导致弹窗卡死（ant-zoom-leave 循环），无动画路径直接卸载。
        transitionName=""
        maskTransitionName=""
      >
        <p style={{ color: 'var(--ms-warning)', fontSize: 13 }}>
          影响面提示：切换后该能力立即按目标档位运行，服务重启后回落配置默认；切换事件全量留痕（manual）。
        </p>
        <Form form={form} layout="vertical" onFinish={handleOverride}>
          <Form.Item name="to" label="切换目标" rules={[{ required: true, message: '请选择目标档位' }]}>
            <Select
              placeholder="选择目标档位"
              options={(editing?.availableStates ?? []).map((s) => ({ value: s, label: s }))}
            />
          </Form.Item>
          <Form.Item name="reason" label="切换原因" rules={[{ required: true, message: '切换原因必填（留痕）' }]}>
            <Input.TextArea placeholder="说明切换原因" rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
