/**
 * doing/85 TOC-003/006：家庭设备管理页
 * 路由：/toc/devices
 * 家庭账号绑定设备列表（FAMILY 绑定）+ 解绑 + 远程管理偏好（音量/音色/对话偏好，
 * 设备端配置拉取时下发）；绑定入口引导扫码配置页（CFG-010 联动）。
 */
import { useCallback, useEffect, useState } from 'react'
import { View, Text, Button } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { listTocDevices, tocUnbindDevice, setTocPreferences, type TocDeviceItem } from '../../services/toc'
import './index.scss'

const VOLUME_OPTIONS = [30, 60, 80]
const VOICE_OPTIONS = ['qingyu', 'xiaobobo']
const DIALOGUE_OPTIONS = ['gentle', 'energetic']

export default function TocDevicesPage() {
  const [devices, setDevices] = useState<TocDeviceItem[]>([])
  const [error, setError] = useState('')
  // TOC-006 远程管理：偏好设置展开项
  const [prefOpen, setPrefOpen] = useState('')
  const [volume, setVolume] = useState(60)
  const [voicePersona, setVoicePersona] = useState('qingyu')
  const [dialoguePref, setDialoguePref] = useState('gentle')
  const [prefMsg, setPrefMsg] = useState('')

  const load = useCallback(async () => {
    try {
      setDevices(await listTocDevices())
    } catch (e) {
      setError(e instanceof Error ? e.message : '设备列表加载失败')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const handleUnbind = async (deviceCode: string) => {
    try {
      await tocUnbindDevice(deviceCode)
      void load()
    } catch (e) {
      setError(e instanceof Error ? e.message : '解绑失败')
    }
  }

  const togglePref = (deviceCode: string) => {
    setPrefOpen((cur) => (cur === deviceCode ? '' : deviceCode))
    setPrefMsg('')
  }

  const handleSavePref = async (deviceCode: string) => {
    try {
      await setTocPreferences(deviceCode, { volume, voicePersona, dialoguePref })
      setPrefMsg('偏好已保存，设备下次拉取配置时生效')
    } catch (e) {
      setPrefMsg(e instanceof Error ? e.message : '保存失败')
    }
  }

  const goScan = () => {
    // 扫码配置页（/p/:v/:deviceCode）检测 toC 登录态 → 家庭绑定模式（CFG-010）
    Taro.navigateTo({ url: '/pages/device/index?v=1&deviceCode=SCAN' })
  }

  return (
    <View className='toc-devices'>
      <View className='toc-devices__header'>
        <Text className='toc-devices__title'>我的终端设备</Text>
        <Button className='toc-devices__add' size='mini' onClick={goScan}>绑定新设备</Button>
      </View>

      {error ? <Text className='toc-devices__error'>{error}</Text> : null}

      <View className='toc-devices__list'>
        {devices.length === 0 ? (
          <View className='toc-devices__empty'>
            <Text>还没有绑定设备</Text>
            <Text className='toc-devices__empty-hint'>扫描设备机身二维码开始配置（二维码 URL：/p/1/{'{deviceCode}'}）</Text>
          </View>
        ) : (
          devices.map((d) => (
            <View key={d.deviceCode}>
              <View className='toc-devices__item'>
                <View className='toc-devices__item-info'>
                  <Text className='toc-devices__item-code'>{d.deviceCode}</Text>
                  <Text className='toc-devices__item-meta'>
                    {d.deviceType} · {d.online ? '在线' : '离线'} · {d.firmwareVersion ?? ''}
                  </Text>
                </View>
                <View className='toc-devices__item-actions'>
                  <Text className='toc-devices__item-btn' onClick={() => togglePref(d.deviceCode)}>偏好</Text>
                  <Text className='toc-devices__item-btn toc-devices__item-btn--danger' onClick={() => void handleUnbind(d.deviceCode)}>解绑</Text>
                </View>
              </View>
              {prefOpen === d.deviceCode ? (
                <View className='toc-devices__pref'>
                  <Text className='toc-devices__pref-label'>音量（0-100）：{volume}</Text>
                  <View className='toc-devices__pref-options'>
                    {VOLUME_OPTIONS.map((v) => (
                      <Text
                        key={v}
                        className={'toc-devices__pref-opt' + (volume === v ? ' toc-devices__pref-opt--active' : '')}
                        onClick={() => setVolume(v)}
                      >
                        {v}
                      </Text>
                    ))}
                  </View>
                  <Text className='toc-devices__pref-label'>音色</Text>
                  <View className='toc-devices__pref-options'>
                    {VOICE_OPTIONS.map((v) => (
                      <Text
                        key={v}
                        className={'toc-devices__pref-opt' + (voicePersona === v ? ' toc-devices__pref-opt--active' : '')}
                        onClick={() => setVoicePersona(v)}
                      >
                        {v}
                      </Text>
                    ))}
                  </View>
                  <Text className='toc-devices__pref-label'>对话偏好</Text>
                  <View className='toc-devices__pref-options'>
                    {DIALOGUE_OPTIONS.map((v) => (
                      <Text
                        key={v}
                        className={'toc-devices__pref-opt' + (dialoguePref === v ? ' toc-devices__pref-opt--active' : '')}
                        onClick={() => setDialoguePref(v)}
                      >
                        {v}
                      </Text>
                    ))}
                  </View>
                  <Button className='toc-devices__pref-save' size='mini' onClick={() => void handleSavePref(d.deviceCode)}>
                    保存偏好
                  </Button>
                  {prefMsg ? <Text className='toc-devices__pref-msg'>{prefMsg}</Text> : null}
                </View>
              ) : null}
            </View>
          ))
        )}
      </View>
    </View>
  )
}
