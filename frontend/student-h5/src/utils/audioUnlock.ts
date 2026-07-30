/**
 * 全局音频解锁（必须在用户手势同步调用栈内调用）
 * 
 * 问题：Safari（iPad）/Firefox 等浏览器要求 audio.play() 必须在用户手势内触发。
 * 如果经过异步 API 调用后再 play()，浏览器不再认为处于"用户激活"状态 → 自动播放被拒。
 * 
 * 方案：在"开始聊天"按钮点击时立即调用 unlockAudio()，预创建并激活 AudioContext + Audio 元素。
 * 后续 ChatRoom 挂载后使用同一实例即可正常播放。
 */

let audioCtx = null
let audioEl = null
let unlocked = false

/** 获取全局 AudioContext（复用，避免重复创建） */
export function getGlobalAudioContext() {
  if (!audioCtx) {
    audioCtx = new (window.AudioContext || window.webkitAudioContext)()
  }
  return audioCtx
}

/** 获取全局 Audio 元素（复用，避免重复创建） */
export function getGlobalAudioElement() {
  if (!audioEl) {
    audioEl = new Audio()
    audioEl.preload = 'auto'
    audioEl.playsInline = true
    audioEl.setAttribute('playsinline', '')
    audioEl.setAttribute('webkit-playsinline', '')
  }
  return audioEl
}

/**
 * 在用户手势中调用，解锁浏览器音频自动播放限制。
 * 幂等：多次调用安全，只有首次实际执行解锁。
 */
export function unlockAudio() {
  if (unlocked) return
  unlocked = true

  // 1. 解锁 AudioContext
  try {
    const ctx = getGlobalAudioContext()
    if (ctx.state === 'suspended') {
      ctx.resume()
    }
    const buffer = ctx.createBuffer(1, 1, 22050)
    const source = ctx.createBufferSource()
    source.buffer = buffer
    source.connect(ctx.destination)
    source.start(0)
  } catch { /* ignore */ }

  // 2. 预热 Audio 元素（静音 play → pause，让浏览器记住此元素已被用户手势激活）
  const audio = getGlobalAudioElement()
  const silentWav = 'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA='
  audio.src = silentWav
  audio.volume = 0
  audio.play().then(() => {
    audio.pause()
    audio.volume = 1
    audio.currentTime = 0
  }).catch(() => {
    audio.volume = 1
  })

  // 3. 预热 speechSynthesis（部分安卓/iOS 浏览器需用户手势触发）
  if ('speechSynthesis' in window) {
    try {
      const warm = new SpeechSynthesisUtterance('')
      warm.volume = 0
      window.speechSynthesis.speak(warm)
      window.speechSynthesis.cancel()
    } catch { /* ignore */ }
  }
}

/** 是否已解锁 */
export function isAudioUnlocked() {
  return unlocked
}
