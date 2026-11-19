package com.fraud.detection.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayeeStats {
    private String payeeUpiId;

    @Builder.Default
    private int totalReceivedCount = 0;

    @Builder.Default
    private BigDecimal totalReceivedAmount = BigDecimal.ZERO;

    private Long firstSeenTimestamp;
    private Long lastSeenTimestamp;

    @Builder.Default
    private List<Long> recentTimestamps = new ArrayList<>();
}
