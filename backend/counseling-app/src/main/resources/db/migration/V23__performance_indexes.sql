-- V23: 数据库性能优化索引（PERF-002）
-- 目标：消除高频查询的全表扫描，覆盖会话列表、风险事件、质量评分等热路径

-- 1. 会话查询（教师端按学生/时间范围查会话）
CREATE INDEX IF NOT EXISTS idx_sessions_tenant_student
    ON tenant_template.counseling_sessions (tenant_id, student_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_sessions_tenant_status
    ON tenant_template.counseling_sessions (tenant_id, session_status, created_at DESC);

-- 2. 风险事件（教师端按状态/时间筛选）
CREATE INDEX IF NOT EXISTS idx_risk_events_tenant_status
    ON tenant_template.risk_events (tenant_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_risk_events_tenant_type
    ON tenant_template.risk_events (tenant_id, risk_type, created_at DESC);

-- 4. 质量评分（按会话/时间范围查询）
CREATE INDEX IF NOT EXISTS idx_quality_scores_session
    ON tenant_template.quality_scores (session_id);

CREATE INDEX IF NOT EXISTS idx_quality_scores_tenant_time
    ON tenant_template.quality_scores (tenant_id, evaluated_at DESC);

-- 5. 消息摘要（会话结束后查询）
CREATE INDEX IF NOT EXISTS idx_message_summaries_session
    ON tenant_template.message_summaries (session_id);

-- 6. 审计日志（管理员按时间/操作类型查询）
CREATE INDEX IF NOT EXISTS idx_audit_logs_tenant_time
    ON tenant_template.audit_logs (tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_logs_action
    ON tenant_template.audit_logs (tenant_id, action, created_at DESC);

-- 7. 长期记忆（跨会话回注查询）
CREATE INDEX IF NOT EXISTS idx_long_term_memories_student
    ON tenant_template.long_term_memories (tenant_id, student_user_id, importance DESC);

-- 8. Prompt 版本（按租户+模板+激活状态查询）
CREATE INDEX IF NOT EXISTS idx_prompt_versions_lookup
    ON tenant_template.prompt_versions (tenant_id, template_key, is_active, ab_group);

-- 9. 用户查询（按租户+类型筛选）
CREATE INDEX IF NOT EXISTS idx_users_tenant_type
    ON tenant_template.users (tenant_id, user_type, status);

-- 10. 学生画像（按学生查询最新）
CREATE INDEX IF NOT EXISTS idx_student_profiles_student
    ON tenant_template.student_profiles (user_id, last_updated_at DESC);
