/**
 * toolboxApi 单测（F-2，design/36 §五 API 契约）
 *
 * 契约：
 * - fetchToolboxTools：GET /toolbox（后端按年级过滤）
 * - fetchSosTools：GET /toolbox/sos（断网可开目标态，接口层不阻塞）
 * - recordMoodCheck：POST /toolbox/mood-check（toolId/preMood/postMood）
 * - reportSosEvent：SOS 打开上报为 fire-and-forget——任何失败（含断网）不得抛出（design/36 §3.4 不阻塞界面）
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fetchToolboxTools, fetchSosTools, recordMoodCheck, reportSosEvent } from '../api/toolboxApi'

function mockJson(data: unknown, success = true) {
  return Promise.resolve({
    status: 200,
    json: () => Promise.resolve({ success, data, message: success ? '' : 'err' }),
  } as Response)
}

describe('api/toolboxApi', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    sessionStorage.setItem('mindsafe_student_token', 'test-token')
  })

  it('fetchToolboxTools 命中 GET /toolbox 并返回 data', async () => {
    const tools = [{ toolId: 'breathing_box', title: '深呼吸' }]
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockReturnValue(mockJson(tools))
    const result = await fetchToolboxTools()
    expect(fetchSpy.mock.calls[0][0]).toBe('/api/v1/toolbox')
    expect(result).toEqual(tools)
  })

  it('fetchSosTools 命中 GET /toolbox/sos', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockReturnValue(mockJson([]))
    await fetchSosTools()
    expect(fetchSpy.mock.calls[0][0]).toBe('/api/v1/toolbox/sos')
  })

  it('recordMoodCheck 以 toolId/preMood/postMood 提交 POST /toolbox/mood-check', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockReturnValue(mockJson({ effect: 'IMPROVED', needsAttention: false }))
    const result = await recordMoodCheck('grounding_54321', 3, 7)
    const [url, init] = fetchSpy.mock.calls[0]
    expect(url).toBe('/api/v1/toolbox/mood-check')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(init?.body as string)).toEqual({ toolId: 'grounding_54321', preMood: 3, postMood: 7 })
    expect(result).toMatchObject({ effect: 'IMPROVED' })
  })

  it('reportSosEvent 成功时不抛错', async () => {
    vi.spyOn(globalThis, 'fetch').mockReturnValue(mockJson({}))
    await expect(reportSosEvent()).resolves.toBeUndefined()
  })

  it('reportSosEvent 断网/失败时静默（绝不阻塞 SOS 界面，design/36 §3.4）', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    await expect(reportSosEvent()).resolves.toBeUndefined()
  })
})
