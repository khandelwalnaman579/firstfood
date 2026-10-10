package com.firstfood.membership;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonServiceImpl implements PersonService {

    private final PersonRepository personRepository;
    private final PersonProvisioner provisioner;

    public PersonServiceImpl(PersonRepository personRepository, PersonProvisioner provisioner) {
        this.personRepository = personRepository;
        this.provisioner = provisioner;
    }

    @Override
    @Transactional(readOnly = true)
    public PersonView getMyPerson(UUID accountId) {
        return provisioner.findPrimary(accountId).map(PersonView::from).orElseThrow(MembershipException::personNotFound);
    }

    @Override
    @Transactional
    public PersonView saveMyPerson(UUID accountId, String fullName) {
        String name = PersonProvisioner.normalize(fullName);
        if (name == null) {
            throw MembershipException.personNameRequired();
        }
        Person person = provisioner.ensurePrimary(accountId, name);
        if (!person.getFullName().equals(name)) {
            person.rename(name);
            person = personRepository.save(person);
        }
        return PersonView.from(person);
    }
}
