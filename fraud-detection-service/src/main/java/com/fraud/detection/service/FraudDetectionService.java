package com.fraud.detection.service;

import com.fraud.common.dto.FraudResult;
import com.fraud.common.dto.FraudResult.FraudStatus;
import com.fraud.common.dto.TransactionEvent;
import com.fraud.detection.config.FraudRulesProperties;
import com.fraud.detection.model.UserTransactionHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * FraudDetectionService — Core Fraud Analysis Orchestrator
 *
 * Implements a 3-layer short-circuit pipeline:
 *
 *  ╔══════════════════════════════════════════════════════╗
 *  ║  LAYER 1 — Rule Engine (deterministic, <1ms)        ║
 *  ║  ├─ Amount threshold   → REVIEW if above limit      ║
 *  ║  ├─ Unknown location   → FRAUD if new for user      ║
 *  ║  └─ Transaction count  → FRAUD if velocity exceeded ║
 *  ╠══════════════════════════════════════════════════════╣
 *  ║  LAYER 2 — Redis History Analyser (contextual)      ║
 *  ║  ├─ Impossible travel  → FRAUD (2 cities < Nmin)   ║
 *  ║  └─ Device change      → REVIEW (soft flag to AI)  ║
 *  ╠══════════════════════════════════════════════════════╣
 *  ║  LAYER 3 — Google Gemini                           ║
 *  ║  ├─ confidence > 0.6   → FRAUD                     ║
 *  ║  ├─ confidence 0.4–0.6 → REVIEW (human review)     ║
 *  ║  └─ confidence < 0.4   → SAFE                      ║
 *  ╚══════════════════════════════════════════════════════╝
 *
 * Short-circuit: each layer returns immediately on FRAUD detection;
 * REVIEW from Layer 2 escalates to AI for a final verdict.
 *
 * CHANGE LOG:
 *  v1.1 — Removed ALL hardcoded thresholds; injected FraudRulesProperties
 *        — Device change now produces REVIEW (not silently ignored)
 *        — AI confidence band drives REVIEW vs SAFE vs FRAUD classification
 *        — Added reviewNotes population when status = REVIEW
 *        — Improved structured logging at every decision point
 */
@Service
public class FraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);

    // ─── Injected Dependencies ─────────────────────────────────────────────

    /**
     * Externalised fraud rule thresholds — all values come from application.yml.
     * No magic numbers exist in this class.
     *
     * Example values:
     *   fraudRules.getHighAmountThreshold()   = 10000 INR
     *   fraudRules.getMaxTransactionsPerDay() = 10
     *   fraudRules.getImpossibleTravelMinutes() = 5
     */
    @Autowired
    private FraudRulesProperties fraudRules;

    /** Redis service — user history reads + result persistence */
    @Autowired
    private RedisService redisService;

    /** AI service — Google Gemini integration */
    @Autowired
    private AiFraudAnalysisService aiFraudAnalysisService;

    /** UPI Phishing & Brand Typosquatting Detector (Technique 1) */
    @Autowired
    private UpiPhishingDetector upiPhishingDetector;

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Runs the full 3-layer fraud detection pipeline for a single transaction.
     *
     * Called by:
     *   - TransactionConsumer (Kafka listener, async path)
     *   - FraudController.analyzeDirectly() (synchronous test path)
     *
     * @param event  The transaction received from Kafka (or direct API call)
     * @return       FraudResult with status SAFE | FRAUD | REVIEW
     */
    public FraudResult analyzeTransaction(TransactionEvent event) {
        long startMs = System.currentTimeMillis();

        log.info("[FRAUD ENGINE] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("[FRAUD ENGINE] START — Transaction: {} | User: {} | Amount: {} | Location: {} | Device: {}",
                event.getTransactionId(),
                event.getUserId(),
                event.getAmount(),
                event.getLocation(),
                event.getDevice());

        // ─── ADMIN BLOCKLIST CHECK ───────────────────────────────────────
        if (redisService.isBlocklisted(event.getUserId())
                || redisService.isBlocklisted(event.getPayerUserId())
                || redisService.isBlocklisted(event.getPayeeUpiId())
                || redisService.isBlocklisted(event.getLocation())
                || redisService.isBlocklisted(event.getDevice())) {
            log.warn("[FRAUD ENGINE] BLOCKLIST HIT — Transaction: {} rejected by admin policy",
                    event.getTransactionId());
            return buildResult(event,
                    FraudStatus.FRAUD,
                    "Transaction blocked by Administrator Security Policy (Entity in Blocklist)",
                    1.0,
                    "ADMIN_BLOCKLIST",
                    "This transaction was immediately rejected because the user, payee UPI, device, or location is blacklisted by the system administrator.");
        }

        // Fetch existing user history ONCE before any layer runs.
        // Layer 1 (unknown-location rule) and Layer 2 (history analysis) both need it.
        // Previously, applyRules() called getHistory() internally and recordTransaction()
        // also called getHistory() — two round-trips to Redis for the same data.
        UserTransactionHistory existingHistory = redisService.getHistory(event.getUserId());

        // ─── LAYER 1: Rule-Based Checks ──────────────────────────────────
        // Fast, deterministic — runs before any additional Redis or AI call
        FraudResult ruleResult = applyRules(event, existingHistory);
        if (ruleResult != null) {
            // Record transaction in history even if FRAUD (for future velocity/travel analysis)
            redisService.recordTransaction(event);
            if (event.getPayeeUpiId() != null && !event.getPayeeUpiId().isBlank()) {
                redisService.recordPayeeActivity(event.getPayeeUpiId(), event.getAmount());
            }
            log.warn("[FRAUD ENGINE] RULE FRAUD → Transaction: {} flagged by rule engine | Elapsed: {}ms",
                    event.getTransactionId(), System.currentTimeMillis() - startMs);
            return ruleResult;
        }

        // ─── LAYER 2: Redis History Analysis ─────────────────────────────
        // Record transaction and fetch enriched history (now includes current transaction)
        UserTransactionHistory history = redisService.recordTransaction(event);
        if (event.getPayeeUpiId() != null && !event.getPayeeUpiId().isBlank()) {
            redisService.recordPayeeActivity(event.getPayeeUpiId(), event.getAmount());
        }
        FraudResult historyResult = analyzeHistory(event, history);

        if (historyResult != null && historyResult.getStatus() == FraudStatus.FRAUD) {
            // Hard FRAUD from history analysis — no need for AI
            log.warn("[FRAUD ENGINE] HISTORY FRAUD → Transaction: {} | Reason: {} | Elapsed: {}ms",
                    event.getTransactionId(), historyResult.getReason(),
                    System.currentTimeMillis() - startMs);
            return historyResult;
        }

        // REVIEW from history (e.g., device change) — escalate to AI with context
        boolean hasHistorySoftFlag = (historyResult != null
                && historyResult.getStatus() == FraudStatus.REVIEW);

        if (hasHistorySoftFlag) {
            log.info("[FRAUD ENGINE] HISTORY REVIEW → escalating {} to AI. Soft flag: {}",
                    event.getTransactionId(), historyResult.getReason());
        }

        // ─── LAYER 3: AI Analysis ─────────────────────────────────────────
        log.info("[FRAUD ENGINE] AI LAYER → sending transaction {} to Gemini",
                event.getTransactionId());
        FraudResult aiResult = aiFraudAnalysisService.analyze(event, existingHistory);

        // If history flagged a soft anomaly and AI says SAFE, bump to REVIEW
        // so the borderline case is not silently cleared.
        if (hasHistorySoftFlag && aiResult.getStatus() == FraudStatus.SAFE) {
            log.info("[FRAUD ENGINE] Upgrading AI SAFE to REVIEW — history soft flag was present");
            aiResult = aiResult.toBuilder()
                    .status(FraudStatus.REVIEW)
                    .reviewNotes(historyResult.getReason()
                            + " | AI returned SAFE but history flag overrides to REVIEW")
                    .build();
        }

        // When confirmed SAFE, whitelist location for future transactions
        if (history != null && aiResult.getStatus() == FraudStatus.SAFE && event.getLocation() != null
                && !event.getLocation().isBlank() && !"UNKNOWN".equalsIgnoreCase(event.getLocation().trim())) {
            history.addKnownLocation(event.getLocation());
            redisService.saveHistory(history);
        }

        log.info("[FRAUD ENGINE] FINAL → Transaction: {} | Status: {} | Confidence: {} | Layer: {} | Elapsed: {}ms",
                event.getTransactionId(),
                aiResult.getStatus(),
                aiResult.getConfidenceScore(),
                aiResult.getDetectionLayer(),
                System.currentTimeMillis() - startMs);
        log.info("[FRAUD ENGINE] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        return aiResult;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LAYER 1 — Rule-Based Detection
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Applies three deterministic fraud rules in order of severity.
     *
     * Rules are evaluated sequentially and short-circuit on first violation.
     * All threshold values are read from FraudRulesProperties — no hardcoding.
     *
     * @param event           The transaction to check
     * @param existingHistory Pre-fetched user history (may be null for new users).
     *                        Passed in to avoid a redundant Redis round-trip — the
     *                        caller already fetched this before invoking Layer 1.
     * @return                FraudResult(FRAUD) on first rule violation; null if all pass
     */
    private FraudResult applyRules(TransactionEvent event, UserTransactionHistory existingHistory) {

        // ── Rule 1: High Amount Threshold ──────────────────────────────
        BigDecimal effectiveThreshold = fraudRules.getHighAmountThreshold();
        Map<String, Object> dyn = redisService.getDynamicRules();
        if (dyn != null && dyn.get("highAmountThreshold") != null) {
            try {
                effectiveThreshold = new BigDecimal(dyn.get("highAmountThreshold").toString());
            } catch (Exception ignored) {}
        }

        if (event.getAmount().compareTo(effectiveThreshold) > 0) {
            log.warn("[RULE ENGINE] HIGH AMOUNT REVIEW — Transaction: {} | Amount: {} > Threshold: {}",
                    event.getTransactionId(),
                    event.getAmount(),
                    effectiveThreshold);

            return buildResult(event,
                    FraudStatus.REVIEW,
                    String.format("High-value payment of %.2f INR exceeds the %.2f INR review threshold; payer confirmation is required",
                            event.getAmount(), effectiveThreshold),
                    0.70,
                    "RULE_BASED",
                    "High-value payments are held rather than automatically rejected. Confirm only if you recognise the recipient and intended amount.");
        }

        // ── Rule 2: Unknown Location ───────────────────────────────────
        // First transaction from a new city after the user already has a location
        // baseline is a hard FRAUD signal. Missing / UNKNOWN location is ignored.
        if (event.getLocation() != null && !event.getLocation().isBlank()
                && existingHistory != null && existingHistory.isUnknownLocation(event.getLocation())) {
            log.warn("[RULE ENGINE] UNKNOWN LOCATION — User: {} | New: {} | Known: {}",
                    event.getUserId(),
                    event.getLocation(),
                    existingHistory.getKnownLocations());

            return buildResult(event,
                    FraudStatus.FRAUD,
                    String.format("Transaction from unknown location '%s'. Known locations: %s",
                            event.getLocation(),
                            existingHistory.getKnownLocations()),
                    0.9,
                    "RULE_BASED",
                    null);
        }

        // ── Rule 3: Transaction Velocity (frequency check) ─────────────
        // Configurable via: fraud.rules.max-transactions-per-day (default: 10).
        // More than 10 prior transactions in the 24h window → this attempt is FRAUD.
        int txCount = (existingHistory != null && existingHistory.getRecentTransactions() != null)
                ? existingHistory.getRecentTransactions().size()
                : 0;
        if (txCount >= fraudRules.getMaxTransactionsPerDay()) {
            log.warn("[RULE ENGINE] HIGH VELOCITY — User: {} | Count: {} >= Limit: {}",
                    event.getUserId(),
                    txCount,
                    fraudRules.getMaxTransactionsPerDay());

            return buildResult(event,
                    FraudStatus.FRAUD,
                    String.format("High velocity: %d transactions already in the 24h window "
                                    + "(limit: %d). This attempt exceeds the daily frequency rule.",
                            txCount,
                            fraudRules.getMaxTransactionsPerDay()),
                    0.85,
                    "RULE_BASED",
                    null);
        }

        // ── Rule 4: UPI Phishing & Brand Typosquatting (Technique 1) ───
        if (event.getPayeeUpiId() != null && !event.getPayeeUpiId().isBlank() && upiPhishingDetector != null) {
            UpiPhishingDetector.PhishingCheckResult phishingCheck =
                    upiPhishingDetector.checkPayeeUpi(event.getPayeeUpiId());
            if (phishingCheck.isFlagged()) {
                log.warn("[RULE ENGINE] PHISHING FRAUD — Transaction: {} | Payee: {} | Reason: {}",
                        event.getTransactionId(), event.getPayeeUpiId(), phishingCheck.getReason());

                return buildResult(event,
                        FraudStatus.FRAUD,
                        phishingCheck.getReason(),
                        phishingCheck.getConfidenceScore(),
                        "UPI_PHISHING_DETECTOR",
                        "Phishing & brand impersonation attempt detected via fuzzy string matching and heuristics.");
            }
        }

        // ── Rule 5: Payee Cold-Start Surge & Mule Account (Technique 3) ─
        if (event.getPayeeUpiId() != null && !event.getPayeeUpiId().isBlank() && redisService != null) {
            com.fraud.detection.model.PayeeStats payeeStats = redisService.getPayeeStats(event.getPayeeUpiId());
            if (payeeStats != null) {
                int recentHourlyCount = payeeStats.getRecentTimestamps() != null ? payeeStats.getRecentTimestamps().size() : 0;
                int lifetimeCount = payeeStats.getTotalReceivedCount();

                // Mule Surge: Cold-start payee receiving high velocity (> 3 payments in last hour)
                if (lifetimeCount <= 5 && recentHourlyCount >= 3) {
                    log.warn("[RULE ENGINE] MULE SURGE — Payee: {} | Lifetime: {} | RecentHour: {}",
                            event.getPayeeUpiId(), lifetimeCount, recentHourlyCount);
                    return buildResult(event,
                            FraudStatus.FRAUD,
                            String.format("Cold-start payee velocity surge detected for '%s' (%d rapid payments in 1 hour on a newly active account). Flagged as money mule activity.",
                                    event.getPayeeUpiId(), recentHourlyCount + 1),
                            0.90,
                            "MULE_SURGE_DETECTOR",
                            "Rapid incoming velocity surge on an account with little or no prior history indicates mule aggregation.");
                }

                // Cold-Start High Value Inflow: Payee with <= 1 prior transaction receiving > ₹5,000
                if (lifetimeCount <= 1 && event.getAmount() != null && event.getAmount().compareTo(new BigDecimal("5000.00")) > 0) {
                    log.warn("[RULE ENGINE] COLD-START INFLOW — Payee: {} | Amount: {}",
                            event.getPayeeUpiId(), event.getAmount());
                    return buildResult(event,
                            FraudStatus.REVIEW,
                            String.format("Payee '%s' is a new cold-start account receiving a large first-time payment of %.2f INR (> 5,000 INR threshold). Requires payer confirmation.",
                                    event.getPayeeUpiId(), event.getAmount()),
                            0.75,
                            "COLD_START_DETECTOR",
                            "Verify the recipient identity before confirming payment to a newly active account.");
                }
            }
        }

        log.debug("[RULE ENGINE] PASSED — All rules cleared for transaction: {}",
                event.getTransactionId());
        return null;  // No violation — proceed to Layer 2
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LAYER 2 — Redis History Analysis
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Analyses the user's cached transaction history for behavioural anomalies.
     *
     * Returns:
     *   FRAUD  — impossible travel detected (hard block)
     *   REVIEW — device change detected (soft flag → escalate to AI)
     *   null   — no anomaly found
     *
     * @param event    The current transaction being processed
     * @param history  User's Redis-cached history (already includes current event)
     * @return         FraudResult or null
     */
    private FraudResult analyzeHistory(TransactionEvent event, UserTransactionHistory history) {
        if (history == null
                || history.getRecentTransactions() == null
                || history.getRecentTransactions().size() < 2) {
            log.debug("[HISTORY ENGINE] Insufficient history for user: {} — skipping",
                    event.getUserId());
            return null;
        }

        List<TransactionEvent> recent = history.getRecentTransactions();
        // Index -2 is the transaction BEFORE the current one
        TransactionEvent previous = recent.get(recent.size() - 2);

        // ── History Check 1: Impossible Travel ────────────────────────
        // Configurable via: fraud.rules.impossible-travel-minutes (default: 5)
        if (isDifferentLocation(previous, event)) {
            long minutesBetween = getMinutesBetween(previous, event);
            if (minutesBetween >= 0 && minutesBetween < fraudRules.getImpossibleTravelMinutes()) {
                log.warn("[HISTORY ENGINE] IMPOSSIBLE TRAVEL — User: {} | {} → {} | Gap: {}min | Limit: {}min",
                        event.getUserId(),
                        previous.getLocation(),
                        event.getLocation(),
                        minutesBetween,
                        fraudRules.getImpossibleTravelMinutes());

                return buildResult(event,
                        FraudStatus.FRAUD,
                        String.format("Impossible travel: transactions from '%s' and '%s' "
                                        + "within %d minutes. Limit: %d minutes.",
                                previous.getLocation(),
                                event.getLocation(),
                                minutesBetween,
                                fraudRules.getImpossibleTravelMinutes()),
                        0.95,
                        "REDIS_HISTORY",
                        null);
            }
        }

        // ── History Check 2: Device Change ────────────────────────────
        // A sudden device change is suspicious but not definitive.
        // Flag as REVIEW and let the AI make the final call.
        if (isDifferentDevice(previous, event)) {
            log.info("[HISTORY ENGINE] DEVICE CHANGE — User: {} | Previous: {} | Current: {} → REVIEW",
                    event.getUserId(), previous.getDevice(), event.getDevice());

            String reviewNote = String.format(
                    "Device change detected: previous transaction used '%s', "
                            + "current transaction uses '%s'. "
                            + "This may indicate account sharing or credential compromise.",
                    previous.getDevice(),
                    event.getDevice());

            return buildResult(event,
                    FraudStatus.REVIEW,
                    "Soft anomaly: device change between consecutive transactions",
                    0.5,          // Neutral confidence — AI will refine this
                    "REDIS_HISTORY",
                    reviewNote);
        }

        log.debug("[HISTORY ENGINE] PASSED — No anomalies found for transaction: {}",
                event.getTransactionId());
        return null;  // Clean — proceed to AI
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Private Helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns true if the two transactions are from meaningfully different locations.
     *
     * "UNKNOWN" is the sentinel value stored by UpiPaymentService when the client
     * did not supply a location (pre-fix frontend requests).  Comparing a real
     * location against "UNKNOWN" would always trigger impossible-travel false positives,
     * so we skip the check whenever either side is "UNKNOWN".
     */
    private boolean isDifferentLocation(TransactionEvent previous, TransactionEvent current) {
        if (previous.getLocation() == null || current.getLocation() == null) return false;
        if ("UNKNOWN".equalsIgnoreCase(previous.getLocation().trim()) ||
            "UNKNOWN".equalsIgnoreCase(current.getLocation().trim())) {
            return false;
        }
        return !UserTransactionHistory.areLocationsEquivalent(previous.getLocation(), current.getLocation());
    }

    /**
     * Returns true if the two transactions used meaningfully different devices.
     *
     * "UNKNOWN" is the sentinel value stored by UpiPaymentService when the client
     * did not supply a device (pre-fix frontend requests).  Comparing a real device
     * identifier against "UNKNOWN" would always trigger a device-change false positive,
     * so we skip the check whenever either side is "UNKNOWN".
     */
    private boolean isDifferentDevice(TransactionEvent previous, TransactionEvent current) {
        return previous.getDevice() != null
                && current.getDevice() != null
                && !previous.getDevice().equalsIgnoreCase("UNKNOWN")
                && !current.getDevice().equalsIgnoreCase("UNKNOWN")
                && !previous.getDevice().equalsIgnoreCase(current.getDevice());
    }

    /**
     * Returns the absolute number of minutes between two transactions' timestamps.
     * Returns -1 if either timestamp is null (cannot compute).
     */
    private long getMinutesBetween(TransactionEvent a, TransactionEvent b) {
        if (a.getTimestamp() == null || b.getTimestamp() == null) {
            return -1;
        }
        return Duration.between(a.getTimestamp(), b.getTimestamp()).abs().toMinutes();
    }

    /**
     * Central factory method for building FraudResult objects.
     * Eliminates repetition across all detection layers.
     *
     * @param event          Source transaction
     * @param status         SAFE | FRAUD | REVIEW
     * @param reason         Human-readable explanation
     * @param confidence     Score 0.0 – 1.0
     * @param detectionLayer Label of the layer producing this result
     * @param reviewNotes    Additional notes for human analysts (nullable)
     */
    private FraudResult buildResult(TransactionEvent event,
                                    FraudStatus status,
                                    String reason,
                                    double confidence,
                                    String detectionLayer,
                                    String reviewNotes) {
        return FraudResult.builder()
                .transactionId(event.getTransactionId())
                .userId(event.getUserId())
                .status(status)
                .reason(reason)
                .confidenceScore(confidence)
                .detectionLayer(detectionLayer)
                .reviewNotes(reviewNotes)         // null for SAFE/FRAUD, populated for REVIEW
                .analyzedAt(LocalDateTime.now())
                .build();
    }
}
