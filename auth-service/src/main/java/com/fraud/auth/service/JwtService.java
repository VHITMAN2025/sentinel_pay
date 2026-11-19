package com.fraud.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * JwtService — JWT token generation and validation.
 *
 * Uses HS256 with a shared secret (must match the api-gateway secret).
 * Email is stored as the JWT subject — used by the gateway to inject X-User-Email.
 * Role is stored in claims for RBAC in frontend and downstream services.
 */
@Slf4j
@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration-ms:86400000}")  // default: 24 hours
    private long jwtExpirationMs;

    @Value("${jwt.refresh-expiration-ms:604800000}")  // default: 7 days
    private long refreshExpirationMs;

    /**
     * Generate a signed JWT access token for the given email with role and metadata.
     */
    public String generateToken(String email, String role, String name, String upiId) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + jwtExpirationMs);

        String effectiveRole = (role != null && !role.isBlank()) ? role : "ROLE_USER";

        return Jwts.builder()
                .setSubject(email)
                .claim("role", effectiveRole)
                .claim("roles", List.of(effectiveRole))
                .claim("name", name != null ? name : "")
                .claim("upiId", upiId != null ? upiId : "")
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Backward-compatible token generator.
     */
    public String generateToken(String email) {
        return generateToken(email, "ROLE_USER", null, null);
    }

    /**
     * Generate a signed refresh token for the given email.
     */
    public String generateRefreshToken(String email) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + refreshExpirationMs);

        return Jwts.builder()
                .setSubject(email)
                .claim("type", "REFRESH")
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Extract the email (subject) from a JWT token.
     */
    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Validate an access token.
     */
    public boolean isTokenValid(String token) {
        try {
            Claims claims = parseClaims(token);
            return !claims.getExpiration().before(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[JWT] Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Validate a refresh token.
     */
    public boolean isRefreshTokenValid(String token) {
        try {
            Claims claims = parseClaims(token);
            boolean notExpired = !claims.getExpiration().before(new Date());
            boolean isRefreshType = "REFRESH".equals(claims.get("type"));
            return notExpired && isRefreshType;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[JWT] Refresh token validation failed: {}", e.getMessage());
            return false;
        }
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Key getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
