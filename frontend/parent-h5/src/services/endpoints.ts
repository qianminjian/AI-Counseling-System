/**
 * parent-h5 前端端点清单（ARCH-008 F-7 契约防线，单一事实源）
 * doing/73 T3：由 src/api/endpoints.ts 迁移（内容原样，契约断言继续生效）
 *
 * 形态约定（与 apiContract.test.ts 的规范化规则一致）：
 * - 全路径（/api/v1 开头），query 剥离
 * - 方法为 HTTP 方法小写
 * - 新增/修改端点时必须同步本清单，测试断言源码全部 API 路径 ⊆ 本清单
 */
export const FRONTEND_ENDPOINTS: Array<[path: string, method: string]> = [
  // 认证
  ['/api/v1/auth/refresh', 'post'],
  ['/api/v1/parent/auth/register', 'post'],
  ['/api/v1/parent/auth/login', 'post'],
  // 家长数据
  ['/api/v1/parent/report', 'get'],
  ['/api/v1/parent/consent/withdraw', 'post'],
  ['/api/v1/parent/consent/status', 'get'], // BUG-P-P04-01：授权状态查询
  // CFG-001/004（doing/84 §六.2）：无屏终端扫码配置（扫码入口匿名查询 + 绑定需登录态）
  ['/api/v1/device/{deviceCode}/info', 'get'],
  ['/api/v1/device/{deviceCode}/status', 'get'],
  ['/api/v1/device/{deviceCode}/bind-code', 'post'],
  ['/api/v1/device/{deviceCode}/bind', 'post'],
  // doing/85 TOC-001/002：toC 家庭账号与孩子档案（注册/登录匿名 + 档案 CRUD）
  ['/api/v1/toc/auth/send-code', 'post'],
  ['/api/v1/toc/auth/register', 'post'],
  ['/api/v1/toc/auth/login', 'post'],
  ['/api/v1/toc/profiles', 'get'],
  ['/api/v1/toc/profiles', 'post'],
  ['/api/v1/toc/profiles/{profileId}', 'put'],
  ['/api/v1/toc/profiles/{profileId}', 'delete'],
  // doing/85 TOC-003：家庭设备绑定（联动 doing/84 设备域）
  ['/api/v1/toc/devices', 'get'],
  ['/api/v1/toc/devices/{deviceCode}/bind-code', 'post'],
  ['/api/v1/toc/devices/{deviceCode}/bind', 'post'],
  ['/api/v1/toc/devices/{deviceCode}/unbind', 'post'],
  // doing/85 TOC-007：隐私控制（数据查看/删除，X-Confirm）
  ['/api/v1/toc/privacy', 'get'],
  ['/api/v1/toc/privacy/data', 'delete'],
]
