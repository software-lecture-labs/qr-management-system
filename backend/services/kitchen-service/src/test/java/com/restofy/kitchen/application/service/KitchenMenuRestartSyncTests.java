package com.restofy.kitchen.application.service;

import com.restofy.kitchen.KitchenServiceApplication;
import com.restofy.kitchen.api.dto.request.ChangeProductAvailabilityRequest;
import com.restofy.kitchen.api.dto.response.ClosedProductResponse;
import com.restofy.kitchen.api.dto.response.PageResponse;
import com.restofy.kitchen.application.port.MenuAvailabilityPort;
import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;
import com.restofy.kitchen.support.FakeMenuCatalogue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KITCHEN-03 / 3.4: what a restart of the Kitchen application does to closed products. Each "restart" is a whole new
 * application context against the same PostgreSQL database; the Menu service stand-in lives outside the contexts,
 * like the real Menu would. This proves Kitchen's behaviour towards the proposed contract. It does NOT prove how a
 * real Menu service persists its data (that service does not exist yet: LIVE NOT VERIFIED).
 */
@SpringBootTest
class KitchenMenuRestartSyncTests {

	private static final Set<ProductAvailabilityStatus> BOTH = EnumSet.of(ProductAvailabilityStatus.OUT_OF_STOCK,
			ProductAvailabilityStatus.TEMPORARILY_CLOSED);

	@Autowired
	private JdbcTemplate jdbc;

	/** A fresh application instance wired to the given Menu stand-in (the production default bean backs off). */
	private ConfigurableApplicationContext start(FakeMenuCatalogue menu) {
		return new SpringApplicationBuilder(KitchenServiceApplication.class).profiles("test").properties("server.port=0")
				.initializers(context -> context.getBeanFactory().registerSingleton("fakeMenuCatalogue", menu)).run();
	}

	private static ChangeProductAvailabilityRequest request(ProductAvailabilityStatus status, String text) {
		return new ChangeProductAvailabilityRequest(status, null, text);
	}

	private int rows(UUID restaurant) {
		return jdbc.queryForObject("SELECT count(*) FROM product_availability_commands WHERE restaurant_id = ?", Integer.class, restaurant);
	}

	@Test
	void closedProductsStayClosedWithTheirReasonsAfterARestartAndNothingIsReopened() {
		FakeMenuCatalogue menu = new FakeMenuCatalogue();
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID stock = menu.addProduct(restaurant, "Burger", true, true);
		UUID closed = menu.addProduct(restaurant, "Pizza", true, true);
		UUID hidden = menu.addProduct(restaurant, "Taslak", false, true);
		UUID stockKey = UUID.randomUUID();
		try (ConfigurableApplicationContext first = start(menu)) {
			ProductAvailabilityService service = first.getBean(ProductAvailabilityService.class);
			service.change(restaurant, actor, stockKey, stock, request(ProductAvailabilityStatus.OUT_OF_STOCK, "Bitti"));
			service.change(restaurant, actor, UUID.randomUUID(), closed, request(ProductAvailabilityStatus.TEMPORARILY_CLOSED, "Fritöz bozuldu"));
			service.change(restaurant, actor, UUID.randomUUID(), hidden, request(ProductAvailabilityStatus.OUT_OF_STOCK, null));
		}
		int commandsBefore = menu.commandCalls();
		assertEquals(3, rows(restaurant));

		try (ConfigurableApplicationContext second = start(menu)) {
			PageResponse<ClosedProductResponse> list = second.getBean(ClosedProductsService.class).list(restaurant, BOTH, 0, 20);

			assertEquals(3, list.totalElements());
			assertEquals(ProductAvailabilityStatus.OUT_OF_STOCK, menu.statusOf(stock));
			assertEquals(ProductAvailabilityStatus.TEMPORARILY_CLOSED, menu.statusOf(closed));
			assertEquals(ProductAvailabilityStatus.OUT_OF_STOCK, menu.statusOf(hidden));
			ClosedProductResponse pizza = list.content().stream().filter(p -> p.productId().equals(closed)).findFirst().orElseThrow();
			assertEquals("Fritöz bozuldu", pizza.reasonText());
			assertEquals(actor, pizza.changedByUserId());
			assertEquals(Boolean.FALSE, list.content().stream().filter(p -> p.productId().equals(hidden)).findFirst().orElseThrow().published());

			// Starting up sent no command at all, and the command log is exactly what it was before the restart.
			assertEquals(commandsBefore, menu.commandCalls());
			assertEquals(3, rows(restaurant));
			assertEquals("CONFIRMED", jdbc.queryForObject(
					"SELECT outcome FROM product_availability_commands WHERE restaurant_id = ? AND command_id = ?", String.class, restaurant, stockKey));

			// The same command repeated after the restart is recognised as a repeat and Menu changes nothing again.
			long versionBefore = menu.versionOf(stock);
			second.getBean(ProductAvailabilityService.class).change(restaurant, actor, stockKey, stock,
					request(ProductAvailabilityStatus.OUT_OF_STOCK, "Bitti"));
			assertEquals(versionBefore, menu.versionOf(stock));
			assertEquals(3, rows(restaurant));
		}
	}

	@Test
	void whenMenuIsDownAtStartupNoStateIsShownAndNothingIsChanged() {
		FakeMenuCatalogue menu = new FakeMenuCatalogue();
		UUID restaurant = UUID.randomUUID();
		UUID product = menu.addProduct(restaurant, "Burger", true, true);
		try (ConfigurableApplicationContext first = start(menu)) {
			first.getBean(ProductAvailabilityService.class).change(restaurant, UUID.randomUUID(), UUID.randomUUID(), product,
					request(ProductAvailabilityStatus.OUT_OF_STOCK, null));
		}

		menu.setReachable(false);
		try (ConfigurableApplicationContext second = start(menu)) {
			ClosedProductsService service = second.getBean(ClosedProductsService.class);
			ProductAvailabilityException error = assertThrows(ProductAvailabilityException.class,
					() -> service.list(restaurant, BOTH, 0, 20));
			assertEquals(MenuAvailabilityPort.Kind.UNAVAILABLE, error.getKind());
			assertEquals(1, menu.listCalls(), "exactly the one list the test asked for, none made by the startup");
		}

		menu.setReachable(true);
		try (ConfigurableApplicationContext third = start(menu)) {
			assertEquals(1, third.getBean(ClosedProductsService.class).list(restaurant, BOTH, 0, 20).totalElements());
			assertEquals(ProductAvailabilityStatus.OUT_OF_STOCK, menu.statusOf(product));
		}
	}

	@Test
	void anUnconfirmedCommandSurvivesARestartWithoutBeingRetriedOrConfirmedByItself() {
		FakeMenuCatalogue menu = new FakeMenuCatalogue();
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = menu.addProduct(restaurant, "Burger", true, true);
		UUID key = UUID.randomUUID();
		menu.setReachable(false);
		try (ConfigurableApplicationContext first = start(menu)) {
			ProductAvailabilityService service = first.getBean(ProductAvailabilityService.class);
			ChangeProductAvailabilityRequest change = request(ProductAvailabilityStatus.OUT_OF_STOCK, null);
			assertThrows(ProductAvailabilityException.class,
					() -> service.change(restaurant, actor, key, product, change));
		}
		int callsAfterFailure = menu.commandCalls();
		Map<String, Object> unconfirmed = jdbc.queryForMap(
				"SELECT outcome, attempt_count, outcome_code FROM product_availability_commands WHERE restaurant_id = ? AND command_id = ?",
						restaurant, key);
		assertEquals("UNCONFIRMED", unconfirmed.get("outcome"));
		assertEquals(1, unconfirmed.get("attempt_count"));

		menu.setReachable(true);
		try (ConfigurableApplicationContext second = start(menu)) {
			// Menu is back, yet nothing was retried and nothing was reported as done on its own.
			assertEquals(callsAfterFailure, menu.commandCalls());
			assertEquals(ProductAvailabilityStatus.AVAILABLE, menu.statusOf(product));
			assertEquals("UNCONFIRMED", jdbc.queryForObject(
					"SELECT outcome FROM product_availability_commands WHERE restaurant_id = ? AND command_id = ?", String.class, restaurant, key));

			// Only the client's retry with the same Idempotency-Key settles it, once.
			second.getBean(ProductAvailabilityService.class).change(restaurant, actor, key, product,
					request(ProductAvailabilityStatus.OUT_OF_STOCK, null));
			assertEquals(ProductAvailabilityStatus.OUT_OF_STOCK, menu.statusOf(product));
			assertEquals(1L, menu.versionOf(product));
			Map<String, Object> settled = jdbc.queryForMap(
					"SELECT outcome, attempt_count FROM product_availability_commands WHERE restaurant_id = ? AND command_id = ?", restaurant, key);
			assertEquals("CONFIRMED", settled.get("outcome"));
			assertEquals(2, settled.get("attempt_count"));
		}
		assertEquals(1, rows(restaurant));
	}

	@Test
	void theApplicationHasNothingThatCouldChangeAProductWhileStartingUpOrInTheBackground() {
		FakeMenuCatalogue menu = new FakeMenuCatalogue();
		try (ConfigurableApplicationContext context = start(menu)) {
			for (Class<?> hook : new Class<?>[] { ApplicationRunner.class, CommandLineRunner.class, SmartLifecycle.class }) {
				context.getBeansOfType(hook).forEach((name, bean) -> assertFalse(
						bean.getClass().getName().startsWith("com.restofy.kitchen"), "Kitchen defines a startup hook: " + name));
			}
			assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty(), "scheduling is not enabled");
			assertEquals(0, menu.commandCalls());
			assertEquals(0, menu.listCalls());
		}
	}
}
