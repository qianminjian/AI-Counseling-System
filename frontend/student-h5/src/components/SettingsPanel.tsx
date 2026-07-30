/**
 * 设置面板（底部弹出）
 * - 主题切换（海洋/花园/彩虹）
 * - 音色选择（小星/气球/月亮）
 * - 语音开关
 * - 语音唤醒开关（design/28 §1.1；不支持/未配置时隐藏）
 * 适合儿童操作：大图标 + 简短文字
 */
import { useTheme, THEMES } from '../theme/ThemeProvider'
import { useVoicePersona, VOICE_PERSONAS } from '../hooks/useVoicePersona'

export default function SettingsPanel({ open, onClose, muted, onToggleMute, wakeSupported = false, wakeOn = false, onToggleWake }) {
  const { themeId, changeTheme } = useTheme()
  const { personaId, changePersona } = useVoicePersona()

  if (!open) return null

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center">
      {/* 遮罩 */}
      <div className="absolute inset-0 bg-black/30" onClick={onClose} />

      {/* 面板 */}
      <div className="relative w-full max-w-lg rounded-t-3xl bg-white p-6 pb-10 shadow-2xl animate-slide-up">
        {/* 拖拽条 */}
        <div className="mx-auto mb-4 h-1.5 w-12 rounded-full bg-gray-200" />

        <h2 className="mb-5 text-center text-xl font-bold text-gray-800">
          ⚙️ 我的设置
        </h2>

        {/* 主题选择 */}
        <section className="mb-6">
          <h3 className="mb-3 text-sm font-semibold text-gray-500">🎨 选择主题</h3>
          <div className="grid grid-cols-3 gap-3">
            {Object.values(THEMES).map((t) => (
              <button
                key={t.id}
                onClick={() => changeTheme(t.id)}
                className={`flex flex-col items-center gap-1 rounded-2xl border-2 p-3 transition-all active:scale-95 ${
                  themeId === t.id
                    ? 'border-[var(--primary)] bg-[var(--primary-light)] shadow-md'
                    : 'border-gray-100 bg-gray-50'
                }`}
              >
                <span className="text-3xl">{t.emoji}</span>
                <span className="text-xs font-medium text-gray-700">{t.name}</span>
                <span className="text-2xl">{t.companion}</span>
              </button>
            ))}
          </div>
        </section>

        {/* 音色选择 */}
        <section className="mb-6">
          <h3 className="mb-3 text-sm font-semibold text-gray-500">🎵 选择声音</h3>
          <div className="grid grid-cols-3 gap-3">
            {Object.values(VOICE_PERSONAS).map((p) => (
              <button
                key={p.id}
                onClick={() => changePersona(p.id)}
                className={`flex flex-col items-center gap-1 rounded-2xl border-2 p-3 transition-all active:scale-95 ${
                  personaId === p.id
                    ? 'border-[var(--primary)] bg-[var(--primary-light)] shadow-md'
                    : 'border-gray-100 bg-gray-50'
                }`}
              >
                <span className="text-3xl">{p.emoji}</span>
                <span className="text-xs font-medium text-gray-700">{p.name}</span>
                <span className="text-[10px] text-gray-400">{p.desc}</span>
              </button>
            ))}
          </div>
        </section>

        {/* 语音开关 */}
        <section className="mb-4">
          <h3 className="mb-3 text-sm font-semibold text-gray-500">🔊 语音播报</h3>
          <button
            onClick={onToggleMute}
            className={`flex w-full items-center justify-between rounded-2xl border-2 p-4 transition-all active:scale-[0.98] ${
              !muted
                ? 'border-[var(--primary)] bg-[var(--primary-light)]'
                : 'border-gray-100 bg-gray-50'
            }`}
          >
            <div className="flex items-center gap-3">
              <span className="text-2xl">{muted ? '🔇' : '🔊'}</span>
              <div className="text-left">
                <p className="text-sm font-medium text-gray-700">
                  {muted ? '语音已关闭' : '语音已开启'}
                </p>
                <p className="text-xs text-gray-400">
                  {muted ? '只显示文字，不播放声音' : 'AI 回复会自动读给你听'}
                </p>
              </div>
            </div>
            {/* 开关指示 */}
            <div className={`h-7 w-12 rounded-full p-1 transition-colors ${
              !muted ? 'bg-[var(--primary)]' : 'bg-gray-300'
            }`}>
              <div className={`h-5 w-5 rounded-full bg-white shadow transition-transform ${
                !muted ? 'translate-x-5' : 'translate-x-0'
              }`} />
            </div>
          </button>
        </section>

        {/* 语音唤醒开关（design/28 §1.1；始终显示，不支持时灰显提示） */}
        <section className="mb-4">
          <h3 className="mb-3 text-sm font-semibold text-gray-500">🐬 语音唤醒</h3>
          <button
            onClick={wakeSupported ? onToggleWake : undefined}
            disabled={!wakeSupported}
            className={`flex w-full items-center justify-between rounded-2xl border-2 p-4 transition-all active:scale-[0.98] ${
              !wakeSupported
                ? 'border-gray-100 bg-gray-50 opacity-50 cursor-not-allowed'
                : wakeOn
                  ? 'border-[var(--primary)] bg-[var(--primary-light)]'
                  : 'border-gray-100 bg-gray-50'
            }`}
          >
            <div className="flex items-center gap-3">
              <span className="text-2xl">{!wakeSupported ? '🚫' : wakeOn ? '🎙️' : '💤'}</span>
              <div className="text-left">
                <p className="text-sm font-medium text-gray-700">
                  {!wakeSupported ? '当前设备不支持' : wakeOn ? '语音唤醒已开启' : '语音唤醒已关闭'}
                </p>
                <p className="text-xs text-gray-400">
                  {!wakeSupported ? '需要支持麦克风和 AudioWorklet 的浏览器' : wakeOn ? '直接说"哈喽波波"就能叫我' : '开启后说"哈喽波波"就能和我说话'}
                </p>
              </div>
            </div>
            <div className={`h-7 w-12 rounded-full p-1 transition-colors ${
              wakeOn && wakeSupported ? 'bg-[var(--primary)]' : 'bg-gray-300'
            }`}>
              <div className={`h-5 w-5 rounded-full bg-white shadow transition-transform ${
                wakeOn && wakeSupported ? 'translate-x-5' : 'translate-x-0'
              }`} />
            </div>
          </button>
        </section>

        {/* 关闭按钮 */}
        <button
          onClick={onClose}
          className="w-full rounded-2xl bg-[var(--primary)] py-3.5 text-center text-base font-bold text-white shadow-lg transition-all active:scale-[0.97]"
        >
          完成 ✓
        </button>
      </div>
    </div>
  )
}
