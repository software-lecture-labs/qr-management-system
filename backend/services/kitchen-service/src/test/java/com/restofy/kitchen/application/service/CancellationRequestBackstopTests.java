package com.restofy.kitchen.application.service;

import com.restofy.kitchen.api.dto.request.RequestOrderCancellationRequest;
import com.restofy.kitchen.domain.entity.CancellationRequest;
import com.restofy.kitchen.domain.entity.OutboxEvent;
import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.exception.CancellationAlreadyRequestedException;
import com.restofy.kitchen.domain.exception.CommandIdConflictException;
import com.restofy.kitchen.infrastructure.persistence.repository.CancellationRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * The database is the last line of defence behind the application's own checks. These tests blind the application's
 * checks on purpose (a race that slipped past them) and prove the constraints still turn the race into the right 409
 * instead of a server error, and that nothing half-written stays behind.
 */
@SpringBootTest
class CancellationRequestBackstopTests {

	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private CancellationRequestService cancellations;
	@Autowired
	private KitchenEventRecorder recorder;
	@Autowired
	private JdbcTemplate jdbc;
	@MockitoSpyBean
	private CancellationRequestRepository requests;

	private static final RequestOrderCancellationRequest BODY = new RequestOrderCancellationRequest(CancellationReason.OTHER, null);

	private UUID order(UUID restaurant) {
		UUID orderId = UUID.randomUUID();
		ingest.ingest(simple(restaurant, orderId));
		return orderId;
	}

	private int stored(UUID restaurant) {
		return jdbc.queryForObject("SELECT count(*) FROM cancellation_requests WHERE restaurant_id = ?", Integer.class, restaurant);
	}

	private int events(UUID restaurant) {
		return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE restaurant_id = ?", Integer.class, restaurant);
	}

	@Test
	void theSameKeyRacingInForAnotherOrderIsACommandConflictNotAServerError() {
		UUID restaurant = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		UUID first = order(restaurant);
		UUID second = order(restaurant);
		cancellations.request(restaurant, UUID.randomUUID(), key, first, BODY);
		// The application "does not see" the first request, as if the other transaction had not committed yet.
		doReturn(Optional.empty()).when(requests).findByRestaurantIdAndCommandId(any(), any());

		UUID secondActorId = UUID.randomUUID();
		assertThrows(CommandIdConflictException.class,
				() -> cancellations.request(restaurant, secondActorId, key, second, BODY));

		assertEquals(1, stored(restaurant));
		assertEquals(1, events(restaurant), "the refused request left no event behind");
	}

	@Test
	void aSecondOpenRequestSlippingPastTheApplicationCheckIsStoppedByTheDatabase() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = order(restaurant);
		cancellations.request(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId, BODY);
		doReturn(Optional.empty()).when(requests).findByRestaurantIdAndOrderIdAndStatus(any(), any(), any());

		UUID secondActorId = UUID.randomUUID();
		UUID secondCommandId = UUID.randomUUID();
		assertThrows(CancellationAlreadyRequestedException.class,
				() -> cancellations.request(restaurant, secondActorId, secondCommandId, orderId, BODY));

		assertEquals(1, stored(restaurant));
		assertEquals(1, events(restaurant));
	}

	@Test
	void anyOtherIntegrityProblemIsNotDisguisedAsAConflict() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = order(restaurant);
		doThrow(new DataIntegrityViolationException("something unrelated")).when(requests).saveAndFlush(any(CancellationRequest.class));

		UUID actorId = UUID.randomUUID();
		UUID commandId = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class,
				() -> cancellations.request(restaurant, actorId, commandId, orderId, BODY));

		assertEquals(0, stored(restaurant));
	}

	@Test
	void aStoredEventOfAnUnknownKindOrWithAnUnreadablePayloadIsNeverSentAsSomethingElse() {
		var aggregate = new OutboxEvent.Aggregate(UUID.randomUUID(), UUID.randomUUID(), 1);
		Instant now = Instant.parse("2026-10-20T10:00:00Z");

		var unknown = OutboxEvent.create(aggregate, new OutboxEvent.EventData("SomethingElse", 1, null, null, "{}", now), now);
		var broken = OutboxEvent.create(aggregate,
				new OutboxEvent.EventData("KitchenCancellationRequested", 1, null, null, "{ not json", now), now);

		assertThrows(IllegalStateException.class, () -> recorder.toMessage(unknown));
		assertThrows(IllegalStateException.class, () -> recorder.toMessage(broken));
	}
}
