package com.fraud.detection.controller;

import com.fraud.common.dto.FraudResult;
import com.fraud.common.dto.TransactionEvent;
import com.fraud.detection.dto.PendingReviewTransactionResponse;
import com.fraud.detection.model.PaymentRecord;
import com.fraud.detection.model.PaymentStatus;
import com.fraud.detection.model.ReviewDecision;
import com.fraud.detection.model.ReviewDecisionRequest;
import com.fraud.detection.payment.PaymentProcessorService;
import com.fraud.detection.service.FraudDetectionService;
import com.fraud.detection.service.RedisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudControllerReviewTest {

    @Mock
    private FraudDetectionService fraudDetectionService;

    @Mock
    private RedisService redisService;

    @Mock
    private PaymentProcessorService paymentProcessorService;

    @InjectMocks
    private FraudController fraudController;

    @Test
    void rejectsReviewDecisionFromSomeoneOtherThanThePayer() {
        when(redisService.getFraudResult("review-123")).thenReturn(reviewResult("payer@example.com"));
        ReviewDecisionRequest request = request(ReviewDecision.Decision.CONFIRM);

        var response = fraudController.submitReviewDecision(
                "review-123", "other@example.com", request, new MockHttpServletRequest());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(redisService, never()).saveReviewDecision(any());
    }

    @Test
    void recordsPayerConfirmationWithoutChangingTheReviewVerdict() {
        FraudResult result = reviewResult("payer@example.com");
        when(redisService.getFraudResult("review-123")).thenReturn(result);
        when(redisService.saveReviewDecision(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = fraudController.submitReviewDecision(
                "review-123", "payer@example.com", request(ReviewDecision.Decision.CONFIRM),
                new MockHttpServletRequest());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(ReviewDecision.Decision.CONFIRM,
                ((ReviewDecision) response.getBody()).getDecision());
        assertEquals(FraudResult.FraudStatus.REVIEW, result.getStatus());
    }

    @Test
    void rejectsDecisionWhenTransactionIsNotUnderReview() {
        when(redisService.getFraudResult("safe-123")).thenReturn(FraudResult.builder()
                .transactionId("safe-123")
                .userId("payer@example.com")
                .status(FraudResult.FraudStatus.SAFE)
                .build());

        var response = fraudController.submitReviewDecision(
                "safe-123", "payer@example.com", request(ReviewDecision.Decision.CANCEL),
                new MockHttpServletRequest());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(redisService, never()).saveReviewDecision(any());
    }

    @Test
    void payerApprovalProcessesReviewedBankPaymentAndReturnsPaymentStatus() {
        when(redisService.getFraudResult("review-123")).thenReturn(reviewResult("payer@example.com"));
        when(redisService.getReviewTransaction("review-123")).thenReturn(reviewEvent("BANK"));
        when(redisService.saveReviewDecision(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(redisService.acquireReviewApprovalLock("review-123")).thenReturn(true);
        when(paymentProcessorService.processManuallyApprovedPayment(any()))
                .thenReturn(PaymentRecord.builder()
                        .transactionId("review-123")
                        .paymentStatus(PaymentStatus.SUCCESS)
                        .build());

        var response = fraudController.submitReviewDecision(
                "review-123", "payer@example.com", request(ReviewDecision.Decision.APPROVE),
                new MockHttpServletRequest());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(PaymentStatus.SUCCESS, ((ReviewDecision) response.getBody()).getPaymentStatus());
        verify(paymentProcessorService).processManuallyApprovedPayment(any());
        verify(redisService).releaseReviewApprovalLock("review-123");
    }

    @Test
    void rejectsPayerApprovalForNonBankPayment() {
        when(redisService.getFraudResult("review-123")).thenReturn(reviewResult("payer@example.com"));
        when(redisService.getReviewTransaction("review-123")).thenReturn(reviewEvent("RAZORPAY"));

        var response = fraudController.submitReviewDecision(
                "review-123", "payer@example.com", request(ReviewDecision.Decision.APPROVE),
                new MockHttpServletRequest());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(redisService, never()).saveReviewDecision(any());
        verify(paymentProcessorService, never()).processManuallyApprovedPayment(any());
    }

    @Test
    void returnsTheAuthenticatedPayersPendingReviewTransactions() {
        PendingReviewTransactionResponse pending = PendingReviewTransactionResponse.builder()
                .transactionId("review-123")
                .payeeUpiId("receiver@upi")
                .build();
        when(redisService.getPendingReviewTransactions("payer@example.com"))
                .thenReturn(List.of(pending));

        var response = fraudController.getPendingReviewTransactions(
                "payer@example.com", new MockHttpServletRequest());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(pending), response.getBody());
    }

    @Test
    void requiresAuthenticationToReadPendingReviewTransactions() {
        var response = fraudController.getPendingReviewTransactions(
                null, new MockHttpServletRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(redisService, never()).getPendingReviewTransactions(any());
    }

    private FraudResult reviewResult(String payer) {
        return FraudResult.builder()
                .transactionId("review-123")
                .userId(payer)
                .status(FraudResult.FraudStatus.REVIEW)
                .build();
    }

    private ReviewDecisionRequest request(ReviewDecision.Decision decision) {
        ReviewDecisionRequest request = new ReviewDecisionRequest();
        request.setDecision(decision);
        return request;
    }

    private TransactionEvent reviewEvent(String paymentMode) {
        return TransactionEvent.builder()
                .transactionId("review-123")
                .payerUserId("payer@example.com")
                .userId("payer@example.com")
                .payeeUpiId("receiver@upi")
                .paymentMode(paymentMode)
                .build();
    }
}
