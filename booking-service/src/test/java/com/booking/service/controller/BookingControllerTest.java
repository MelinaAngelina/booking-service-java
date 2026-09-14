package com.booking.service.controller;

import com.booking.service.dto.response.BookingStatisticsResponse;
import com.booking.service.dto.response.TopResourceResponse;
import com.booking.service.entity.BookingStatus;
import com.booking.service.exception.GlobalExceptionHandler;
import com.booking.service.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookingControllerTest {

    @Test
    void getStatistics_returnsStatisticsAsJson() throws Exception {
        BookingStatisticsResponse statistics = new BookingStatisticsResponse(
                10L,
                Map.of(BookingStatus.CONFIRMED, 6L),
                List.of(new TopResourceResponse(2L, 5L))
        );
        BookingServiceStub bookingService = new BookingServiceStub(statistics);
        MockMvc mockMvc = createMockMvc(bookingService);

        mockMvc.perform(get("/api/booking/statistics")
                        .queryParam("dateFrom", "2026-08-01")
                        .queryParam("dateTo", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(10))
                .andExpect(jsonPath("$.countByStatus.CONFIRMED").value(6))
                .andExpect(jsonPath("$.topResources[0].resourceId").value(2))
                .andExpect(jsonPath("$.topResources[0].bookingCount").value(5));

        assertEquals(LocalDate.of(2026, 8, 1), bookingService.dateFrom());
        assertEquals(LocalDate.of(2026, 8, 31), bookingService.dateTo());
    }

    @Test
    void getStatistics_withoutRequiredParameters_returnsBadRequest() throws Exception {
        MockMvc mockMvc = createMockMvc(emptyBookingService());

        mockMvc.perform(get("/api/booking/statistics"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/booking/statistics")
                        .queryParam("dateFrom", "2026-08-01"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/booking/statistics")
                        .queryParam("dateTo", "2026-08-31"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getStatistics_whenDateToIsBeforeDateFrom_returnsBadRequest() throws Exception {
        MockMvc mockMvc = createMockMvc(emptyBookingService());

        mockMvc.perform(get("/api/booking/statistics")
                        .queryParam("dateFrom", "2026-08-31")
                        .queryParam("dateTo", "2026-08-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Business Error"));
    }

    @Test
    void getStatistics_withInvalidDateFormat_returnsBadRequest() throws Exception {
        MockMvc mockMvc = createMockMvc(emptyBookingService());

        mockMvc.perform(get("/api/booking/statistics")
                        .queryParam("dateFrom", "01.08.2026")
                        .queryParam("dateTo", "2026-08-31"))
                .andExpect(status().isBadRequest());
    }

    private MockMvc createMockMvc(BookingService bookingService) {
        BookingController controller = new BookingController(bookingService, null);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private BookingServiceStub emptyBookingService() {
        return new BookingServiceStub(new BookingStatisticsResponse(
                0L,
                Map.of(),
                List.of()
        ));
    }

    private static final class BookingServiceStub extends BookingService {

        private final BookingStatisticsResponse statistics;
        private LocalDate dateFrom;
        private LocalDate dateTo;

        private BookingServiceStub(BookingStatisticsResponse statistics) {
            super(null, null, null);
            this.statistics = statistics;
        }

        @Override
        public BookingStatisticsResponse getStatistics(LocalDate dateFrom, LocalDate dateTo) {
            this.dateFrom = dateFrom;
            this.dateTo = dateTo;
            return statistics;
        }

        private LocalDate dateFrom() {
            return dateFrom;
        }

        private LocalDate dateTo() {
            return dateTo;
        }
    }
}
