package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.DiningTable;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

public interface DiningTableRepository extends JpaRepository<DiningTable, UUID> {
    Optional<DiningTable> findByRestaurantIdAndId(UUID restaurantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DiningTable t where t.restaurantId = :restaurantId and t.id = :id")
    Optional<DiningTable> findForQrManagement(@Param("restaurantId") UUID restaurantId, @Param("id") UUID id);

    @Query("""
            select t from DiningTable t where t.restaurantId = :restaurantId
              and (:active is null or t.active = :active)
              and (:status is null or t.operationalStatus = :status)
            """)
    Page<DiningTable> findTables(@Param("restaurantId") UUID restaurantId,
                                @Param("active") Boolean active,
                                @Param("status") TableOperationalStatus status, Pageable pageable);
}
