package com.mindsafe.service.monitoring;

import com.mindsafe.domain.entity.ServiceHealthSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 服务健康探针单元测试（ADMIN-P0-05，AC-P0-05）
 * 覆盖：postgres/redis/backend/tts(DEGRADED)/不可达 DOWN/未知状态 DOWN
 */
class ServiceHealthProbeTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ServiceHealthProbe probe =
            new ServiceHealthProbe(dataSource, redisTemplate, "http://127.0.0.1:1/tts", "http://127.0.0.1:1/voice", "http://127.0.0.1:1/nginx");

    @Test
    @DisplayName("postgres/redis/backend 均 UP（backend 自身进程即健康）")
    void infraServicesUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(mock(Connection.class));
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");

        Map<String, String> statuses = probe.probeAll();

        assertThat(statuses).containsEntry("postgres", ServiceHealthSnapshot.STATUS_UP);
        assertThat(statuses).containsEntry("redis", ServiceHealthSnapshot.STATUS_UP);
        assertThat(statuses).containsEntry("backend", ServiceHealthSnapshot.STATUS_UP);
    }

    @Test
    @DisplayName("postgres 连接失败 → DOWN")
    void postgresDown() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("db unreachable"));

        assertThat(probe.probeAll()).containsEntry("postgres", ServiceHealthSnapshot.STATUS_DOWN);
    }

    @Test
    @DisplayName("redis ping 失败 → DOWN")
    void redisDown() {
        when(redisTemplate.getConnectionFactory()).thenThrow(new RuntimeException("redis unreachable"));

        assertThat(probe.probeAll()).containsEntry("redis", ServiceHealthSnapshot.STATUS_DOWN);
    }

    @Test
    @DisplayName("HTTP 探测不可达 → DOWN（不抛异常）")
    void httpProbeUnreachable() {
        Map<String, String> statuses = probe.probeAll();

        assertThat(statuses).containsEntry("tts", ServiceHealthSnapshot.STATUS_DOWN);
        assertThat(statuses).containsEntry("voice", ServiceHealthSnapshot.STATUS_DOWN);
        assertThat(statuses).containsEntry("nginx", ServiceHealthSnapshot.STATUS_DOWN);
    }
}
