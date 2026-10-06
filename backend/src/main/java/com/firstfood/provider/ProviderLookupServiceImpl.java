package com.firstfood.provider;

import com.firstfood.provideraccess.ProviderNotFoundException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderLookupServiceImpl implements ProviderLookupService {

    private final FoodProviderRepository repository;

    public ProviderLookupServiceImpl(FoodProviderRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProviderIntake lockForIntake(UUID providerId) {
        FoodProvider provider = repository.findByIdForUpdate(providerId).orElseThrow(ProviderNotFoundException::new);
        return new ProviderIntake(provider.getStatus(), provider.isAcceptingNewCustomers(),
                provider.getMaxActiveSubscriptions());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> findNames(Collection<UUID> providerIds) {
        if (providerIds == null || providerIds.isEmpty()) {
            return Map.of();
        }
        return repository.findAllById(providerIds).stream()
                .collect(Collectors.toMap(FoodProvider::getId, FoodProvider::getName));
    }
}
