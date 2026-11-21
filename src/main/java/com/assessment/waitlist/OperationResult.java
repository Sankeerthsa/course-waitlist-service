package com.assessment.waitlist;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record OperationResult(
        OperationStatus status,
        String learnerId,
        Optional<String> promotedLearnerId,
        List<String> skippedIneligibleLearners
) {
    public OperationResult {
        Objects.requireNonNull(status, "status must not be null");
        if (learnerId == null || learnerId.isBlank()) {
            throw new IllegalArgumentException("learnerId must not be null or blank");
        }
        promotedLearnerId = promotedLearnerId == null ? Optional.empty() : promotedLearnerId;
        skippedIneligibleLearners = skippedIneligibleLearners == null
                ? List.of()
                : List.copyOf(skippedIneligibleLearners);
    }

    public static OperationResult of(OperationStatus status, String learnerId) {
        return new OperationResult(status, learnerId, Optional.empty(), List.of());
    }

    public static OperationResult dropWithPromotion(
            OperationStatus status,
            String learnerId,
            String promotedLearnerId,
            List<String> skippedIneligibleLearners
    ) {
        return new OperationResult(
                status,
                learnerId,
                Optional.ofNullable(promotedLearnerId),
                skippedIneligibleLearners
        );
    }
}