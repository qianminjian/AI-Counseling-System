/**
 * student-h5 前端端点常量表（doing/94 R-001，对齐 teacher-web FA-15 单一事实源）
 *
 * 此前端点路径散落：api.ts 函数内硬编码 + apiContract.test.ts 本地清单双维护，
 * 新增端点必双改且路径形态漂移无编译期提示。现在：
 * - api.ts 内部函数全部消费本表，路径只在本文件出现一次
 * - path 为全路径模板（/api/v1 开头），路径参数用 {name} 占位（fillPath 替换）
 * - method 一并登记
 * - FRONTEND_ENDPOINTS 由本表派生（占位符剥离），apiContract 测试直接校验常量表
 * - 组件内联路径（useChatSession/useSseStream 的会话端点）已登记入表（createChatSession/
 *   chatSessionMessages/closeSession 条目）；F-01（doing/98）：diary/relaxation/achievements
 *   表外路径已全部登记入表并改 fillPath 消费，apiContract 测试新增消费面双向扫描防回潮
 */
export const ENDPOINTS = {
  // 公开认证（publicFetch，无需登录）
  trialRegister: { path: '/api/v1/auth/trial/register', method: 'post' },
  pinLogin: { path: '/api/v1/auth/pin-login', method: 'post' },
  voiceLogin: { path: '/api/v1/auth/voice-login', method: 'post' },
  getVoiceprintConfig: { path: '/api/v1/voiceprint/config', method: 'get' },
  remoteVoiceprintVerify: { path: '/api/v1/voiceprint/verify', method: 'post' },
  // 认证（api()，需登录）
  setPin: { path: '/api/v1/auth/set-pin', method: 'post' },
  authMe: { path: '/api/v1/auth/me', method: 'get' },
  issueVoiceCredential: { path: '/api/v1/auth/voice-credential', method: 'post' },
  requestGuardianConsent: { path: '/api/v1/auth/guardian-consent/request', method: 'post' },
  confirmGuardianConsent: { path: '/api/v1/auth/guardian-consent/confirm', method: 'post' },
  remoteVoiceprintEnroll: { path: '/api/v1/voiceprint/enroll', method: 'post' },
  // 对话
  warmPrompt: { path: '/api/v1/chat/sessions/{sessionId}/nudge', method: 'post' },
  closeSession: { path: '/api/v1/sessions/{id}/close', method: 'post' },
  // 情绪日记（F-01 doing/98：表外路径入表）
  diaryToday: { path: '/api/v1/diary/today', method: 'get' },
  diaryHistory: { path: '/api/v1/diary/history', method: 'get' },
  diaryStreak: { path: '/api/v1/diary/streak', method: 'get' },
  diaryCheckin: { path: '/api/v1/diary/checkin', method: 'post' },
  diaryAchievements: { path: '/api/v1/diary/achievements', method: 'get' },
  // 放松练习（F-01 doing/98：表外路径入表）
  relaxationSessions: { path: '/api/v1/relaxation/sessions', method: 'post' },
  relaxationToday: { path: '/api/v1/relaxation/sessions/today', method: 'get' },
  relaxationExercises: { path: '/api/v1/relaxation/exercises', method: 'get' },
  // 工具箱
  toolboxTools: { path: '/api/v1/toolbox', method: 'get' },
  moodCheck: { path: '/api/v1/toolbox/mood-check', method: 'post' },
  sosEvents: { path: '/api/v1/sos/events', method: 'post' },
  // 系统/语音（authFetch 原始 Response，调用方自解析）
  systemConfig: { path: '/api/v1/system/config', method: 'get' },
  loginPrompt: { path: '/api/v1/tts/login-prompt', method: 'post' },
  ttsSynthesize: { path: '/api/v1/tts/synthesize', method: 'post' },
  voiceAnalyze: { path: '/api/v1/voice/analyze', method: 'post' },
  // 消费在 shared authFetch / useSseStream / 组件（登记供契约测试知晓）
  authRefresh: { path: '/api/v1/auth/refresh', method: 'post' },
  createChatSession: { path: '/api/v1/chat/sessions', method: 'post' },
  chatSessionMessages: { path: '/api/v1/chat/sessions/{sessionId}/messages', method: 'post' },
} as const

export type EndpointKey = keyof typeof ENDPOINTS

/** 路径参数替换：'/api/v1/.../{id}' + { id: 'x' } → '/api/v1/.../x'；未提供参数按空串（测试期望） */
export function fillPath(template: string, params: Record<string, string>): string {
  return template.replace(/\{(\w+)\}/g, (_, name: string) => params[name] ?? '')
}

/**
 * 契约清单（doing/94 R-001：从常量表派生，占位符剥离，供 apiContract 测试直接校验）
 * 新增端点只需登记 ENDPOINTS 一处，本清单自动跟随
 */
export const FRONTEND_ENDPOINTS: Array<[path: string, method: string]> = Object.values(ENDPOINTS).map((e) => [
  e.path.replace(/\{\w+\}/g, '').replace(/\/+$/, ''),
  e.method,
])
