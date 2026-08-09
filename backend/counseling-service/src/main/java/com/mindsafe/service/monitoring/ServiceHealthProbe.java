package com.mindsafe.service.monitoring;

import com.mindsafe.domain.entity.ServiceHealthSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务健康探针（ADMIN-P0-05，M2 服务拓扑只读）
 * <p>
 * 六服务 UP/DEGRADED/DOWN 语义对齐 service-manager：
 * <ul>
 *   <li>postgres：DataSource 连接测试</li>
 *   <li>redis：Redis ping</li>
 *   <li>backend：自身进程即 UP（能响应即健康）</li>
 *   <li>tts/voice/nginx：HTTP GET /health，解析 status 字段（UP/DEGRADED，DEGRADED ≠ DOWN）</li>
 * </ul>
 * 设计见 doing/83 后台管理端 §5.2 2.1。
 */
@Component
public class ServiceHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(ServiceHealthProbe.class);

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final RestTemplate restTemplate = buildRestTemplate();
    private final String ttsUrl;
    private final String voiceUrl;
    private final String nginxUrl;

    public ServiceHealthProbe(DataSource dataSource,
                              StringRedisTemplate redisTemplate,
                              @Value("${mindsafe.monitoring.service-probes.tts:http://tts-service:10096}") String ttsUrl,
                              @Value("${mindsafe.monitoring.service-probes.voice:http://voice-service:10095}") String voiceUrl,
                              @Value("${mindsafe.monitoring.service-probes.nginx:http://nginx}") String nginxUrl) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
        this.ttsUrl = ttsUrl;
        this.voiceUrl = voiceUrl;
        this.nginxUrl = nginxUrl;
    }

    /** 探测全部六服务，返回 service → UP/DEGRADED/DOWN */
    public Map<String, String> probeAll() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("postgres", probePostgres());
        result.put("redis", probeRedis());
        result.put("backend", ServiceHealthSnapshot.STATUS_UP);
        result.put("tts", probeHttp("tts", ttsUrl));
        result.put("voice", probeHttp("voice", voiceUrl));
        result.put("nginx", probeHttp("nginx", nginxUrl));
        return result;
    }

    private String probePostgres() {
        try (Connection connection = dataSource.getConnection()) {
            return ServiceHealthSnapshot.STATUS_UP;
        } catch (Exception e) {
            log.warn("服务探测 postgres DOWN: {}", e.getMessage());
            return ServiceHealthSnapshot.STATUS_DOWN;
        }
    }

    private String probeRedis() {
        // try-with-resources 归还连接（生产 lettuce 连接池，泄漏会耗尽 max-active，code-review H2）
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.ping();
            return ServiceHealthSnapshot.STATUS_UP;
        } catch (Exception e) {
            log.warn("服务探测 redis DOWN: {}", e.getMessage());
            return ServiceHealthSnapshot.STATUS_DOWN;
        }
    }

    /** HTTP /health 探测：解析 status 字段（UP/DEGRADED）；连接失败或未知状态 = DOWN */
    private String probeHttp(String service, String baseUrl) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restTemplate.getForObject(baseUrl + "/health", Map.class);
            String status = String.valueOf(body == null ? "" : body.getOrDefault("status", "UP"));
            if (ServiceHealthSnapshot.STATUS_UP.equals(status) || ServiceHealthSnapshot.STATUS_DEGRADED.equals(status)) {
                return status;
            }
            return ServiceHealthSnapshot.STATUS_DOWN;
        } catch (Exception e) {
            log.warn("服务探测 {} DOWN: {}", service, e.getMessage());
            return ServiceHealthSnapshot.STATUS_DOWN;
        }
    }

    /** 探测外呼必须带超时：目标不可达时不能挂死调度线程（WeComAlertService 同模式） */
    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new RestTemplate(factory);
    }
}
