import { useState, useRef, useCallback } from 'react'

/** 当前告知同意版本（与后端 TrialAuthService.CURRENT_CONSENT_VERSION 一致） */
export const CONSENT_VERSION = 'v0.1'

/**
 * 告知同意门控页（进入产品前强制展示）
 * - 必须滚动到底 + 勾选"我已阅读并同意"才能继续
 * - 分层呈现：首屏关键信息（非医疗 + 危机热线 + 隐私要点）
 * - 内容基于 design/22 告知同意条款草稿 v0.1
 */
export default function ConsentGate({ onAgree }) {
  const [scrolledToBottom, setScrolledToBottom] = useState(false)
  const [checked, setChecked] = useState(false)
  const scrollRef = useRef(null)

  const handleScroll = useCallback(() => {
    const el = scrollRef.current
    if (!el) return
    // 距底部 50px 内视为已读完
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 50) {
      setScrolledToBottom(true)
    }
  }, [])

  const canProceed = scrolledToBottom && checked

  return (
    <div className="min-h-screen flex flex-col" style={{ background: 'linear-gradient(to bottom, #f0f7ff, #e8f4f8)' }}>
      {/* 标题 */}
      <header className="px-6 pt-8 pb-4 text-center">
        <div className="text-4xl mb-3">🛡️</div>
        <h1 className="text-xl font-bold text-gray-800">使用前请阅读以下重要信息</h1>
        <p className="text-sm text-gray-500 mt-1">MindSafe AI 情绪陪伴助手 · 试用版</p>
      </header>

      {/* 条款内容（可滚动区域） */}
      <div
        ref={scrollRef}
        onScroll={handleScroll}
        className="flex-1 overflow-y-auto mx-4 mb-4 bg-white rounded-2xl shadow-sm border border-gray-100 p-6 text-sm leading-relaxed text-gray-700 space-y-5"
      >
        {/* 第一条：服务性质（加粗突出） */}
        <section>
          <h2 className="font-bold text-base text-gray-900 mb-2">一、服务性质声明</h2>
          <p className="bg-amber-50 border-l-4 border-amber-400 p-3 rounded-r-lg font-medium text-amber-800">
            本产品是 AI 情绪陪伴助手，<strong>非医疗服务、非专业心理咨询</strong>。
            不能诊断、不能开药、不能替代面对面专业咨询。
            如您或孩子正在经历严重心理困扰，请寻求专业帮助。
          </p>
        </section>

        {/* 第二条：危机资源 */}
        <section>
          <h2 className="font-bold text-base text-gray-900 mb-2">二、紧急情况</h2>
          <p className="bg-red-50 border-l-4 border-red-400 p-3 rounded-r-lg text-red-700">
            如果您或孩子正处于危机中，请立即拨打：<br />
            <strong className="text-lg">📞 24h 心理援助热线：400-161-9995</strong><br />
            紧急情况请拨打 <strong>120</strong> 或 <strong>110</strong>。<br />
            本工具不适合处理紧急情况。
          </p>
        </section>

        {/* 第三条：适用人群 */}
        <section>
          <h2 className="font-bold text-base text-gray-900 mb-2">三、适用人群</h2>
          <p>本试用版面向 <strong>18 周岁以上成人体验者</strong>（家长、老师、产品同行）。
            不满 14 周岁的未成年人需在监护人陪同下使用，且监护人需同意处理其个人信息。</p>
        </section>

        {/* 第四条：隐私告知 */}
        <section>
          <h2 className="font-bold text-base text-gray-900 mb-2">四、个人信息保护</h2>
          <ul className="list-disc pl-5 space-y-1">
            <li><strong>收集范围</strong>：昵称、年龄、对话内容（用于提供陪伴服务和风险识别）</li>
            <li><strong>不收集</strong>：真实姓名、身份证号、精确位置</li>
            <li><strong>存储</strong>：中国境内云服务器，加密存储</li>
            <li><strong>保留期限</strong>：账号注销后【30】日内删除</li>
            <li><strong>共享</strong>：除法律要求外，不向第三方提供个人信息</li>
            <li><strong>您的权利</strong>：查阅、更正、删除个人信息，撤回同意，注销账号</li>
          </ul>
        </section>

        {/* 第五条：AI 局限性 */}
        <section>
          <h2 className="font-bold text-base text-gray-900 mb-2">五、AI 局限性告知</h2>
          <p>AI 可能出错、可能无法识别所有风险信号、回复仅供参考。
            对话中的风险预警会通知试用咨询师，但 AI 不能保证识别所有危机情况。</p>
        </section>

        {/* 第六条：责任边界 */}
        <section>
          <h2 className="font-bold text-base text-gray-900 mb-2">六、责任边界</h2>
          <p>本服务按"现状"提供，我们尽合理努力保障服务质量，但不保证完全无误。
            因不可抗力、用户自身行为或第三方原因导致的损害，我们依法不承担责任。
            <strong>本条款不免除因故意或重大过失造成人身伤害的法定责任。</strong></p>
        </section>

        {/* 第七条：使用规范 */}
        <section>
          <h2 className="font-bold text-base text-gray-900 mb-2">七、使用规范</h2>
          <p>请勿利用本服务从事违法活动、输入恶意内容或试图干扰系统运行。
            我们有权对违规行为采取必要措施。</p>
        </section>

        {/* 第八条：同意自愿 */}
        <section>
          <h2 className="font-bold text-base text-gray-900 mb-2">八、同意自愿</h2>
          <p>您的同意完全自愿。您可以随时撤回同意（注销账号），
            撤回不影响撤回前已进行的数据处理的合法性。</p>
        </section>

        {/* 版本信息 */}
        <div className="pt-4 border-t border-gray-100 text-xs text-gray-400 text-center">
          告知同意版本：{CONSENT_VERSION}（草稿，待法务审定）· 2026-07-23
        </div>
      </div>

      {/* 底部操作区 */}
      <footer className="px-6 pb-8 space-y-3">
        {!scrolledToBottom && (
          <p className="text-center text-xs text-gray-400 animate-pulse">↓ 请滑动阅读全部内容</p>
        )}

        <label className={`flex items-start gap-3 px-4 py-3 rounded-xl transition-colors ${
          scrolledToBottom ? 'bg-white cursor-pointer' : 'bg-gray-100 cursor-not-allowed opacity-50'
        }`}>
          <input
            type="checkbox"
            checked={checked}
            disabled={!scrolledToBottom}
            onChange={(e) => setChecked(e.target.checked)}
            className="mt-0.5 w-5 h-5 rounded accent-blue-500"
          />
          <span className="text-sm text-gray-600">
            我已阅读并理解以上全部内容，同意按照上述条款使用本服务。
            {checked && <span className="text-green-600 font-medium"> ✓</span>}
          </span>
        </label>

        <button
          onClick={() => canProceed && onAgree(CONSENT_VERSION)}
          disabled={!canProceed}
          className={`w-full py-4 rounded-full text-white font-medium text-lg transition-all ${
            canProceed
              ? 'bg-blue-500 hover:bg-blue-600 active:scale-[0.98] shadow-lg'
              : 'bg-gray-300 cursor-not-allowed'
          }`}
        >
          同意并继续
        </button>
      </footer>
    </div>
  )
}
