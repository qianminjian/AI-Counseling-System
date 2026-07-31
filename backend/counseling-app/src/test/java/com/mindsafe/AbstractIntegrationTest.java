package com.mindsafe;

import com.mindsafe.app.MindSafeApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * 集成测试基类
 * <p>
 * 双模式运行：
 * - CI 模式（检测到 CI_DB_URL 环境变量）：使用 GitHub Actions services 提供的外部 PG/Redis，
 *   Testcontainers 不启动（disabledWithoutDocker + 容器声明为 null-safe）
 * - 本地模式（无 CI_DB_URL）：使用 Testcontainers 自动启动 PostgreSQL 16 + Redis 7
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = MindSafeApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    /** CI 环境下由 GitHub Actions services 提供 DB，此时为 true */
    private static final boolean USE_EXTERNAL_DB = System.getenv("CI_DB_URL") != null;

    @Container
    static PostgreSQLContainer<?> postgres = USE_EXTERNAL_DB ? null :
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("mindsafe_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withInitScript("init-test-db.sql")
                    .withStartupTimeout(Duration.ofSeconds(180));

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = USE_EXTERNAL_DB ? null :
            new GenericContainer<>(
                    DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withStartupTimeout(Duration.ofSeconds(60));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (USE_EXTERNAL_DB) {
            // CI 模式：从环境变量读取 GitHub Actions services 的连接信息
            registry.add("spring.datasource.url", () -> System.getenv("CI_DB_URL"));
            registry.add("spring.datasource.username", () -> System.getenv("CI_DB_USERNAME"));
            registry.add("spring.datasource.password", () -> System.getenv("CI_DB_PASSWORD"));
            registry.add("spring.data.redis.host", () -> System.getenv("CI_REDIS_HOST"));
            registry.add("spring.data.redis.port", () -> System.getenv("CI_REDIS_PORT"));
            registry.add("spring.data.redis.password", () -> "");
        } else {
            // 本地模式：Testcontainers 动态端口
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.data.redis.host", redis::getHost);
            registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
            registry.add("spring.data.redis.password", () -> "");
        }
    }
}
