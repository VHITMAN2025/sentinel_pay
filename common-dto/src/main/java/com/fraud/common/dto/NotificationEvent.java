package com.fraud.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent implements Serializable {

    private String transactionId;

    private String userEmail;     // payer
    private String payeeEmail;    // receiver
    private String payeeUpiId;    // receiver UPI

    private BigDecimal amount;

    private String status;        // SUCCESS / FAILED / FRAUD
    private String message;
}
