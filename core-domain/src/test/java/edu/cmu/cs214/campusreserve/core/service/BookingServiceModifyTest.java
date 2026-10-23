package edu.cmu.cs214.campusreserve.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.BookingChange;
import edu.cmu.cs214.campusreserve.core.domain.ModificationResult;
import edu.cmu.cs214.campusreserve.core.domain.RejectionReason;
import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import edu.cmu.cs214.campusreserve.core.domain.WaitlistEntry;
import edu.cmu.cs214.campusreserve.core.events.BookingModifiedEvent;
import edu.cmu.cs214.campusreserve.core.policy.BookingPolicy;
import edu.cmu.cs214.campusreserve.core.support.Fixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BookingServiceModifyTest extends BookingServiceTestBase {

    private static final TimeInterval SLOT = Fixtures.interval("10:00", "11:00");

    private ModificationResult modify(String id, String user, TimeInterval interval, int attendees) {
        return service.modifyBooking(id, user, interval, attendees);
    }

    private void assertRejected(RejectionReason expected, ModificationResult result) {
        assertEquals(Optional.of(expected), result.rejectionReason());
    }

    private List<String> waiters(String roomId) {
        return service.waitlistForRoom(roomId).stream().map(WaitlistEntry::userId).toList();
    }

    @Test
    void aFifteenMinuteNudgeSucceedsAndRecordsHistory() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        TimeInterval nudged = Fixtures.interval("10:15", "11:15");

        ModificationResult result = modify(id, "u-1", nudged, 4);

        assertTrue(result.isModified());
        Booking stored = service.findBooking(id).orElseThrow();
        assertEquals(nudged, stored.interval());
        assertEquals(1, stored.history().size());
        BookingChange change = stored.history().get(0);
        assertEquals(SLOT, change.previousInterval());
        assertEquals(nudged, change.newInterval());
        assertEquals(4, change.previousAttendees());
        assertEquals(4, change.newAttendees());
    }

    @Test
    void aMoveIntoATakenSlotChangesNothing() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        bookAndGetId("r-1", "u-2", Fixtures.interval("12:00", "13:00"));
        book("r-1", "u-3", Fixtures.interval("12:00", "13:00"));
        Booking before = service.findBooking(id).orElseThrow();
        List<WaitlistEntry> waitlistBefore = service.waitlistForRoom("r-1");
        List<String> eventsBefore = publisher.types();

        ModificationResult result = modify(id, "u-1", Fixtures.interval("12:30", "13:30"), 4);

        assertRejected(RejectionReason.ROOM_UNAVAILABLE, result);
        assertEquals(before, service.findBooking(id).orElseThrow());
        assertTrue(service.findBooking(id).orElseThrow().history().isEmpty());
        assertEquals(waitlistBefore, service.waitlistForRoom("r-1"));
        assertEquals(eventsBefore, publisher.types());
    }

    @Test
    void anOverlapInAnotherRoomIsADoubleBooking() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        bookAndGetId("r-2", "u-1", Fixtures.interval("12:00", "13:00"));
        Booking before = service.findBooking(id).orElseThrow();

        ModificationResult result = modify(id, "u-1", Fixtures.interval("12:30", "13:30"), 4);

        assertRejected(RejectionReason.USER_DOUBLE_BOOKED, result);
        assertEquals(before, service.findBooking(id).orElseThrow());
    }

    @Test
    void aUserAtTheBookingCapCanStillModify() {
        build(BookingPolicy.defaults().withMaxActiveBookingsPerUser(1));
        String id = bookAndGetId("r-1", "u-1", SLOT);
        assertTrue(modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4).isModified());
    }

    @Test
    void eachRequestRuleRejects() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        assertRejected(RejectionReason.INTERVAL_TOO_SHORT,
            modify(id, "u-1", Fixtures.interval("10:00", "10:15"), 4));
        assertRejected(RejectionReason.INTERVAL_TOO_LONG,
            modify(id, "u-1", Fixtures.interval("10:00", "14:00"), 4));
        assertRejected(RejectionReason.INTERVAL_NOT_ALIGNED,
            modify(id, "u-1", Fixtures.interval("10:05", "11:05"), 4));
        assertRejected(RejectionReason.INTERVAL_SPANS_DAYS,
            modify(id, "u-1", TimeInterval.parse("2026-10-20T23:30:00Z", "2026-10-21T00:30:00Z"), 4));
        assertRejected(RejectionReason.INTERVAL_IN_PAST,
            modify(id, "u-1", Fixtures.intervalOn("2026-10-19", "07:00", "08:00"), 4));
        assertRejected(RejectionReason.OUTSIDE_OPENING_HOURS,
            modify(id, "u-1", Fixtures.interval("05:00", "06:00"), 4));
        assertRejected(RejectionReason.TOO_FAR_IN_ADVANCE,
            modify(id, "u-1", Fixtures.intervalOn("2026-11-20", "10:00", "11:00"), 4));
        assertRejected(RejectionReason.ATTENDEES_EXCEED_CAPACITY, modify(id, "u-1", SLOT, 11));
        assertTrue(service.findBooking(id).orElseThrow().history().isEmpty());

        rooms.save(rooms.findById("r-1").orElseThrow().withActive(false));
        assertRejected(RejectionReason.ROOM_INACTIVE,
            modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4));
    }

    @Test
    void aNonOwnerIsRejectedEvenForANoOp() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        assertRejected(RejectionReason.NOT_BOOKING_OWNER, modify(id, "u-2", SLOT, 4));
        assertRejected(RejectionReason.NOT_BOOKING_OWNER,
            modify(id, "u-2", Fixtures.interval("10:15", "11:15"), 4));
    }

    @Test
    void aStartedBookingIsRejected() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        clock.setTo(Fixtures.DAY + "T10:30:00Z");
        assertRejected(RejectionReason.BOOKING_ALREADY_STARTED,
            modify(id, "u-1", Fixtures.interval("11:00", "12:00"), 4));
    }

    @Test
    void aCancelledBookingIsRejected() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        service.cancelBooking(id, "u-1");
        assertRejected(RejectionReason.BOOKING_ALREADY_CANCELLED,
            modify(id, "u-1", Fixtures.interval("11:00", "12:00"), 4));
    }

    @Test
    void anUnknownBookingIsRejected() {
        assertRejected(RejectionReason.BOOKING_NOT_FOUND, modify("nope", "u-1", SLOT, 4));
    }

    @Test
    void anAttendeesOnlyChangePublishesOneEventAndPromotesNobody() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        book("r-1", "u-2", SLOT);

        ModificationResult result = modify(id, "u-1", SLOT, 6);

        assertTrue(result.isModified());
        assertTrue(result.promotions().isEmpty());
        assertEquals(List.of("booking-created", "booking-modified"), publisher.types());
        BookingModifiedEvent event = publisher.ofType(BookingModifiedEvent.class).get(0);
        assertEquals(6, event.attendees());
        assertEquals(4, event.previousAttendees());
        assertEquals(SLOT, event.previousInterval());
        assertEquals(List.of("u-2"), waiters("r-1"));
    }

    @Test
    void shorteningPromotesTheHeadWaiter() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        book("r-1", "u-2", Fixtures.interval("10:30", "11:00"));
        book("r-1", "u-3", Fixtures.interval("10:30", "11:00"));

        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:00", "10:30"), 4);

        assertEquals(1, result.promotions().size());
        assertEquals("u-2", result.promotions().get(0).booking().userId());
        assertEquals(List.of("u-3"), waiters("r-1"));
        assertEquals(List.of("booking-created", "booking-modified", "waitlist-promoted"),
            publisher.types());
    }

    @Test
    void lengtheningRunsThePassAndPromotesNobody() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        book("r-1", "u-2", SLOT);

        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:00", "12:00"), 4);

        assertTrue(result.isModified());
        assertTrue(result.promotions().isEmpty());
        assertEquals(List.of("u-2"), waiters("r-1"));
    }

    @Test
    void aWaiterWhoseOwnCalendarClashesIsSkipped() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        TimeInterval tail = Fixtures.interval("10:30", "11:00");
        book("r-1", "u-2", tail);
        assertTrue(book("r-2", "u-2", tail).isConfirmed());

        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:00", "10:30"), 4);

        assertTrue(result.promotions().isEmpty());
        assertEquals(List.of("u-2"), waiters("r-1"));
    }

    @Test
    void promotionRunsAfterTheModifiedBookingIsSaved() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        TimeInterval clash = Fixtures.interval("10:00", "10:30");
        // u-2's wait is blocked only by u-1's old interval.
        book("r-1", "u-2", clash);
        assertEquals(List.of("u-2"), waiters("r-1"));

        // Move u-1 to 10:30-11:30: u-2's slot is free only once the new booking is stored.
        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:30", "11:30"), 4);

        assertEquals(1, result.promotions().size());
        Booking promoted = result.promotions().get(0).booking();
        Booking moved = service.findBooking(id).orElseThrow();
        assertFalse(promoted.interval().overlaps(moved.interval()));
        assertEquals(clash, promoted.interval());
        assertEquals(List.of("booking-created", "booking-modified", "waitlist-promoted"),
            publisher.types());
    }

    @Test
    void aNoOpEmitsAndWritesNothing() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        book("r-1", "u-2", SLOT);
        Booking before = service.findBooking(id).orElseThrow();
        List<String> eventsBefore = publisher.types();

        ModificationResult result = modify(id, "u-1", SLOT, 4);

        assertTrue(result.isModified());
        assertTrue(result.promotions().isEmpty());
        assertEquals(before, service.findBooking(id).orElseThrow());
        assertTrue(service.findBooking(id).orElseThrow().history().isEmpty());
        assertEquals(eventsBefore, publisher.types());
        assertEquals(List.of("u-2"), waiters("r-1"));
    }

    @Test
    void aThrowingPublisherDoesNotFailTheModification() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        publisher.failWith(new IllegalStateException("subscriber down"));

        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);

        assertTrue(result.isModified());
        assertEquals(Fixtures.interval("10:15", "11:15"), service.findBooking(id).orElseThrow().interval());
    }

    @Test
    void cancellingKeepsTheHistory() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);
        service.cancelBooking(id, "u-1");
        assertEquals(1, service.findBooking(id).orElseThrow().history().size());
    }
}
