import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { sendCode, verifyPhone } from '../../api/index.js'
import { extractTokenFromUrl, setToken, isAuthenticated } from '../../utils/auth.js'

export default function VerifyPage() {
  const navigate = useNavigate()
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [countdown, setCountdown] = useState(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [initialToken, setInitialTokenState] = useState('')
  const timerRef = useRef(null)

  useEffect(() => {
    if (isAuthenticated()) {
      navigate('/report', { replace: true })
      return
    }
    const token = extractTokenFromUrl()
    if (!token) {
      setError('链接无效或已过期，请联系老师重新生成')
    }
    setInitialTokenState(token)
    return () => { if (timerRef.current) clearInterval(timerRef.current) }
  }, [])

  const startCountdown = () => {
    setCountdown(60)
    timerRef.current = setInterval(() => {
      setCountdown(prev => {
        if (prev <= 1) { clearInterval(timerRef.current); return 0 }
        return prev - 1
      })
    }, 1000)
  }

  const handleSendCode = async () => {
    if (!phone || phone.length !== 11) { setError('请输入正确的 11 位手机号'); return }
    setError('')
    try {
      await sendCode(phone, initialToken)
      startCountdown()
    } catch (e) {
      setError(e.message || '发送失败，请稍后重试')
    }
  }

  const handleVerify = async () => {
    if (!phone || phone.length !== 11) { setError('请输入正确的手机号'); return }
    if (!code || code.length !== 6) { setError('请输入 6 位验证码'); return }
    setError('')
    setLoading(true)
    try {
      const res = await verifyPhone(phone, code, initialToken)
      setToken(res.data?.token || res.token)
      navigate('/report', { replace: true })
    } catch (e) {
      setError(e.message || '验证失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="container verify-page">
      <div className="logo-area">
        <span className="logo-emoji">🌈</span>
        <h1 className="page-title">MindSafe 家长端</h1>
        <p className="page-subtitle">验证手机号，查看孩子的情绪周报</p>
      </div>

      <div className="card">
        <div className="input-group">
          <label className="input-label">手机号</label>
          <input
            className="input-field"
            type="tel"
            maxLength={11}
            placeholder="请输入手机号"
            value={phone}
            onChange={e => setPhone(e.target.value)}
          />
        </div>

        <div className="input-group">
          <label className="input-label">验证码</label>
          <div className="code-row">
            <input
              className="input-field"
              type="number"
              maxLength={6}
              placeholder="6 位验证码"
              value={code}
              onChange={e => setCode(e.target.value)}
            />
            <button
              className="code-btn"
              disabled={countdown > 0 || !phone}
              onClick={handleSendCode}
            >
              {countdown > 0 ? `${countdown}s` : '获取验证码'}
            </button>
          </div>
        </div>

        {error && <p className="error-text">{error}</p>}

        <button
          className="btn-primary"
          disabled={loading || !phone || !code}
          onClick={handleVerify}
        >
          {loading ? '验证中...' : '验证并查看报告'}
        </button>
      </div>

      <p className="tip-text">验证通过后即可查看孩子近 7 天的情绪周报</p>
      <p className="tip-text">如有问题请联系学校心理老师</p>
    </div>
  )
}
