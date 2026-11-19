package com.fraud.detection.model;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReviewDecisionRequest {

    @NotNull(message = "decision is required")
    private ReviewDecision.Decision decision;
}
