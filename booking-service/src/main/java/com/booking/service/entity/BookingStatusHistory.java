package com.booking.service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;


@Entity
@Table(name = "booking_status_history")
@Getter
@NoArgsConstructor
public class BookingStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 32)
    private BookingStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", length = 32, nullable = false)
    private BookingStatus newStatus;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    @Column(name = "reason", length = 512, nullable = false)
    private String reason;

    @Column(name = "initiator", length = 64, nullable = false)
    private String initiator;

    public BookingStatusHistory(
            Long bookingId,
            BookingStatus previousStatus,
            BookingStatus newStatus,
            OffsetDateTime changedAt,
            String reason,
            String initiator
    ) {
        this.bookingId = bookingId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.changedAt = changedAt;
        this.reason = reason;
        this.initiator = initiator;
    }
}
