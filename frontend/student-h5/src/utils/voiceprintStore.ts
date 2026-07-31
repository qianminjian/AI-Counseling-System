/**
 * 声纹模板 IndexedDB 存储（纯本地，不出设备）
 *
 * 数据结构：
 * {
 *   userId: string,          // 用户 ID（主键）
 *   pseudonym: string,       // 昵称（辅助索引，用于显示）
 *   embeddings: number[][],  // 多段 embedding 向量（序列化后存 JSON）
 *   sampleCount: number,     // 当前模板数
 *   voiceCredential?: string, // 后端签发的设备登录凭证（声纹匹配后凭其换正式 token）
 *   createdAt: number,       // 首次注册时间戳
 *   updatedAt: number,       // 最后更新时间戳
 * }
 *
 * 隐私说明：
 * - 仅存 256-dim 特征向量，不存原始音频
 * - IndexedDB 同源策略保护
 * - 用户可随时通过设置面板删除
 */
import { VP_DB_NAME, VP_DB_VERSION, VP_STORE_NAME, VP_MAX_TEMPLATES } from '../config/voiceprint'

let dbPromise = null

/** 获取/创建 IndexedDB 实例（单例） */
function getDB() {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      if (typeof indexedDB === 'undefined') {
        reject(new Error('IndexedDB 不可用'))
        return
      }
      const request = indexedDB.open(VP_DB_NAME, VP_DB_VERSION)
      request.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result
        if (!db.objectStoreNames.contains(VP_STORE_NAME)) {
          const store = db.createObjectStore(VP_STORE_NAME, { keyPath: 'userId' })
          store.createIndex('pseudonym', 'pseudonym', { unique: false })
        }
      }
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => {
        dbPromise = null
        reject(request.error)
      }
    })
  }
  return dbPromise
}

/**
 * 查询所有已注册声纹（登录时遍历比对）
 * @returns {Promise<Array>} 声纹记录列表
 */
export async function getAllVoiceprints() {
  try {
    const db = await getDB()
    return new Promise((resolve, reject) => {
      const tx = db.transaction(VP_STORE_NAME, 'readonly')
      const store = tx.objectStore(VP_STORE_NAME)
      const request = store.getAll()
      request.onsuccess = () => resolve(request.result || [])
      request.onerror = () => reject(request.error)
    })
  } catch {
    return []
  }
}

/**
 * 按 userId 查询声纹
 * @param {string} userId
 * @returns {Promise<object|null>}
 */
export async function getVoiceprint(userId) {
  try {
    const db = await getDB()
    return new Promise((resolve, reject) => {
      const tx = db.transaction(VP_STORE_NAME, 'readonly')
      const store = tx.objectStore(VP_STORE_NAME)
      const request = store.get(userId)
      request.onsuccess = () => resolve(request.result || null)
      request.onerror = () => reject(request.error)
    })
  } catch {
    return null
  }
}

/**
 * 注册声纹（首次写入）
 * @param {string} userId
 * @param {string} pseudonym
 * @param {number[][]} embeddings - 多段 embedding 向量
 */
export async function enrollVoiceprint(userId, pseudonym, embeddings) {
  const db = await getDB()
  const existing = await getVoiceprint(userId) as any
  const now = Date.now()
  const record = {
    userId,
    pseudonym,
    embeddings: embeddings.slice(0, VP_MAX_TEMPLATES),
    sampleCount: embeddings.length,
    // 重录时保留旧凭证（仍有效），随后签发成功会覆盖
    ...(existing?.voiceCredential ? { voiceCredential: existing.voiceCredential } : {}),
    createdAt: existing?.createdAt || now,
    updatedAt: now,
  }
  return new Promise((resolve, reject) => {
    const tx = db.transaction(VP_STORE_NAME, 'readwrite')
    const store = tx.objectStore(VP_STORE_NAME)
    const request = store.put(record)
    request.onsuccess = () => resolve(record)
    request.onerror = () => reject(request.error)
  })
}

/**
 * 保存后端签发的设备登录凭证（声纹录入成功后调用）
 * @param {string} userId
 * @param {string} credential - 后端 /auth/voice-credential 签发的凭证 JWT
 */
export async function saveVoiceCredential(userId, credential) {
  try {
    const existing = await getVoiceprint(userId) as any
    if (!existing) return
    const db = await getDB()
    const updated = { ...existing, voiceCredential: credential, updatedAt: Date.now() }
    return new Promise((resolve, reject) => {
      const tx = db.transaction(VP_STORE_NAME, 'readwrite')
      const store = tx.objectStore(VP_STORE_NAME)
      const request = store.put(updated)
      request.onsuccess = () => resolve(updated)
      request.onerror = () => reject(request.error)
    })
  } catch {
    // 凭证保存失败不影响声纹主流程（下次可在设置中重录补发）
  }
}

/**
 * 追加 embedding（自适应更新：成功登录后追加最新样本）
 * 滑动窗口保留最近 VP_MAX_TEMPLATES 个
 * @param {string} userId
 * @param {number[]} embedding - 单段 embedding
 */
export async function appendEmbedding(userId, embedding) {
  try {
    const existing = await getVoiceprint(userId) as any
    if (!existing) return
    const db = await getDB()
    const embeddings = [...(existing.embeddings as any[]), embedding]
    // 滑动窗口：保留最近 N 个
    const trimmed = embeddings.slice(-VP_MAX_TEMPLATES)
    const updated = {
      ...existing,
      embeddings: trimmed,
      sampleCount: trimmed.length,
      updatedAt: Date.now(),
    }
    return new Promise((resolve, reject) => {
      const tx = db.transaction(VP_STORE_NAME, 'readwrite')
      const store = tx.objectStore(VP_STORE_NAME)
      const request = store.put(updated)
      request.onsuccess = () => resolve(updated)
      request.onerror = () => reject(request.error)
    })
  } catch {
    // 追加失败不影响主流程
  }
}

/**
 * 删除指定用户声纹
 * @param {string} userId
 */
export async function deleteVoiceprint(userId) {
  try {
    const db = await getDB()
    return new Promise<void>((resolve, reject) => {
      const tx = db.transaction(VP_STORE_NAME, 'readwrite')
      const store = tx.objectStore(VP_STORE_NAME)
      const request = store.delete(userId)
      request.onsuccess = () => resolve()
      request.onerror = () => reject(request.error)
    })
  } catch {
    // ignore
  }
}

/**
 * 检查设备是否有任何已注册声纹（决定是否显示声纹入口）
 * @returns {Promise<boolean>}
 */
export async function hasAnyVoiceprint() {
  const all = await getAllVoiceprints() as any[]
  return all.length > 0
}

/**
 * 检查 IndexedDB 是否可用（隐私模式下可能不可用）
 * @returns {Promise<boolean>}
 */
export async function isVoiceprintStorageAvailable() {
  try {
    await getDB()
    return true
  } catch {
    return false
  }
}
