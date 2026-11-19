package com.fraud.bank.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangePinRequest {

    @NotBlank(message = "Current UPI PIN is required")
    private String currentPin;

    @NotBlank(message = "New UPI PIN is required")
    @Pattern(regexp = "^[0-9]{6}$", message = "New UPI PIN must be exactly 6 digits")
    private String newPin;
}
