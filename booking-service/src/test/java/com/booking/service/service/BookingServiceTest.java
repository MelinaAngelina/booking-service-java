package com.booking.service.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import com.booking.service.dto.response.BookingStatisticsResponse;
import com.booking.service.dto.response.TopResourceResponse;
import com.booking.service.entity.Booking;
import com.booking.service.entity.BookingStatus;
import com.booking.service.entity.BookingStatusHistory;
import com.booking.service.exception.BusinessException;
import com.booking.service.messaging.contracts.CancelBookingJobByRequestIdRequest;
import com.booking.service.messaging.contracts.CreateBookingJobRequest;
import com.booking.service.messaging.listener.BookingEventPublisher;
import com.booking.service.repository.BookingRepository;
import com.booking.service.repository.BookingStatusHistoryRepository;
import com.booking.service.repository.projection.BookingCountByStatusProjection;
import com.booking.service.repository.projection.TopResourceProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static java.time.ZoneOffset.UTC;

class BookingServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-18T12:00:00+03:00");
    private static final UUID REQUEST_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final LocalDate DATE_FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate DATE_TO = LocalDate.of(2026, 8, 31);

    private BookingRepositoryStub repositoryStub;
    private RecordingBookingEventPublisher eventPublisher;
    private HistoryRepositoryStub historyRepositoryStub;
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        repositoryStub = new BookingRepositoryStub();
        eventPublisher = new RecordingBookingEventPublisher();
        historyRepositoryStub = new HistoryRepositoryStub();
        bookingService = new BookingService(
                repositoryStub.repository(),
                eventPublisher,
                historyRepositoryStub.repository(),
                () -> NOW
        );
    }

    @Test
    void createBooking_recordsInitialStatusAndUser() {
        Long id = bookingService.createBooking(7L, 2L,
                NOW.toLocalDate().plusDays(1), NOW.toLocalDate().plusDays(2));

        assertNotNull(id);
        assertNotNull(eventPublisher.publishedCreateCommand);
        assertHistory(id, null, BookingStatus.AWAIT_CONFIRMATION,
                "Бронирование создано", "7");
    }

    @Test
    void confirmation_recordsSystemTransition() {
        repositoryStub.add(42L, createBooking());

        bookingService.handleBookingJobConfirmed(REQUEST_ID);

        assertHistory(42L, BookingStatus.AWAIT_CONFIRMATION, BookingStatus.CONFIRMED,
                "Получено подтверждение Catalog", "System");
    }

    @Test
    void denial_forAwaitingBooking_recordsCancellation() {
        repositoryStub.add(42L, createBooking());

        bookingService.handleBookingJobDenied(REQUEST_ID);

        assertHistory(42L, BookingStatus.AWAIT_CONFIRMATION, BookingStatus.CANCELLED,
                "Получено событие отказа Catalog", "System");
    }

    @Test
    void cancellation_forConfirmedBooking_recordsActualOldStatus() {
        Booking booking = createBooking();
        booking.confirm();
        repositoryStub.add(42L, booking);

        bookingService.cancelBooking(42L);

        assertHistory(42L, BookingStatus.CONFIRMED, BookingStatus.CANCELLATION_PENDING,
                "Пользователь запросил отмену", "1");
    }

    @Test
    void rollback_forPreviouslyConfirmedBooking_recordsRestoredStatus() {
        Booking booking = createBooking();
        booking.confirm();
        booking.beginCancellation(NOW);
        repositoryStub.add(42L, booking);

        bookingService.handleError(REQUEST_ID);

        assertHistory(42L, BookingStatus.CANCELLATION_PENDING, BookingStatus.CONFIRMED,
                "Отмена не выполнена: получена ошибка из DLQ, статус восстановлен", "System");
    }

    @Test
    void rejectedTransition_doesNotRecordHistory() {
        Booking booking = createBooking();
        booking.cancel(NOW.toLocalDate());
        repositoryStub.add(42L, booking);

        assertThrows(BusinessException.class, () -> bookingService.cancelBooking(42L));
        assertThrows(BusinessException.class, () -> bookingService.handleBookingJobConfirmed(REQUEST_ID));
        assertThrows(BusinessException.class, () -> bookingService.handleBookingJobDenied(REQUEST_ID));

        assertTrue(historyRepositoryStub.saved.isEmpty());
        assertEquals(0, repositoryStub.saveCount());
    }

    @Test
    void unknownBooking_doesNotRecordHistory() {
        assertThrows(BusinessException.class, () -> bookingService.cancelBooking(42L));
        bookingService.handleBookingJobConfirmed(REQUEST_ID);
        bookingService.handleBookingJobDenied(REQUEST_ID);
        bookingService.handleError(REQUEST_ID);

        assertTrue(historyRepositoryStub.saved.isEmpty());
        assertEquals(0, repositoryStub.saveCount());
    }

    @Test
    void confirmationDuringCancellation_logsWarningAndIgnoresLaterDlqRollback() {
        Booking booking = createBooking();
        booking.beginCancellation(NOW);
        repositoryStub.add(1L, booking);
        Logger logger = (Logger) LoggerFactory.getLogger(BookingService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            bookingService.handleBookingJobConfirmed(REQUEST_ID);
            assertTrue(appender.list.stream().anyMatch(event ->
                    event.getLevel() == Level.WARN && event.getFormattedMessage().contains("Race condition")));
            assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
            assertNull(booking.getPreviousStatus());
            assertNull(booking.getCommandSentAt());

            bookingService.handleError(REQUEST_ID);
            assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
            assertEquals(1, repositoryStub.saveCount());
            assertHistory(1L, BookingStatus.CANCELLATION_PENDING, BookingStatus.CONFIRMED,
                    "Получено подтверждение Catalog", "System");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
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

        assertEquals(1, historyRepositoryStub.saved.size());
        BookingStatusHistory history = historyRepositoryStub.saved.getFirst();
        assertEquals(1L, history.getBookingId());
        assertEquals(BookingStatus.AWAIT_CONFIRMATION, history.getPreviousStatus());
        assertEquals(BookingStatus.CANCELLATION_PENDING, history.getNewStatus());
        assertEquals(NOW, history.getChangedAt());
        assertEquals("Пользователь запросил отмену", history.getReason());
        assertEquals("1", history.getInitiator());
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
        assertHistory(1L, BookingStatus.CANCELLATION_PENDING, BookingStatus.CANCELLED,
                "Получено событие отказа Catalog", "System");
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
        assertHistory(1L, BookingStatus.CANCELLATION_PENDING, BookingStatus.AWAIT_CONFIRMATION,
                "Отмена не выполнена: получена ошибка из DLQ, статус восстановлен", "System");
    }

    @Test
    void handleError_forNonPendingBooking_doesNothing() {
        Booking booking = createBooking();
        repositoryStub.add(1L, booking);

        bookingService.handleError(REQUEST_ID);

        assertEquals(BookingStatus.AWAIT_CONFIRMATION, booking.getStatus());
        assertEquals(0, repositoryStub.saveCount());
        assertTrue(historyRepositoryStub.saved.isEmpty());
    }

    @Test
    void getStatistics_returnsAggregatedStatistics() {
        repositoryStub.setStatistics(
                10L,
                List.of(
                        new BookingCountByStatusProjectionStub(BookingStatus.CONFIRMED, 6L),
                        new BookingCountByStatusProjectionStub(BookingStatus.CANCELLED, 4L),
                        new BookingCountByStatusProjectionStub(BookingStatus.NONE, 99L)
                ),
                List.of(
                        new TopResourceProjectionStub(2L, 5L),
                        new TopResourceProjectionStub(7L, 3L)
                )
        );

        BookingStatisticsResponse result = bookingService.getStatistics(DATE_FROM, DATE_TO);

        assertEquals(10L, result.totalCount());
        assertEquals(6L, result.countByStatus().get(BookingStatus.CONFIRMED));
        assertEquals(4L, result.countByStatus().get(BookingStatus.CANCELLED));
        assertEquals(0L, result.countByStatus().get(BookingStatus.AWAIT_CONFIRMATION));
        assertEquals(0L, result.countByStatus().get(BookingStatus.CANCELLATION_PENDING));
        assertEquals(4, result.countByStatus().size());
        assertEquals(2, result.topResources().size());
        assertEquals(new TopResourceResponse(2L, 5L), result.topResources().get(0));
        assertEquals(DATE_FROM.atStartOfDay().atOffset(UTC), repositoryStub.requestedDateFrom());
        assertEquals(DATE_TO.atTime(LocalTime.MAX).atOffset(UTC), repositoryStub.requestedDateTo());
        assertEquals(0, repositoryStub.requestedPageable().getPageNumber());
        assertEquals(5, repositoryStub.requestedPageable().getPageSize());
    }

    @Test
    void getStatistics_forEmptyRepository_returnsEmptyStatistics() {
        repositoryStub.setStatistics(0L, List.of(), List.of());

        BookingStatisticsResponse result = bookingService.getStatistics(DATE_FROM, DATE_TO);

        assertEquals(0L, result.totalCount());
        assertEquals(Map.of(
                BookingStatus.AWAIT_CONFIRMATION, 0L,
                BookingStatus.CONFIRMED, 0L,
                BookingStatus.CANCELLED, 0L,
                BookingStatus.CANCELLATION_PENDING, 0L
        ), result.countByStatus());
        assertEquals(List.of(), result.topResources());
    }

    private void assertHistory(Long bookingId, BookingStatus previousStatus, BookingStatus newStatus,
                               String reason, String initiator) {
        assertEquals(1, historyRepositoryStub.saved.size());
        BookingStatusHistory history = historyRepositoryStub.saved.getFirst();
        assertEquals(bookingId, history.getBookingId());
        assertEquals(previousStatus, history.getPreviousStatus());
        assertEquals(newStatus, history.getNewStatus());
        assertEquals(NOW, history.getChangedAt());
        assertEquals(reason, history.getReason());
        assertEquals(initiator, history.getInitiator());
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
        private CreateBookingJobRequest publishedCreateCommand;

        private RecordingBookingEventPublisher() {
            super(null, null);
        }

        @Override
        public void publishCreateBookingJob(CreateBookingJobRequest request) {
            publishedCreateCommand = request;
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
        private long totalCount;
        private List<BookingCountByStatusProjection> statusCounts = List.of();
        private List<TopResourceProjection> topResources = List.of();
        private OffsetDateTime requestedDateFrom;
        private OffsetDateTime requestedDateTo;
        private Pageable requestedPageable;

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
                            Booking booking = (Booking) args[0];
                            if (booking.getId() == null) {
                                long id = bookingsById.keySet().stream().mapToLong(Long::longValue)
                                        .max().orElse(0L) + 1;
                                add(id, booking);
                            }
                            yield booking;
                        }
                        case "countBookingsWithinPeriod" -> {
                            requestedDateFrom = (OffsetDateTime) args[0];
                            requestedDateTo = (OffsetDateTime) args[1];
                            yield totalCount;
                        }
                        case "countBookingsByStatus" -> statusCounts;
                        case "findTopResources" -> {
                            requestedPageable = (Pageable) args[2];
                            yield topResources;
                        }
                        case "toString" -> "BookingRepositoryStub";
                        default -> throw new UnsupportedOperationException(
                                "Метод не реализован в тестовом репозитории: " + method.getName()
                        );
                    }
            );
        }

        private void add(Long id, Booking booking) {
            ReflectionTestUtils.setField(booking, "id", id);
            bookingsById.put(id, booking);
        }

        private int saveCount() {
            return saveCount;
        }

        private void setStatistics(
                long totalCount,
                List<BookingCountByStatusProjection> statusCounts,
                List<TopResourceProjection> topResources
        ) {
            this.totalCount = totalCount;
            this.statusCounts = statusCounts;
            this.topResources = topResources;
        }

        private Pageable requestedPageable() {
            return requestedPageable;
        }

        private OffsetDateTime requestedDateFrom() {
            return requestedDateFrom;
        }

        private OffsetDateTime requestedDateTo() {
            return requestedDateTo;
        }
    }

    private record BookingCountByStatusProjectionStub(
            BookingStatus status,
            Long bookingCount
    ) implements BookingCountByStatusProjection {

        @Override
        public BookingStatus getStatus() {
            return status;
        }

        @Override
        public Long getBookingCount() {
            return bookingCount;
        }
    }

    private record TopResourceProjectionStub(
            Long resourceId,
            Long bookingCount
    ) implements TopResourceProjection {

        @Override
        public Long getResourceId() {
            return resourceId;
        }

        @Override
        public Long getBookingCount() {
            return bookingCount;
        }
    }

    private static final class HistoryRepositoryStub {

        private final List<BookingStatusHistory> saved = new ArrayList<>();

        private BookingStatusHistoryRepository repository() {
            return (BookingStatusHistoryRepository) Proxy.newProxyInstance(
                    BookingStatusHistoryRepository.class.getClassLoader(),
                    new Class<?>[]{BookingStatusHistoryRepository.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("save")) {
                            BookingStatusHistory history =
                                    (BookingStatusHistory) args[0];

                            saved.add(history);
                            return history;
                        }

                        throw new UnsupportedOperationException(
                                "Метод не реализован в заглушке: " + method.getName()
                        );
                    }
            );
        }
    }
}
