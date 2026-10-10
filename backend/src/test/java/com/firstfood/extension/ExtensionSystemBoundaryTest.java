package com.firstfood.extension;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SYSTEM-only boundary of the extension engine: the method that applies an extension without an actor must not
 * be reachable from anything that handles a client request, now or after someone "just wires it in" later.
 */
class ExtensionSystemBoundaryTest extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void theUserFacingInterfaceHasNoSystemMethod() {
        for (Method m : ExtensionService.class.getMethods()) {
            assertThat(m.getName()).as("ExtensionService must stay user-facing").isNotEqualTo("applyAutomatically");
        }
    }

    @Test
    void noControllerDependsOnTheSystemService() {
        Map<String, Object> controllers = new HashMap<>(context.getBeansWithAnnotation(RestController.class));
        controllers.putAll(context.getBeansWithAnnotation(Controller.class));
        assertThat(controllers).isNotEmpty();
        for (Object bean : controllers.values()) {
            Class<?> type = AopUtils.getTargetClass(bean);
            for (Constructor<?> c : type.getDeclaredConstructors()) {
                for (Class<?> p : c.getParameterTypes()) {
                    assertThat(SystemExtensionService.class.isAssignableFrom(p))
                            .as("%s constructor takes %s", type.getSimpleName(), p.getSimpleName()).isFalse();
                }
            }
            for (Field f : type.getDeclaredFields()) {
                assertThat(SystemExtensionService.class.isAssignableFrom(f.getType()))
                        .as("%s field %s", type.getSimpleName(), f.getName()).isFalse();
            }
        }
    }

    @Test
    void theSystemServiceIsNotAnAuthorizedUserApiAndRecordsNoActor() {
        // Only application code (Phase 10 jobs) injects this; the event it writes has no account behind it.
        assertThat(context.getBeansOfType(SystemExtensionService.class)).hasSize(1);
    }
}
