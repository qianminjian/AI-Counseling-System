/**
 * 波波头像（品牌图形统一，doing/75 §7.5-2）
 * - 纯 SVG 海豚头像（与 public/favicon.svg 同构），跨平台渲染一致，替代主题 emoji
 * - 角色固定、随主题换色（design/10 §7.2/§7.3：body/belly/fin 三元组，THEMES[].bobo）
 */
export default function BoBoAvatar({
  size = 72,
  colors,
  className = '',
}: {
  size?: number
  colors?: { body?: string; belly?: string; fin?: string }
  className?: string
}) {
  const { body = '#38BDF8', belly = '#E0F2FE', fin = '#0284C7' } = colors || {}
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 512 512"
      className={className}
      role="img"
      aria-label="波波"
      data-testid="bobo-avatar"
    >
      {/* 圆形软底（主题浅色调） */}
      <circle cx="256" cy="256" r="248" fill={belly} />
      {/* 背鳍 */}
      <path d="M 230 125 C 235 82, 278 58, 308 64 C 295 88, 288 112, 286 132 C 267 124, 248 122, 230 125 Z" fill={fin} />
      {/* 海豚头部主体 */}
      <path d="M 120 280 C 115 190, 175 120, 270 118 C 345 116, 400 155, 418 205 C 435 212, 452 225, 458 240 C 461 248, 456 255, 447 252 C 436 249, 422 246, 413 250 C 398 300, 345 345, 272 350 C 190 356, 126 340, 120 280 Z" fill={body} />
      {/* 肚皮（浅色） */}
      <path d="M 162 318 C 218 350, 318 350, 378 310 C 362 342, 308 360, 252 356 C 208 353, 178 338, 162 318 Z" fill={belly} />
      {/* 眼睛 */}
      <circle cx="322" cy="212" r="34" fill="#FFFFFF" />
      <circle cx="332" cy="217" r="17" fill="#0F172A" />
      <circle cx="338" cy="209" r="6" fill="#FFFFFF" />
      {/* 腮红 */}
      <ellipse cx="352" cy="262" rx="20" ry="13" fill="#FDA4AF" opacity="0.8" />
      {/* 微笑嘴 */}
      <path d="M 416 255 C 405 266, 390 269, 376 265" stroke="#0369A1" strokeWidth="7" fill="none" strokeLinecap="round" />
    </svg>
  )
}
