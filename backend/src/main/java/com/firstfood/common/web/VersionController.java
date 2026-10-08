package com.firstfood.common.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Confirms API versioning is wired end-to-end (phases.md Phase 1 exit
 * criteria). Real business endpoints live under /api/v1/<module> in each
 * module's own controller package.
 */
@RestController
public class VersionController {

    @GetMapping("/api/v1/version")
    public Map<String, String> version() {
        return Map.of(
                "service", "firstfood-v2",
                "apiVersion", "v1"
        );
    }
}
