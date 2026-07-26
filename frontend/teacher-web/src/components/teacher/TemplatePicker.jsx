import { useState } from 'react'
import { Tag, Popover, Spin } from 'antd'
import { SnippetsOutlined } from '@ant-design/icons'
import { getTemplates } from '../../api'

/** 干预话术模板选择器（嵌入备注/处理输入框旁） */
export default function TemplatePicker({ onSelect }) {
  const [templates, setTemplates] = useState([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)

  const load = async () => {
    if (templates.length > 0) return
    setLoading(true)
    try {
      const data = await getTemplates()
      setTemplates(data || [])
    } catch { /* ignore */ }
    setLoading(false)
  }

  const content = (
    <div style={{ width: 320, maxHeight: 280, overflow: 'auto' }}>
      {loading ? <Spin size="small" /> : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {templates.map(t => (
            <div key={t.id}
              onClick={() => { onSelect(t.content); setOpen(false) }}
              style={{ cursor: 'pointer', padding: '8px 10px', borderRadius: 6, border: '1px solid #f0f0f0', fontSize: 12, lineHeight: 1.5 }}
              onMouseEnter={e => e.currentTarget.style.background = '#f5f5f5'}
              onMouseLeave={e => e.currentTarget.style.background = '#fff'}>
              <Tag color="blue" style={{ marginBottom: 4 }}>{t.category}</Tag>
              <div style={{ color: '#555' }}>{t.content}</div>
            </div>
          ))}
        </div>
      )}
    </div>
  )

  return (
    <Popover content={content} title="选择话术模板" trigger="click"
      open={open} onOpenChange={(v) => { setOpen(v); if (v) load() }}
      placement="bottomRight">
      <Tag icon={<SnippetsOutlined />} color="processing" style={{ cursor: 'pointer' }}>
        话术模板
      </Tag>
    </Popover>
  )
}
