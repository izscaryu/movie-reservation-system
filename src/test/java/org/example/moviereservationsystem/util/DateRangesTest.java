package org.example.moviereservationsystem.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.example.moviereservationsystem.exception.BadRequestException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure date-range helper shared by ReportService and
 * AdminReservationService. Bounds are inclusive to the caller on both ends but
 * implemented half-open under the hood ({@code >= from 00:00}, {@code < (to+1d) 00:00});
 * an inverted range is a 400. No Spring context, no DB — pure functions.
 */
class DateRangesTest {

    @Test
    void validateRange_fromAfterTo_throws400() {
        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> DateRanges.validateRange(
                        LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 1)));
    }

    @Test
    void validateRange_ascendingEqualOrOpen_isAccepted() {
        // Equal bounds (a single day), an ascending range, and any open bound are
        // all valid — only a strictly inverted range is rejected.
        assertThatNoException().isThrownBy(() -> {
            DateRanges.validateRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1));
            DateRanges.validateRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2));
            DateRanges.validateRange(null, LocalDate.of(2026, 6, 2));
            DateRanges.validateRange(LocalDate.of(2026, 6, 1), null);
            DateRanges.validateRange(null, null);
        });
    }

    @Test
    void startOfDay_isInclusiveLowerBound_orNullWhenUnbounded() {
        assertThat(DateRanges.startOfDay(null)).isNull();
        assertThat(DateRanges.startOfDay(LocalDate.of(2026, 6, 1)))
                .isEqualTo(LocalDateTime.of(2026, 6, 1, 0, 0));
    }

    @Test
    void startOfDayAfter_makesInclusiveToHalfOpen_orNullWhenUnbounded() {
        assertThat(DateRanges.startOfDayAfter(null)).isNull();
        // 'to' = 2026-06-01 becomes the start of 2026-06-02, so the whole of the
        // 2026-06-01 day satisfies createdAt < bound (inclusive to the caller).
        assertThat(DateRanges.startOfDayAfter(LocalDate.of(2026, 6, 1)))
                .isEqualTo(LocalDateTime.of(2026, 6, 2, 0, 0));
    }

    @Test
    void singleDayRange_bracketsTheWholeToDay() {
        LocalDate day = LocalDate.of(2026, 6, 1);
        LocalDateTime lower = DateRanges.startOfDay(day);
        LocalDateTime upper = DateRanges.startOfDayAfter(day);
        LocalDateTime endOfDay = LocalDateTime.of(2026, 6, 1, 23, 59, 59);

        // A timestamp at the very end of the 'to' day is still inside [lower, upper).
        assertThat(endOfDay).isAfterOrEqualTo(lower).isBefore(upper);
    }
}
