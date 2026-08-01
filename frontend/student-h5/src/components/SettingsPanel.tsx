/**
 * 设置面板（底部弹出）
 * - 主题切换（海洋/花园/彩虹）
 * - 音色选择（小星/气球/月亮）
 * - 语音开关
 * - 语音唤醒开关（design/28 §1.1；不支持/未配置时隐藏）
 * - 我的家庭码（家长绑定用）
 * - 切换同学（退出当前用户，二次确认）
 * 适合儿童操作：大图标 + 简短文字
 */
import { useState, useEffect } from 'react'
import { useTheme, THEMES } from '../theme/ThemeProvider'
import { useVoicePersona, VOICE_PERSONAS } from '../hooks/useVoicePersona'
import { api, getUser, issueVoiceCredential, getVoiceprintConfig, remoteVoiceprintEnroll } from '../api'
import { hasAnyVoiceprint, deleteVoiceprint, enrollVoiceprint, saveVoiceCredential } from '../utils/voiceprintStore'
import VoiceLoginOverlay from './VoiceLoginOverlay'
import ConfirmDialog from './ConfirmDialog'

export default function SettingsPanel({ open, onClose, muted, onToggleMute, wakeSupported = false, wakeOn = false, onToggleWake, personaId: externalPersonaId, onPersonaChange, dialectEnabled = false, onToggleDialect, selectedDialect, onDialectChange, supportedDialects, currentPersonaDialectCapable = false }: {
  open: boolean
  onClose: () => void
  muted: boolean
  onToggleMute: () => void
  wakeSupported?: boolean
  wakeOn?: boolean
  onToggleWake?: () => void
  personaId?: string
  onPersonaChange?: (id: string) => void
  dialectEnabled?: boolean
  onToggleDialect?: (enabled: boolean) => void
  selectedDialect?: string | null
  onDialectChange?: (id: string) => void
  supportedDialects?: Record<string, { id: string; label: string }>
  currentPersonaDialectCapable?: boolean
}) {
  const { themeId, changeTheme } = useTheme()
  const internalPersona = useVoicePersona()
  // 优先使用外部传入的 persona 状态（与 ChatRoom TTS 共享同一份 state）
  const personaId = externalPersonaId ?? internalPersona.personaId
  const changePersona = onPersonaChange ?? internalPersona.changePersona
  const [familyCode, setFamilyCode] = useState<string>((getUser()?.familyCode as string) || '')
  const [copied, setCopied] = useState(false)
  const [hasVoiceprint, setHasVoiceprint] = useState(false)
  const [showEnroll, setShowEnroll] = useState(false)
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
      }).catch(() => {})
      if (!familyCode) {
        api('/auth/me').then((data) => {
          if (data?.familyCode) setFamilyCode(data.familyCode)
        }).catch(() => {})
      }
    }
  }, [open])

  const copyCode = () => {
    navigator.clipboard?.writeText(familyCode).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
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
              if (vpMode === 'remote') {
                // remote 模式：embedding 传服务端存储
                await remoteVoiceprintEnroll(result.embeddings)
              } else {
                // local 模式：存 IndexedDB + 签发设备凭证
                await enrollVoiceprint(user.userId as string, (user.pseudonym || '') as string, result.embeddings)
                try {
                  const cred = await issueVoiceCredential()
                  await saveVoiceCredential(user.userId as string, cred)
                } catch (e) {
                  console.warn('[声纹重录] 设备凭证签发失败（不影响本次录入）:', e)
                }
              }
              setHasVoiceprint(true)
            } catch (e) {
              console.warn('[声纹重录] 存储失败:', e)
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
                <span className="text-2xl">{t.companion}</span>
              </button>
            ))}
          </div>
        </section>

        {/* 音色选择（design/56：4+3 布局） */}
        <section className="mb-6">
          <h3 className="mb-3 text-sm font-semibold text-gray-500">🎵 选择声音</h3>
          <div className="grid grid-cols-4 gap-2.5">
            {Object.values(VOICE_PERSONAS).map((p) => (
              <button
                key={p.id}
                onClick={() => changePersona(p.id)}
                className={`flex flex-col items-center gap-1 rounded-2xl border-2 p-2.5 transition-all active:scale-95 ${
                  personaId === p.id
                    ? 'border-[var(--primary)] bg-[var(--primary-light)] shadow-md'
                    : 'border-gray-100 bg-gray-50'
                }`}
              >
                <span className="text-2xl">{p.emoji}</span>
                <span className="text-[11px] font-medium text-gray-700">{p.name}</span>
                <span className="text-[9px] text-gray-400 leading-tight">{p.desc}</span>
              </button>
            ))}
          </div>
        </section>

        {/* 方言选择（design/56 §三：仅 dialectCapable 音色显示） */}
        {currentPersonaDialectCapable && (
          <section className="mb-6">
            <h3 className="mb-3 text-sm font-semibold text-gray-500">🏠 用家乡话聊天</h3>
            {/* 方言开关 */}
            <button
              onClick={() => onToggleDialect?.(!dialectEnabled)}
              className={`flex w-full items-center justify-between rounded-2xl border-2 p-4 transition-all active:scale-[0.98] ${
                dialectEnabled
                  ? 'border-[var(--primary)] bg-[var(--primary-light)]'
                  : 'border-gray-100 bg-gray-50'
              }`}
            >
              <div className="flex items-center gap-3">
                <span className="text-2xl">{dialectEnabled ? '🗣️' : '💬'}</span>
                <div className="text-left">
                  <p className="text-sm font-medium text-gray-700">
                    {dialectEnabled ? '方言已开启' : '方言已关闭'}
                  </p>
                  <p className="text-xs text-gray-400">
                    {dialectEnabled ? '波波会用家乡话和你说话' : '开启后可以用方言聊天'}
                  </p>
                </div>
              </div>
              <div className={`h-7 w-12 rounded-full p-1 transition-colors ${
                dialectEnabled ? 'bg-[var(--primary)]' : 'bg-gray-300'
              }`}>
                <div className={`h-5 w-5 rounded-full bg-white shadow transition-transform ${
                  dialectEnabled ? 'translate-x-5' : 'translate-x-0'
                }`} />
              </div>
            </button>
            {/* 方言类型选择（开启后展开） */}
            {dialectEnabled && supportedDialects && (
              <div className="mt-3 grid grid-cols-4 gap-2">
                {Object.values(supportedDialects).map((d) => (
                  <button
                    key={d.id}
                    onClick={() => onDialectChange?.(d.id)}
                    className={`rounded-xl border-2 px-2 py-2 text-xs font-medium transition-all active:scale-95 ${
                      selectedDialect === d.id
                        ? 'border-[var(--primary)] bg-[var(--primary-light)] text-[var(--primary)]'
                        : 'border-gray-100 bg-gray-50 text-gray-600'
                    }`}
                  >
                    {d.label}
                  </button>
                ))}
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
                  {!wakeSupported ? '当前设备不支持' : wakeOn ? '语音唤醒已开启' : '语音唤醒已关闭'}
                </p>
                <p className="text-xs text-gray-400">
                  {!wakeSupported ? '需要支持麦克风的浏览器（HTTPS）' : wakeOn ? '直接说“哈喽波波”就能叫我' : '开启后说“哈喽波波”就能和我说话'}
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
        
        {/* 声纹登录管理 */}
        <section className="mb-4">
          <h3 className="mb-3 text-sm font-semibold text-gray-500">🎤 声纹登录</h3>
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
            setHasVoiceprint(false)
          }
        }}
        onCancel={() => setConfirmAction(null)}
      />
    </div>
  )
}
