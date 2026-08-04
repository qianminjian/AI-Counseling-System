import { describe, it, expect, vi, afterEach } from 'vitest';
import { evaluateSla, formatSlaCountdown, getSlaMinutes, urgencyWeight } from '../utils/sla';

/**
 * 预警 SLA 策略单测（前端镜像后端 AlertSlaPolicy，design/05 §13）
 * 铁律：S0「5 分钟必须有人接住」。
 */

const NOW = new Date('2026-07-28T10:00:00Z');

function minutesAgo(min: number): string {
  return new Date(NOW.getTime() - min * 60_000).toISOString();
}

afterEach(() => {
  vi.useRealTimers();
});

describe('getSlaMinutes', () => {
  it('S0=5min / S1=15min / S2=60min / S3 无 SLA', () => {
    expect(getSlaMinutes(3)).toBe(5);
    expect(getSlaMinutes(2)).toBe(15);
    expect(getSlaMinutes(1)).toBe(60);
    expect(getSlaMinutes(0)).toBe(0);
  });
});

describe('evaluateSla', () => {
  it('S0 open 超时 → 违约且升级', () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    const sla = evaluateSla(3, 'open', minutesAgo(6));
    expect(sla.breached).toBe(true);
    expect(sla.escalate).toBe(true);
    expect(sla.overdueMin).toBe(1);
  });

  it('S0 open 未超时 → 返回剩余时间', () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    const sla = evaluateSla(3, 'open', minutesAgo(3));
    expect(sla.breached).toBe(false);
    expect(sla.escalate).toBe(false);
    expect(sla.remainingMin).toBe(2);
  });

  it('S0 claimed 超时 → 违约但不升级（已有人接住）', () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    const sla = evaluateSla(3, 'claimed', minutesAgo(10));
    expect(sla.breached).toBe(true);
    expect(sla.escalate).toBe(false);
  });

  it('S2 open 超时 → 仅提醒，不升级', () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    const sla = evaluateSla(1, 'open', minutesAgo(70));
    expect(sla.breached).toBe(true);
    expect(sla.escalate).toBe(false);
  });

  it('已关闭状态（resolved/false_positive）不评估', () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    for (const status of ['resolved', 'false_positive', 'closed']) {
      const sla = evaluateSla(3, status, minutesAgo(100));
      expect(sla.breached).toBe(false);
      expect(sla.escalate).toBe(false);
      expect(sla.hasSla).toBe(true);
    }
  });

  it('S3（绿色）无 SLA', () => {
    const sla = evaluateSla(0, 'open', minutesAgo(100));
    expect(sla.hasSla).toBe(false);
  });

  it('缺少 detectedAt 不评估', () => {
    const sla = evaluateSla(3, 'open', null);
    expect(sla.hasSla).toBe(false);
  });
});

describe('formatSlaCountdown', () => {
  it('各状态的人类可读文案', () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    expect(formatSlaCountdown(0, 'open', minutesAgo(1))).toBe('无时限');
    expect(formatSlaCountdown(3, 'open', minutesAgo(3))).toBe('剩 2min');
    expect(formatSlaCountdown(3, 'open', minutesAgo(6))).toBe('逾期 1min');
    expect(formatSlaCountdown(3, 'resolved', minutesAgo(100))).toBe('已关闭');
  });
});

describe('urgencyWeight', () => {
  it('排序：逾期 S0 > 逾期 S1 > 快到期 > 充裕 > 无 SLA', () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    const overdueS0 = urgencyWeight(3, 'open', minutesAgo(20));   // 逾期 15min
    const overdueS1 = urgencyWeight(2, 'open', minutesAgo(20));   // 逾期 5min
    const nearDue = urgencyWeight(2, 'open', minutesAgo(14));     // 剩 1min
    const relaxed = urgencyWeight(1, 'open', minutesAgo(10));     // 剩 50min
    const noSla = urgencyWeight(0, 'open', minutesAgo(10));       // 无 SLA

    const sorted = [noSla, relaxed, overdueS1, nearDue, overdueS0].sort((a, b) => a - b);
    expect(sorted).toEqual([overdueS0, overdueS1, nearDue, relaxed, noSla]);
  });
});
