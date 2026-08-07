/**
 * DC-007 声纹注册流程收敛测试（SPEC §21）
 * 覆盖：remote 分支（api + 存储标记）、local 分支（存本地 + 签发凭证）、
 * 凭证签发失败不阻断、主流程异常上抛
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { enrollVoiceprintFlow } from './voiceprintEnrollment'

const mocks = vi.hoisted(() => ({
  remoteVoiceprintEnroll: vi.fn(),
  issueVoiceCredential: vi.fn(),
  enrollVoiceprint: vi.fn(),
  saveVoiceCredential: vi.fn(),
  markRemoteVoiceprintEnrolled: vi.fn(),
}))

vi.mock('../api', () => ({
  remoteVoiceprintEnroll: mocks.remoteVoiceprintEnroll,
  issueVoiceCredential: mocks.issueVoiceCredential,
}))

vi.mock('./voiceprintStore', () => ({
  enrollVoiceprint: mocks.enrollVoiceprint,
  saveVoiceCredential: mocks.saveVoiceCredential,
  markRemoteVoiceprintEnrolled: mocks.markRemoteVoiceprintEnrolled,
}))

const EMBEDDINGS = [[1, 0], [0, 1]]

describe('enrollVoiceprintFlow', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('remote 分支：remoteVoiceprintEnroll → markRemoteVoiceprintEnrolled(tenantId)，不碰本地存储', async () => {
    mocks.remoteVoiceprintEnroll.mockResolvedValue({ enrolled: 2, tenantId: 't1' })

    const result = await enrollVoiceprintFlow(
      { embeddings: EMBEDDINGS, userId: 'u1', pseudonym: '小明' },
      'remote'
    )

    expect(mocks.remoteVoiceprintEnroll).toHaveBeenCalledWith(EMBEDDINGS)
    expect(mocks.markRemoteVoiceprintEnrolled).toHaveBeenCalledWith('t1')
    expect(result).toEqual({ mode: 'remote', enrolled: 2 })
    expect(mocks.enrollVoiceprint).not.toHaveBeenCalled()
    expect(mocks.issueVoiceCredential).not.toHaveBeenCalled()
  })

  it('local 分支：enrollVoiceprint → issueVoiceCredential → saveVoiceCredential', async () => {
    mocks.enrollVoiceprint.mockResolvedValue({})
    mocks.issueVoiceCredential.mockResolvedValue('cred_jwt')
    mocks.saveVoiceCredential.mockResolvedValue({})

    const result = await enrollVoiceprintFlow(
      { embeddings: EMBEDDINGS, userId: 'u1', pseudonym: '小明' },
      'local'
    )

    expect(mocks.enrollVoiceprint).toHaveBeenCalledWith('u1', '小明', EMBEDDINGS)
    expect(mocks.issueVoiceCredential).toHaveBeenCalled()
    expect(mocks.saveVoiceCredential).toHaveBeenCalledWith('u1', 'cred_jwt')
    expect(result).toEqual({ mode: 'local', enrolled: 2 })
    expect(mocks.markRemoteVoiceprintEnrolled).not.toHaveBeenCalled()
  })

  it('local 分支：凭证签发失败不阻断（console.warn 后仍返回成功）', async () => {
    mocks.enrollVoiceprint.mockResolvedValue({})
    mocks.issueVoiceCredential.mockRejectedValue(new Error('network'))
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

    const result = await enrollVoiceprintFlow(
      { embeddings: EMBEDDINGS, userId: 'u1', pseudonym: '小明' },
      'local'
    )

    expect(warnSpy).toHaveBeenCalled()
    expect(result).toEqual({ mode: 'local', enrolled: 2 })
    expect(mocks.saveVoiceCredential).not.toHaveBeenCalled()
    warnSpy.mockRestore()
  })

  it('remote 分支：主流程异常上抛（不吞没）', async () => {
    mocks.remoteVoiceprintEnroll.mockRejectedValue(new Error('server down'))

    await expect(
      enrollVoiceprintFlow({ embeddings: EMBEDDINGS, userId: 'u1', pseudonym: '小明' }, 'remote')
    ).rejects.toThrow('server down')
  })

  it('local 分支：本地存储失败上抛且不签发凭证', async () => {
    mocks.enrollVoiceprint.mockRejectedValue(new Error('IndexedDB 不可用'))

    await expect(
      enrollVoiceprintFlow({ embeddings: EMBEDDINGS, userId: 'u1', pseudonym: '小明' }, 'local')
    ).rejects.toThrow('IndexedDB 不可用')
    expect(mocks.issueVoiceCredential).not.toHaveBeenCalled()
  })
})
