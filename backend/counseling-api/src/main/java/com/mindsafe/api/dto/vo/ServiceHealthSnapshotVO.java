package com.mindsafe.api.dto.vo;

import com.mindsafe.domain.entity.ServiceHealthSnapshot;

import java.time.Instant;

/**
 * 服务健康快照 VO（F9：ops 健康历史响应，替代实体直接暴露）。
 */
public record ServiceHealthSnapshotVO(
        Long snapshotId,
        String service,
        String status,
        String detail,
        Instant sampledAt
) {
    public static ServiceHealthSnapshotVO from(ServiceHealthSnapshot s) {
        return new ServiceHealthSnapshotVO(s.getSnapshotId(), s.getService(), s.getStatus(),
                s.getDetail(), s.getSampledAt());
    }
}
