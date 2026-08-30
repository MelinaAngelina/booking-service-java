package com.booking.service.repository;

import com.booking.service.entity.Booking;
import com.booking.service.entity.BookingStatus;
import com.booking.service.repository.projection.BookingCountByStatusProjection;
import com.booking.service.repository.projection.TopResourceProjection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {
        "spring.liquibase.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestExecutionListeners(
        listeners = {
                DependencyInjectionTestExecutionListener.class,
                TransactionalTestExecutionListener.class
        },
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS
)
class BookingRepositoryTest {

    private static final OffsetDateTime PERIOD_START = OffsetDateTime.parse("2026-08-10T00:00:00Z");
    private static final OffsetDateTime PERIOD_END = OffsetDateTime.parse("2026-08-20T23:59:59.999999Z");
    private static final OffsetDateTime INSIDE_PERIOD = OffsetDateTime.parse("2026-08-15T12:00:00Z");

    private final AtomicLong nextUserId = new AtomicLong(1L);

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void countBookingsWithinPeriod_filtersByCreatedAtAndIncludesBoundaries() {
        saveBookings(1L, BookingStatus.CONFIRMED, 1, PERIOD_START);
        saveBookings(2L, BookingStatus.CONFIRMED, 1, PERIOD_END);
        saveBookings(3L, BookingStatus.CONFIRMED, 1, PERIOD_START.minusNanos(1_000));
        saveBookings(4L, BookingStatus.CONFIRMED, 1, PERIOD_END.plusNanos(1_000));

        long result = bookingRepository.countBookingsWithinPeriod(PERIOD_START, PERIOD_END);

        assertEquals(2L, result);
    }

    @Test
    void countBookingsByStatus_groupsBookingsByStatus() {
        saveBookings(10L, BookingStatus.CONFIRMED, 3);
        saveBookings(20L, BookingStatus.CANCELLED, 2);
        saveBookings(30L, BookingStatus.AWAIT_CONFIRMATION, 1);
        saveBookings(40L, BookingStatus.CANCELLED, 5, PERIOD_START.minusDays(1));

        List<BookingCountByStatusProjection> projections =
                bookingRepository.countBookingsByStatus(PERIOD_START, PERIOD_END);

        Map<BookingStatus, Long> counts = new EnumMap<>(BookingStatus.class);
        projections.forEach(projection ->
                counts.put(projection.getStatus(), projection.getBookingCount()));

        assertEquals(3L, counts.get(BookingStatus.CONFIRMED));
        assertEquals(2L, counts.get(BookingStatus.CANCELLED));
        assertEquals(1L, counts.get(BookingStatus.AWAIT_CONFIRMATION));
    }

    @Test
    void findTopResources_ordersByCountThenResourceId_andLimitsResult() {
        saveBookings(2L, BookingStatus.CONFIRMED, 6);
        saveBookings(7L, BookingStatus.CONFIRMED, 4);
        saveBookings(9L, BookingStatus.CONFIRMED, 4);
        saveBookings(1L, BookingStatus.CONFIRMED, 2);
        saveBookings(3L, BookingStatus.CONFIRMED, 1);
        saveBookings(4L, BookingStatus.CONFIRMED, 1);
        saveBookings(99L, BookingStatus.CONFIRMED, 10, PERIOD_END.plusDays(1));

        List<TopResourceProjection> result =
                bookingRepository.findTopResources(PERIOD_START, PERIOD_END, PageRequest.of(0, 5));

        assertEquals(List.of(2L, 7L, 9L, 1L, 3L),
                result.stream().map(TopResourceProjection::getResourceId).toList());
        assertEquals(List.of(6L, 4L, 4L, 2L, 1L),
                result.stream().map(TopResourceProjection::getBookingCount).toList());
    }

    private void saveBookings(Long resourceId, BookingStatus status, int count) {
        saveBookings(resourceId, status, count, INSIDE_PERIOD);
    }

    private void saveBookings(
            Long resourceId,
            BookingStatus status,
            int count,
            OffsetDateTime createdAt
    ) {
        for (int i = 0; i < count; i++) {
            Booking booking = Booking.create(
                    nextUserId.getAndIncrement(),
                    resourceId,
                    createdAt.toLocalDate().plusDays(10),
                    createdAt.toLocalDate().plusDays(12),
                    createdAt
            );
            changeStatus(booking, status, createdAt);
            bookingRepository.save(booking);
        }
    }

    private void changeStatus(Booking booking, BookingStatus status, OffsetDateTime createdAt) {
        switch (status) {
            case AWAIT_CONFIRMATION -> {
                // Booking.create already assigns this status.
            }
            case CONFIRMED -> booking.confirm();
            case CANCELLED -> booking.cancel(createdAt.toLocalDate());
            case CANCELLATION_PENDING -> booking.beginCancellation(createdAt);
            case NONE -> throw new IllegalArgumentException("NONE is not a valid persisted status");
        }
    }
}
