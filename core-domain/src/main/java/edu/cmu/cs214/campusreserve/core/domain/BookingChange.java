package edu.cmu.cs214.campusreserve.core.domain;

import java.time.Instant;
import java.util.Objects;

/** One edit to a confirmed booking: what it held before, what it holds now, and when. */
public record BookingChange(
    Instant changedAt,
    TimeInterval previousInterval,
    TimeInterval newInterval,
    int previousAttendees,
    int newAttendees) {

    public BookingChange {
        Objects.requireNonNull(changedAt, "changedAt");
        Objects.requireNonNull(previousInterval, "previousInterval");
        Objects.requireNonNull(newInterval, "newInterval");
    }

    /** Did this edit move or resize the booking? */
    public boolean changedInterval() {
        return !previousInterval.equals(newInterval);
    }
}
