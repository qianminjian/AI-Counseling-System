/**
 * doing/85 TOC-007：toC 隐私控制页
 * 路由：/toc/privacy
 * 数据清单查看 + 删除全部家庭数据（不可逆，二次确认）。
 */
import { useCallback, useEffect, useState } from 'react'
import { View, Text, Button } from '@tarojs/components'
import {
  getTocPrivacyOverview,
  deleteTocPrivacyData,
  type TocPrivacyOverview,
} from '../../services/toc'
import './index.scss'

export default function TocPrivacyPage() {
  const [overview, setOverview] = useState<TocPrivacyOverview | null>(null)
  const [error, setError] = useState('')
  // F-12（doing/98）：删除成功提示独立状态（原写入 error 字段以红色错误样式渲染成功结果，语义错位）
  const [notice, setNotice] = useState('')
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const load = useCallback(async () => {
    try {
      setOverview(await getTocPrivacyOverview())
    } catch (e) {
      setError(e instanceof Error ? e.message : '数据加载失败')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const handleDelete = async () => {
    setDeleting(true)
    try {
      const result = await deleteTocPrivacyData()
      setError('')
      setOverview(null)
      // F-12：成功提示走 notice（正常文案样式），不再写 error
      setNotice(`数据已删除且不可恢复（${String(result.accountStatus ?? '')}）`)
      setConfirmOpen(false)
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败')
    } finally {
      setDeleting(false)
    }
  }

  return (
    <View className='toc-privacy'>
      <Text className='toc-privacy__title'>隐私控制</Text>
      {notice ? <Text className='toc-privacy__notice'>{notice}</Text> : null}
      {error ? <Text className='toc-privacy__error'>{error}</Text> : null}

      {overview && (
        <View className='toc-privacy__card'>
          <Text className='toc-privacy__label'>账号：{overview.phone}</Text>
          <Text className='toc-privacy__label'>孩子档案：{overview.profileCount} 个</Text>
          <Text className='toc-privacy__label'>绑定设备：{overview.deviceCount} 台</Text>
          <Text className='toc-privacy__note'>{overview.dataRetentionNote}</Text>
        </View>
      )}

      <View className='toc-privacy__card toc-privacy__card--danger'>
        <Text className='toc-privacy__danger-title'>删除全部数据</Text>
        <Text className='toc-privacy__note'>
          将解绑全部设备、删除全部孩子档案并禁用账号，操作不可逆。
        </Text>
        {!confirmOpen ? (
          <Button
            className='toc-privacy__danger-btn'
            loading={deleting}
            onClick={() => setConfirmOpen(true)}
          >
            删除全部数据
          </Button>
        ) : (
          <View className='toc-privacy__confirm'>
            <Text className='toc-privacy__confirm-hint'>确认删除？此操作不可恢复</Text>
            <View className='toc-privacy__confirm-actions'>
              <Button size='mini' onClick={() => setConfirmOpen(false)}>取消</Button>
              <Button size='mini' type='warn' loading={deleting} onClick={() => void handleDelete()}>
                确认删除
              </Button>
            </View>
          </View>
        )}
      </View>
    </View>
  )
}
