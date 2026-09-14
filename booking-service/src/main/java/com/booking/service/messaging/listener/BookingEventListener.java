package com.booking.service.messaging.listener;

import com.booking.service.config.RabbitMqProperties;
import com.booking.service.messaging.contracts.BookingJobConfirmed;
import com.booking.service.messaging.contracts.BookingJobDenied;
import com.booking.service.messaging.contracts.CancelBookingJobByRequestIdRequest;
import com.booking.service.service.BookingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * Listener для обработки событий из RabbitMQ
 * Принимает события от Catalog Service и делегирует обработку в BookingService
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class BookingEventListener {

    private final BookingService bookingService;
    private final ObjectMapper objectMapper;
    private final RabbitMqProperties rabbitMqProperties;

    /**
     * Единый Consumer для всех событий от Catalog Service
     * Spring Cloud Stream автоматически вызывает эту функцию при получении события
     * TypeBased routing - маршрутизация по заголовку rbs2-msg-type (Rebus протокол)
     */
    @Bean
    public Consumer<Message<String>> bookingEvents() {
        return message -> {
            String messageType = (String) message.getHeaders().get(RabbitMqProperties.HEADER_MSG_TYPE);
            String payload = message.getPayload();

            log.debug("Получено событие из RabbitMQ: type={}", messageType);

            try {
                if (isMessageType(messageType, rabbitMqProperties.getMessageTypes().getBookingJobConfirmed())) {
                    handleBookingJobConfirmed(payload);

                } else if (isMessageType(messageType, rabbitMqProperties.getMessageTypes().getBookingJobDenied())) {
                    handleBookingJobDenied(payload);

                } else {
                    log.warn("Неизвестный тип события: {}", messageType);
                }
            } catch (Exception e) {
                log.error("Ошибка обработки события: type={}, payload={}", messageType, payload, e);
                throw new RuntimeException("Failed to process event: " + messageType, e);
            }
        };
    }

    /**
     * Consumer для обработки ошибок отмены бронирования из Dead Letter Queue
     * Вызывается когда Catalog Service не смог обработать CancelBookingJobByRequestIdRequest
     */
    @Bean
    public Consumer<Message<String>> cancelBookingErrors() {
        return message -> {
            String messageType = (String) message.getHeaders().get(RabbitMqProperties.HEADER_MSG_TYPE);
            String payload = message.getPayload();

            log.warn("Получено сообщение об ошибке из DLQ: type={}", messageType);
            log.debug("DLQ payload: {}", payload);

            try {
                if (isMessageType(messageType, rabbitMqProperties.getMessageTypes().getCancelBookingJob())) {
                    handleCancelBookingError(payload);
                } else {
                    log.warn("Неизвестный тип сообщения в DLQ: {}", messageType);
                }
            } catch (Exception e) {
                log.error("Ошибка обработки DLQ сообщения: type={}, payload={}", messageType, payload, e);
                throw new RuntimeException("Failed to process cancellation error: " + messageType, e);
            }
        };
    }

    private void handleBookingJobConfirmed(String payload) throws Exception {
        log.info("Обработка события BookingJobConfirmed");

        BookingJobConfirmed event = objectMapper.readValue(payload, BookingJobConfirmed.class);

        log.debug("BookingJobConfirmed: eventId={}, requestId={}",
                event.getEventId(), event.getRequestId());

        bookingService.handleBookingJobConfirmed(event.getRequestId());
    }

    private void handleBookingJobDenied(String payload) throws Exception {
        log.info("Обработка события BookingJobDenied");

        BookingJobDenied event = objectMapper.readValue(payload, BookingJobDenied.class);

        log.debug("BookingJobDenied: eventId={}, requestId={}",
                event.getEventId(), event.getRequestId());

        bookingService.handleBookingJobDenied(event.getRequestId());
    }

    private void handleCancelBookingError(String payload) throws Exception {
        CancelBookingJobByRequestIdRequest command = objectMapper.readValue(
                payload,
                CancelBookingJobByRequestIdRequest.class
        );

        log.debug("Команда отмены из DLQ: requestId={}", command.getRequestId());

        bookingService.handleError(command.getRequestId());
    }

    /**
     * Проверяет соответствие типа сообщения
     */
    private boolean isMessageType(String actualType, String expectedType) {
        return actualType != null && actualType.contains(expectedType.split(",")[0].trim());
    }
}
