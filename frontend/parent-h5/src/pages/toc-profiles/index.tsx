/**
 * doing/85 TOC-002：toC 家庭档案管理页
 * 路由：/toc/profiles
 * 一账号多孩档案：列表/新建/编辑/删除（数据按家庭账号隔离）。
 */
import { useCallback, useEffect, useState } from 'react'
import { View, Text, Input, Button } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { validateTocNickname } from '../../utils/tocAuth'
import {
  listTocProfiles,
  createTocProfile,
  updateTocProfile,
  deleteTocProfile,
  clearTocSession,
  type TocChildProfile,
} from '../../services/toc'
import './index.scss'

/** Taro Input 值兼容（detail.value / target.value） */
function inputValue(e: unknown): string {
  const ev = e as { detail?: { value?: string }; target?: { value?: string } }
  return ev.detail?.value ?? ev.target?.value ?? ''
}

export default function TocProfilesPage() {
  const [profiles, setProfiles] = useState<TocChildProfile[]>([])
  const [nickname, setNickname] = useState('')
  const [age, setAge] = useState('')
  const [interests, setInterests] = useState('')
  const [editingId, setEditingId] = useState('')
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    try {
      setProfiles(await listTocProfiles())
    } catch (e) {
      setError(e instanceof Error ? e.message : '档案加载失败')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const handleSave = async () => {
    setError('')
    const nicknameError = validateTocNickname(nickname)
    if (nicknameError) {
      setError(nicknameError)
      return
    }
    try {
      const body = {
        nickname: nickname.trim(),
        age: age ? Number(age) : undefined,
        interests: interests.trim() || undefined,
      }
      if (editingId) {
        await updateTocProfile(editingId, body)
      } else {
        await createTocProfile(body)
      }
      setNickname('')
      setAge('')
      setInterests('')
      setEditingId('')
      void load()
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败')
    }
  }

  const handleEdit = (p: TocChildProfile) => {
    setEditingId(p.profileId)
    setNickname(p.nickname)
    setAge(p.age ? String(p.age) : '')
    setInterests(p.interests ?? '')
  }

  const handleDelete = async (profileId: string) => {
    try {
      await deleteTocProfile(profileId)
      void load()
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败')
    }
  }

  const handleLogout = () => {
    clearTocSession()
    Taro.reLaunch({ url: '/pages/toc-login/index' })
  }

  return (
    <View className='toc-profiles'>
      <View className='toc-profiles__header'>
        <Text className='toc-profiles__title'>我的家庭档案</Text>
        <Text className='toc-profiles__logout' onClick={handleLogout}>退出</Text>
      </View>

      <View className='toc-profiles__form'>
        <Input
          className='toc-profiles__input'
          placeholder='孩子昵称（必填）'
          value={nickname}
          onInput={(e) => setNickname(inputValue(e))}
        />
        <Input
          className='toc-profiles__input'
          type='number'
          placeholder='年龄'
          value={age}
          onInput={(e) => setAge(inputValue(e))}
        />
        <Input
          className='toc-profiles__input'
          placeholder='兴趣（逗号分隔，如：恐龙,画画）'
          value={interests}
          onInput={(e) => setInterests(inputValue(e))}
        />
        {error ? <Text className='toc-profiles__error'>{error}</Text> : null}
        <Button className='toc-profiles__save' type='primary' onClick={() => void handleSave()}>
          {editingId ? '保存修改' : '添加档案'}
        </Button>
      </View>

      <View className='toc-profiles__list'>
        {profiles.length === 0 ? (
          <Text className='toc-profiles__empty'>还没有孩子档案，添加第一个吧</Text>
        ) : (
          profiles.map((p) => (
            <View key={p.profileId} className='toc-profiles__item'>
              <View className='toc-profiles__item-info'>
                <Text className='toc-profiles__item-name'>{p.nickname}</Text>
                <Text className='toc-profiles__item-meta'>
                  {p.age ? `${p.age} 岁` : ''} {p.interests ?? ''}
                </Text>
              </View>
              <View className='toc-profiles__item-actions'>
                <Text className='toc-profiles__item-btn' onClick={() => handleEdit(p)}>编辑</Text>
                <Text className='toc-profiles__item-btn toc-profiles__item-btn--danger' onClick={() => void handleDelete(p.profileId)}>删除</Text>
              </View>
            </View>
          ))
        )}
      </View>
    </View>
  )
}
