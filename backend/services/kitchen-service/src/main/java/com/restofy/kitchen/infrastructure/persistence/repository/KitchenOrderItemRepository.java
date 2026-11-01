package com.restofy.kitchen.infrastructure.persistence.repository;

import com.restofy.kitchen.domain.entity.KitchenOrderItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface KitchenOrderItemRepository extends JpaRepository<KitchenOrderItem, UUID> {

	/**
	 * The names under which this restaurant's orders carried the product, newest order first. Used only to label a
	 * waste record with a name Kitchen itself received; ask for one row.
	 */
	@Query("""
			select i.productNameSnapshot from KitchenOrderItem i
			where i.restaurantId = :restaurantId and i.productId = :productId
			order by i.kitchenOrder.receivedAt desc, i.id desc
			""")
	List<String> latestNames(@Param("restaurantId") UUID restaurantId, @Param("productId") UUID productId, Pageable limit);
}
