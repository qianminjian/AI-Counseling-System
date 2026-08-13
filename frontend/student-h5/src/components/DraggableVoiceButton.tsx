/**
 * 可拖拽悬浮语音按钮（design/27 §5.4 移动端语音入口）
 *
 * 交互设计：
 * - 短按（位移 < 10px）= 按住说话（透传 press-to-talk handlers）
 * - 长按拖动（位移 ≥ 10px）= 重新定位悬浮球，支持任意位置摆放
 * - 松手后仅收敛回可视安全区（不遮顶栏/输入栏），不再强制吸附左右边缘
 * - 初始默认位置：发送按钮正上方（右下角、输入栏之上）
 * - 位置持久化到 localStorage，下次进入保持
 *
 * 视觉：
 * - 圆形半透明毛玻璃底 + 内部渲染 children（BoBoPet 或麦克风图标）
 * - 拖动时放大 1.1x + 阴影加深，给出"拿起来了"的反馈
 * - 边缘吸附时轻微弹跳动画
 */
import { useState, useRef, useCallback, useEffect } from 'react'

const STORAGE_KEY = 'mindsafe_voice_btn_pos_v2'  // v2：改为任意位置摆放，旧的边缘吸附坐标作废
const DRAG_THRESHOLD = 10   // px，超过即判定为拖拽
const EDGE_MARGIN = 12      // 距屏幕边缘最小间距
const BTN_SIZE = 72         // 按钮尺寸（px）
const TOP_SAFE = 80         // 顶部安全区（不遮顶栏）
const BOTTOM_SAFE = 92      // 底部安全区（不遮输入栏，footer 约 80px + 余量）

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

/** 收敛到可视安全区内（任意位置摆放，仅防溢出屏幕/遮挡顶栏输入栏） */
function clampToSafeArea(x: number, y: number): Position {
  const vw = window.innerWidth
  const vh = window.innerHeight
  return {
    x: Math.max(EDGE_MARGIN, Math.min(x, vw - BTN_SIZE - EDGE_MARGIN)),
    y: Math.max(TOP_SAFE, Math.min(y, vh - BTN_SIZE - BOTTOM_SAFE)),
  }
}

/** 默认初始位置：发送按钮正上方（右下角、输入栏之上） */
function defaultPosition(): Position {
  return clampToSafeArea(
    window.innerWidth - BTN_SIZE - EDGE_MARGIN,
    window.innerHeight - BTN_SIZE - BOTTOM_SAFE,
  )
}

export default function DraggableVoiceButton({
  children,
  disabled = false,
  onPointerDown,
  onPointerMove,
  onPointerUp,
  onPointerCancel,
}: {
  children: React.ReactNode | ((side: 'left' | 'right') => React.ReactNode)
  disabled?: boolean
  onPointerDown?: (e: React.PointerEvent) => void
  onPointerMove?: (e: React.PointerEvent) => void
  onPointerUp?: (e: React.PointerEvent) => void
  onPointerCancel?: (e: React.PointerEvent) => void
}) {
  // 初始位置：默认发送按钮上方，或从 localStorage 恢复
  const [pos, setPos] = useState<Position>(() => {
    const saved = loadPosition()
    if (saved) return clampToSafeArea(saved.x, saved.y)
    return defaultPosition()
  })
  const [dragging, setDragging] = useState(false)
  // F-14（doing/98）：viewport 宽度 state 化（resize 同步；渲染期不再直读 window）
  const [viewportWidth, setViewportWidth] = useState(() => (typeof window !== 'undefined' ? window.innerWidth : 375))
  useEffect(() => {
    const onResize = () => setViewportWidth(window.innerWidth)
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])

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
      // 拖动过程中实时收敛，球不会被拖出安全区
      setPos(clampToSafeArea(
        startBtnPosRef.current.x + dx,
        startBtnPosRef.current.y + dy,
      ))
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
      // 任意位置摆放：仅收敛回安全区，不吸附边缘
      const clamped = clampToSafeArea(
        startBtnPosRef.current.x + (e.clientX - startPosRef.current.x),
        startBtnPosRef.current.y + (e.clientY - startPosRef.current.y),
      )
      setPos(clamped)
      savePosition(clamped)
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

  // 窗口 resize 时确保按钮仍在安全区内
  useEffect(() => {
    const onResize = () => {
      setPos(prev => clampToSafeArea(prev.x, prev.y))
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
      {/* 内容（BoBoPet）：任意位置摆放后，把所在半屏告知子组件用于气泡展开方向 */}
      {/* F-14（doing/98）：半屏判定改读 state 化 viewportWidth（原渲染期直读 window.innerWidth 不纯，resize 同步） */}
      <div className="relative w-full h-full flex items-center justify-center">
        {typeof children === 'function'
          ? children(pos.x + BTN_SIZE / 2 < viewportWidth / 2 ? 'left' : 'right')
          : children}
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
