package com.fraud.detection.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewDecision implements Serializable {

    private static final long serialVersionUID = 1L;

    private String transactionId;
    private String userId;
    private Decision decision;
    private LocalDateTime decidedAt;
    private PaymentStatus paymentStatus;
    private BigDecimal amount;
    private String payeeUpiId;

    public enum Decision {
        APPROVE,
        CONFIRM,
        CANCEL
    }
}
