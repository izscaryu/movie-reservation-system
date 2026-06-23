package org.example.moviereservationsystem.job;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The recurring trigger for {@link ReservationExpiryJob}. Split out so the
 * trigger -- not the job -- can be switched off: with
 * {@code app.scheduling.enabled=false} (the test profile) this bean is never
 * created, so {@code @Scheduled} never fires, yet {@link ReservationExpiryJob}
 * and its {@code runOnce()} stay available for deterministic test use.
 *
 * <p>Default on ({@code matchIfMissing = true}) so production behaviour is
 * unchanged: a sweep every 60 seconds.
 */
@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ReservationExpiryScheduler {

    private final ReservationExpiryJob expiryJob;

    @Scheduled(fixedRate = 60_000)
    public void trigger() {
        expiryJob.runOnce();
    }
}
