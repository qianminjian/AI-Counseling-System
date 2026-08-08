import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

/**
 * 首次登录强制改密页测试（ARCH-009 E-2 补测）
 * - 两次新密码不一致 → 不调用接口并提示
 * - 提交成功 → 调用 onChanged
 * - 提交失败 → 不调用 onChanged
 * - 渲染用户名为提示文案
 */

const mockCallEndpoint = vi.fn();
vi.mock('../api', () => ({
  callEndpoint: (key: string, options?: unknown) => mockCallEndpoint(key, options),
}));

import ChangePassword from '../pages/ChangePassword';

describe('ChangePassword 强制改密页', () => {
  beforeEach(() => {
    mockCallEndpoint.mockReset();
  });

  it('渲染表单与用户提示', () => {
    render(<ChangePassword userName="李老师" onChanged={vi.fn()} />);
    expect(screen.getByText(/李老师，请/)).toBeInTheDocument();
    expect(screen.getByLabelText('临时密码')).toBeInTheDocument();
    expect(screen.getByLabelText('新密码')).toBeInTheDocument();
    expect(screen.getByLabelText('确认新密码')).toBeInTheDocument();
  });

  it('两次新密码不一致时不调用接口', async () => {
    const onChanged = vi.fn();
    render(<ChangePassword userName="李老师" onChanged={onChanged} />);

    fireEvent.change(screen.getByLabelText('临时密码'), { target: { value: 'tmp12345' } });
    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: 'newpass12' } });
    fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: 'different1' } });
    fireEvent.click(screen.getByRole('button', { name: '确认修改' }));

    await waitFor(() => expect(mockCallEndpoint).not.toHaveBeenCalled());
    expect(onChanged).not.toHaveBeenCalled();
  });

  it('提交成功调用 onChanged', async () => {
    mockCallEndpoint.mockResolvedValue(null);
    const onChanged = vi.fn();
    render(<ChangePassword userName="李老师" onChanged={onChanged} />);

    fireEvent.change(screen.getByLabelText('临时密码'), { target: { value: 'tmp12345' } });
    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: 'newpass12' } });
    fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: 'newpass12' } });
    fireEvent.click(screen.getByRole('button', { name: '确认修改' }));

    await waitFor(() => expect(mockCallEndpoint).toHaveBeenCalledWith(
      'changePassword',
      expect.objectContaining({
        body: JSON.stringify({ oldPassword: 'tmp12345', newPassword: 'newpass12' }),
      })
    ));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
  });

  it('接口失败不回调 onChanged', async () => {
    mockCallEndpoint.mockRejectedValue(new Error('修改失败'));
    const onChanged = vi.fn();
    render(<ChangePassword userName="李老师" onChanged={onChanged} />);

    fireEvent.change(screen.getByLabelText('临时密码'), { target: { value: 'tmp12345' } });
    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: 'newpass12' } });
    fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: 'newpass12' } });
    fireEvent.click(screen.getByRole('button', { name: '确认修改' }));

    await waitFor(() => expect(onChanged).not.toHaveBeenCalled());
  });
});
