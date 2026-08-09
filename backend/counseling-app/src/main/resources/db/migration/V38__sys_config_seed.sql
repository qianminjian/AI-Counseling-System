-- V38: M1 配置注册表种子数据（ADMIN-P1-01 补齐，缺口 4）
--
-- 背景：V36 建 sys_config 表但无种子数据，配置面板为空壳（无可配置项）。
-- 本迁移写入首批 HOT 可改配置键（对应既有 SystemConfigProperties/配置使用点，
-- 仅标记 HOT 开放修改，R-3；SECRET 类只存掩码标记）。
-- 幂等：ON CONFLICT DO NOTHING（已存在键不覆盖，键唯一约束 config_key）。

INSERT INTO tenant_template.sys_config (config_key, domain, value, value_type, sensitive, effect_mode, source, description)
VALUES
    ('mindsafe.safety.voiceprint-threshold', 'security', '0.70', 'number', 'NORMAL', 'HOT', 'db',
     '声纹登录 local 模式余弦相似度阈值（0.55-0.80，调高更严格）'),
    ('mindsafe.safety.crisis-hotline', 'security', '400-161-9995', 'string', 'NORMAL', 'HOT', 'db',
     '危机热线号码（SAFE-001，环境变量 MINDSAFE_CRISIS_HOTLINE 可覆盖）'),
    ('mindsafe.security.sla-escalation.re-alert-cooldown-minutes', 'alert', '30', 'number', 'NORMAL', 'HOT', 'db',
     'SLA 超时扫描冷却期（分钟，同事件冷却期内只告警一次）'),
    ('mindsafe.security.sla-escalation.enabled', 'alert', 'true', 'bool', 'NORMAL', 'HOT', 'db',
     'SLA 超时扫描总开关（false 暂停扫描）'),
    ('mindsafe.tts.synthesize-timeout', 'voice', '30', 'number', 'NORMAL', 'HOT', 'db',
     'TTS 单次合成超时（秒，对应 TTS_SYNTHESIZE_TIMEOUT）'),
    ('mindsafe.alert.wecom.webhook-url', 'alert', NULL, 'string', 'SECRET', 'HOT', 'db',
     '企微告警 webhook（SECRET：值不回读；默认未配置，实际接入后由 super_admin 回写掩码标记）')
ON CONFLICT (config_key) DO NOTHING;

COMMENT ON TABLE tenant_template.sys_config IS '配置注册表（M1；V38 种子：首批 HOT 可改键，SECRET 存掩码）';
