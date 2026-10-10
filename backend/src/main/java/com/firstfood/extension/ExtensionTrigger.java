package com.firstfood.extension;

/** What caused an extension (rules.md Rule 13.6: trigger/source). */
public enum ExtensionTrigger {
    /** A provider account applied it; the event names that account. */
    STAFF,
    /** The engine itself (a background job, Phase 10); there is no account. */
    SYSTEM
}
