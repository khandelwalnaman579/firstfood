package com.firstfood.attendance.web;

import com.firstfood.attendance.AttendanceService;
import com.firstfood.attendance.DeclareAbsenceResult;
import com.firstfood.attendance.MyAbsenceView;
import com.firstfood.attendance.MyAttendanceDayView;
import com.firstfood.attendance.dto.DeclareAbsenceRequest;
import com.firstfood.identity.security.AuthenticatedAccount;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's side: "Open subscription -> select date -> Not eating -> confirm" (phases.md §11). Always scoped to
 * the JWT principal's own persons; whether a day may still be changed is decided by the backend from the
 * subscription's policy snapshot and reported per day.
 */
@RestController
@RequestMapping("/api/v1/subscriptions/{subscriptionId}")
public class MyAttendanceController {

    private final AttendanceService attendanceService;

    public MyAttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @GetMapping("/attendance")
    public List<MyAttendanceDayView> days(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID subscriptionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendanceService.myDays(principal.accountId(), subscriptionId, from, to);
    }

    @GetMapping("/absence")
    public List<MyAbsenceView> absences(
            @AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID subscriptionId) {
        return attendanceService.myAbsences(principal.accountId(), subscriptionId);
    }

    /** 201 when at least one day was newly declared; 200 when every day was already declared (a retry). */
    @PostMapping("/absence")
    public ResponseEntity<DeclareAbsenceResult> declare(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID subscriptionId,
            @Valid @RequestBody DeclareAbsenceRequest request) {
        DeclareAbsenceResult result = attendanceService.declare(
                principal.accountId(), subscriptionId, request.fromDate(), request.toDate(), request.reason());
        return ResponseEntity.status(result.anyCreated() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @PostMapping("/absence/{absenceId}/cancel")
    public MyAbsenceView cancel(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID subscriptionId,
            @PathVariable UUID absenceId) {
        return attendanceService.cancelAbsence(principal.accountId(), subscriptionId, absenceId);
    }
}
