import { useState, useEffect } from 'react'
import { useWakeModelStatus } from '../hooks/useWakeWord'
import { useVoiceprintModelStatus } from '../hooks/useVoiceprint'

/**
 * F-8（2026-08-09）：提取模型下载进度组件为独立文件，避免 ChatRoom 反向 import LoginPage 造成循环依赖。
 * 供 LoginPage 和 ChatRoom 共用——进对话后用户也能感知"语音引擎 X% 加载中"，避免长期"加载中"无感。
 */
export default function ModelDownloadProgress() {
  const wake = useWakeModelStatus()
  const vp = useVoiceprintModelStatus()
  const [showReady, setShowReady] = useState(false)

  // 汇总两个模型的状态
  const items: { label: string; status: string; progress: number; error?: string }[] = []
  if (vp.status === 'loading' || vp.status === 'error') {
    items.push({ label: '声纹引擎', status: vp.status, progress: vp.progress, error: vp.error })
  }
  if (wake.status === 'loading' || wake.status === 'error') {
    items.push({ label: '语音引擎', status: wake.status, progress: wake.progress, error: wake.error })
  }

  // 两个模型都 ready 时，短暂显示绿色就绪提示（3s 后自动隐藏）
  const bothReady = vp.status === 'ready' && wake.status === 'ready'
  useEffect(() => {
    if (bothReady) {
      setShowReady(true)
      const t = setTimeout(() => setShowReady(false), 3000)
      return () => clearTimeout(t)
    }
  }, [bothReady])

  if (items.length === 0 && !showReady) return null

  // 就绪状态：绿色胶囊
  if (items.length === 0 && showReady) {
    return (
      <div className="fixed bottom-4 left-1/2 -translate-x-1/2 z-40 px-4 py-1.5 rounded-full border text-xs font-medium bg-green-50 text-green-600 border-green-200 transition-opacity duration-500">
        🎧 语音引擎已就绪
      </div>
    )
  }

  const hasError = items.some((i) => i.status === 'error')
  const allLoading = items.filter((i) => i.status === 'loading')

  // 有失败：仅友好提示（AUD-024：不向儿童界面渲染错误堆栈/内部路径，诊断信息保留在 console）
  if (hasError) {
    const failedItems = items.filter((i) => i.status === 'error')
    const failedNames = failedItems.map((i) => i.label).join('、')
    return (
      <div className="fixed bottom-4 left-1/2 -translate-x-1/2 z-40 px-4 py-1.5 rounded-full border text-xs font-medium bg-red-50 text-red-500 border-red-200 max-w-[90vw]">
        ⚠️ {failedNames}加载失败，不影响登录
      </div>
    )
  }

  // 正在下载：显示进度
  if (allLoading.length > 0) {
    const text = allLoading.length === 2
      ? `🎧 语音引擎准备中 ${Math.round((allLoading[0].progress + allLoading[1].progress) / 2) || 0}%`
      : `🎧 ${allLoading[0].label}准备中 ${allLoading[0].progress > 0 ? `${allLoading[0].progress}%` : '…'}`
    return (
      <div className="fixed bottom-4 left-1/2 -translate-x-1/2 z-40 px-3 py-1.5 rounded-full border text-xs font-medium bg-blue-50 text-blue-600 border-blue-200 transition-opacity duration-500">
        {text}
      </div>
    )
  }

  return null
}
