package com.booking.service.dto.response;

import com.booking.service.entity.BookingStatus;

import java.time.OffsetDateTime;

public record BookingStatusHistoryResponse(
        Long id,
        Long bookingId,
        BookingStatus previousStatus,
        BookingStatus newStatus,
        OffsetDateTime changedAt,
        String reason,
        String initiator
) {
}