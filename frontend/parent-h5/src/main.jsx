import { createRoot } from 'react-dom/client'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import VerifyPage from './pages/verify/index.jsx'
import ReportPage from './pages/report/index.jsx'
import ConsentPage from './pages/consent/index.jsx'
import { isAuthenticated } from './utils/auth.js'
import './app.scss'

function ProtectedRoute({ children }) {
  if (!isAuthenticated()) {
    return <Navigate to="/" replace />
  }
  return children
}

createRoot(document.getElementById('root')).render(
  <BrowserRouter basename="/parent">
    <Routes>
      <Route path="/" element={<VerifyPage />} />
      <Route path="/report" element={<ProtectedRoute><ReportPage /></ProtectedRoute>} />
      <Route path="/consent" element={<ProtectedRoute><ConsentPage /></ProtectedRoute>} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  </BrowserRouter>
)
