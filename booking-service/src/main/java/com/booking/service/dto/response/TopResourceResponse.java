package com.booking.service.dto.response;

public record TopResourceResponse(
        Long resourceId,
        long bookingCount
) {
}
