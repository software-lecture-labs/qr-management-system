package com.restofy.order.infrastructure.persistence.repository;

import com.restofy.order.domain.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    /** Still-active units (ordered minus cancelled) per order for a page of orders, in one query. */
    interface UnitCount {
        UUID getOrderId();

        Long getUnits();
    }

    @Query("select i.order.id as orderId, sum(i.orderedQuantity - i.cancelledQuantity) as units from OrderItem i"
            + " where i.order.id in :orderIds group by i.order.id")
    List<UnitCount> unitCounts(@Param("orderIds") Collection<UUID> orderIds);

    /**
     * Tenant-scoped lookup that takes a row lock ({@code SELECT ... FOR UPDATE}) until the transaction ends, so two
     * concurrent status changes of one order run one after the other. Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id and o.restaurantId = :restaurantId")
    Optional<Order> findForUpdate(@Param("id") UUID id, @Param("restaurantId") UUID restaurantId);

    /** Tenant-scoped lookup: an id from another restaurant is simply not found. */
    @EntityGraph(attributePaths = {"items", "items.options"})
    Optional<Order> findByIdAndRestaurantId(UUID id, UUID restaurantId);
}
