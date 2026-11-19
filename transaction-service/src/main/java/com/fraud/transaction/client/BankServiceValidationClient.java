package com.fraud.transaction.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * BankServiceValidationClient — pre-payment payee UPI ID validation.
 *
 * Called by UpiPaymentService BEFORE publishing to Kafka to verify the payee's
 * UPI ID exists in bank-service. This prevents Kafka messages that will always
 * fail in the fraud-detection-service payment processor, polluting the audit log
 * with guaranteed-failed transactions.
 *
 * Validation is a read-only operation — no state is changed.
 *
 * If bank-service is unreachable (RestClientException), the validation is treated
 * as passing (fail-open) to preserve availability. The payment will still be
 * rejected by PaymentProcessorService if the UPI ID is truly invalid.
 * This trade-off can be reversed to fail-closed by changing the catch block.
 */
@Slf4j
@Component
public class BankServiceValidationClient {

    private final RestTemplate restTemplate;
    private final String bankServiceUrl;

    public BankServiceValidationClient(
            RestTemplate restTemplate,
            @Value("${bank-service.url:http://localhost:8084}") String bankServiceUrl) {
        this.restTemplate   = restTemplate;
        this.bankServiceUrl = bankServiceUrl;
    }

    /**
     * Check whether a UPI ID is registered in bank-service.
     *
     * Calls GET /bank/account/by-upi/{upiId}.
     *   - 200 → UPI ID exists → returns true
     *   - 404 → UPI ID not found → returns false
     *   - Network error → fail-open → returns true (with warning log)
     *
     * @param upiId the payee's UPI ID to validate
     * @return true if the UPI ID is registered or bank-service is unreachable
     */
    public boolean isUpiIdRegistered(String upiId) {
        String url = bankServiceUrl + "/bank/account/by-upi/" + upiId;
        try {
            restTemplate.getForEntity(url, Object.class);
            log.debug("[BANK-VALIDATION] UPI ID '{}' is registered.", upiId);
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            log.info("[BANK-VALIDATION] UPI ID '{}' is NOT registered in bank-service.", upiId);
            return false;
        } catch (RestClientException e) {
            // Fail-open: bank-service unreachable, do not block payment initiation.
            // PaymentProcessorService will detect the invalid UPI ID later.
            log.warn("[BANK-VALIDATION] bank-service unreachable during UPI pre-validation "
                    + "for upiId='{}' — failing open. Error: {}", upiId, e.getMessage());
            return true;
        }
    }

    /**
     * Verify payer's UPI PIN with bank-service.
     *
     * @param payerEmail Payer's user ID / email
     * @param upiPin 6-digit UPI PIN
     */
    public void validateUpiPin(String payerEmail, String upiPin) {
        if (payerEmail == null || upiPin == null || upiPin.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "UPI PIN is required to authorize payment");
        }
        String url = bankServiceUrl + "/bank/pin/verify";
        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("X-User-Email", payerEmail);
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            java.util.Map<String, String> body = java.util.Map.of("upiPin", upiPin);
            org.springframework.http.HttpEntity<java.util.Map<String, String>> requestEntity =
                    new org.springframework.http.HttpEntity<>(body, headers);

            org.springframework.http.ResponseEntity<java.util.Map> response =
                    restTemplate.postForEntity(url, requestEntity, java.util.Map.class);

            if (!response.getStatusCode().is2xxSuccessful() ||
                    !Boolean.TRUE.equals(response.getBody() != null ? response.getBody().get("valid") : false)) {
                String msg = response.getBody() != null && response.getBody().get("message") != null
                        ? response.getBody().get("message").toString()
                        : "Incorrect UPI PIN. Please try again.";
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, msg);
            }
        } catch (HttpClientErrorException.UnprocessableEntity | HttpClientErrorException.BadRequest e) {
            String errorBody = e.getResponseBodyAsString();
            String msg = "Incorrect UPI PIN. Please try again.";
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(errorBody);
                if (node.has("message")) {
                    msg = node.get("message").asText();
                }
            } catch (Exception ignored) {}
            log.warn("[BANK-VALIDATION] UPI PIN validation rejected for payer='{}': {}", payerEmail, msg);
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, msg);
        } catch (HttpClientErrorException e) {
            log.warn("[BANK-VALIDATION] UPI PIN validation rejected for payer='{}': {}", payerEmail, e.getStatusCode());
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Incorrect UPI PIN. Authorization failed.");
        } catch (RestClientException e) {
            log.error("[BANK-VALIDATION] bank-service unreachable during UPI PIN validation for payer='{}': {}",
                    payerEmail, e.getMessage());
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Banking service is currently unavailable to verify UPI PIN.");
        }
    }

    public boolean verifyUpiPin(String payerEmail, String upiPin) {
        try {
            validateUpiPin(payerEmail, upiPin);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
