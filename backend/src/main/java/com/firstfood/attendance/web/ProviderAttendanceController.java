package com.firstfood.attendance.web;

import com.firstfood.attendance.AbsenceView;
import com.firstfood.attendance.AttendanceDayView;
import com.firstfood.attendance.AttendanceHistoryEntry;
import com.firstfood.attendance.AttendanceService;
import com.firstfood.attendance.DailySheetView;
import com.firstfood.attendance.dto.CorrectAttendanceRequest;
import com.firstfood.identity.security.AuthenticatedAccount;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider staff's side of attendance: the daily sheet, one subscription's days, and the correction. Thin HTTP
 * layer - the acting account is always the JWT principal and every authorization decision is in
 * {@link AttendanceService}. Dates are ISO (yyyy-MM-dd).
 */
@RestController
@RequestMapping("/api/v1/providers/{providerId}")
public class ProviderAttendanceController {

    private final AttendanceService attendanceService;

    public ProviderAttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    /** Who is eating on a date (default today). */
    @GetMapping("/attendance")
    public DailySheetView dailySheet(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return attendanceService.dailySheet(principal.accountId(), providerId, date);
    }

    @GetMapping("/subscriptions/{subscriptionId}/attendance")
    public List<AttendanceDayView> days(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID subscriptionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendanceService.days(principal.accountId(), providerId, subscriptionId, from, to);
    }

    @GetMapping("/subscriptions/{subscriptionId}/attendance/{date}/history")
    public List<AttendanceHistoryEntry> history(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID subscriptionId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return attendanceService.history(principal.accountId(), providerId, subscriptionId, date);
    }

    /** Provider staff decide one day: ABSENT records an absence for the customer, PRESENT overrides one. */
    @PutMapping("/subscriptions/{subscriptionId}/attendance/{date}")
    public AttendanceDayView correct(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID subscriptionId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody CorrectAttendanceRequest request) {
        return attendanceService.correct(
                principal.accountId(), providerId, subscriptionId, date, request.status(), request.reason());
    }

    @GetMapping("/subscriptions/{subscriptionId}/absences")
    public List<AbsenceView> absences(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID subscriptionId) {
        return attendanceService.absences(principal.accountId(), providerId, subscriptionId);
    }
}
