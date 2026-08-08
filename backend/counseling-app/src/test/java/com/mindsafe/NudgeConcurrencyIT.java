package com.mindsafe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.service.conversation.NudgeProperties;
import com.mindsafe.service.conversation.RedisSessionStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BA-09 集成测试：多实例并发 nudge 计数一致性（真实 Redis Lua 原子脚本）
 * <p>
 * 场景：10 路并发同时触发暖场（等价 10 个实例同时决策），Lua 原子脚本保证
 * 放行数恰好 = maxCount、计数真值一致——快照路径（已删）无法提供该保证。
 * <p>
 * 间隔隔离：默认 minIntervalSeconds=20s 下，:at 时间戳为 epoch 秒粒度，同秒并发调用
 * 会被间隔护栏拦截（正确语义）；故并发断言使用 minIntervalSeconds=0 的独立 store，
 * 聚焦「计数上限原子一致性」这一本测试的目标。
 */
class NudgeConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("并发 10 路 tryNudge：放行恰为 maxCount 次，计数一致")
    void concurrentTryNudge_allowsExactlyMaxCount() throws Exception {
        // 间隔隔离（minIntervalSeconds=0）：并发测试聚焦计数上限原子一致性
        NudgeProperties zeroInterval = new NudgeProperties();
        zeroInterval.setMinIntervalSeconds(0);
        RedisSessionStateStore concurrentStore = new RedisSessionStateStore(redisTemplate, objectMapper, zeroInterval);
        int maxCount = zeroInterval.getMaxCount();  // 默认 2

        UUID tenantId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        // 清理独立键（count + :at），确保断言从零开始
        redisTemplate.delete("session:nudge:" + tenantId + ":" + sessionId);
        redisTemplate.delete("session:nudge:" + tenantId + ":" + sessionId + ":at");

        int threads = 10;  // 模拟 10 个实例同时暖场
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return concurrentStore.tryNudge(tenantId, sessionId);
            }));
        }
        ready.await();
        start.countDown();
        long allowed = 0;
        for (Future<Boolean> f : futures) {
            if (Boolean.TRUE.equals(f.get())) allowed++;
        }
        pool.shutdown();

        assertThat(allowed).isEqualTo(maxCount);
        assertThat(concurrentStore.getNudgeCount(tenantId, sessionId)).isEqualTo(maxCount);
    }
}
