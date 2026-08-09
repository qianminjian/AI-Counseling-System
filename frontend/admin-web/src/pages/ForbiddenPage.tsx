/** 403 无权限页（ADMIN-P0-04：路由守卫兜底） */
export default function ForbiddenPage() {
  return (
    <div style={{ padding: 80, textAlign: 'center', color: 'var(--ms-text-muted)' }}>
      <h2 style={{ color: 'var(--ms-danger)' }}>403 无权限访问</h2>
      <p>当前角色无权访问该页面，如有疑问请联系超级管理员。</p>
    </div>
  )
}
