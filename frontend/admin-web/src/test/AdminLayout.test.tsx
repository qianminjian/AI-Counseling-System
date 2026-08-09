import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import AdminLayout, { allowedViews } from '../components/AdminLayout'

describe('AdminLayout 角色菜单', () => {
  it('super_admin 可见全部四项（总览/服务/告警/审计）', () => {
    render(
      <AdminLayout role="super_admin" name="超管" view="overview" onNavigate={() => {}} onLogout={() => {}}>
        <div>内容</div>
      </AdminLayout>,
    )
    expect(screen.getByText('平台总览')).toBeInTheDocument()
    expect(screen.getByText('服务状态')).toBeInTheDocument()
    expect(screen.getByText('告警中心')).toBeInTheDocument()
    expect(screen.getByText('审计日志')).toBeInTheDocument()
  })

  it('ops_admin 不可见审计日志（最小权限）', () => {
    render(
      <AdminLayout role="ops_admin" name="运维" view="overview" onNavigate={() => {}} onLogout={() => {}}>
        <div>内容</div>
      </AdminLayout>,
    )
    expect(screen.getByText('服务状态')).toBeInTheDocument()
    expect(screen.queryByText('审计日志')).not.toBeInTheDocument()
  })

  it('finance_admin 仅总览', () => {
    const views = allowedViews('finance_admin')
    expect(views.has('overview')).toBe(true)
    expect(views.has('services')).toBe(false)
    expect(views.has('audit')).toBe(false)
  })

  it('未知角色 → 无菜单（deny-by-default）', () => {
    const views = allowedViews('unknown_role')
    expect(views.size).toBe(0)
  })
})
