package com.booking.service.config;

import com.booking.service.job.CancellationRetryJob;
import com.booking.service.messaging.listener.BookingEventPublisher;
import com.booking.service.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    void configuration_bindsCustomTimeoutAndRegistersJob() {
        BookingRepository repository = (BookingRepository) Proxy.newProxyInstance(
                BookingRepository.class.getClassLoader(), new Class<?>[]{BookingRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findByStatusAndCommandSentAtBefore")) {
                        return List.of();
                    }
                    if (method.getName().equals("toString")) {
                        return "EmptyTestRepository";
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        contextRunner.withUserConfiguration(CancellationRetryJob.class)
                .withBean(BookingRepository.class, () -> repository)
                .withBean(BookingEventPublisher.class, () -> new BookingEventPublisher(null, null))
                .withBean(CurrentDateTimeProvider.class, () -> OffsetDateTime::now)
                .withPropertyValues("booking.cancellation-retry.timeout=PT7M",
                        "booking.cancellation-retry.fixed-delay=PT1H")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CancellationRetryProperties.class).timeout())
                            .isEqualTo(Duration.ofMinutes(7));
                    assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
                            .hasSize(1);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "-PT1S"})
    void configuration_rejectsNonPositiveTimeout(String timeout) {
        contextRunner.withPropertyValues("booking.cancellation-retry.timeout=" + timeout)
                .run(context -> assertThat(context).hasFailed());
    }
}
