package com.mindsafe.service.monitoring;

import com.mindsafe.common.tenant.TenantContextHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.ServiceHealthSnapshot;
import com.mindsafe.domain.mapper.ServiceHealthSnapshotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 服务健康快照采样（ADMIN-P0-05，M2 服务拓扑历史/SLA 验证）
 * <p>
 * 30s 周期采样六服务健康状态落库（保留 30 天，超期由既有清理机制覆盖）。
 * 定时任务线程无租户上下文：runAsSystem 显式声明系统作用域（M1-003 惯例），
 * 配合拦截器 IGNORE_TABLES（platform 表，V35）。
 */
@Component
public class ServiceHealthSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(ServiceHealthSnapshotJob.class);

    private final ServiceHealthProbe probe;
    private final ServiceHealthSnapshotMapper snapshotMapper;

    public ServiceHealthSnapshotJob(ServiceHealthProbe probe,
                                    ServiceHealthSnapshotMapper snapshotMapper) {
        this.probe = probe;
        this.snapshotMapper = snapshotMapper;
    }

    @Scheduled(cron = "${mindsafe.monitoring.health-snapshot.cron:*/30 * * * * ?}")
    public void snapshot() {
        TenantContextHolder.runAsSystem(this::doSnapshot);
    }

    private void doSnapshot() {
        Map<String, String> statuses = probe.probeAll();
        Instant now = Instant.now();
        for (Map.Entry<String, String> entry : statuses.entrySet()) {
            ServiceHealthSnapshot snapshot = new ServiceHealthSnapshot();
            snapshot.setService(entry.getKey());
            snapshot.setStatus(entry.getValue());
            snapshot.setSampledAt(now);
            snapshotMapper.insert(snapshot);
        }
        log.debug("服务健康快照已采样: {}", statuses);
    }

    /**
     * 保留 30 天（§6.3 口径）：每日清理过期快照（code-review L2——
     * 既有 DataRetentionCleanupJob 不覆盖本表，需自行清理防止无界增长）。
     */
    @Scheduled(cron = "${mindsafe.monitoring.health-snapshot.cleanup-cron:0 30 3 * * ?}")
    public void cleanup() {
        TenantContextHolder.runAsSystem(() -> {
            Instant threshold = Instant.now().minus(30, ChronoUnit.DAYS);
            int removed = snapshotMapper.delete(new LambdaQueryWrapper<ServiceHealthSnapshot>()
                    .lt(ServiceHealthSnapshot::getSampledAt, threshold));
            log.info("服务健康快照清理完成: 删除 {} 条（< {}）", removed, threshold);
        });
    }
}
