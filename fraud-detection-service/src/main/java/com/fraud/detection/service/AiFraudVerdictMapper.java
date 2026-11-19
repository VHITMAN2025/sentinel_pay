package com.fraud.detection.service;

import com.fraud.common.dto.FraudResult.FraudStatus;

/**
 * Maps an explicitly reported P(fraud) onto SAFE / REVIEW / FRAUD.
 */
final class AiFraudVerdictMapper {

    private AiFraudVerdictMapper() {
    }

    static double toFraudProbability(double reported) {
        return round(clamp(reported));
    }

    static FraudStatus toStatus(double fraudProbability, double reviewMin, double reviewMax) {
        if (fraudProbability > reviewMax) {
            return FraudStatus.FRAUD;
        }
        if (fraudProbability >= reviewMin) {
            return FraudStatus.REVIEW;
        }
        return FraudStatus.SAFE;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
