package com.firstfood.extension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One applied extension as the customer sees it: staff account ids are never exposed to customers. */
public record MyExtensionEventView(
        UUID id,
        int sequenceNo,
        ExtensionKind kind,
        ExtensionTrigger triggerSource,
        int eligibleAbsenceDays,
        int appliedExtensionDays,
        int overAppliedDays,
        LocalDate previousExpiryDate,
        LocalDate newExpiryDate,
        LocalDate maximumExpiryDate,
        boolean capped,
        Instant createdAt) {

    static MyExtensionEventView from(ExtensionEvent e) {
        return new MyExtensionEventView(e.getId(), e.getSequenceNo(), e.getKind(), e.getTriggerSource(),
                e.getEligibleAbsenceDays(), e.getAppliedExtensionDays(), e.getOverAppliedDays(), e.getPreviousExpiryDate(),
                e.getNewExpiryDate(), e.getMaximumExpiryDate(), e.isCapped(), e.getCreatedAt());
    }
}
