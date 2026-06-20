package org.example.moviereservationsystem.dto;

import org.example.moviereservationsystem.entity.Role;
import org.example.moviereservationsystem.entity.User;

public record UserResponse(Long id, String email, String name, Role role) {

    public static UserResponse fromEntity(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getRole());
    }
}
