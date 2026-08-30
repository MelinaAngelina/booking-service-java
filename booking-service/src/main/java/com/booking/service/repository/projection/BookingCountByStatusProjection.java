package com.booking.service.repository.projection;

import com.booking.service.entity.BookingStatus;

public interface BookingCountByStatusProjection {
    BookingStatus getStatus();
    Long getBookingCount();
}
