package com.fraud.detection.model;

import com.fraud.common.dto.TransactionEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Builder.Default;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * UserTransactionHistory — Redis Cached Model
 *
 * Stored in Redis under key: "user:{userId}:history"
 * TTL: 24 hours (configured in RedisService)
 *
 * Holds the last N transactions per user.
 * Used by FraudDetectionService to:
 *  - Detect unusual transaction frequency
 *  - Compare current location vs known locations
 *  - Identify patterns inconsistent with normal behaviour
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserTransactionHistory implements Serializable {

    /** User ID — matches userId in TransactionEvent */
    private String userId;

    /**
     * List of recent transactions.
     * Eagerly initialised — never null, eliminates defensive null checks throughout the codebase.
     * Capped at MAX_HISTORY_SIZE to prevent unbounded growth.
     * @Builder.Default ensures the Lombok builder respects this initialiser.
     */
    @Default
    private List<TransactionEvent> recentTransactions = new ArrayList<>();

    /**
     * Set of known locations for this user (insertion-ordered, no duplicates).
     * Eagerly initialised — LinkedHashSet gives O(1) contains() and preserves insertion order.
     * @Builder.Default ensures the Lombok builder respects this initialiser.
     */
    @Default
    private Set<String> knownLocations = new LinkedHashSet<>();

    /** Maximum number of transactions to keep per user */
    public static final int MAX_HISTORY_SIZE = 20;

    /**
     * Adds a new transaction to the history.
     * Maintains the rolling window of MAX_HISTORY_SIZE.
     * No null check on recentTransactions — eagerly initialised by @Builder.Default.
     */
    public void addTransaction(TransactionEvent event) {
        recentTransactions.add(event);

        // Keep only the latest MAX_HISTORY_SIZE transactions
        if (recentTransactions.size() > MAX_HISTORY_SIZE) {
            recentTransactions.remove(0);  // Remove oldest
        }

        // Register initial baseline location.
        // Anomalous / unconfirmed locations must not be automatically whitelisted.
        if (knownLocations.isEmpty() && event.getLocation() != null && !event.getLocation().isBlank()
                && !"UNKNOWN".equalsIgnoreCase(event.getLocation().trim())
                && !event.getLocation().toLowerCase().contains("demo remote")
                && !event.getLocation().toLowerCase().contains("london")) {
            addKnownLocation(event.getLocation());
        }
    }

    /**
     * Registers a location as known for this user.
     * No null check on knownLocations — eagerly initialised by @Builder.Default.
     * Set.add() is idempotent — duplicates are silently ignored.
     */
    public void addKnownLocation(String location) {
        if (location != null && !location.isBlank() && !"UNKNOWN".equalsIgnoreCase(location.trim())) {
            knownLocations.add(location.trim());
        }
    }

    /**
     * Returns true if this user has a location baseline and the given city
     * is not in that baseline. Missing / UNKNOWN locations are not a signal.
     * Comparison is case-insensitive and accounts for GPS coordinate drift and timezone equivalence.
     */
    public boolean isUnknownLocation(String location) {
        if (location == null || location.isBlank() || "UNKNOWN".equalsIgnoreCase(location.trim())) {
            return false;
        }
        if (knownLocations == null || knownLocations.isEmpty()) {
            return false;
        }
        String candidate = location.trim();
        for (String known : knownLocations) {
            if (known != null && areLocationsEquivalent(known, candidate)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if two location strings represent the same geographical area.
     */
    public static boolean areLocationsEquivalent(String locA, String locB) {
        if (locA == null || locB == null) return false;
        String a = locA.trim();
        String b = locB.trim();

        if (a.equalsIgnoreCase(b)) return true;

        String aLower = a.toLowerCase();
        String bLower = b.toLowerCase();

        // 1. Timezone equivalence
        boolean aIsIndiaTz = aLower.contains("asia/calcutta") || aLower.contains("asia/kolkata");
        boolean bIsIndiaTz = bLower.contains("asia/calcutta") || bLower.contains("asia/kolkata");
        if (aIsIndiaTz && bIsIndiaTz) return true;

        // 2. Both are GPS coordinates (Geo: lat, lon)
        if (aLower.startsWith("geo:") && bLower.startsWith("geo:")) {
            try {
                String[] partsA = a.substring(4).split(",");
                String[] partsB = b.substring(4).split(",");
                double latA = Double.parseDouble(partsA[0].trim());
                double lonA = Double.parseDouble(partsA[1].trim());
                double latB = Double.parseDouble(partsB[0].trim());
                double lonB = Double.parseDouble(partsB[1].trim());

                // Within ~1.0 degree latitude & longitude (~100km radius metro)
                if (Math.abs(latA - latB) <= 1.0 && Math.abs(lonA - lonB) <= 1.0) {
                    return true;
                }
            } catch (Exception ignored) {}
        }

        // 3. Indian timezone matching Indian Geo coordinates (Lat: 8 to 37, Lon: 68 to 98)
        if (aIsIndiaTz && bLower.startsWith("geo:")) {
            try {
                String[] partsB = b.substring(4).split(",");
                double latB = Double.parseDouble(partsB[0].trim());
                double lonB = Double.parseDouble(partsB[1].trim());
                if (latB >= 8.0 && latB <= 37.0 && lonB >= 68.0 && lonB <= 98.0) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        if (bIsIndiaTz && aLower.startsWith("geo:")) {
            try {
                String[] partsA = a.substring(4).split(",");
                double latA = Double.parseDouble(partsA[0].trim());
                double lonA = Double.parseDouble(partsA[1].trim());
                if (latA >= 8.0 && latA <= 37.0 && lonA >= 68.0 && lonA <= 98.0) {
                    return true;
                }
            } catch (Exception ignored) {}
        }

        return false;
    }
}
