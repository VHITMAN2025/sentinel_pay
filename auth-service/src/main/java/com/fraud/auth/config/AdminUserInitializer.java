package com.fraud.auth.config;

import com.fraud.auth.client.BankServiceClient;
import com.fraud.auth.entity.User;
import com.fraud.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * AdminUserInitializer — Seeds karthik@gmail.com with ROLE_ADMIN on startup
 * if not already registered, and creates the associated bank account.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserInitializer implements CommandLineRunner {

    private static final String ADMIN_EMAIL = "karthik@gmail.com";
    private static final String ADMIN_NAME  = "Karthik Admin";
    private static final String ADMIN_UPI   = "karthik@upi";
    private static final String ADMIN_PASS  = "Admin@12345";

    private final UserRepository    userRepository;
    private final PasswordEncoder   passwordEncoder;
    private final BankServiceClient bankServiceClient;

    @Override
    public void run(String... args) {
        try {
            userRepository.findByEmail(ADMIN_EMAIL).ifPresentOrElse(
                user -> {
                    if (!"ROLE_ADMIN".equals(user.getRole())) {
                        user.setRole("ROLE_ADMIN");
                        userRepository.save(user);
                        log.info("[ADMIN-SEEDER] Upgraded {} to ROLE_ADMIN", ADMIN_EMAIL);
                    } else {
                        log.info("[ADMIN-SEEDER] Admin user {} already configured with ROLE_ADMIN", ADMIN_EMAIL);
                    }
                },
                () -> {
                    User admin = User.builder()
                            .name(ADMIN_NAME)
                            .email(ADMIN_EMAIL)
                            .password(passwordEncoder.encode(ADMIN_PASS))
                            .upiId(ADMIN_UPI)
                            .role("ROLE_ADMIN")
                            .build();
                    userRepository.save(admin);
                    log.info("[ADMIN-SEEDER] Created default admin account: {} (password: {})", ADMIN_EMAIL, ADMIN_PASS);
                    try {
                        bankServiceClient.createBankAccount(ADMIN_EMAIL, ADMIN_UPI);
                        log.info("[ADMIN-SEEDER] Created bank account for admin {}", ADMIN_EMAIL);
                    } catch (Exception e) {
                        log.warn("[ADMIN-SEEDER] Bank account creation deferred or skipped: {}", e.getMessage());
                    }
                }
            );
        } catch (Exception e) {
            log.error("[ADMIN-SEEDER] Error ensuring admin account: {}", e.getMessage(), e);
        }
    }
}
