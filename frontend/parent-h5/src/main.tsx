import { createRoot } from 'react-dom/client'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router'
import type { ReactNode } from 'react'
import VerifyPage from './pages/verify/index'
import ReportPage from './pages/report/index'
import ConsentPage from './pages/consent/index'
import PrivacyPage from './pages/privacy/index'
import ErrorBoundary from './components/ErrorBoundary'
import { isAuthenticated } from './utils/auth'
import './app.scss'

function ProtectedRoute({ children }: { children: ReactNode }) {
  if (!isAuthenticated()) {
    return <Navigate to="/" replace />
  }
  return <>{children}</>
}

createRoot(document.getElementById('root')!).render(
  <ErrorBoundary>
    <BrowserRouter basename="/parent">
      <Routes>
        <Route path="/" element={<VerifyPage />} />
        <Route path="/privacy" element={<PrivacyPage />} />
        <Route path="/report" element={<ProtectedRoute><ReportPage /></ProtectedRoute>} />
        <Route path="/consent" element={<ProtectedRoute><ConsentPage /></ProtectedRoute>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  </ErrorBoundary>
)
