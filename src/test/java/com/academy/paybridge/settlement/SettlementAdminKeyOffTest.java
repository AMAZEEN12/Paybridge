package com.academy.paybridge.settlement;

import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Plan 002-004, step C1 (R-SET-12): with no admin key configured the settlement endpoints are off (404). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "paybridge.settlement.admin-key=")
@ActiveProfiles("test")
class SettlementAdminKeyOffTest {

    @Value("${local.server.port}")
    int port;

    @BeforeAll
    static void needDatabase() {
        assumeTrue(TestDb.available(), "No test database. Create one to run these tests.");
    }

    @Test
    void withNoKeyConfiguredTheEndpointIsOffEvenWithAHeader() {
        RestClient client = RestClient.create("http://localhost:" + port);
        String email = "sk-" + UUID.randomUUID() + "@example.com";
        client.post().uri("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fullName", "Test Operator", "email", email, "password", "password123"))
                .exchange((req, res) -> res.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> login = client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "password123")).retrieve().body(Map.class);
        String token = (String) login.get("token");

        int status = client.method(HttpMethod.GET).uri("/api/v1/admin/settlement/positions")
                .headers(h -> {
                    h.setBearerAuth(token);
                    h.set("X-Admin-Key", "anything");
                }).exchange((req, res) -> res.getStatusCode().value());

        assertThat(status).isEqualTo(404);
    }
}