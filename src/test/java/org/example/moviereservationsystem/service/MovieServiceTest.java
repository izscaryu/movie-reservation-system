package org.example.moviereservationsystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.example.moviereservationsystem.dto.movie.MovieRequest;
import org.example.moviereservationsystem.dto.movie.MovieResponse;
import org.example.moviereservationsystem.entity.Genre;
import org.example.moviereservationsystem.entity.Movie;
import org.example.moviereservationsystem.repository.GenreRepository;
import org.example.moviereservationsystem.repository.MovieRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for the PURE genre-resolution logic in MovieService: within a single
 * request, names are de-duplicated by their lowercased, trimmed form, and
 * null/blank names are skipped. The first-seen casing is the one kept.
 *
 * <p>The get-or-create round-trip itself (findByNameIgnoreCase else insert, and
 * reuse of an existing row across requests) is a DB behaviour covered by
 * MovieIntegrationTest; here genreRepository is mocked, so we assert how many
 * distinct names the service tried to resolve, not what the DB returns.
 */
@ExtendWith(MockitoExtension.class)
class MovieServiceTest {

    @Mock
    private MovieRepository movieRepository;
    @Mock
    private GenreRepository genreRepository;

    @InjectMocks
    private MovieService service;

    @Test
    void create_dedupsGenresByLowercasedTrimmedName_skipsBlanks_keepsFirstSeenCasing() {
        // findByNameIgnoreCase returns empty (no existing row), save echoes the
        // genre it was given, movie save echoes the movie it was given.
        when(genreRepository.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(genreRepository.save(any(Genre.class))).thenAnswer(inv -> inv.getArgument(0));
        when(movieRepository.save(any(Movie.class))).thenAnswer(inv -> inv.getArgument(0));

        // Five entries that collapse to ONE genre: case variants, surrounding
        // whitespace, a blank, and a null.
        MovieResponse response = service.create(new MovieRequest(
                "The Matrix", null, null, 136,
                Arrays.asList("Action", "action", "  ", null, " ACTION ")));

        // Exactly one distinct genre resolved, in its first-seen casing ("Action").
        ArgumentCaptor<String> resolved = ArgumentCaptor.forClass(String.class);
        verify(genreRepository, times(1)).findByNameIgnoreCase(resolved.capture());
        assertThat(resolved.getValue()).isEqualTo("Action");
        verify(genreRepository, times(1)).save(any(Genre.class));

        // The response reflects the single de-duplicated genre.
        assertThat(response.genres()).containsExactly("Action");
    }
}
