package org.example.moviereservationsystem.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.example.moviereservationsystem.exception.BadRequestException;

/**
 * Shared date-range handling for the report and admin-reservation endpoints.
 * Bounds are ISO {@code yyyy-MM-dd}, both optional and inclusive to the caller,
 * implemented half-open under the hood ({@code >= from 00:00}, {@code < (to+1d) 00:00})
 * so the whole {@code to} day is included while the query stays a clean
 * {@code createdAt < bound}. Extracted from ReportService so every date-bounded
 * endpoint shares one implementation (and the same {@code from > to -> 400}).
 */
public final class DateRanges {

    private DateRanges() {
    }

    /** Reject an inverted range with a 400. */
    public static void validateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("'from' must not be after 'to'");
        }
    }

    /** Inclusive lower bound: start of the {@code from} day. Null = unbounded below. */
    public static LocalDateTime startOfDay(LocalDate from) {
        return from == null ? null : from.atStartOfDay();
    }

    /** Inclusive {@code to} made half-open: start of the day AFTER {@code to}. Null = unbounded above. */
    public static LocalDateTime startOfDayAfter(LocalDate to) {
        return to == null ? null : to.plusDays(1).atStartOfDay();
    }
}
