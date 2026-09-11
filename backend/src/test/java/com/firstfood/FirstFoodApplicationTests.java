package com.firstfood;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Smoke test for Phase 1 exit criteria (see FirstFood_V2_Phase1_Review.md
 * #15/#16/#24): application context loads, Postgres connection + Flyway
 * migration work, Redis is actually reachable (not just configured), and
 * the public endpoints respond.
 *
 * Uses RestTestClient rather than TestRestTemplate: Spring Boot 4 moved
 * TestRestTemplate to a new package and no longer auto-configures it on
 * @SpringBootTest, and the framework's own guidance is to prefer
 * RestTestClient going forward (see the Spring Boot 4.0 migration guide).
 *
 * Postgres version here MUST track the version used in
 * infra/docker-compose.yml and production (currently 17) - a mismatch
 * lets a migration pass in CI on one major version and fail on another.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class FirstFoodApplicationTests {

    @Container
    @SuppressWarnings("resource") // lifecycle is managed by the @Testcontainers JUnit extension
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("firstfood_test")
            .withUsername("firstfood")
            .withPassword("firstfood");

    @Container
    @SuppressWarnings("resource") // lifecycle is managed by the @Testcontainers JUnit extension
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("valkey/valkey:8-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    RestTestClient restClient;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void contextLoadsAndMigrationsRun() {
        // If Flyway or the datasource is misconfigured, context load
        // itself fails before this assertion runs.
        assertThat(restClient).isNotNull();
    }

    @Test
    void versionEndpointIsPublic() {
        restClient.get()
                .uri("/api/v1/version")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("firstfood-v2"));
    }

    @Test
    void healthEndpointIsPublic() {
        restClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("\"status\":\"UP\""));
    }

    @Test
    void redisConnectionReadWriteWorks() {
        redisTemplate.opsForValue().set("phase1:test", "ok");
        assertThat(redisTemplate.opsForValue().get("phase1:test")).isEqualTo("ok");
        redisTemplate.delete("phase1:test");
    }
}