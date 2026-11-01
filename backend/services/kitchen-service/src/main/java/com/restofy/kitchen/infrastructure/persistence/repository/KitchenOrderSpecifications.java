package com.restofy.kitchen.infrastructure.persistence.repository;

import com.restofy.kitchen.domain.entity.KitchenOrder;
import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class KitchenOrderSpecifications {

	private KitchenOrderSpecifications() {
	}

	/**
	 * Tenant scoped queue query. The restaurant predicate is mandatory and always first; the date range is
	 * [receivedFrom, receivedTo) and each bound is optional.
	 */
	public static Specification<KitchenOrder> queue(UUID restaurantId, Set<KitchenOrderStatus> statuses,
			Instant receivedFrom, Instant receivedTo) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.equal(root.get("restaurantId"), restaurantId));
			predicates.add(root.get("status").in(statuses));
			if (receivedFrom != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("receivedAt"), receivedFrom));
			}
			if (receivedTo != null) {
				predicates.add(cb.lessThan(root.get("receivedAt"), receivedTo));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}
}
