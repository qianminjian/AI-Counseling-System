/**
 * 可拖拽悬浮语音按钮（design/27 §5.4 移动端语音入口）
 *
 * 交互设计：
 * - 短按（位移 < 10px）= 按住说话（透传 press-to-talk handlers）
 * - 长按拖动（位移 ≥ 10px）= 重新定位悬浮球
 * - 松手后自动吸附到最近的左/右边缘（防遮挡内容）
 * - 位置持久化到 localStorage，下次进入保持
 *
 * 视觉：
 * - 圆形半透明毛玻璃底 + 内部渲染 children（BoBoPet 或麦克风图标）
 * - 拖动时放大 1.1x + 阴影加深，给出"拿起来了"的反馈
 * - 边缘吸附时轻微弹跳动画
 */
import { useState, useRef, useCallback, useEffect } from 'react'

const STORAGE_KEY = 'mindsafe_voice_btn_pos'
const DRAG_THRESHOLD = 10   // px，超过即判定为拖拽
const EDGE_MARGIN = 12      // 吸附后距边缘间距
const BTN_SIZE = 72         // 按钮尺寸（px）

interface Position { x: number; y: number }

function loadPosition(): Position | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) return JSON.parse(raw)
  } catch { /* ignore */ }
  return null
}

function savePosition(pos: Position) {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(pos)) } catch { /* ignore */ }
}

/** 吸附到最近左/右边缘 */
function snapToEdge(x: number, y: number): Position {
  const vw = window.innerWidth
  const vh = window.innerHeight
  const snappedX = x < vw / 2 ? EDGE_MARGIN : vw - BTN_SIZE - EDGE_MARGIN
  // Y 轴限制在安全区内（不超出顶栏/底栏）
  const snappedY = Math.max(80, Math.min(y, vh - BTN_SIZE - 100))
  return { x: snappedX, y: snappedY }
}

export default function DraggableVoiceButton({
  children,
  disabled = false,
  onPointerDown,
  onPointerMove,
  onPointerUp,
  onPointerCancel,
}: {
  children: React.ReactNode
  disabled?: boolean
  onPointerDown?: (e: React.PointerEvent) => void
  onPointerMove?: (e: React.PointerEvent) => void
  onPointerUp?: (e: React.PointerEvent) => void
  onPointerCancel?: (e: React.PointerEvent) => void
}) {
  // 初始位置：默认右下角（输入栏上方），或从 localStorage 恢复
  const [pos, setPos] = useState<Position>(() => {
    const saved = loadPosition()
    if (saved) return saved
    return { x: window.innerWidth - BTN_SIZE - EDGE_MARGIN, y: window.innerHeight - 200 }
  })
  const [dragging, setDragging] = useState(false)

  const startPosRef = useRef<Position>({ x: 0, y: 0 })   // pointer 按下时屏幕坐标
  const startBtnPosRef = useRef<Position>({ x: 0, y: 0 }) // 按下时按钮位置
  const isDraggingRef = useRef(false)
  const pointerIdRef = useRef<number | null>(null)
  const talkStartedRef = useRef(false)
  const handlePointerDownWithTalk = useCallback((e: React.PointerEvent) => {
    if (disabled) return
    e.currentTarget.setPointerCapture(e.pointerId)
    pointerIdRef.current = e.pointerId
    startPosRef.current = { x: e.clientX, y: e.clientY }
    startBtnPosRef.current = { ...pos }
    isDraggingRef.current = false
    talkStartedRef.current = true
    // 立即透传按下（启动录音），拖拽判定在 move 中处理
    onPointerDown?.(e)
  }, [disabled, pos, onPointerDown])

  const handlePointerMoveWithTalk = useCallback((e: React.PointerEvent) => {
    if (pointerIdRef.current !== e.pointerId) return
    const dx = e.clientX - startPosRef.current.x
    const dy = e.clientY - startPosRef.current.y
    const dist = Math.sqrt(dx * dx + dy * dy)

    if (!isDraggingRef.current && dist >= DRAG_THRESHOLD) {
      isDraggingRef.current = true
      setDragging(true)
      // 取消已启动的录音
      if (talkStartedRef.current) {
        onPointerCancel?.(e)
        talkStartedRef.current = false
      }
    }

    if (isDraggingRef.current) {
      const newX = startBtnPosRef.current.x + dx
      const newY = startBtnPosRef.current.y + dy
      setPos({ x: newX, y: newY })
    } else {
      onPointerMove?.(e)
    }
  }, [onPointerMove, onPointerCancel])

  const handlePointerUpFinal = useCallback((e: React.PointerEvent) => {
    if (pointerIdRef.current !== e.pointerId) return
    pointerIdRef.current = null

    if (isDraggingRef.current) {
      isDraggingRef.current = false
      setDragging(false)
      const snapped = snapToEdge(
        startBtnPosRef.current.x + (e.clientX - startPosRef.current.x),
        startBtnPosRef.current.y + (e.clientY - startPosRef.current.y),
      )
      setPos(snapped)
      savePosition(snapped)
    } else {
      onPointerUp?.(e)
    }
    talkStartedRef.current = false
  }, [onPointerUp])

  const handlePointerCancelFinal = useCallback((e: React.PointerEvent) => {
    if (isDraggingRef.current) {
      isDraggingRef.current = false
      setDragging(false)
      setPos(startBtnPosRef.current)
    } else {
      onPointerCancel?.(e)
    }
    pointerIdRef.current = null
    talkStartedRef.current = false
  }, [onPointerCancel])

  // 窗口 resize 时确保按钮仍在可视区内
  useEffect(() => {
    const onResize = () => {
      setPos(prev => ({
        x: Math.min(prev.x, window.innerWidth - BTN_SIZE - EDGE_MARGIN),
        y: Math.min(prev.y, window.innerHeight - BTN_SIZE - 80),
      }))
    }
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])

  return (
    <div
      className={`fixed z-50 select-none transition-shadow duration-200 ${dragging ? 'scale-110' : ''}`}
      style={{
        left: pos.x,
        top: pos.y,
        width: BTN_SIZE,
        height: BTN_SIZE,
        touchAction: 'none',
        transition: dragging ? 'none' : 'left 0.3s cubic-bezier(0.34,1.56,0.64,1), top 0.3s cubic-bezier(0.34,1.56,0.64,1), transform 0.2s',
      }}
      onPointerDown={handlePointerDownWithTalk}
      onPointerMove={handlePointerMoveWithTalk}
      onPointerUp={handlePointerUpFinal}
      onPointerCancel={handlePointerCancelFinal}
      onLostPointerCapture={handlePointerCancelFinal}
    >
      {/* 毛玻璃圆形底座 */}
      <div className={`absolute inset-0 rounded-full backdrop-blur-md transition-all duration-200
        ${dragging
          ? 'bg-white/90 shadow-xl ring-2 ring-sky-300/60'
          : 'bg-white/70 shadow-lg ring-1 ring-white/50'}`}
      />
      {/* 内容（BoBoPet） */}
      <div className="relative w-full h-full flex items-center justify-center">
        {children}
      </div>
      {/* 拖拽提示小把手（底部三点） */}
      <div className="absolute -bottom-1 left-1/2 -translate-x-1/2 flex gap-0.5 opacity-40">
        <span className="w-1 h-1 rounded-full bg-gray-400" />
        <span className="w-1 h-1 rounded-full bg-gray-400" />
        <span className="w-1 h-1 rounded-full bg-gray-400" />
      </div>
    </div>
  )
}
