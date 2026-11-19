package com.fraud.bank.dto;

import com.fraud.bank.entity.TransactionType;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Value
@Builder
public class BankTransactionResponse {

    String transactionId;
    TransactionType type;
    BigDecimal amount;
    BigDecimal balanceAfter;
    LocalDateTime appliedAt;
}
