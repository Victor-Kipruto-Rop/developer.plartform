package com.pesaguard.backend.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class FeedbackSchemaMigrationTest {

    private static final String CREDENTIAL_KEY = base64("integration-credential-key-32-bytes!!");
    private static final String AUDIT_KEY = base64("integration-audit-key-32-bytes!!!!!!");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-bookworm");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("pesaguard.security.credential-hmac-key", () -> CREDENTIAL_KEY);
        registry.add("pesaguard.security.audit-hmac-key", () -> AUDIT_KEY);
        registry.add("pesaguard.security.allowed-origins",
                () -> "https://developers.pesaguard.victorkipruto.com");
    }

    @Autowired
    JdbcTemplate jdbc;

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void feedbackTablesTenantConstraintsAndImmutableActivityTriggerAreInstalled() {
        Integer tables = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public'
                  and table_name in ('feedback', 'feedback_comments', 'feedback_events',
                                     'feedback_create_idempotency', 'feedback_rate_limits')
                """, Integer.class);
        Integer tenantConstraints = jdbc.queryForObject("""
                select count(*) from pg_constraint
                where conname in ('feedback_project_org_fk', 'feedback_environment_project_org_fk')
                """, Integer.class);
        Integer immutableTriggers = jdbc.queryForObject("""
                select count(*) from pg_trigger
                where tgname = 'feedback_events_immutable' and not tgisinternal
                """, Integer.class);

        assertThat(tables).isEqualTo(5);
        assertThat(tenantConstraints).isEqualTo(2);
        assertThat(immutableTriggers).isEqualTo(1);
    }
}
