/**
 * 契约测试共享 mock 样例（TEST-006/M3，ARCH-005 F-3 抽取）
 *
 * 与 api.test.ts / toolboxApi.test.ts 一致，对齐后端真实结构；
 * apiContract.test.ts 按快照 schemas 校验（validateMock）。
 * 单一来源：新增/修改响应样例只改这里。
 */

/** pin-login / voice-login 响应（AuthResult，对齐 LoginResponse） */
export const loginMock = {
  token: 'pin_tk',
  refreshToken: 'rt',
  userId: '2',
  displayName: '小明',
  userType: 'STUDENT',
  gradeCode: '4',
  classCode: '401',
  mustChangePassword: false,
}

/** trial-register 响应（AuthResult，对齐 TrialRegisterResponse） */
export const trialMock = {
  token: 'tk',
  refreshToken: 'rt',
  userId: '1',
  tenantId: 't-1',
  userType: 'STUDENT',
  pseudonym: '花花',
  familyCode: 'F-1',
  guardianConsentPending: false,
}

/** voiceprint/config 响应（VoiceprintConfig，后端为 Map → 容器校验 + 字段断言） */
export const voiceprintConfigMock = {
  mode: 'local',
  privacyNote: '声音信息只保存在这台设备上，不会上传到任何服务器',
}

/** toolbox 列表元素（ToolboxTool，对齐 ToolDefinition） */
export const toolMock = {
  toolId: 'breathing_box',
  title: '深呼吸',
  emoji: '🧘',
  durationSec: 60,
  minGrade: 1,
  preMoodCheck: true,
  postMoodCheck: false,
  rewardBadge: null,
  category: 'BREATHING',
}

/** mood-check 响应（对齐后端 ToolboxController 返回结构） */
export const moodCheckMock = {
  toolId: 'grounding_54321',
  preMood: 3,
  postMood: 7,
  delta: 4,
  level: 'IMPROVED',
  needsAttention: false,
}
