import { useRef } from 'react'
import * as echarts from 'echarts/core'
import type { EChartsOption } from 'echarts'
import { emotionLabel } from '../../../../shared/src/emotionMeta'
import { themeColors } from '../../../../shared/src/theme'
import { riskHex } from '../../utils/riskLevel'
import { useECharts } from '../../hooks/useECharts'
import { LineChart, PieChart, BarChart } from 'echarts/charts'
import type { DailyCount, RiskDistItem, ClassRiskItem, EmotionItem } from '../../api'
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

/** 通用 ECharts 容器（生命周期统一走 useECharts，FA-03） */
function ChartBox({ option, height = 260 }: { option: EChartsOption; height?: number }) {
  const ref = useRef<HTMLDivElement | null>(null)
  useECharts(ref, option)
  return <div ref={ref} style={{ width: '100%', height }} />
}

// 品牌色板单源（doing/92 R-005）：自 shared/theme 导出，与 index.css --ms-* token 同源（一致性由 theme-consistency.test.ts 守卫）

// 风险等级色：FA-01 收敛到 utils/riskLevel 单源（1 黄 / 2 橙 / 3 红，此前 1/2 同色）

/** 30 天会话趋势折线图 */
export function SessionTrendChart({ data }: { data: DailyCount[] | undefined }) {
  const option: EChartsOption = {
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
      areaStyle: { color: themeColors.primarySoft },
      lineStyle: { color: themeColors.primary, width: 2 },
      itemStyle: { color: themeColors.primary },
    }],
  }
  return <ChartBox option={option} height={220} />
}

/** 风险等级分布饼图 */
export function RiskPieChart({ data }: { data: RiskDistItem[] | undefined }) {
  const option: EChartsOption = {
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
        itemStyle: { color: riskHex(d.level) },
      })),
    }],
  }
  return <ChartBox option={option} height={240} />
}

/** 班级对比柱状图 */
export function ClassBarChart({ data }: { data: ClassRiskItem[] | undefined }) {
  const option: EChartsOption = {
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
        itemStyle: { color: themeColors.danger, borderRadius: [3, 3, 0, 0] },
        barMaxWidth: 28,
      },
      {
        name: '学生数',
        type: 'bar',
        data: data?.map(d => d.studentCount) || [],
        // 青屿主色系（替换 antd 默认蓝 #91d5ff）
        itemStyle: { color: themeColors.primaryMid, borderRadius: [3, 3, 0, 0] },
        barMaxWidth: 28,
      },
    ],
    legend: { bottom: 0, itemWidth: 12, itemHeight: 12 },
  }
  return <ChartBox option={option} height={240} />
}

/** 情绪分布横向柱状图 */
export function EmotionBarChart({ data }: { data: EmotionItem[] | undefined }) {
  const sorted = [...(data || [])].reverse()
  const option: EChartsOption = {
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
        // 青屿渐变（主色 → 主色加深，替换 antd 默认蓝）
        color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
          { offset: 0, color: themeColors.gradientMid },
          { offset: 1, color: themeColors.primaryDeep },
        ]),
      },
      barMaxWidth: 18,
    }],
  }
  return <ChartBox option={option} height={220} />
}
