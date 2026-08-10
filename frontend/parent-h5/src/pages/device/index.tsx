/**
 * CFG-002/003/004（doing/84 §四.1~4.3）：无屏终端扫码入口页
 * 路由：/p/:v/:deviceCode（机身/包装二维码 URL）
 *
 * 流程（84 §三.3 配置状态机）：自检分流 → ①连热点指引 → ②配网引导 + 回连轮询
 * （3s 间隔 / 100s 超时）→ ③绑定（归属 + 验证码）→ ④完成。
 * 离线兜底：无外网时展示引导卡（设备配网页 192.168.4.1 直连不依赖本页）。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { useRouter } from '@tarojs/taro'
import { View, Text, Input, Button } from '@tarojs/components'
import {
  getDeviceInfo,
  getDeviceStatus,
  createBindCode,
  bindDevice,
  type DeviceInfo,
} from '../../services/device'
import { validateBindInput } from '../../utils/deviceBind'
import './index.scss'

/** 回连检查轮询间隔（ms）/ 超时（ms），对齐 84 §三.3（3s / 100s） */
const POLL_INTERVAL_MS = 3000
const POLL_TIMEOUT_MS = 100_000

/** 步骤条（涂鸦单页步骤条模式，84 §四.1） */
const STEPS = ['连接热点', '配置网络', '绑定设备', '完成']

type Phase = 'loading' | 'notFound' | 'bound' | 'steps'

/**
 * Taro Input 事件取值兼容：Taro 封装事件为 e.detail.value，
 * 原生 DOM/测试环境为 e.target.value（doing/73 踩坑清单同类）。
 */
function inputValue(e: unknown): string {
  const ev = e as { detail?: { value?: string }; target?: { value?: string } }
  return ev.detail?.value ?? ev.target?.value ?? ''
}

export default function DeviceConfigPage() {
  const router = useRouter()
  const deviceCode = (router.params?.deviceCode ?? '').toUpperCase()

  const [phase, setPhase] = useState<Phase>('loading')
  const [info, setInfo] = useState<DeviceInfo | null>(null)
  const [step, setStep] = useState(0)
  // 回连轮询
  const [polling, setPolling] = useState(false)
  const [online, setOnline] = useState(false)
  const pollTimers = useRef<{ interval?: ReturnType<typeof setInterval>; timeout?: ReturnType<typeof setTimeout> }>({})
  // 绑定表单
  const [bindType, setBindType] = useState('CLASS')
  const [bindTargetId, setBindTargetId] = useState('')
  const [code, setCode] = useState('')
  const [bindError, setBindError] = useState('')
  const [bindCodeExpires, setBindCodeExpires] = useState('')
  const [offline] = useState(() => typeof navigator !== 'undefined' && !navigator.onLine)

  /** 自检分流（AC-84-01/02）：已绑定 → bound；未找到 → notFound；否则进入配置流程 */
  const loadInfo = useCallback(async () => {
    if (!deviceCode) {
      setPhase('notFound')
      return
    }
    try {
      const deviceInfo = await getDeviceInfo(deviceCode)
      setInfo(deviceInfo)
      if (deviceInfo.bound) {
        setPhase('bound')
      } else {
        setPhase('steps')
      }
    } catch {
      setPhase('notFound')
    }
  }, [deviceCode])

  useEffect(() => {
    void loadInfo()
  }, [loadInfo])

  /** 步骤②回连检查轮询（AC-84-04）：立即检查一次 + 3s 间隔，设备上报在线 → 自动推进绑定 */
  const startPolling = useCallback(() => {
    setPolling(true)
    const check = async () => {
      try {
        const status = await getDeviceStatus(deviceCode)
        if (status.online) {
          setOnline(true)
          stopPolling()
          setStep(2)
        }
      } catch {
        // 网络异常不中断轮询，超时统一收尾
      }
    }
    void check()
    pollTimers.current.interval = setInterval(() => void check(), POLL_INTERVAL_MS)
    pollTimers.current.timeout = setTimeout(() => {
      stopPolling()
      setPolling(false)
    }, POLL_TIMEOUT_MS)
  }, [deviceCode])

  const stopPolling = useCallback(() => {
    if (pollTimers.current.interval) clearInterval(pollTimers.current.interval)
    if (pollTimers.current.timeout) clearTimeout(pollTimers.current.timeout)
    pollTimers.current = {}
  }, [])

  useEffect(() => {
    if (phase === 'steps' && step === 1) {
      startPolling()
    }
    return stopPolling
  }, [phase, step, startPolling, stopPolling])

  /** 进入绑定步骤时自动生成验证码（AC-84-23，设备语音播报） */
  useEffect(() => {
    if (phase === 'steps' && step === 2) {
      setBindError('')
      createBindCode(deviceCode)
        .then((result) => setBindCodeExpires(result.expiresAt))
        .catch(() => setBindError('验证码生成失败，请稍后重试'))
    }
  }, [phase, step, deviceCode])

  /** 绑定提交（AC-84-10/11/12） */
  const handleBind = useCallback(async () => {
    const validationError = validateBindInput(bindTargetId, code)
    if (validationError) {
      setBindError(validationError)
      return
    }
    try {
      await bindDevice(deviceCode, { bindType, bindTargetId: bindTargetId.trim(), code })
      setStep(4)
    } catch (e) {
      setBindError(e instanceof Error ? e.message : '绑定失败，请重试')
    }
  }, [deviceCode, bindType, bindTargetId, code])

  if (phase === 'loading') {
    return (
      <View className='device-page'>
        <Text className='device-hint'>正在识别设备…</Text>
      </View>
    )
  }

  if (phase === 'notFound') {
    return (
      <View className='device-page'>
        <Text className='device-title'>未找到该设备</Text>
        <Text className='device-hint'>请核对机身二维码，或联系学校管理员</Text>
      </View>
    )
  }

  if (phase === 'bound') {
    return (
      <View className='device-page'>
        <Text className='device-title'>设备已绑定</Text>
        <Text className='device-hint'>如需管理设备（音色/心情/解绑），请在管理台操作</Text>
      </View>
    )
  }

  return (
    <View className='device-page'>
      {/* 设备识别 */}
      <View className='device-card'>
        <Text className='device-title'>波波小伙伴 · 配置向导</Text>
        <Text className='device-hint'>
          设备 {info?.deviceType ?? '终端'}（尾号 {info?.codeTail ?? '****'}）
        </Text>
      </View>

      {/* 离线兜底卡（AC-84-03） */}
      {offline && (
        <View className='device-offline'>
          <Text className='device-hint'>
            当前无法访问网络服务，请先连接设备热点（BoBo_Setup_ 开头），配网页地址
            http://192.168.4.1
          </Text>
        </View>
      )}

      {/* 步骤条 */}
      <View className='device-steps'>
        {STEPS.map((label, i) => (
          <View key={label} className={`device-step ${i === step ? 'active' : ''} ${i < step ? 'done' : ''}`}>
            <Text>{i + 1}</Text>
            <Text className='device-step-label'>{label}</Text>
          </View>
        ))}
      </View>

      {step === 0 && (
        <View className='device-card'>
          <Text className='device-section-title'>① 连接设备热点</Text>
          <Text className='device-hint'>1. 长按机身按键 3 秒进入配网（LED 蓝灯慢闪）</Text>
          <Text className='device-hint'>2. 打开手机设置，连接热点 BoBo_Setup_（或 Xiaozhi- 开头）</Text>
          <Text className='device-hint'>
            3. 如提示「此网络无法上网/是否保持连接」，请选择保持连接
          </Text>
          <View className='device-led'>
            <Text className='device-hint'>LED 状态：蓝=配网中 / 绿=已连网 / 红=失败 / 灭=休眠</Text>
          </View>
          <Button className='device-btn' onClick={() => setStep(1)}>
            我已连接热点，下一步
          </Button>
        </View>
      )}

      {step === 1 && (
        <View className='device-card'>
          <Text className='device-section-title'>② 配置网络</Text>
          <Text className='device-hint'>
            配网页将自动弹出（或手动打开 http://192.168.4.1），选择 WiFi 并输入密码，仅支持 2.4G
          </Text>
          <Text className='device-hint'>配网完成后，请将手机切回原来的 WiFi/网络</Text>
          <View className='device-status'>
            <Text className={`device-dot ${online ? 'green' : 'blue'}`} />
            <Text className='device-hint'>
              {online ? '设备已连上网络！' : polling ? '正在检查设备网络连接…' : '等待配网完成…'}
            </Text>
          </View>
          {!polling && !online && (
            <Button className='device-btn' onClick={startPolling}>
              重新检查连接
            </Button>
          )}
        </View>
      )}

      {step === 2 && (
        <View className='device-card'>
          <Text className='device-section-title'>③ 绑定设备</Text>
          <Text className='device-hint'>设备将语音播报 6 位验证码，请输入进行绑定</Text>
          <View className='device-form'>
            <Input
              className='device-input'
              placeholder='归属类型：CLASS（班级）/ ROOM（咨询室）/ SCHOOL（学校）'
              value={bindType}
              onInput={(e) => setBindType(inputValue(e))}
            />
            <Input
              className='device-input'
              placeholder='归属 ID（学校/班级/咨询室）'
              value={bindTargetId}
              onInput={(e) => setBindTargetId(inputValue(e))}
            />
            <Input
              className='device-input'
              placeholder='设备语音播报的 6 位验证码'
              maxlength={6}
              value={code}
              onInput={(e) => setCode(inputValue(e))}
            />
          </View>
          {bindError && <Text className='device-error'>{bindError}</Text>}
          <Button className='device-btn' onClick={handleBind}>
            确认绑定
          </Button>
          {bindCodeExpires && (
            <Text className='device-hint'>绑定会话有效期至 {new Date(bindCodeExpires).toLocaleTimeString()}</Text>
          )}
        </View>
      )}

      {step === 3 && (
        <View className='device-card'>
          <Text className='device-section-title'>④ 完成</Text>
          <Text className='device-hint'>设备绑定成功！设备 LED 绿色 3 次快闪确认</Text>
          <Text className='device-hint'>现在可以让学生抱着波波开始对话了</Text>
        </View>
      )}
    </View>
  )
}
