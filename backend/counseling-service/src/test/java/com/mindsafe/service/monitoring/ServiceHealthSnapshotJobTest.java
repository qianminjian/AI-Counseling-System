package com.mindsafe.service.monitoring;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mindsafe.domain.entity.ServiceHealthSnapshot;
import com.mindsafe.domain.mapper.ServiceHealthSnapshotMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务健康快照采样单元测试（ADMIN-P0-05：30s 采样落库 + 30 天清理）
 */
class ServiceHealthSnapshotJobTest {

    private final ServiceHealthProbe probe = mock(ServiceHealthProbe.class);
    private final ServiceHealthSnapshotMapper mapper = mock(ServiceHealthSnapshotMapper.class);
    private final ServiceHealthSnapshotJob job = new ServiceHealthSnapshotJob(probe, mapper);

    @Test
    @DisplayName("snapshot：六服务状态逐一落库（service/status/sampledAt 完整）")
    void snapshotInsertsAllServices() {
        Map<String, String> statuses = new LinkedHashMap<>();
        statuses.put("postgres", "UP");
        statuses.put("redis", "UP");
        statuses.put("backend", "UP");
        statuses.put("tts", "DEGRADED");
        statuses.put("voice", "UP");
        statuses.put("nginx", "DOWN");
        when(probe.probeAll()).thenReturn(statuses);

        job.snapshot();

        ArgumentCaptor<ServiceHealthSnapshot> captor = ArgumentCaptor.forClass(ServiceHealthSnapshot.class);
        verify(mapper, times(6)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(ServiceHealthSnapshot::getService)
                .containsExactlyInAnyOrder("postgres", "redis", "backend", "tts", "voice", "nginx");
        assertThat(captor.getAllValues()).extracting(ServiceHealthSnapshot::getStatus)
                .contains("UP", "DEGRADED", "DOWN");
        assertThat(captor.getAllValues()).allMatch(s -> s.getSampledAt() != null);
    }

    @Test
    @DisplayName("cleanup：删除 30 天前快照（条件含 sampled_at）")
    void cleanupDeletesExpired() {
        when(mapper.delete(any(Wrapper.class))).thenReturn(12);

        job.cleanup();

        verify(mapper).delete(any(Wrapper.class));
    }
}
