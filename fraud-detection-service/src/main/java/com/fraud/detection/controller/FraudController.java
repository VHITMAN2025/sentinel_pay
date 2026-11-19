package com.fraud.detection.controller;

import com.fraud.common.dto.FraudResult;
import com.fraud.common.dto.TransactionEvent;
import com.fraud.detection.dto.PendingReviewTransactionResponse;
import com.fraud.detection.model.PaymentRecord;
import com.fraud.detection.model.ReviewDecision;
import com.fraud.detection.model.ReviewDecisionRequest;
import com.fraud.detection.model.UserTransactionHistory;
import com.fraud.detection.payment.PaymentProcessorService;
import com.fraud.detection.service.FraudDetectionService;
import com.fraud.detection.service.RedisService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FraudController — REST API for fraud-detection-service (Hardening v2)
 *
 * Endpoints:
 *   GET  /api/fraud/health
 *   GET  /api/fraud/result/{transactionId}    — fraud verdict polling
 *   GET  /api/fraud/payment/{transactionId}   — payment lifecycle status (NEW)
 *   DELETE /api/fraud/result/{transactionId}  — clear result for REVIEW re-processing
 *   GET  /api/fraud/history/{userId}          — user behaviour history (debug)
 *   POST /api/fraud/analyze                   — direct analysis (testing only)
 *
 * Error responses follow the standard schema:
 *   { traceId, timestamp, httpStatus, error, message, path }
 *
 * X-User-Email header: all GET endpoints read userId from path/param, not from
 * X-User-Email — the fraud service is an internal service queried by transaction-service,
 * not directly by end users. No identity spoofing risk on read-only endpoints.
 */
@Validated
@RestController
@RequestMapping("/api/fraud")
public class FraudController {

    private static final Logger log = LoggerFactory.getLogger(FraudController.class);

    @Autowired private FraudDetectionService fraudDetectionService;
    @Autowired private RedisService          redisService;
    @Autowired private PaymentProcessorService paymentProcessorService;

    // ── Health ────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service",   "fraud-detection-service");
        body.put("status",    "UP");
        body.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.ok(body);
    }

    // ── Fraud Result ──────────────────────────────────────────────────────────

    /**
     * GET /api/fraud/result/{transactionId}
     *
     * Poll for the fraud verdict after submitting a transaction.
     *
     * Response codes:
     *   200 — verdict ready; body = FraudResult
     *   202 — not yet processed; body = pending hint with retryAfterSeconds
     */
    @GetMapping("/result/{transactionId}")
    public ResponseEntity<?> getFraudResult(
            @PathVariable @NotBlank(message = "transactionId must not be blank")
            String transactionId,
            HttpServletRequest request) {

        log.info("[FRAUD-CTRL] GET /result/{}", transactionId);
        FraudResult result = redisService.getFraudResult(transactionId);

        if (result == null) {
            log.info("[FRAUD-CTRL] Result pending for txId={}", transactionId);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(buildPendingResponse(transactionId));
        }

        log.info("[FRAUD-CTRL] Result found — txId={} status={} layer={} confidence={}",
                transactionId, result.getStatus(),
                result.getDetectionLayer(), result.getConfidenceScore());
        return ResponseEntity.ok(result);
    }

    // ── Payment Status ────────────────────────────────────────────────────────

    /**
     * GET /api/fraud/payment/{transactionId}
     *
     * Returns the payment lifecycle state for a SAFE-verified transaction.
     * Called internally by transaction-service for GET /api/transactions/{id}.
     *
     * Response codes:
     *   200 — PaymentRecord found; contains paymentStatus, payerEmail, payeeEmail, amount, etc.
     *   202 — Payment not yet initiated (fraud verdict may still be pending)
     *   404 — No payment record (non-UPI transaction or very old/expired)
     */
    @GetMapping("/payment/{transactionId}")
    public ResponseEntity<?> getPaymentStatus(
            @PathVariable("transactionId") @NotBlank(message = "transactionId must not be blank")
            String transactionId,
            HttpServletRequest request) {

        log.info("[FRAUD-CTRL] GET /payment/{}", transactionId);
        PaymentRecord record = redisService.getPaymentRecord(transactionId);

        if (record == null) {
            // Either not a UPI payment, or fraud verdict still pending
            boolean fraudResultExists = redisService.fraudResultExists(transactionId);
            if (fraudResultExists) {
                // Fraud verdict exists but no payment record → non-UPI or FRAUD/REVIEW verdict
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(buildErrorBody(HttpStatus.NOT_FOUND,
                                "No payment record for txId=" + transactionId
                                        + ". Transaction may have been blocked (FRAUD/REVIEW) "
                                        + "or is a non-UPI legacy transaction.",
                                request));
            } else {
                // Neither result exists — fraud pipeline not yet run
                return ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(buildPendingResponse(transactionId));
            }
        }

        log.info("[FRAUD-CTRL] Payment record found — txId={} status={}",
                transactionId, record.getPaymentStatus());
        return ResponseEntity.ok(record);
    }

    // ── Delete Fraud Result ───────────────────────────────────────────────────

    /**
     * DELETE /api/fraud/result/{transactionId}
     * Remove a stored result (used when re-processing a REVIEW verdict).
     */
    @DeleteMapping("/result/{transactionId}")
    public ResponseEntity<?> deleteFraudResult(
            @PathVariable @NotBlank(message = "transactionId must not be blank")
            String transactionId,
            HttpServletRequest request) {

        log.info("[FRAUD-CTRL] DELETE /result/{}", transactionId);
        boolean deleted = redisService.deleteFraudResult(transactionId);

        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(buildErrorBody(HttpStatus.NOT_FOUND,
                            "No fraud result found for txId=" + transactionId, request));
        }
        return ResponseEntity.noContent().build(); // 204
    }

    @GetMapping("/review/{transactionId}")
    public ResponseEntity<?> getReviewDecision(
            @PathVariable @NotBlank String transactionId,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            HttpServletRequest request) {
        ResponseEntity<?> accessError = validateReviewAccess(transactionId, userEmail, request);
        if (accessError != null) return accessError;

        ReviewDecision decision = redisService.getReviewDecision(transactionId);
        TransactionEvent reviewEvent = redisService.getReviewTransaction(transactionId);
        if (decision != null && reviewEvent != null) {
            decision.setAmount(reviewEvent.getAmount());
            decision.setPayeeUpiId(reviewEvent.getPayeeUpiId());
        }
        if (decision != null) {
            PaymentRecord paymentRecord = redisService.getPaymentRecord(transactionId);
            if (paymentRecord != null) {
                decision.setPaymentStatus(paymentRecord.getPaymentStatus());
            }
        }
        if (decision == null && reviewEvent != null) {
            decision = ReviewDecision.builder()
                    .transactionId(transactionId)
                    .amount(reviewEvent.getAmount())
                    .payeeUpiId(reviewEvent.getPayeeUpiId())
                    .build();
        }
        return decision == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(decision);
    }

    @GetMapping("/review/pending")
    public ResponseEntity<?> getPendingReviewTransactions(
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            HttpServletRequest request) {
        if (userEmail == null || userEmail.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(buildErrorBody(HttpStatus.UNAUTHORIZED, "Authentication required.", request));
        }
        List<PendingReviewTransactionResponse> pending =
                redisService.getPendingReviewTransactions(userEmail);
        return ResponseEntity.ok(pending);
    }

    @PostMapping("/review/{transactionId}")
    public ResponseEntity<?> submitReviewDecision(
            @PathVariable @NotBlank String transactionId,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @Valid @RequestBody ReviewDecisionRequest decisionRequest,
            HttpServletRequest request) {
        ResponseEntity<?> accessError = validateReviewAccess(transactionId, userEmail, request);
        if (accessError != null) return accessError;

        TransactionEvent reviewEvent = redisService.getReviewTransaction(transactionId);
        if (decisionRequest.getDecision() == ReviewDecision.Decision.APPROVE) {
            if (reviewEvent == null || !userEmail.equalsIgnoreCase(reviewEvent.getPayerUserId())) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(buildErrorBody(HttpStatus.NOT_FOUND,
                                "The payment details needed to complete this reviewed transaction have expired.", request));
            }
            if (reviewEvent.getPaymentMode() != null
                    && !"BANK".equalsIgnoreCase(reviewEvent.getPaymentMode())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(buildErrorBody(HttpStatus.CONFLICT,
                                "Payer approval is available only for simulated BANK payments.", request));
            }
        }

        ReviewDecision requested = ReviewDecision.builder()
                .transactionId(transactionId)
                .userId(userEmail)
                .decision(decisionRequest.getDecision())
                .decidedAt(LocalDateTime.now())
                .amount(reviewEvent != null ? reviewEvent.getAmount() : null)
                .payeeUpiId(reviewEvent != null ? reviewEvent.getPayeeUpiId() : null)
                .build();
        ReviewDecision saved = redisService.saveReviewDecision(requested);
        if (saved == null || saved.getDecision() != requested.getDecision()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(buildErrorBody(HttpStatus.CONFLICT,
                            "A different decision was already recorded for this transaction.", request));
        }

        if (saved.getDecision() == ReviewDecision.Decision.APPROVE) {
            if (!redisService.acquireReviewApprovalLock(transactionId)) {
                PaymentRecord inProgress = redisService.getPaymentRecord(transactionId);
                saved.setPaymentStatus(inProgress != null ? inProgress.getPaymentStatus() : null);
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(saved);
            }
            try {
                PaymentRecord paymentRecord = paymentProcessorService
                        .processManuallyApprovedPayment(reviewEvent);
                if (paymentRecord == null) {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(buildErrorBody(HttpStatus.SERVICE_UNAVAILABLE,
                                    "Approval was recorded, but payment processing has not completed. Retry shortly.",
                                    request));
                }
                saved.setPaymentStatus(paymentRecord.getPaymentStatus());
            } catch (Exception e) {
                log.error("[FRAUD-CTRL] Approved review payment failed txId={}", transactionId, e);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(buildErrorBody(HttpStatus.SERVICE_UNAVAILABLE,
                                "Approval was recorded, but the payment could not be completed. Retry shortly.",
                                request));
            } finally {
                redisService.releaseReviewApprovalLock(transactionId);
            }
        }
        return ResponseEntity.ok(saved);
    }

    private ResponseEntity<?> validateReviewAccess(String transactionId, String userEmail,
                                                    HttpServletRequest request) {
        if (userEmail == null || userEmail.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(buildErrorBody(HttpStatus.UNAUTHORIZED, "Authentication required.", request));
        }
        FraudResult result = redisService.getFraudResult(transactionId);
        if (result == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(buildErrorBody(HttpStatus.NOT_FOUND, "Fraud result not found.", request));
        }
        if (!FraudResult.FraudStatus.REVIEW.equals(result.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(buildErrorBody(HttpStatus.CONFLICT,
                            "Only transactions with a REVIEW verdict can receive a review decision.", request));
        }
        if (!userEmail.equalsIgnoreCase(result.getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(buildErrorBody(HttpStatus.FORBIDDEN,
                            "Only the payer can record a decision for this transaction.", request));
        }
        return null;
    }

    // ── User History (debug) ──────────────────────────────────────────────────

    /**
     * GET /api/fraud/history/{userId}
     * Returns cached transaction history for the user. For debugging and audit.
     */
    @GetMapping("/history/{userId}")
    public ResponseEntity<?> getUserHistory(
            @PathVariable @NotBlank(message = "userId must not be blank")
            String userId,
            HttpServletRequest request) {

        log.info("[FRAUD-CTRL] GET /history/{}", userId);
        UserTransactionHistory history = redisService.getHistory(userId);

        if (history == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(buildErrorBody(HttpStatus.NOT_FOUND,
                            "No history for userId=" + userId
                                    + ". History expires after 24h or user has no transactions.",
                            request));
        }

        log.info("[FRAUD-CTRL] History — userId={} txCount={} locations={}",
                userId,
                history.getRecentTransactions() != null
                        ? history.getRecentTransactions().size() : 0,
                history.getKnownLocations());
        return ResponseEntity.ok(history);
    }

    // ── Direct Analysis (test only) ───────────────────────────────────────────

    /**
     * POST /api/fraud/analyze
     * Synchronous analysis bypassing Kafka. FOR TESTING ONLY — do not expose in production.
     */
    @PostMapping("/analyze")
    public ResponseEntity<FraudResult> analyzeDirectly(
            @Valid @RequestBody TransactionEvent event) {

        log.info("[FRAUD-CTRL] POST /analyze — txId={} userId={}",
                event.getTransactionId(), event.getUserId());

        if (event.getTimestamp() == null) {
            event.setTimestamp(LocalDateTime.now());
        }

        FraudResult result = fraudDetectionService.analyzeTransaction(event);
        redisService.saveFraudResult(result);

        log.info("[FRAUD-CTRL] Direct analysis — txId={} status={} confidence={}",
                result.getTransactionId(), result.getStatus(), result.getConfidenceScore());
        return ResponseEntity.ok(result);
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private Map<String, Object> buildPendingResponse(String transactionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionId",    transactionId);
        body.put("status",           "PENDING");
        body.put("message",          "Transaction is queued for processing. "
                + "Retry GET /api/fraud/result/" + transactionId + " in 3 seconds.");
        body.put("retryAfterSeconds", 3);
        body.put("httpStatus",       202);
        body.put("timestamp",        LocalDateTime.now().toString());
        return body;
    }

    /**
     * Standard error response body with traceId for log correlation.
     * Matches schema used by all other services.
     */
    private Map<String, Object> buildErrorBody(HttpStatus status, String message,
                                                HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId",    UUID.randomUUID().toString());
        body.put("timestamp",  LocalDateTime.now().toString());
        body.put("httpStatus", status.value());
        body.put("error",      status.getReasonPhrase());
        body.put("message",    message);
        body.put("path",       request.getRequestURI());
        return body;
    }
}
