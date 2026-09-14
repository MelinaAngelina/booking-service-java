package com.booking.service.job;

import com.booking.service.config.CancellationRetryProperties;
import com.booking.service.entity.Booking;
import com.booking.service.entity.BookingStatus;
import com.booking.service.messaging.contracts.CancelBookingJobByRequestIdRequest;
import com.booking.service.messaging.listener.BookingEventPublisher;
import com.booking.service.repository.BookingRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CancellationRetryJobTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-11T12:00:00Z");
    private static final Duration TIMEOUT = Duration.ofMinutes(7);

    @Test
    void retry_sendsOriginalRequestIdAndPreservesState() {
        Booking booking = pendingBooking(UUID.randomUUID());
        RecordingPublisher publisher = new RecordingPublisher();
        CancellationRetryJob job = job(List.of(booking), publisher);

        job.retryStuckCancellations();

        assertEquals(1, publisher.sent.size());
        assertEquals(booking.getCatalogRequestId(), publisher.sent.getFirst().getRequestId());
        assertNotNull(publisher.sent.getFirst().getEventId());
        assertEquals(BookingStatus.CANCELLATION_PENDING, booking.getStatus());
        assertEquals(BookingStatus.AWAIT_CONFIRMATION, booking.getPreviousStatus());
        assertEquals(NOW.minusMinutes(10), booking.getCommandSentAt());
    }

    @Test
    void retry_afterOneSendFails_continuesWithRemainingBookings() {
        Booking first = pendingBooking(UUID.randomUUID());
        Booking second = pendingBooking(UUID.randomUUID());
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.failedRequestId = first.getCatalogRequestId();

        assertDoesNotThrow(() -> job(List.of(first, second), publisher).retryStuckCancellations());

        assertEquals(List.of(first.getCatalogRequestId(), second.getCatalogRequestId()), publisher.attempted);
        assertEquals(1, publisher.sent.size());
        assertEquals(second.getCatalogRequestId(), publisher.sent.getFirst().getRequestId());
        assertEquals(BookingStatus.CANCELLATION_PENDING, first.getStatus());
        assertEquals(BookingStatus.CANCELLATION_PENDING, second.getStatus());
    }

    @Test
    void retry_withoutCatalogRequestId_skipsBookingAndContinues() {
        Booking valid = pendingBooking(UUID.randomUUID());
        RecordingPublisher publisher = new RecordingPublisher();

        job(List.of(pendingBooking(null), valid), publisher).retryStuckCancellations();

        assertEquals(List.of(valid.getCatalogRequestId()), publisher.attempted);
    }

    @Test
    void retry_withoutStuckBookings_sendsNothing() {
        RecordingPublisher publisher = new RecordingPublisher();
        job(List.of(), publisher).retryStuckCancellations();
        assertTrue(publisher.attempted.isEmpty());
    }

    @Test
    void retry_untilReplyArrives_usesNewEventIdForSameCatalogRequest() {
        Booking booking = pendingBooking(UUID.randomUUID());
        RecordingPublisher publisher = new RecordingPublisher();
        CancellationRetryJob job = job(List.of(booking), publisher);

        job.retryStuckCancellations();
        job.retryStuckCancellations();

        assertEquals(2, publisher.sent.size());
        assertNotEquals(publisher.sent.get(0).getEventId(), publisher.sent.get(1).getEventId());
        assertEquals(publisher.sent.get(0).getRequestId(), publisher.sent.get(1).getRequestId());
    }

    private CancellationRetryJob job(List<Booking> bookings, RecordingPublisher publisher) {
        BookingRepository repository = (BookingRepository) Proxy.newProxyInstance(
                BookingRepository.class.getClassLoader(), new Class<?>[]{BookingRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findByStatusAndCommandSentAtBefore")) {
                        assertEquals(BookingStatus.CANCELLATION_PENDING, args[0]);
                        assertEquals(NOW.minus(TIMEOUT), args[1]);
                        return bookings;
                    }
                    // Любая попытка save запрещена: job только отправляет команды.
                    throw new UnsupportedOperationException(method.getName());
                });
        return new CancellationRetryJob(repository, publisher, () -> NOW,
                new CancellationRetryProperties(TIMEOUT));
    }

    private Booking pendingBooking(UUID requestId) {
        Booking booking = Booking.create(1L, 1L, NOW.toLocalDate().plusDays(1),
                NOW.toLocalDate().plusDays(2), NOW.minusHours(1));
        if (requestId != null) {
            booking.setCatalogRequestId(requestId);
        }
        booking.beginCancellation(NOW.minusMinutes(10));
        return booking;
    }

    private static class RecordingPublisher extends BookingEventPublisher {
        private final List<UUID> attempted = new ArrayList<>();
        private final List<CancelBookingJobByRequestIdRequest> sent = new ArrayList<>();
        private UUID failedRequestId;

        private RecordingPublisher() {
            super(null, null);
        }

        @Override
        public void publishCancelBookingJob(CancelBookingJobByRequestIdRequest request) {
            attempted.add(request.getRequestId());
            if (request.getRequestId().equals(failedRequestId)) {
                throw new IllegalStateException("Test broker failure");
            }
            sent.add(request);
        }
    }
}
