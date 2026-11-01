package com.restofy.kitchen.infrastructure.persistence.repository;

import com.restofy.kitchen.domain.entity.ProductAvailabilityCommand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Parallel requests may carry the same command id (a client retry), so the record is changed with atomic, conditional
 * statements instead of read-modify-write: concurrent attempts never collide on a version, a counter never loses an
 * increment and a final outcome (CONFIRMED / REJECTED) can never be overwritten. Each statement returns the rows changed.
 */
public interface ProductAvailabilityCommandRepository extends JpaRepository<ProductAvailabilityCommand, UUID> {

	/** Tenant scoped: a command id of another restaurant is never visible. */
	Optional<ProductAvailabilityCommand> findByRestaurantIdAndCommandId(UUID restaurantId, UUID commandId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update versioned ProductAvailabilityCommand c set c.attemptCount = c.attemptCount + 1, c.lastAttemptAt = :now where c.id = :id")
	int recordAttempt(@Param("id") UUID id, @Param("now") Instant now);

	/** Menu confirmed. Only an unsettled command can become CONFIRMED. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update versioned ProductAvailabilityCommand c
			set c.outcome = com.restofy.kitchen.domain.enums.CommandOutcome.CONFIRMED, c.confirmedAt = :menuChangedAt, c.outcomeCode = null
			where c.id = :id and c.outcome = com.restofy.kitchen.domain.enums.CommandOutcome.UNCONFIRMED
			""")
	int confirm(@Param("id") UUID id, @Param("menuChangedAt") Instant menuChangedAt);

	/** Menu definitively rejected. Only an unsettled command can become REJECTED. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update versioned ProductAvailabilityCommand c
			set c.outcome = com.restofy.kitchen.domain.enums.CommandOutcome.REJECTED, c.outcomeCode = :code
			where c.id = :id and c.outcome = com.restofy.kitchen.domain.enums.CommandOutcome.UNCONFIRMED
			""")
	int reject(@Param("id") UUID id, @Param("code") String code);

	/** No reliable answer: stays UNCONFIRMED and keeps a short safe code of why. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update versioned ProductAvailabilityCommand c set c.outcomeCode = :code
			where c.id = :id and c.outcome = com.restofy.kitchen.domain.enums.CommandOutcome.UNCONFIRMED
			""")
	int markUnconfirmed(@Param("id") UUID id, @Param("code") String code);
}
