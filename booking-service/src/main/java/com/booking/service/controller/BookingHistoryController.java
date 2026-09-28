package com.booking.service.controller;

import com.booking.service.dto.response.BookingStatusHistoryResponse;
import com.booking.service.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingHistoryController {

    private final BookingService bookingService;

    @GetMapping("/{id}/history")
    public Page<BookingStatusHistoryResponse> getHistory(
            @PathVariable("id") Long id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        return bookingService.getHistory(id, page, size);
    }
}