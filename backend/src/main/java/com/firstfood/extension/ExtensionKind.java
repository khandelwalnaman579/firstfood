package com.firstfood.extension;

/** What an {@link ExtensionEvent} records. Both kinds share one gap-free sequence per subscription. */
public enum ExtensionKind {
    /** Days were added to the expiry. */
    EXTENSION_APPLIED,
    /**
     * A reconciliation report: an absence that was counted has since been corrected, so more days are applied than
     * are now eligible. The expiry is never reduced; nothing earlier is edited.
     */
    OVER_APPLIED
}
