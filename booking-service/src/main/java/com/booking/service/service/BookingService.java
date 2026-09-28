package com.booking.service.service;

import com.booking.service.config.CurrentDateTimeProvider;
import com.booking.service.dto.response.BookingStatisticsResponse;
import com.booking.service.dto.response.BookingStatusHistoryResponse;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.time.ZoneOffset.UTC;

/**
 * Сервис для работы с бронированиями
 * Объединяет CRUD операции, бизнес-логику и обработку событий
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingEventPublisher bookingEventPublisher;
    private final BookingStatusHistoryRepository bookingStatusHistoryRepository;
    private final CurrentDateTimeProvider dateTimeProvider;

    // === КОМАНДЫ (Use Cases) ===

    /**
     * Создать новое бронирование
     * Отправляет асинхронную команду в Catalog Service для создания booking job
     *
     * @return ID созданного бронирования
     */
    public Long createBooking(Long userId, Long resourceId, LocalDate bookedFrom, LocalDate bookedTo) {
        Booking booking = Booking.create(userId, resourceId, bookedFrom, bookedTo, dateTimeProvider.utcNow());

        UUID requestId = UUID.randomUUID();
        booking.setCatalogRequestId(requestId);

        booking = bookingRepository.save(booking);

        saveHistory(
                booking,
                null,
                "Бронирование создано",
                String.valueOf(userId)
        );

        CreateBookingJobRequest command = new CreateBookingJobRequest(
                UUID.randomUUID(),
                requestId,
                booking.getResourceId(),
                booking.getBookedFrom(),
                booking.getBookedTo()
        );

        bookingEventPublisher.publishCreateBookingJob(command);

        log.info("Создано бронирование с ID: {} и requestId: {}", booking.getId(), requestId);
        return booking.getId();
    }

    /**
     * Отменить бронирование
     * Отправляет асинхронную команду в Catalog Service для отмены booking job
     *
     * @param id идентификатор бронирования
     */
    public void cancelBooking(Long id) {
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Бронирование с указанным id: '" + id + "' не найдено."));

        OffsetDateTime now = dateTimeProvider.utcNow();
        BookingStatus previousStatus = booking.getStatus();
        booking.beginCancellation(now);

        bookingRepository.save(booking);

        saveHistory(
                booking,
                previousStatus,
                "Пользователь запросил отмену",
                String.valueOf(booking.getUserId())
        );

        if (booking.getCatalogRequestId() != null) {
            CancelBookingJobByRequestIdRequest command = new CancelBookingJobByRequestIdRequest(
                    UUID.randomUUID(),
                    booking.getCatalogRequestId()
            );

            bookingEventPublisher.publishCancelBookingJob(command);
        }

        log.info("Запущена отмена бронирования с ID: {}", id);
    }

    // === ЗАПРОСЫ (Queries) ===

    /**
     * Получить бронирование по ID
     *
     * @param id идентификатор бронирования
     * @return бронирование
     */
    @Transactional(readOnly = true)
    public Booking getById(Long id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Бронирование с указанным id: '" + id + "' не найдено."));
    }

    /**
     * Получить историю бронирования: новые записи первыми.
     * При одинаковом времени записи упорядочиваются по ID по убыванию.
     *
     * @param bookingId идентификатор бронирования
     * @param page номер страницы, начиная с нуля
     * @param size положительный размер страницы
     * @return страница истории с общим количеством записей и страниц
     */
    @Transactional(readOnly = true)
    public Page<BookingStatusHistoryResponse> getHistory(
            Long bookingId,
            int page,
            int size
    ) {
        if (page < 0 || size <= 0) {
            throw new BusinessException(
                    "Номер страницы должен быть неотрицательным, а размер — положительным"
            );
        }

        getById(bookingId);

        Pageable pageable = PageRequest.of(page, size);

        Page<BookingStatusHistory> history =
                bookingStatusHistoryRepository
                        .findByBookingIdOrderByChangedAtDescIdDesc(
                                bookingId,
                                pageable
                        );

        return history.map(entry -> new BookingStatusHistoryResponse(
                entry.getId(),
                entry.getBookingId(),
                entry.getPreviousStatus(),
                entry.getNewStatus(),
                entry.getChangedAt(),
                entry.getReason(),
                entry.getInitiator()
        ));
    }

    /**
     * Получить бронирования по фильтрам с пагинацией
     *
     * @param userId идентификатор пользователя (опционально)
     * @param resourceId идентификатор ресурса (опционально)
     * @param status статус бронирования (опционально)
     * @param pageNumber номер страницы
     * @param pageSize размер страницы
     * @return страница с бронированиями
     */
    @Transactional(readOnly = true)
    public List<Booking> getByFilter(Long userId, Long resourceId, BookingStatus status,
                                     int pageNumber, int pageSize) {
        Pageable pageable = PageRequest.of(pageNumber, pageSize);
        return bookingRepository.findByFilter(userId, resourceId, status, pageable);
    }

    /**
     * Получить только статус бронирования по ID
     *
     * @param id идентификатор бронирования
     * @return статус бронирования или null
     */
    @Transactional(readOnly = true)
    public BookingStatus getStatusById(Long id) {
        return bookingRepository.findStatusById(id);
    }

    /**
     * Получить агрегированную статистику по бронированиям:
     * общее количество, количество по статусам и топ-5 ресурсов.
     *
     * @param dateFrom начало периода включительно
     * @param dateTo окончание периода включительно
     * @return статистика по бронированиям
     */
    @Transactional(readOnly = true)
    public BookingStatisticsResponse getStatistics(LocalDate dateFrom, LocalDate dateTo) {
        OffsetDateTime periodStart = dateFrom.atStartOfDay().atOffset(UTC);
        OffsetDateTime periodEnd = dateTo.atTime(LocalTime.MAX).atOffset(UTC);

        long totalCount = bookingRepository.countBookingsWithinPeriod(periodStart, periodEnd);

        List<BookingCountByStatusProjection> statusCounts =
                bookingRepository.countBookingsByStatus(periodStart, periodEnd);

        Map<BookingStatus, Long> countByStatus =
                new EnumMap<>(BookingStatus.class);

        for (BookingStatus status : BookingStatus.values()) {
            if (status != BookingStatus.NONE) {
                countByStatus.put(status, 0L);
            }
        }

        for (BookingCountByStatusProjection statusCount : statusCounts) {
            if (statusCount.getStatus() != BookingStatus.NONE) {
                countByStatus.put(
                        statusCount.getStatus(),
                        statusCount.getBookingCount()
                );
            }
        }

        Pageable topFive = PageRequest.of(0, 5);

        List<TopResourceProjection> topResourceProjections =
                bookingRepository.findTopResources(periodStart, periodEnd, topFive);

        List<TopResourceResponse> topResources = new ArrayList<>();

        for (TopResourceProjection projection : topResourceProjections) {
            topResources.add(
                    new TopResourceResponse(
                            projection.getResourceId(),
                            projection.getBookingCount()
                    )
            );
        }

        return new BookingStatisticsResponse(
                totalCount,
                countByStatus,
                topResources
        );
    }

    // === EVENT HANDLERS (Обработка асинхронных событий от Catalog Service) ===

    /**
     * Обработать событие подтверждения booking job от Catalog Service
     * Обновляет статус бронирования на CONFIRMED
     *
     * @param requestId идентификатор запроса
     */
    @Transactional
    public void handleBookingJobConfirmed(UUID requestId) {
        log.info("Получено событие BookingJobConfirmed: requestId={}", requestId);

        Booking booking = bookingRepository.findByCatalogRequestId(requestId).orElse(null);
        if (booking == null) {
            log.warn("Бронирование не найдено по requestId: {}. Событие проигнорировано.", requestId);
            return;
        }

        log.info("Найдено бронирование: id={}, статус={}. Подтверждаем...",
                booking.getId(), booking.getStatus());

        if (booking.getStatus() == BookingStatus.CANCELLATION_PENDING) {
            log.warn("Race condition: подтверждение Catalog пришло во время отмены бронирования id={}, requestId={}. Принимаем подтверждение.",
                    booking.getId(), requestId);
        }
        BookingStatus previousStatus = booking.getStatus();

        booking.confirm();
        bookingRepository.save(booking);

        saveHistory(
                booking,
                previousStatus,
                "Получено подтверждение Catalog",
                "System"
        );

        log.info("Бронирование успешно подтверждено: id={}, новый статус={}",
                booking.getId(), booking.getStatus());
    }

    /**
     * Обработать событие отклонения booking job от Catalog Service
     * Отменяет бронирование
     *
     * @param requestId идентификатор запроса
     */
    @Transactional
    public void handleBookingJobDenied(UUID requestId) {
        log.info("Получено событие BookingJobDenied: requestId={}", requestId);

        Booking booking = bookingRepository.findByCatalogRequestId(requestId).orElse(null);
        if (booking == null) {
            log.warn("Бронирование не найдено по requestId: {}. Событие проигнорировано.", requestId);
            return;
        }

        log.info("Найдено бронирование: id={}, статус={}. Отменяем...",
                booking.getId(), booking.getStatus());

        LocalDate currentDate = LocalDate.from(dateTimeProvider.utcNow());
        BookingStatus previousStatus = booking.getStatus();

        booking.cancel(currentDate);
        bookingRepository.save(booking);

        saveHistory(
                booking,
                previousStatus,
                "Получено событие отказа Catalog",
                "System"
        );

        log.info("Бронирование успешно отменено: id={}, новый статус={}",
                booking.getId(), booking.getStatus());
    }

    /**
     * Обработать событие ошибки от Catalog Service
     *
     * @param requestId идентификатор запроса
     */
    @Transactional
    public void handleError(UUID requestId) {
        log.info("Получено событие ошибки из DLQ: requestId={}", requestId);

        Booking booking = bookingRepository.findByCatalogRequestId(requestId).orElse(null);

        if (booking == null) {
            log.warn("Бронирование не найдено по requestId: {}. Событие проигнорировано.", requestId);
            return;
        }

        if (booking.getStatus() != BookingStatus.CANCELLATION_PENDING) {
            log.warn(
                    "Бронирование id={} находится в статусе {}. Откат отмены не требуется, событие проигнорировано.",
                    booking.getId(),
                    booking.getStatus()
            );
            return;
        }
        BookingStatus previousStatus = booking.getStatus();

        booking.rollbackCancellation();
        bookingRepository.save(booking);

        saveHistory(
                booking,
                previousStatus,
                "Отмена не выполнена: получена ошибка из DLQ, статус восстановлен",
                "System"
        );
        log.info("Rollback успешно выполнен: id={}, новый статус={}",
                booking.getId(), booking.getStatus());
    }

    /**
     * Добавить запись аудита в текущей транзакции изменения бронирования.
     * При создании previousStatus равен null; автоматический инициатор — "System".
     */
    private void saveHistory(
            Booking booking,
            BookingStatus previousStatus,
            String reason,
            String initiator
    ) {
        BookingStatusHistory history = new BookingStatusHistory(
                booking.getId(),
                previousStatus,
                booking.getStatus(),
                dateTimeProvider.utcNow(),
                reason,
                initiator
        );

        bookingStatusHistoryRepository.save(history);
    }
}
