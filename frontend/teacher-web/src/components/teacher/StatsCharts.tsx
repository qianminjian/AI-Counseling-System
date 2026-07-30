import { useRef, useEffect } from 'react'
import * as echarts from 'echarts/core'
import { emotionLabel } from '../../utils/emotionLabels'
import { LineChart, PieChart, BarChart } from 'echarts/charts'
import {
  TitleComponent, TooltipComponent, LegendComponent,
  GridComponent, DatasetComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  LineChart, PieChart, BarChart,
  TitleComponent, TooltipComponent, LegendComponent,
  GridComponent, DatasetComponent, CanvasRenderer,
])

/** 通用 ECharts 容器 */
function ChartBox({ option, height = 260 }) {
  const ref = useRef(null)
  const chartRef = useRef(null)

  useEffect(() => {
    if (!ref.current) return
    chartRef.current = echarts.init(ref.current)
    const ro = new ResizeObserver(() => chartRef.current?.resize())
    ro.observe(ref.current)
    return () => { ro.disconnect(); chartRef.current?.dispose() }
  }, [])

  useEffect(() => {
    if (chartRef.current && option) {
      chartRef.current.setOption(option, true)
    }
  }, [option])

  return <div ref={ref} style={{ width: '100%', height }} />
}

const RISK_COLORS = { 1: '#faad14', 2: '#fa8c16', 3: '#ff4d4f' }

/** 30 天会话趋势折线图 */
export function SessionTrendChart({ data }) {
  const option = {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 16, top: 32, bottom: 28 },
    xAxis: {
      type: 'category',
      data: data?.map(d => d.date.slice(5)) || [],
      axisLabel: { fontSize: 10, interval: 4 },
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{
      name: '会话数',
      type: 'line',
      data: data?.map(d => d.count) || [],
      smooth: true,
      areaStyle: { color: 'rgba(24,144,255,0.08)' },
      lineStyle: { color: '#1890ff', width: 2 },
      itemStyle: { color: '#1890ff' },
    }],
  }
  return <ChartBox option={option} height={220} />
}

/** 风险等级分布饼图 */
export function RiskPieChart({ data }) {
  const option = {
    tooltip: { trigger: 'item', formatter: '{b}: {c} 次 ({d}%)' },
    legend: { bottom: 0, itemWidth: 12, itemHeight: 12 },
    series: [{
      type: 'pie',
      radius: ['40%', '68%'],
      center: ['50%', '45%'],
      avoidLabelOverlap: true,
      label: { show: true, formatter: '{b}\n{c}次' },
      data: (data || []).map(d => ({
        name: d.label,
        value: d.count,
        itemStyle: { color: RISK_COLORS[d.level] },
      })),
    }],
  }
  return <ChartBox option={option} height={240} />
}

/** 班级对比柱状图 */
export function ClassBarChart({ data }) {
  const option = {
    tooltip: { trigger: 'axis' },
    grid: { left: 60, right: 16, top: 32, bottom: 28 },
    xAxis: {
      type: 'category',
      data: data?.map(d => d.classCode) || [],
      axisLabel: { fontSize: 10, rotate: data?.length > 6 ? 30 : 0 },
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '预警数',
        type: 'bar',
        data: data?.map(d => d.alertCount) || [],
        itemStyle: { color: '#ff7875', borderRadius: [3, 3, 0, 0] },
        barMaxWidth: 28,
      },
      {
        name: '学生数',
        type: 'bar',
        data: data?.map(d => d.studentCount) || [],
        itemStyle: { color: '#91d5ff', borderRadius: [3, 3, 0, 0] },
        barMaxWidth: 28,
      },
    ],
    legend: { bottom: 0, itemWidth: 12, itemHeight: 12 },
  }
  return <ChartBox option={option} height={240} />
}

/** 情绪分布横向柱状图 */
export function EmotionBarChart({ data }) {
  const sorted = [...(data || [])].reverse()
  const option = {
    tooltip: { trigger: 'axis' },
    grid: { left: 72, right: 24, top: 12, bottom: 12 },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: {
      type: 'category',
      data: sorted.map(d => emotionLabel(d.emotion)),
      axisLabel: { fontSize: 11 },
    },
    series: [{
      type: 'bar',
      data: sorted.map(d => d.count),
      itemStyle: {
        borderRadius: [0, 3, 3, 0],
        color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
          { offset: 0, color: '#83bff6' },
          { offset: 1, color: '#188df0' },
        ]),
      },
      barMaxWidth: 18,
    }],
  }
  return <ChartBox option={option} height={220} />
}
