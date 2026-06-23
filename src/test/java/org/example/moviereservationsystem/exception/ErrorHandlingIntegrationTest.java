package org.example.moviereservationsystem.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.example.moviereservationsystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Verifies the Phase 7 additions to {@link GlobalExceptionHandler}: the new
 * handlers fire and every one returns the uniform error body. In particular this
 * pins down which no-handler exception Boot 3.5 actually throws — an unknown path
 * must come back as a clean 404 in our shape, not a Whitelabel/ProblemDetail body.
 */
class ErrorHandlingIntegrationTest extends AbstractIntegrationTest {

    @Test
    void unknownPath_returns404_inUniformShape() throws Exception {
        // Authenticated so we pass the security chain and actually reach the
        // dispatcher (an unauthenticated unknown path would 401 at the filter).
        mockMvc.perform(get("/api/does-not-exist")
                        .header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/api/does-not-exist"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void malformedJsonBody_returns400() throws Exception {
        // /api/auth/** is public, so this reaches the body parser and trips
        // HttpMessageNotReadableException.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not valid json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed JSON request"));
    }

    @Test
    void pathVariableTypeMismatch_returns400() throws Exception {
        // GET /api/movies/{id} is public; a non-numeric id can't bind to Long.
        mockMvc.perform(get("/api/movies/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void unsupportedMethod_returns405() throws Exception {
        // /api/auth/login is mapped for POST only; GET is method-not-allowed.
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }
}
