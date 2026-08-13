-- 01_schema.sql 快照（doing/92 R-023：由 gen-schema-snapshot.sh 生成，勿手工编辑）
-- 生成时间: 2026-08-13T07:28:53Z
-- 来源: Flyway 迁移后数据库 mindsafe（V1-V45+）
-- 用途: 灾备重建参照（权威源仍为 Flyway migration/）
-- PostgreSQL database dump




-- Name: tenant_template; Type: SCHEMA; Schema: -; Owner: -

CREATE SCHEMA tenant_template;


-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;


-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: -

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


-- Name: uuid-ossp; Type: EXTENSION; Schema: -; Owner: -

CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;


-- Name: EXTENSION "uuid-ossp"; Type: COMMENT; Schema: -; Owner: -

COMMENT ON EXTENSION "uuid-ossp" IS 'generate universally unique identifiers (UUIDs)';


-- Name: vector; Type: EXTENSION; Schema: -; Owner: -

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: -

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';




-- Name: flyway_schema_history; Type: TABLE; Schema: public; Owner: -

CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


-- Name: schools; Type: TABLE; Schema: public; Owner: -

CREATE TABLE public.schools (
    school_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id uuid NOT NULL,
    school_code character varying(64) NOT NULL,
    school_name character varying(128) NOT NULL,
    edu_stage character varying(32) DEFAULT 'primary'::character varying,
    province character varying(64),
    city character varying(64),
    district character varying(64),
    settings jsonb DEFAULT '{}'::jsonb,
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


-- Name: TABLE schools; Type: COMMENT; Schema: public; Owner: -

COMMENT ON TABLE public.schools IS '学校表：一个租户下可有多所学校';


-- Name: tenants; Type: TABLE; Schema: public; Owner: -

CREATE TABLE public.tenants (
    tenant_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_code character varying(64) NOT NULL,
    tenant_name character varying(128) NOT NULL,
    data_region character varying(32) DEFAULT 'cn-east'::character varying,
    kms_key_ref character varying(128),
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


-- Name: TABLE tenants; Type: COMMENT; Schema: public; Owner: -

COMMENT ON TABLE public.tenants IS '租户表：SaaS 隔离根，一个租户对应一个独立 Schema';


-- Name: alert_events; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.alert_events (
    event_id uuid DEFAULT gen_random_uuid() NOT NULL,
    source character varying(16) NOT NULL,
    fingerprint character varying(64),
    rule_name character varying(128) NOT NULL,
    severity character varying(16) NOT NULL,
    status character varying(16) DEFAULT 'firing'::character varying NOT NULL,
    summary text,
    detail text,
    acknowledged_by character varying(64),
    acknowledged_at timestamp with time zone,
    fired_at timestamp with time zone NOT NULL,
    resolved_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    notify_status character varying(16),
    ack_reason character varying(512)
);


-- Name: TABLE alert_events; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.alert_events IS '告警事件历史（OPS-MON-008；AlertManager 120h 窗口外可查，供管理端 M2 告警中心）';


-- Name: COLUMN alert_events.source; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.alert_events.source IS '来源: alertmanager=采集器拉取 / alertservice=业务告警同步写';


-- Name: COLUMN alert_events.fingerprint; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.alert_events.fingerprint IS 'AlertManager 告警指纹（source=alertmanager 时非空，UNIQUE 约束支撑 upsert 去重）';


-- Name: COLUMN alert_events.status; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.alert_events.status IS '状态: firing/resolved/ack/closed（firing→resolved 由采集器流转，ack/closed 由管理端 API）';


-- Name: COLUMN alert_events.notify_status; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.alert_events.notify_status IS '推送状态: PENDING/SUCCESS/FAILED/SKIPPED（alertservice 来源）；alertmanager 来源为 NULL（推送由 AlertManager 负责）';


-- Name: COLUMN alert_events.ack_reason; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.alert_events.ack_reason IS '确认原因（ack 时必填，审计留痕；V41 新增）';


-- Name: audit_logs; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.audit_logs (
    audit_log_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id uuid,
    user_id uuid,
    action character varying(64) NOT NULL,
    resource_type character varying(64) NOT NULL,
    resource_id uuid,
    detail jsonb,
    ip_hash character(64),
    user_agent character varying(256),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE audit_logs; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.audit_logs IS '审计日志表：记录敏感操作（登录/查看学生档案/导出/配置变更）';


-- Name: consent_records; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.consent_records (
    consent_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    user_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    consent_type character varying(32) NOT NULL,
    consent_version character varying(16) NOT NULL,
    consented_at timestamp with time zone DEFAULT now() NOT NULL,
    ip_hash character varying(64),
    user_agent character varying(255)
);


-- Name: TABLE consent_records; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.consent_records IS '告知同意留痕：版本化记录，每次版本升级需重新同意';


-- Name: counseling_sessions; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.counseling_sessions (
    session_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id uuid NOT NULL,
    school_id uuid,
    student_user_id uuid NOT NULL,
    channel character varying(32) DEFAULT 'web'::character varying NOT NULL,
    interaction_mode character varying(20) DEFAULT 'text'::character varying NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    ended_at timestamp with time zone,
    session_status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    risk_level_snapshot smallint DEFAULT 0,
    transcript_policy character varying(32) DEFAULT 'summary_only'::character varying NOT NULL,
    consent_version character varying(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    state_path jsonb,
    satisfaction_rating smallint,
    satisfaction_comment character varying(512) DEFAULT NULL::character varying,
    turn_count integer DEFAULT 0,
    session_summary text,
    prompt_version character varying(100)
);


-- Name: TABLE counseling_sessions; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.counseling_sessions IS '辅导会话表：一次有限回合对话';


-- Name: COLUMN counseling_sessions.state_path; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.counseling_sessions.state_path IS 'CBT 状态机路径（CbtSessionState JSON）';


-- Name: COLUMN counseling_sessions.satisfaction_rating; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.counseling_sessions.satisfaction_rating IS '会话满意度评分（1-5 星）';


-- Name: COLUMN counseling_sessions.turn_count; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.counseling_sessions.turn_count IS '对话轮次数（会话结束时写入）';


-- Name: COLUMN counseling_sessions.session_summary; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.counseling_sessions.session_summary IS 'AI 生成的会话结构化摘要（JSON：mainTopic/emotionTrend/riskNote/suggestion）';


-- Name: COLUMN counseling_sessions.prompt_version; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.counseling_sessions.prompt_version IS 'AI-005: 会话使用的 Prompt 版本标识（如 SYS_001:v3:treatment_a）';


-- Name: degradation_events; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.degradation_events (
    event_id uuid DEFAULT gen_random_uuid() NOT NULL,
    point character varying(32) NOT NULL,
    from_state character varying(64) NOT NULL,
    to_state character varying(64) NOT NULL,
    trigger_type character varying(8) NOT NULL,
    operator character varying(64),
    detail character varying(512),
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    dedup_key character varying(128)
);


-- Name: TABLE degradation_events; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.degradation_events IS '降级事件历史（OPS-MON-007/008；auto 检测器落库、manual 管理端切换写库）';


-- Name: COLUMN degradation_events.point; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.degradation_events.point IS '降级点: llm/tts/asr/ser/voice-policy/wake-word';


-- Name: COLUMN degradation_events.trigger_type; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.degradation_events.trigger_type IS '触发方式: auto=监控检测器 / manual=管理端手动切换';


-- Name: COLUMN degradation_events.operator; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.degradation_events.operator IS '手动切换操作人（platform_admin 账号）';


-- Name: COLUMN degradation_events.dedup_key; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.degradation_events.dedup_key IS '幂等去重键（auto: trigger:point:from->to:时间桶，防抖窗口内唯一；manual 为 NULL）';


-- Name: device; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.device (
    device_id uuid DEFAULT gen_random_uuid() NOT NULL,
    device_code character varying(16) NOT NULL,
    sn character varying(32) NOT NULL,
    device_type character varying(32) DEFAULT 'desk_toy'::character varying NOT NULL,
    firmware_version character varying(16),
    status character varying(16) DEFAULT 'UNACTIVATED'::character varying NOT NULL,
    server_url character varying(128),
    last_online_at timestamp with time zone,
    last_offline_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    device_secret character varying(64),
    device_token character varying(256)
);


-- Name: TABLE device; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.device IS '无屏终端设备档案（CFG-001；平台级表，无 tenant_id 列）';


-- Name: COLUMN device.device_code; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.device.device_code IS '短码 11 位：10 位 base32（SN 派生）+ 1 位 Luhn 校验位（doing/84 §5.2.1）';


-- Name: COLUMN device.status; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.device.status IS 'UNACTIVATED=未激活 / PROVISIONING=配网中 / ONLINE_UNBOUND=已联网待绑定 / ONLINE_BOUND=已绑定运行中 / OFFLINE=离线 / RETIRED=已注销';


-- Name: COLUMN device.device_secret; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.device.device_secret IS '设备 HMAC 密钥（P0-1：reportOnline 生成后返回，设备存储；请求签名 = HMAC-SHA256(body+timestamp+nonce, secret)——固件侧对接后启用；当前仅存储待固件二期）';


-- Name: COLUMN device.device_token; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.device.device_token IS '设备 JWT token（P0-1：reportOnline 成功后签发，有效期 24h，subject=deviceCode；设备用于后续 report/heartbeat/status/voiceprint 鉴权；SecurityConfig /report/* 白名单同步收紧）';


-- Name: device_bind_codes; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.device_bind_codes (
    code_id uuid DEFAULT gen_random_uuid() NOT NULL,
    device_id uuid NOT NULL,
    code_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    fail_count integer DEFAULT 0 NOT NULL,
    locked_until timestamp with time zone,
    used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE device_bind_codes; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.device_bind_codes IS '设备绑定验证码会话（CFG-004；哈希存储、5 分钟有效、3 次锁定、一次性）';


-- Name: COLUMN device_bind_codes.code_hash; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.device_bind_codes.code_hash IS '验证码 SHA-256 哈希，明文不落库不落日志';


-- Name: COLUMN device_bind_codes.fail_count; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.device_bind_codes.fail_count IS '连续错误次数，达 3 次置 locked_until';


-- Name: COLUMN device_bind_codes.locked_until; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.device_bind_codes.locked_until IS '锁定截止时间（5 分钟）';


-- Name: device_bindings; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.device_bindings (
    binding_id uuid DEFAULT gen_random_uuid() NOT NULL,
    device_id uuid NOT NULL,
    bind_type character varying(16) NOT NULL,
    bind_target_id uuid NOT NULL,
    student_id uuid,
    bound_by character varying(64),
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    bound_at timestamp with time zone,
    unbound_at timestamp with time zone
);


-- Name: TABLE device_bindings; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.device_bindings IS '设备绑定关系（CFG-004；toB 学校/班级/咨询室三层归属）';


-- Name: COLUMN device_bindings.bind_type; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.device_bindings.bind_type IS 'SCHOOL=学校 / CLASS=班级 / ROOM=咨询室 / FAMILY=家庭（toC 预留）';


-- Name: COLUMN device_bindings.student_id; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.device_bindings.student_id IS 'toC 单孩绑定；toB 多人共用为 NULL';


-- Name: device_operations; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.device_operations (
    operation_id uuid DEFAULT gen_random_uuid() NOT NULL,
    device_code character varying(16) NOT NULL,
    action character varying(32) NOT NULL,
    operator character varying(128),
    accepted_at timestamp with time zone DEFAULT now() NOT NULL,
    note text
);


-- Name: TABLE device_operations; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.device_operations IS '设备操作审计（P1：batch/ota/reboot/factory-reset 受理留痕，生产必读）';


-- Name: COLUMN device_operations.operator; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.device_operations.operator IS '操作人（平台 admin 用户名；设备通道为 deviceCode）';


-- Name: device_preferences; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.device_preferences (
    pref_id uuid DEFAULT gen_random_uuid() NOT NULL,
    device_code character varying(16) NOT NULL,
    family_account_id uuid NOT NULL,
    volume integer,
    voice_persona character varying(32),
    dialogue_pref character varying(64),
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE device_preferences; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.device_preferences IS '设备偏好（TOC-006 远程管理软件侧；平台级表，按 family_account_id 隔离）';


-- Name: COLUMN device_preferences.volume; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.device_preferences.volume IS '音量 0-100；固件执行待 NST-HW-02 二期';


-- Name: device_qr_issuance; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.device_qr_issuance (
    issuance_id uuid DEFAULT gen_random_uuid() NOT NULL,
    device_id uuid NOT NULL,
    issued_by character varying(64),
    qr_payload character varying(256) NOT NULL,
    issued_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE device_qr_issuance; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.device_qr_issuance IS '设备二维码签发记录（CFG-005；批量印制留痕，供回溯）';


-- Name: emotion_diaries; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.emotion_diaries (
    diary_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id uuid NOT NULL,
    student_user_id uuid NOT NULL,
    emotion_label character varying(32) NOT NULL,
    intensity smallint DEFAULT 3 NOT NULL,
    note character varying(512) DEFAULT NULL::character varying,
    diary_date date DEFAULT CURRENT_DATE NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE emotion_diaries; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.emotion_diaries IS '情绪日记表：学生每日情绪打卡（每天一条）';


-- Name: knowledge_chunks; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.knowledge_chunks (
    chunk_id uuid DEFAULT gen_random_uuid() NOT NULL,
    doc_id uuid NOT NULL,
    tenant_id uuid,
    chunk_index integer NOT NULL,
    content text NOT NULL,
    embedding public.vector(1536),
    token_count integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: knowledge_documents; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.knowledge_documents (
    doc_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    title character varying(200) NOT NULL,
    category character varying(50) DEFAULT 'general'::character varying NOT NULL,
    source character varying(200),
    content text NOT NULL,
    status character varying(20) DEFAULT 'draft'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    grade_band character varying(20),
    source_type character varying(30),
    evidence_level character varying(20),
    reviewer character varying(100),
    reviewed_at timestamp with time zone
);


-- Name: long_term_memories; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.long_term_memories (
    memory_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    student_user_id uuid NOT NULL,
    session_id uuid,
    memory_type character varying(30) DEFAULT 'key_event'::character varying NOT NULL,
    content text NOT NULL,
    emotion_context character varying(50),
    importance real DEFAULT 0.5 NOT NULL,
    recall_count integer DEFAULT 0 NOT NULL,
    last_recalled_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: message_summaries; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.message_summaries (
    summary_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id uuid NOT NULL,
    session_id uuid NOT NULL,
    student_user_id uuid NOT NULL,
    turn_count integer DEFAULT 0 NOT NULL,
    emotion_tags jsonb DEFAULT '[]'::jsonb,
    topic_tags jsonb DEFAULT '[]'::jsonb,
    risk_signals jsonb DEFAULT '[]'::jsonb,
    suggested_next_action character varying(256),
    content_hash character(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    sender_type character varying(20) DEFAULT 'student'::character varying,
    emotion_label character varying(64) DEFAULT NULL::character varying,
    risk_level smallint DEFAULT 0,
    content_summary text DEFAULT NULL::character varying,
    cbt_fields jsonb
);


-- Name: TABLE message_summaries; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.message_summaries IS '消息摘要表：替代完整聊天记录，仅存结构化摘要';


-- Name: COLUMN message_summaries.sender_type; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.message_summaries.sender_type IS '消息发送者类型（student/ai）';


-- Name: COLUMN message_summaries.content_summary; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.message_summaries.content_summary IS '单条消息内容摘要（R-01 字段级加密密文，TEXT 容纳 AES-GCM 密文膨胀，AUDIT-P0-3 从 VARCHAR(1024) 扩展）';


-- Name: COLUMN message_summaries.cbt_fields; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.message_summaries.cbt_fields IS 'CBT 结构化字段（emotion_label, auto_thought, balanced_thought 等）';


-- Name: model_call_logs; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.model_call_logs (
    call_log_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id uuid NOT NULL,
    session_id uuid,
    agent_name character varying(64) NOT NULL,
    prompt_version character varying(64),
    model_version character varying(128),
    input_tokens integer DEFAULT 0,
    output_tokens integer DEFAULT 0,
    total_tokens integer DEFAULT 0,
    latency_ms integer DEFAULT 0,
    status character varying(20) DEFAULT 'success'::character varying NOT NULL,
    error_message character varying(512),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE model_call_logs; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.model_call_logs IS '模型调用日志表：每次 LLM 调用的性能与成本追踪';


-- Name: notifications; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.notifications (
    notification_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id uuid NOT NULL,
    school_id uuid,
    recipient_user_id uuid NOT NULL,
    recipient_role character varying(64),
    channel character varying(32) DEFAULT 'in_app'::character varying NOT NULL,
    template_code character varying(64),
    severity smallint DEFAULT 1 NOT NULL,
    title character varying(128) NOT NULL,
    body_summary character varying(512),
    payload_enc bytea,
    related_type character varying(64),
    related_id uuid,
    delivery_status character varying(20) DEFAULT 'pending'::character varying NOT NULL,
    sent_at timestamp with time zone,
    read_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE notifications; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.notifications IS '通知表：预警推送、系统消息（内容按角色最小化）';


-- Name: parent_accounts; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.parent_accounts (
    parent_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id uuid NOT NULL,
    phone character varying(20) NOT NULL,
    password_hash character varying(100) NOT NULL,
    display_name character varying(30),
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    last_login_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE parent_accounts; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.parent_accounts IS '家长账号：手机号+密码登录，通过家庭码绑定学生';


-- Name: parent_student_links; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.parent_student_links (
    link_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id uuid NOT NULL,
    parent_id uuid NOT NULL,
    student_user_id uuid NOT NULL,
    relation character varying(20) DEFAULT 'parent'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE parent_student_links; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.parent_student_links IS '家长-学生关联：一个家长可绑多个孩子，一个孩子可绑多个家长';


-- Name: COLUMN parent_student_links.relation; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.parent_student_links.relation IS '关系：father/mother/grandparent/other';


-- Name: platform_admin; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.platform_admin (
    admin_id uuid DEFAULT gen_random_uuid() NOT NULL,
    username character varying(64) NOT NULL,
    password_hash character varying(100) NOT NULL,
    role character varying(16) NOT NULL,
    display_name character varying(64),
    status character varying(16) DEFAULT 'active'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_login_at timestamp with time zone
);


-- Name: TABLE platform_admin; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.platform_admin IS '平台管理员账号（M6，独立于租户 users 表，DEC-007）';


-- Name: COLUMN platform_admin.role; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.platform_admin.role IS '角色: super_admin/ops_admin/finance_admin/audit（四角色 RBAC）';


-- Name: COLUMN platform_admin.status; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.platform_admin.status IS '状态: active/disabled（禁用账号拒绝登录）';


-- Name: prompt_versions; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.prompt_versions (
    version_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    template_key character varying(50) NOT NULL,
    version integer NOT NULL,
    content text NOT NULL,
    description character varying(500),
    ab_group character varying(20) DEFAULT 'control'::character varying NOT NULL,
    is_active boolean DEFAULT false NOT NULL,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    status character varying(16) DEFAULT 'draft'::character varying NOT NULL
);


-- Name: TABLE prompt_versions; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.prompt_versions IS 'AI-005: Prompt 版本管理（支持 A/B 测试分组）';


-- Name: COLUMN prompt_versions.ab_group; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.prompt_versions.ab_group IS 'A/B 分组: control=对照组, treatment_a/b=实验组';


-- Name: COLUMN prompt_versions.status; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.prompt_versions.status IS '审核发布流状态: draft/pending_review/approved/active/retired（M7，§6.10；is_active 保留兼容，激活时两者同步）';


-- Name: quality_scores; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.quality_scores (
    score_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    session_id uuid NOT NULL,
    empathy_score numeric(4,3),
    cbt_completion numeric(4,3),
    safety_compliance numeric(4,3),
    engagement_score numeric(4,3),
    overall_score numeric(4,3),
    evaluator character varying(32) DEFAULT 'llm-judge'::character varying NOT NULL,
    flagged boolean DEFAULT false NOT NULL,
    flag_reason character varying(256),
    raw_response text,
    evaluated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE quality_scores; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.quality_scores IS '对话质量评估（LLM-as-Judge 异步评分）';


-- Name: COLUMN quality_scores.empathy_score; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.quality_scores.empathy_score IS '共情度：AI 是否准确识别并回应学生情绪';


-- Name: COLUMN quality_scores.cbt_completion; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.quality_scores.cbt_completion IS 'CBT 完成度：是否推进了 CBT 流程（情境→想法→感受→替代想法）';


-- Name: COLUMN quality_scores.safety_compliance; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.quality_scores.safety_compliance IS '安全合规：是否遵守危机干预规则、不越界、不诊断';


-- Name: COLUMN quality_scores.engagement_score; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.quality_scores.engagement_score IS '互动投入度：学生参与程度、对话深度、是否有效引导';


-- Name: relaxation_sessions; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.relaxation_sessions (
    relaxation_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id uuid NOT NULL,
    student_user_id uuid NOT NULL,
    exercise_type character varying(64) DEFAULT 'breathing_323'::character varying NOT NULL,
    duration_seconds integer DEFAULT 0,
    completed boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE relaxation_sessions; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.relaxation_sessions IS '放松练习记录表：呼吸练习/正念练习完成记录';


-- Name: risk_events; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.risk_events (
    risk_event_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id uuid NOT NULL,
    school_id uuid,
    student_user_id uuid NOT NULL,
    source_type character varying(32) DEFAULT 'session'::character varying NOT NULL,
    source_id uuid,
    risk_type character varying(64) NOT NULL,
    risk_level smallint NOT NULL,
    trigger_signal_summary_enc bytea,
    detected_by character varying(64) DEFAULT 'agent'::character varying NOT NULL,
    detected_at timestamp with time zone DEFAULT now() NOT NULL,
    status character varying(20) DEFAULT 'open'::character varying NOT NULL,
    assigned_user_id uuid,
    closed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    resolution_note text,
    resolved_at timestamp with time zone,
    follow_up_at timestamp with time zone,
    follow_up_note text,
    follow_up_done boolean DEFAULT false NOT NULL,
    outcome character varying(30),
    notify_status character varying(20) DEFAULT 'pending'::character varying NOT NULL,
    notify_attempts smallint DEFAULT 0 NOT NULL,
    last_notify_attempt_at timestamp with time zone,
    risk_score smallint,
    reason_codes jsonb,
    review_json jsonb
);


-- Name: TABLE risk_events; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.risk_events IS '风险事件表：由会话/测评/人工触发的风险记录';


-- Name: COLUMN risk_events.resolution_note; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.risk_events.resolution_note IS '处置记录（教师填写）';


-- Name: COLUMN risk_events.follow_up_at; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.risk_events.follow_up_at IS '计划回访时间';


-- Name: COLUMN risk_events.follow_up_note; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.risk_events.follow_up_note IS '回访记录';


-- Name: COLUMN risk_events.outcome; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.risk_events.outcome IS '最终评估结果';


-- Name: COLUMN risk_events.notify_status; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.risk_events.notify_status IS '通知状态: pending/sent/failed/dead（outbox 补偿，P0-4）';


-- Name: COLUMN risk_events.notify_attempts; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.risk_events.notify_attempts IS '通知尝试次数（含补偿扫描重试，上限 5 次）';


-- Name: COLUMN risk_events.last_notify_attempt_at; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.risk_events.last_notify_attempt_at IS '最后一次通知尝试时间';


-- Name: COLUMN risk_events.risk_score; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.risk_events.risk_score IS '结构化风险评分（RISK-203，0-100，供教师端排序/复核/画像）';


-- Name: COLUMN risk_events.reason_codes; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.risk_events.reason_codes IS '可解释评分项 JSON 数组（如 ["intent_explicit","plan_method"]，供教师复核/审计）';


-- Name: COLUMN risk_events.review_json; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.risk_events.review_json IS 'Layer2 输出安全审查 JSON（LLM reviewJson 原文，output_review 留痕与 recall 召回共用；TC260 人工抽检依据）';


-- Name: roles; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.roles (
    role_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id uuid NOT NULL,
    role_code character varying(64) NOT NULL,
    role_name character varying(128) NOT NULL,
    scope_level character varying(20) DEFAULT 'school'::character varying NOT NULL,
    permission_set jsonb DEFAULT '[]'::jsonb,
    is_system boolean DEFAULT false NOT NULL,
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE roles; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.roles IS '角色表：RBAC 角色定义';


-- Name: service_health_snapshots; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.service_health_snapshots (
    snapshot_id bigint NOT NULL,
    service character varying(16) NOT NULL,
    status character varying(16) NOT NULL,
    detail jsonb,
    sampled_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE service_health_snapshots; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.service_health_snapshots IS '服务健康快照（M2 服务拓扑历史/SLA 验证，30s 采样，保留 30 天）';


-- Name: COLUMN service_health_snapshots.status; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.service_health_snapshots.status IS '状态: UP/DEGRADED/DOWN（语义对齐 service-manager）';


-- Name: service_health_snapshots_snapshot_id_seq; Type: SEQUENCE; Schema: tenant_template; Owner: -

CREATE SEQUENCE tenant_template.service_health_snapshots_snapshot_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


-- Name: service_health_snapshots_snapshot_id_seq; Type: SEQUENCE OWNED BY; Schema: tenant_template; Owner: -

ALTER SEQUENCE tenant_template.service_health_snapshots_snapshot_id_seq OWNED BY tenant_template.service_health_snapshots.snapshot_id;


-- Name: sla_escalation_log; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.sla_escalation_log (
    escalation_id uuid DEFAULT gen_random_uuid() NOT NULL,
    risk_event_id uuid NOT NULL,
    stage character varying(16) NOT NULL,
    expected_at timestamp with time zone,
    escalated_at timestamp with time zone DEFAULT now() NOT NULL,
    action character varying(32) NOT NULL,
    operator character varying(64),
    detail character varying(512)
);


-- Name: TABLE sla_escalation_log; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.sla_escalation_log IS 'SLA 逾期升级留痕（M8，平台级表：无 tenant_id 列）';


-- Name: COLUMN sla_escalation_log.action; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.sla_escalation_log.action IS 'notify_escalate=通知升级（扫描器自动）/transfer=转派/force_close=强制关闭（平台操作）';


-- Name: student_profiles; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.student_profiles (
    profile_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    emotion_baseline jsonb DEFAULT '{}'::jsonb NOT NULL,
    communication_pref jsonb DEFAULT '{}'::jsonb NOT NULL,
    resilience jsonb DEFAULT '{}'::jsonb NOT NULL,
    risk_trajectory jsonb DEFAULT '{}'::jsonb NOT NULL,
    social_graph jsonb DEFAULT '{}'::jsonb NOT NULL,
    growth_track jsonb DEFAULT '{}'::jsonb NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    total_sessions integer DEFAULT 0 NOT NULL,
    last_updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    personality_traits jsonb DEFAULT '{}'::jsonb NOT NULL
);


-- Name: TABLE student_profiles; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.student_profiles IS '学生心理画像（结构化统计，不存原始对话）';


-- Name: COLUMN student_profiles.emotion_baseline; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.student_profiles.emotion_baseline IS '情绪基线：分布/波动度/触发主题';


-- Name: COLUMN student_profiles.communication_pref; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.student_profiles.communication_pref IS '沟通偏好：表达深度/偏好风格/活跃时段';


-- Name: COLUMN student_profiles.resilience; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.student_profiles.resilience IS '心理韧性：恢复速度/应对技巧/自我效能';


-- Name: COLUMN student_profiles.risk_trajectory; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.student_profiles.risk_trajectory IS '风险轨迹：等级分布/趋势/敏感主题';


-- Name: COLUMN student_profiles.social_graph; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.student_profiles.social_graph IS '社交图谱：关键人物(代号化)/满意度/求助意愿';


-- Name: COLUMN student_profiles.growth_track; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.student_profiles.growth_track IS '成长轨迹：频率/里程碑/干预有效性';


-- Name: COLUMN student_profiles.personality_traits; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.student_profiles.personality_traits IS '性格特征（LLM 提炼）：introversion/sensitivity/curiosity/dominant_interests';


-- Name: sys_config; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.sys_config (
    config_id uuid DEFAULT gen_random_uuid() NOT NULL,
    config_key character varying(128) NOT NULL,
    domain character varying(32) NOT NULL,
    value text,
    value_type character varying(16) DEFAULT 'string'::character varying NOT NULL,
    sensitive character varying(8) DEFAULT 'NORMAL'::character varying NOT NULL,
    effect_mode character varying(8) DEFAULT 'RESTART'::character varying NOT NULL,
    source character varying(32) DEFAULT 'db'::character varying NOT NULL,
    description character varying(512),
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by character varying(64)
);


-- Name: TABLE sys_config; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.sys_config IS '配置注册表（M1；V38 种子：首批 HOT 可改键，SECRET 存掩码）';


-- Name: COLUMN sys_config.sensitive; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.sys_config.sensitive IS 'NORMAL=值可读 / SECRET=仅显示已配置未配置，值永不出 API';


-- Name: COLUMN sys_config.effect_mode; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.sys_config.effect_mode IS 'HOT=修改即时生效 / RESTART=需重启生效（仅标记 HOT 开放修改，R-3）';


-- Name: sys_config_history; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.sys_config_history (
    history_id uuid DEFAULT gen_random_uuid() NOT NULL,
    config_key character varying(128) NOT NULL,
    old_value text,
    new_value text,
    changed_by character varying(64) NOT NULL,
    reason character varying(512),
    changed_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE sys_config_history; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.sys_config_history IS '配置变更历史（M1，留痕）';


-- Name: teacher_notes; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.teacher_notes (
    note_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id uuid NOT NULL,
    student_user_id uuid NOT NULL,
    teacher_user_id uuid NOT NULL,
    content character varying(2048) NOT NULL,
    note_type character varying(32) DEFAULT 'general'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE teacher_notes; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.teacher_notes IS '教师备注表：个案管理备注';


-- Name: toc_child_profiles; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.toc_child_profiles (
    profile_id uuid DEFAULT gen_random_uuid() NOT NULL,
    family_account_id uuid NOT NULL,
    nickname character varying(50) NOT NULL,
    age integer,
    gender character varying(16),
    interests character varying(500),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE toc_child_profiles; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.toc_child_profiles IS 'toC 孩子档案（TOC-002；平台级表，一账号多孩，按 family_account_id 隔离）';


-- Name: COLUMN toc_child_profiles.interests; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.toc_child_profiles.interests IS '兴趣标签（逗号分隔；成长报告 TOC-004 算法输入，非原始对话）';


-- Name: toc_family_accounts; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.toc_family_accounts (
    family_account_id uuid DEFAULT gen_random_uuid() NOT NULL,
    phone character varying(20) NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE toc_family_accounts; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.toc_family_accounts IS 'toC 家庭账号（TOC-001；平台级表，无 tenant_id 列，独立于校园体系）';


-- Name: COLUMN toc_family_accounts.status; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.toc_family_accounts.status IS 'ACTIVE=正常 / DISABLED=禁用（隐私控制关闭设备/账号）';


-- Name: trial_invite_codes; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.trial_invite_codes (
    code_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id uuid NOT NULL,
    code character varying(32) NOT NULL,
    max_uses integer DEFAULT 1 NOT NULL,
    used_count integer DEFAULT 0 NOT NULL,
    expires_at timestamp with time zone,
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    bound_user_id uuid,
    used_at timestamp with time zone,
    batch_id character varying(50),
    generated_by uuid
);


-- Name: TABLE trial_invite_codes; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.trial_invite_codes IS '试用邀请码：控制试用准入人群范围';


-- Name: COLUMN trial_invite_codes.bound_user_id; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.trial_invite_codes.bound_user_id IS '绑定的用户ID（一人一码，用后填入）';


-- Name: COLUMN trial_invite_codes.used_at; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.trial_invite_codes.used_at IS '实际使用时间';


-- Name: COLUMN trial_invite_codes.batch_id; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.trial_invite_codes.batch_id IS '批次号（教师批量生成时标记）';


-- Name: COLUMN trial_invite_codes.generated_by; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.trial_invite_codes.generated_by IS '生成者（教师 userId）';


-- Name: usage_events; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.usage_events (
    event_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    metric character varying(32) NOT NULL,
    value numeric NOT NULL,
    unit character varying(16) NOT NULL,
    event_time timestamp with time zone NOT NULL,
    ref_id uuid
);


-- Name: TABLE usage_events; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.usage_events IS '计量事件（M4 采集层，计量非计费，DEC-007 先行）';


-- Name: COLUMN usage_events.metric; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.usage_events.metric IS 'active_student_snapshot=活跃学生快照/llm_call=LLM 调用（token）/tts_call/asr_call';


-- Name: COLUMN usage_events.unit; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.usage_events.unit IS 'count/token/seconds';


-- Name: user_roles; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.user_roles (
    user_role_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role_id uuid NOT NULL,
    school_id uuid,
    grade_code character varying(32),
    class_code character varying(32),
    effective_from timestamp with time zone DEFAULT now() NOT NULL,
    effective_to timestamp with time zone,
    granted_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE user_roles; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.user_roles IS '用户角色授权表：多对多绑定';


-- Name: users; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.users (
    user_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id uuid NOT NULL,
    school_id uuid,
    user_type character varying(20) NOT NULL,
    external_subject_id_hash character(64),
    display_name_enc bytea,
    pseudonym character varying(64),
    grade_code character varying(32),
    class_code character varying(32),
    student_no_hash character(64),
    mobile_enc bytea,
    email_enc bytea,
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    last_login_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    password_hash character varying(128),
    must_change_password boolean DEFAULT false NOT NULL,
    gender character varying(10),
    pin_hash character varying(100),
    pin_set_at timestamp with time zone,
    password_changed_at timestamp with time zone,
    family_code character varying(6),
    dialect character varying(32)
);


-- Name: TABLE users; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.users IS '用户表：统一账号（student/teacher/head_teacher/admin/guardian）';


-- Name: COLUMN users.must_change_password; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.users.must_change_password IS '首次登录强制改密标记（方案 B：临时密码 + 首次改密）';


-- Name: COLUMN users.gender; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.users.gender IS '性别：male/female，用于对话风格与 TTS 音色个性化';


-- Name: COLUMN users.pin_hash; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.users.pin_hash IS 'PIN码BCrypt哈希（4-6位数字）';


-- Name: COLUMN users.pin_set_at; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.users.pin_set_at IS 'PIN码设置时间';


-- Name: COLUMN users.password_changed_at; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.users.password_changed_at IS '最近一次密码修改时间（用于 90 天过期判断，NULL 视为从未设置）';


-- Name: COLUMN users.family_code; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.users.family_code IS '家庭码（6位字母数字，学生注册时生成，家长绑定凭证）';


-- Name: COLUMN users.dialect; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON COLUMN tenant_template.users.dialect IS '方言偏好（cantonese/northeastern/sichuan/henan/shandong/hunan/shaanxi/anhui），NULL=普通话';


-- Name: voiceprint_embeddings; Type: TABLE; Schema: tenant_template; Owner: -

CREATE TABLE tenant_template.voiceprint_embeddings (
    id bigint NOT NULL,
    user_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    embedding jsonb NOT NULL,
    sample_index smallint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- Name: TABLE voiceprint_embeddings; Type: COMMENT; Schema: tenant_template; Owner: -

COMMENT ON TABLE tenant_template.voiceprint_embeddings IS '声纹特征向量（remote模式）：仅存256-dim embedding，不存音频，PIPL数据最小化';


-- Name: voiceprint_embeddings_id_seq; Type: SEQUENCE; Schema: tenant_template; Owner: -

CREATE SEQUENCE tenant_template.voiceprint_embeddings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


-- Name: voiceprint_embeddings_id_seq; Type: SEQUENCE OWNED BY; Schema: tenant_template; Owner: -

ALTER SEQUENCE tenant_template.voiceprint_embeddings_id_seq OWNED BY tenant_template.voiceprint_embeddings.id;


-- Name: service_health_snapshots snapshot_id; Type: DEFAULT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.service_health_snapshots ALTER COLUMN snapshot_id SET DEFAULT nextval('tenant_template.service_health_snapshots_snapshot_id_seq'::regclass);


-- Name: voiceprint_embeddings id; Type: DEFAULT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.voiceprint_embeddings ALTER COLUMN id SET DEFAULT nextval('tenant_template.voiceprint_embeddings_id_seq'::regclass);


-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: -

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


-- Name: schools schools_pkey; Type: CONSTRAINT; Schema: public; Owner: -

ALTER TABLE ONLY public.schools
    ADD CONSTRAINT schools_pkey PRIMARY KEY (school_id);


-- Name: tenants tenants_pkey; Type: CONSTRAINT; Schema: public; Owner: -

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT tenants_pkey PRIMARY KEY (tenant_id);


-- Name: schools uq_schools_tenant_code; Type: CONSTRAINT; Schema: public; Owner: -

ALTER TABLE ONLY public.schools
    ADD CONSTRAINT uq_schools_tenant_code UNIQUE (tenant_id, school_code);


-- Name: tenants uq_tenants_code; Type: CONSTRAINT; Schema: public; Owner: -

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT uq_tenants_code UNIQUE (tenant_code);


-- Name: alert_events alert_events_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.alert_events
    ADD CONSTRAINT alert_events_pkey PRIMARY KEY (event_id);


-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (audit_log_id);


-- Name: consent_records consent_records_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.consent_records
    ADD CONSTRAINT consent_records_pkey PRIMARY KEY (consent_id);


-- Name: counseling_sessions counseling_sessions_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.counseling_sessions
    ADD CONSTRAINT counseling_sessions_pkey PRIMARY KEY (session_id);


-- Name: degradation_events degradation_events_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.degradation_events
    ADD CONSTRAINT degradation_events_pkey PRIMARY KEY (event_id);


-- Name: device_bind_codes device_bind_codes_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.device_bind_codes
    ADD CONSTRAINT device_bind_codes_pkey PRIMARY KEY (code_id);


-- Name: device_bindings device_bindings_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.device_bindings
    ADD CONSTRAINT device_bindings_pkey PRIMARY KEY (binding_id);


-- Name: device_operations device_operations_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.device_operations
    ADD CONSTRAINT device_operations_pkey PRIMARY KEY (operation_id);


-- Name: device device_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.device
    ADD CONSTRAINT device_pkey PRIMARY KEY (device_id);


-- Name: device_preferences device_preferences_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.device_preferences
    ADD CONSTRAINT device_preferences_pkey PRIMARY KEY (pref_id);


-- Name: device_qr_issuance device_qr_issuance_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.device_qr_issuance
    ADD CONSTRAINT device_qr_issuance_pkey PRIMARY KEY (issuance_id);


-- Name: emotion_diaries emotion_diaries_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.emotion_diaries
    ADD CONSTRAINT emotion_diaries_pkey PRIMARY KEY (diary_id);


-- Name: knowledge_chunks knowledge_chunks_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.knowledge_chunks
    ADD CONSTRAINT knowledge_chunks_pkey PRIMARY KEY (chunk_id);


-- Name: knowledge_documents knowledge_documents_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.knowledge_documents
    ADD CONSTRAINT knowledge_documents_pkey PRIMARY KEY (doc_id);


-- Name: long_term_memories long_term_memories_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.long_term_memories
    ADD CONSTRAINT long_term_memories_pkey PRIMARY KEY (memory_id);


-- Name: message_summaries message_summaries_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.message_summaries
    ADD CONSTRAINT message_summaries_pkey PRIMARY KEY (summary_id);


-- Name: model_call_logs model_call_logs_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.model_call_logs
    ADD CONSTRAINT model_call_logs_pkey PRIMARY KEY (call_log_id);


-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (notification_id);


-- Name: parent_accounts parent_accounts_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.parent_accounts
    ADD CONSTRAINT parent_accounts_pkey PRIMARY KEY (parent_id);


-- Name: parent_student_links parent_student_links_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.parent_student_links
    ADD CONSTRAINT parent_student_links_pkey PRIMARY KEY (link_id);


-- Name: platform_admin platform_admin_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.platform_admin
    ADD CONSTRAINT platform_admin_pkey PRIMARY KEY (admin_id);


-- Name: platform_admin platform_admin_username_key; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.platform_admin
    ADD CONSTRAINT platform_admin_username_key UNIQUE (username);


-- Name: prompt_versions prompt_versions_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.prompt_versions
    ADD CONSTRAINT prompt_versions_pkey PRIMARY KEY (version_id);


-- Name: quality_scores quality_scores_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.quality_scores
    ADD CONSTRAINT quality_scores_pkey PRIMARY KEY (score_id);


-- Name: relaxation_sessions relaxation_sessions_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.relaxation_sessions
    ADD CONSTRAINT relaxation_sessions_pkey PRIMARY KEY (relaxation_id);


-- Name: risk_events risk_events_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.risk_events
    ADD CONSTRAINT risk_events_pkey PRIMARY KEY (risk_event_id);


-- Name: roles roles_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (role_id);


-- Name: service_health_snapshots service_health_snapshots_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.service_health_snapshots
    ADD CONSTRAINT service_health_snapshots_pkey PRIMARY KEY (snapshot_id);


-- Name: sla_escalation_log sla_escalation_log_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.sla_escalation_log
    ADD CONSTRAINT sla_escalation_log_pkey PRIMARY KEY (escalation_id);


-- Name: student_profiles student_profiles_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.student_profiles
    ADD CONSTRAINT student_profiles_pkey PRIMARY KEY (profile_id);


-- Name: sys_config sys_config_config_key_key; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.sys_config
    ADD CONSTRAINT sys_config_config_key_key UNIQUE (config_key);


-- Name: sys_config_history sys_config_history_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.sys_config_history
    ADD CONSTRAINT sys_config_history_pkey PRIMARY KEY (history_id);


-- Name: sys_config sys_config_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.sys_config
    ADD CONSTRAINT sys_config_pkey PRIMARY KEY (config_id);


-- Name: teacher_notes teacher_notes_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.teacher_notes
    ADD CONSTRAINT teacher_notes_pkey PRIMARY KEY (note_id);


-- Name: toc_child_profiles toc_child_profiles_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.toc_child_profiles
    ADD CONSTRAINT toc_child_profiles_pkey PRIMARY KEY (profile_id);


-- Name: toc_family_accounts toc_family_accounts_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.toc_family_accounts
    ADD CONSTRAINT toc_family_accounts_pkey PRIMARY KEY (family_account_id);


-- Name: trial_invite_codes trial_invite_codes_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.trial_invite_codes
    ADD CONSTRAINT trial_invite_codes_pkey PRIMARY KEY (code_id);


-- Name: device uk_device_code; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.device
    ADD CONSTRAINT uk_device_code UNIQUE (device_code);


-- Name: device_preferences uk_device_prefs_family; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.device_preferences
    ADD CONSTRAINT uk_device_prefs_family UNIQUE (device_code, family_account_id);


-- Name: device uk_device_sn; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.device
    ADD CONSTRAINT uk_device_sn UNIQUE (sn);


-- Name: toc_family_accounts uk_toc_family_phone; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.toc_family_accounts
    ADD CONSTRAINT uk_toc_family_phone UNIQUE (phone);


-- Name: trial_invite_codes uq_invite_code; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.trial_invite_codes
    ADD CONSTRAINT uq_invite_code UNIQUE (tenant_id, code);


-- Name: parent_accounts uq_parent_phone; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.parent_accounts
    ADD CONSTRAINT uq_parent_phone UNIQUE (tenant_id, phone);


-- Name: parent_student_links uq_parent_student; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.parent_student_links
    ADD CONSTRAINT uq_parent_student UNIQUE (parent_id, student_user_id);


-- Name: prompt_versions uq_prompt_version; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.prompt_versions
    ADD CONSTRAINT uq_prompt_version UNIQUE (tenant_id, template_key, version, ab_group);


-- Name: quality_scores uq_quality_session; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.quality_scores
    ADD CONSTRAINT uq_quality_session UNIQUE (tenant_id, session_id);


-- Name: roles uq_roles_tenant_code; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.roles
    ADD CONSTRAINT uq_roles_tenant_code UNIQUE (tenant_id, role_code);


-- Name: student_profiles uq_student_profile; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.student_profiles
    ADD CONSTRAINT uq_student_profile UNIQUE (tenant_id, user_id);


-- Name: usage_events usage_events_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.usage_events
    ADD CONSTRAINT usage_events_pkey PRIMARY KEY (event_id);


-- Name: user_roles user_roles_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.user_roles
    ADD CONSTRAINT user_roles_pkey PRIMARY KEY (user_role_id);


-- Name: users users_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (user_id);


-- Name: voiceprint_embeddings voiceprint_embeddings_pkey; Type: CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.voiceprint_embeddings
    ADD CONSTRAINT voiceprint_embeddings_pkey PRIMARY KEY (id);


-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: -

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


-- Name: idx_schools_tenant_status; Type: INDEX; Schema: public; Owner: -

CREATE INDEX idx_schools_tenant_status ON public.schools USING btree (tenant_id, status);


-- Name: idx_tenants_status; Type: INDEX; Schema: public; Owner: -

CREATE INDEX idx_tenants_status ON public.tenants USING btree (status);


-- Name: idx_alert_events_status_time; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_alert_events_status_time ON tenant_template.alert_events USING btree (status, fired_at DESC);


-- Name: idx_audit_action; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_audit_action ON tenant_template.audit_logs USING btree (tenant_id, action, created_at DESC);


-- Name: idx_audit_logs_action; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_audit_logs_action ON tenant_template.audit_logs USING btree (tenant_id, action, created_at DESC);


-- Name: idx_audit_logs_tenant_time; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_audit_logs_tenant_time ON tenant_template.audit_logs USING btree (tenant_id, created_at DESC);


-- Name: idx_audit_resource; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_audit_resource ON tenant_template.audit_logs USING btree (tenant_id, resource_type, resource_id);


-- Name: idx_audit_user_time; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_audit_user_time ON tenant_template.audit_logs USING btree (tenant_id, user_id, created_at DESC);


-- Name: idx_consent_user; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_consent_user ON tenant_template.consent_records USING btree (tenant_id, user_id, consent_type);


-- Name: idx_degradation_events_point_time; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_degradation_events_point_time ON tenant_template.degradation_events USING btree (point, occurred_at DESC);


-- Name: idx_device_bindings_device; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_device_bindings_device ON tenant_template.device_bindings USING btree (device_id, status);


-- Name: idx_device_bindings_target; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_device_bindings_target ON tenant_template.device_bindings USING btree (bind_type, bind_target_id, status);


-- Name: idx_device_codes_device; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_device_codes_device ON tenant_template.device_bind_codes USING btree (device_id, used_at);


-- Name: idx_device_ops_action; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_device_ops_action ON tenant_template.device_operations USING btree (action);


-- Name: idx_device_ops_device; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_device_ops_device ON tenant_template.device_operations USING btree (device_code);


-- Name: idx_device_qr_device; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_device_qr_device ON tenant_template.device_qr_issuance USING btree (device_id);


-- Name: idx_diary_student_time; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_diary_student_time ON tenant_template.emotion_diaries USING btree (tenant_id, student_user_id, diary_date DESC);


-- Name: idx_invite_code_lookup; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_invite_code_lookup ON tenant_template.trial_invite_codes USING btree (tenant_id, code, status);


-- Name: idx_knowledge_chunks_doc; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_knowledge_chunks_doc ON tenant_template.knowledge_chunks USING btree (doc_id);


-- Name: idx_knowledge_chunks_embedding; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_knowledge_chunks_embedding ON tenant_template.knowledge_chunks USING ivfflat (embedding public.vector_cosine_ops) WITH (lists='100');


-- Name: idx_knowledge_documents_tenant; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_knowledge_documents_tenant ON tenant_template.knowledge_documents USING btree (tenant_id, category, status);


-- Name: idx_long_term_memories_student; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_long_term_memories_student ON tenant_template.long_term_memories USING btree (tenant_id, student_user_id, importance DESC);


-- Name: idx_ltm_session; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_ltm_session ON tenant_template.long_term_memories USING btree (session_id);


-- Name: idx_ltm_student_importance; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_ltm_student_importance ON tenant_template.long_term_memories USING btree (tenant_id, student_user_id, importance DESC, created_at DESC);


-- Name: idx_message_summaries_session; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_message_summaries_session ON tenant_template.message_summaries USING btree (session_id);


-- Name: idx_model_call_agent; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_model_call_agent ON tenant_template.model_call_logs USING btree (tenant_id, agent_name, created_at DESC);


-- Name: idx_model_call_session; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_model_call_session ON tenant_template.model_call_logs USING btree (session_id, created_at DESC);


-- Name: idx_model_call_time; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_model_call_time ON tenant_template.model_call_logs USING btree (tenant_id, created_at DESC);


-- Name: idx_msgsum_session; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_msgsum_session ON tenant_template.message_summaries USING btree (session_id);


-- Name: idx_msgsum_student_time; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_msgsum_student_time ON tenant_template.message_summaries USING btree (tenant_id, student_user_id, created_at DESC);


-- Name: idx_notes_student; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_notes_student ON tenant_template.teacher_notes USING btree (tenant_id, student_user_id, created_at DESC);


-- Name: idx_notifications_recipient; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_notifications_recipient ON tenant_template.notifications USING btree (tenant_id, recipient_user_id, delivery_status);


-- Name: idx_notifications_related; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_notifications_related ON tenant_template.notifications USING btree (related_type, related_id);


-- Name: idx_parent_accounts_phone; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_parent_accounts_phone ON tenant_template.parent_accounts USING btree (tenant_id, phone, status);


-- Name: idx_parent_links_parent; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_parent_links_parent ON tenant_template.parent_student_links USING btree (tenant_id, parent_id);


-- Name: idx_parent_links_student; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_parent_links_student ON tenant_template.parent_student_links USING btree (tenant_id, student_user_id);


-- Name: idx_profile_user; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_profile_user ON tenant_template.student_profiles USING btree (tenant_id, user_id);


-- Name: idx_prompt_versions_active; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_prompt_versions_active ON tenant_template.prompt_versions USING btree (template_key, ab_group, is_active) WHERE (is_active = true);


-- Name: idx_prompt_versions_lookup; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_prompt_versions_lookup ON tenant_template.prompt_versions USING btree (tenant_id, template_key, is_active, ab_group);


-- Name: idx_prompt_versions_status; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_prompt_versions_status ON tenant_template.prompt_versions USING btree (status);


-- Name: idx_prompt_versions_tenant; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_prompt_versions_tenant ON tenant_template.prompt_versions USING btree (tenant_id, template_key);


-- Name: idx_quality_flagged; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_quality_flagged ON tenant_template.quality_scores USING btree (tenant_id, flagged) WHERE (flagged = true);


-- Name: idx_quality_scores_session; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_quality_scores_session ON tenant_template.quality_scores USING btree (session_id);


-- Name: idx_quality_scores_tenant_time; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_quality_scores_tenant_time ON tenant_template.quality_scores USING btree (tenant_id, evaluated_at DESC);


-- Name: idx_quality_tenant; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_quality_tenant ON tenant_template.quality_scores USING btree (tenant_id, evaluated_at DESC);


-- Name: idx_relaxation_student; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_relaxation_student ON tenant_template.relaxation_sessions USING btree (tenant_id, student_user_id, created_at DESC);


-- Name: idx_risk_events_tenant_status; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_risk_events_tenant_status ON tenant_template.risk_events USING btree (tenant_id, status, created_at DESC);


-- Name: idx_risk_events_tenant_type; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_risk_events_tenant_type ON tenant_template.risk_events USING btree (tenant_id, risk_type, created_at DESC);


-- Name: idx_risk_followup; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_risk_followup ON tenant_template.risk_events USING btree (tenant_id, follow_up_at) WHERE ((follow_up_done = false) AND (follow_up_at IS NOT NULL));


-- Name: idx_risk_notify_retry; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_risk_notify_retry ON tenant_template.risk_events USING btree (notify_status, notify_attempts, detected_at DESC) WHERE (((notify_status)::text = ANY ((ARRAY['pending'::character varying, 'failed'::character varying])::text[])) AND (notify_attempts < 5));


-- Name: idx_risk_open; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_risk_open ON tenant_template.risk_events USING btree (tenant_id, school_id, status, risk_level DESC, detected_at DESC);


-- Name: idx_risk_student_time; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_risk_student_time ON tenant_template.risk_events USING btree (tenant_id, student_user_id, detected_at DESC);


-- Name: idx_service_health_snapshots_service_time; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_service_health_snapshots_service_time ON tenant_template.service_health_snapshots USING btree (service, sampled_at DESC);


-- Name: idx_sessions_risk; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_sessions_risk ON tenant_template.counseling_sessions USING btree (tenant_id, risk_level_snapshot, started_at DESC);


-- Name: idx_sessions_student_time; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_sessions_student_time ON tenant_template.counseling_sessions USING btree (tenant_id, student_user_id, started_at DESC);


-- Name: idx_sessions_tenant_status; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_sessions_tenant_status ON tenant_template.counseling_sessions USING btree (tenant_id, session_status, created_at DESC);


-- Name: idx_sessions_tenant_student; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_sessions_tenant_student ON tenant_template.counseling_sessions USING btree (tenant_id, student_user_id, created_at DESC);


-- Name: idx_sla_escalation_log_risk_event; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_sla_escalation_log_risk_event ON tenant_template.sla_escalation_log USING btree (risk_event_id, escalated_at DESC);


-- Name: idx_student_profiles_student; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_student_profiles_student ON tenant_template.student_profiles USING btree (user_id, last_updated_at DESC);


-- Name: idx_sys_config_history_key_time; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_sys_config_history_key_time ON tenant_template.sys_config_history USING btree (config_key, changed_at DESC);


-- Name: idx_toc_child_profiles_account; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_toc_child_profiles_account ON tenant_template.toc_child_profiles USING btree (family_account_id);


-- Name: idx_usage_events_metric_time; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_usage_events_metric_time ON tenant_template.usage_events USING btree (metric, event_time DESC);


-- Name: idx_user_roles_role; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_user_roles_role ON tenant_template.user_roles USING btree (role_id);


-- Name: idx_user_roles_user; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_user_roles_user ON tenant_template.user_roles USING btree (user_id);


-- Name: idx_users_class; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_users_class ON tenant_template.users USING btree (tenant_id, school_id, grade_code, class_code);


-- Name: idx_users_family_code; Type: INDEX; Schema: tenant_template; Owner: -

CREATE UNIQUE INDEX idx_users_family_code ON tenant_template.users USING btree (tenant_id, family_code) WHERE (family_code IS NOT NULL);


-- Name: idx_users_school_type; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_users_school_type ON tenant_template.users USING btree (tenant_id, school_id, user_type);


-- Name: idx_users_tenant_type; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_users_tenant_type ON tenant_template.users USING btree (tenant_id, user_type, status);


-- Name: idx_vp_embeddings_tenant; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_vp_embeddings_tenant ON tenant_template.voiceprint_embeddings USING btree (tenant_id);


-- Name: idx_vp_embeddings_user; Type: INDEX; Schema: tenant_template; Owner: -

CREATE INDEX idx_vp_embeddings_user ON tenant_template.voiceprint_embeddings USING btree (user_id);


-- Name: uq_alert_events_source_fingerprint; Type: INDEX; Schema: tenant_template; Owner: -

CREATE UNIQUE INDEX uq_alert_events_source_fingerprint ON tenant_template.alert_events USING btree (source, fingerprint) WHERE (fingerprint IS NOT NULL);


-- Name: uq_degradation_events_dedup_key; Type: INDEX; Schema: tenant_template; Owner: -

CREATE UNIQUE INDEX uq_degradation_events_dedup_key ON tenant_template.degradation_events USING btree (dedup_key) WHERE (dedup_key IS NOT NULL);


-- Name: uq_diary_student_date; Type: INDEX; Schema: tenant_template; Owner: -

CREATE UNIQUE INDEX uq_diary_student_date ON tenant_template.emotion_diaries USING btree (tenant_id, student_user_id, diary_date);


-- Name: uq_usage_events_metric_tenant_time; Type: INDEX; Schema: tenant_template; Owner: -

CREATE UNIQUE INDEX uq_usage_events_metric_tenant_time ON tenant_template.usage_events USING btree (metric, COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), event_time);


-- Name: schools schools_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -

ALTER TABLE ONLY public.schools
    ADD CONSTRAINT schools_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(tenant_id);


-- Name: counseling_sessions counseling_sessions_student_user_id_fkey; Type: FK CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.counseling_sessions
    ADD CONSTRAINT counseling_sessions_student_user_id_fkey FOREIGN KEY (student_user_id) REFERENCES tenant_template.users(user_id);


-- Name: emotion_diaries emotion_diaries_student_user_id_fkey; Type: FK CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.emotion_diaries
    ADD CONSTRAINT emotion_diaries_student_user_id_fkey FOREIGN KEY (student_user_id) REFERENCES tenant_template.users(user_id);


-- Name: device_bindings fk_device_bindings_device; Type: FK CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.device_bindings
    ADD CONSTRAINT fk_device_bindings_device FOREIGN KEY (device_id) REFERENCES tenant_template.device(device_id);


-- Name: device_bind_codes fk_device_codes_device; Type: FK CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.device_bind_codes
    ADD CONSTRAINT fk_device_codes_device FOREIGN KEY (device_id) REFERENCES tenant_template.device(device_id);


-- Name: device_qr_issuance fk_device_qr_device; Type: FK CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.device_qr_issuance
    ADD CONSTRAINT fk_device_qr_device FOREIGN KEY (device_id) REFERENCES tenant_template.device(device_id);


-- Name: voiceprint_embeddings fk_vp_user; Type: FK CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.voiceprint_embeddings
    ADD CONSTRAINT fk_vp_user FOREIGN KEY (user_id) REFERENCES tenant_template.users(user_id) ON DELETE CASCADE;


-- Name: knowledge_chunks knowledge_chunks_doc_id_fkey; Type: FK CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.knowledge_chunks
    ADD CONSTRAINT knowledge_chunks_doc_id_fkey FOREIGN KEY (doc_id) REFERENCES tenant_template.knowledge_documents(doc_id) ON DELETE CASCADE;


-- Name: message_summaries message_summaries_session_id_fkey; Type: FK CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.message_summaries
    ADD CONSTRAINT message_summaries_session_id_fkey FOREIGN KEY (session_id) REFERENCES tenant_template.counseling_sessions(session_id);


-- Name: parent_student_links parent_student_links_parent_id_fkey; Type: FK CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.parent_student_links
    ADD CONSTRAINT parent_student_links_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES tenant_template.parent_accounts(parent_id);


-- Name: relaxation_sessions relaxation_sessions_student_user_id_fkey; Type: FK CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.relaxation_sessions
    ADD CONSTRAINT relaxation_sessions_student_user_id_fkey FOREIGN KEY (student_user_id) REFERENCES tenant_template.users(user_id);


-- Name: risk_events risk_events_student_user_id_fkey; Type: FK CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.risk_events
    ADD CONSTRAINT risk_events_student_user_id_fkey FOREIGN KEY (student_user_id) REFERENCES tenant_template.users(user_id);


-- Name: teacher_notes teacher_notes_student_user_id_fkey; Type: FK CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.teacher_notes
    ADD CONSTRAINT teacher_notes_student_user_id_fkey FOREIGN KEY (student_user_id) REFERENCES tenant_template.users(user_id);


-- Name: teacher_notes teacher_notes_teacher_user_id_fkey; Type: FK CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.teacher_notes
    ADD CONSTRAINT teacher_notes_teacher_user_id_fkey FOREIGN KEY (teacher_user_id) REFERENCES tenant_template.users(user_id);


-- Name: user_roles user_roles_role_id_fkey; Type: FK CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.user_roles
    ADD CONSTRAINT user_roles_role_id_fkey FOREIGN KEY (role_id) REFERENCES tenant_template.roles(role_id);


-- Name: user_roles user_roles_user_id_fkey; Type: FK CONSTRAINT; Schema: tenant_template; Owner: -

ALTER TABLE ONLY tenant_template.user_roles
    ADD CONSTRAINT user_roles_user_id_fkey FOREIGN KEY (user_id) REFERENCES tenant_template.users(user_id);


-- PostgreSQL database dump complete


