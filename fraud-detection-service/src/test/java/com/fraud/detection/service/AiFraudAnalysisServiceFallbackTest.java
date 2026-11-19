package com.fraud.detection.service;

import com.fraud.common.dto.FraudResult;
import com.fraud.common.dto.TransactionEvent;
import com.fraud.detection.config.FraudRulesProperties;
import com.fraud.detection.model.UserTransactionHistory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiFraudAnalysisServiceFallbackTest {

    @Test
    void usesFastLocalScoresWhenGeminiIsNotConfigured() {
        AiFraudAnalysisService service = configuredService();

        FraudResult small = service.analyze(event("small", "500.00"), null);
        FraudResult larger = service.analyze(event("larger", "5000.00"), null);

        assertEquals(FraudResult.FraudStatus.SAFE, small.getStatus());
        assertEquals("LOCAL_FALLBACK", small.getDetectionLayer());
        assertEquals(FraudResult.FraudStatus.SAFE, larger.getStatus());
        assertNotEquals(small.getConfidenceScore(), larger.getConfidenceScore());
        assertTrue(larger.getConfidenceScore() > small.getConfidenceScore());
    }

    @Test
    void sendsLargeBehaviouralSpikeToReviewWithoutGemini() {
        AiFraudAnalysisService service = configuredService();
        UserTransactionHistory history = UserTransactionHistory.builder()
                .userId("payer@example.com")
                .recentTransactions(List.of(event("prior", "300.00")))
                .build();

        FraudResult result = service.analyze(event("spike", "3500.00"), history);

        assertEquals(FraudResult.FraudStatus.REVIEW, result.getStatus());
        assertTrue(result.getConfidenceScore() >= 0.4);
        assertTrue(result.getReason().contains("previous payment"));
    }

    private AiFraudAnalysisService configuredService() {
        AiFraudAnalysisService service = new AiFraudAnalysisService();
        FraudRulesProperties rules = new FraudRulesProperties();
        ReflectionTestUtils.setField(service, "fraudRules", rules);
        ReflectionTestUtils.setField(service, "geminiApiKey", "not-configured");
        return service;
    }

    private TransactionEvent event(String id, String amount) {
        return TransactionEvent.builder()
                .transactionId(id)
                .userId("payer@example.com")
                .amount(new BigDecimal(amount))
                .device("web")
                .location("Chennai")
                .timestamp(LocalDateTime.now())
                .build();
    }
}
