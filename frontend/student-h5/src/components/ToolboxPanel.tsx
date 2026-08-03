import { useEffect, useState } from 'react'
import { fetchToolboxTools } from '../api/toolboxApi'
import type { ToolboxTool } from '../api/toolboxApi'
import ToolPractice from './ToolPractice'

/**
 * 百宝箱面板（F-2，design/36 §3.1 信息架构）
 *
 * - 挂载拉取工具清单（后端按年级过滤）
 * - 卡片点击 → ToolPractice 练习流程（前后心情记录）
 * - 接口失败（弱网/离线）→ 温和兜底提示，不白屏（儿童产品的错误呈现原则）
 */
export default function ToolboxPanel({ onBack }: { onBack: () => void }) {
  const [tools, setTools] = useState<ToolboxTool[]>([])
  const [loadError, setLoadError] = useState(false)
  const [activeTool, setActiveTool] = useState<ToolboxTool | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchToolboxTools()
      .then((list) => {
        if (!cancelled) setTools(list)
      })
      .catch(() => {
        if (!cancelled) setLoadError(true)
      })
    return () => {
      cancelled = true
    }
  }, [])

  if (activeTool) {
    return <ToolPractice tool={activeTool} onClose={() => setActiveTool(null)} />
  }

  return (
    <div className="fixed inset-0 z-40 bg-sky-50 overflow-y-auto">
      <div className="max-w-md mx-auto p-6 pb-16">
        <button type="button" onClick={onBack} className="text-slate-500 font-bold mb-4">
          ← 返回
        </button>
        <h1 className="text-2xl font-bold text-slate-700 text-center mb-1">百宝箱 🧰</h1>
        <p className="text-center text-slate-500 mb-6">挑一个小练习，和波波一起让心情变好吧</p>

        {loadError ? (
          <div className="bg-white rounded-2xl p-6 text-center text-slate-600">
            <div className="text-4xl mb-3">😴</div>
            网络好像睡着了，工具清单暂时拿不到。
            <br />
            别担心，右上角 🆘 里的小练习随时都能用。
          </div>
        ) : tools.length === 0 ? (
          <div className="text-center text-slate-400 py-10">正在打开百宝箱…</div>
        ) : (
          <div className="space-y-3">
            {tools.map((tool) => (
              <button
                key={tool.toolId}
                type="button"
                onClick={() => setActiveTool(tool)}
                className="w-full bg-white rounded-2xl p-4 flex items-center gap-4 shadow-sm active:scale-95 transition-transform text-left"
              >
                <span className="text-4xl">{tool.emoji}</span>
                <span className="flex-1">
                  <span className="block font-bold text-slate-700">{tool.title}</span>
                  <span className="block text-sm text-slate-400">
                    约 {Math.max(1, Math.round(tool.durationSec / 60))} 分钟
                  </span>
                </span>
                <span className="text-slate-300 text-xl">›</span>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
