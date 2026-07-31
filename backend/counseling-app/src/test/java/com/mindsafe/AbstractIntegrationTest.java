package com.mindsafe;

import com.mindsafe.app.MindSafeApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * 集成测试基类（Testcontainers: PostgreSQL 16 + Redis 7）
 * <p>
 * 所有集成测试继承此类，自动获得：
 * - 真实 PostgreSQL（pgvector 镜像）+ Flyway 迁移
 * - 真实 Redis（限流/缓存）
 * - Spring Boot 完整上下文
 * <p>
 * 容器采用单例模式（static 块手动 start，不用 @Container/@Testcontainers）：
 * JUnit 的 @Container 按测试类启停容器，但 Spring TestContext 会跨 IT 类缓存上下文，
 * 第二个类会拿到缓存里指向已销毁容器旧端口的连接池 → 拒连。单例容器与 JVM 同生命周期，
 * 结束后由 Testcontainers Ryuk 兜底回收。
 * <p>
 * 注意：需要本机 Docker 运行。CI 中由 GitHub Actions 提供 Docker 环境。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = MindSafeApplication.class)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("mindsafe_test")
            .withUsername("test")
            .withPassword("test")
            // 预装迁移依赖的扩展（vector/uuid-ossp/pgcrypto），在 Flyway 迁移前执行
            .withInitScript("init-test-db.sql")
            // CI 环境镜像拉取+初始化较慢，增大启动超时（默认 60s）
            .withStartupTimeout(Duration.ofSeconds(180));

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(60));

    static {
        // JVM 内只启动一次，所有 IT 类共享同一容器实例与端口
        postgres.start();
        redis.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // Redis
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }
}
