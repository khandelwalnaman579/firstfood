package com.firstfood.extension;

/**
 * The outcome of applying an extension. {@code applied} is false when there was nothing to add (no eligible days yet,
 * everything already applied, or the maximum expiry reached) - repeating a request is therefore harmless and returns
 * this instead of an error. {@code event} is the new EXTENSION_APPLIED event when {@code applied}; when not applied it is null, or the new OVER_APPLIED report if corrections left more days applied than are eligible (the expiry is unchanged).
 */
public record ApplyExtensionResult(boolean applied, ExtensionEventView event, ExtensionStatusView status) {
}
