import { useEffect, useState } from 'react'
import { Button, Card, message, Table, Tag } from 'antd'
import { fetchPromptVersions, getAdminName, promptAction, type PromptVersionItem } from '../api'

/** Prompt 管理（ADMIN-P1-02/03，M7：版本列表 + 提交审核/审核/激活，三重门禁提示） */
export default function PromptPage() {
  const [versions, setVersions] = useState<PromptVersionItem[]>([])
  const [loading, setLoading] = useState(false)

  const load = () => {
    setLoading(true)
    fetchPromptVersions('chat_default')
      .then(setVersions)
      .catch((e: Error) => message.error(e.message))
      .finally(() => setLoading(false))
  }

  useEffect(load, [])

  const act = async (path: string, body?: unknown, success?: string) => {
    try {
      await promptAction(path, body)
      message.success(success ?? '操作成功')
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  // reviewer 取当前登录账号（M7 签字留痕不可伪造，code-review M1）
  const reviewer = getAdminName() || 'admin'

  const statusColor: Record<string, string> = {
    draft: 'default', pending_review: 'orange', approved: 'blue', active: 'green', retired: 'default',
  }

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>Prompt 管理（chat_default 模板）</h2>
      <Card title="版本与审核流（draft→pending_review→approved→active；激活走红队+审校+eval 三重门禁）" style={{ borderRadius: 'var(--ms-radius-card)' }}>
        <Table<PromptVersionItem>
          rowKey="versionId"
          dataSource={versions}
          size="small"
          loading={loading}
          pagination={false}
          columns={[
            { title: '版本', dataIndex: 'version', width: 70 },
            { title: 'A/B', dataIndex: 'abGroup', width: 90 },
            {
              title: '状态',
              dataIndex: 'status',
              width: 130,
              render: (s?: string) => <Tag color={statusColor[s ?? '']}>{s ?? '-'}</Tag>,
            },
            { title: '生效', dataIndex: 'isActive', width: 70, render: (v: boolean) => (v ? '✅' : '') },
            { title: '说明', dataIndex: 'description', ellipsis: true },
            { title: '内容', dataIndex: 'contentLength', width: 80, render: (n: number) => `${n} 字符` },
            {
              title: '操作',
              width: 240,
              render: (_, r) => (
                <>
                  {r.status === 'draft' ? (
                    <Button size="small" onClick={() => act(`/versions/${r.versionId}/submit`, undefined, '已提交审核')}>
                      提交审核
                    </Button>
                  ) : null}
                  {r.status === 'pending_review' ? (
                    <Button
                      size="small"
                      onClick={() => act(`/versions/${r.versionId}/review`, { reviewer }, '审核通过')}
                    >
                      审核通过
                    </Button>
                  ) : null}
                  {r.status === 'approved' ? (
                    <Button
                      size="small"
                      type="primary"
                      onClick={() =>
                        act(`/versions/${r.versionId}/activate`, { reviewer }, '已激活（门禁通过）')
                      }
                    >
                      激活
                    </Button>
                  ) : null}
                </>
              ),
            },
          ]}
        />
      </Card>
    </div>
  )
}
