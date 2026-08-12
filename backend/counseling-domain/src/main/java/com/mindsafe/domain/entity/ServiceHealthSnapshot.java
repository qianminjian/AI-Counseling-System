package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 服务健康快照实体（对应 tenant_template.service_health_snapshots，V35）
 * <p>
 * M2 服务拓扑历史曲线与 SLA 统计数据源（30s 采样，保留 30 天）。
 * 设计见 doing/83 后台管理端 §6.3。
 */
@TableName(value = "service_health_snapshots", schema = TenantSchema.TENANT_TEMPLATE)
public class ServiceHealthSnapshot {

    /** 状态：健康 */
    public static final String STATUS_UP = "UP";

    /** 状态：降级（仍可用，DEGRADED ≠ DOWN） */
    public static final String STATUS_DEGRADED = "DEGRADED";

    /** 状态：不可达 */
    public static final String STATUS_DOWN = "DOWN";

    @TableId(value = "snapshot_id", type = IdType.AUTO)
    private Long snapshotId;

    /** postgres/redis/tts/voice/backend/nginx */
    private String service;

    /** UP/DEGRADED/DOWN */
    private String status;

    /** 引擎/就绪态等附加信息（JSONB） */
    private String detail;

    /** 采样时间 */
    private Instant sampledAt;

    public Long getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(Long snapshotId) {
        this.snapshotId = snapshotId;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getSampledAt() {
        return sampledAt;
    }

    public void setSampledAt(Instant sampledAt) {
        this.sampledAt = sampledAt;
    }
}
