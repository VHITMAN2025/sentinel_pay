package com.fraud.detection.payment;

import com.fraud.common.dto.FraudResult;
import com.fraud.common.dto.TransactionEvent;
import com.fraud.detection.client.BankServiceClient;
import com.fraud.detection.model.PaymentRecord;
import com.fraud.detection.model.PaymentStatus;
import com.fraud.detection.model.ReviewDecision;
import com.fraud.detection.producer.NotificationProducer;
import com.fraud.detection.service.RazorpayRefundService;
import com.fraud.detection.service.RedisService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentProcessorServiceManualApprovalTest {

    @Mock private BankServiceClient bankServiceClient;
    @Mock private RazorpayService razorpayService;
    @Mock private RazorpayRefundService razorpayRefundService;
    @Mock private RedisService redisService;
    @Mock private MeterRegistry meterRegistry;
    @Mock private NotificationProducer notificationProducer;
    @Mock private Counter counter;

    @InjectMocks
    private PaymentProcessorService paymentProcessorService;

    @Test
    void manualReviewApprovalRunsTheSimulatedBankDebitAndCredit() {
        TransactionEvent event = reviewEvent();
        AtomicReference<PaymentRecord> latestRecord = new AtomicReference<>();
        when(redisService.getFraudResult("review-123")).thenReturn(reviewResult());
        when(redisService.getReviewDecision("review-123")).thenReturn(approvedDecision());
        when(redisService.getPaymentRecord("review-123")).thenAnswer(invocation -> latestRecord.get());
        when(redisService.paymentRecordExists("review-123")).thenReturn(false);
        doAnswer(invocation -> {
            latestRecord.set(invocation.getArgument(0));
            return null;
        }).when(redisService).savePaymentRecord(any(PaymentRecord.class));
        when(bankServiceClient.resolveUserIdByUpiId("receiver@upi")).thenReturn("receiver@example.com");
        when(bankServiceClient.debit("payer@example.com", new BigDecimal("500.00"), "review-123"))
                .thenReturn(true);
        when(bankServiceClient.credit("receiver@example.com", new BigDecimal("500.00"), "review-123"))
                .thenReturn(true);
        when(meterRegistry.counter(anyString())).thenReturn(counter);

        PaymentRecord result = paymentProcessorService.processManuallyApprovedPayment(event);

        assertEquals(PaymentStatus.SUCCESS, result.getPaymentStatus());
        assertTrue(result.isPayerApprovedReview());
        verify(bankServiceClient).debit("payer@example.com", new BigDecimal("500.00"), "review-123");
        verify(bankServiceClient).credit("receiver@example.com", new BigDecimal("500.00"), "review-123");
    }

    @Test
    void automaticProcessingStillRefusesReviewVerdicts() {
        when(redisService.getFraudResult("review-123")).thenReturn(reviewResult());

        paymentProcessorService.processPayment(reviewEvent());

        verify(bankServiceClient, never()).debit(anyString(), any(), anyString());
        verify(bankServiceClient, never()).credit(anyString(), any(), anyString());
    }

    private TransactionEvent reviewEvent() {
        return TransactionEvent.builder()
                .transactionId("review-123")
                .userId("payer@example.com")
                .payerUserId("payer@example.com")
                .payeeUpiId("receiver@upi")
                .paymentMode("BANK")
                .amount(new BigDecimal("500.00"))
                .build();
    }

    private FraudResult reviewResult() {
        return FraudResult.builder()
                .transactionId("review-123")
                .userId("payer@example.com")
                .status(FraudResult.FraudStatus.REVIEW)
                .build();
    }

    private ReviewDecision approvedDecision() {
        return ReviewDecision.builder()
                .transactionId("review-123")
                .userId("payer@example.com")
                .decision(ReviewDecision.Decision.APPROVE)
                .build();
    }
}
