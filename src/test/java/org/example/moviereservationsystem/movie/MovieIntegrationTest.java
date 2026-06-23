package org.example.moviereservationsystem.movie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.example.moviereservationsystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 3 acceptance coverage for movie management. Each test uses UUID-suffixed
 * genre names so genre-filtered lists stay deterministic; combined with the
 * per-test truncate in the base class, results never depend on other tests.
 */
class MovieIntegrationTest extends AbstractIntegrationTest {

    @Test
    void adminCreate_returns201_withSortedGenres() throws Exception {
        String admin = adminToken();
        String uid = uid();
        MovieBody body = new MovieBody(
                "Inception " + uid, "A heist in dreams.", "http://img/incept.jpg", 148,
                List.of("Sci-Fi-" + uid, "Action-" + uid, "Thriller-" + uid));

        MvcResult result = mockMvc.perform(post("/api/admin/movies")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = read(result);
        assertThat(json.get("id").asLong()).isPositive();
        assertThat(json.get("durationMinutes").asInt()).isEqualTo(148);
        // Genres returned sorted (case-insensitive): Action, Sci-Fi, Thriller.
        assertThat(genres(json))
                .containsExactly("Action-" + uid, "Sci-Fi-" + uid, "Thriller-" + uid);
    }

    @Test
    void genreFilter_returnsEachMoviesFullGenreSet_notJustTheFilteredGenre() throws Exception {
        String admin = adminToken();
        String uid = uid();
        String action = "Action-" + uid;
        String drama = "Drama-" + uid;
        String comedy = "Comedy-" + uid;
        createMovie(admin, new MovieBody(
                "Multi " + uid, null, null, 120, List.of(action, drama, comedy)));

        JsonNode list = read(mockMvc.perform(get("/api/movies").param("genre", action))
                .andExpect(status().isOk())
                .andReturn());

        // Exactly the one movie matches this unique genre.
        assertThat(list.isArray()).isTrue();
        assertThat(list).hasSize(1);
        // The critical assertion: filtering by "Action" must NOT truncate the
        // genre collection — all three genres come back, sorted.
        assertThat(genres(list.get(0))).containsExactly(action, comedy, drama);
    }

    @Test
    void genresAreCaseInsensitiveGetOrCreate_reusedAcrossMovies() throws Exception {
        String admin = adminToken();
        String uid = uid();
        String shared = "Genre-" + uid; // first-seen casing wins
        createMovie(admin, new MovieBody("First " + uid, null, null, 100, List.of(shared)));
        createMovie(admin, new MovieBody(
                "Second " + uid, null, null, 100, List.of(shared.toUpperCase())));

        // Filtering by any casing returns both movies -> same genre row reused.
        JsonNode list = read(mockMvc.perform(get("/api/movies").param("genre", shared.toLowerCase()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(list).hasSize(2);
        // Both expose the original stored casing, proving no duplicate insert.
        assertThat(genres(list.get(0))).containsExactly(shared);
        assertThat(genres(list.get(1))).containsExactly(shared);
    }

    @Test
    void update_replacesFieldsAndGenres() throws Exception {
        String admin = adminToken();
        String uid = uid();
        long id = createMovie(admin, new MovieBody(
                "Old " + uid, "old", null, 90, List.of("OldGenre-" + uid)));

        MovieBody updated = new MovieBody(
                "New " + uid, "new", "http://img/new.jpg", 95, List.of("NewGenre-" + uid));
        JsonNode json = read(mockMvc.perform(put("/api/admin/movies/" + id)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updated)))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(json.get("title").asText()).isEqualTo("New " + uid);
        assertThat(json.get("durationMinutes").asInt()).isEqualTo(95);
        assertThat(genres(json)).containsExactly("NewGenre-" + uid); // old genre dropped
    }

    @Test
    void softDelete_hidesFromPublicReads_andSecondDeleteIs404() throws Exception {
        String admin = adminToken();
        String uid = uid();
        String genre = "Gone-" + uid;
        long id = createMovie(admin, new MovieBody("Doomed " + uid, null, null, 100, List.of(genre)));

        // Delete -> 204.
        mockMvc.perform(delete("/api/admin/movies/" + id)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        // Vanishes from single read and from the genre-filtered list.
        mockMvc.perform(get("/api/movies/" + id)).andExpect(status().isNotFound());
        JsonNode list = read(mockMvc.perform(get("/api/movies").param("genre", genre))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(list).isEmpty();

        // Re-delete and update on an already soft-deleted movie -> 404.
        mockMvc.perform(delete("/api/admin/movies/" + id)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
        MovieBody body = new MovieBody("Zombie " + uid, null, null, 100, List.of(genre));
        mockMvc.perform(put("/api/admin/movies/" + id)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void userToken_onAdminCreate_returns403_andNoToken_returns401() throws Exception {
        String userToken = userToken();
        MovieBody body = new MovieBody("Nope " + uid(), null, null, 100, List.of());

        mockMvc.perform(post("/api/admin/movies")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/movies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidRequests_return400() throws Exception {
        String admin = adminToken();
        String uid = uid();

        // Blank title.
        mockMvc.perform(post("/api/admin/movies")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MovieBody("  ", null, null, 100, List.of()))))
                .andExpect(status().isBadRequest());

        // Null duration.
        mockMvc.perform(post("/api/admin/movies")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MovieBody("Ok " + uid, null, null, null, List.of()))))
                .andExpect(status().isBadRequest());

        // Non-positive duration.
        mockMvc.perform(post("/api/admin/movies")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MovieBody("Ok " + uid, null, null, 0, List.of()))))
                .andExpect(status().isBadRequest());
    }

    // --- movie-specific helper ---

    private List<String> genres(JsonNode movieJson) throws Exception {
        return objectMapper.convertValue(
                movieJson.get("genres"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }
}
