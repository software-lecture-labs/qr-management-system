package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.IngestOutcome;
import com.restofy.kitchen.domain.enums.OptionAction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static com.restofy.kitchen.support.KitchenTestAuth.admin;
import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static com.restofy.kitchen.support.OrderMessages.option;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class KitchenOrderListApiTests {

	private static final String URL = "/api/v1/kitchen/orders";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private AcceptedOrderIngestService ingest;

	private UUID accept(UUID restaurant, UUID session, String table, String number) throws InterruptedException {
		UUID orderId = UUID.randomUUID();
		assertEquals(IngestOutcome.CREATED, ingest.ingest(message(restaurant, orderId, session, table, number, 1,
				UUID.randomUUID(), item("Burger", 2, null, option(OptionAction.ADD, "Peynir", 1, null)),
				item("Ayran", 1, "buzsuz"))));
		Thread.sleep(5); // distinct receivedAt for a deterministic oldest-first assertion
		return orderId;
	}

	@Test
	void unauthenticatedRequestsAreRejectedWith401() throws Exception {
		mvc.perform(get(URL)).andExpect(status().isUnauthorized());
		mvc.perform(get(URL + "?restaurantId=" + UUID.randomUUID())).andExpect(status().isUnauthorized());
		// /actuator/health is intentionally anonymous; see KitchenHttpSecurityTests for the health/actuator contract.
		mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/other")).andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatedUsersWithoutAKitchenRoleGet403() throws Exception {
		mvc.perform(get(URL).with(user("waiter").roles("WAITER"))).andExpect(status().isForbidden());
		mvc.perform(get(URL).with(user("guest").roles("GUEST"))).andExpect(status().isForbidden());
	}

	@Test
	void kitchenAndAdminSeeNewOrdersOldestFirstWithSeparateRowsPerOrderOnTheSameTable() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		UUID first = accept(restaurant, session, "12", "A-1");
		UUID second = accept(restaurant, session, "12", "A-2");
		UUID third = accept(restaurant, UUID.randomUUID(), "7", "A-3");

		for (RequestPostProcessor auth : List.of(kitchen(restaurant), admin(restaurant))) {
			mvc.perform(get(URL).with(auth))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.totalElements").value(3))
					.andExpect(jsonPath("$.page").value(0))
					.andExpect(jsonPath("$.size").value(20))
					.andExpect(jsonPath("$.content[0].orderId").value(first.toString()))
					.andExpect(jsonPath("$.content[1].orderId").value(second.toString()))
					.andExpect(jsonPath("$.content[2].orderId").value(third.toString()))
					.andExpect(jsonPath("$.content[0].tableNumber").value("12"))
					.andExpect(jsonPath("$.content[1].tableNumber").value("12"))
					.andExpect(jsonPath("$.content[0].tableSessionId").value(session.toString()))
					.andExpect(jsonPath("$.content[0].status").value("WAITING"))
					.andExpect(jsonPath("$.content[0].itemCount").value(2))
					.andExpect(jsonPath("$.content[0].orderNumber").value("A-1"))
					.andExpect(jsonPath("$.content[0].receivedAt").exists())
					.andExpect(jsonPath("$.content[0].restaurantId").doesNotExist());
		}
	}

	@Test
	void anotherRestaurantNeverSeesOrdersAndATenantParameterIsIgnored() throws Exception {
		UUID owner = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		accept(owner, UUID.randomUUID(), "1", "A-1");

		mvc.perform(get(URL).with(kitchen(other)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0))
				.andExpect(jsonPath("$.content").isEmpty());
		// Caller claims the owner's tenant through query parameter and header: both must be ignored.
		mvc.perform(get(URL + "?restaurantId=" + owner).header("X-Restaurant-Id", owner.toString()).with(kitchen(other)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void paginationIsBoundedAndDeterministic() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID a = accept(restaurant, UUID.randomUUID(), "1", "A-1");
		UUID b = accept(restaurant, UUID.randomUUID(), "2", "A-2");
		UUID c = accept(restaurant, UUID.randomUUID(), "3", "A-3");

		mvc.perform(get(URL + "?page=0&size=2").with(kitchen(restaurant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andExpect(jsonPath("$.content[0].orderId").value(a.toString()))
				.andExpect(jsonPath("$.content[1].orderId").value(b.toString()));
		mvc.perform(get(URL + "?page=1&size=2").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].orderId").value(c.toString()));
		mvc.perform(get(URL + "?page=5&size=2").with(kitchen(restaurant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isEmpty());
	}

	@Test
	void invalidParametersReturn400ProblemDetailWithoutInternals() throws Exception {
		UUID restaurant = UUID.randomUUID();
		for (String query : new String[] { "?status=BOGUS", "?size=101", "?size=0", "?page=-1", "?page=abc" }) {
			mvc.perform(get(URL + query).with(kitchen(restaurant)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.status").value(400))
					.andExpect(jsonPath("$.trace").doesNotExist())
					.andExpect(jsonPath("$.exception").doesNotExist());
		}
	}

	@Test
	void correlationIdIsEchoedWhenSafeAndReplacedWhenNot() throws Exception {
		UUID restaurant = UUID.randomUUID();
		mvc.perform(get(URL).header("X-Correlation-Id", "corr-123.ok").with(kitchen(restaurant)))
				.andExpect(header().string("X-Correlation-Id", "corr-123.ok"));
		String replaced = mvc.perform(get(URL).header("X-Correlation-Id", "bad value\twith space").with(kitchen(restaurant)))
				.andReturn().getResponse().getHeader("X-Correlation-Id");
		assertEquals(36, replaced.length());
		// Even rejected requests carry a correlation id.
		assertEquals(36, mvc.perform(get(URL)).andReturn().getResponse().getHeader("X-Correlation-Id").length());
	}
}
