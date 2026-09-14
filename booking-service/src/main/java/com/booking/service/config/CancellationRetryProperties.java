package com.booking.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Настройки ожидания ответа Catalog перед повторной отправкой отмены. */
@ConfigurationProperties(prefix = "booking.cancellation-retry")
public record CancellationRetryProperties(Duration timeout) {

    public CancellationRetryProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Таймаут повторной отмены должен быть положительным");
        }
    }
}
