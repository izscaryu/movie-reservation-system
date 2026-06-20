package org.example.moviereservationsystem.service;

import lombok.RequiredArgsConstructor;
import org.example.moviereservationsystem.dto.UserResponse;
import org.example.moviereservationsystem.dto.auth.AuthResponse;
import org.example.moviereservationsystem.dto.auth.LoginRequest;
import org.example.moviereservationsystem.dto.auth.SignupRequest;
import org.example.moviereservationsystem.entity.Role;
import org.example.moviereservationsystem.entity.User;
import org.example.moviereservationsystem.exception.EmailAlreadyExistsException;
import org.example.moviereservationsystem.repository.UserRepository;
import org.example.moviereservationsystem.security.JwtService;
import org.example.moviereservationsystem.security.UserPrincipal;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    /** Registers a new USER. Throws if the email is taken. */
    @Transactional
    public UserResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }
        User user = new User();
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setName(request.name());
        user.setRole(Role.USER);
        return UserResponse.fromEntity(userRepository.save(user));
    }

    /** Verifies credentials and issues a JWT. Throws BadCredentialsException on failure. */
    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        String token = jwtService.generateToken(principal);
        return AuthResponse.bearer(token, jwtService.getExpirationMs());
    }
}
