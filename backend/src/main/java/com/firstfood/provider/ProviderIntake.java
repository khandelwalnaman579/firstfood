package com.firstfood.provider;

/**
 * Whether a provider currently takes on new customers (status + intake switch) and how many
 * ACTIVE subscriptions it allows at most ({@code null} = no limit; enforced by the subscription
 * module when a subscription is sold).
 */
public record ProviderIntake(ProviderStatus status, boolean acceptingNewCustomers, Integer maxActiveSubscriptions) {

    public boolean closed() {
        return status == ProviderStatus.CLOSED;
    }
}
