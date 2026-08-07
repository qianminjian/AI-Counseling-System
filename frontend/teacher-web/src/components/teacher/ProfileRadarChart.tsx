import { useState, useEffect, useRef } from 'react'
import { Card, Empty, Spin, Tag, Timeline } from 'antd'
import * as echarts from 'echarts'
import { getStudentRadar } from '../../api'

/** 画像雷达图 + 成长里程碑（PROF-004，对齐 design/23 §6） */
export default function ProfileRadarChart({ studentId }) {
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const chartRef = useRef(null)
  const chartInstance = useRef(null)

  useEffect(() => {
    if (!studentId) return
    let cancelled = false
    setLoading(true)
    getStudentRadar(studentId)
      .then((d) => { if (!cancelled) setData(d) })
      .catch(() => { if (!cancelled) setData(null) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [studentId])

  // 渲染 ECharts 雷达图
  useEffect(() => {
    if (!data?.dimensions || !chartRef.current) return

    if (!chartInstance.current) {
      chartInstance.current = echarts.init(chartRef.current)
    }
    const chart = chartInstance.current

    const indicators = data.dimensions.map((d) => ({ name: d.name, max: 100 }))
    const values = data.dimensions.map((d) => d.score)

    chart.setOption({
      tooltip: { trigger: 'item' },
      radar: {
        indicator: indicators,
        shape: 'polygon',
        radius: '65%',
        axisName: { color: '#666', fontSize: 12 },
        splitArea: { areaStyle: { color: ['#f5f5f5', '#fff'] } },
      },
      series: [{
        type: 'radar',
        data: [{
          value: values,
          name: '心理画像',
          areaStyle: { color: 'rgba(24, 144, 255, 0.2)' },
          lineStyle: { color: 'var(--ms-primary)', width: 2 },
          itemStyle: { color: 'var(--ms-primary)' },
        }],
      }],
    })

    const handleResize = () => chart.resize()
    window.addEventListener('resize', handleResize)
    return () => {
      window.removeEventListener('resize', handleResize)
    }
  }, [data])

  // 组件卸载时销毁图表
  useEffect(() => {
    return () => {
      if (chartInstance.current) {
        chartInstance.current.dispose()
        chartInstance.current = null
      }
    }
  }, [])

  if (loading) {
    return (
      <Card title="心理画像" size="small">
        <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
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
      extra={<span style={{ fontSize: 12, color: '#999' }}>累计 {data.totalSessions} 次会话</span>}
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
                <span style={{ fontSize: 13 }}>
                  <Tag color="green" style={{ margin: 0 }}>{m.label}</Tag>
                  {m.period && <span style={{ marginLeft: 8, color: '#999', fontSize: 12 }}>{m.period}</span>}
                </span>
              ),
            }))}
          />
        </div>
      )}
    </Card>
  )
}
