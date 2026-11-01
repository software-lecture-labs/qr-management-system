package com.restofy.kitchen.infrastructure.persistence;

import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.domain.entity.KitchenOrder;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@code @Version} column is the second safety net: a writer that did not take the row lock cannot silently
 * overwrite a newer state (lost update) on PostgreSQL.
 */
@SpringBootTest
class KitchenOptimisticLockTests {

	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private KitchenOrderRepository orders;
	@Autowired
	private TransactionTemplate tx;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void aStaleWriterIsRejectedInsteadOfOverwritingANewerState() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null)));

		TransactionTemplate rival = new TransactionTemplate(tx.getTransactionManager());
		rival.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		assertThrows(ObjectOptimisticLockingFailureException.class, () -> tx.executeWithoutResult(status -> {
			KitchenOrder stale = orders.findByRestaurantIdAndOrderId(restaurant, orderId).orElseThrow();
			// Somebody else commits, in their own transaction, a newer state (and version) after this writer's read.
			rival.executeWithoutResult(other -> jdbc.update(
					"UPDATE kitchen_orders SET status = 'CANCELLED', cancelled_at = now(), version = version + 1 "
							+ "WHERE restaurant_id = ? AND order_id = ?", restaurant, orderId));
			stale.startPreparation(Instant.now());
			orders.saveAndFlush(stale);
		}));

		assertEquals("CANCELLED", jdbc.queryForObject(
				"SELECT status FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?", String.class, restaurant, orderId));
		assertEquals(1, jdbc.queryForObject("SELECT version FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?",
				Integer.class, restaurant, orderId), "the newer state and its version are untouched");
	}
}
