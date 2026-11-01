package com.restofy.kitchen.api.controller;

import com.jayway.jsonpath.JsonPath;
import com.restofy.kitchen.api.dto.response.DailyKitchenReportResponse;
import com.restofy.kitchen.application.contract.OrderCancellationDecisionMessage;
import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.DailyReportService;
import com.restofy.kitchen.application.service.OrderDecisionService;
import com.restofy.kitchen.domain.enums.DecisionOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.restofy.kitchen.application.service.OrderDecisionService.SOURCE_ORDER;
import static com.restofy.kitchen.support.FakeOrderDecisionSource.approved;
import static com.restofy.kitchen.support.FakeOrderDecisionSource.rejected;
import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KITCHEN-04 / 4.3: the whole cancellation story against the (fake) Order service, end to end through the real API:
 * a request leaves the order untouched, only an approved decision cancels it, and the screens and the day report show
 * the difference between "asked", "refused" and "cancelled".
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenOrderCancellationFlowTests {

	private static final String KEY = "Idempotency-Key";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private OrderDecisionService decisions;
	@Autowired
	private DailyReportService reports;
	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	private UUID order(UUID restaurant) {
		UUID orderId = UUID.randomUUID();
		ingest.ingest(simple(restaurant, orderId));
		return orderId;
	}

	private String ask(UUID restaurant, UUID orderId, String reason) throws Exception {
		return mvc.perform(post("/api/v1/kitchen/orders/" + orderId + "/cancellation-requests").header(KEY, UUID.randomUUID().toString())
				.contentType("application/json").content("{\"reasonCode\":\"" + reason + "\"}").with(kitchen(restaurant)).with(csrf()))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
	}

	private UUID askedRequestId(UUID restaurant, UUID orderId, String reason) throws Exception {
		return UUID.fromString(JsonPath.read(ask(restaurant, orderId, reason), "$.requestId"));
	}

	private ResultActions detail(UUID restaurant, UUID orderId) throws Exception {
		return mvc.perform(get("/api/v1/kitchen/orders/" + orderId).with(kitchen(restaurant)));
	}

	private ResultActions list(UUID restaurant, String query) throws Exception {
		return mvc.perform(get("/api/v1/kitchen/orders" + query).with(kitchen(restaurant)));
	}

	// ---- asked, refused, cancelled ----------------------------------------------------------------------------------

	@Test
	void aRequestAloneCancelsNothingAndAnApprovedDecisionCancelsAndShowsWhyAndWhen() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = order(restaurant);
		UUID requestId = askedRequestId(restaurant, orderId, "KITCHEN_ERROR");

		// Asked, not decided: the order is still in the working queue and shows no cancellation.
		detail(restaurant, orderId).andExpect(jsonPath("$.status").value("WAITING")).andExpect(jsonPath("$.cancelledAt").value((Object) null))
				.andExpect(jsonPath("$.cancellationReason").value((Object) null));
		list(restaurant, "?status=ACTIVE").andExpect(jsonPath("$.totalElements").value(1));
		list(restaurant, "?status=CANCELLED").andExpect(jsonPath("$.totalElements").value(0));

		var approval = approved(restaurant, orderId, requestId, 2);
		assertEquals(DecisionOutcome.CANCELLED, decisions.apply(SOURCE_ORDER, approval.message()).outcome());

		detail(restaurant, orderId).andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.cancelledAt").isNotEmpty())
				.andExpect(jsonPath("$.cancellationReason").value("KITCHEN_ERROR"));
		list(restaurant, "?status=CANCELLED").andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].orderId").value(orderId.toString())).andExpect(jsonPath("$.content[0].status").value("CANCELLED"))
				.andExpect(jsonPath("$.content[0].cancelledAt").isNotEmpty())
						.andExpect(jsonPath("$.content[0].cancellationReason").value("KITCHEN_ERROR"));
		list(restaurant, "?status=ACTIVE").andExpect(jsonPath("$.totalElements").value(0));
		list(restaurant, "").andExpect(jsonPath("$.totalElements").value(0));
		// The history shows the request and its decision next to the order's own status.
		mvc.perform(get("/api/v1/kitchen/orders/" + orderId + "/cancellation-requests").with(kitchen(restaurant)))
				.andExpect(jsonPath("$[0].status").value("APPROVED")).andExpect(jsonPath("$[0].decidedAt").isNotEmpty());
		// A cancelled order cannot be prepared any more.
		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/start-preparation").header(KEY, UUID.randomUUID().toString())
				.with(kitchen(restaurant)).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/mark-ready").header(KEY, UUID.randomUUID().toString())
				.with(kitchen(restaurant)).with(csrf())).andExpect(status().isConflict());
		// And no new request can be made for it.
		mvc.perform(post("/api/v1/kitchen/orders/" + orderId + "/cancellation-requests").header(KEY, UUID.randomUUID().toString())
				.contentType("application/json").content("{\"reasonCode\":\"OTHER\"}").with(kitchen(restaurant)).with(csrf()))
						.andExpect(status().isConflict());
	}

	@Test
	void aRefusedRequestLeavesTheOrderInTheQueueAndShowsTheRefusal() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = order(restaurant);
		UUID requestId = askedRequestId(restaurant, orderId, "CUSTOMER_REQUEST");

		assertEquals(DecisionOutcome.REQUEST_REJECTED,
				decisions.apply(SOURCE_ORDER, rejected(restaurant, orderId, requestId, 1).message()).outcome());

		detail(restaurant, orderId).andExpect(jsonPath("$.status").value("WAITING")).andExpect(jsonPath("$.cancelledAt").value((Object) null));
		list(restaurant, "?status=ACTIVE").andExpect(jsonPath("$.totalElements").value(1));
		mvc.perform(get("/api/v1/kitchen/orders/" + orderId + "/cancellation-requests").with(kitchen(restaurant)))
				.andExpect(jsonPath("$[0].status").value("REJECTED")).andExpect(jsonPath("$[0].decisionReasonCode").value("ORDER_ALREADY_SERVED"));
		// After a refusal the kitchen may ask again, with a new key.
		ask(restaurant, orderId, "OTHER");
		mvc.perform(get("/api/v1/kitchen/orders/" + orderId + "/cancellation-requests").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	void anApprovalThatArrivesAfterTheOrderBecameReadyForcesNothing() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = order(restaurant);
		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/start-preparation").header(KEY, UUID.randomUUID().toString())
				.with(kitchen(restaurant)).with(csrf())).andExpect(status().isOk());
		UUID requestId = askedRequestId(restaurant, orderId, "OTHER");
		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/mark-ready").header(KEY, UUID.randomUUID().toString())
				.with(kitchen(restaurant)).with(csrf())).andExpect(status().isOk());

		assertEquals(DecisionOutcome.NEEDS_RECONCILIATION,
				decisions.apply(SOURCE_ORDER, approved(restaurant, orderId, requestId, 5).message()).outcome());

		detail(restaurant, orderId).andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.cancelledAt").value((Object) null));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE restaurant_id = ? AND event_type = 'KitchenOrderReady'",
				Integer.class, restaurant),
				"the ready event is neither undone nor duplicated");
		mvc.perform(get("/api/v1/kitchen/orders/" + orderId + "/cancellation-requests").with(kitchen(restaurant)))
				.andExpect(jsonPath("$[0].status").value("REQUESTED"));
	}

	@Test
	void anotherRestaurantsDecisionNeverTouchesThisRestaurantsOrder() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID intruder = UUID.randomUUID();
		UUID orderId = order(restaurant);
		UUID requestId = askedRequestId(restaurant, orderId, "OTHER");

		assertEquals(DecisionOutcome.UNKNOWN, decisions.apply(SOURCE_ORDER, approved(intruder, orderId, requestId, 2).message()).outcome());

		detail(restaurant, orderId).andExpect(jsonPath("$.status").value("WAITING"));
		mvc.perform(get("/api/v1/kitchen/orders/" + orderId).with(kitchen(intruder))).andExpect(status().isNotFound());
	}

	// ---- the day report ------------------------------------------------------------------------------------------------

	@Test
	void onlyAConfirmedCancellationCountsInTheDayReportNeverARequestOrARefusal() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID pending = order(restaurant);
		UUID refused = order(restaurant);
		UUID cancelled = order(restaurant);
		order(restaurant);
		askedRequestId(restaurant, pending, "OTHER");
		decisions.apply(SOURCE_ORDER, rejected(restaurant, refused, askedRequestId(restaurant, refused, "OTHER"), 1).message());
		decisions.apply(SOURCE_ORDER, approved(restaurant, cancelled, askedRequestId(restaurant, cancelled, "KITCHEN_ERROR"), 2).message());
		LocalDate day = jdbc.queryForObject("SELECT (received_at AT TIME ZONE 'UTC')::date FROM kitchen_orders WHERE order_id = ?",
				Date.class, cancelled).toLocalDate();

		DailyKitchenReportResponse report = reports.daily(restaurant, day, ZoneOffset.UTC);

		assertEquals(4, report.orders().received());
		assertEquals(1, report.orders().currentStatusOfReceived().cancelled(), "only the approved one is cancelled");
		assertEquals(3, report.orders().currentStatusOfReceived().waiting(), "pending, refused and untouched orders are still waiting");
		assertEquals(1, report.orders().duringDay().cancelled(), "the confirmed cancellation is a transition of that day");
		assertEquals(0, report.orders().duringDay().becameReady());
	}

	// ---- no way in from outside ----------------------------------------------------------------------------------------

	@Test
	void nothingInProductionAcceptsAnOrderDecisionFromOutside() throws Exception {
		for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
			HandlerMethod handler = entry.getValue();
			assertNotEquals(OrderDecisionService.class, handler.getBeanType(), "a mapping exposes the decision service: " + entry.getKey());
			for (var parameter : handler.getMethodParameters()) {
				assertNotEquals(OrderCancellationDecisionMessage.class, parameter.getParameterType(),
						"a mapping takes a decision message: " + entry.getKey());
			}
		}
		UUID restaurant = UUID.randomUUID();
		UUID orderId = order(restaurant);
		UUID requestId = askedRequestId(restaurant, orderId, "OTHER");
		String forged = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"OrderCancelled\",\"schemaVersion\":1,\"restaurantId\":\""
				+ restaurant
				+ "\",\"orderId\":\"" + orderId + "\",\"requestId\":\"" + requestId
						+ "\",\"orderVersion\":2,\"decision\":\"APPROVED\",\"decidedAt\":\"2026-10-20T10:00:00Z\"}";
		for (String path : new String[] { "/api/v1/kitchen/orders/" + orderId + "/cancellation-decisions",
				"/api/v1/kitchen/cancellation-decisions",
				"/api/v1/orders/cancellation-decisions", "/api/v1/kitchen/decisions", "/internal/order-decisions",
						"/api/v1/kitchen/orders/" + orderId + "/cancel" }) {
			mvc.perform(post(path).contentType("application/json").content(forged).with(csrf())).andExpect(status().isUnauthorized());
			mvc.perform(post(path).contentType("application/json").content(forged).with(kitchen(restaurant)).with(csrf()))
					.andExpect(status().is4xxClientError());
			mvc.perform(put(path).contentType("application/json").content(forged).with(kitchen(restaurant)).with(csrf()))
					.andExpect(status().is4xxClientError());
		}
		detail(restaurant, orderId).andExpect(jsonPath("$.status").value("WAITING"));
	}
}
