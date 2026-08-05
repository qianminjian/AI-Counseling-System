/**
 * 五角色默认落地页差异化路由（F-3，design/35 §3.1 角色裁剪）
 *
 * - 校领导/管理者（admin）→ 数据大屏（无个案明细入口，只见聚合与脱敏数据）
 * - 心理老师 / 班主任 / 普通教师 → 工作台
 * - 未知/缺失角色 → 保守落工作台
 */
export type LandingPage = 'bigscreen' | 'dashboard';

export function defaultLandingFor(userType: string | null | undefined): LandingPage {
  return userType === 'admin' ? 'bigscreen' : 'dashboard';
}
