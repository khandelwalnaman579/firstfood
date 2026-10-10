package com.firstfood.membership;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipLookupServiceImpl implements MembershipLookupService {

    private final ProviderMembershipRepository membershipRepository;
    private final PersonRepository personRepository;

    public MembershipLookupServiceImpl(
            ProviderMembershipRepository membershipRepository, PersonRepository personRepository) {
        this.membershipRepository = membershipRepository;
        this.personRepository = personRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MembershipRef> find(UUID providerId, UUID membershipId) {
        return membershipRepository.findByIdAndProviderId(membershipId, providerId)
                .map(m -> new MembershipRef(m.getId(), m.getProviderId(), m.getPersonId(), m.getStatus()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, MemberSummary> summarize(Collection<UUID> membershipIds) {
        if (membershipIds == null || membershipIds.isEmpty()) {
            return Map.of();
        }
        var memberships = membershipRepository.findAllById(membershipIds);
        Map<UUID, Person> persons = new HashMap<>();
        personRepository.findAllById(memberships.stream().map(ProviderMembership::getPersonId)
                .collect(Collectors.toSet())).forEach(p -> persons.put(p.getId(), p));
        Map<UUID, MemberSummary> result = new HashMap<>();
        for (ProviderMembership m : memberships) {
            Person person = persons.get(m.getPersonId());
            result.put(m.getId(), new MemberSummary(m.getId(), m.getPersonId(),
                    person == null ? "(unknown)" : person.getFullName(), m.getStatus()));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> personIdsOf(UUID accountId) {
        return personRepository.findIdsByUserAccountId(accountId);
    }
}
