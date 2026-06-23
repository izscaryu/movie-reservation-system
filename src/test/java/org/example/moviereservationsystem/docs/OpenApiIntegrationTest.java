package org.example.moviereservationsystem.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.moviereservationsystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Confirms the springdoc wiring: the OpenAPI spec generates, is reachable without
 * a token (permitted in the SecurityFilterChain), describes the endpoints, and
 * advertises the JWT bearer scheme so the Swagger UI shows an Authorize button.
 */
class OpenApiIntegrationTest extends AbstractIntegrationTest {

    @Test
    void apiDocs_arePublic_describeEndpoints_andDeclareBearerScheme() throws Exception {
        JsonNode doc = read(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn());

        // A representative spread of endpoints is documented.
        JsonNode paths = doc.get("paths");
        assertThat(paths.has("/api/movies")).isTrue();
        assertThat(paths.has("/api/reservations")).isTrue();
        assertThat(paths.has("/api/admin/reservations")).isTrue();

        // The JWT bearer scheme is declared (drives the UI's Authorize button).
        JsonNode bearer = doc.get("components").get("securitySchemes").get("bearerAuth");
        assertThat(bearer.get("type").asText()).isEqualTo("http");
        assertThat(bearer.get("scheme").asText()).isEqualTo("bearer");
        assertThat(bearer.get("bearerFormat").asText()).isEqualTo("JWT");
    }

    @Test
    void swaggerUi_isReachableWithoutAuth() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
