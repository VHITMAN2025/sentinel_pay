package com.fraud.detection.service;

import com.fraud.common.dto.FraudResult;
import com.fraud.common.dto.TransactionEvent;
import com.fraud.detection.config.FraudRulesProperties;
import com.fraud.detection.model.UserTransactionHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudDetectionServiceSoftSignalsTest {

    @Mock private FraudRulesProperties fraudRules;
    @Mock private RedisService redisService;
    @Mock private AiFraudAnalysisService aiFraudAnalysisService;

    @InjectMocks
    private FraudDetectionService fraudDetectionService;

    @Test
    void velocityLimitIsHardFraud() {
        TransactionEvent event = event("chennai", new BigDecimal("500.00"));
        UserTransactionHistory history = historyWithPriorTransactions(10, "chennai");
        when(redisService.getHistory("payer@example.com")).thenReturn(history);
        when(fraudRules.getHighAmountThreshold()).thenReturn(new BigDecimal("10000"));
        when(fraudRules.getMaxTransactionsPerDay()).thenReturn(10);

        FraudResult result = fraudDetectionService.analyzeTransaction(event);

        assertEquals(FraudResult.FraudStatus.FRAUD, result.getStatus());
        assertEquals("RULE_BASED", result.getDetectionLayer());
        verify(redisService).recordTransaction(event);
        verify(aiFraudAnalysisService, never()).analyze(event, history);
    }

    @Test
    void newLocationIsHardFraud() {
        TransactionEvent event = event("new-city", new BigDecimal("500.00"));
        UserTransactionHistory history = historyWithPriorTransactions(1, "known-city");
        when(redisService.getHistory("payer@example.com")).thenReturn(history);
        when(fraudRules.getHighAmountThreshold()).thenReturn(new BigDecimal("10000"));

        FraudResult result = fraudDetectionService.analyzeTransaction(event);

        assertEquals(FraudResult.FraudStatus.FRAUD, result.getStatus());
        assertEquals("RULE_BASED", result.getDetectionLayer());
        verify(aiFraudAnalysisService, never()).analyze(event, history);
    }

    @Test
    void amountAboveTenThousandRequiresPayerReview() {
        TransactionEvent event = event("chennai", new BigDecimal("10000.01"));
        when(redisService.getHistory("payer@example.com")).thenReturn(null);
        when(fraudRules.getHighAmountThreshold()).thenReturn(new BigDecimal("10000"));

        FraudResult result = fraudDetectionService.analyzeTransaction(event);

        assertEquals(FraudResult.FraudStatus.REVIEW, result.getStatus());
        assertEquals("RULE_BASED", result.getDetectionLayer());
        verify(aiFraudAnalysisService, never()).analyze(event, null);
    }

    @Test
    void missingLocationIsNotTreatedAsAnUnknownLocationFraudSignal() {
        TransactionEvent event = event(null, new BigDecimal("500.00"));
        UserTransactionHistory priorHistory = historyWithPriorTransactions(1, "known-city");
        UserTransactionHistory historyWithCurrentAttempt = historyWithPriorTransactions(1, "known-city");
        when(redisService.getHistory("payer@example.com")).thenReturn(priorHistory);
        when(redisService.recordTransaction(event)).thenReturn(historyWithCurrentAttempt);
        when(fraudRules.getHighAmountThreshold()).thenReturn(new BigDecimal("10000"));
        when(fraudRules.getMaxTransactionsPerDay()).thenReturn(10);
        when(aiFraudAnalysisService.analyze(event, priorHistory)).thenReturn(
                FraudResult.builder()
                        .transactionId(event.getTransactionId())
                        .userId(event.getUserId())
                        .status(FraudResult.FraudStatus.SAFE)
                        .build());

        FraudResult result = fraudDetectionService.analyzeTransaction(event);

        assertEquals(FraudResult.FraudStatus.SAFE, result.getStatus());
        verify(aiFraudAnalysisService).analyze(event, priorHistory);
    }

    @Test
    void fiveHundredRupeePaymentWithNoHistoryGoesToAi() {
        TransactionEvent event = event("chennai", new BigDecimal("500.00"));
        when(redisService.getHistory("payer@example.com")).thenReturn(null);
        when(redisService.recordTransaction(event)).thenReturn(null);
        when(fraudRules.getHighAmountThreshold()).thenReturn(new BigDecimal("10000"));
        when(fraudRules.getMaxTransactionsPerDay()).thenReturn(10);
        when(aiFraudAnalysisService.analyze(event, null)).thenReturn(
                FraudResult.builder()
                        .transactionId(event.getTransactionId())
                        .userId(event.getUserId())
                        .status(FraudResult.FraudStatus.SAFE)
                        .confidenceScore(0.08)
                        .detectionLayer("AI")
                        .build());

        FraudResult result = fraudDetectionService.analyzeTransaction(event);

        assertEquals(FraudResult.FraudStatus.SAFE, result.getStatus());
        verify(aiFraudAnalysisService).analyze(event, null);
    }

    @Test
    void sameCityDifferentCaseIsNotUnknownLocation() {
        TransactionEvent event = event("Chennai", new BigDecimal("500.00"));
        UserTransactionHistory history = historyWithPriorTransactions(1, "chennai");
        UserTransactionHistory historyWithCurrent = historyWithPriorTransactions(1, "chennai");
        when(redisService.getHistory("payer@example.com")).thenReturn(history);
        when(redisService.recordTransaction(event)).thenReturn(historyWithCurrent);
        when(fraudRules.getHighAmountThreshold()).thenReturn(new BigDecimal("10000"));
        when(fraudRules.getMaxTransactionsPerDay()).thenReturn(10);
        when(aiFraudAnalysisService.analyze(event, history)).thenReturn(
                FraudResult.builder()
                        .transactionId(event.getTransactionId())
                        .userId(event.getUserId())
                        .status(FraudResult.FraudStatus.SAFE)
                        .confidenceScore(0.1)
                        .detectionLayer("AI")
                        .build());

        FraudResult result = fraudDetectionService.analyzeTransaction(event);

        assertEquals(FraudResult.FraudStatus.SAFE, result.getStatus());
        verify(aiFraudAnalysisService).analyze(event, history);
    }

    @Test
    void deviceChangeKeepsReviewWhenAiSaysSafe() {
        TransactionEvent event = event("chennai", new BigDecimal("500.00"));
        UserTransactionHistory priorHistory = historyWithPriorTransactions(1, "chennai");
        priorHistory.getRecentTransactions().get(0).setDevice("laptop");
        UserTransactionHistory historyWithCurrent = UserTransactionHistory.builder()
                .userId("payer@example.com")
                .recentTransactions(List.of(priorHistory.getRecentTransactions().get(0), event))
                .knownLocations(new LinkedHashSet<>(List.of("chennai")))
                .build();
        when(redisService.getHistory("payer@example.com")).thenReturn(priorHistory);
        when(redisService.recordTransaction(event)).thenReturn(historyWithCurrent);
        when(fraudRules.getHighAmountThreshold()).thenReturn(new BigDecimal("10000"));
        when(fraudRules.getMaxTransactionsPerDay()).thenReturn(10);
        when(aiFraudAnalysisService.analyze(event, priorHistory)).thenReturn(
                FraudResult.builder()
                        .transactionId(event.getTransactionId())
                        .userId(event.getUserId())
                        .status(FraudResult.FraudStatus.SAFE)
                        .confidenceScore(0.1)
                        .detectionLayer("AI")
                        .build());

        FraudResult result = fraudDetectionService.analyzeTransaction(event);

        assertEquals(FraudResult.FraudStatus.REVIEW, result.getStatus());
        verify(aiFraudAnalysisService).analyze(event, priorHistory);
    }

    private UserTransactionHistory historyWithPriorTransactions(int count, String location) {
        List<TransactionEvent> transactions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            transactions.add(event(location, new BigDecimal("1.00")));
        }
        return UserTransactionHistory.builder()
                .userId("payer@example.com")
                .recentTransactions(transactions)
                .knownLocations(new LinkedHashSet<>(List.of(location)))
                .build();
    }

    private TransactionEvent event(String location, BigDecimal amount) {
        return TransactionEvent.builder()
                .transactionId("test-transaction")
                .userId("payer@example.com")
                .payerUserId("payer@example.com")
                .amount(amount)
                .location(location)
                .device("mobile")
                .build();
    }
}
