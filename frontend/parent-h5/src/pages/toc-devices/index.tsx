/**
 * doing/85 TOC-003：家庭设备管理页
 * 路由：/toc/devices
 * 家庭账号绑定设备列表（FAMILY 绑定）+ 解绑；绑定入口引导扫码配置页
 * （扫码页检测 toC 登录态后走家庭绑定模式，CFG-010 联动）。
 */
import { useCallback, useEffect, useState } from 'react'
import { View, Text, Button } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { listTocDevices, tocUnbindDevice, type TocDeviceItem } from '../../services/toc'
import './index.scss'

export default function TocDevicesPage() {
  const [devices, setDevices] = useState<TocDeviceItem[]>([])
  const [error, setError] = useState('')

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
            <View key={d.deviceCode} className='toc-devices__item'>
              <View className='toc-devices__item-info'>
                <Text className='toc-devices__item-code'>{d.deviceCode}</Text>
                <Text className='toc-devices__item-meta'>
                  {d.deviceType} · {d.online ? '在线' : '离线'} · {d.firmwareVersion ?? ''}
                </Text>
              </View>
              <Text className='toc-devices__item-btn' onClick={() => void handleUnbind(d.deviceCode)}>解绑</Text>
            </View>
          ))
        )}
      </View>
    </View>
  )
}
