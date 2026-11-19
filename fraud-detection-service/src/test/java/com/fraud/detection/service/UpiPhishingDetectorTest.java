package com.fraud.detection.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UpiPhishingDetectorTest {

    private UpiPhishingDetector detector;

    @BeforeEach
    void setUp() {
        detector = new UpiPhishingDetector();
    }

    @Test
    void detectsBrandTyposquattingSingleEdit() {
        // "amazonn" is edit distance 1 from "amazon"
        UpiPhishingDetector.PhishingCheckResult result = detector.checkPayeeUpi("amazonn@upi");
        assertTrue(result.isFlagged());
        assertEquals("amazon", result.getMatchedBrand());
        assertTrue(result.getReason().contains("Brand Typosquatting Alert"));
    }

    @Test
    void detectsBrandTyposquattingPhonepe() {
        // "phonpe" is edit distance 1 from "phonepe"
        UpiPhishingDetector.PhishingCheckResult result = detector.checkPayeeUpi("phonpe.merchant@ybl");
        assertTrue(result.isFlagged());
        assertEquals("phonepe", result.getMatchedBrand());
    }

    @Test
    void detectsBrandWithHighRiskKeywordCombination() {
        // "sbi" + "kyc"
        UpiPhishingDetector.PhishingCheckResult result = detector.checkPayeeUpi("sbi_kyc_verification@okaxis");
        assertTrue(result.isFlagged());
        assertEquals("sbi", result.getMatchedBrand());
        assertTrue(result.getReason().contains("Phishing Alert"));
    }

    @Test
    void detectsMultiplePhishingKeywords() {
        // "electricity" + "billpay" + "urgent"
        UpiPhishingDetector.PhishingCheckResult result = detector.checkPayeeUpi("electricity.billpay.urgent@oksbi");
        assertTrue(result.isFlagged());
        assertTrue(result.getReason().contains("Social Engineering Alert"));
    }

    @Test
    void allowsLegitimateUpiId() {
        UpiPhishingDetector.PhishingCheckResult result = detector.checkPayeeUpi("karthik@upi");
        assertFalse(result.isFlagged());

        UpiPhishingDetector.PhishingCheckResult result2 = detector.checkPayeeUpi("sharma.groceries@okhdfcbank");
        assertFalse(result2.isFlagged());
    }
}
