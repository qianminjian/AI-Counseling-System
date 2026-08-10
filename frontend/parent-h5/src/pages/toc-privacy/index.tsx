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
      // 删除成功后账号已禁用，提示后返回登录页
      setError(`数据已删除且不可恢复（${String(result.accountStatus ?? '')}）`)
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
