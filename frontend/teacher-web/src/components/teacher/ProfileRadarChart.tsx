import { useState, useEffect, useRef } from 'react'
import { Card, Empty, Spin, Tag, Timeline } from 'antd'
import { RadarChart } from 'echarts/charts'
import { TooltipComponent, RadarComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import * as echarts from 'echarts/core'
import type { EChartsOption } from 'echarts'
import { getStudentRadar, getToken } from '../../api'
import { decodeJwtPayload } from '../../utils/jwt'
import { useECharts } from '../../hooks/useECharts'

// FA-03：按需注册（替代全量导入 ~1MB），与 StatsCharts 同模式
echarts.use([RadarChart, TooltipComponent, RadarComponent, CanvasRenderer])

/** 画像雷达数据（后端 Map 输出，见 ProfileRadarService） */
interface RadarData {
  dimensions?: Array<{ name: string; score: number }>
  hasProfile?: boolean
  totalSessions?: number
  milestones?: Array<{ label: string; period?: string }>
}

/** 画像雷达图 + 成长里程碑（PROF-004，对齐 design/23 §6） */
export default function ProfileRadarChart({ studentId }: { studentId: string }) {
  const [data, setData] = useState<RadarData | null>(null)
  const [loading, setLoading] = useState(true)
  const chartRef = useRef<HTMLDivElement | null>(null)

  // BUG-T-05-01：班主任（class/head_teacher）前端不请求画像（服务端已 403 双保险）
  const restricted = (() => {
    const token = getToken()
    if (!token) return false
    const ut = String(decodeJwtPayload(token)?.userType ?? '')
    return ut === 'head_teacher' || ut === 'class_teacher'
  })()

  useEffect(() => {
    if (!studentId || restricted) {
      setLoading(false)
      return
    }
    let cancelled = false
    setLoading(true)
    getStudentRadar(studentId)
      .then((d) => { if (!cancelled) setData(d) })
      .catch(() => { if (!cancelled) setData(null) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [studentId, restricted])

  // 渲染 ECharts 雷达图（生命周期统一走 useECharts，FA-03）
  const option: EChartsOption | null = data?.dimensions ? {
    tooltip: { trigger: 'item' },
    radar: {
      indicator: data.dimensions.map((d) => ({ name: d.name, max: 100 })),
      shape: 'polygon',
      radius: '65%',
      // ECharts canvas 绘制不支持 CSS var()，用 token 对应色值（doing/75 方案 A）
      axisName: { color: '#5C6B76', fontSize: 12 },
      splitArea: { areaStyle: { color: ['#F4F7F6', '#FFFFFF'] } },
    },
    series: [{
      type: 'radar',
      data: [{
        value: data.dimensions.map((d) => d.score),
        name: '心理画像',
        // 青屿主色（替换 antd 默认蓝；canvas 不支持 var()，用真实色值）
        areaStyle: { color: 'rgba(43, 168, 160, 0.2)' },
        lineStyle: { color: '#2BA8A0', width: 2 },
        itemStyle: { color: '#2BA8A0' },
      }],
    }],
  } : null
  useECharts(chartRef, option)

  // 班主任裁剪：与服务端一致显示权限提示（不渲染图表；Hook 调用须在 return 前保持顺序）
  if (restricted) {
    return (
      <Card title="心理画像" size="small" className="ms-mb-16">
        <Empty description="无权查看该数据（班主任角色）" />
      </Card>
    )
  }

  if (loading) {
    return (
      <Card title="心理画像" size="small">
        <div className="ms-empty"><Spin /></div>
      </Card>
    )
  }

  if (!data || !data.hasProfile) {
    return (
      <Card title="心理画像" size="small">
        <Empty description="暂无画像数据（需完成至少一次对话）" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </Card>
    )
  }

  return (
    <Card
      title="心理画像"
      size="small"
      extra={<span className="ms-hint">累计 {data.totalSessions} 次会话</span>}
    >
      {/* 雷达图 */}
      <div ref={chartRef} style={{ width: '100%', height: 280 }} />

      {/* 成长里程碑 */}
      {data.milestones?.length > 0 && (
        <div style={{ marginTop: 12 }}>
          <div style={{ fontWeight: 500, marginBottom: 8, fontSize: 13 }}>🏆 成长里程碑</div>
          <Timeline
            items={data.milestones.map((m) => ({
              color: 'green',
              children: (
                <span className="ms-text-sm">
                  <Tag color="green" className="ms-m-0">{m.label}</Tag>
                  {m.period && <span style={{ marginLeft: 8, color: 'var(--ms-text-muted)', fontSize: 12 }}>{m.period}</span>}
                </span>
              ),
            }))}
          />
        </div>
      )}
    </Card>
  )
}
