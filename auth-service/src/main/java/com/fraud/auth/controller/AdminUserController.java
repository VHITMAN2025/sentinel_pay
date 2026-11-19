package com.fraud.auth.controller;

import com.fraud.auth.dto.UserSummaryResponse;
import com.fraud.auth.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<?> getAllUsers(
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            HttpServletRequest request) {

        if (!"karthik@gmail.com".equalsIgnoreCase(userEmail) || !"ROLE_ADMIN".equals(userRole)) {
            log.warn("[ADMIN-USER-CTRL] Unauthorized access attempt: email={} role={}", userEmail, userRole);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                            "error", "Forbidden",
                            "message", "Access restricted: Only karthik@gmail.com has administrative privileges."
                    ));
        }

        List<UserSummaryResponse> users = userRepository.findAll().stream()
                .map(u -> UserSummaryResponse.builder()
                        .id(u.getId())
                        .name(u.getName())
                        .email(u.getEmail())
                        .upiId(u.getUpiId())
                        .role(u.getRole())
                        .build())
                .collect(Collectors.toList());

        log.info("[ADMIN-USER-CTRL] Admin fetched {} users", users.size());
        return ResponseEntity.ok(users);
    }
}
