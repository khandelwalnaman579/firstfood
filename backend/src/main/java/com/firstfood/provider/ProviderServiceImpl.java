package com.firstfood.provider;

import com.firstfood.provider.dto.CreateProviderRequest;
import com.firstfood.provider.dto.ProviderResponse;
import com.firstfood.provider.dto.UpdateProviderRequest;
import com.firstfood.provider.error.InvalidProviderRequestException;
import com.firstfood.provider.error.ProviderClosedException;
import com.firstfood.provideraccess.ProviderAccessService;
import com.firstfood.provideraccess.ProviderNotFoundException;
import com.firstfood.provideraccess.ProviderPermission;
import com.firstfood.provideraccess.ProviderRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderServiceImpl implements ProviderService {

    private final FoodProviderRepository providerRepository;
    private final ProviderAccessService accessService;

    public ProviderServiceImpl(FoodProviderRepository providerRepository, ProviderAccessService accessService) {
        this.providerRepository = providerRepository;
        this.accessService = accessService;
    }

    @Override
    @Transactional
    public ProviderResponse create(UUID accountId, CreateProviderRequest request) {
        FoodProvider provider = new FoodProvider(
                request.name().trim(),
                request.providerType(),
                request.addressLine().trim(),
                request.locality().trim(),
                request.city().trim(),
                request.maxActiveSubscriptions());
        provider.setDescription(blankToNull(request.description()));
        provider.setPincode(request.pincode());
        provider.setContactPhone(request.contactPhone());

        providerRepository.saveAndFlush(provider);
        // Same transaction: the provider and its mandatory OWNER row commit
        // together or not at all (freeze #4).
        accessService.assignOwner(provider.getId(), accountId);

        return toResponse(provider, ProviderRole.OWNER);
    }

    @Override
    @Transactional(readOnly = true)
    public ProviderResponse get(UUID accountId, UUID providerId) {
        ProviderRole role = accessService.requirePermission(accountId, providerId, ProviderPermission.PROVIDER_VIEW);
        FoodProvider provider = providerRepository.findById(providerId).orElseThrow(ProviderNotFoundException::new);
        return toResponse(provider, role);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProviderResponse> list(UUID accountId) {
        Map<UUID, ProviderRole> roles = accessService.rolesFor(accountId);
        if (roles.isEmpty()) {
            return List.of();
        }
        roles.values().removeIf(role -> !role.grants(ProviderPermission.PROVIDER_VIEW));
        if (roles.isEmpty()) {
            return List.of();
        }
        return providerRepository.findByIdInOrderByCreatedAtDesc(roles.keySet()).stream()
                .map(provider -> toResponse(provider, roles.get(provider.getId())))
                .toList();
    }

    @Override
    @Transactional
    public ProviderResponse update(UUID accountId, UUID providerId, UpdateProviderRequest request) {
        ProviderRole role = accessService.requirePermission(accountId, providerId, ProviderPermission.PROVIDER_EDIT);
        FoodProvider provider = providerRepository.findByIdForUpdate(providerId)
                .orElseThrow(ProviderNotFoundException::new);

        if (provider.getStatus() == ProviderStatus.CLOSED) {
            throw new ProviderClosedException();
        }
        validateRequestConsistency(request);

        if (request.status() == ProviderStatus.CLOSED && request.status() != provider.getStatus()) {

            accessService.requirePermission(accountId, providerId, ProviderPermission.PROVIDER_CLOSE);
        }

        applyProfile(provider, request);
        applyCapacity(provider, request);
        applyStatus(provider, request, accountId);

        providerRepository.saveAndFlush(provider);
        return toResponse(provider, role);
    }

    private void validateRequestConsistency(UpdateProviderRequest request) {
        if (Boolean.TRUE.equals(request.unlimitedCapacity()) && request.maxActiveSubscriptions() != null) {
            throw new InvalidProviderRequestException(
                    "Send either maxActiveSubscriptions or unlimitedCapacity=true, not both.");
        }
        boolean closing = request.status() == ProviderStatus.CLOSED;
        if (request.closureReason() != null && !closing) {
            throw new InvalidProviderRequestException("closureReason can only be sent when closing a provider.");
        }
    }

    private void applyProfile(FoodProvider provider, UpdateProviderRequest request) {
        if (request.name() != null) {
            provider.setName(request.name().trim());
        }
        if (request.providerType() != null) {
            provider.setProviderType(request.providerType());
        }
        if (request.description() != null) {
            provider.setDescription(blankToNull(request.description()));
        }
        if (request.addressLine() != null) {
            provider.setAddressLine(request.addressLine().trim());
        }
        if (request.locality() != null) {
            provider.setLocality(request.locality().trim());
        }
        if (request.city() != null) {
            provider.setCity(request.city().trim());
        }
        if (request.pincode() != null) {
            provider.setPincode(request.pincode());
        }
        if (request.contactPhone() != null) {
            provider.setContactPhone(request.contactPhone());
        }
    }

    /** Stores/validates capacity only - subscription-side enforcement is a later phase. */
    private void applyCapacity(FoodProvider provider, UpdateProviderRequest request) {
        if (Boolean.TRUE.equals(request.unlimitedCapacity())) {
            provider.setMaxActiveSubscriptions(null);
        } else if (request.maxActiveSubscriptions() != null) {
            provider.setMaxActiveSubscriptions(request.maxActiveSubscriptions());
        }
    }

    private void applyStatus(FoodProvider provider, UpdateProviderRequest request, UUID actorAccountId) {
        ProviderStatus target = request.status();
        boolean changing = target != null && target != provider.getStatus();

        if (changing && target == ProviderStatus.CLOSED) {
            provider.close(actorAccountId, blankToNull(request.closureReason()),
                    request.acceptingNewCustomers(), Instant.now());
        } else if (changing) {
            provider.transitionTo(target, request.acceptingNewCustomers());
        } else if (request.acceptingNewCustomers() != null) {
            provider.setIntake(request.acceptingNewCustomers());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ProviderResponse toResponse(FoodProvider p, ProviderRole myRole) {
        return new ProviderResponse(
                p.getId(), p.getName(), p.getProviderType(), p.getDescription(), p.getAddressLine(),
                p.getLocality(), p.getCity(), p.getPincode(), p.getContactPhone(), p.getStatus(),
                p.isAcceptingNewCustomers(), p.getMaxActiveSubscriptions(), myRole, p.getCreatedAt(),
                p.getUpdatedAt(), p.getClosedAt(), p.getClosedByAccountId(), p.getClosureReason(),
                myRole.permissions());
    }
}
