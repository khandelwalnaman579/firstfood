package com.firstfood.identity;

import com.firstfood.identity.dto.PhonePattern;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountLookupServiceImpl implements AccountLookupService {

    private static final Pattern PHONE = Pattern.compile(PhonePattern.REGEX);

    private final UserAccountRepository repository;

    public AccountLookupServiceImpl(UserAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findActiveAccountIdByPhone(String phone) {
        if (phone == null) {
            return Optional.empty();
        }
        String normalized = phone.replaceAll("[\\s-]", "");
        if (!PHONE.matcher(normalized).matches()) {
            return Optional.empty();
        }
        // Phones are stored exactly as verified at login, with or without a leading '+'.
        String alternate = normalized.startsWith("+") ? normalized.substring(1) : "+" + normalized;
        return repository.findByPhone(normalized)
                .or(() -> repository.findByPhone(alternate))
                .filter(account -> account.getStatus() == AccountStatus.ACTIVE)
                .map(UserAccount::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> findPhonesByIds(Collection<UUID> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Map.of();
        }
        return repository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getPhone));
    }
}
