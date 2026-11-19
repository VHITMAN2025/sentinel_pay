package com.fraud.auth.service;

import com.fraud.auth.client.BankServiceClient;
import com.fraud.auth.dto.AuthResponse;
import com.fraud.auth.dto.LoginRequest;
import com.fraud.auth.dto.RegisterRequest;
import com.fraud.auth.entity.User;
import com.fraud.auth.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AuthService — core authentication business logic.
 */
@Slf4j
@Service
public class AuthService {

    private final UserRepository    userRepository;
    private final PasswordEncoder   passwordEncoder;
    private final JwtService        jwtService;
    private final BankServiceClient bankServiceClient;

    @Lazy
    @Autowired
    private AuthService self;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       BankServiceClient bankServiceClient) {
        this.userRepository    = userRepository;
        this.passwordEncoder   = passwordEncoder;
        this.jwtService        = jwtService;
        this.bankServiceClient = bankServiceClient;
    }

    // ─── Register ────────────────────────────────────────────────────────────

    public AuthResponse register(RegisterRequest request) {
        String email = request.getEmail().toLowerCase().trim();

        // DB work — committed before bank-service call
        RegistrationResult result = self.registerUserInternal(email,
                request.getName().trim(),
                request.getPassword());

        // Bank-service call runs AFTER DB transaction committed — non-fatal
        bankServiceClient.createBankAccount(email, result.upiId());

        // Issue JWT access + refresh tokens
        String token = jwtService.generateToken(email, result.role(), result.name(), result.upiId());
        String refreshToken = jwtService.generateRefreshToken(email);

        return AuthResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .email(email)
                .name(result.name())
                .upiId(result.upiId())
                .role(result.role())
                .message("Registration successful")
                .build();
    }

    @Transactional
    public RegistrationResult registerUserInternal(String email, String name, String rawPassword) {
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email is already registered: " + email);
        }

        String upiId = generateUniqueUpiId(name);
        String role = "karthik@gmail.com".equalsIgnoreCase(email) ? "ROLE_ADMIN" : "ROLE_USER";

        User user = User.builder()
                .name(name)
                .email(email)
                .password(passwordEncoder.encode(rawPassword))
                .upiId(upiId)
                .role(role)
                .build();

        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            log.warn("[AUTH] Constraint violation on save: email={}", email);
            if (userRepository.existsByEmail(email)) {
                throw new IllegalArgumentException("Email is already registered: " + email);
            }
            throw new IllegalArgumentException("Registration failed due to a conflict. Please try again.");
        }

        log.info("[AUTH] User persisted: email={} upiId={} role={}", email, upiId, role);
        return new RegistrationResult(user.getName(), upiId, role);
    }

    // ─── Login ───────────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.getEmail().toLowerCase().trim();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        log.info("[AUTH] Login successful: email={}", email);

        String role = "ROLE_USER";
        if ("karthik@gmail.com".equalsIgnoreCase(email)) {
            role = "ROLE_ADMIN";
            if (!"ROLE_ADMIN".equals(user.getRole())) {
                user.setRole("ROLE_ADMIN");
                userRepository.save(user);
            }
        } else if ("ROLE_ADMIN".equals(user.getRole())) {
            user.setRole("ROLE_USER");
            userRepository.save(user);
        } else if (user.getRole() != null) {
            role = user.getRole();
        }

        String token = jwtService.generateToken(email, role, user.getName(), user.getUpiId());
        String refreshToken = jwtService.generateRefreshToken(email);

        return AuthResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .email(email)
                .name(user.getName())
                .upiId(user.getUpiId())
                .role(role)
                .message("Login successful")
                .build();
    }

    // ─── Refresh Token ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AuthResponse refresh(String refreshToken) {
        if (refreshToken == null || !jwtService.isRefreshTokenValid(refreshToken)) {
            throw new IllegalArgumentException("Invalid or expired refresh token");
        }

        String email = jwtService.extractEmail(refreshToken);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + email));

        String role = "karthik@gmail.com".equalsIgnoreCase(email) ? "ROLE_ADMIN" : "ROLE_USER";
        String newAccessToken = jwtService.generateToken(email, role, user.getName(), user.getUpiId());
        String newRefreshToken = jwtService.generateRefreshToken(email);

        log.info("[AUTH] Token refreshed successfully for email={}", email);

        return AuthResponse.builder()
                .token(newAccessToken)
                .refreshToken(newRefreshToken)
                .email(email)
                .name(user.getName())
                .upiId(user.getUpiId())
                .role(role)
                .message("Token refreshed successfully")
                .build();
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    private String generateUniqueUpiId(String name) {
        String base = name.toLowerCase()
                          .replaceAll("[^a-z0-9]", "")
                          .trim();

        if (base.isEmpty()) {
            base = "user";
        }

        String candidate = base + "@upi";
        int suffix = 1;

        while (userRepository.existsByUpiId(candidate)) {
            candidate = base + suffix + "@upi";
            suffix++;
        }

        return candidate;
    }

    // ─── Internal DTO ────────────────────────────────────────────────────────

    public record RegistrationResult(String name, String upiId, String role) {}
}
