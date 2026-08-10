import { useEffect, useState } from 'react'
import { Card, Col, Row, message, Statistic } from 'antd'
import { fetchMetricsQuery } from '../api'

/** 提取 Prometheus 即时查询结果的单值（无样本返回 undefined） */
function extractValue(body: Record<string, unknown> | undefined): number | undefined {
  if (!body || body.status !== 'success') return undefined
  const data = body.data as { result?: Array<{ value?: [number, string] }> } | undefined
  const first = data?.result?.[0]
  if (!first?.value) return undefined
  const v = Number(first.value[1])
  return Number.isFinite(v) ? v : undefined
}

/** 指标卡配置：标题 + Prometheus 表达式 + 单位 + 精度 */
const METRIC_CARDS: Array<{ title: string; expr: string; unit?: string; digits?: number }> = [
  { title: 'TTS 合成请求量', expr: 'sum(tts_synthesize_requests_total)', digits: 0 },
  { title: 'TTS 降级事件', expr: 'sum(tts_degraded_events_total)', digits: 0 },
  { title: 'LLM 主备切换', expr: 'sum(mindsafe_llm_model_fallback_total)', digits: 0 },
  { title: 'LLM 超时', expr: 'sum(mindsafe_llm_timeout_total)', digits: 0 },
  { title: '语音分析请求量', expr: 'sum(voice_analyze_requests_total)', digits: 0 },
  { title: '语音 ASR 就绪', expr: 'voice_asr_ready', digits: 0 },
  { title: '语音 SER 就绪', expr: 'voice_ser_ready', digits: 0 },
  { title: 'HTTP 请求量', expr: 'sum(http_server_requests_seconds_count)', digits: 0 },
  { title: 'JVM 堆使用 (MB)', expr: 'jvm_memory_used_bytes', digits: 1 },
  { title: 'DB 连接池活跃', expr: 'sum(hikaricp_connections_active)', digits: 0 },
]

/** 指标看板（ADMIN-P1-07/09，M2：后端白名单代理查询 Prometheus，非白名单 403） */
export default function MetricsPage() {
  const [values, setValues] = useState<Record<string, number | undefined>>({})
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    Promise.all(
      METRIC_CARDS.map(async (card) => {
        try {
          const body = await fetchMetricsQuery(card.expr)
          return { expr: card.expr, value: extractValue(body) }
        } catch {
          return { expr: card.expr, value: undefined }
        }
      }),
    ).then((results) => {
      if (cancelled) return
      const map: Record<string, number | undefined> = {}
      for (const r of results) map[r.expr] = r.value
      setValues(map)
      setLoading(false)
    })
    return () => {
      cancelled = true
    }
  }, [])

  const fmt = (v: number | undefined, digits: number): string => (v === undefined ? '—' : v.toFixed(digits))

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>指标看板</h2>
      <Row gutter={[16, 16]}>
        {METRIC_CARDS.map((card) => (
          <Col key={card.expr} xs={12} md={8} lg={6}>
            <Card title={card.title} size="small" loading={loading} style={{ borderRadius: 'var(--ms-radius-card)' }}>
              <Statistic
                value={fmt(values[card.expr], card.digits ?? 0)}
                valueStyle={{ fontSize: 20 }}
                suffix={card.unit}
              />
            </Card>
          </Col>
        ))}
      </Row>
      <p style={{ color: 'var(--ms-text-muted)', fontSize: 12, marginTop: 12 }}>
        数据来源：Prometheus 即时查询（后端白名单代理，仅平台自有指标）。无数据（—）表示指标尚无样本或服务未上报。
      </p>
    </div>
  )
}
