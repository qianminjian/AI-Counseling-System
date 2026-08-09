-- V34: 运维监控事件表（OPS-MON-007/008，doing/83 服务降级监控与告警设计）
--
-- 背景：BUG-TTS-01 事故复盘——服务运行期降级无告警、无事件留痕。本迁移为
-- 降级监控链路提供两张平台级事件表（无 tenant_id 字段，属平台表，管理端
-- TenantLineHandler 忽略名单范围）：
--   1) degradation_events  降级事件历史（auto 由检测器写、manual 由管理端切换 API 写）
--   2) alert_events        告警事件历史（AlertManager 仅保留 120h，采集器落库供长期查询）
-- 关联：设计文档 doing/83_服务降级监控与告警设计.md §6.4/§6.5（表结构单一事实源）；
--       字段变更需同步 design/doing/83_后台管理端AdminConsole设计方案.md §6.4/6.5。

-- ===== degradation_events（降级事件历史，§6.5） =====
CREATE TABLE IF NOT EXISTS tenant_template.degradation_events (
    event_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    point        VARCHAR(32)  NOT NULL,              -- 降级点: llm/tts/asr/ser/voice-policy/wake-word
    from_state   VARCHAR(64)  NOT NULL,              -- 切换前档位
    to_state     VARCHAR(64)  NOT NULL,              -- 切换后档位
    trigger_type VARCHAR(8)   NOT NULL,              -- auto（检测器）/ manual（管理端切换）
    operator     VARCHAR(64),                        -- 手动切换操作人（auto 为 NULL）
    detail       VARCHAR(512),                       -- 原因/影响
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  tenant_template.degradation_events IS '降级事件历史（OPS-MON-007/008；auto 检测器落库、manual 管理端切换写库）';
COMMENT ON COLUMN tenant_template.degradation_events.point        IS '降级点: llm/tts/asr/ser/voice-policy/wake-word';
COMMENT ON COLUMN tenant_template.degradation_events.trigger_type IS '触发方式: auto=监控检测器 / manual=管理端手动切换';
COMMENT ON COLUMN tenant_template.degradation_events.operator     IS '手动切换操作人（platform_admin 账号）';

-- 时间线查询索引（管理端 M3 事件时间线：按点过滤 + 时间倒序）
CREATE INDEX IF NOT EXISTS idx_degradation_events_point_time
    ON tenant_template.degradation_events (point, occurred_at DESC);

-- ===== alert_events（告警事件历史，§6.4 + fingerprint 采集去重列） =====
CREATE TABLE IF NOT EXISTS tenant_template.alert_events (
    event_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source            VARCHAR(16)  NOT NULL,          -- alertmanager / alertservice
    fingerprint       VARCHAR(64),                    -- AlertManager 告警指纹（upsert 去重键，alertservice 来源为 NULL）
    rule_name         VARCHAR(128) NOT NULL,          -- 规则名（如 TtsPrimaryEngineDegraded）
    severity          VARCHAR(16)  NOT NULL,          -- CRITICAL/WARNING/INFO
    status            VARCHAR(16)  NOT NULL DEFAULT 'firing',  -- firing/resolved/ack/closed
    summary           TEXT,                           -- 摘要
    detail            TEXT,                           -- 详情
    acknowledged_by   VARCHAR(64),                    -- 确认人（管理端 ack）
    acknowledged_at   TIMESTAMPTZ,                    -- 确认时间
    fired_at          TIMESTAMPTZ  NOT NULL,          -- 触发时间
    resolved_at       TIMESTAMPTZ,                    -- 恢复时间
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  tenant_template.alert_events IS '告警事件历史（OPS-MON-008；AlertManager 120h 窗口外可查，供管理端 M2 告警中心）';
COMMENT ON COLUMN tenant_template.alert_events.source      IS '来源: alertmanager=采集器拉取 / alertservice=业务告警同步写';
COMMENT ON COLUMN tenant_template.alert_events.fingerprint IS 'AlertManager 告警指纹（source=alertmanager 时非空，UNIQUE 约束支撑 upsert 去重）';
COMMENT ON COLUMN tenant_template.alert_events.status      IS '状态: firing/resolved/ack/closed（firing→resolved 由采集器流转，ack/closed 由管理端 API）';

-- upsert 去重键（同一告警重复拉取不重复落库）
CREATE UNIQUE INDEX IF NOT EXISTS uq_alert_events_source_fingerprint
    ON tenant_template.alert_events (source, fingerprint) WHERE fingerprint IS NOT NULL;

-- 告警中心查询索引（状态筛选 + 时间倒序）
CREATE INDEX IF NOT EXISTS idx_alert_events_status_time
    ON tenant_template.alert_events (status, fired_at DESC);
