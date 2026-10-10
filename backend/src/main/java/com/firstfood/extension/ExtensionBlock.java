package com.firstfood.extension;

/** Why a subscription cannot be extended at all right now. Absent (null) = it can; there may still be nothing to add. */
public enum ExtensionBlock {
    /** MEAL subscriptions are counted by meals; absence does not extend a fixed quantity (Phase 6 decision 2). */
    NOT_SUPPORTED_FOR_MEAL,
    /** The extension terms it was sold under say no. */
    NOT_ALLOWED_BY_POLICY,
    /** Cancelled (final). */
    SUBSCRIPTION_CANCELLED,
    /** Expired (final). */
    SUBSCRIPTION_EXPIRED,
    /** A renewal exists; extending the old subscription would overlap it. */
    ALREADY_RENEWED
}
