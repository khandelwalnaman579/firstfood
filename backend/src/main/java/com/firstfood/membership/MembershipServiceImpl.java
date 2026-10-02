package com.firstfood.membership;

import com.firstfood.identity.AccountLookupService;
import com.firstfood.provider.ProviderIntake;
import com.firstfood.provider.ProviderLookupService;
import com.firstfood.provideraccess.ProviderAccessService;
import com.firstfood.provideraccess.ProviderPermission;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipServiceImpl implements MembershipService {

    private final ProviderAccessService accessService;
    private final ProviderLookupService providerLookup;
    private final AccountLookupService accountLookup;
    private final PersonProvisioner provisioner;
    private final PersonRepository personRepository;
    private final ProviderMembershipRepository membershipRepository;

    public MembershipServiceImpl(
            ProviderAccessService accessService,
            ProviderLookupService providerLookup,
            AccountLookupService accountLookup,
            PersonProvisioner provisioner,
            PersonRepository personRepository,
            ProviderMembershipRepository membershipRepository) {
        this.accessService = accessService;
        this.providerLookup = providerLookup;
        this.accountLookup = accountLookup;
        this.provisioner = provisioner;
        this.personRepository = personRepository;
        this.membershipRepository = membershipRepository;
    }

    @Override
    @Transactional
    public CustomerView addCustomer(UUID actorAccountId, UUID providerId, String phone, String fullName) {
        // Authorize BEFORE locking so outsiders can never hold or contend for a provider's row lock.
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.MEMBERSHIP_MANAGE);
        // Serializes concurrent changes for this provider and gives a stable status/intake view.
        ProviderIntake intake = providerLookup.lockForIntake(providerId);
        if (intake.closed()) {
            throw MembershipException.providerClosed();
        }
        if (!intake.acceptingNewCustomers()) {
            throw MembershipException.providerNotAccepting();
        }

        UUID customerAccountId = accountLookup.findActiveAccountIdByPhone(phone == null ? "" : phone.trim())
                .orElseThrow(MembershipException::targetAccountNotFound);
        Person person = provisioner.ensurePrimary(customerAccountId, fullName);

        if (membershipRepository
                .findByProviderIdAndPersonIdAndStatus(providerId, person.getId(), MembershipStatus.ACTIVE)
                .isPresent()) {
            throw MembershipException.alreadyActive();
        }
        ProviderMembership membership = new ProviderMembership(providerId, person.getId(), actorAccountId,
                Instant.now());
        try {
            membership = membershipRepository.saveAndFlush(membership);
        } catch (DataIntegrityViolationException e) {
            // Backstop for the partial unique index; the provider lock normally prevents reaching it.
            throw MembershipException.alreadyActive();
        }
        return toView(membership, person, phoneOf(person));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerView> listCustomers(UUID actorAccountId, UUID providerId, boolean includeInactive) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.MEMBERSHIP_VIEW);
        List<ProviderMembership> memberships = includeInactive
                ? membershipRepository.findByProviderIdOrderByJoinedAtDesc(providerId)
                : membershipRepository.findByProviderIdAndStatusOrderByJoinedAtDesc(providerId,
                        MembershipStatus.ACTIVE);
        return toViews(memberships);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerView getCustomer(UUID actorAccountId, UUID providerId, UUID membershipId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.MEMBERSHIP_VIEW);
        ProviderMembership membership = membershipRepository.findByIdAndProviderId(membershipId, providerId)
                .orElseThrow(MembershipException::membershipNotFound);
        return toViews(List.of(membership)).get(0);
    }

    @Override
    @Transactional
    public CustomerView deactivate(UUID actorAccountId, UUID providerId, UUID membershipId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.MEMBERSHIP_MANAGE);
        ProviderIntake intake = providerLookup.lockForIntake(providerId);
        if (intake.closed()) {
            throw MembershipException.providerClosed();
        }
        ProviderMembership membership = membershipRepository.findByIdAndProviderId(membershipId, providerId)
                .orElseThrow(MembershipException::membershipNotFound);
        if (!membership.isActive()) {
            throw MembershipException.alreadyInactive();
        }
        Instant leftAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        membership.deactivate(actorAccountId, leftAt);
        membership = membershipRepository.saveAndFlush(membership);
        return toViews(List.of(membership)).get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MyMembershipView> listMine(UUID accountId) {
        Set<UUID> personIds = personRepository.findIdsByUserAccountId(accountId);
        if (personIds.isEmpty()) {
            return List.of();
        }
        List<ProviderMembership> memberships = membershipRepository.findByPersonIdInOrderByJoinedAtDesc(personIds);
        Map<UUID, String> names = providerLookup.findNames(
                memberships.stream().map(ProviderMembership::getProviderId).collect(Collectors.toSet()));
        return memberships.stream()
                .map(m -> new MyMembershipView(m.getId(), m.getProviderId(),
                        names.getOrDefault(m.getProviderId(), "(unknown provider)"), m.getPersonId(), m.getStatus(),
                        m.getJoinedAt(), m.getLeftAt()))
                .toList();
    }

    private List<CustomerView> toViews(List<ProviderMembership> memberships) {
        if (memberships.isEmpty()) {
            return List.of();
        }
        Map<UUID, Person> persons = new HashMap<>();
        personRepository.findAllById(memberships.stream().map(ProviderMembership::getPersonId).collect(Collectors.toSet()))
                .forEach(p -> persons.put(p.getId(), p));
        Map<UUID, String> phones = accountLookup.findPhonesByIds(
                persons.values().stream().map(Person::getUserAccountId).collect(Collectors.toSet()));
        return memberships.stream()
                .map(m -> {
                    Person person = persons.get(m.getPersonId());
                    return toView(m, person, phones.get(person.getUserAccountId()));
                })
                .toList();
    }

    private CustomerView toView(ProviderMembership m, Person person, String phone) {
        return CustomerView.from(m, person, phone);
    }

    private String phoneOf(Person person) {
        return accountLookup.findPhonesByIds(Set.of(person.getUserAccountId())).get(person.getUserAccountId());
    }
}
