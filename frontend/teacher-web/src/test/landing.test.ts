import { describe, it, expect } from 'vitest';
import { defaultLandingFor } from '../utils/landing';

/**
 * 五角色默认落地页差异化路由（F-3，design/35 §3.1 角色裁剪）
 *
 * 契约：
 * - 校领导/管理者（admin）→ 数据大屏 'bigscreen'（无个案明细，只见聚合数据）
 * - 心理老师 / 班主任 / 普通教师 → 工作台 'dashboard'
 * - 未知角色 → 保守落工作台（不放大屏，避免越权看到聚合数据页）
 */
describe('defaultLandingFor', () => {
  it('管理者默认落地数据大屏', () => {
    expect(defaultLandingFor('admin')).toBe('bigscreen');
  });

  it('心理老师默认落地工作台', () => {
    expect(defaultLandingFor('psych_teacher')).toBe('dashboard');
  });

  it('班主任默认落地工作台', () => {
    expect(defaultLandingFor('class_teacher')).toBe('dashboard');
  });

  it('普通教师默认落地工作台', () => {
    expect(defaultLandingFor('teacher')).toBe('dashboard');
  });

  it('未知角色保守落工作台', () => {
    expect(defaultLandingFor('platform_ops')).toBe('dashboard');
  });

  it('角色缺失保守落工作台', () => {
    expect(defaultLandingFor(undefined)).toBe('dashboard');
    expect(defaultLandingFor(null)).toBe('dashboard');
    expect(defaultLandingFor('')).toBe('dashboard');
  });
});
