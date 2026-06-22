package org.example.moviereservationsystem.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Total revenue from CONFIRMED reservations over an optional date range. The
 * range echoes back the requested {@code from}/{@code to} (either may be null =
 * unbounded). {@code totalRevenue} is 0 (never null) when nothing matches.
 */
public record RevenueReport(
        LocalDate from,
        LocalDate to,
        BigDecimal totalRevenue,
        long confirmedReservations) {
}
