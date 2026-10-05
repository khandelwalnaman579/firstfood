package com.firstfood.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The FirstFood login/authentication identity (rules.md Rule 3.1).
 *
 * Deliberately NOT the person who eats (rules.md Rule 3.2) - Person is
 * introduced in Phase 5. Do not add a unique constraint or 1:1 assumption
 * anywhere that would prevent one UserAccount from later owning multiple
 * Persons.
 */
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 20)
    private String phone;

    @Column(length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserAccount() {
        // JPA
    }

    public UserAccount(String phone) {
        this.id = UUID.randomUUID();
        this.phone = phone;
    }

    public UUID getId() {
        return id;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
