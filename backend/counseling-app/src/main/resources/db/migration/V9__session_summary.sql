-- V9: 会话 AI 摘要字段
-- 会话关闭后异步生成结构化摘要，供教师端查看

ALTER TABLE tenant_template.counseling_sessions
    ADD COLUMN IF NOT EXISTS session_summary TEXT;

COMMENT ON COLUMN tenant_template.counseling_sessions.session_summary
    IS 'AI 生成的会话结构化摘要（JSON：mainTopic/emotionTrend/riskNote/suggestion）';
