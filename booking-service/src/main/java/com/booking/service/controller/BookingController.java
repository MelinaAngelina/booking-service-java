package com.booking.service.controller;

import com.booking.service.dto.request.CreateBookingRequest;
import com.booking.service.dto.request.GetBookingsByFilterRequest;
import com.booking.service.dto.response.BookingResponse;
import com.booking.service.dto.response.BookingStatisticsResponse;
import com.booking.service.entity.Booking;
import com.booking.service.entity.BookingStatus;
import com.booking.service.service.BookingService;
import com.booking.service.service.mapper.BookingMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST контроллер для работы с бронированиями
 */
@RestController
@RequestMapping("/api/booking")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final BookingMapper mapper;

    /**
     * Получить агрегированную статистику по бронированиям.
     *
     * @return статистика по бронированиям
     */
    @GetMapping("/statistics")
    public BookingStatisticsResponse getStatistics() {
        return bookingService.getStatistics();
    }

    /**
     * Создать новое бронирование
     */
    @PostMapping
    public Long create(@Valid @RequestBody CreateBookingRequest request) {
        return bookingService.createBooking(request.userId(), request.resourceId(),
                request.bookedFrom(), request.bookedTo());
    }

    /**
     * Получить бронирование по ID
     */
    @GetMapping("{id}")
    public BookingResponse getById(@PathVariable Long id) {
        var booking = bookingService.getById(id);
        return mapper.toResponse(booking);
    }

    /**
     * Получить список бронирований с фильтрацией
     */
    @PostMapping("by-filter")
    public List<BookingResponse> getByFilter(@RequestBody GetBookingsByFilterRequest request) {
        List<Booking> bookings = bookingService.getByFilter(
                request.userId(),
                request.resourceId(),
                request.status(),
                request.pageNumber(),
                request.pageSize()
        );

        return bookings.stream().map(mapper::toResponse).toList();
    }

    /**
     * Получить статус бронирования по ID
     */
    @GetMapping("{id}/status")
    public BookingStatus getStatus(@PathVariable Long id) {
        return bookingService.getStatusById(id);
    }

    /**
     * Отменить бронирование
     */
    @PostMapping("{id}/cancel")
    public void cancel(@PathVariable Long id) {
        bookingService.cancelBooking(id);
    }
}
