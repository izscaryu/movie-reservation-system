package org.example.moviereservationsystem.service;

import lombok.RequiredArgsConstructor;
import org.example.moviereservationsystem.dto.UserResponse;
import org.example.moviereservationsystem.entity.Role;
import org.example.moviereservationsystem.entity.User;
import org.example.moviereservationsystem.exception.ResourceNotFoundException;
import org.example.moviereservationsystem.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /**
     * Promotes a user to ADMIN. Idempotent — promoting an existing admin is a
     * no-op. Note: because tokens are stateless, an already-logged-in user must
     * obtain a new token (re-login) before the new role takes effect.
     */
    @Transactional
    public UserResponse promoteToAdmin(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No user with id: " + userId));
        user.setRole(Role.ADMIN);
        return UserResponse.fromEntity(userRepository.save(user));
    }
}
