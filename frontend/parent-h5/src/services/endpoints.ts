/**
 * parent-h5 前端端点常量表（ARCH-008 F-7 契约防线，单一事实源）
 * doing/73 T3：由 src/api/endpoints.ts 迁移（契约断言继续生效）
 * 板块09 P2-1（2026-08-12）：元组数组 → 对象常量表形态（对齐 student-h5/teacher-web R-001/FA-15），
 * 端点从“只能全量断言的清单”升级为“可按 key 消费 + 编译期约束”的常量表。
 *
 * 形态约定（与 apiContract.test.ts 的规范化规则一致）：
 * - 全路径（/api/v1 开头），query 剥离；路径参数用 {name} 占位（fillPath 替换）
 * - 方法为 HTTP 方法小写
 * - FRONTEND_ENDPOINTS 由本表派生（占位符剥离），apiContract 测试直接校验常量表
 * - 新增/修改端点时必须同步本清单，测试断言源码全部 API 路径 ⊆ 本清单
 */
export const ENDPOINTS = {
  // 认证
  authRefresh: { path: '/api/v1/auth/refresh', method: 'post' },
  parentRegister: { path: '/api/v1/parent/auth/register', method: 'post' },
  parentLogin: { path: '/api/v1/parent/auth/login', method: 'post' },
  // 家长数据
  parentReport: { path: '/api/v1/parent/report', method: 'get' },
  consentWithdraw: { path: '/api/v1/parent/consent/withdraw', method: 'post' },
  consentStatus: { path: '/api/v1/parent/consent/status', method: 'get' }, // BUG-P-P04-01：授权状态查询
  // CFG-001/004（doing/84 §六.2）：无屏终端扫码配置（扫码入口匿名查询 + 绑定需登录态）
  deviceInfo: { path: '/api/v1/device/{deviceCode}/info', method: 'get' },
  deviceStatus: { path: '/api/v1/device/{deviceCode}/status', method: 'get' },
  deviceBindCode: { path: '/api/v1/device/{deviceCode}/bind-code', method: 'post' },
  deviceBind: { path: '/api/v1/device/{deviceCode}/bind', method: 'post' },
  // doing/85 TOC-001/002：toC 家庭账号与孩子档案（注册/登录匿名 + 档案 CRUD）
  tocSendCode: { path: '/api/v1/toc/auth/send-code', method: 'post' },
  tocRegister: { path: '/api/v1/toc/auth/register', method: 'post' },
  tocLogin: { path: '/api/v1/toc/auth/login', method: 'post' },
  tocProfiles: { path: '/api/v1/toc/profiles', method: 'get' },
  createTocProfile: { path: '/api/v1/toc/profiles', method: 'post' },
  updateTocProfile: { path: '/api/v1/toc/profiles/{profileId}', method: 'put' },
  deleteTocProfile: { path: '/api/v1/toc/profiles/{profileId}', method: 'delete' },
  // doing/85 TOC-003：家庭设备绑定（联动 doing/84 设备域）
  tocDevices: { path: '/api/v1/toc/devices', method: 'get' },
  tocDeviceBindCode: { path: '/api/v1/toc/devices/{deviceCode}/bind-code', method: 'post' },
  tocDeviceBind: { path: '/api/v1/toc/devices/{deviceCode}/bind', method: 'post' },
  tocDeviceUnbind: { path: '/api/v1/toc/devices/{deviceCode}/unbind', method: 'post' },
  // doing/85 TOC-006：远程管理偏好
  tocDevicePreferences: { path: '/api/v1/toc/devices/{deviceCode}/preferences', method: 'get' },
  updateTocDevicePreferences: { path: '/api/v1/toc/devices/{deviceCode}/preferences', method: 'put' },
  // doing/85 TOC-007：隐私控制（数据查看/删除，X-Confirm）
  tocPrivacy: { path: '/api/v1/toc/privacy', method: 'get' },
  deleteTocPrivacyData: { path: '/api/v1/toc/privacy/data', method: 'delete' },
} as const

export type EndpointKey = keyof typeof ENDPOINTS

/** 路径参数替换：'/api/v1/.../{profileId}' + { profileId: 'x' } → '/api/v1/.../x'；未提供参数按空串（测试期望） */
export function fillPath(template: string, params: Record<string, string>): string {
  return template.replace(/\{(\w+)\}/g, (_, name: string) => params[name] ?? '')
}

/**
 * 服务层路径消费助手（F-16，doing/98）：常量表为单一事实源，服务层统一经本函数取路径。
 * 返回短路径（剥 /api/v1 前缀）——parent-h5 request 工厂 baseUrl='/api/v1' 会加回，
 * 避免 fillPath 全路径形态下的 /api/v1/api/v1 双前缀；与 admin-web 的 fillPath 全路径形态互补。
 */
export function apiPath(key: EndpointKey, params: Record<string, string> = {}): string {
  return fillPath(ENDPOINTS[key].path, params).replace(/^\/api\/v1/, '')
}

/**
 * 契约清单（P2-1：从常量表派生，占位符剥离，供 apiContract 测试直接校验）
 * 保持元组数组形态（apiContract.test.ts 的规范化断言不变），新增端点只需登记 ENDPOINTS 一处
 */
export const FRONTEND_ENDPOINTS: Array<[path: string, method: string]> = Object.values(ENDPOINTS).map((e) => [
  e.path.replace(/\{\w+\}/g, '').replace(/\/+$/, ''),
  e.method,
])
