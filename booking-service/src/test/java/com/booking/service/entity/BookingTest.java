package com.booking.service.entity;

import com.booking.service.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BookingTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-18T12:00:00+03:00");

    @Test
    void beginCancellation_fromAwaitConfirmation_setsPendingState() {
        Booking booking = createBooking();

        booking.beginCancellation(NOW);

        assertEquals(BookingStatus.CANCELLATION_PENDING, booking.getStatus());
        assertEquals(BookingStatus.AWAIT_CONFIRMATION, booking.getPreviousStatus());
        assertEquals(NOW, booking.getCommandSentAt());
    }

    @Test
    void beginCancellation_fromConfirmedBeforeBookingStart_setsPendingState() {
        Booking booking = createConfirmedBooking();

        booking.beginCancellation(NOW);

        assertEquals(BookingStatus.CANCELLATION_PENDING, booking.getStatus());
        assertEquals(BookingStatus.CONFIRMED, booking.getPreviousStatus());
        assertEquals(NOW, booking.getCommandSentAt());
    }

    @Test
    void beginCancellation_fromConfirmedOnBookingStart_throwsAndDoesNotChangeState() {
        Booking booking = createConfirmedBooking();
        OffsetDateTime bookingStart = OffsetDateTime.parse("2026-08-20T09:00:00+03:00");

        assertThrows(BusinessException.class, () -> booking.beginCancellation(bookingStart));
        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
        assertNull(booking.getPreviousStatus());
        assertNull(booking.getCommandSentAt());
    }

    @Test
    void completeCancellation_fromPending_setsCancelledState() {
        Booking booking = createBooking();
        booking.beginCancellation(NOW);

        booking.completeCancellation();

        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
        assertNull(booking.getPreviousStatus());
        assertNull(booking.getCommandSentAt());
    }

    @Test
    void catalogDenied_fromPending_completesCancellation() {
        Booking booking = createBooking();
        booking.beginCancellation(NOW);

        booking.cancel(NOW.toLocalDate());

        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
        assertNull(booking.getPreviousStatus());
        assertNull(booking.getCommandSentAt());
    }

    @Test
    void rollbackCancellation_restoresAwaitConfirmation() {
        Booking booking = createBooking();
        booking.beginCancellation(NOW);

        booking.rollbackCancellation();

        assertEquals(BookingStatus.AWAIT_CONFIRMATION, booking.getStatus());
        assertNull(booking.getPreviousStatus());
        assertNull(booking.getCommandSentAt());
    }

    @Test
    void rollbackCancellation_restoresConfirmed() {
        Booking booking = createConfirmedBooking();
        booking.beginCancellation(NOW);

        booking.rollbackCancellation();

        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
        assertNull(booking.getPreviousStatus());
        assertNull(booking.getCommandSentAt());
    }

    @Test
    void repeatedCancellationFromPending_throws() {
        Booking booking = createBooking();
        booking.beginCancellation(NOW);

        assertThrows(BusinessException.class, () -> booking.beginCancellation(NOW.plusMinutes(1)));
    }

    @Test
    void rollbackFromNonPendingStatus_throws() {
        Booking booking = createBooking();

        assertThrows(BusinessException.class, booking::rollbackCancellation);
    }

    private Booking createBooking() {
        return Booking.create(
                1L,
                1L,
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 22),
                NOW
        );
    }

    private Booking createConfirmedBooking() {
        Booking booking = createBooking();
        booking.confirm();
        return booking;
    }
}
