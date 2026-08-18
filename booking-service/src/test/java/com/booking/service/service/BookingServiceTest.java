package com.booking.service.service;

import com.booking.service.entity.Booking;
import com.booking.service.entity.BookingStatus;
import com.booking.service.messaging.contracts.CancelBookingJobByRequestIdRequest;
import com.booking.service.messaging.listener.BookingEventPublisher;
import com.booking.service.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BookingServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-18T12:00:00+03:00");
    private static final UUID REQUEST_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    private BookingRepositoryStub repositoryStub;
    private RecordingBookingEventPublisher eventPublisher;
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        repositoryStub = new BookingRepositoryStub();
        eventPublisher = new RecordingBookingEventPublisher();
        bookingService = new BookingService(repositoryStub.repository(), eventPublisher, () -> NOW);
    }

    @Test
    void cancelBooking_setsPendingStateAndPublishesCommand() {
        Booking booking = createBooking();
        repositoryStub.add(1L, booking);

        bookingService.cancelBooking(1L);

        assertEquals(BookingStatus.CANCELLATION_PENDING, booking.getStatus());
        assertEquals(BookingStatus.AWAIT_CONFIRMATION, booking.getPreviousStatus());
        assertEquals(NOW, booking.getCommandSentAt());
        assertEquals(1, repositoryStub.saveCount());
        assertNotNull(eventPublisher.publishedCancelCommand());
        assertEquals(REQUEST_ID, eventPublisher.publishedCancelCommand().getRequestId());
    }

    @Test
    void handleBookingJobDenied_forPendingBooking_completesCancellation() {
        Booking booking = createBooking();
        booking.beginCancellation(NOW);
        repositoryStub.add(1L, booking);

        bookingService.handleBookingJobDenied(REQUEST_ID);

        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
        assertNull(booking.getPreviousStatus());
        assertNull(booking.getCommandSentAt());
        assertEquals(1, repositoryStub.saveCount());
    }

    @Test
    void handleError_forPendingBooking_rollsBackCancellation() {
        Booking booking = createBooking();
        booking.beginCancellation(NOW);
        repositoryStub.add(1L, booking);

        bookingService.handleError(REQUEST_ID);

        assertEquals(BookingStatus.AWAIT_CONFIRMATION, booking.getStatus());
        assertNull(booking.getPreviousStatus());
        assertNull(booking.getCommandSentAt());
        assertEquals(1, repositoryStub.saveCount());
    }

    @Test
    void handleError_forNonPendingBooking_doesNothing() {
        Booking booking = createBooking();
        repositoryStub.add(1L, booking);

        bookingService.handleError(REQUEST_ID);

        assertEquals(BookingStatus.AWAIT_CONFIRMATION, booking.getStatus());
        assertEquals(0, repositoryStub.saveCount());
    }

    private Booking createBooking() {
        Booking booking = Booking.create(
                1L,
                1L,
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 22),
                NOW
        );
        booking.setCatalogRequestId(REQUEST_ID);
        return booking;
    }

    private static final class RecordingBookingEventPublisher extends BookingEventPublisher {

        private CancelBookingJobByRequestIdRequest publishedCancelCommand;

        private RecordingBookingEventPublisher() {
            super(null, null);
        }

        @Override
        public void publishCancelBookingJob(CancelBookingJobByRequestIdRequest request) {
            publishedCancelCommand = request;
        }

        private CancelBookingJobByRequestIdRequest publishedCancelCommand() {
            return publishedCancelCommand;
        }
    }

    private static final class BookingRepositoryStub {

        private final Map<Long, Booking> bookingsById = new HashMap<>();
        private int saveCount;

        private BookingRepository repository() {
            return (BookingRepository) Proxy.newProxyInstance(
                    BookingRepository.class.getClassLoader(),
                    new Class<?>[]{BookingRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "findById" -> Optional.ofNullable(bookingsById.get((Long) args[0]));
                        case "findByCatalogRequestId" -> bookingsById.values().stream()
                                .filter(booking -> args[0].equals(booking.getCatalogRequestId()))
                                .findFirst();
                        case "save" -> {
                            saveCount++;
                            yield args[0];
                        }
                        case "toString" -> "BookingRepositoryStub";
                        default -> throw new UnsupportedOperationException(
                                "Метод не реализован в тестовом репозитории: " + method.getName()
                        );
                    }
            );
        }

        private void add(Long id, Booking booking) {
            bookingsById.put(id, booking);
        }

        private int saveCount() {
            return saveCount;
        }
    }
}
