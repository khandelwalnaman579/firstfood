package com.firstfood.attendance.dto;

import com.firstfood.attendance.AttendanceStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A provider-staff decision for one day. Always carries a reason: a correction must be explainable later. */
public record CorrectAttendanceRequest(
        @NotNull AttendanceStatus status,
        @NotBlank @Size(max = 500) String reason) {
}
