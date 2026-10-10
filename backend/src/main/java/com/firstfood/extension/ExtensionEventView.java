package com.firstfood.extension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One applied extension as provider staff see it: what was asked, what was applied, by whom and why it stopped. */
public record ExtensionEventView(
        UUID id,
        UUID subscriptionId,
        int sequenceNo,
        ExtensionKind kind,
        ExtensionTrigger triggerSource,
        /** The staff account; null for a SYSTEM event. Ids only - no phone numbers. */
        UUID createdBy,
        LocalDate evaluatedOn,
        int eligibleAbsenceDays,
        int requestedExtensionDays,
        int appliedExtensionDays,
        /** OVER_APPLIED only: applied days that are no longer eligible (never taken back); 0 otherwise. */
        int overAppliedDays,
        LocalDate previousExpiryDate,
        LocalDate newExpiryDate,
        LocalDate maximumExpiryDate,
        boolean capped,
        Instant createdAt) {

    static ExtensionEventView from(ExtensionEvent e) {
        return new ExtensionEventView(e.getId(), e.getSubscriptionId(), e.getSequenceNo(), e.getKind(), e.getTriggerSource(),
                e.getCreatedBy(), e.getEvaluatedOn(), e.getEligibleAbsenceDays(), e.getRequestedExtensionDays(),
                e.getAppliedExtensionDays(), e.getOverAppliedDays(), e.getPreviousExpiryDate(), e.getNewExpiryDate(),
                e.getMaximumExpiryDate(), e.isCapped(), e.getCreatedAt());
    }
}
