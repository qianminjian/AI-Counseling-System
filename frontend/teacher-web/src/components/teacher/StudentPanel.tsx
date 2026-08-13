import { useState, useEffect, useCallback, useMemo } from 'react'
import { Table, Tag, Card, Button, message, Input, List, Descriptions, Timeline, Space, Empty, Spin, Select, Checkbox } from 'antd'
import type { TableProps } from 'antd'
import { ArrowLeftOutlined, PlusOutlined, MessageOutlined, DownloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { getStudents, getHighRiskStudents, getStudentProfile, addStudentNote, exportStudentsCsv, type AlertVO, type NoteVO, type StudentProfileVO, type StudentVO, type HighRiskStudentVO } from '../../api'
import SessionMessagesDrawer from './SessionMessagesDrawer'
import ProfileRadarChart from './ProfileRadarChart'
import { riskColor, riskLabel } from '../../utils/riskLevel'

/** 学生档案详情 */
function StudentProfile({ studentId, onBack }: { studentId: string; onBack: () => void }) {
  const [profile, setProfile] = useState<StudentProfileVO | null>(null)
  const [loading, setLoading] = useState(true)
  const [noteText, setNoteText] = useState('')
  const [adding, setAdding] = useState(false)
  const [viewSessionId, setViewSessionId] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await getStudentProfile(studentId)
      setProfile(data)
    } catch (e) {
      message.error('加载档案失败: ' + e.message)
    } finally {
      setLoading(false)
    }
  }, [studentId])

  useEffect(() => { load() }, [load])

  const handleAddNote = async () => {
    if (!noteText.trim()) return
    setAdding(true)
    try {
      await addStudentNote(studentId, noteText.trim())
      message.success('备注已添加')
      setNoteText('')
      load()
    } catch (e) {
      message.error(e.message)
    } finally {
      setAdding(false)
    }
  }

  if (loading) return <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
  if (!profile) return <Empty description="未找到学生信息" />

  return (
    <div>
      <Button icon={<ArrowLeftOutlined />} onClick={onBack} className="ms-mb-16">
        返回列表
      </Button>

      {/* 基本信息 */}
      <Card size="small" className="ms-mb-16">
        <Descriptions column={{ xs: 1, sm: 3 }} size="small">
          <Descriptions.Item label="姓名">{profile.displayName}</Descriptions.Item>
          <Descriptions.Item label="年级">{profile.gradeCode || '-'}</Descriptions.Item>
          <Descriptions.Item label="班级">{profile.classCode || '-'}</Descriptions.Item>
          {/* BUG-UI-03：账号状态（withdrawn=家长撤回冻结） */}
          <Descriptions.Item label="账号状态">
            {profile.status === 'withdrawn' ? <Tag color="default">冻结</Tag> : <Tag color="green">正常</Tag>}
          </Descriptions.Item>
          <Descriptions.Item label="最高风险等级">
            {profile.maxRiskLevel != null ? (
              <Tag color={riskColor(profile.maxRiskLevel)}>{riskLabel(profile.maxRiskLevel)}</Tag>
            ) : (
              <span style={{ color: 'var(--ms-text-muted)' }}>无权查看</span>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="累计会话">{profile.totalSessions} 次</Descriptions.Item>
        </Descriptions>
      </Card>

      {/* 心理画像雷达图（PROF-004） */}
      <div className="ms-mb-16">
        <ProfileRadarChart studentId={studentId} />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: 16 }}>
        {/* 近期会话 */}
        <Card title="近期会话" size="small">
          {profile.recentSessions?.length > 0 ? (
            <Timeline
              items={profile.recentSessions.map((s) => ({
                color: s.riskLevel >= 2 ? 'red' : s.riskLevel >= 1 ? 'orange' : 'green',
                children: (
                  <div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <span>{dayjs(s.startedAt).format('MM-DD HH:mm')}</span>
                      <Button
                        size="small"
                        type="link"
                        icon={<MessageOutlined />}
                        onClick={() => setViewSessionId(s.sessionId)}
                        style={{ padding: 0, height: 'auto', fontSize: 12 }}
                      >
                        对话摘要
                      </Button>
                    </div>
                    <div className="ms-hint">
                      状态: {s.status} | 风险: {riskLabel(s.riskLevel) || '无'}
                      {s.satisfactionRating && (
                        <span style={{ marginLeft: 8, color: s.satisfactionRating >= 4 ? 'var(--ms-success)' : 'var(--ms-warning)' }}>
                          满意度: {'⭐'.repeat(s.satisfactionRating)}
                        </span>
                      )}
                    </div>
                  </div>
                ),
              }))}
            />
          ) : <Empty description="暂无会话记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
        </Card>

        {/* 预警历史 */}
        <Card title="预警历史" size="small">
          {profile.alertHistory?.length > 0 ? (
            <List
              size="small"
              dataSource={profile.alertHistory}
              renderItem={(item: AlertVO) => (
                <List.Item>
                  <Space>
                    <Tag color={riskColor(item.riskLevel)}>{riskLabel(item.riskLevel)}</Tag>
                    <span>{item.riskType}</span>
                    <span className="ms-hint">
                      {dayjs(item.detectedAt).format('MM-DD')}
                    </span>
                  </Space>
                </List.Item>
              )}
            />
          ) : <Empty description="暂无预警" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
        </Card>
      </div>

      {/* 教师备注 */}
      <Card title="教师备注" size="small" className="ms-mt-16">
        <div style={{ marginBottom: 12, display: 'flex', gap: 8 }}>
          <Input.TextArea
            value={noteText}
            onChange={(e) => setNoteText(e.target.value)}
            placeholder="添加观察备注..."
            autoSize={{ minRows: 2, maxRows: 4 }}
            style={{ flex: 1 }}
          />
          <Button
            type="primary"
            icon={<PlusOutlined />}
            loading={adding}
            onClick={handleAddNote}
            disabled={!noteText.trim()}
          >
            添加
          </Button>
        </div>
        {profile.notes?.length > 0 ? (
          <List
            size="small"
            dataSource={profile.notes}
            renderItem={(note: NoteVO) => (
              <List.Item>
                <div>
                  <div>{note.content}</div>
                  <div style={{ fontSize: 11, color: 'var(--ms-text-muted)', marginTop: 2 }}>
                    {note.noteType} · {dayjs(note.createdAt).format('YYYY-MM-DD HH:mm')}
                  </div>
                </div>
              </List.Item>
            )}
          />
        ) : <Empty description="暂无备注" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
      </Card>

      {/* 对话摘要抽屉 */}
      <SessionMessagesDrawer sessionId={viewSessionId} onClose={() => setViewSessionId(null)} />
    </div>
  )
}

/** 学生管理主面板（BUG-T-04-03：年级/班级筛选 + 昵称搜索 + 风险等级列） */
export default function StudentPanel() {
  const [students, setStudents] = useState<StudentVO[]>([])
  const [highRisk, setHighRisk] = useState<HighRiskStudentVO[]>([])
  const [loading, setLoading] = useState(true)
  // F-04：显式泛型（学生 ID；原 useState(null) 推断 any）
  const [selectedStudent, setSelectedStudent] = useState<string | null>(null)
  const [gradeCode, setGradeCode] = useState<string>()   // 年级筛选
  const [classCode, setClassCode] = useState<string>()   // 班级筛选
  const [keyword, setKeyword] = useState('')             // 昵称搜索
  const [highRiskOnly, setHighRiskOnly] = useState(false) // 只看高危（风险≥2）

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [studs, hr] = await Promise.all([
        getStudents({ gradeCode, classCode, keyword: keyword || undefined, minRisk: highRiskOnly ? 2 : undefined }),
        getHighRiskStudents(),
      ])
      setStudents(studs)
      setHighRisk(hr)
    } catch (e) {
      message.error('加载学生列表失败')
    } finally {
      setLoading(false)
    }
  }, [gradeCode, classCode, keyword, highRiskOnly])

  useEffect(() => { void load() }, [load])

  // 年级/班级选项（从当前列表去重派生）
  const gradeOptions = useMemo(() =>
    Array.from(new Set(students.map((s) => s.gradeCode).filter(Boolean))).map((v) => ({ value: v, label: `${v} 年级` })),
    [students],
  )
  const classOptions = useMemo(() =>
    Array.from(new Set(students.map((s) => s.classCode).filter(Boolean))).map((v) => ({ value: v, label: v })),
    [students],
  )

  if (selectedStudent) {
    return <StudentProfile studentId={selectedStudent} onBack={() => setSelectedStudent(null)} />
  }

  const highRiskIds = new Set(highRisk.map((h) => h.studentUserId))

  const columns: TableProps<StudentVO>['columns'] = [
    {
      title: '姓名', dataIndex: 'displayName',
      render: (v, record) => (
        <Space>
          <a onClick={() => setSelectedStudent(record.userId)}>{v}</a>
          {highRiskIds.has(record.userId) && <Tag color="red">高风险</Tag>}
          {/* BUG-UI-03：冻结学生可见并带标识（撤回同意） */}
          {record.status === 'withdrawn' && <Tag>冻结</Tag>}
        </Space>
      ),
    },
    { title: '年级', dataIndex: 'gradeCode', width: 80 },
    { title: '班级', dataIndex: 'classCode', width: 90 },
    {
      // BUG-T-04-03：风险等级列（0-3，会话快照 ∪ 未关闭预警）
      title: '风险', dataIndex: 'riskLevel', width: 80,
      render: (level: number) =>
        level > 0 ? <Tag color={riskColor(level)}>{riskLabel(level)}</Tag> : <span className="ms-hint">无</span>,
    },
    {
      title: '状态', dataIndex: 'status', width: 80,
      render: (s: string) => s === 'withdrawn' ? <Tag>冻结</Tag> : <span className="ms-hint">正常</span>,
    },
    {
      title: '操作', width: 100,
      render: (_, record) => (
        <Button size="small" onClick={() => setSelectedStudent(record.userId)}>查看档案</Button>
      ),
    },
  ]

  return (
    <div>
      {/* 高风险提醒 */}
      {highRisk.length > 0 && (
        <Card size="small" className="ms-mb-16" style={{ borderColor: 'var(--ms-danger-soft)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <span className="ms-text-danger" style={{ fontWeight: 500 }}>⚠️ 高风险学生：</span>
            {highRisk.map((s) => (
              <Tag
                key={s.studentUserId}
                color={riskColor(s.maxRiskLevel)}
                style={{ cursor: 'pointer' }}
                onClick={() => setSelectedStudent(s.studentUserId)}
              >
                {s.displayName}
              </Tag>
            ))}
          </div>
        </Card>
      )}

      <Card size="small" title="学生列表" extra={
        <Button size="small" icon={<DownloadOutlined />} onClick={exportStudentsCsv}>导出 CSV</Button>
      }>
        {/* BUG-T-04-03：筛选/搜索区 */}
        <Space style={{ marginBottom: 12 }} wrap>
          <Select
            allowClear
            placeholder="年级筛选"
            style={{ width: 130 }}
            value={gradeCode}
            onChange={(v) => setGradeCode(v ?? undefined)}
            options={gradeOptions}
          />
          <Select
            allowClear
            placeholder="班级筛选"
            style={{ width: 130 }}
            value={classCode}
            onChange={(v) => setClassCode(v ?? undefined)}
            options={classOptions}
          />
          <Input.Search
            allowClear
            placeholder="搜索学生昵称"
            style={{ width: 180 }}
            onSearch={(v) => setKeyword(v)}
          />
          <Checkbox checked={highRiskOnly} onChange={(e) => setHighRiskOnly(e.target.checked)}>
            只看风险学生
          </Checkbox>
        </Space>
        <Table
          dataSource={students}
          columns={columns}
          rowKey="userId"
          loading={loading}
          pagination={{ pageSize: 20, showSizeChanger: false }}
          size="small"
        />
      </Card>
    </div>
  )
}
