package com.restofy.order.infrastructure.persistence.repository;

import com.restofy.order.domain.entity.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    /** Creates the guest's ACTIVE cart unless one already exists; safe under concurrent first-adds. */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO carts (id, restaurant_id, table_id, table_session_id, guest_session_id, status,
                               version, created_at, updated_at)
            VALUES (:id, :restaurantId, :tableId, :tableSessionId, :guestSessionId, 'ACTIVE', 0, :now, :now)
            ON CONFLICT (restaurant_id, guest_session_id) WHERE status = 'ACTIVE' DO NOTHING
            """)
    int insertActiveIfAbsent(@Param("id") UUID id, @Param("restaurantId") UUID restaurantId,
                             @Param("tableId") UUID tableId, @Param("tableSessionId") UUID tableSessionId,
                             @Param("guestSessionId") UUID guestSessionId, @Param("now") Instant now);

    /** Row-locks the active cart so concurrent mutations of one cart are serialized. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.restaurantId = :restaurantId and c.guestSessionId = :guestSessionId"
            + " and c.status = com.restofy.order.domain.enums.CartStatus.ACTIVE")
    Optional<Cart> findActiveForUpdate(@Param("restaurantId") UUID restaurantId,
                                       @Param("guestSessionId") UUID guestSessionId);

    @EntityGraph(attributePaths = {"items", "items.options"})
    @Query("select c from Cart c where c.restaurantId = :restaurantId and c.guestSessionId = :guestSessionId"
            + " and c.status = com.restofy.order.domain.enums.CartStatus.ACTIVE")
    Optional<Cart> findActiveWithItems(@Param("restaurantId") UUID restaurantId,
                                       @Param("guestSessionId") UUID guestSessionId);
}
