import { useEffect, useState } from 'react'
import { Button, Card, Input, message, Modal, Table, Tag } from 'antd'
import { fetchPromptVersions, getAdminName, postAdmin, promptAction, type PromptVersionItem } from '../api'
import { ENDPOINTS } from '../api/endpoints'

/** Prompt 管理（ADMIN-P1-02/03，M7：版本列表 + 创建/提交审核/审核/激活，三重门禁提示） */
export default function PromptPage() {
  const [versions, setVersions] = useState<PromptVersionItem[]>([])
  const [loading, setLoading] = useState(false)
  // A-04-01（2026-08-13 遍历）：补创建版本入口（后端 POST /versions 已存在，前端缺失）
  const [createOpen, setCreateOpen] = useState(false)
  const [createContent, setCreateContent] = useState('')
  const [createDesc, setCreateDesc] = useState('')
  const [creating, setCreating] = useState(false)

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

  const handleCreate = async () => {
    if (!createContent.trim()) {
      message.error('Prompt 内容必填')
      return
    }
    setCreating(true)
    try {
      await postAdmin(ENDPOINTS.promptVersions.path, {
        templateKey: 'chat_default',
        content: createContent,
        description: createDesc || undefined,
      })
      message.success('版本已创建（draft）')
      setCreateOpen(false)
      setCreateContent('')
      setCreateDesc('')
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败')
    } finally {
      setCreating(false)
    }
  }

  const statusColor: Record<string, string> = {
    draft: 'default', pending_review: 'orange', approved: 'blue', active: 'green', retired: 'default',
  }

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>Prompt 管理（chat_default 模板）</h2>
      <Card title="版本与审核流（draft→pending_review→approved→active；激活走红队+审校+eval 三重门禁）" style={{ borderRadius: 'var(--ms-radius-card)' }}>
        <div style={{ marginBottom: 12 }}>
          <Button type="primary" onClick={() => setCreateOpen(true)}>新建版本</Button>
        </div>
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
      <Modal
        title="新建 Prompt 版本（chat_default）"
        open={createOpen}
        onOk={handleCreate}
        confirmLoading={creating}
        onCancel={() => setCreateOpen(false)}
        okText="创建"
        cancelText="取消"
      >
        <Input.TextArea
          value={createContent}
          onChange={(e) => setCreateContent(e.target.value)}
          placeholder="Prompt 内容（如：你是波波，AI 情绪陪伴助手…）"
          autoSize={{ minRows: 5, maxRows: 10 }}
          maxLength={4000}
          showCount
        />
        <Input
          style={{ marginTop: 8 }}
          value={createDesc}
          onChange={(e) => setCreateDesc(e.target.value)}
          placeholder="变更说明（可选，将进入版本描述）"
        />
      </Modal>
    </div>
  )
}
