package com.firstfood.provider;

/** Whether a provider currently takes on new customers (status + intake switch). */
public record ProviderIntake(ProviderStatus status, boolean acceptingNewCustomers) {

    public boolean closed() {
        return status == ProviderStatus.CLOSED;
    }
}
