package com.restofy.order.application.service;

import com.restofy.order.api.dto.request.KitchenCancellationRequest;
import com.restofy.order.api.dto.response.KitchenCancellationRequestResponse;
import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.application.service.CancellationApplier.Money;
import com.restofy.order.domain.cancellation.CancellationCalculator;
import com.restofy.order.domain.cancellation.CancellationCalculator.ItemCancellation;
import com.restofy.order.domain.cancellation.CancellationCalculator.Result;
import com.restofy.order.domain.cancellation.CancellationCalculator.Selection;
import com.restofy.order.domain.cancellation.CancellationPolicy;
import com.restofy.order.domain.cancellation.CancellationPolicy.Requester;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.enums.CancellationReasonCode;
import com.restofy.order.domain.enums.CancellationStatus;
import com.restofy.order.domain.enums.ServiceName;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.domain.history.ChangeActor;
import com.restofy.order.infrastructure.config.CorrelationIdFilter;
import com.restofy.order.infrastructure.config.IdempotencyProperties;
import com.restofy.order.infrastructure.persistence.CancellationStore;
import com.restofy.order.infrastructure.persistence.CancellationStore.ItemRow;
import com.restofy.order.infrastructure.persistence.CancellationStore.ReasonRow;
import com.restofy.order.infrastructure.persistence.CancellationStore.Row;
import com.restofy.order.infrastructure.persistence.IdempotencyStore;
import com.restofy.order.infrastructure.persistence.InboxStore;
import com.restofy.order.infrastructure.persistence.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Kitchen service asks to cancel an order (or lines of it) it cannot make. Receiving the request never cancels by
 * itself: the Order Service stays the only authority and applies its own rules, in one transaction under the order row
 * lock, then either cancels (the Kitchen's request is its own confirmation, so nothing waits) or refuses and records the
 * refusal with its code. Both outcomes leave a cancellation record, its lines, the mandatory reason and the matching
 * outbox events; a request that is not valid at all (unknown order, bad selection, no reason) is refused before anything
 * is written and is not remembered, so the Kitchen can correct and send it again.
 *
 * <p>Rules (see {@link CancellationPolicy}): RECEIVED and PREPARING orders only (D-02 keeps READY and later closed); a
 * PAID order is never cancelled on the Kitchen's word (a waiter or admin decides, the refusal is recorded and announced);
 * while another request still waits for the Kitchen's answer, this one is refused with 409 and the Kitchen answers that
 * one through the decision endpoint instead. The Kitchen's {@code eventId} makes redelivery safe.
 *
 * <p>The record: the requester is the verified Kitchen SERVICE (requested_by_type SERVICE, id and role KITCHEN, which the
 * schema allows for no other service), never a staff member. The staff member the Kitchen says it acted for
 * ({@code requestedByStaffId}) is only a claim: it is stored apart in {@code reported_staff_id}, shown apart, and never
 * used as an author, as authority, or to decide that somebody "already did" something. The reasons are SERVICE reasons of
 * the same author. The request (received at {@code requested_at}) and the decision (taken by the Order Service at
 * {@code decided_at}) are separate facts even though one call makes both.
 */
@Slf4j
@Service
public class KitchenCancellationRequestService {

    static final String OPERATION = "KITCHEN_CANCEL_REQUEST";
    static final String EVENT_TYPE_APPLIED = "KitchenCancellationRequest";
    static final String EVENT_TYPE_REJECTED = "KitchenCancellationRequestRejected";
    private static final String KITCHEN = ServiceName.KITCHEN.name();
    private static final String SCOPE = "service:" + KITCHEN;
    private static final String PARAMETER = "parameter";
    private static final String SERVICE = "SERVICE";
    private static final int OK = HttpStatus.OK.value();

    private record RequestTiming(Instant receivedAt, Instant decidedAt) {
    }

    private final OrderRepository orders;
    private final CancellationStore cancellations;
    private final CancellationApplier applier;
    private final CancellationEventPublisher events;
    private final InboxStore inbox;
    private final IdempotencyStore idempotency;
    private final IdempotencyProperties retention;
    private final Clock clock;

    public KitchenCancellationRequestService(OrderRepository orders, CancellationStore cancellations,
                                             CancellationApplier applier, CancellationEventPublisher events,
                                             InboxStore inbox, IdempotencyStore idempotency,
                                             IdempotencyProperties retention, Clock clock) {
        this.orders = orders;
        this.cancellations = cancellations;
        this.applier = applier;
        this.events = events;
        this.inbox = inbox;
        this.idempotency = idempotency;
        this.retention = retention;
        this.clock = clock;
    }

    @Transactional
    public KitchenCancellationRequestResponse request(ServiceActor service, KitchenCancellationRequest request) {
        if (service.name() != ServiceName.KITCHEN) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "Only the Kitchen service may ask for this cancellation");
        }
        boolean cancelAll = Boolean.TRUE.equals(request.cancelAll());
        List<Selection> selections = OrderCancellationService.selections(request.cancelAll(), request.items());
        var reasons = CancellationReasons.parse(request.reasonCodes(), request.reasonText());
        validateReasons(reasons);
        String digest = digest(request, cancelAll, selections, reasons);

        // the order must exist in exactly the restaurant the request names; anything else looks like a missing order
        Order order = orders.findForUpdate(request.orderId(), request.restaurantId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));
        String key = request.eventId().toString();
        Optional<IdempotencyStore.Record> previous = idempotency.find(order.getRestaurantId(), SCOPE, OPERATION, key);
        if (previous.isPresent()) {
            return replay(order, previous.get(), digest);
        }
        var processed = inbox.find(InboxStore.KITCHEN, request.eventId());
        if (processed.isPresent()) {
            return forgottenDuplicate(order, processed.get());
        }

        // the request is received now; the decision is taken (and timed) after the rules were applied below
        Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant now = receivedAt;
        UUID cancellationId = UUID.randomUUID();
        if (!idempotency.tryClaim(order.getRestaurantId(), SCOPE, OPERATION, key, digest, cancellationId, OK, now,
                now.plus(retention.cancellationRetention()))) {
            IdempotencyStore.Record winner = idempotency.find(order.getRestaurantId(), SCOPE, OPERATION, key)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                            "The request conflicts with the current state, please retry"));
            return replay(order, winner, digest);
        }
        Optional<Row> open = cancellations.findOpen(order.getId());
        if (open.isPresent()) {
            throw new BusinessException(ErrorCode.CANCELLATION_ALREADY_PENDING,
                    "Another cancellation of this order is waiting for the kitchen's answer; answer that one instead",
                    Map.of("orderId", order.getId(), "cancellationId", open.get().id()));
        }

        // a bad selection is an invalid request (422/409), not a refusal to record
        Result result = CancellationCalculator.calculate(OrderCancellationService.linesOf(order), cancelAll, selections);
        String rejection = null;
        try {
            CancellationPolicy.evaluate(Requester.KITCHEN, order.getFulfillmentStatus(), order.getPaymentStatus());
        } catch (BusinessException refused) {
            if (refused.code() != ErrorCode.CANCELLATION_NOT_ALLOWED) {
                throw refused;
            }
            rejection = String.valueOf(refused.details().get("reason"));
        }
        boolean accepted = rejection == null;
        Instant decidedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (decidedAt.isBefore(receivedAt)) {
            decidedAt = receivedAt;
        }
        now = decidedAt;

        Money money = accepted ? applier.money(order, result)
                : new Money(order.getCurrentDueMinor(), order.getCurrentDueMinor(), applier.collected(order), 0L);
        Row row = row(order, cancellationId, result, money, request.requestedByStaffId(), rejection,
                new RequestTiming(receivedAt, decidedAt));
        List<ItemRow> itemRows = result.items().stream().map(OrderCancellationService::itemRow).toList();
        List<ReasonRow> reasonRows = reasonRows(reasons, receivedAt);
        cancellations.insert(row);
        cancellations.insertItems(order.getRestaurantId(), cancellationId, itemRows);
        cancellations.insertReasons(order.getRestaurantId(), cancellationId, reasonRows);

        String previousFulfillment = order.getFulfillmentStatus().name();
        Map<UUID, Integer> remainingAfter = result.items().stream()
                .collect(Collectors.toMap(ItemCancellation::orderItemId, ItemCancellation::remainingAfter));
        if (accepted) {
            applier.apply(order, result, money, cancellationId, ChangeActor.service(KITCHEN), now,
                    MDC.get(CorrelationIdFilter.MDC_KEY));
            events.applied(order, row, itemRows, reasonRows, remainingAfter,
                    new CancellationEventPublisher.ApplicationSnapshot(previousFulfillment, order.getInitialGrossMinor(),
                            order.getInitialGrossMinor() - result.payableAfterMinor(), true), now);
        } else {
            // nothing changes, so every line keeps what is still active now
            Map<UUID, Integer> current = result.items().stream().collect(Collectors.toMap(ItemCancellation::orderItemId,
                    item -> item.remainingAfter() + item.quantity()));
            events.rejected(order, row, itemRows, current, true, now);
        }
        inbox.append(InboxStore.KITCHEN, request.eventId(), order.getRestaurantId(), order.getId(),
                accepted ? EVENT_TYPE_APPLIED : EVENT_TYPE_REJECTED, "APPLIED", now);
        log.info("Kitchen cancellation request {}: cancellationId={} orderId={} restaurantId={} scope={}",
                accepted ? "applied" : "refused (" + rejection + ")", cancellationId, order.getId(),
                order.getRestaurantId(), row.scope());
        return response(false, order, row);
    }

    private KitchenCancellationRequestResponse replay(Order order, IdempotencyStore.Record previous, String digest) {
        if (!previous.requestDigest().equals(digest)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "This event id was already used with a different request");
        }
        Row row = cancellations.find(order.getRestaurantId(), order.getId(), previous.resourceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "This event id was already used for a different order"));
        return response(true, order, row);
    }

    /** The idempotency record is gone (72 h) but the inbox still remembers the event: nothing is done a second time. */
    private KitchenCancellationRequestResponse forgottenDuplicate(Order order, InboxStore.Processed processed) {
        if (!processed.orderId().equals(order.getId())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "This event id was already used for a different order");
        }
        String outcome = EVENT_TYPE_REJECTED.equals(processed.eventType())
                ? CancellationStatus.REJECTED.name() : CancellationStatus.APPLIED.name();
        return new KitchenCancellationRequestResponse(outcome, true, order.getId(), null, null, null,
                order.getFulfillmentStatus().name(), order.getPaymentStatus().name());
    }

    private static KitchenCancellationRequestResponse response(boolean duplicate, Order order, Row row) {
        return new KitchenCancellationRequestResponse(row.status(), duplicate, order.getId(), row.id(), row.scope(),
                row.rejectionCode(), order.getFulfillmentStatus().name(), order.getPaymentStatus().name());
    }

    private static void validateReasons(CancellationReasons.Parsed reasons) {
        if (reasons.codes().isEmpty()) {
            throw invalid("reasonCodes", "at least one reason code is required");
        }
        if (reasons.codes().contains(CancellationReasonCode.OTHER) && reasons.text() == null) {
            throw invalid("reasonText", "required when the reason code OTHER is used");
        }
    }

    private static Row row(Order order, UUID id, Result result, Money money, UUID reportedStaffId, String rejection,
                           RequestTiming timing) {
        boolean accepted = rejection == null;
        return new Row(id, order.getRestaurantId(), order.getId(), result.scope().name(),
                (accepted ? CancellationStatus.APPLIED : CancellationStatus.REJECTED).name(), SERVICE, KITCHEN,
                KITCHEN, reportedStaffId, timing.receivedAt(), false, order.getFulfillmentStatus().name(), order.getPaymentStatus().name(),
                // accepted: decided by the Order Service's rules on the Kitchen's request; refused: by the rules alone
                accepted ? "KITCHEN" : "SYSTEM", "SYSTEM", null, null, timing.decidedAt(), rejection,
                accepted ? null : "The order cannot be cancelled on the kitchen's request in its current state",
                order.getCurrency(), money.previousDueMinor(), accepted ? result.grossMinor() : 0L,
                accepted ? result.netMinor() : 0L, accepted ? result.vatMinor() : 0L, money.newDueMinor(),
                money.collectedMinor(), money.refundRequiredMinor(), 0L, null, null, 0L, timing.receivedAt());
    }

    private static List<ReasonRow> reasonRows(CancellationReasons.Parsed reasons, Instant now) {
        List<ReasonRow> rows = new ArrayList<>();
        reasons.codes().forEach(code -> rows.add(new ReasonRow(SERVICE, code.name(), null, SERVICE,
                KITCHEN, KITCHEN, now)));
        if (reasons.text() != null) {
            rows.add(new ReasonRow(SERVICE, null, reasons.text(), SERVICE, KITCHEN, KITCHEN, now));
        }
        return rows;
    }

    private static String digest(KitchenCancellationRequest request, boolean cancelAll, List<Selection> selections,
                                 CancellationReasons.Parsed reasons) {
        String items = selections.stream().sorted(Comparator.comparing(Selection::orderItemId))
                .map(s -> s.orderItemId() + ":" + s.quantity()).collect(Collectors.joining(","));
        String codes = reasons.codes().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        return IdempotencyKeys.sha256("v1|kitchen|" + request.orderId() + "|" + (cancelAll ? "all" : items) + "|" + codes
                + "|" + (reasons.text() == null ? "" : reasons.text()) + "|"
                + (request.requestedByStaffId() == null ? "" : request.requestedByStaffId()));
    }

    private static BusinessException invalid(String parameter, String rule) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid Kitchen cancellation request",
                Map.of(PARAMETER, parameter, "rule", rule));
    }
}
