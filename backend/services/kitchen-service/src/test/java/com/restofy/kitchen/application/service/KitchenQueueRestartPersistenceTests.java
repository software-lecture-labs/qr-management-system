package com.restofy.kitchen.application.service;

import com.restofy.kitchen.KitchenServiceApplication;
import com.restofy.kitchen.domain.enums.KitchenStatusFilter;
import com.restofy.kitchen.support.KitchenOrderSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * "Restart" proof: a whole application context is started, used and closed, then a new one is started against the
 * same PostgreSQL database. The surrounding test context keeps the Testcontainers database alive.
 */
@SpringBootTest
class KitchenQueueRestartPersistenceTests {

	@Autowired
	private JdbcTemplate jdbc;

	private ConfigurableApplicationContext start() {
		return new SpringApplicationBuilder(KitchenServiceApplication.class)
				.profiles("test")
				.properties("server.port=0")
				.run();
	}

	@Test
	void queueSurvivesAnApplicationRestart() {
		UUID restaurant = UUID.randomUUID();
		UUID ingested = UUID.randomUUID();
		UUID seededPreparing;
		try (ConfigurableApplicationContext first = start()) {
			first.getBean(AcceptedOrderIngestService.class).ingest(simple(restaurant, ingested));
			seededPreparing = new KitchenOrderSeeder(jdbc).seed(restaurant, "PREPARING", Instant.now().minusSeconds(600));
		}

		try (ConfigurableApplicationContext second = start()) {
			KitchenOrderQueryService queries = second.getBean(KitchenOrderQueryService.class);
			var active = queries.list(restaurant, KitchenStatusFilter.ACTIVE, null, null, 0, 20);
			assertEquals(2, active.totalElements());
			assertEquals(seededPreparing, active.content().get(0).orderId());
			assertEquals(ingested, active.content().get(1).orderId());
			assertEquals(0, queries.list(UUID.randomUUID(), KitchenStatusFilter.ACTIVE, null, null, 0, 20).totalElements());
		}
	}
}
