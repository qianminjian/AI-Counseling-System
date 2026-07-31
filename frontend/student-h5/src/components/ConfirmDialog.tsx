/**
 * 通用二次确认弹窗（儿童友好）
 * 用于关键操作防误触：注册提交 / 删除声纹 / 切换同学 / 退出等
 * - danger=true 时确认按钮为红色（不可逆操作）
 * - children 可放摘要卡片等自定义内容
 */
export default function ConfirmDialog({
  open,
  emoji = '🤔',
  title,
  message,
  confirmText = '确认',
  cancelText = '我点错了',
  danger = false,
  onConfirm,
  onCancel,
  children,
}: {
  open: boolean
  emoji?: string
  title: string
  message?: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
  onConfirm: () => void
  onCancel: () => void
  children?: any
}) {
  if (!open) return null

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-6">
      {/* 遮罩：点击遮罩视为取消（儿童防误触） */}
      <div className="absolute inset-0 bg-black/40" onClick={onCancel} />
      <div
        className="relative w-full max-w-xs rounded-3xl bg-white p-6 text-center shadow-2xl animate-slide-up"
        onClick={(e) => e.stopPropagation()}
      >
        <span className="text-5xl">{emoji}</span>
        <h3 className="mt-3 text-lg font-bold text-gray-800">{title}</h3>
        {message && (
          <p className="mt-2 text-sm leading-relaxed text-gray-500">{message}</p>
        )}
        {children}
        <div className="mt-5 flex gap-3">
          <button
            onClick={onCancel}
            className="flex-1 rounded-full border border-gray-200 py-3 text-sm text-gray-500 transition-all hover:bg-gray-50 active:scale-95"
          >
            {cancelText}
          </button>
          <button
            onClick={onConfirm}
            className={`flex-1 rounded-full py-3 text-sm font-medium text-white shadow-md transition-all active:scale-95 ${
              danger ? 'bg-red-500' : ''
            }`}
            style={danger ? undefined : { background: 'var(--primary)' }}
          >
            {confirmText}
          </button>
        </div>
      </div>
    </div>
  )
}
