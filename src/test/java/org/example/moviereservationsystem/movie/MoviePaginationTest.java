package org.example.moviereservationsystem.movie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.example.moviereservationsystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Full-strength pagination coverage for GET /api/movies, exploiting the clean
 * per-test DB so absolute page math (totalElements/totalPages, exact page sizes)
 * is reliable. The assertions that actually catch the subtle bugs are:
 *   - cross-page-boundary ordering (the last item of one page sorts before the
 *     first of the next) — fails if a collection fetch is paginated in memory, or
 *     if the second fetch's IN(...) doesn't re-apply the order;
 *   - duplicate-title stability — fails if (title) alone is the sort key with no
 *     id tiebreaker, which would let a row appear on two pages or none.
 */
class MoviePaginationTest extends AbstractIntegrationTest {

    @Test
    void unfilteredList_pageMathAndCrossBoundaryOrdering() throws Exception {
        String admin = adminToken();
        // 25 movies with zero-padded titles so the (title, id) order is known.
        for (int i = 0; i < 25; i++) {
            createMovie(admin, new MovieBody(
                    "PgMovie " + String.format("%02d", i), null, null, 100, List.of()));
        }

        JsonNode page0 = getPage(null, 0, 10);
        assertThat(page0.get("totalElements").asLong()).isEqualTo(25);
        assertThat(page0.get("totalPages").asInt()).isEqualTo(3);
        assertThat(page0.get("page").asInt()).isEqualTo(0);
        assertThat(page0.get("size").asInt()).isEqualTo(10);
        assertThat(page0.get("first").asBoolean()).isTrue();
        assertThat(page0.get("last").asBoolean()).isFalse();
        JsonNode content0 = page0.get("content");
        assertThat(content0).hasSize(10);
        assertThat(content0.get(0).get("title").asText()).isEqualTo("PgMovie 00");
        assertThat(content0.get(9).get("title").asText()).isEqualTo("PgMovie 09");

        JsonNode page1 = getPage(null, 1, 10);
        assertThat(page1.get("content")).hasSize(10);
        assertThat(page1.get("first").asBoolean()).isFalse();
        assertThat(page1.get("last").asBoolean()).isFalse();

        JsonNode page2 = getPage(null, 2, 10);
        assertThat(page2.get("content")).hasSize(5); // 25 = 10 + 10 + 5
        assertThat(page2.get("last").asBoolean()).isTrue();
        assertThat(page2.get("content").get(0).get("title").asText()).isEqualTo("PgMovie 20");
        assertThat(page2.get("content").get(4).get("title").asText()).isEqualTo("PgMovie 24");

        // Cross-boundary: last of page 0 sorts strictly before first of page 1.
        String lastOfPage0 = content0.get(9).get("title").asText();
        String firstOfPage1 = page1.get("content").get(0).get("title").asText();
        assertThat(lastOfPage0).isLessThan(firstOfPage1);
    }

    @Test
    void duplicateTitles_pageWithoutOverlapOrGaps() throws Exception {
        String admin = adminToken();
        // 15 movies that all share one title -> the (title) key ties for every row,
        // so only the id tiebreaker keeps the global order total and the pages
        // disjoint. Without it, a row could land on two pages or vanish.
        for (int i = 0; i < 15; i++) {
            createMovie(admin, new MovieBody("SameTitle", null, null, 100, List.of()));
        }

        Set<Long> ids = new HashSet<>();
        collectIds(ids, getPage(null, 0, 10)); // 10
        collectIds(ids, getPage(null, 1, 10)); // 5

        // Every movie appears exactly once across the two pages: no overlap, no gap.
        assertThat(ids).hasSize(15);
    }

    @Test
    void genreFilter_pagedAndStillReturnsFullGenreSet() throws Exception {
        String admin = adminToken();
        String shared = "PgGenre";
        for (int i = 0; i < 15; i++) {
            createMovie(admin, new MovieBody(
                    "GenMovie " + String.format("%02d", i), null, null, 100,
                    List.of(shared, "Uniq-" + String.format("%02d", i))));
        }

        JsonNode page0 = getPage(shared, 0, 10);
        assertThat(page0.get("totalElements").asLong()).isEqualTo(15);
        assertThat(page0.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page0.get("content")).hasSize(10);
        // The genre filter must NOT truncate each movie's genre set: every paged
        // movie still carries BOTH its genres.
        for (JsonNode movie : page0.get("content")) {
            assertThat(movie.get("genres")).hasSize(2);
            assertThat(genreNames(movie)).contains(shared);
        }

        JsonNode page1 = getPage(shared, 1, 10);
        assertThat(page1.get("content")).hasSize(5);

        // Cross-boundary ordering holds on the filtered path too.
        String lastOfPage0 = page0.get("content").get(9).get("title").asText();
        String firstOfPage1 = page1.get("content").get(0).get("title").asText();
        assertThat(lastOfPage0).isLessThan(firstOfPage1);
    }

    @Test
    void pageSizeBounds_areEnforced() throws Exception {
        // Over the cap, zero, and negative page all reject with 400.
        mockMvc.perform(get("/api/movies").param("size", "101")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/movies").param("size", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/movies").param("page", "-1")).andExpect(status().isBadRequest());
    }

    // --- helpers ---

    private JsonNode getPage(String genre, int page, int size) throws Exception {
        var request = get("/api/movies")
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size));
        if (genre != null) {
            request = request.param("genre", genre);
        }
        return read(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    private void collectIds(Set<Long> into, JsonNode page) {
        for (JsonNode movie : page.get("content")) {
            into.add(movie.get("id").asLong());
        }
    }

    private List<String> genreNames(JsonNode movieJson) {
        return objectMapper.convertValue(
                movieJson.get("genres"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }
}
