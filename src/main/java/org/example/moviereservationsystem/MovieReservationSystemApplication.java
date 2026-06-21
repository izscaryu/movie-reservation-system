package org.example.moviereservationsystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling activates the @Scheduled expiry job (Phase 5). Without it
// the job bean is created but never fired.
@SpringBootApplication
@EnableScheduling
public class MovieReservationSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(MovieReservationSystemApplication.class, args);
    }

}
