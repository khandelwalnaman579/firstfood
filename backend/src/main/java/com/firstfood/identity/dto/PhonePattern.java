package com.firstfood.identity.dto;

/**
 * Loosely E.164-ish: optional leading +, 10-15 digits total, first digit
 * non-zero. Deliberately not stricter (e.g. India-only) since the pilot
 * numbers used for local testing may not always be Indian mobile
 * numbers, and full E.164 validation belongs in a dedicated library if
 * this needs to get precise later.
 */
public final class PhonePattern {

    public static final String REGEX = "^\\+?[1-9]\\d{9,14}$";

    private PhonePattern() {
    }
}
