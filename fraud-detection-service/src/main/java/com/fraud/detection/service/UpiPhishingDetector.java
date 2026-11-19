package com.fraud.detection.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * UpiPhishingDetector — Technique 1: Phishing & Brand Typosquatting Detection
 *
 * Employs Fuzzy String Matching (Levenshtein Distance) and Phishing Keyword
 * Heuristics to detect UPI IDs impersonating verified brands and institutions.
 *
 * Example attack vectors detected:
 *   - Typosquatting: "amazonn@upi", "paytmm@okaxis", "phonpe.care@ybl", "flipkard@upi"
 *   - Social Engineering Keywords: "sbi.kyc.update@upi", "electricity.bill.support@oksbi", "amazon.refund@paytm"
 */
@Component
public class UpiPhishingDetector {

    private static final Logger log = LoggerFactory.getLogger(UpiPhishingDetector.class);

    @Getter
    @AllArgsConstructor
    public static class PhishingCheckResult {
        private final boolean flagged;
        private final String reason;
        private final double confidenceScore;
        private final String matchedBrand;
    }

    private static final Set<String> PROTECTED_BRANDS = Set.of(
            "amazon", "flipkart", "googlepay", "gpay", "phonepe", "paytm",
            "sbi", "hdfc", "icici", "axis", "kotak", "pmcares", "bescom",
            "tneb", "zomato", "swiggy", "netflix", "airtel", "jio", "cred"
    );

    private static final Set<String> HIGH_RISK_KEYWORDS = Set.of(
            "refund", "cashback", "kyc", "support", "helpline", "lottery",
            "bonus", "billpay", "electricity", "rewards", "verify", "customercare",
            "tollfree", "prize", "giftcard", "winner", "urgent"
    );

    /**
     * Inspects a payee UPI ID for brand impersonation or phishing indicators.
     *
     * @param upiId Payee UPI ID (e.g., "amazonn.refund@upi")
     * @return PhishingCheckResult with verdict and explanation
     */
    public PhishingCheckResult checkPayeeUpi(String upiId) {
        if (upiId == null || upiId.isBlank() || !upiId.contains("@")) {
            return new PhishingCheckResult(false, null, 0.0, null);
        }

        String handle = upiId.substring(0, upiId.indexOf("@")).toLowerCase().trim();
        String[] tokens = handle.split("[._\\-0-9]+");

        // 1. Check for Typosquatting (Levenshtein edit distance 1 or 2)
        for (String brand : PROTECTED_BRANDS) {
            for (String token : tokens) {
                if (token.length() < 3) continue;

                int dist = computeLevenshteinDistance(token, brand);

                // Typosquatting detection: edit distance of 1 or 2 against protected brand
                if (dist > 0 && dist <= (brand.length() > 4 ? 2 : 1)) {
                    log.warn("[PHISHING-DETECTOR] Brand typosquatting detected: handle='{}' brand='{}' dist={}",
                            handle, brand, dist);
                    return new PhishingCheckResult(
                            true,
                            String.format("Brand Typosquatting Alert: Payee UPI ID '%s' is impersonating '%s' (Levenshtein edit distance: %d). This is an active phishing attempt.",
                                    upiId, brand, dist),
                            0.95,
                            brand
                    );
                }

                // Brand + High-Risk keyword combination (e.g. amazon_refund, sbi_kyc)
                if (token.equals(brand)) {
                    for (String otherToken : tokens) {
                        if (HIGH_RISK_KEYWORDS.contains(otherToken)) {
                            log.warn("[PHISHING-DETECTOR] Brand + Phishing keyword detected: handle='{}' brand='{}' keyword='{}'",
                                    handle, brand, otherToken);
                            return new PhishingCheckResult(
                                    true,
                                    String.format("Phishing Alert: Payee UPI ID '%s' combines trusted brand '%s' with high-risk keyword '%s' (impersonation fraud).",
                                            upiId, brand, otherToken),
                                    0.92,
                                    brand
                            );
                        }
                    }
                }
            }
        }

        // 2. High-Risk Phishing Handle Check (e.g. electricity.billpay.urgent@upi)
        long matchedKeywords = Arrays.stream(tokens)
                .filter(HIGH_RISK_KEYWORDS::contains)
                .count();

        if (matchedKeywords >= 2) {
            log.warn("[PHISHING-DETECTOR] Multiple phishing keywords detected: handle='{}' count={}",
                    handle, matchedKeywords);
            return new PhishingCheckResult(
                    true,
                    String.format("Social Engineering Alert: Payee UPI ID '%s' contains multiple suspicious scam keywords (phishing score elevated).",
                            upiId),
                    0.88,
                    null
            );
        }

        return new PhishingCheckResult(false, null, 0.0, null);
    }

    /**
     * Compute Levenshtein distance between two strings using dynamic programming.
     */
    public int computeLevenshteinDistance(String a, String b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        int[][] dp = new int[a.length() + 1][b.length() + 1];

        for (int i = 0; i <= a.length(); i++) {
            for (int j = 0; j <= b.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                    dp[i][j] = Math.min(
                            Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                            dp[i - 1][j - 1] + cost
                    );
                }
            }
        }
        return dp[a.length()][b.length()];
    }
}
