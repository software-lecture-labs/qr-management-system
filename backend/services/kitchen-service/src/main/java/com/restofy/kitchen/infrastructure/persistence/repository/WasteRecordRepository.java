package com.restofy.kitchen.infrastructure.persistence.repository;

import com.restofy.kitchen.domain.entity.WasteRecord;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WasteRecordRepository extends JpaRepository<WasteRecord, UUID>, JpaSpecificationExecutor<WasteRecord> {

	/** Tenant scoped: a command id of another restaurant is never visible. */
	Optional<WasteRecord> findByRestaurantIdAndCommandId(UUID restaurantId, UUID commandId);

	/** One row per reason in the window [from, to): {reason, summed quantity, number of records}. */
	@Query("""
			select w.reasonCode, sum(w.quantity), count(w) from WasteRecord w
			where w.restaurantId = :restaurantId and w.recordedAt >= :from and w.recordedAt < :to
			group by w.reasonCode
			""")
	List<Object[]> totalsByReason(@Param("restaurantId") UUID restaurantId, @Param("from") Instant from, @Param("to") Instant to);

	/** Tenant scoped list filter: the restaurant predicate is mandatory; every other bound is optional; [from, to). */
	static Specification<WasteRecord> filter(UUID restaurantId, Instant from, Instant to, UUID productId) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.equal(root.get("restaurantId"), restaurantId));
			if (from != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("recordedAt"), from));
			}
			if (to != null) {
				predicates.add(cb.lessThan(root.get("recordedAt"), to));
			}
			if (productId != null) {
				predicates.add(cb.equal(root.get("productId"), productId));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}
}
