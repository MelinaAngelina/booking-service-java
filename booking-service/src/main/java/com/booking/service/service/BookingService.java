package com.booking.service.service;

import com.booking.service.config.CurrentDateTimeProvider;
import com.booking.service.entity.Booking;
import com.booking.service.entity.BookingStatus;
import com.booking.service.exception.BusinessException;
import com.booking.service.messaging.contracts.CancelBookingJobByRequestIdRequest;
import com.booking.service.messaging.contracts.CreateBookingJobRequest;
import com.booking.service.messaging.listener.BookingEventPublisher;
import com.booking.service.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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
        booking.beginCancellation(now);

        bookingRepository.save(booking);

        log.info("Перед отправкой отмены catalogRequestId={}", booking.getCatalogRequestId());

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

        booking.confirm();
        bookingRepository.save(booking);

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
        booking.cancel(currentDate);
        bookingRepository.save(booking);

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
        booking.rollbackCancellation();
        bookingRepository.save(booking);
        log.info("Rollback успешно выполнен: id={}, новый статус={}",
                booking.getId(), booking.getStatus());
    }
}
