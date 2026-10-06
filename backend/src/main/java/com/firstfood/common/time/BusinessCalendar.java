package com.firstfood.common.time;

import java.time.Clock;
import java.time.LocalDate;
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
 * A per-provider time zone is a later, deliberate change - and Phase 8 (same-day absence cutoff,
 * which is a wall-clock time) is where it will first matter.
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

    public ZoneId zone() {
        return zone;
    }
}
