import { useEffect, useRef, type RefObject } from 'react'
import * as echarts from 'echarts/core'
import type { EChartsOption } from 'echarts'

/**
 * ECharts 统一生命周期 hook（FA-03，DOC-074）
 * init → setOption（option 变化时 notMerge 刷新）→ ResizeObserver 自适应 → 卸载 dispose。
 * 此前两套并存：StatsCharts 内联 ResizeObserver 实现、ProfileRadarChart 全量导入 +
 * window.resize 监听 + 手动 dispose；新图表统一走本 hook。
 * 支持图表容器条件渲染（div 晚于挂载出现）：option 就绪后自动补 init。
 * 注意：模块注册（echarts.use）由消费方按需完成（echarts/core 按需注册模式）。
 */
function initChart(node: HTMLElement) {
  const chart = echarts.init(node)
  const ro = new ResizeObserver(() => chart.resize())
  ro.observe(node)
  return { chart, ro }
}

export function useECharts(ref: RefObject<HTMLElement | null>, option: EChartsOption | null | undefined) {
  const chartRef = useRef(null)

  // 挂载即 init（容器常驻渲染的场景，如 ChartBox）
  useEffect(() => {
    if (!ref.current || chartRef.current) return
    const { chart, ro } = initChart(ref.current)
    chartRef.current = chart
    return () => {
      ro.disconnect()
      chart.dispose()
      chartRef.current = null
    }
  }, [ref])

  // 条件渲染补 init（容器晚于挂载出现，如 ProfileRadarChart loading 后才渲染 div）
  useEffect(() => {
    if (!option || chartRef.current || !ref.current) return
    const { chart, ro } = initChart(ref.current)
    chartRef.current = chart
    return () => {
      ro.disconnect()
      chart.dispose()
      chartRef.current = null
    }
  }, [option, ref])

  useEffect(() => {
    if (chartRef.current && option) {
      chartRef.current.setOption(option, true)
    }
  }, [option])

  return chartRef
}
