package com.truesight.backend.service;

import com.truesight.backend.common.ConflictException;
import com.truesight.backend.common.InvalidCredentialsException;
import com.truesight.backend.domain.Portfolio;
import com.truesight.backend.domain.User;
import com.truesight.backend.domain.UserSettings;
import com.truesight.backend.repository.PortfolioRepository;
import com.truesight.backend.repository.UserRepository;
import com.truesight.backend.repository.UserSettingsRepository;
import com.truesight.backend.security.JwtService;
import com.truesight.backend.web.dto.AuthResponse;
import com.truesight.backend.web.dto.LoginRequest;
import com.truesight.backend.web.dto.RegisterRequest;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final PortfolioRepository portfolioRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            UserSettingsRepository userSettingsRepository,
            PortfolioRepository portfolioRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.userSettingsRepository = userSettingsRepository;
        this.portfolioRepository = portfolioRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // AC 1.1: "Duplicate email rejected with 'an account with this email already
        // exists'." Checked explicitly (rather than relying on the DB unique constraint
        // throwing) so the error message is the exact user-facing one the AC specifies,
        // not a raw constraint-violation message.
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("An account with this email already exists");
        }

        User user = new User(request.email(), passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName());
        user.setFirm(request.firm());
        user = userRepository.save(user);

        userSettingsRepository.save(new UserSettings(user));

        // AC 1.1: "On success the user is logged in and lands on an empty portfolio
        // page." A default portfolio is created so there's always at least one to land
        // on, without the frontend needing a separate "no portfolios yet, create one"
        // branch on the very first screen after registering.
        portfolioRepository.save(new Portfolio(user, "My Portfolio"));

        String token = jwtService.issueToken(user.getId(), user.getEmail());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getDisplayName());
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        // AC 1.2: one generic message for both "no such email" and "wrong password" —
        // enforced by funnelling both failure paths through the same exception type
        // rather than distinguishing them anywhere in this method.
        User user = userRepository.findByEmailIgnoreCase(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        // getPreviousLoginAt() still holds the login-before-this-one at this point —
        // AC 3.4's dashboard summary reads that field directly to compute "since your
        // last visit" counts, so it must be overwritten only AFTER anything this
        // request needs has already read it. Nothing in this method needs the old
        // value itself, but the ordering here is what keeps the field meaningful.
        user.setPreviousLoginAt(Instant.now());
        userRepository.save(user);

        String token = jwtService.issueToken(user.getId(), user.getEmail());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getDisplayName());
    }
}
