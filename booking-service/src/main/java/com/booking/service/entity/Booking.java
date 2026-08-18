package com.booking.service.entity;

import com.booking.service.exception.BusinessException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA Entity для бронирования с инкапсулированной бизнес-логикой
 */
@Entity
@Table(name = "bookings")
@Getter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.ORDINAL)
    @Column(nullable = false)
    private BookingStatus status;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "previous_status")
    private BookingStatus previousStatus;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    @Column(name = "booked_from", nullable = false)
    private LocalDate bookedFrom;

    @Column(name = "booked_to", nullable = false)
    private LocalDate bookedTo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "command_sent_at")
    private OffsetDateTime commandSentAt;


    @Column(name = "catalog_request_id")
    private UUID catalogRequestId;

    /**
     * Factory method для создания нового бронирования с валидацией бизнес-правил
     */
    public static Booking create(Long userId, Long resourceId, LocalDate bookedFrom, LocalDate bookedTo, OffsetDateTime createdAt) {
        if (userId <= 0) {
            throw new BusinessException("Некорректный идентификатор пользователя " + userId);
        }
        if (resourceId <= 0) {
            throw new BusinessException("Некорректный идентификатор ресурса " + resourceId);
        }

        LocalDate currentDate = LocalDate.from(createdAt);
        if (!bookedFrom.isAfter(currentDate)) {
            throw new BusinessException("Дата начала бронирования должна быть больше текущей даты");
        }
        if (bookedTo.isBefore(bookedFrom)) {
            throw new BusinessException("Выбранная дата окончания бронирования раньше даты начала бронирования");
        }

        Booking booking = new Booking();
        booking.status = BookingStatus.AWAIT_CONFIRMATION;
        booking.userId = userId;
        booking.resourceId = resourceId;
        booking.bookedFrom = bookedFrom;
        booking.bookedTo = bookedTo;
        booking.createdAt = createdAt;
        return booking;
    }

    /**
     * Установить идентификатор запроса в Catalog Service
     */
    public void setCatalogRequestId(UUID catalogRequestId) {
        if (this.catalogRequestId != null) {
            throw new BusinessException("CatalogRequestId уже имеет значение: " + this.catalogRequestId);
        }
        if (catalogRequestId == null) {
            throw new BusinessException("CatalogRequestId не инициилизирован: " + catalogRequestId);
        }
        this.catalogRequestId = catalogRequestId;
    }

    /**
     * Подтвердить бронирование (переход из AwaitConfirmation в Confirmed)
     */
    public void confirm() {
        if (status != BookingStatus.AWAIT_CONFIRMATION) {
            throw new BusinessException("Статус заявки некорректен, заявка должна быть в статусе " + BookingStatus.AWAIT_CONFIRMATION);
        }
        this.status = BookingStatus.CONFIRMED;
    }


    /**
     * Начать отмену и сохранить данные, необходимые для компенсации.
     */
    public void beginCancellation(OffsetDateTime now) {
        switch (status) {
            case AWAIT_CONFIRMATION:
                this.previousStatus = status;
                this.status = BookingStatus.CANCELLATION_PENDING;
                this.commandSentAt = now;
                break;
            case CONFIRMED:
                if (now.toLocalDate().isBefore(bookedFrom)) {
                    this.previousStatus = status;
                    this.status = BookingStatus.CANCELLATION_PENDING;
                    this.commandSentAt = now;
                } else {
                    throw new BusinessException("Невозможно отменить начавшееся бронирование");
                }
                break;
            case NONE:
            case CANCELLED:
            case CANCELLATION_PENDING:
            default:
                throw new BusinessException("Некорректный статус для отмены");
        }
    }


    /**
     * Восстановить состояние, которое было до начала отмены.
     */
    public void rollbackCancellation() {
        if (status != BookingStatus.CANCELLATION_PENDING) {
            throw new BusinessException("Статус бронирования некорректен, бронирование должно находиться в статусе отмены");
        }
        if (previousStatus == null) {
            throw new BusinessException("Невозможно выполнить rollback: предыдущий статус не сохранен");
        }
        this.status = previousStatus;
        this.previousStatus = null;
        this.commandSentAt = null;
    }

    /**
     * Завершить отмену после подтверждения от Catalog Service.
     */
    public void completeCancellation() {
        if (status != BookingStatus.CANCELLATION_PENDING) {
            throw new BusinessException(
                    "Статус бронирования некорректен, бронирование должно находиться в статусе отмены"
            );
        }
        this.status = BookingStatus.CANCELLED;
        this.previousStatus = null;
        this.commandSentAt = null;
    }

    /**
     * Обработать отказ Catalog Service.
     * Для новой заявки отказ завершает создание, а для ожидающей отмены
     * подтверждает успешное завершение отмены.
     */
    public void cancel(LocalDate currentDate) {
        switch (status) {
            case AWAIT_CONFIRMATION:
                this.status = BookingStatus.CANCELLED;
                break;
            case CONFIRMED:
                if (currentDate.isBefore(bookedFrom)) {
                    this.status = BookingStatus.CANCELLED;
                } else {
                    throw new BusinessException("Невозможно отменить начавшееся бронирование");
                }
                break;
            case CANCELLATION_PENDING:
                completeCancellation();
                break;
            case NONE:
            case CANCELLED:
            default:
                throw new BusinessException("Некорректный статус для отмены");
        }
    }
}
