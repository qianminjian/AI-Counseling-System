-- V38 rollback: M1 配置注册表种子数据（缺口 4）

DELETE FROM tenant_template.sys_config WHERE config_key IN (
    'mindsafe.safety.voiceprint-threshold',
    'mindsafe.safety.crisis-hotline',
    'mindsafe.security.sla-escalation.re-alert-cooldown-minutes',
    'mindsafe.security.sla-escalation.enabled',
    'mindsafe.tts.synthesize-timeout',
    'mindsafe.alert.wecom.webhook-url'
);
