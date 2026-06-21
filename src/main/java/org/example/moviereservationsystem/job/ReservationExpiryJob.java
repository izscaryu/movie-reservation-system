package org.example.moviereservationsystem.job;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.moviereservationsystem.service.ReservationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Releases expired seat holds. Runs every 60s.
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

    @Scheduled(fixedRate = 60_000)
    public void run() {
        runOnce();
    }

    /** Extracted so tests can drive one sweep deterministically. Returns the count expired. */
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
