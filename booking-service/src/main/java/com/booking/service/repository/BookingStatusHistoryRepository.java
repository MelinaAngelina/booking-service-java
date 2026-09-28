package com.booking.service.repository;

import com.booking.service.entity.BookingStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BookingStatusHistoryRepository
        extends JpaRepository<BookingStatusHistory, Long> {
    Page<BookingStatusHistory> findByBookingIdOrderByChangedAtDescIdDesc(
            Long bookingId,
            Pageable pageable
    );
}