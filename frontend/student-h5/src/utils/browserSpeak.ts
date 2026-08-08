/**
 * 浏览器 speechSynthesis 降级朗读（TTS 降级链最末一环）
 *
 * F5（doing/78 §12）：三处自实现（useTtsPlayer / VoiceLoginOverlay / RelaxationExercises）
 * 各自重写 cancel→utter→zhVoice 三段，收敛为单一实现——统一中文语音选择、人设音高/语速
 * profile、onEnd 语义。
 *
 * 用法：
 *   const ok = browserSpeak(text, { rate: 0.9, persona: 'bobo', onEnd: () => {} })
 *   // ok=false 表示浏览器 TTS 不可用，调用方走自己的兜底（如显示字幕）
 *   stopBrowserSpeak()  // 停止播放（组件卸载/切换时）
 */

/** 人设音色参数（speechSynthesis 无法选音色，用 pitch/rate 区分人设） */
const PERSONA_VOICE_PROFILES = {
  xiaoxing: { pitch: 1.1, rateScale: 1.0 },   // 小星：温暖大姐姐
  bobo: { pitch: 1.05, rateScale: 0.95 },     // 波波老师：温柔女老师
  qiqiu: { pitch: 1.4, rateScale: 1.1 },      // 方言：活泼俘皮，音调高、语速快
  yueliang: { pitch: 1.0, rateScale: 0.9 },   // 月亮：温柔轻语，语速慢
  xiaotaiyang: { pitch: 0.7, rateScale: 1.0 },// 小太阳：阳光大哥哥，低音调模拟男声
  dashu: { pitch: 0.6, rateScale: 0.95 },     // 大树：暖心大叔，低沉稳重
  doudou: { pitch: 1.5, rateScale: 1.1 },     // 豆豆：顽皮男孩，音调高语速快
}

/**
 * 用浏览器 speechSynthesis 朗读（后端 TTS 不可用时的降级，按人设调整音高语速）
 * @param text 要朗读的文本
 * @param opts.rate 语速倍率（在人设 rateScale 之上叠加）
 * @param opts.persona 人设音色 id（见 PERSONA_VOICE_PROFILES）
 * @param opts.onEnd 播放结束/失败回调（含浏览器 TTS 不可用场景）
 * @returns 是否成功启动播放（false = 浏览器 TTS 不可用）
 */
export function browserSpeak(
  text: string,
  { rate = 1.0, persona = 'xiaoxing', onEnd }: { rate?: number; persona?: string; onEnd?: () => void } = {},
): boolean {
  if (!('speechSynthesis' in window)) { onEnd?.(); return false }
  try {
    window.speechSynthesis.cancel()
    const utter = new SpeechSynthesisUtterance(text)
    const profile = PERSONA_VOICE_PROFILES[persona] || PERSONA_VOICE_PROFILES.xiaoxing
    utter.lang = 'zh-CN'
    utter.rate = Math.max(0.5, Math.min(2, rate * profile.rateScale))
    utter.pitch = profile.pitch
    // 优先选中文语音
    const voices = window.speechSynthesis.getVoices()
    const zhVoice = voices.find(v => v.lang.startsWith('zh'))
    if (zhVoice) utter.voice = zhVoice
    utter.onend = () => onEnd?.()
    utter.onerror = () => onEnd?.()
    window.speechSynthesis.speak(utter)
    return true
  } catch {
    onEnd?.()
    return false
  }
}

/** 停止浏览器 TTS 播放（幂等，无 speechSynthesis 时静默） */
export function stopBrowserSpeak() {
  if ('speechSynthesis' in window) {
    try { window.speechSynthesis.cancel() } catch { /* ignore */ }
  }
}
