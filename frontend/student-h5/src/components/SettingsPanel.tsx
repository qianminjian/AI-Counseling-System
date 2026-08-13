/**
 * 设置面板（底部弹出）
 * - 主题切换（海洋/花园/彩虹）
 * - 音色选择（小星/波波老师/月亮/小太阳/大树/豆豆/方言）
 * - 语音开关
 * - 语音唤醒开关（design/28 §1.1；不支持/未配置时隐藏）
 * - 我的家庭码（家长绑定用）
 * - 切换同学（退出当前用户，二次确认）
 * 适合儿童操作：大图标 + 简短文字
 */
import { useState, useEffect, useRef } from 'react'
import { useTheme, THEMES } from '../theme/ThemeProvider'
import { useVoicePersona, VOICE_PERSONAS, NATIVE_DIALECT_IDS } from '../hooks/useVoicePersona'
import { api, getUser, getVoiceprintConfig } from '../api'
import { fillPath, ENDPOINTS } from '../endpoints'
import { hasAnyVoiceprint, deleteVoiceprint, clearRemoteVoiceprintMark } from '../utils/voiceprintStore'
// DC-007：声纹注册编排收敛（SPEC §21）
import { useVoiceEnrollment } from '../hooks/useVoiceEnrollment'
import { useMotionPreference } from '../hooks/useMotionPreference'
import VoiceLoginOverlay from './VoiceLoginOverlay'
import BoBoAvatar from './BoBoAvatar'
import ConfirmDialog from './ConfirmDialog'

export default function SettingsPanel({ open, onClose, muted, onToggleMute, wakeSupported = false, wakeOn = false, wakeAuthorized = true, onToggleWake, personaId: externalPersonaId, onPersonaChange, selectedDialect, onDialectChange, supportedDialects, hasNativeVoice }: {
  open: boolean
  onClose: () => void
  muted: boolean
  onToggleMute: () => void
  wakeSupported?: boolean
  wakeOn?: boolean
  // BUG-S-04-01（2026-08-12）：麦克风授权状态——拒绝授权后开关仍开时文案需区分「未授权」
  wakeAuthorized?: boolean
  onToggleWake?: () => void
  personaId?: string
  onPersonaChange?: (id: string) => void
  selectedDialect?: string | null
  onDialectChange?: (id: string) => void
  supportedDialects?: Record<string, { id: string; label: string }>
  hasNativeVoice?: boolean
}) {
  const { themeId, changeTheme } = useTheme()
  // 动效/触感开关（design/37 §4.3）：默认跟随系统“减弱动态效果”，可手动覆盖
  const motion = useMotionPreference()
  const internalPersona = useVoicePersona()
  // 优先使用外部传入的 persona 状态（与 ChatRoom TTS 共享同一份 state）
  const personaId = externalPersonaId ?? internalPersona.personaId
  const changePersona = onPersonaChange ?? internalPersona.changePersona
  // 方言：优先外部 props（ChatRoom 传入），否则用内部 hook
  const effSelectedDialect = onDialectChange ? selectedDialect : internalPersona.selectedDialect
  const effDialectChange = onDialectChange ?? internalPersona.changeDialect
  const effSupportedDialects = supportedDialects ?? internalPersona.supportedDialects
  const effHasNativeVoice = hasNativeVoice ?? internalPersona.hasNativeVoice
  // 方言是否启用：仅当选中“方言”音色（qiqiu）时
  const dialectActive = personaId === 'qiqiu'
  const [familyCode, setFamilyCode] = useState<string>((getUser()?.familyCode as string) || '')
  const [copied, setCopied] = useState(false)
  // AUD-017：copyCode 的“已复制”提示定时器挂 ref，卸载时清理避免 setState-after-unmount
  const copiedTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => () => { if (copiedTimerRef.current) clearTimeout(copiedTimerRef.current) }, [])
  const [hasVoiceprint, setHasVoiceprint] = useState(false)
  // DC-007：双模式重录编排（local/remote）收敛到 hook（SPEC §21）
  const { enroll } = useVoiceEnrollment()
  const [showEnroll, setShowEnroll] = useState(false)
  const [enrollError, setEnrollError] = useState('')
  const [confirmAction, setConfirmAction] = useState<'deleteVp' | null>(null)
  const [vpMode, setVpMode] = useState<'local' | 'remote'>('local')
  const [vpPrivacyNote, setVpPrivacyNote] = useState('声音信息只保存在这台设备上，不会上传到任何服务器')

  // 打开时检查声纹状态 + 获取 familyCode + 获取声纹模式
  useEffect(() => {
    if (open) {
      hasAnyVoiceprint().then(setHasVoiceprint)
      getVoiceprintConfig().then((cfg) => {
        setVpMode(cfg.mode)
        setVpPrivacyNote(cfg.privacyNote)
      }).catch((e) => console.warn('[SettingsPanel] 获取声纹配置失败:', e))
      if (!familyCode) {
        api(fillPath(ENDPOINTS.authMe.path, {})).then((data) => {
          if (data?.familyCode) setFamilyCode(data.familyCode)
        }).catch((e) => console.warn('[SettingsPanel] 获取家庭码失败:', e))
      }
    }
  }, [open])

  const copyCode = () => {
    navigator.clipboard?.writeText(familyCode).then(() => {
      setCopied(true)
      if (copiedTimerRef.current) clearTimeout(copiedTimerRef.current)
      copiedTimerRef.current = setTimeout(() => setCopied(false), 2000)
    })
  }

  if (!open) return null

  // 声纹采集覆盖层（重新录入）
  if (showEnroll) {
    const user = getUser()
    return (
      <VoiceLoginOverlay
        mode="enroll"
        onComplete={async (result) => {
          if (result.embeddings && result.embeddings.length > 0 && user?.userId) {
            try {
              // DC-007：双模式重录编排收敛（SPEC §21）——remote 传服务端 + 租户暂存，local 存 IndexedDB + 凭证签发
              await enroll(
                { userId: user.userId as string, pseudonym: (user.pseudonym || '') as string, embeddings: result.embeddings },
                vpMode
              )
              setHasVoiceprint(true)
              setEnrollError('')
            } catch (e) {
              console.error('[声纹重录] 存储失败:', e)
              setEnrollError('声音数据保存失败，请检查网络后重试')
            }
          }
          setShowEnroll(false)
        }}
        onCancel={() => setShowEnroll(false)}
      />
    )
  }

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center">
      {/* 遮罩 */}
      <div className="absolute inset-0 bg-black/30" onClick={onClose} />

      {/* 面板 */}
      <div className="relative w-full max-w-lg max-h-[85vh] overflow-y-auto rounded-t-3xl bg-white p-6 pb-10 shadow-2xl animate-slide-up">
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
                <BoBoAvatar size={24} colors={t.bobo} />
              </button>
            ))}
          </div>
        </section>

        {/* 音色选择（design/56：4+3 布局，“方言”特殊样式） */}
        <section className="mb-6">
          <h3 className="mb-3 text-sm font-semibold text-gray-500">🎵 选择声音</h3>
          <div className="grid grid-cols-4 gap-2.5">
            {Object.values(VOICE_PERSONAS).map((p) => {
              const isDialectPersona = p.id === 'qiqiu'
              const isSelected = personaId === p.id
              return (
                <button
                  key={p.id}
                  onClick={() => changePersona(p.id)}
                  className={`flex flex-col items-center gap-1 rounded-2xl border-2 p-2.5 transition-all active:scale-95 ${
                    isSelected
                      ? isDialectPersona
                        ? 'border-amber-400 bg-amber-50 shadow-md ring-2 ring-amber-200'
                        : 'border-[var(--primary)] bg-[var(--primary-light)] shadow-md'
                      : isDialectPersona
                        ? 'border-amber-200 bg-gradient-to-b from-amber-50 to-orange-50'
                        : 'border-gray-100 bg-gray-50'
                  }`}
                >
                  <span className="text-2xl">{p.emoji}</span>
                  <span className={`text-[11px] font-bold ${
                    isDialectPersona
                      ? 'bg-gradient-to-r from-amber-600 to-orange-500 bg-clip-text text-transparent'
                      : 'font-medium text-gray-700'
                  }`}>{p.name}</span>
                  <span className={`text-[9px] leading-tight ${
                    isDialectPersona ? 'text-amber-500' : 'text-gray-400'
                  }`}>{p.desc}</span>
                </button>
              )
            })}
          </div>
        </section>

        {/* 方言选择（仅“方言”音色选中时显示） */}
        {dialectActive && (
          <section className="mb-6">
            <h3 className="mb-3 text-sm font-semibold text-amber-600">🏠 选择方言</h3>
            {/* 方言类型选择（直接展示，无需开关） */}
            <div className="grid grid-cols-4 gap-2">
              {Object.values(effSupportedDialects).map((d) => {
                const isNative = NATIVE_DIALECT_IDS.includes(d.id)
                return (
                  <button
                    key={d.id}
                    onClick={() => effDialectChange(d.id)}
                    className={`rounded-xl border-2 px-2 py-2 text-xs font-medium transition-all active:scale-95 ${
                      effSelectedDialect === d.id
                        ? 'border-amber-400 bg-amber-50 text-amber-700'
                        : 'border-gray-100 bg-gray-50 text-gray-600'
                    }`}
                  >
                    {d.label}
                    {isNative && <span className="ml-0.5 text-[8px] text-amber-400">★</span>}
                  </button>
                )
              })}
            </div>
            {/* 原生方言提示（粤语/闽南话选中时显示） */}
            {effHasNativeVoice && (
              <div className="mt-3 flex items-center gap-2 rounded-2xl border-2 border-amber-100 bg-amber-50/50 p-3">
                <span className="text-sm">🎙️</span>
                <span className="text-xs text-amber-600">使用原生方言音色，无需额外设置</span>
              </div>
            )}
          </section>
        )}

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
                  {/* BUG-S-04-01：开关开但未授权时明确提示（原仅显示已开启，与实际状态矛盾） */}
                  {!wakeSupported ? '当前设备不支持' : wakeOn ? (wakeAuthorized ? '语音唤醒已开启' : '语音唤醒已开启（未授权麦克风）') : '语音唤醒已关闭'}
                </p>
                <p className="text-xs text-gray-400">
                  {!wakeSupported ? '需要支持麦克风的浏览器（HTTPS）' : wakeOn ? (wakeAuthorized ? '直接说“哈喽波波”就能叫我' : '需在对话页同意麦克风授权后生效') : '开启后说“哈喽波波”就能和我说话'}
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

        {/* 动效与触感（design/37 §4.3）：动画效果 + 触觉反馈开关 */}
        <section className="mb-4">
          <h3 className="mb-3 text-sm font-semibold text-gray-500">✨ 动效与触感</h3>
          <div className="flex flex-col gap-3">
            <button
              onClick={() => motion.setAnimationEnabled(!motion.animationEnabled)}
              data-testid="toggle-animation"
              className={`flex w-full items-center justify-between rounded-2xl border-2 p-4 transition-all active:scale-[0.98] ${
                motion.animationEnabled
                  ? 'border-[var(--primary)] bg-[var(--primary-light)]'
                  : 'border-gray-100 bg-gray-50'
              }`}
            >
              <div className="flex items-center gap-3">
                <span className="text-2xl">{motion.animationEnabled ? '🎞️' : '🖼️'}</span>
                <div className="text-left">
                  <p className="text-sm font-medium text-gray-700">
                    {motion.animationEnabled ? '动画效果已开启' : '动画效果已关闭'}
                  </p>
                  <p className="text-xs text-gray-400">
                    {motion.animationEnabled ? '波波和界面会有可爱动效' : '关闭后画面更安静、更省电'}
                  </p>
                </div>
              </div>
              <div className={`h-7 w-12 rounded-full p-1 transition-colors ${
                motion.animationEnabled ? 'bg-[var(--primary)]' : 'bg-gray-300'
              }`}>
                <div className={`h-5 w-5 rounded-full bg-white shadow transition-transform ${
                  motion.animationEnabled ? 'translate-x-5' : 'translate-x-0'
                }`} />
              </div>
            </button>
            <button
              onClick={() => motion.setHapticsEnabled(!motion.hapticsEnabled)}
              data-testid="toggle-haptics"
              className={`flex w-full items-center justify-between rounded-2xl border-2 p-4 transition-all active:scale-[0.98] ${
                motion.hapticsEnabled
                  ? 'border-[var(--primary)] bg-[var(--primary-light)]'
                  : 'border-gray-100 bg-gray-50'
              }`}
            >
              <div className="flex items-center gap-3">
                <span className="text-2xl">{motion.hapticsEnabled ? '📳' : '📴'}</span>
                <div className="text-left">
                  <p className="text-sm font-medium text-gray-700">
                    {motion.hapticsEnabled ? '触觉反馈已开启' : '触觉反馈已关闭'}
                  </p>
                  <p className="text-xs text-gray-400">
                    {motion.hapticsEnabled ? '按住说话时会轻轻震动' : '开启后按住说话会有轻微震动感'}
                  </p>
                </div>
              </div>
              <div className={`h-7 w-12 rounded-full p-1 transition-colors ${
                motion.hapticsEnabled ? 'bg-[var(--primary)]' : 'bg-gray-300'
              }`}>
                <div className={`h-5 w-5 rounded-full bg-white shadow transition-transform ${
                  motion.hapticsEnabled ? 'translate-x-5' : 'translate-x-0'
                }`} />
              </div>
            </button>
          </div>
        </section>
        
        {/* 声纹登录管理 */}
        <section className="mb-4">
          <h3 className="mb-3 text-sm font-semibold text-gray-500">🎤 声纹登录</h3>
          {enrollError && (
            <div className="mb-3 rounded-2xl border-2 border-red-100 bg-red-50 p-3 text-center">
              <p className="text-sm font-medium text-red-600">⚠️ {enrollError}</p>
              <button onClick={() => { setEnrollError(''); setShowEnroll(true) }} className="mt-2 rounded-xl bg-red-500 px-4 py-1.5 text-xs font-bold text-white active:scale-95 transition-all">重新录入</button>
            </div>
          )}
          {hasVoiceprint ? (
            <div className="flex w-full items-center justify-between rounded-2xl border-2 border-green-100 bg-green-50 p-4">
              <div className="flex items-center gap-3">
                <span className="text-2xl">✅</span>
                <div className="text-left">
                  <p className="text-sm font-medium text-gray-700">声纹已录入</p>
                  <p className="text-xs text-gray-400">登录页可用声音直接登录</p>
                </div>
              </div>
              <div className="flex gap-2">
                <button
                  onClick={() => setConfirmAction('deleteVp')}
                  className="rounded-xl bg-red-50 px-3 py-2 text-xs font-bold text-red-500 active:scale-95 transition-all"
                >
                  删除
                </button>
                <button
                  onClick={() => setShowEnroll(true)}
                  className="rounded-xl bg-[var(--primary)] px-3 py-2 text-xs font-bold text-white active:scale-95 transition-all"
                >
                  重新录入
                </button>
              </div>
            </div>
          ) : (
            <div className="rounded-2xl border-2 border-dashed border-[var(--primary)] bg-[var(--primary-light)] p-4 text-center">
              <p className="text-2xl mb-2">🎤</p>
              <p className="text-sm font-medium text-gray-700 mb-1">还没录入声纹</p>
              <p className="text-xs text-gray-400 mb-3">录入后登录时只要对波波说句话就能直接进入，<br />不用输秘密数字啦！</p>
              <button
                onClick={() => setShowEnroll(true)}
                className="rounded-xl bg-[var(--primary)] px-6 py-2.5 text-sm font-bold text-white active:scale-95 transition-all shadow-md"
              >
                现在录入 🎤
              </button>
            </div>
          )}
          <p className="mt-2 text-center text-[10px] text-gray-300">{vpPrivacyNote}</p>
        </section>

        {/* 我的家庭码（家长绑定用） */}
        {familyCode && (
          <section className="mb-4">
            <h3 className="mb-3 text-sm font-semibold text-gray-500">🏠 我的家庭码</h3>
            <div className="flex items-center justify-between rounded-2xl border-2 border-gray-100 bg-gray-50 p-4">
              <div>
                <p className="font-mono text-2xl font-bold tracking-[0.2em] text-[var(--primary)]">{familyCode}</p>
                <p className="mt-1 text-xs text-gray-400">告诉家长，用于绑定家长账号</p>
              </div>
              <button
                onClick={copyCode}
                className="rounded-xl bg-[var(--primary)] px-3 py-2 text-xs font-bold text-white active:scale-95 transition-all"
              >
                {copied ? '已复制 ✓' : '复制'}
              </button>
            </div>
          </section>
        )}

        {/* 关闭按钮 */}
        <button
          onClick={onClose}
          className="w-full rounded-2xl bg-[var(--primary)] py-3.5 text-center text-base font-bold text-white shadow-lg transition-all active:scale-[0.97]"
        >
          完成 ✓
        </button>
      </div>

      {/* 删除声纹二次确认 */}
      <ConfirmDialog
        open={confirmAction === 'deleteVp'}
        emoji="🗑️"
        title="真的要删掉声音钥匙吗？"
        message="删掉后就不能用声音登录啦，需要重新录入"
        confirmText="删掉"
        danger
        onConfirm={async () => {
          setConfirmAction(null)
          const user = getUser()
          if (user?.userId) {
            await deleteVoiceprint(user.userId as string)
            clearRemoteVoiceprintMark()
            setHasVoiceprint(false)
          }
        }}
        onCancel={() => setConfirmAction(null)}
      />
    </div>
  )
}
