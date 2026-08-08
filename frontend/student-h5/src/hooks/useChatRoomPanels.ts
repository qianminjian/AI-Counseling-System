import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * ChatRoom 面板/弹窗/提示条状态收敛（FA-06，DOC-074）
 * <p>
 * 此前 7 个 UI 状态内联在 ChatRoom 神组件（595 行）：面板开合（设置/百宝箱/SOS/
 * 切换同学/满意度）+ 语音提示条（3 处重复 setTimeout 清空模式）+ 单条消息重播高亮。
 * 收敛到此 hook：开合状态互不耦合（无联动约束），提示条统一定时清空 + 新提示重置旧定时器。
 * <p>
 * 未纳入：cancelArmed（按住说话上滑取消态，与 pointerStartYRef 手势逻辑强耦合，留在组件内）。
 */
export function useChatRoomPanels() {
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [toolboxOpen, setToolboxOpen] = useState(false)
  const [sosOpen, setSosOpen] = useState(false)
  const [confirmSwitch, setConfirmSwitch] = useState(false)
  const [showSatisfaction, setShowSatisfaction] = useState(false)
  const [voiceNotice, setVoiceNotice] = useState('')
  const [speakingMsgIdx, setSpeakingMsgIdx] = useState(-1)
  const noticeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  /** 展示提示条并自动清空；新提示重置旧定时器（覆盖原 3 处 setVoiceNotice + setTimeout 重复模式） */
  const showNotice = useCallback((text: string, durationMs: number) => {
    if (noticeTimerRef.current) clearTimeout(noticeTimerRef.current)
    setVoiceNotice(text)
    noticeTimerRef.current = setTimeout(() => setVoiceNotice(''), durationMs)
  }, [])

  // 卸载时清除定时器，避免 setState-after-unmount（AUD-017 同款）
  useEffect(() => () => {
    if (noticeTimerRef.current) clearTimeout(noticeTimerRef.current)
  }, [])

  return {
    settingsOpen, setSettingsOpen,
    toolboxOpen, setToolboxOpen,
    sosOpen, setSosOpen,
    confirmSwitch, setConfirmSwitch,
    showSatisfaction, setShowSatisfaction,
    voiceNotice, showNotice,
    speakingMsgIdx, setSpeakingMsgIdx,
  }
}
