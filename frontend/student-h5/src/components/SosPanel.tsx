import { useEffect } from 'react'
import { reportSosEvent } from '../api'

/**
 * SOS 面板（F-2，design/36 §3.4，Wysa 范式）
 *
 * 纯静态三段式，断网 100% 可打开：
 * 1. 立即联系：12355 青少年服务台一键拨号 + 找信任的大人
 * 2. 先稳住自己：54321 接地引导 + 深呼吸
 * 3. 我的安全小岛：安全计划卡片（创建流程为余量，此处静态引导）
 *
 * 打开即上报 SOS 事件（fire-and-forget，失败静默不阻塞界面）。
 */

const GROUNDING_STEPS = [
  '👀 说出 5 个你看到的东西',
  '🖐️ 说出 4 个你能摸到的东西',
  '👂 说出 3 个你能听到的声音',
  '👃 说出 2 个你能闻到的气味',
  '👅 说出 1 个你能尝到的味道',
]

export default function SosPanel({ onBack }: { onBack: () => void }) {
  // SOS 打开事件上报：fire-and-forget，任何失败不得影响界面
  useEffect(() => {
    reportSosEvent().catch(() => {})
  }, [])

  return (
    <div className="fixed inset-0 z-50 bg-amber-50 overflow-y-auto">
      <div className="max-w-md mx-auto p-6 pb-16">
        <button type="button" onClick={onBack} className="text-slate-500 font-bold mb-4">
          ← 返回
        </button>

        <h1 className="text-2xl font-bold text-slate-700 text-center mb-1">波波在这里陪你 💙</h1>
        <p className="text-center text-slate-500 mb-6">难受的时候，可以先看看这里</p>

        {/* 第一段：立即联系 */}
        <section className="mb-6">
          <h2 className="text-lg font-bold text-slate-700 mb-3">📞 立即联系</h2>
          <a
            href="tel:12355"
            className="block bg-orange-400 text-white rounded-2xl p-4 text-center shadow-sm active:scale-95 transition-transform"
          >
            <span className="text-xl font-bold">拨打 12355 青少年服务台</span>
            <span className="block text-sm opacity-90 mt-1">24 小时都有人愿意听你说</span>
          </a>
          <div className="bg-white rounded-2xl p-4 mt-3 text-slate-600">
            也可以去找你信任的大人：老师、爸爸妈妈、或者其他家人。说出"我需要帮助"是很勇敢的事。
          </div>
        </section>

        {/* 第二段：先稳住自己 */}
        <section className="mb-6">
          <h2 className="text-lg font-bold text-slate-700 mb-3">🌱 先稳住自己</h2>
          <div className="bg-white rounded-2xl p-4">
            <p className="font-bold text-slate-700 mb-2">🔍 和波波玩"找一找"（54321 接地）</p>
            <ul className="space-y-2 text-slate-600">
              {GROUNDING_STEPS.map((step) => (
                <li key={step}>{step}</li>
              ))}
            </ul>
            <p className="text-sm text-slate-400 mt-3">慢慢来，一个一个找，波波陪着你。</p>
          </div>
          <div className="bg-white rounded-2xl p-4 mt-3 text-slate-600">
            🫧 或者做三次深呼吸：用鼻子吸气数 4 下，再用嘴巴慢慢呼气数 6 下。
          </div>
        </section>

        {/* 第三段：我的安全小岛 */}
        <section>
          <h2 className="text-lg font-bold text-slate-700 mb-3">🏝️ 我的安全小岛</h2>
          <div className="bg-white rounded-2xl p-4 text-slate-600">
            闭上眼睛，想一想一个让你觉得安心的地方：可以是真实的，也可以是想象的。
            那里有谁？有什么声音？波波和你一起待在这个小岛上。
          </div>
        </section>
      </div>
    </div>
  )
}
