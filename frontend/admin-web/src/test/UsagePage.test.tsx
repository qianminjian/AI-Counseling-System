import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import UsagePage from '../pages/UsagePage'

vi.mock('../api', () => ({
  fetchUsageSummary: vi.fn(),
}))

import { fetchUsageSummary } from '../api'

describe('UsagePage 用量报表', () => {
  beforeEach(() => {
    vi.mocked(fetchUsageSummary).mockReset()
  })

  it('渲染计量预览（计费冻结标注）', async () => {
    vi.mocked(fetchUsageSummary).mockResolvedValue({ llm_call: 100, active_student_snapshot: 42 })

    render(<UsagePage />)

    await waitFor(() => expect(screen.getByText(/计量预览/)).toBeInTheDocument())
    expect(screen.getByText('100')).toBeInTheDocument()
    expect(screen.getByText('42')).toBeInTheDocument()
  })
})
