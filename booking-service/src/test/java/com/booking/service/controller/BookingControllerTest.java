package com.booking.service.controller;

import com.booking.service.dto.response.BookingStatisticsResponse;
import com.booking.service.dto.response.TopResourceResponse;
import com.booking.service.entity.BookingStatus;
import com.booking.service.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

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
        BookingController controller = new BookingController(
                new BookingServiceStub(statistics),
                null
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/booking/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(10))
                .andExpect(jsonPath("$.countByStatus.CONFIRMED").value(6))
                .andExpect(jsonPath("$.topResources[0].resourceId").value(2))
                .andExpect(jsonPath("$.topResources[0].bookingCount").value(5));
    }

    private static final class BookingServiceStub extends BookingService {

        private final BookingStatisticsResponse statistics;

        private BookingServiceStub(BookingStatisticsResponse statistics) {
            super(null, null, null);
            this.statistics = statistics;
        }

        @Override
        public BookingStatisticsResponse getStatistics() {
            return statistics;
        }
    }
}
