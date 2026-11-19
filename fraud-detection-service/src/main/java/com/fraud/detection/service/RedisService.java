package com.fraud.detection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fraud.common.dto.FraudResult;
import com.fraud.common.dto.TransactionEvent;
import com.fraud.detection.config.FraudRulesProperties;
import com.fraud.detection.dto.PendingReviewTransactionResponse;
import com.fraud.detection.model.PaymentRecord;
import com.fraud.detection.model.PaymentStatus;
import com.fraud.detection.model.ReviewDecision;
import com.fraud.detection.model.UserTransactionHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * RedisService — Unified Redis Cache Operations
 *
 * Owns ALL Redis interactions for the fraud detection system.
 * Three distinct key namespaces:
 *
 *  ┌──────────────────────────────────────────────────────────────┐
 *  │  Namespace 1: User Transaction History                       │
 *  │  Key schema : "user:{userId}:history"                        │
 *  │  Value type : UserTransactionHistory (JSON)                  │
 *  │  TTL        : 24h (fixed sliding window)                     │
 *  │  Purpose    : Feed rule engine and history analyser          │
 *  ├──────────────────────────────────────────────────────────────┤
 *  │  Namespace 2: Fraud Analysis Results                         │
 *  │  Key schema : "fraud:result:{transactionId}"                 │
 *  │  Value type : FraudResult (JSON)                             │
 *  │  TTL        : fraud.rules.result-ttl-hours (default 72h)     │
 *  │  Purpose    : Serve GET /api/fraud/result/{id}               │
 *  ├──────────────────────────────────────────────────────────────┤
 *  │  Namespace 3: Payment Records (HARDENING v2)                 │
 *  │  Key schema : "payment:record:{transactionId}"               │
 *  │  Value type : PaymentRecord (JSON)                           │
 *  │  TTL        : fraud.rules.result-ttl-hours (default 72h)     │
 *  │  Purpose    : Idempotency gate, compensation tracking,       │
 *  │               serve GET /api/fraud/payment/{id}              │
 *  └──────────────────────────────────────────────────────────────┘
 *
 * All read failures are fail-open (return null/false — never block pipeline).
 * All write failures are non-fatal (logged, never re-thrown to caller).
 */
@Service
public class RedisService {

    private static final Logger log = LoggerFactory.getLogger(RedisService.class);

    // ─── Key Schema Constants ──────────────────────────────────────────────
    private static final String HISTORY_KEY_PREFIX  = "user:";
    private static final String HISTORY_KEY_SUFFIX  = ":history";
    private static final String RESULT_KEY_PREFIX   = "fraud:result:";
    private static final String REVIEW_KEY_PREFIX   = "fraud:review:";
    private static final String REVIEW_EVENT_PREFIX = "fraud:review:event:";
    private static final String REVIEW_LOCK_PREFIX  = "fraud:review:approval-lock:";
    private static final String PAYMENT_KEY_PREFIX  = "payment:record:";
    private static final String TIMELINE_KEY_PREFIX = "tx:timeline:";
    private static final String PAYEE_STATS_PREFIX  = "payee:stats:";

    private static final long HISTORY_TTL_HOURS = 24;

    // ─── Dependencies ──────────────────────────────────────────────────────

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private FraudRulesProperties fraudRules;

    private final ObjectMapper objectMapper;

    public RedisService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  NAMESPACE 1 — User Transaction History
    // ══════════════════════════════════════════════════════════════════════

    private String buildHistoryKey(String userId) {
        return HISTORY_KEY_PREFIX + userId + HISTORY_KEY_SUFFIX;
    }

    /**
     * Retrieve cached transaction history. Returns null on miss or Redis error (fail-open).
     */
    public UserTransactionHistory getHistory(String userId) {
        String key = buildHistoryKey(userId);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached == null) {
                log.debug("[REDIS][HISTORY] Cache miss — user: {}", userId);
                return null;
            }
            UserTransactionHistory history =
                    objectMapper.convertValue(cached, UserTransactionHistory.class);
            log.debug("[REDIS][HISTORY] Cache hit — user: {} | {} transactions",
                    userId,
                    history.getRecentTransactions() != null
                            ? history.getRecentTransactions().size() : 0);
            return history;
        } catch (Exception e) {
            log.error("[REDIS][HISTORY] Read failed for user: {} — {}", userId, e.getMessage(), e);
            return null; // fail-open
        }
    }

    /**
     * Persist user transaction history with sliding 24h TTL. Non-fatal on write failure.
     */
    public void saveHistory(UserTransactionHistory history) {
        String key = buildHistoryKey(history.getUserId());
        try {
            redisTemplate.opsForValue().set(key, history, HISTORY_TTL_HOURS, TimeUnit.HOURS);
            log.debug("[REDIS][HISTORY] Saved — user: {} | {} transactions | TTL: {}h",
                    history.getUserId(),
                    history.getRecentTransactions() != null
                            ? history.getRecentTransactions().size() : 0,
                    HISTORY_TTL_HOURS);
        } catch (Exception e) {
            log.error("[REDIS][HISTORY] Write failed for user: {} — {}", history.getUserId(), e.getMessage(), e);
        }
    }

    /**
     * Fetch → append → save in one call. Creates new history if absent.
     */
    public UserTransactionHistory recordTransaction(TransactionEvent event) {
        UserTransactionHistory history = getHistory(event.getUserId());
        if (history == null) {
            log.info("[REDIS][HISTORY] First transaction for user: {} — initialising history",
                    event.getUserId());
            history = UserTransactionHistory.builder()
                    .userId(event.getUserId())
                    .build();
        }
        history.addTransaction(event);
        saveHistory(history);
        log.info("[REDIS][HISTORY] Recorded txId={} for user={} | Total in window: {}",
                event.getTransactionId(), event.getUserId(),
                history.getRecentTransactions().size());
        return history;
    }

    public int getTransactionCount(String userId) {
        UserTransactionHistory history = getHistory(userId);
        if (history == null || history.getRecentTransactions() == null) return 0;
        return history.getRecentTransactions().size();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  NAMESPACE 2 — Fraud Analysis Results
    // ══════════════════════════════════════════════════════════════════════

    private String buildResultKey(String transactionId) {
        return RESULT_KEY_PREFIX + transactionId;
    }

    /**
     * Persist FraudResult. TTL from fraud.rules.result-ttl-hours. Non-fatal on failure.
     */
    public void saveFraudResult(FraudResult result) {
        String key = buildResultKey(result.getTransactionId());
        long ttlHours = fraudRules.getResultTtlHours();
        try {
            redisTemplate.opsForValue().set(key, result, ttlHours, TimeUnit.HOURS);
            log.info("[REDIS][RESULT] Saved — txId={} status={} TTL={}h",
                    result.getTransactionId(), result.getStatus(), ttlHours);
        } catch (Exception e) {
            log.error("[REDIS][RESULT] Write failed for txId={} — {}", result.getTransactionId(), e.getMessage(), e);
        }
    }

    /**
     * Retrieve FraudResult. Returns null on miss or Redis error (fail-open).
     */
    public FraudResult getFraudResult(String transactionId) {
        String key = buildResultKey(transactionId);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached == null) {
                log.info("[REDIS][RESULT] Not found for txId={}", transactionId);
                return null;
            }
            FraudResult result = objectMapper.convertValue(cached, FraudResult.class);
            log.info("[REDIS][RESULT] Retrieved — txId={} status={} layer={}",
                    transactionId, result.getStatus(), result.getDetectionLayer());
            return result;
        } catch (Exception e) {
            log.error("[REDIS][RESULT] Read failed for txId={} — {}", transactionId, e.getMessage(), e);
            return null;
        }
    }

    /** Delete FraudResult (used for REVIEW re-processing). Returns true if key existed. */
    public boolean deleteFraudResult(String transactionId) {
        String key = buildResultKey(transactionId);
        try {
            Boolean deleted = redisTemplate.delete(key);
            boolean wasDeleted = Boolean.TRUE.equals(deleted);
            if (wasDeleted) log.info("[REDIS][RESULT] Deleted txId={}", transactionId);
            else           log.warn("[REDIS][RESULT] Delete: key not found for txId={}", transactionId);
            return wasDeleted;
        } catch (Exception e) {
            log.error("[REDIS][RESULT] Delete failed for txId={} — {}", transactionId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Idempotency check — does a FraudResult already exist for this transaction?
     * Fail-open (returns false on Redis error so the pipeline re-runs rather than blocks).
     */
    public boolean fraudResultExists(String transactionId) {
        String key = buildResultKey(transactionId);
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("[REDIS][RESULT] hasKey failed for txId={} — assuming absent", transactionId);
            return false;
        }
    }

    public ReviewDecision getReviewDecision(String transactionId) {
        String key = REVIEW_KEY_PREFIX + transactionId;
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            return cached == null ? null : objectMapper.convertValue(cached, ReviewDecision.class);
        } catch (Exception e) {
            log.error("[REDIS][REVIEW] Read failed for txId={} — {}", transactionId, e.getMessage(), e);
            throw new IllegalStateException("Unable to read review decision", e);
        }
    }

    public ReviewDecision saveReviewDecision(ReviewDecision decision) {
        String key = REVIEW_KEY_PREFIX + decision.getTransactionId();
        long ttlHours = fraudRules.getResultTtlHours();
        try {
            Boolean created = redisTemplate.opsForValue().setIfAbsent(
                    key, decision, ttlHours, TimeUnit.HOURS);
            if (Boolean.TRUE.equals(created)) {
                return decision;
            }
            ReviewDecision existing = getReviewDecision(decision.getTransactionId());
            if (existing != null
                    && existing.getDecision() == ReviewDecision.Decision.CONFIRM
                    && decision.getDecision() == ReviewDecision.Decision.APPROVE) {
                redisTemplate.opsForValue().set(key, decision, ttlHours, TimeUnit.HOURS);
                return decision;
            }
            return existing;
        } catch (Exception e) {
            log.error("[REDIS][REVIEW] Write failed for txId={} — {}",
                    decision.getTransactionId(), e.getMessage(), e);
            throw new IllegalStateException("Unable to save review decision", e);
        }
    }

    public void saveReviewTransaction(TransactionEvent event) {
        String key = REVIEW_EVENT_PREFIX + event.getTransactionId();
        redisTemplate.opsForValue().set(
                key, event, fraudRules.getResultTtlHours(), TimeUnit.HOURS);
    }

    public TransactionEvent getReviewTransaction(String transactionId) {
        Object cached = redisTemplate.opsForValue().get(REVIEW_EVENT_PREFIX + transactionId);
        return cached == null ? null : objectMapper.convertValue(cached, TransactionEvent.class);
    }

    public List<PendingReviewTransactionResponse> getPendingReviewTransactions(String userId) {
        UserTransactionHistory history = getHistory(userId);
        if (history == null || history.getRecentTransactions() == null) {
            return Collections.emptyList();
        }

        List<PendingReviewTransactionResponse> pending = new ArrayList<>();
        for (TransactionEvent event : history.getRecentTransactions()) {
            if (event == null || event.getTransactionId() == null
                    || event.getPayeeUpiId() == null || event.getPayeeUpiId().isBlank()) {
                continue;
            }
            String payerId = event.getPayerUserId() != null
                    ? event.getPayerUserId() : event.getUserId();
            if (payerId == null || !userId.equalsIgnoreCase(payerId)) {
                continue;
            }

            FraudResult result = getFraudResult(event.getTransactionId());
            if (result == null || result.getStatus() != FraudResult.FraudStatus.REVIEW
                    || getReviewDecision(event.getTransactionId()) != null) {
                continue;
            }

            pending.add(PendingReviewTransactionResponse.builder()
                    .transactionId(event.getTransactionId())
                    .amount(event.getAmount())
                    .payeeUpiId(event.getPayeeUpiId())
                    .paymentMode(event.getPaymentMode())
                    .location(event.getLocation())
                    .device(event.getDevice())
                    .merchantCategory(event.getMerchantCategory())
                    .confidenceScore(result.getConfidenceScore())
                    .detectionLayer(result.getDetectionLayer())
                    .reason(result.getReason())
                    .submittedAt(event.getTimestamp())
                    .build());
        }
        pending.sort(Comparator.comparing(
                PendingReviewTransactionResponse::getSubmittedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return pending;
    }

    public boolean acquireReviewApprovalLock(String transactionId) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                REVIEW_LOCK_PREFIX + transactionId, "PROCESSING", 5, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(acquired);
    }

    public void releaseReviewApprovalLock(String transactionId) {
        redisTemplate.delete(REVIEW_LOCK_PREFIX + transactionId);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  NAMESPACE 3 — Payment Records (Hardening v2)
    // ══════════════════════════════════════════════════════════════════════

    private String buildPaymentKey(String transactionId) {
        return PAYMENT_KEY_PREFIX + transactionId;
    }

    /**
     * Persist a PaymentRecord. Used after every state transition in the payment lifecycle.
     * TTL matches fraud result TTL (default 72h). Non-fatal on write failure.
     *
     * @param record the current payment state to store
     */
    public void savePaymentRecord(PaymentRecord record) {
        String key = buildPaymentKey(record.getTransactionId());
        long ttlHours = fraudRules.getResultTtlHours();
        try {
            redisTemplate.opsForValue().set(key, record, ttlHours, TimeUnit.HOURS);
            log.info("[REDIS][PAYMENT] Saved — txId={} status={} TTL={}h",
                    record.getTransactionId(), record.getPaymentStatus(), ttlHours);
        } catch (Exception e) {
            log.error("[REDIS][PAYMENT] Write failed for txId={} — {}",
                    record.getTransactionId(), e.getMessage(), e);
        }
    }

    /**
     * Retrieve a PaymentRecord. Returns null on miss or Redis error (fail-open).
     * Used by:
     *   - PaymentProcessorService (idempotency gate)
     *   - FraudController GET /api/fraud/payment/{id}
     *   - transaction-service GET /api/transactions/{id}
     */
    public PaymentRecord getPaymentRecord(String transactionId) {
        String key = buildPaymentKey(transactionId);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached == null) {
                log.debug("[REDIS][PAYMENT] Not found — txId={}", transactionId);
                return null;
            }
            PaymentRecord record = objectMapper.convertValue(cached, PaymentRecord.class);
            log.info("[REDIS][PAYMENT] Retrieved — txId={} status={}",
                    transactionId, record.getPaymentStatus());
            return record;
        } catch (Exception e) {
            log.error("[REDIS][PAYMENT] Read failed for txId={} — {}", transactionId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Check whether ANY payment record exists for this transaction.
     * Used as the first idempotency gate in PaymentProcessorService.
     * Fail-open: returns false on Redis error so payment proceeds (idempotency via status check).
     */
    public boolean paymentRecordExists(String transactionId) {
        String key = buildPaymentKey(transactionId);
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("[REDIS][PAYMENT] hasKey failed for txId={} — assuming absent", transactionId);
            return false;
        }
    }

    /**
     * Convenience: check whether payment is in a terminal state (no further processing needed).
     * Terminal states: SUCCESS, FAILED, COMPENSATED, COMPENSATION_FAILED, RAZORPAY_ORDER_CREATED, SKIPPED.
     * Non-terminal: PENDING, COMPENSATING.
     *
     * @return true if terminal and should be skipped; false if absent or non-terminal
     */
    public boolean isPaymentTerminal(String transactionId) {
        PaymentRecord record = getPaymentRecord(transactionId);
        if (record == null) return false;
        return switch (record.getPaymentStatus()) {
            case SUCCESS, FAILED, COMPENSATED, COMPENSATION_FAILED,
                 RAZORPAY_ORDER_CREATED, SKIPPED -> true;
            case PENDING, COMPENSATING            -> false;
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    //  NAMESPACE 4 — Transaction Timeline
    //
    //  Key schema : "tx:timeline:{transactionId}"
    //  Value type : List<String> — ordered step entries, e.g.
    //               "INITIATED=2024-01-15T10:30:00.123"
    //  TTL        : matches fraud result TTL (default 72h)
    //
    //  Steps (in order):
    //    INITIATED       — event received by Kafka consumer
    //    FRAUD_VERDICT   — fraud pipeline completed and result saved
    //    PAYMENT_STARTED — PaymentProcessorService began processing
    //    DEBIT_DONE      — payer's account debited successfully
    //    CREDIT_DONE     — payee's account credited successfully
    //    COMPLETED       — terminal payment state reached
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Append a step to the transaction timeline.
     *
     * Stores: "STEP_NAME=ISO_TIMESTAMP" as a new list element.
     * The list preserves insertion order — first in, first out (chronological).
     * Non-fatal: Redis unavailability is logged and silently ignored.
     *
     * @param txId  the transaction ID
     * @param step  the pipeline stage name (e.g. "INITIATED", "DEBIT_DONE")
     */
    public void appendTimeline(String txId, String step) {
        String key = TIMELINE_KEY_PREFIX + txId;
        try {
            String entry = step + "=" + LocalDateTime.now();
            redisTemplate.opsForList().rightPush(key, entry);
            // Refresh TTL on every append — the key stays alive for the full window
            redisTemplate.expire(key, fraudRules.getResultTtlHours(), TimeUnit.HOURS);
            log.debug("[REDIS][TIMELINE] txId={} step={}", txId, step);
        } catch (Exception e) {
            // Non-fatal — timeline is observability only; never block the payment pipeline
            log.warn("[REDIS][TIMELINE] Append failed txId={} step={} — {}",
                    txId, step, e.getMessage());
        }
    }

    /**
     * Retrieve the full ordered timeline for a transaction.
     * Returns an empty list on miss or Redis error (fail-open).
     *
     * @param txId the transaction ID
     * @return ordered list of "STEP=TIMESTAMP" strings, empty if not found
     */
    @SuppressWarnings("unchecked")
    public List<String> getTimeline(String txId) {
        String key = TIMELINE_KEY_PREFIX + txId;
        try {
            List<Object> raw = redisTemplate.opsForList().range(key, 0, -1);
            if (raw == null || raw.isEmpty()) return Collections.emptyList();
            return raw.stream().map(Object::toString).toList();
        } catch (Exception e) {
            log.warn("[REDIS][TIMELINE] Read failed txId={} — {}", txId, e.getMessage());
            return Collections.emptyList();
        }
    }
    // ─────────────────────────────────────────────
// Razorpay Order ↔ Transaction Mapping
// ─────────────────────────────────────────────

    public void saveOrderMapping(String orderId, String txId) {
        redisTemplate.opsForValue().set(
                "razorpay:order:" + orderId,
                txId,
                72,
                TimeUnit.HOURS
        );
    }

    public String getTxIdByOrderId(String orderId) {
        Object value = redisTemplate.opsForValue().get("razorpay:order:" + orderId);

        if (value == null) return null;

        return value.toString(); // ✅ SAFE
    }
    // ─────────────────────────────────────────────
// Razorpay: Mark Payment SUCCESS (Webhook)
// ─────────────────────────────────────────────
    public void markPaymentSuccess(String txId) {

        PaymentRecord record = getPaymentRecord(txId);

        if (record == null) {
            log.error("[RAZORPAY] PaymentRecord not found for txId={}", txId);
            return;
        }

        // 🔒 Idempotency (very important)
        if (record.getPaymentStatus() == PaymentStatus.SUCCESS) {
            log.warn("[RAZORPAY] Already SUCCESS txId={}", txId);
            return;
        }

        record.setPaymentStatus(PaymentStatus.SUCCESS);
        record.setPaymentMode("RAZORPAY");
        record.setCompletedAt(LocalDateTime.now());
        log.info("[TX-TRACE] transactionId={} stage=RAZORPAY_SUCCESS", txId);
        savePaymentRecord(record);
        log.info("[RAZORPAY] Payment marked SUCCESS txId={}", txId);
    }

    public void markPaymentFailed(String txId) {

        PaymentRecord record = getPaymentRecord(txId);

        if (record == null) {
            log.error("[RAZORPAY] PaymentRecord not found for txId={}", txId);
            return;
        }

        // Idempotency
        if (record.getPaymentStatus() == PaymentStatus.FAILED) {
            log.warn("[RAZORPAY] Already FAILED txId={}", txId);
            return;
        }

        record.setPaymentStatus(PaymentStatus.FAILED);
        record.setCompletedAt(LocalDateTime.now());
        record.setFailureReason("Razorpay payment failed");

        savePaymentRecord(record);

        log.warn("[RAZORPAY] Payment marked FAILED txId={}", txId);
    }
    public void savePaymentId(String txId, String paymentId) {
        redisTemplate.opsForValue().set("razorpay:payment:" + txId, paymentId);
    }
    public String getPaymentId(String txId) {
        Object val = redisTemplate.opsForValue().get("razorpay:payment:" + txId);
        return val != null ? val.toString() : null;
    }
    public void markCompensating(String txId, String reason) {

        PaymentRecord record = getPaymentRecord(txId);

        record.setPaymentStatus(PaymentStatus.COMPENSATING);
        record.setCompensationReason(reason);

        savePaymentRecord(record);
    }
    public void markCompensated(String txId) {

        PaymentRecord record = getPaymentRecord(txId);

        record.setPaymentStatus(PaymentStatus.COMPENSATED);

        savePaymentRecord(record);
    }
    public void markCompensationFailed(String txId, String reason) {

        PaymentRecord record = getPaymentRecord(txId);

        record.setPaymentStatus(PaymentStatus.COMPENSATION_FAILED);
        record.setCompensationReason(reason);

        savePaymentRecord(record);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  NAMESPACE 4 — Admin Controls (Blocklist, Rules, Audit, Stats)
    // ══════════════════════════════════════════════════════════════════════

    private static final String BLOCKLIST_KEY     = "admin:blocklist";
    private static final String DYNAMIC_RULES_KEY = "admin:fraud:rules";
    private static final String AUDIT_LOGS_KEY    = "admin:audit:logs";

    public void addToBlocklist(String item, String type, String reason) {
        try {
            Map<String, String> data = new LinkedHashMap<>();
            data.put("item", item);
            data.put("type", type);
            data.put("reason", reason);
            data.put("addedAt", LocalDateTime.now().toString());
            redisTemplate.opsForHash().put(BLOCKLIST_KEY, item, objectMapper.writeValueAsString(data));
            recordAdminAudit("BLOCKLIST_ADD", item, "Added to blocklist: " + reason);
        } catch (Exception e) {
            log.error("[REDIS][ADMIN] Failed to add {} to blocklist", item, e);
        }
    }

    public void removeFromBlocklist(String item) {
        try {
            redisTemplate.opsForHash().delete(BLOCKLIST_KEY, item);
            recordAdminAudit("BLOCKLIST_REMOVE", item, "Removed from blocklist");
        } catch (Exception e) {
            log.error("[REDIS][ADMIN] Failed to remove {} from blocklist", item, e);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> getBlocklist() {
        List<Map<String, String>> list = new ArrayList<>();
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(BLOCKLIST_KEY);
            for (Object val : entries.values()) {
                if (val != null) {
                    list.add(objectMapper.readValue(val.toString(), Map.class));
                }
            }
        } catch (Exception e) {
            log.warn("[REDIS][ADMIN] Failed to read blocklist: {}", e.getMessage());
        }
        return list;
    }

    public boolean isBlocklisted(String item) {
        if (item == null || item.isBlank()) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(BLOCKLIST_KEY, item));
        } catch (Exception e) {
            log.warn("[REDIS][ADMIN] Blocklist check error for {}: {}", item, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getDynamicRules() {
        Map<String, Object> rules = new LinkedHashMap<>();
        try {
            Object raw = redisTemplate.opsForValue().get(DYNAMIC_RULES_KEY);
            if (raw != null) {
                rules = objectMapper.readValue(raw.toString(), Map.class);
            }
        } catch (Exception e) {
            log.warn("[REDIS][ADMIN] Could not read dynamic rules, returning defaults: {}", e.getMessage());
        }

        if (rules.isEmpty()) {
            rules.put("highAmountThreshold", fraudRules.getHighAmountThreshold());
            rules.put("highRiskScoreCutoff", 0.70);
            rules.put("reviewScoreCutoff", 0.40);
            rules.put("maxTxPerMinute", 5);
            rules.put("ruleEngineEnabled", true);
            rules.put("mlModelEnabled", true);
            rules.put("geminiAiEnabled", true);
        }
        return rules;
    }

    public void updateDynamicRules(Map<String, Object> rules) {
        try {
            String json = objectMapper.writeValueAsString(rules);
            redisTemplate.opsForValue().set(DYNAMIC_RULES_KEY, json);
            recordAdminAudit("RULES_UPDATE", "FraudRules", "Updated fraud detection thresholds");
        } catch (Exception e) {
            log.error("[REDIS][ADMIN] Failed to update dynamic rules", e);
        }
    }

    public void recordAdminAudit(String action, String target, String details) {
        try {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("id", UUID.randomUUID().toString());
            entry.put("action", action);
            entry.put("target", target);
            entry.put("details", details);
            entry.put("timestamp", LocalDateTime.now().toString());
            redisTemplate.opsForList().leftPush(AUDIT_LOGS_KEY, objectMapper.writeValueAsString(entry));
            redisTemplate.opsForList().trim(AUDIT_LOGS_KEY, 0, 99);
        } catch (Exception e) {
            log.warn("[REDIS][ADMIN] Failed to record admin audit: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> getAdminAuditLogs() {
        List<Map<String, String>> logs = new ArrayList<>();
        try {
            List<Object> raw = redisTemplate.opsForList().range(AUDIT_LOGS_KEY, 0, 49);
            if (raw != null) {
                for (Object item : raw) {
                    logs.add(objectMapper.readValue(item.toString(), Map.class));
                }
            }
        } catch (Exception e) {
            log.warn("[REDIS][ADMIN] Failed to read audit logs: {}", e.getMessage());
        }
        return logs;
    }

    public List<FraudResult> getAllRecentFraudResults() {
        List<FraudResult> results = new ArrayList<>();
        try {
            Set<String> keys = redisTemplate.keys(RESULT_KEY_PREFIX + "*");
            if (keys != null) {
                for (String key : keys) {
                    if (results.size() >= 100) break;
                    try {
                        Object val = redisTemplate.opsForValue().get(key);
                        if (val != null) {
                            results.add(objectMapper.convertValue(val, FraudResult.class));
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("[REDIS][ADMIN] Error querying recent fraud results: {}", e.getMessage());
        }
        return results;
    }

    public List<PendingReviewTransactionResponse> getAllPendingReviewTransactions() {
        List<PendingReviewTransactionResponse> allPending = new ArrayList<>();
        try {
            Set<String> keys = redisTemplate.keys(REVIEW_EVENT_PREFIX + "*");
            if (keys != null) {
                for (String key : keys) {
                    try {
                        String txId = key.substring(REVIEW_EVENT_PREFIX.length());
                        TransactionEvent event = getReviewTransaction(txId);
                        if (event == null) continue;
                        FraudResult result = getFraudResult(txId);
                        ReviewDecision decision = getReviewDecision(txId);
                        if (decision != null) continue;

                        allPending.add(PendingReviewTransactionResponse.builder()
                                .transactionId(txId)
                                .amount(event.getAmount())
                                .payeeUpiId(event.getPayeeUpiId())
                                .paymentMode(event.getPaymentMode())
                                .location(event.getLocation())
                                .device(event.getDevice())
                                .merchantCategory(event.getMerchantCategory())
                                .confidenceScore(result != null ? result.getConfidenceScore() : 0.5)
                                .detectionLayer(result != null ? result.getDetectionLayer() : "LAYER_1_RULE_ENGINE")
                                .reason(result != null ? result.getReason() : "Awaiting manual review")
                                .submittedAt(event.getTimestamp())
                                .build());
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("[REDIS][ADMIN] Error querying all pending reviews: {}", e.getMessage());
        }
        allPending.sort(Comparator.comparing(
                PendingReviewTransactionResponse::getSubmittedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return allPending;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  NAMESPACE 5 — Payee Inflow Tracking (Cold-Start & Mule Detection)
    // ══════════════════════════════════════════════════════════════════════

    public com.fraud.detection.model.PayeeStats getPayeeStats(String payeeUpiId) {
        if (payeeUpiId == null || payeeUpiId.isBlank()) return null;
        String key = PAYEE_STATS_PREFIX + payeeUpiId.toLowerCase().trim();
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached == null) return null;
            return objectMapper.convertValue(cached, com.fraud.detection.model.PayeeStats.class);
        } catch (Exception e) {
            log.warn("[REDIS][PAYEE] Failed to get stats for {}: {}", payeeUpiId, e.getMessage());
            return null;
        }
    }

    public com.fraud.detection.model.PayeeStats recordPayeeActivity(String payeeUpiId, java.math.BigDecimal amount) {
        if (payeeUpiId == null || payeeUpiId.isBlank()) return null;
        String key = PAYEE_STATS_PREFIX + payeeUpiId.toLowerCase().trim();
        long now = System.currentTimeMillis();
        long oneHourAgo = now - 3600_000L;

        com.fraud.detection.model.PayeeStats stats = getPayeeStats(payeeUpiId);
        if (stats == null) {
            stats = com.fraud.detection.model.PayeeStats.builder()
                    .payeeUpiId(payeeUpiId.toLowerCase().trim())
                    .totalReceivedCount(1)
                    .totalReceivedAmount(amount != null ? amount : java.math.BigDecimal.ZERO)
                    .firstSeenTimestamp(now)
                    .lastSeenTimestamp(now)
                    .recentTimestamps(new ArrayList<>(List.of(now)))
                    .build();
        } else {
            stats.setTotalReceivedCount(stats.getTotalReceivedCount() + 1);
            if (amount != null) {
                stats.setTotalReceivedAmount(
                        (stats.getTotalReceivedAmount() != null ? stats.getTotalReceivedAmount() : java.math.BigDecimal.ZERO).add(amount));
            }
            stats.setLastSeenTimestamp(now);
            if (stats.getRecentTimestamps() == null) {
                stats.setRecentTimestamps(new ArrayList<>());
            }
            stats.getRecentTimestamps().add(now);
            // Prune timestamps older than 1 hour
            stats.getRecentTimestamps().removeIf(ts -> ts < oneHourAgo);
        }

        try {
            redisTemplate.opsForValue().set(key, stats, 7, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("[REDIS][PAYEE] Failed to record stats for {}: {}", payeeUpiId, e.getMessage());
        }
        return stats;
    }
}
