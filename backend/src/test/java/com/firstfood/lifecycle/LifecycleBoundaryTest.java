package com.firstfood.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.IdentityCleanupService;
import com.firstfood.subscription.SubscriptionLifecycleService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Phase 10 system-only interfaces (no authorization, no actor) must never be reachable from anything that handles
 * a client request - the same guard {@code ExtensionSystemBoundaryTest} puts around the extension engine. Also pins the
 * wiring facts the scheduler relies on.
 */
class LifecycleBoundaryTest extends AbstractIntegrationTest {

    private static final List<Class<?>> SYSTEM_ONLY = List.of(SubscriptionLifecycleService.class,
            IdentityCleanupService.class, JobRunner.class, BackgroundJob.class, SubscriptionLapseProcessor.class);

    @Autowired
    ApplicationContext context;

    @Test
    void noControllerDependsOnASystemOnlyService() {
        Map<String, Object> controllers = new HashMap<>(context.getBeansWithAnnotation(RestController.class));
        controllers.putAll(context.getBeansWithAnnotation(Controller.class));
        assertThat(controllers).isNotEmpty();
        for (Object bean : controllers.values()) {
            Class<?> type = AopUtils.getTargetClass(bean);
            for (Constructor<?> c : type.getDeclaredConstructors()) {
                for (Class<?> p : c.getParameterTypes()) {
                    for (Class<?> forbidden : SYSTEM_ONLY) {
                        assertThat(forbidden.isAssignableFrom(p))
                                .as("%s constructor takes %s", type.getSimpleName(), p.getSimpleName()).isFalse();
                    }
                }
            }
            for (Field f : type.getDeclaredFields()) {
                for (Class<?> forbidden : SYSTEM_ONLY) {
                    assertThat(forbidden.isAssignableFrom(f.getType()))
                            .as("%s field %s", type.getSimpleName(), f.getName()).isFalse();
                }
            }
        }
    }

    @Test
    void theFourJobsAreRegisteredWithUniqueValidNames() {
        List<String> names = context.getBeansOfType(BackgroundJob.class).values().stream()
                .map(BackgroundJob::name).toList();
        assertThat(names).containsExactlyInAnyOrder("subscription-expiry", "extension-evaluation", "otp-cleanup",
                "session-cleanup");
        assertThat(names).doesNotHaveDuplicates().allMatch(n -> n.matches("^[a-z][a-z0-9-]*$"));
    }

    @Test
    void theSchedulerIsOffInTestsSoTestsControlWhenJobsRun() {
        assertThat(context.getBeansOfType(JobScheduler.class)).isEmpty();
        assertThat(context.getBean(JobProperties.class).enabled()).isFalse();
    }
}
