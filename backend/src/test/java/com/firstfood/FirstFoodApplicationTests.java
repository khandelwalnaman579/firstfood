package com.firstfood;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

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
 */
class FirstFoodApplicationTests extends AbstractIntegrationTest {

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
