package com.fraud.detection.controller;

import com.fraud.common.dto.FraudResult;
import com.fraud.common.dto.TransactionEvent;
import com.fraud.detection.dto.PendingReviewTransactionResponse;
import com.fraud.detection.model.PaymentRecord;
import com.fraud.detection.model.ReviewDecision;
import com.fraud.detection.model.ReviewDecisionRequest;
import com.fraud.detection.payment.PaymentProcessorService;
import com.fraud.detection.service.RedisService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/admin/fraud")
@RequiredArgsConstructor
public class AdminFraudController {

    private final RedisService redisService;
    private final PaymentProcessorService paymentProcessorService;

    // ─── Stats ───────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<?> getAdminStats(
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        if (!isAdmin(userEmail, userRole)) return forbiddenResponse();

        List<FraudResult> results = redisService.getAllRecentFraudResults();
        long total = results.size();
        long safe = results.stream().filter(r -> r.getStatus() == FraudResult.FraudStatus.SAFE).count();
        long review = results.stream().filter(r -> r.getStatus() == FraudResult.FraudStatus.REVIEW).count();
        long blocked = results.stream().filter(r -> r.getStatus() == FraudResult.FraudStatus.FRAUD).count();
        double fraudRate = total > 0 ? ((double) blocked / total) * 100.0 : 0.0;

        List<PendingReviewTransactionResponse> pendingQueue = redisService.getAllPendingReviewTransactions();
        List<Map<String, String>> blocklist = redisService.getBlocklist();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalAnalysed", total);
        stats.put("safeCount", safe);
        stats.put("reviewCount", review);
        stats.put("blockedCount", blocked);
        stats.put("fraudRate", String.format("%.1f%%", fraudRate));
        stats.put("pendingReviewCount", pendingQueue.size());
        stats.put("activeBlocklistCount", blocklist.size());
        stats.put("timestamp", LocalDateTime.now().toString());

        return ResponseEntity.ok(stats);
    }

    // ─── Transactions Ledger ─────────────────────────────────────────────────

    @GetMapping("/transactions")
    public ResponseEntity<?> getAllFraudTransactions(
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        if (!isAdmin(userEmail, userRole)) return forbiddenResponse();

        List<FraudResult> results = redisService.getAllRecentFraudResults();
        results.sort(Comparator.comparing(FraudResult::getAnalyzedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return ResponseEntity.ok(results);
    }

    // ─── Review Queue ────────────────────────────────────────────────────────

    @GetMapping("/review-queue")
    public ResponseEntity<?> getReviewQueue(
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        if (!isAdmin(userEmail, userRole)) return forbiddenResponse();

        List<PendingReviewTransactionResponse> pending = redisService.getAllPendingReviewTransactions();
        return ResponseEntity.ok(pending);
    }

    @PostMapping("/review/{transactionId}")
    public ResponseEntity<?> submitAdminReviewDecision(
            @PathVariable String transactionId,
            @Valid @RequestBody ReviewDecisionRequest decisionRequest,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        if (!isAdmin(userEmail, userRole)) return forbiddenResponse();

        TransactionEvent reviewEvent = redisService.getReviewTransaction(transactionId);
        ReviewDecision requested = ReviewDecision.builder()
                .transactionId(transactionId)
                .userId(userEmail)
                .decision(decisionRequest.getDecision())
                .decidedAt(LocalDateTime.now())
                .amount(reviewEvent != null ? reviewEvent.getAmount() : null)
                .payeeUpiId(reviewEvent != null ? reviewEvent.getPayeeUpiId() : null)
                .build();

        ReviewDecision saved = redisService.saveReviewDecision(requested);

        if (decisionRequest.getDecision() == ReviewDecision.Decision.APPROVE && reviewEvent != null) {
            try {
                PaymentRecord record = paymentProcessorService.processManuallyApprovedPayment(reviewEvent);
                if (record != null) {
                    saved.setPaymentStatus(record.getPaymentStatus());
                }
            } catch (Exception e) {
                log.error("[ADMIN-FRAUD-CTRL] Payment execution failed on admin approval for txId={}", transactionId, e);
            }
        }

        redisService.recordAdminAudit("REVIEW_DECISION", transactionId,
                "Admin decision: " + decisionRequest.getDecision() + (reviewEvent != null ? " for " + reviewEvent.getAmount() + " INR" : ""));

        return ResponseEntity.ok(saved);
    }

    // ─── Dynamic Rules ───────────────────────────────────────────────────────

    @GetMapping("/rules")
    public ResponseEntity<?> getFraudRules(
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        if (!isAdmin(userEmail, userRole)) return forbiddenResponse();
        return ResponseEntity.ok(redisService.getDynamicRules());
    }

    @PutMapping("/rules")
    public ResponseEntity<?> updateFraudRules(
            @RequestBody Map<String, Object> rules,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        if (!isAdmin(userEmail, userRole)) return forbiddenResponse();
        redisService.updateDynamicRules(rules);
        return ResponseEntity.ok(Map.of("message", "Fraud rules updated successfully", "rules", rules));
    }

    // ─── Blocklist ───────────────────────────────────────────────────────────

    @GetMapping("/blocklist")
    public ResponseEntity<?> getBlocklist(
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        if (!isAdmin(userEmail, userRole)) return forbiddenResponse();
        return ResponseEntity.ok(redisService.getBlocklist());
    }

    @PostMapping("/blocklist")
    public ResponseEntity<?> addToBlocklist(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        if (!isAdmin(userEmail, userRole)) return forbiddenResponse();

        String item = body.get("item");
        String type = body.getOrDefault("type", "UPI_ID");
        String reason = body.getOrDefault("reason", "Suspicious activity detected by admin");

        if (item == null || item.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "item is required"));
        }

        redisService.addToBlocklist(item.trim(), type, reason);
        return ResponseEntity.ok(Map.of("message", "Item added to blocklist", "item", item));
    }

    @DeleteMapping("/blocklist/{item}")
    public ResponseEntity<?> removeFromBlocklist(
            @PathVariable String item,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        if (!isAdmin(userEmail, userRole)) return forbiddenResponse();

        redisService.removeFromBlocklist(item);
        return ResponseEntity.ok(Map.of("message", "Item removed from blocklist", "item", item));
    }

    // ─── Audit Logs ──────────────────────────────────────────────────────────

    @GetMapping("/audit-logs")
    public ResponseEntity<?> getAuditLogs(
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        if (!isAdmin(userEmail, userRole)) return forbiddenResponse();
        return ResponseEntity.ok(redisService.getAdminAuditLogs());
    }

    // ─── System Health ───────────────────────────────────────────────────────

    @GetMapping("/system/health")
    public ResponseEntity<?> getSystemHealth(
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        if (!isAdmin(userEmail, userRole)) return forbiddenResponse();

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "HEALTHY");
        health.put("authService", "UP");
        health.put("bankService", "UP");
        health.put("transactionService", "UP");
        health.put("fraudDetectionService", "UP");
        health.put("redisCache", "CONNECTED");
        health.put("kafkaCluster", "ACTIVE");
        health.put("geminiAiModel", "ACTIVE (gemini-1.5-flash)");
        health.put("timestamp", LocalDateTime.now().toString());

        return ResponseEntity.ok(health);
    }

    private boolean isAdmin(String email, String role) {
        return "karthik@gmail.com".equalsIgnoreCase(email) && "ROLE_ADMIN".equals(role);
    }

    private ResponseEntity<?> forbiddenResponse() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of(
                        "error", "Forbidden",
                        "message", "Access restricted: Only karthik@gmail.com has administrative privileges."
                ));
    }
}
