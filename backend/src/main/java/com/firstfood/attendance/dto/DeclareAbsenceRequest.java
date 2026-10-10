package com.firstfood.attendance.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * "I will not be eating on these days." {@code toDate} defaults to {@code fromDate} (a single day). The days are
 * checked against the provider's policy on the server; nothing here decides whether the request is allowed.
 */
public record DeclareAbsenceRequest(
        @NotNull LocalDate fromDate,
        LocalDate toDate,
        @Size(max = 500) String reason) {
}
