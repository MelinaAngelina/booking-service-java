package com.booking.service.service;

import com.booking.service.config.CurrentDateTimeProvider;
import com.booking.service.controller.BookingHistoryController;
import com.booking.service.dto.response.BookingStatusHistoryResponse;
import com.booking.service.entity.Booking;
import com.booking.service.entity.BookingStatus;
import com.booking.service.entity.BookingStatusHistory;
import com.booking.service.exception.GlobalExceptionHandler;
import com.booking.service.messaging.contracts.CancelBookingJobByRequestIdRequest;
import com.booking.service.messaging.contracts.CreateBookingJobRequest;
import com.booking.service.messaging.listener.BookingEventPublisher;
import com.booking.service.repository.BookingRepository;
import com.booking.service.repository.BookingStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Реальные репозитории и транзакционный сервис; таблицы создаёт именно Liquibase.
 * Внешний Catalog заменён отправителем без сети. Тесты не обёрнуты общей транзакцией:
 * проверяем результат настоящего commit/rollback каждого вызова сервиса.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:booking-history;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.liquibase.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BookingService.class, BookingHistoryIntegrationTest.Dependencies.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestExecutionListeners(
        listeners = {DependencyInjectionTestExecutionListener.class, TransactionalTestExecutionListener.class},
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS
)
class BookingHistoryIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-18T12:00:00Z");

    @Autowired private BookingService service;
    @Autowired private BookingRepository bookings;
    @Autowired private BookingStatusHistoryRepository histories;
    @Autowired private TestPublisher publisher;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        publisher.fail = false;
        histories.deleteAllInBatch();
        bookings.deleteAllInBatch();
        mvc = MockMvcBuilders.standaloneSetup(new BookingHistoryController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void migration_createsHistoryTableForeignKeyAndOrderedIndex() {
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID = '3' AND AUTHOR = 'booking-service'",
                Integer.class));
        assertEquals(List.of("BOOKING_ID", "CHANGED_AT"), jdbc.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS " +
                        "WHERE INDEX_NAME = 'IDX_BOOKING_HISTORY_BOOKING_CHANGED_AT' ORDER BY ORDINAL_POSITION",
                String.class));
        assertEquals("DESC", jdbc.queryForObject(
                "SELECT ORDERING_SPECIFICATION FROM INFORMATION_SCHEMA.INDEX_COLUMNS " +
                        "WHERE INDEX_NAME = 'IDX_BOOKING_HISTORY_BOOKING_CHANGED_AT' AND COLUMN_NAME = 'CHANGED_AT'",
                String.class));

        assertThrows(DataIntegrityViolationException.class, () -> histories.saveAndFlush(
                new BookingStatusHistory(-1L, null, BookingStatus.AWAIT_CONFIRMATION,
                        NOW, "Несуществующее бронирование", "System")));
    }

    @Test
    void createBooking_commitsInitialHistoryWithGeneratedBookingId() {
        Long id = createBooking();
        Booking booking = bookings.findById(id).orElseThrow();
        BookingStatusHistory history = historyOf(id).getFirst();

        assertEquals(1, historyOf(id).size());
        assertNotNull(history.getId());
        assertEquals(id, history.getBookingId());
        assertNull(history.getPreviousStatus());
        assertEquals(booking.getStatus(), history.getNewStatus());
        assertEquals(BookingStatus.AWAIT_CONFIRMATION, history.getNewStatus());
        assertEquals(NOW.toInstant(), history.getChangedAt().toInstant());
        assertEquals("Бронирование создано", history.getReason());
        assertEquals("7", history.getInitiator());
    }

    @Test
    void transitions_preserveEarlierHistoryAndRecordRollback() {
        Long id = createBooking();
        Booking booking = bookings.findById(id).orElseThrow();

        service.handleBookingJobConfirmed(booking.getCatalogRequestId());
        service.cancelBooking(id);
        service.handleError(booking.getCatalogRequestId());

        List<BookingStatusHistory> history = historyOf(id);
        assertEquals(List.of(BookingStatus.CONFIRMED, BookingStatus.CANCELLATION_PENDING,
                        BookingStatus.CONFIRMED, BookingStatus.AWAIT_CONFIRMATION),
                history.stream().map(BookingStatusHistory::getNewStatus).toList());
        assertEquals(List.of("System", "7", "System", "7"),
                history.stream().map(BookingStatusHistory::getInitiator).toList());
        assertEquals(BookingStatus.CANCELLATION_PENDING, history.getFirst().getPreviousStatus());
        assertEquals(BookingStatus.CONFIRMED, bookings.findById(id).orElseThrow().getStatus());
    }

    @Test
    void creationFailure_rollsBackBookingAndHistoryTogether() {
        publisher.fail = true;

        assertThrows(IllegalStateException.class, this::createBooking);

        assertEquals(0, bookings.count());
        assertEquals(0, histories.count());
    }

    @Test
    void cancellationFailure_rollsBackStatusAndNewHistoryTogether() {
        Long id = createBooking();
        Long originalHistoryId = historyOf(id).getFirst().getId();
        publisher.fail = true;

        assertThrows(IllegalStateException.class, () -> service.cancelBooking(id));

        Booking booking = bookings.findById(id).orElseThrow();
        assertEquals(BookingStatus.AWAIT_CONFIRMATION, booking.getStatus());
        assertNull(booking.getPreviousStatus());
        assertNull(booking.getCommandSentAt());
        assertEquals(List.of(originalHistoryId), historyOf(id).stream()
                .map(BookingStatusHistory::getId).toList());
    }

    @Test
    void auditInsertFailure_preventsBookingStatusCommit() {
        Long id = createBooking();
        // Временное ограничение только в тестовой БД имитирует отказ записи аудита.
        jdbc.execute("ALTER TABLE booking_status_history ADD CONSTRAINT test_reject_pending " +
                "CHECK (new_status <> 'CANCELLATION_PENDING')");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> service.cancelBooking(id));
            assertEquals(BookingStatus.AWAIT_CONFIRMATION, bookings.findById(id).orElseThrow().getStatus());
            assertEquals(1, historyOf(id).size());
        } finally {
            jdbc.execute("ALTER TABLE booking_status_history DROP CONSTRAINT test_reject_pending");
        }
    }

    @Test
    void historyQuery_filtersBookingPaginatesAndBreaksTimestampTiesById() {
        Long id = createBooking();
        Long otherId = createBooking();
        BookingStatusHistory older = saveHistory(id, NOW.minusMinutes(1), "Старое событие");
        BookingStatusHistory newer = saveHistory(id, NOW.plusMinutes(1), "Новое событие");
        BookingStatusHistory tied = saveHistory(id, NOW.plusMinutes(1), "То же время, больший id");
        saveHistory(otherId, NOW.plusDays(1), "Чужая история");

        Page<BookingStatusHistoryResponse> first = service.getHistory(id, 0, 2);
        Page<BookingStatusHistoryResponse> second = service.getHistory(id, 1, 2);
        Page<BookingStatusHistoryResponse> beyondEnd = service.getHistory(id, 2, 2);

        assertEquals(4, first.getTotalElements());
        assertEquals(2, first.getTotalPages());
        assertTrue(first.hasNext());
        assertEquals(List.of(tied.getId(), newer.getId()), first.getContent().stream()
                .map(BookingStatusHistoryResponse::id).toList());
        assertEquals(older.getId(), second.getContent().getLast().id());
        assertFalse(second.hasNext());
        assertTrue(beyondEnd.isEmpty());
        assertEquals(4, beyondEnd.getTotalElements());

        BookingStatusHistoryResponse dto = first.getContent().getFirst();
        assertEquals(tied.getBookingId(), dto.bookingId());
        assertEquals(tied.getPreviousStatus(), dto.previousStatus());
        assertEquals(tied.getNewStatus(), dto.newStatus());
        assertEquals(tied.getChangedAt().toInstant(), dto.changedAt().toInstant());
        assertEquals(tied.getReason(), dto.reason());
        assertEquals(tied.getInitiator(), dto.initiator());
    }

    @Test
    void endpoint_returnsSortedPageAsJson() throws Exception {
        Long id = createBooking();
        Long otherId = createBooking();
        BookingStatusHistory expected = saveHistory(id, NOW.plusMinutes(1), "Подтверждение");
        saveHistory(otherId, NOW.plusDays(1), "Чужая история");

        mvc.perform(get("/api/bookings/{id}/history", id).param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(expected.getId()))
                .andExpect(jsonPath("$.content[0].bookingId").value(id))
                .andExpect(jsonPath("$.content[0].previousStatus").value("AWAIT_CONFIRMATION"))
                .andExpect(jsonPath("$.content[0].newStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.content[0].changedAt").exists())
                .andExpect(jsonPath("$.content[0].reason").value("Подтверждение"))
                .andExpect(jsonPath("$.content[0].initiator").value("System"))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        mvc.perform(get("/api/bookings/{id}/history", id).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].reason").value("Бронирование создано"))
                .andExpect(jsonPath("$.number").value(1));
    }

    @Test
    void endpoint_usesDefaultPageParameters() throws Exception {
        Long id = createBooking();

        mvc.perform(get("/api/bookings/{id}/history", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void endpoint_forExistingBookingWithoutHistory_returnsEmptyPage() throws Exception {
        // Старое бронирование, созданное до внедрения аудита.
        Booking booking = bookings.saveAndFlush(Booking.create(7L, 2L,
                NOW.toLocalDate().plusDays(1), NOW.toLocalDate().plusDays(2), NOW));

        mvc.perform(get("/api/bookings/{id}/history", booking.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void endpoint_forUnknownBooking_returnsExistingBusinessError() throws Exception {
        mvc.perform(get("/api/bookings/{id}/history", -1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Business Error"));
    }

    @ParameterizedTest
    @CsvSource({"-1,10", "0,0", "0,-1", "text,10", "0,text", "2147483648,10"})
    void endpoint_rejectsInvalidPageParameters(String page, String size) throws Exception {
        Long id = createBooking();
        mvc.perform(get("/api/bookings/{id}/history", id).param("page", page).param("size", size))
                .andExpect(status().isBadRequest());
    }

    @Test
    void endpoint_rejectsNonNumericBookingId() throws Exception {
        mvc.perform(get("/api/bookings/text/history"))
                .andExpect(status().isBadRequest());
    }

    private Long createBooking() {
        return service.createBooking(7L, 2L, NOW.toLocalDate().plusDays(1), NOW.toLocalDate().plusDays(2));
    }

    private List<BookingStatusHistory> historyOf(Long id) {
        return histories.findByBookingIdOrderByChangedAtDescIdDesc(id, PageRequest.of(0, 100)).getContent();
    }

    private BookingStatusHistory saveHistory(Long bookingId, OffsetDateTime time, String reason) {
        return histories.saveAndFlush(new BookingStatusHistory(bookingId,
                BookingStatus.AWAIT_CONFIRMATION, BookingStatus.CONFIRMED, time, reason, "System"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean
        CurrentDateTimeProvider dateTimeProvider() {
            return () -> NOW;
        }

        @Bean
        TestPublisher publisher() {
            return new TestPublisher();
        }
    }

    static class TestPublisher extends BookingEventPublisher {
        private boolean fail;

        TestPublisher() {
            super(null, null);
        }

        @Override
        public void publishCreateBookingJob(CreateBookingJobRequest request) {
            failIfRequested();
        }

        @Override
        public void publishCancelBookingJob(CancelBookingJobByRequestIdRequest request) {
            failIfRequested();
        }

        private void failIfRequested() {
            if (fail) {
                throw new IllegalStateException("Test publisher failure");
            }
        }
    }
}
