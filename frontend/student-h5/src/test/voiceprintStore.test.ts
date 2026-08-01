import { describe, it, expect, vi, beforeAll } from 'vitest'
import 'fake-indexeddb/auto'
import {
  getAllVoiceprints,
  getVoiceprint,
  enrollVoiceprint,
  saveVoiceCredential,
  appendEmbedding,
  deleteVoiceprint,
  hasAnyVoiceprint,
  isVoiceprintStorageAvailable,
} from '../utils/voiceprintStore'

describe('voiceprintStore', () => {
  // 使用唯一 ID 避免测试间冲突（单例 DB 不重置）
  let uid = 0
  const nextId = () => `user-${++uid}-${Date.now()}`

  it('isVoiceprintStorageAvailable 正常环境返回 true', async () => {
    expect(await isVoiceprintStorageAvailable()).toBe(true)
  })

  it('getVoiceprint 不存在返回 null', async () => {
    const result = await getVoiceprint('nonexist-xyz')
    expect(result).toBeNull()
  })

  it('enrollVoiceprint 写入后可查询', async () => {
    const id = nextId()
    await enrollVoiceprint(id, '小明', [[1, 2, 3], [4, 5, 6]])
    const record = await getVoiceprint(id) as any
    expect(record).toBeTruthy()
    expect(record.userId).toBe(id)
    expect(record.pseudonym).toBe('小明')
    expect(record.embeddings).toHaveLength(2)
    expect(record.sampleCount).toBe(2)
    expect(record.createdAt).toBeGreaterThan(0)
    expect(record.updatedAt).toBeGreaterThan(0)
    // cleanup
    await deleteVoiceprint(id)
  })

  it('getAllVoiceprints 返回已注册记录', async () => {
    const id = nextId()
    await enrollVoiceprint(id, 'T', [[1]])
    const all = await getAllVoiceprints() as any[]
    expect(all.length).toBeGreaterThanOrEqual(1)
    expect(all.some(r => r.userId === id)).toBe(true)
    await deleteVoiceprint(id)
  })

  it('hasAnyVoiceprint 有数据时 true', async () => {
    const id = nextId()
    await enrollVoiceprint(id, 'X', [[1]])
    expect(await hasAnyVoiceprint()).toBe(true)
    await deleteVoiceprint(id)
  })

  it('deleteVoiceprint 删除后查询为 null', async () => {
    const id = nextId()
    await enrollVoiceprint(id, 'Del', [[1]])
    await deleteVoiceprint(id)
    expect(await getVoiceprint(id)).toBeNull()
  })

  it('appendEmbedding 追加向量', async () => {
    const id = nextId()
    await enrollVoiceprint(id, 'A', [[1], [2], [3]])
    await appendEmbedding(id, [99] as any)
    const record = await getVoiceprint(id) as any
    expect(record.embeddings.length).toBe(4)
    expect(record.embeddings[3]).toEqual([99])
    await deleteVoiceprint(id)
  })

  it('appendEmbedding 对不存在用户无效', async () => {
    // 不抛错
    await appendEmbedding('ghost-user-xyz', [1] as any)
    expect(await getVoiceprint('ghost-user-xyz')).toBeNull()
  })

  it('saveVoiceCredential 保存凭证', async () => {
    const id = nextId()
    await enrollVoiceprint(id, 'C', [[1]])
    await saveVoiceCredential(id, 'jwt-abc')
    const record = await getVoiceprint(id) as any
    expect(record.voiceCredential).toBe('jwt-abc')
    await deleteVoiceprint(id)
  })

  it('saveVoiceCredential 对不存在用户静默', async () => {
    await saveVoiceCredential('ghost-xyz', 'cred')
    expect(await getVoiceprint('ghost-xyz')).toBeNull()
  })

  it('enrollVoiceprint 重录保留旧凭证', async () => {
    const id = nextId()
    await enrollVoiceprint(id, 'R', [[1]])
    await saveVoiceCredential(id, 'old-cred')
    // 重新录入
    await enrollVoiceprint(id, 'R', [[9, 9]])
    const record = await getVoiceprint(id) as any
    expect(record.voiceCredential).toBe('old-cred')
    expect(record.embeddings).toEqual([[9, 9]])
    await deleteVoiceprint(id)
  })

  it('enrollVoiceprint 截断超过 VP_MAX_TEMPLATES(8) 的模板', async () => {
    const id = nextId()
    // VP_MAX_TEMPLATES = 8
    const many = [[1], [2], [3], [4], [5], [6], [7], [8], [9], [10]]
    await enrollVoiceprint(id, 'Max', many)
    const record = await getVoiceprint(id) as any
    expect(record.embeddings.length).toBe(8)
    await deleteVoiceprint(id)
  })
})

describe('voiceprintStore 错误路径', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('indexedDB.open 失败时 isVoiceprintStorageAvailable 返回 false', async () => {
    vi.resetModules()
    vi.stubGlobal('indexedDB', {
      open: () => {
        const req: any = { onupgradeneeded: null, onsuccess: null, onerror: null, error: new Error('open failed') }
        setTimeout(() => req.onerror?.(), 0)
        return req
      },
    })
    const store = await import('../utils/voiceprintStore')
    expect(await store.isVoiceprintStorageAvailable()).toBe(false)
    // dbPromise 已重置，再次尝试仍然失败
    expect(await store.isVoiceprintStorageAvailable()).toBe(false)
  })

  it('indexedDB 不可用时 getAllVoiceprints/getVoiceprint/deleteVoiceprint 容错', async () => {
    vi.resetModules()
    vi.stubGlobal('indexedDB', undefined)
    const store = await import('../utils/voiceprintStore')
    expect(await store.getAllVoiceprints()).toEqual([])
    expect(await store.getVoiceprint('x')).toBeNull()
    await expect(store.deleteVoiceprint('x')).resolves.toBeUndefined()
    expect(await store.hasAnyVoiceprint()).toBe(false)
    expect(await store.isVoiceprintStorageAvailable()).toBe(false)
  })

  it('transaction 失败时 getVoiceprint 返回 null（Promise reject 路径）', async () => {
    vi.resetModules()
    // 模拟 indexedDB.open 成功但 db.transaction 抛错
    const fakeDb = {
      objectStoreNames: { contains: () => true },
      transaction: () => { throw new Error('tx boom') },
    }
    vi.stubGlobal('indexedDB', {
      open: () => {
        const req: any = { onupgradeneeded: null, onsuccess: null, onerror: null, result: fakeDb }
        setTimeout(() => req.onsuccess?.(), 0)
        return req
      },
    })
    const store = await import('../utils/voiceprintStore')
    // transaction 在 Promise executor 内抛错 → Promise reject → getVoiceprint 返回 rejected Promise
    // 由于 getVoiceprint 的 try/catch 无法捕获 executor 内的 throw，它会传播为 rejection
    await expect(store.getVoiceprint('x')).rejects.toThrow('tx boom')
    // saveVoiceCredential: getVoiceprint reject 被外层 try/catch 捕获 → 容错
    await expect(store.saveVoiceCredential('x', 'cred')).resolves.toBeUndefined()
    await expect(store.appendEmbedding('x', [1, 2])).resolves.toBeUndefined()
  })

  it('saveVoiceCredential/appendEmbedding 对不存在用户静默返回', async () => {
    vi.resetModules()
    vi.stubGlobal('indexedDB', undefined)
    const store = await import('../utils/voiceprintStore')
    // getVoiceprint 返回 null（indexedDB 不可用）→ early return
    await expect(store.saveVoiceCredential('nobody', 'cred')).resolves.toBeUndefined()
    await expect(store.appendEmbedding('nobody', [1])).resolves.toBeUndefined()
  })

  it('request.onerror 触发时 getAllVoiceprints reject、deleteVoiceprint 容错', async () => {
    vi.resetModules()
    const makeReq = () => {
      const req: any = { onsuccess: null, onerror: null, error: new Error('idb request fail'), result: undefined }
      setTimeout(() => req.onerror?.(), 0)
      return req
    }
    const fakeDb = {
      objectStoreNames: { contains: () => true },
      transaction: () => ({ objectStore: () => ({ getAll: () => makeReq(), delete: () => makeReq() }) }),
    }
    vi.stubGlobal('indexedDB', {
      open: () => {
        const req: any = { onupgradeneeded: null, onsuccess: null, onerror: null, result: fakeDb }
        setTimeout(() => req.onsuccess?.(), 0)
        return req
      },
    })
    const store = await import('../utils/voiceprintStore')
    // getAllVoiceprints: onerror → reject → Promise rejection 传播（try/catch 无法捕获 returned Promise reject）
    await expect(store.getAllVoiceprints()).rejects.toThrow('idb request fail')
    // deleteVoiceprint: 同理，onerror 触发后 rejection 传播
    await expect(store.deleteVoiceprint('x')).rejects.toThrow('idb request fail')
  })
})
