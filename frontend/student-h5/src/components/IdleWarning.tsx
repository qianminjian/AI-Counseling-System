import BoBoAvatar from './BoBoAvatar'

/**
 * 无操作超时警告卡（共享 Pad 隐私保护）
 * 5 分钟无操作后全屏弹出，60 秒倒计时；
 * 点「我还在！」继续用，倒计时归零自动退出回登录页
 */
export default function IdleWarning({ secondsLeft, onStay }: { secondsLeft: number; onStay: () => void }) {
  return (
    <div className="fixed inset-0 z-[70] flex items-center justify-center bg-black/50 p-6">
      <div className="w-full max-w-xs rounded-3xl bg-white p-7 text-center shadow-2xl animate-slide-up">
        <span className="inline-block float-companion"><BoBoAvatar size={60} /></span>
        <h3 className="mt-3 text-xl font-bold text-gray-800">你还在吗？</h3>
        <p className="mt-2 text-sm leading-relaxed text-gray-500">
          波波等你哦～<br />
          <strong className="text-lg text-red-500">{secondsLeft}</strong> 秒后我就先睡啦 💤
        </p>
        <button
          onClick={onStay}
          className="mt-5 w-full rounded-full py-3.5 text-base font-bold text-white shadow-lg transition-all active:scale-95"
          style={{ background: 'var(--primary)' }}
        >
          我还在！
        </button>
        <p className="mt-3 text-[11px] text-gray-300">离开的话不用管，我会自己退出保护你的小秘密</p>
      </div>
    </div>
  )
}
