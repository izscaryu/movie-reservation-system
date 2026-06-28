package org.example.moviereservationsystem.service;

import lombok.RequiredArgsConstructor;
import org.example.moviereservationsystem.dto.UserResponse;
import org.example.moviereservationsystem.dto.auth.AuthResponse;
import org.example.moviereservationsystem.dto.auth.LoginRequest;
import org.example.moviereservationsystem.dto.auth.RefreshTokenRequest;
import org.example.moviereservationsystem.dto.auth.SignupRequest;
import org.example.moviereservationsystem.entity.RefreshToken;
import org.example.moviereservationsystem.entity.Role;
import org.example.moviereservationsystem.entity.User;
import org.example.moviereservationsystem.exception.EmailAlreadyExistsException;
import org.example.moviereservationsystem.exception.InvalidRefreshTokenException;
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
    private final RefreshTokenService refreshTokenService;

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

    /**
     * Verifies credentials and issues an access JWT plus a persisted refresh
     * token. Throws BadCredentialsException on failure.
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        // getReferenceById: a proxy is enough to set the FK; no extra user load.
        User userRef = userRepository.getReferenceById(principal.getId());
        return issueTokens(principal, userRef);
    }

    /**
     * Rotates a refresh token: validates + consumes the presented one (with reuse
     * detection) and issues a fresh access + refresh pair. Invalid/expired/revoked
     * -> 401 via InvalidRefreshTokenException.
     *
     * <p>noRollbackFor: on reuse detection (a replayed revoked token, OR losing the
     * atomic consume race), verifyAndConsume revokes the whole token family (a write)
     * and THEN throws to signal 401. Without this, the throw would roll back that
     * family-revoke. Marking the exception non-rollback lets the security write commit
     * while still surfacing the 401. The other 401 paths (unknown/expired) perform no
     * writes, so committing their empty tx is harmless.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshToken consumed = refreshTokenService.verifyAndConsume(request.refreshToken());
        User user = consumed.getUser();
        return issueTokens(UserPrincipal.fromEntity(user), user);
    }

    /** Logout: revokes the presented refresh token. Idempotent (no error if unknown). */
    @Transactional
    public void logout(RefreshTokenRequest request) {
        refreshTokenService.revoke(request.refreshToken());
    }

    private AuthResponse issueTokens(UserPrincipal principal, User user) {
        String accessToken = jwtService.generateToken(principal);
        String refreshToken = refreshTokenService.issueFor(user);
        return AuthResponse.of(accessToken, jwtService.getAccessExpirationMs(), refreshToken);
    }
}
