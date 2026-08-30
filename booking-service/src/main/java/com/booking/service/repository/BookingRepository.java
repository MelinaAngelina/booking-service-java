package com.booking.service.repository;

import com.booking.service.entity.Booking;
import com.booking.service.entity.BookingStatus;
import com.booking.service.repository.projection.BookingCountByStatusProjection;
import com.booking.service.repository.projection.TopResourceProjection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA репозиторий для работы с бронированиями
 */
@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Найти бронирование по идентификатору запроса в Catalog Service
     * @param catalogRequestId идентификатор запроса
     * @return Optional с бронированием или empty
     */
    Optional<Booking> findByCatalogRequestId(UUID catalogRequestId);

    /**
     * Найти бронирования по фильтрам с пагинацией
     * Используется для получения списка бронирований с опциональными фильтрами
     *
     * @param userId идентификатор пользователя (опционально)
     * @param resourceId идентификатор ресурса (опционально)
     * @param status статус бронирования (опционально)
     * @param pageable параметры пагинации и сортировки
     * @return Page с результатами
     */
    @Query("SELECT b FROM Booking b WHERE " +
           "(:userId IS NULL OR b.userId = :userId) AND " +
           "(:resourceId IS NULL OR b.resourceId = :resourceId) AND " +
           "(:status IS NULL OR b.status = :status)")
    List<Booking> findByFilter(@Param("userId") Long userId,
                               @Param("resourceId") Long resourceId,
                               @Param("status") BookingStatus status,
                               Pageable pageable);

    /**
     * Получить только статус бронирования по ID
     * @param id идентификатор бронирования
     * @return статус бронирования или null
     */
    @Query("SELECT b.status FROM Booking b WHERE b.id = :id")
    BookingStatus findStatusById(@Param("id") Long id);

    /**
     * Получить общее количество бронирований, созданных за указанный период.
     *
     * @param dateFrom начало периода включительно
     * @param dateTo окончание периода включительно
     * @return количество бронирований
     */
    @Query("""
        SELECT COUNT(b)
        FROM Booking b
        WHERE b.createdAt >= :dateFrom
          AND b.createdAt <= :dateTo
        """)
    long countBookingsWithinPeriod(@Param("dateFrom") OffsetDateTime dateFrom,
                                   @Param("dateTo") OffsetDateTime dateTo);

    /**
     * Получить количество бронирований за период с группировкой по статусу.
     *
     * @param dateFrom начало периода включительно
     * @param dateTo окончание периода включительно
     * @return список статусов и количества бронирований для каждого статуса
     */
    @Query("""
        SELECT b.status AS status,
               COUNT(b) AS bookingCount
        FROM Booking b
        WHERE b.createdAt >= :dateFrom
          AND b.createdAt <= :dateTo
        GROUP BY b.status
        """)
    List<BookingCountByStatusProjection> countBookingsByStatus(
            @Param("dateFrom") OffsetDateTime dateFrom,
            @Param("dateTo") OffsetDateTime dateTo
    );

    /**
     * Получить ресурсы, отсортированные по количеству бронирований.
     * При одинаковом количестве бронирований ресурсы сортируются по идентификатору.
     * Количество возвращаемых ресурсов ограничивается параметрами пагинации.
     *
     * @param dateFrom начало периода включительно
     * @param dateTo окончание периода включительно
     * @param pageable параметры ограничения количества результатов
     * @return список ресурсов и количества их бронирований
     */
    @Query("""
        SELECT b.resourceId AS resourceId,
               COUNT(b) AS bookingCount
        FROM Booking b
        WHERE b.createdAt >= :dateFrom
          AND b.createdAt <= :dateTo
        GROUP BY b.resourceId
        ORDER BY COUNT(b) DESC, b.resourceId ASC
        """)
    List<TopResourceProjection> findTopResources(
            @Param("dateFrom") OffsetDateTime dateFrom,
            @Param("dateTo") OffsetDateTime dateTo,
            Pageable pageable
    );
}
