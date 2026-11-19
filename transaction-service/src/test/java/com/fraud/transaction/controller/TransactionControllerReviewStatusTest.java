package com.fraud.transaction.controller;

import com.fraud.transaction.client.FraudServiceClient;
import com.fraud.transaction.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionControllerReviewStatusTest {

    @Mock
    private TransactionService transactionService;

    @Mock
    private FraudServiceClient fraudServiceClient;

    @InjectMocks
    private TransactionController transactionController;

    @Test
    void reportsApprovedReviewPaymentAsCompleteWithoutChangingFraudVerdict() {
        when(fraudServiceClient.getFraudResult("review-123"))
                .thenReturn(Map.of("status", "REVIEW", "confidenceScore", 0.5));
        when(fraudServiceClient.getPaymentRecord("review-123"))
                .thenReturn(Map.of(
                        "paymentStatus", "SUCCESS",
                        "paymentMode", "BANK",
                        "amount", new BigDecimal("500.00"),
                        "payerEmail", "payer@example.com",
                        "payeeEmail", "receiver@example.com",
                        "payeeUpiId", "receiver@upi",
                        "payerApprovedReview", true));

        var response = transactionController.getTransactionStatus("review-123");

        assertEquals("REVIEW", response.getBody().getFraudStatus());
        assertEquals("SUCCESS", response.getBody().getPaymentStatus());
        assertEquals("COMPLETE", response.getBody().getOverallStatus());
        assertEquals("receiver@upi", response.getBody().getPayeeUpiId());
    }

    @Test
    void reportsUnapprovedReviewPaymentAsAwaitingPayerAction() {
        when(fraudServiceClient.getFraudResult("review-123"))
                .thenReturn(Map.of("status", "REVIEW"));
        when(fraudServiceClient.getPaymentRecord("review-123")).thenReturn(null);

        var response = transactionController.getTransactionStatus("review-123");

        assertEquals("REVIEW", response.getBody().getFraudStatus());
        assertEquals("REVIEW", response.getBody().getOverallStatus());
        assertEquals("Payment is on hold. The payer must approve or cancel it.",
                response.getBody().getMessage());
    }

    @Test
    void surfacesFraudServiceOutageInsteadOfReportingAFalseBlockedStatus() {
        when(fraudServiceClient.getFraudResult("review-123"))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "Fraud detection service is unavailable"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> transactionController.getTransactionStatus("review-123"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }
}
