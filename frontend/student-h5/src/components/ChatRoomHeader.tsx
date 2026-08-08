import BoBoAvatar from './BoBoAvatar'
import { useTheme } from '../theme/ThemeProvider'

/** ChatRoom 顶部栏（FA-06，DOC-074：从 ChatRoom 神组件拆出，纯展示 + 回调，无内部状态） */
export default function ChatRoomHeader({
  muted,
  onToggleMute,
  onOpenSos,
  onOpenToolbox,
  onOpenSettings,
  onSwitchUser,
  onEnd,
}: {
  muted: boolean
  onToggleMute: () => void
  onOpenSos: () => void
  onOpenToolbox: () => void
  onOpenSettings: () => void
  onSwitchUser?: () => void
  onEnd: () => void
}) {
  const { theme } = useTheme()

  return (
    <header className="flex items-center justify-between px-4 lg:px-8 py-3 lg:py-4 bg-white/80 backdrop-blur border-b border-gray-100 shadow-sm">
      <div className="flex items-center gap-2 lg:gap-3">
        <BoBoAvatar size={24} colors={theme.bobo} />
        <span className="font-medium text-gray-800 lg:text-xl">{theme.companionName}</span>
      </div>
      <div className="flex items-center gap-2">
        {/* TTS 静音快捷按钮 */}
        <button
          onClick={onToggleMute}
          className={`p-2 lg:p-2.5 rounded-full transition-colors ${
            muted ? 'text-gray-300' : 'text-[var(--primary)]'
          }`}
          title={muted ? '开启语音' : '关闭语音'}
        >
          {muted ? (
            <svg className="w-5 h-5 lg:w-6 lg:h-6" fill="currentColor" viewBox="0 0 24 24">
              <path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"/>
            </svg>
          ) : (
            <svg className="w-5 h-5 lg:w-6 lg:h-6" fill="currentColor" viewBox="0 0 24 24">
              <path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/>
            </svg>
          )}
        </button>
        {/* SOS 常驻入口（design/36 §3.4：非埋藏在菜单里，暖色不制造焦虑） */}
        <button
          onClick={onOpenSos}
          className="px-3 py-1.5 lg:px-3.5 lg:py-2 rounded-full bg-rose-50 border border-rose-200 text-rose-500 hover:bg-rose-100 active:scale-95 transition-all"
          title="SOS 帮助"
        >
          <span className="text-sm lg:text-base font-semibold">🆘</span>
        </button>
        {/* 百宝箱入口（design/36 §3.1） */}
        <button
          onClick={onOpenToolbox}
          className="p-2 lg:p-2.5 rounded-full text-gray-400 hover:text-[var(--primary)] transition-colors"
          title="百宝箱"
        >
          <span className="text-lg lg:text-xl">🧰</span>
        </button>
        {/* 设置按钮 */}
        <button
          onClick={onOpenSettings}
          className="p-2 lg:p-2.5 rounded-full text-gray-400 hover:text-[var(--primary)] transition-colors"
          title="设置"
        >
          <svg className="w-5 h-5 lg:w-6 lg:h-6" fill="currentColor" viewBox="0 0 24 24">
            <path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/>
          </svg>
        </button>
        {/* 切换同学（与设置并排，共享 Pad 场景） */}
        {onSwitchUser && (
          <button
            onClick={onSwitchUser}
            className="flex items-center gap-1 px-3 py-1.5 lg:px-3.5 lg:py-2 rounded-full bg-orange-50 border border-orange-200 text-orange-600 hover:bg-orange-100 active:scale-95 transition-all"
            title="切换同学"
          >
            <span className="text-sm lg:text-base">🔄</span>
            <span className="text-xs lg:text-sm font-semibold">换人</span>
          </button>
        )}
        {/* 结束对话 */}
        <button
          onClick={onEnd}
          className="text-sm lg:text-base px-4 py-2 lg:px-6 lg:py-3 rounded-full border border-gray-200
            text-gray-500 hover:text-red-500 hover:border-red-200 transition-colors"
        >
          结束
        </button>
      </div>
    </header>
  )
}
