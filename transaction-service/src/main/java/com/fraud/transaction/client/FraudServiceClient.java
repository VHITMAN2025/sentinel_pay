package com.fraud.transaction.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * FraudServiceClient — REST client for transaction-service → fraud-detection-service calls.
 *
 * Used exclusively by TransactionController to serve:
 *   GET /api/transactions/{transactionId}
 *
 * Calls two fraud-detection-service endpoints:
 *   GET /api/fraud/result/{id}   → FraudResult (as Map)
 *   GET /api/fraud/payment/{id}  → PaymentRecord (as Map)
 *
 * A missing result/payment record is returned as null. Service communication
 * failures are surfaced so callers do not mistake an unavailable detector for
 * a transaction that is still processing.
 * Callers must handle null as "not yet available".
 */
@Slf4j
@Component
public class FraudServiceClient {

    private final RestTemplate restTemplate;
    private final String       fraudServiceUrl;
    private final ObjectMapper objectMapper;

    public FraudServiceClient(
            RestTemplate restTemplate,
            @Value("${fraud-service.url:http://localhost:8082}") String fraudServiceUrl) {
        this.restTemplate    = restTemplate;
        this.fraudServiceUrl = fraudServiceUrl;
        this.objectMapper    = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ─── Fraud Result ─────────────────────────────────────────────────────────

    /**
     * Fetch the FraudResult for a transaction. Returns the body as a Map.
     * Returns null if not yet available (202) or not found (404).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getFraudResult(String transactionId) {
        String url = fraudServiceUrl + "/api/fraud/result/" + transactionId;
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.debug("[FRAUD-CLIENT] Fraud result found for txId={}", transactionId);
                return response.getBody();
            }
            return null; // 202 — still pending
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("[FRAUD-CLIENT] Fraud result not found for txId={}", transactionId);
            return null;
        } catch (RestClientException e) {
            log.error("[FRAUD-CLIENT] Failed to fetch fraud result for txId={}: {}",
                    transactionId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Fraud detection service is unavailable", e);
        }
    }

    // ─── Payment Record ───────────────────────────────────────────────────────

    /**
     * Fetch the PaymentRecord for a transaction. Returns the body as a Map.
     * Returns null if no payment record exists (404).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getPaymentRecord(String transactionId) {
        String url = fraudServiceUrl + "/api/fraud/payment/" + transactionId;
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.debug("[FRAUD-CLIENT] Payment record found for txId={}", transactionId);
                return response.getBody();
            }
            return null;
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("[FRAUD-CLIENT] No payment record for txId={} (non-UPI or blocked)", transactionId);
            return null;
        } catch (RestClientException e) {
            log.error("[FRAUD-CLIENT] Failed to fetch payment record for txId={}: {}",
                    transactionId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Fraud detection service is unavailable", e);
        }
    }
}
