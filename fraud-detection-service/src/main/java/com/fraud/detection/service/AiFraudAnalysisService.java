package com.fraud.detection.service;

import com.fraud.common.dto.FraudResult;
import com.fraud.common.dto.FraudResult.FraudStatus;
import com.fraud.common.dto.TransactionEvent;
import com.fraud.detection.config.FraudRulesProperties;
import com.fraud.detection.model.UserTransactionHistory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AiFraudAnalysisService — Google Gemini fraud analysis
 *
 * This is Layer 3 of the fraud detection pipeline. It is invoked only when
 * Layers 1 and 2 (rule engine + Redis history) produce no hard FRAUD verdict.
 *
 * Responsibilities:
 *  1. Build a structured, deterministic prompt with full transaction context
 *  2. Call Google Gemini via its generateContent REST API
 *  3. Parse the machine-parseable response (verdict / fraud_probability / reason)
 *  4. Map estimated fraud probability to FraudStatus using the configurable review band.
 *
 * CHANGE LOG:
 *  v1.1 — Completely rewrote prompt for deterministic, machine-parseable output
 *        — Added REVIEW verdict band driven by FraudRulesProperties
 *        — Prompt now includes all 5 fraud dimensions (amount, location, device,
 *          frequency, merchant category)
 *        — Injected FraudRulesProperties for configurable confidence thresholds
 *        — Improved fallback handling: AI unavailable → explainable local score
 *        — Added reviewNotes population when AI returns REVIEW
 */
@Service
public class AiFraudAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiFraudAnalysisService.class);

    /** Maximum time to wait for a Gemini response before timing out */
    private static final int AI_TIMEOUT_SECONDS = 5;

    /**
     * Dedicated fixed thread pool for Gemini calls.
     *
     * WHY NOT CompletableFuture's default ForkJoinPool:
     *  - ForkJoinPool is shared across the entire JVM (Spring internals, @Async, etc.)
     *  - AI calls are I/O-bound and can block for seconds — starving compute tasks
     *  - This dedicated pool gives the AI 10 threads, isolated from the rest of the app
     *
     * Sizing: 10 threads supports up to 10 concurrent AI calls before queuing.
     * With AI_TIMEOUT_SECONDS=5 and 3 Kafka partitions, 10 threads provides
     * comfortable headroom for burst processing.
     */
    private static final ExecutorService AI_EXECUTOR = Executors.newFixedThreadPool(10);

    // ─── Injected Dependencies ─────────────────────────────────────────────

    /**
     * Gemini REST client and request configuration.
     */
    @Autowired
    private RestClient geminiRestClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${gemini.api-key:not-configured}")
    private String geminiApiKey;

    @Value("${gemini.model:gemini-1.5-flash}")
    private String geminiModel;

    @PreDestroy
    public void shutdown() {
        log.info("[AI SERVICE] Shutting down AI executor thread pool...");
        AI_EXECUTOR.shutdown();
        try {
            if (!AI_EXECUTOR.awaitTermination(2, TimeUnit.SECONDS)) {
                AI_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            AI_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Fraud rules configuration — provides configurable confidence thresholds.
     * Avoids hardcoded 0.4 / 0.6 magic numbers in this class.
     */
    @Autowired
    private FraudRulesProperties fraudRules;

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Runs AI-based fraud analysis on the given transaction.
     *
     * The method:
     *   1. Builds a rich, structured prompt
     *   2. Calls the configured Google Gemini model
     *   3. Parses the response into verdict + fraud probability + reason
     *   4. Maps fraud probability to FraudStatus (SAFE / REVIEW / FRAUD)
     *
     * Failure policy: if AI is unavailable, use the deterministic local scorer.
     * This keeps the payment path available during an AI outage while preserving
     * REVIEW for genuinely ambiguous local signals.
     *
     * @param event    The transaction to analyze
     * @param history  User's Redis-cached history (may be null for new users)
     * @return         FraudResult with SAFE | REVIEW | FRAUD
     */
    public FraudResult analyze(TransactionEvent event, UserTransactionHistory history) {
        log.info("[AI SERVICE] Initiating Gemini analysis for transaction: {} | User: {}",
                event.getTransactionId(), event.getUserId());

        // A missing key is an expected local-development configuration, not a
        // reason to hold every payment for manual review (or wait five seconds).
        if (!isGeminiConfigured()) {
            log.info("[AI SERVICE] Gemini is not configured; using local risk scorer for transaction: {}",
                    event.getTransactionId());
            return buildLocalFallback(event, history, "Gemini is not configured");
        }

        try {
            // Build the structured prompt
            String prompt = buildStructuredPrompt(event, history);

            log.debug("[AI SERVICE] Sending prompt to Gemini (length: {} chars)", prompt.length());

            // Invoke Gemini wrapped in a CompletableFuture with a hard timeout.
            // Uses AI_EXECUTOR (dedicated fixed pool) instead of the default ForkJoinPool
            // to isolate I/O-bound AI blocking from the rest of the application's compute tasks.
            final String transactionId = event.getTransactionId();
            String rawResponse = CompletableFuture
                    .supplyAsync(() -> callGemini(prompt),
                            AI_EXECUTOR)   // dedicated pool — not ForkJoinPool.commonPool()
                    .get(AI_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Defensive guard: treat null or blank response as a failure
            if (rawResponse == null || rawResponse.isBlank()) {
                log.warn("[AI SERVICE] Empty/null response from Gemini for transaction: {} — using local scorer",
                        transactionId);
                return buildLocalFallback(event, history, "Gemini returned an empty response");
            }

            log.info("[AI SERVICE] Gemini responded for transaction: {}", transactionId);
            log.debug("[AI SERVICE] Raw response: {}", rawResponse);

            // Parse + classify the response
            return parseAndClassify(event, history, rawResponse);

        } catch (TimeoutException e) {
            log.warn("[AI SERVICE] ⏱ TIMEOUT — Gemini did not respond within {}s for transaction: {} | "
                    + "Using local scorer.",
                    AI_TIMEOUT_SECONDS, event.getTransactionId());
            return buildLocalFallback(event, history,
                    "Gemini did not respond within " + AI_TIMEOUT_SECONDS + " seconds");

        } catch (Exception e) {
            // AI unavailable, rate-limited, interrupted, or returned an unparseable response.
            // Keep the detector live with an explainable local score instead of
            // giving every transaction the same fallback verdict.
            log.error("[AI SERVICE] ✗ FAILED — Transaction: {} | Error: {} | Class: {}",
                    event.getTransactionId(), e.getMessage(), e.getClass().getSimpleName(), e);
            return buildLocalFallback(event, history, "Gemini unavailable: " + e.getClass().getSimpleName());
        }
    }

    private boolean isGeminiConfigured() {
        return geminiApiKey != null && !geminiApiKey.isBlank()
                && !"not-configured".equalsIgnoreCase(geminiApiKey.trim());
    }

    private String callGemini(String prompt) {
        if (geminiApiKey == null || geminiApiKey.isBlank()
                || "not-configured".equals(geminiApiKey)) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured");
        }

        JsonNode response = geminiRestClient.post()
                .uri("/v1beta/models/{model}:generateContent", geminiModel)
                .header("x-goog-api-key", geminiApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "contents", List.of(Map.of(
                                "role", "user",
                                "parts", List.of(Map.of("text", prompt)))),
                        "generationConfig", Map.of(
                                "maxOutputTokens", 512,
                                "temperature", 0.0,
                                "responseMimeType", "application/json",
                                "thinkingConfig", Map.of("thinkingBudget", 0))))
                .retrieve()
                .body(JsonNode.class);

        JsonNode parts = response == null
                ? null
                : response.path("candidates").path(0).path("content").path("parts");
        if (parts == null || !parts.isArray()) {
            throw new IllegalStateException("Gemini response did not contain candidate text");
        }

        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            JsonNode textPart = part.get("text");
            if (textPart != null && textPart.isTextual()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(textPart.asText());
            }
        }
        return text.toString();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LOCAL FALLBACK SCORER
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Explainable, deterministic fallback used if the optional Gemini call is
     * unavailable. It intentionally evaluates only soft signals; hard signals
     * (amount, new location, velocity and impossible travel) have already been
     * handled by the earlier pipeline layers.
     */
    private FraudResult buildLocalFallback(TransactionEvent event,
                                           UserTransactionHistory history,
                                           String fallbackReason) {
        double threshold = fraudRules.getHighAmountThreshold().doubleValue();
        double amount = event.getAmount() == null ? 0.0 : event.getAmount().doubleValue();
        double risk = 0.04 + Math.min(0.20, (amount / Math.max(threshold, 1.0)) * 0.20);
        String reason = "No high-risk rule was triggered; local behavioural score is based on transaction amount.";

        TransactionEvent previous = latestPriorTransaction(history);
        if (previous != null && previous.getAmount() != null
                && previous.getAmount().compareTo(BigDecimal.ZERO) > 0) {
            double ratio = amount / previous.getAmount().doubleValue();
            if (ratio >= 10) {
                risk = Math.max(risk, fraudRules.getReviewConfidenceMin() + 0.12);
                reason = String.format("Amount is %.1fx the previous payment and needs payer confirmation.", ratio);
            } else if (ratio >= 5) {
                risk = Math.max(risk, 0.30);
                reason = String.format("Amount is %.1fx the previous payment, but no hard fraud rule was triggered.", ratio);
            }
            if (isDifferentKnownDevice(previous, event)) {
                risk = Math.max(risk, (fraudRules.getReviewConfidenceMin() + fraudRules.getReviewConfidenceMax()) / 2);
                reason = "Device changed from the previous payment and needs payer confirmation.";
            }
        }

        risk = AiFraudVerdictMapper.toFraudProbability(risk);
        FraudStatus status = AiFraudVerdictMapper.toStatus(risk,
                fraudRules.getReviewConfidenceMin(), fraudRules.getReviewConfidenceMax());
        String reviewNotes = String.format("%s. Local scorer was used because %s.", reason, fallbackReason);
        return FraudResult.builder()
                .transactionId(event.getTransactionId())
                .userId(event.getUserId())
                .status(status)
                .reason(reason)
                .confidenceScore(risk)
                .detectionLayer("LOCAL_FALLBACK")
                .reviewNotes(status == FraudStatus.REVIEW ? reviewNotes : null)
                .analyzedAt(LocalDateTime.now())
                .build();
    }

    private TransactionEvent latestPriorTransaction(UserTransactionHistory history) {
        if (history == null || history.getRecentTransactions() == null
                || history.getRecentTransactions().isEmpty()) {
            return null;
        }
        return history.getRecentTransactions().get(history.getRecentTransactions().size() - 1);
    }

    private boolean isDifferentKnownDevice(TransactionEvent previous, TransactionEvent current) {
        return previous.getDevice() != null && current.getDevice() != null
                && !previous.getDevice().isBlank() && !current.getDevice().isBlank()
                && !"UNKNOWN".equalsIgnoreCase(previous.getDevice())
                && !"UNKNOWN".equalsIgnoreCase(current.getDevice())
                && !previous.getDevice().equalsIgnoreCase(current.getDevice());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PROMPT ENGINEERING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Builds a structured, deterministic prompt for Google Gemini.
     *
     * Design principles applied:
     *  1. Role assignment    — "You are a fraud detection AI" primes the model
     *  2. Structured input   — labelled fields prevent misinterpretation
     *  3. Contextual history — user's recent transactions give the model baseline behaviour
     *  4. Explicit criteria  — lists exactly what to check (5 fraud dimensions)
     *  5. Strict format      — VERDICT/CONFIDENCE/REASON prevents free-form prose
     *
     * @param event    The transaction to evaluate
     * @param history  User's recent transaction history from Redis
     * @return         Complete prompt string ready for Gemini
     */
    private String buildStructuredPrompt(TransactionEvent event, UserTransactionHistory history) {
        StringBuilder sb = new StringBuilder(1500);

        // ── System role instruction ──────────────────────────────────────
        sb.append("You are a fraud detection AI for Indian UPI / simulated bank payments.\n");
        sb.append("Layers 1–2 already blocked amount > ")
                .append(fraudRules.getHighAmountThreshold())
                .append(" INR, unknown cities, >")
                .append(fraudRules.getMaxTransactionsPerDay())
                .append(" txs/24h, and impossible travel. You only see transactions that passed those rules.\n");
        sb.append("Default to SAFE for ordinary retail amounts. Do not invent risk.\n\n");

        // ── Transaction under analysis ───────────────────────────────────
        sb.append("=== TRANSACTION TO ANALYSE ===\n");
        sb.append(String.format("Transaction ID  : %s%n", event.getTransactionId()));
        sb.append(String.format("User ID         : %s%n", event.getUserId()));
        sb.append(String.format("Amount (INR)    : %.2f%n", event.getAmount()));
        sb.append(String.format("Location        : %s%n", event.getLocation()));
        sb.append(String.format("Device          : %s%n", event.getDevice()));
        sb.append(String.format("Merchant Cat.   : %s%n",
                event.getMerchantCategory() != null ? event.getMerchantCategory() : "Not provided"));
        sb.append(String.format("Timestamp       : %s%n", event.getTimestamp()));
        sb.append("\n");

        // ── User behaviour context from Redis ────────────────────────────
        sb.append("=== USER BEHAVIOUR CONTEXT (PRIOR TRANSACTIONS ONLY) ===\n");
        if (history != null
                && history.getRecentTransactions() != null
                && !history.getRecentTransactions().isEmpty()) {

            int txCount = history.getRecentTransactions().size();
            sb.append(String.format("Transactions in 24h window : %d%n", txCount));
            sb.append(String.format("Known locations             : %s%n",
                    history.getKnownLocations() != null ? history.getKnownLocations() : "[]"));

            // Last 3 transactions — enough for pattern recognition without token overload
            sb.append("Recent transactions (latest first):\n");
            int startIdx = Math.max(0, txCount - 3);   // Up to last 3
            for (int i = txCount - 1; i >= startIdx; i--) {
                var t = history.getRecentTransactions().get(i);
                sb.append(String.format("  [%d] Amount: %.2f INR | Location: %s | Device: %s | Time: %s%n",
                        (txCount - i),
                        t.getAmount(),
                        t.getLocation(),
                        t.getDevice(),
                        t.getTimestamp()));
            }
        } else {
            sb.append("No prior transaction history found (new user or first transaction in 24h window).\n");
        }
        sb.append("\n");

        // ── Fraud evaluation criteria ─────────────────────────────────────
        sb.append("=== FRAUD EVALUATION CRITERIA ===\n");
        sb.append("1. AMOUNT — An amount at or below ")
                .append(fraudRules.getHighAmountThreshold())
                .append(" INR is NOT high-risk by itself. ₹100, ₹500, ₹1,000 and ₹5,000 are normal UPI amounts.\n");
        sb.append("   Only flag amount if prior history exists AND this amount is at least 10× the user's largest prior transaction.\n");
        sb.append("2. LOCATION — Do not flag location; unknown cities are already blocked by the rule engine.\n");
        sb.append("3. DEVICE — A device change is a soft signal only. Prefer REVIEW (~0.45–0.55), not FRAUD.\n");
        sb.append("4. VELOCITY — Do not flag velocity; that rule already ran.\n");
        sb.append("5. CATEGORY — Missing or grocery/food/retail categories are normal. Do not invent a spending profile.\n\n");

        sb.append("=== VERDICT DEFINITIONS ===\n");
        sb.append("SAFE   → No specific anomaly in the supplied facts. Use this for typical payments including first transactions.\n");
        sb.append("FRAUD  → Strong, specific evidence of fraud in the supplied facts (not amount alone under the threshold).\n");
        sb.append("REVIEW → Genuine ambiguity, typically a device change without other hard signals.\n\n");

        sb.append("=== REQUIRED RESPONSE FORMAT ===\n");
        sb.append("Return one JSON object only:\n");
        sb.append("{\"verdict\":\"SAFE|FRAUD|REVIEW\",\"fraud_probability\":0.0,\"reason\":\"one concise factual sentence\",\"signals\":[\"observed evidence\"]}\n\n");
        sb.append("CRITICAL SCORING RULES:\n");
        sb.append("- fraud_probability is P(this transaction is fraudulent), NOT how confident you are in the verdict.\n");
        sb.append("- SAFE must use fraud_probability < ")
                .append(fraudRules.getReviewConfidenceMin())
                .append(". Typical clean UPI: 0.05–0.20.\n");
        sb.append("- REVIEW uses fraud_probability between ")
                .append(fraudRules.getReviewConfidenceMin())
                .append(" and ")
                .append(fraudRules.getReviewConfidenceMax())
                .append(" inclusive.\n");
        sb.append("- FRAUD uses fraud_probability > ")
                .append(fraudRules.getReviewConfidenceMax())
                .append(".\n");
        sb.append("- First transaction / empty history → SAFE with fraud_probability <= 0.15. Lack of history is not fraud.\n");
        sb.append("- Never treat ₹500 (or any amount ≤ ")
                .append(fraudRules.getHighAmountThreshold())
                .append(" INR) as fraud solely because it is 'not small'.\n");
        sb.append("- Do not invent anomalies. If none are observed, verdict is SAFE.\n\n");
        sb.append("EXAMPLES:\n");
        sb.append("{\"verdict\":\"SAFE\",\"fraud_probability\":0.08,\"reason\":\"₹500 grocery payment with no specific anomaly.\",\"signals\":[]}\n");
        sb.append("{\"verdict\":\"SAFE\",\"fraud_probability\":0.12,\"reason\":\"First transaction of ₹2,000; no history is not evidence of fraud.\",\"signals\":[]}\n");
        sb.append("{\"verdict\":\"REVIEW\",\"fraud_probability\":0.48,\"reason\":\"Device changed between consecutive payments without other hard signals.\",\"signals\":[\"device_change\"]}\n");
        sb.append("{\"verdict\":\"FRAUD\",\"fraud_probability\":0.82,\"reason\":\"Amount is 20× the user's largest prior payment with an additional device mismatch.\",\"signals\":[\"amount_spike\",\"device_change\"]}\n");

        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  RESPONSE PARSING + STATUS CLASSIFICATION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Parses the Gemini response and maps it to a FraudResult.
     *
     * Expected response (3 lines, strict format):
     *   {"verdict":"FRAUD","fraud_probability":0.92,"reason":"..."}
     *
     * Probability-to-status mapping (driven by FraudRulesProperties):
     *   fraudProbability > reviewConfidenceMax  → FRAUD
     *   fraudProbability in [min, max]           → REVIEW
     *   fraudProbability < reviewConfidenceMin   → SAFE
     *
     * Note: The AI's text VERDICT is used as a primary signal, but the
     * probability-band mapping determines the final classification.
     *
     * @param event      Source transaction (for building the result)
     * @param rawResponse  Raw string from Gemini
     * @return           FraudResult with final SAFE | REVIEW | FRAUD verdict
     */
    private FraudResult parseAndClassify(TransactionEvent event, UserTransactionHistory history,
                                         String rawResponse) {
        try {
            JsonNode response = objectMapper.readTree(extractJson(rawResponse));
            String aiVerdict = response.path("verdict").asText("").trim().toUpperCase();
            JsonNode probabilityNode = response.path("fraud_probability");
            String reason = response.path("reason").asText("").trim();
            if (!List.of("SAFE", "FRAUD", "REVIEW").contains(aiVerdict)
                    || !probabilityNode.isNumber()
                    || reason.isBlank()) {
                throw new IllegalArgumentException("Gemini response omitted a valid verdict, probability, or reason");
            }
            double fraudProbability = AiFraudVerdictMapper.toFraudProbability(probabilityNode.asDouble());
            if (reason.length() > 300) {
                reason = reason.substring(0, 297) + "...";
            }
            return classify(event, aiVerdict, fraudProbability, reason);
        } catch (Exception e) {
            log.warn("[AI SERVICE] Invalid structured response for transaction {} — {}",
                    event.getTransactionId(), e.getMessage());
            return buildLocalFallback(event, history,
                    "Gemini returned an invalid or incomplete structured assessment");
        }
    }

    private String extractJson(String response) {
        String candidate = response.trim()
                .replaceFirst("^```(?:json)?\\s*", "")
                .replaceFirst("\\s*```$", "")
                .trim();
        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("No JSON object found");
        }
        return candidate.substring(start, end + 1);
    }

    private FraudResult classify(TransactionEvent event, String aiVerdict,
                                 double fraudProbability, String reason) {
        FraudStatus finalStatus;
        String reviewNotes = null;

        finalStatus = AiFraudVerdictMapper.toStatus(
                fraudProbability,
                fraudRules.getReviewConfidenceMin(),
                fraudRules.getReviewConfidenceMax());

        if (finalStatus == FraudStatus.FRAUD) {
            log.warn("[AI SERVICE] → FRAUD | Transaction: {} | P(fraud): {} | Reason: {}",
                    event.getTransactionId(), fraudProbability, reason);

        } else if (finalStatus == FraudStatus.REVIEW) {
            reviewNotes = String.format(
                    "AI-estimated fraud risk %.0f%% is in the uncertain band [%.0f%% – %.0f%%]. "
                            + "AI assessment was '%s'. Payer confirmation is required before this simulated payment can proceed. "
                            + "Assessment: %s",
                    fraudProbability * 100,
                    fraudRules.getReviewConfidenceMin() * 100,
                    fraudRules.getReviewConfidenceMax() * 100,
                    aiVerdict,
                    reason);
            log.info("[AI SERVICE] → REVIEW | Transaction: {} | P(fraud): {} (band: {}-{})",
                    event.getTransactionId(), fraudProbability,
                    fraudRules.getReviewConfidenceMin(),
                    fraudRules.getReviewConfidenceMax());

        } else {
            log.info("[AI SERVICE] → SAFE | Transaction: {} | P(fraud): {} | Reason: {}",
                    event.getTransactionId(), fraudProbability, reason);
        }

        return FraudResult.builder()
                .transactionId(event.getTransactionId())
                .userId(event.getUserId())
                .status(finalStatus)
                .reason(reason)
                .confidenceScore(fraudProbability)
                .detectionLayer("AI")
                .reviewNotes(reviewNotes)
                .analyzedAt(LocalDateTime.now())
                .build();
    }
}
