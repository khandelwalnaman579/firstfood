package com.firstfood.attendance;

import java.util.List;

/**
 * The outcome of a declaration request: one absence per requested day, in date order. {@code anyCreated} is false
 * when every day was already declared - a retried request changes nothing and reports the existing records.
 */
public record DeclareAbsenceResult(List<MyAbsenceView> absences, boolean anyCreated) {
}
