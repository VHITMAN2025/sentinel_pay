package com.fraud.detection.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingReviewTransactionResponse {

    private String transactionId;
    private BigDecimal amount;
    private String payeeUpiId;
    private String paymentMode;
    private String location;
    private String device;
    private String merchantCategory;
    private Double confidenceScore;
    private String detectionLayer;
    private String reason;
    private LocalDateTime submittedAt;
}
