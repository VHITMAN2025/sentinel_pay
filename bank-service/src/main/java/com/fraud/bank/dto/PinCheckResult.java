package com.fraud.bank.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PinCheckResult {
    private boolean valid;
    private boolean locked;
    private int remainingAttempts;
    private Long lockedHoursRemaining;
    private String message;
}
