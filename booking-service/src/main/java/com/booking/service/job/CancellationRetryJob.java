package com.booking.service.job;

import com.booking.service.config.CancellationRetryProperties;
import com.booking.service.config.CurrentDateTimeProvider;
import com.booking.service.entity.Booking;
import com.booking.service.entity.BookingStatus;
import com.booking.service.messaging.contracts.CancelBookingJobByRequestIdRequest;
import com.booking.service.messaging.listener.BookingEventPublisher;
import com.booking.service.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Повторно отправляет отмены, на которые Catalog не ответил за установленный срок. */
@Component
@RequiredArgsConstructor
@Slf4j
public class CancellationRetryJob {

    private final BookingRepository bookingRepository;
    private final BookingEventPublisher bookingEventPublisher;
    private final CurrentDateTimeProvider dateTimeProvider;
    private final CancellationRetryProperties properties;

    /**
     * Статус и служебные поля не меняются: результат обрабатывается обычным flow.
     * После истечения таймаута повторяем отправку на каждом запуске до получения ответа.
     */
    @Scheduled(fixedDelayString = "${booking.cancellation-retry.fixed-delay}")
    public void retryStuckCancellations() {
        OffsetDateTime cutoff = dateTimeProvider.utcNow().minus(properties.timeout());
        List<Booking> bookings = bookingRepository.findByStatusAndCommandSentAtBefore(
                BookingStatus.CANCELLATION_PENDING, cutoff);

        for (Booking booking : bookings) {
            try {
                if (booking.getCatalogRequestId() == null) {
                    log.warn("Невозможно повторить отмену: у бронирования id={} отсутствует catalogRequestId",
                            booking.getId());
                    continue;
                }
                bookingEventPublisher.publishCancelBookingJob(new CancelBookingJobByRequestIdRequest(
                        UUID.randomUUID(), booking.getCatalogRequestId()));
                log.info("Повторно отправлена отмена бронирования id={}, requestId={}",
                        booking.getId(), booking.getCatalogRequestId());
            } catch (Exception ex) {
                log.error("Не удалось повторить отмену бронирования id={}, requestId={}",
                        booking.getId(), booking.getCatalogRequestId(), ex);
            }
        }
    }
}
