package com.booking.service.entity;

import lombok.Getter;

/**
 * Статус бронирования
 */
@Getter
public enum BookingStatus {
    /**
     * Отсутствует (0)
     */
    NONE(0),

    /**
     * Ожидает подтверждения (1)
     */
    AWAIT_CONFIRMATION(1),

    /**
     * Подтверждено (2)
     */
    CONFIRMED(2),

    /**
     * Отменено (3)
     */
    CANCELLED(3),

    CANCELLATION_PENDING(4);

    private final int value;

    BookingStatus(int value) {
        this.value = value;
    }

    /**
     * Получить BookingStatus по числовому значению
     */
    public static BookingStatus fromValue(int value) {
        for (BookingStatus status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("Неизвестное значение BookingStatus: " + value);
    }
}
