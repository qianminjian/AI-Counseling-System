import { useState } from 'react'

const FACES = [
  { rating: 1, emoji: '😢', label: '不太好' },
  { rating: 2, emoji: '😐', label: '一般般' },
  { rating: 3, emoji: '🙂', label: '还不错' },
  { rating: 4, emoji: '😊', label: '挺好的' },
  { rating: 5, emoji: '🥰', label: '特别好' },
]

/**
 * 结束会话满意度评价弹窗（儿童友好）
 * 可选评价后关闭，也可跳过；点「再聊一会儿」返回聊天（结束前的反悔出口）
 */
export default function SatisfactionDialog({ onSubmit, onSkip, onResume }: { onSubmit: (rating: number | null, comment?: string) => void; onSkip: () => void; onResume?: () => void }) {
  // FE-003：选中评分显式类型（此前 useState(null) 推断为 null → setSelected(f.rating) 报 TS2345）
  const [selected, setSelected] = useState<number | null>(null)
  const [comment, setComment] = useState('')

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="bg-white rounded-3xl p-6 lg:p-8 w-full max-w-sm shadow-2xl">
        <div className="text-center mb-6">
          <div className="text-4xl mb-2">💬</div>
          <h3 className="text-lg font-medium text-gray-800">今天的聊天对你有帮助吗？</h3>
          <p className="text-sm text-gray-400 mt-1">你的感受很重要（可以不选哦）</p>
        </div>

        {/* 表情评分 */}
        <div className="flex justify-center gap-2 mb-6">
          {FACES.map((f) => (
            <button
              key={f.rating}
              onClick={() => setSelected(f.rating)}
              className={`flex flex-col items-center gap-1 p-2 rounded-xl transition-all
                ${selected === f.rating
                  ? 'bg-amber-50 scale-110 shadow-sm ring-2 ring-amber-300'
                  : 'hover:bg-gray-50 active:scale-95'
                }`}
            >
              <span className="text-2xl lg:text-3xl">{f.emoji}</span>
              <span className="text-[10px] text-gray-500">{f.label}</span>
            </button>
          ))}
        </div>

        {/* 可选留言 */}
        {selected && (
          <textarea
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder="想说的话（选填）"
            maxLength={100}
            className="w-full p-3 border border-gray-200 rounded-xl text-sm resize-none
              focus:outline-none focus:ring-2 focus:ring-amber-200 mb-4"
            rows={2}
          />
        )}

        {/* 按钮 */}
        <div className="flex gap-3">
          <button
            onClick={onSkip}
            className="flex-1 py-3 rounded-full border border-gray-200 text-gray-500 text-sm
              hover:bg-gray-50 active:scale-95 transition-all"
          >
            跳过
          </button>
          <button
            onClick={() => onSubmit(selected, comment || undefined)}
            disabled={!selected}
            className={`flex-1 py-3 rounded-full text-white text-sm font-medium transition-all
              ${selected ? 'active:scale-95 shadow-md' : 'bg-gray-300 cursor-not-allowed'}`}
            style={selected ? { background: 'var(--primary)' } : undefined}
          >
            提交
          </button>
        </div>

        {/* 反悔出口：返回聊天（结束对话的二次确认） */}
        {onResume && (
          <button
            onClick={onResume}
            className="mt-4 w-full text-center text-sm text-gray-400 underline underline-offset-4 hover:text-gray-600 transition-colors"
          >
            💬 还想说说话？再聊一会儿
          </button>
        )}
      </div>
    </div>
  )
}
