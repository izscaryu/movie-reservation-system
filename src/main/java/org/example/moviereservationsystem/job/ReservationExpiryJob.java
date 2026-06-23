package org.example.moviereservationsystem.job;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.moviereservationsystem.service.ReservationService;
import org.springframework.stereotype.Component;

/**
 * Releases expired seat holds. The recurring trigger lives in
 * {@link ReservationExpiryScheduler}; this bean holds the sweep logic and stays
 * unconditional so tests can drive {@link #runOnce()} directly even when the
 * scheduled trigger is disabled.
 *
 * <p>This is a SEPARATE bean from ReservationService on purpose: each overdue
 * hold is expired by calling {@code reservationService.expireOne(id)}, which
 * crosses the bean boundary so Spring's transaction proxy actually applies and
 * every reservation is expired in its OWN transaction. If this loop lived inside
 * ReservationService and called {@code this.expireOne(...)}, the self-invocation
 * would bypass the proxy and the per-reservation @Transactional would silently
 * not take effect. One bad reservation is logged and skipped, not allowed to
 * abort the rest.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationExpiryJob {

    private final ReservationService reservationService;

    /** Runs one expiry sweep. Returns the count expired. */
    public int runOnce() {
        List<Long> overdue = reservationService.findOverdueHoldIds();
        int expired = 0;
        for (Long id : overdue) {
            try {
                reservationService.expireOne(id);
                expired++;
            } catch (RuntimeException e) {
                log.warn("Failed to expire reservation {}: {}", id, e.getMessage());
            }
        }
        if (expired > 0) {
            log.info("Expired {} overdue hold(s).", expired);
        }
        return expired;
    }
}
