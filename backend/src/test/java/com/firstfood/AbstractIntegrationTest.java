package com.firstfood;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Postgres + Redis Testcontainers setup for full-context
 * integration tests, using the "singleton containers" pattern - a
 * static initializer, NOT {@code @Testcontainers}/{@code @Container}.
 *
 * This is deliberate, not an oversight: {@code @Testcontainers} ties a
 * container field's start/stop lifecycle to that specific test class's
 * JUnit callbacks. Since these fields are {@code static}, they're
 * actually shared across every subclass in the same JVM - but with
 * {@code @Testcontainers} on this class, EACH subclass independently
 * starts them in its own beforeAll and (critically) STOPS them in its
 * own afterAll. The first subclass to finish kills the containers out
 * from under every subclass that hasn't run yet, which is exactly what
 * broke the Phase 2 CI run (Postgres/Redis "connection refused" mid-
 * suite). Docker's own Testcontainers guide calls this out by name as
 * "a common mistake" - see
 * https://docs.docker.com/guides/testcontainers-java-lifecycle/singleton-containers/
 *
 * With a static initializer instead, the containers start once when
 * this class is first loaded and are never explicitly stopped - the
 * Ryuk sidecar container Testcontainers manages automatically cleans
 * them up when the whole test JVM exits.
 *
 * Postgres version here MUST track the version used in
 * infra/docker-compose.yml and production (currently 17) - a mismatch
 * lets a migration pass in CI on one major version and fail on another.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
public abstract class AbstractIntegrationTest {

    @SuppressWarnings("resource") // never stopped on purpose - see class javadoc
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("firstfood_test")
            .withUsername("firstfood")
            .withPassword("firstfood");

    @SuppressWarnings("resource") // never stopped on purpose - see class javadoc
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("valkey/valkey:8-alpine"))
                    .withExposedPorts(6379);

    static {
        Startables.deepStart(POSTGRES, REDIS).join();
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}