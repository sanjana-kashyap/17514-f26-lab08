package edu.cmu.cs214.campusreserve.core.events;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import java.time.Instant;
import java.util.Objects;

/**
 * A booking changed its interval or party size. The core fields carry the new
 * state; the previous values travel in memory only, for subscribers that want to
 * say what changed.
 */
public record BookingModifiedEvent(
    Instant occurredAt,
    String bookingId,
    String roomId,
    String userId,
    TimeInterval interval,
    int attendees,
    TimeInterval previousInterval,
    int previousAttendees) implements DomainEvent {

    /** The wire name of this event. */
    public static final String TYPE = "booking-modified";

    public BookingModifiedEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(previousInterval, "previousInterval");
    }

    /** Builds the event from the booking as it now stands and what it held before. */
    public static BookingModifiedEvent of(
        Booking booking, TimeInterval previousInterval, int previousAttendees, Instant occurredAt) {
        return new BookingModifiedEvent(
            occurredAt,
            booking.id(),
            booking.roomId(),
            booking.userId(),
            booking.interval(),
            booking.attendees(),
            previousInterval,
            previousAttendees);
    }

    @Override
    public String type() {
        return TYPE;
    }
}
