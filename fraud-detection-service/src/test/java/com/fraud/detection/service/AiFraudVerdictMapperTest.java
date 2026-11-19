package com.fraud.detection.service;

import com.fraud.common.dto.FraudResult.FraudStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiFraudVerdictMapperTest {

    @Test
    void reportedFraudProbabilityIsNotInvertedBasedOnTheVerdict() {
        assertEquals(0.85, AiFraudVerdictMapper.toFraudProbability(0.85), 0.0001);
        assertEquals(FraudStatus.FRAUD, AiFraudVerdictMapper.toStatus(0.85, 0.4, 0.6));
    }

    @Test
    void lowFraudProbabilityIsSafeEvenForTypicalRetailAmounts() {
        assertEquals(0.08, AiFraudVerdictMapper.toFraudProbability(0.08), 0.0001);
        assertEquals(FraudStatus.SAFE, AiFraudVerdictMapper.toStatus(0.08, 0.4, 0.6));
    }

    @Test
    void borderlineProbabilityIsReview() {
        assertEquals(FraudStatus.REVIEW, AiFraudVerdictMapper.toStatus(0.4, 0.4, 0.6));
        assertEquals(FraudStatus.REVIEW, AiFraudVerdictMapper.toStatus(0.6, 0.4, 0.6));
    }

    @Test
    void highFraudProbabilityIsFraud() {
        assertEquals(0.82, AiFraudVerdictMapper.toFraudProbability(0.82), 0.0001);
        assertEquals(FraudStatus.FRAUD, AiFraudVerdictMapper.toStatus(0.61, 0.4, 0.6));
    }
}
