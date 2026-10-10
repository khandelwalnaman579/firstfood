package com.firstfood.membership.web;

import com.firstfood.identity.security.AuthenticatedAccount;
import com.firstfood.membership.MembershipService;
import com.firstfood.membership.MyMembershipView;
import com.firstfood.membership.PersonService;
import com.firstfood.membership.PersonView;
import com.firstfood.membership.dto.SavePersonRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own Person profile and memberships. Always scoped to the JWT principal. */
@RestController
@RequestMapping("/api/v1/me")
public class MePersonController {

    private final PersonService personService;
    private final MembershipService membershipService;

    public MePersonController(PersonService personService, MembershipService membershipService) {
        this.personService = personService;
        this.membershipService = membershipService;
    }

    @GetMapping("/person")
    public PersonView getPerson(@AuthenticationPrincipal AuthenticatedAccount principal) {
        return personService.getMyPerson(principal.accountId());
    }

    @PutMapping("/person")
    public PersonView savePerson(
            @AuthenticationPrincipal AuthenticatedAccount principal, @Valid @RequestBody SavePersonRequest request) {
        return personService.saveMyPerson(principal.accountId(), request.fullName());
    }

    @GetMapping("/memberships")
    public List<MyMembershipView> memberships(@AuthenticationPrincipal AuthenticatedAccount principal) {
        return membershipService.listMine(principal.accountId());
    }
}
