package com.firstfood;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FirstFood V2 backend entrypoint.
 *
 * Implemented as a modular monolith (see architecture.md #23). Domain
 * modules live under com.firstfood.<module> (identity, provider,
 * provideraccess, membership, plan, subscription, attendance, review,
 * notification) and should not reach into each other's internals -
 * cross-module calls go through each module's public application-service
 * interface only.
 */
@SpringBootApplication
public class FirstFoodApplication {

    public static void main(String[] args) {
        SpringApplication.run(FirstFoodApplication.class, args);
    }
}
