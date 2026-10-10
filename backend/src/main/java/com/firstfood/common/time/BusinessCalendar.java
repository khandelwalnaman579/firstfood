package com.firstfood.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * "What calendar day is it for the business" - the answer every date-based rule (subscription
 * start and expiry, remaining days) needs.
 *
 * Dates such as a subscription's start and expiry are calendar days, not instants, so they must
 * be read in the business's own time zone: a mess in Bhopal that opens a subscription at 00:30 on
 * the 1st means the 1st, whatever the date is in UTC. One zone for the whole product
 * ({@code app.business.time-zone}, default Asia/Kolkata) is enough for the India-only pilot.
 *
 * Phase 8 decision (the timezone convention Phase 6 deferred): a plan's same-day absence cutoff is a plain
 * wall-clock time ({@code LocalTime}) and is read in THIS zone - {@link #timeOfDay()} is "now" on that clock.
 * A per-provider time zone is a later, deliberate change (it would add a zone to the provider and change only
 * this class's callers); until then every provider shares the product's zone.
 */
@Component
public class BusinessCalendar {

    private final Clock clock;
    private final ZoneId zone;

    public BusinessCalendar(Clock clock, @Value("${app.business.time-zone:Asia/Kolkata}") String timeZone) {
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    /** Today's date in the business time zone. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    /** The wall-clock time right now in the business time zone (what a same-day absence cutoff is compared to). */
    public LocalTime timeOfDay() {
        return LocalTime.now(clock.withZone(zone));
    }

    /** The business-zone calendar day an instant falls on. */
    public LocalDate dateOf(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }

    /** The first instant of the given business-zone day. */
    public Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(zone).toInstant();
    }

    public ZoneId zone() {
        return zone;
    }
}
