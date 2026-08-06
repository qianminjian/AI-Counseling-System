import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

/**
 * 统计图表组件测试（ARCH-009 E-2 补测）
 * - 4 类图表（会话趋势/风险分布/班级对比/情绪分布）有数据时调用 echarts setOption
 * - 空数据不崩溃（option 用空数组兜底）
 */

const mockSetOption = vi.fn();
const mockResize = vi.fn();
const mockDispose = vi.fn();

vi.mock('echarts/core', () => ({
  use: vi.fn(),
  init: vi.fn(() => ({ setOption: mockSetOption, resize: mockResize, dispose: mockDispose })),
  graphic: { LinearGradient: class {} },
}));
vi.mock('echarts/charts', () => ({ LineChart: class {}, PieChart: class {}, BarChart: class {} }));
vi.mock('echarts/components', () => ({
  TitleComponent: class {}, TooltipComponent: class {}, LegendComponent: class {},
  GridComponent: class {}, DatasetComponent: class {},
}));
vi.mock('echarts/renderers', () => ({ CanvasRenderer: class {} }));

import {
  SessionTrendChart, RiskPieChart, ClassBarChart, EmotionBarChart,
} from '../components/teacher/StatsCharts';

const trendData = [
  { date: '2026-07-01', count: 3 },
  { date: '2026-07-02', count: 5 },
];
const riskData = [
  { level: 3, label: '高', count: 2 },
  { level: 2, label: '中', count: 4 },
];
const classData = [
  { classCode: '一班', alertCount: 3, studentCount: 30 },
  { classCode: '二班', alertCount: 1, studentCount: 28 },
];
const emotionData = [
  { emotion: 'happy', count: 10 },
  { emotion: 'anxious', count: 4 },
];

describe('StatsCharts 统计图表', () => {
  beforeEach(() => {
    mockSetOption.mockClear();
  });

  const firstOptionSeries = () => mockSetOption.mock.calls[0][0].series;

  it('会话趋势图有数据时调用 setOption', () => {
    render(<SessionTrendChart data={trendData} />);
    expect(mockSetOption).toHaveBeenCalledTimes(1);
    expect(firstOptionSeries()[0].type).toBe('line');
  });

  it('风险分布饼图有数据时调用 setOption', () => {
    render(<RiskPieChart data={riskData} />);
    expect(mockSetOption).toHaveBeenCalledTimes(1);
    expect(firstOptionSeries()[0].type).toBe('pie');
  });

  it('班级对比柱状图有数据时调用 setOption（双系列）', () => {
    render(<ClassBarChart data={classData} />);
    expect(mockSetOption).toHaveBeenCalledTimes(1);
    expect(firstOptionSeries().map((s) => s.type)).toEqual(['bar', 'bar']);
  });

  it('情绪分布图有数据时调用 setOption', () => {
    render(<EmotionBarChart data={emotionData} />);
    expect(mockSetOption).toHaveBeenCalledTimes(1);
    expect(firstOptionSeries()[0].type).toBe('bar');
  });

  it('空数据不崩溃（选项兜底空数组）', () => {
    render(<SessionTrendChart data={undefined} />);
    render(<RiskPieChart data={undefined} />);
    render(<ClassBarChart data={undefined} />);
    render(<EmotionBarChart data={undefined} />);
    expect(mockSetOption).toHaveBeenCalled();
  });
});
