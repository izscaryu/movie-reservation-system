package org.example.moviereservationsystem.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.moviereservationsystem.entity.Role;
import org.example.moviereservationsystem.entity.User;
import org.example.moviereservationsystem.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds a single ADMIN user on startup if one does not already exist.
 * Idempotent: keyed on the configured admin email, so it is safe to run on
 * every boot (including tests). The password is hashed at runtime, so no
 * bcrypt hash is committed to source control.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminUserInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email}")
    private String adminEmail;

    @Value("${app.admin.password}")
    private String adminPassword;

    @Value("${app.admin.name}")
    private String adminName;

    @Override
    public void run(String... args) {
        if (userRepository.existsByEmail(adminEmail)) {
            log.info("Admin user '{}' already exists; skipping seed.", adminEmail);
            return;
        }

        User admin = new User();
        admin.setEmail(adminEmail);
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setName(adminName);
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);

        log.info("Seeded admin user '{}'.", adminEmail);
    }
}
