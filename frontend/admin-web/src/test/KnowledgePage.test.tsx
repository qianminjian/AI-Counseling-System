import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import KnowledgePage from '../pages/KnowledgePage'

vi.mock('../api', () => ({
  fetchKnowledgeStats: vi.fn(),
}))

import { fetchKnowledgeStats } from '../api'

describe('KnowledgePage 知识库统计', () => {
  beforeEach(() => {
    vi.mocked(fetchKnowledgeStats).mockReset()
  })

  it('渲染平台级状态/分类分布', async () => {
    vi.mocked(fetchKnowledgeStats).mockResolvedValue({
      byStatus: { published: 10, draft: 2 },
      byCategory: { crisis: 5, general: 7 },
    })

    render(<KnowledgePage />)

    await waitFor(() => expect(screen.getByText('12')).toBeInTheDocument())
    expect(screen.getByText('5')).toBeInTheDocument()
    expect(screen.getByText('7')).toBeInTheDocument()
  })
})
