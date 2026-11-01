package com.restofy.order.application.service;

import com.restofy.order.api.dto.request.KitchenCancellationDecisionRequest;
import com.restofy.order.api.dto.response.CancellationDecisionResponse;
import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.application.service.CancellationApplier.Money;
import com.restofy.order.domain.cancellation.CancellationCalculator;
import com.restofy.order.domain.cancellation.CancellationCalculator.Line;
import com.restofy.order.domain.cancellation.CancellationCalculator.Result;
import com.restofy.order.domain.cancellation.CancellationCalculator.Selection;
import com.restofy.order.domain.cancellation.CancellationPolicy;
import com.restofy.order.domain.cancellation.CancellationPolicy.Requester;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.entity.OrderItem;
import com.restofy.order.domain.enums.CancellationStatus;
import com.restofy.order.domain.enums.ServiceName;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.domain.history.ChangeActor;
import com.restofy.order.infrastructure.config.CorrelationIdFilter;
import com.restofy.order.infrastructure.persistence.CancellationStore;
import com.restofy.order.infrastructure.persistence.CancellationStore.ItemRow;
import com.restofy.order.infrastructure.persistence.CancellationStore.Row;
import com.restofy.order.infrastructure.persistence.InboxStore;
import com.restofy.order.infrastructure.persistence.repository.OrderRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Finalises a cancellation request that waited for the Kitchen. Only the trusted Kitchen service can call it (internal
 * endpoint, service credential), and the order it names must belong to the restaurant it names.
 *
 * <p>An approval is not taken on trust: under the order row lock the Order Service evaluates the rules again against the
 * order as it is NOW. If the order has since been paid (a customer request), moved on to READY or beyond, or changed in
 * any way that makes the request different from what was asked, the request is rejected by the Order Service itself
 * (decision source SYSTEM) instead of cancelling something that may already have been served. The Kitchen's own
 * rejection is recorded as such. The event id makes redelivery safe; a cancellation already decided is acknowledged
 * without effect. Without a Kitchen answer nothing is ever finalised.
 */
@Service
public class CancellationDecisionService {

    static final String EVENT_TYPE = "KitchenCancellationDecision";
    static final String OUTCOME_APPLIED = "APPLIED";
    static final String OUTCOME_STALE = "IGNORED_STALE";
    private static final String SYSTEM_REJECTION = "NOT_ALLOWED_STATE";
    private static final String ORDER_CHANGED = "ORDER_CHANGED";
    private static final String SYSTEM = "SYSTEM";
    private static final String SERVICE = "SERVICE";

    private record RejectionReason(String code, String note) {
    }

    private final OrderRepository orders;
    private final CancellationStore cancellations;
    private final CancellationApplier applier;
    private final CancellationEventPublisher events;
    private final InboxStore inbox;
    private final Clock clock;

    public CancellationDecisionService(OrderRepository orders, CancellationStore cancellations,
                                       CancellationApplier applier, CancellationEventPublisher events,
                                       InboxStore inbox, Clock clock) {
        this.orders = orders;
        this.cancellations = cancellations;
        this.applier = applier;
        this.events = events;
        this.inbox = inbox;
        this.clock = clock;
    }

    @Transactional
    public CancellationDecisionResponse decide(ServiceActor service, KitchenCancellationDecisionRequest request) {
        if (service.name() != ServiceName.KITCHEN) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "Only the Kitchen service may decide a cancellation");
        }
        boolean approved = "APPROVED".equals(request.decision());
        if (!approved && request.rejectionCode() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "A rejection needs a rejectionCode",
                    Map.of("parameter", "rejectionCode", "rule", "required when decision is REJECTED"));
        }
        if (approved && (request.rejectionCode() != null || request.rejectionNote() != null)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "An approval carries no rejection data",
                    Map.of("parameter", "rejectionCode", "rule", "only allowed when decision is REJECTED"));
        }
        String note = CancellationReasons.cleanText(request.rejectionNote());

        Order order = orders.findForUpdate(request.orderId(), request.restaurantId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));
        var processed = inbox.find(InboxStore.KITCHEN, request.eventId());
        if (processed.isPresent()) {
            if (!processed.get().orderId().equals(order.getId())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "This event id was already used for a different order");
            }
            Row known = cancellations.find(order.getRestaurantId(), order.getId(), request.cancellationId())
                    .orElseThrow(CancellationDecisionService::notFound);
            return response(processed.get().outcome(), true, order, known);
        }
        Row row = cancellations.find(order.getRestaurantId(), order.getId(), request.cancellationId())
                .orElseThrow(CancellationDecisionService::notFound);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        String outcome;
        if (!CancellationStatus.REQUESTED.name().equals(row.status())) {
            outcome = OUTCOME_STALE;
        } else {
            outcome = OUTCOME_APPLIED;
            List<ItemRow> items = cancellations.findItems(row.id());
            if (approved) {
                approve(order, row, items, now);
            } else {
                reject(order, row, items, "KITCHEN", ChangeActor.service(ServiceName.KITCHEN.name()),
                        new RejectionReason(request.rejectionCode(), note), now);
            }
            row = cancellations.find(order.getRestaurantId(), order.getId(), row.id()).orElseThrow();
        }
        inbox.append(InboxStore.KITCHEN, request.eventId(), order.getRestaurantId(), order.getId(), EVENT_TYPE, outcome,
                now);
        return response(outcome, false, order, row);
    }

    private void approve(Order order, Row row, List<ItemRow> items, Instant now) {
        Result result;
        try {
            CancellationPolicy.evaluate(requesterOf(row), order.getFulfillmentStatus(), order.getPaymentStatus());
            List<Selection> asked = items.stream().map(item -> new Selection(item.orderItemId(), item.quantity()))
                    .toList();
            result = CancellationCalculator.calculate(linesOf(order), false, asked);
        } catch (BusinessException refused) {
            // the order is no longer one this request may cancel: the Order Service answers for itself
            Object reason = refused.details() == null ? null : refused.details().get("reason");
            reject(order, row, items, SYSTEM, ChangeActor.system(),
                    new RejectionReason(reason == null ? SYSTEM_REJECTION : reason.toString(),
                            "The order changed while the request waited for the kitchen"), now);
            return;
        }
        if (!sameAsRequested(result, row, items)) {
            reject(order, row, items, SYSTEM, ChangeActor.system(),
                    new RejectionReason(ORDER_CHANGED, "The order changed while the request waited for the kitchen"), now);
            return;
        }
        Money money = applier.money(order, result);
        ChangeActor kitchen = ChangeActor.service(ServiceName.KITCHEN.name());
        String previousFulfillment = order.getFulfillmentStatus().name();
        cancellations.approve(row.id(), new CancellationStore.DecisionActor("KITCHEN", SERVICE, kitchen.id(),
                        kitchen.role(), now), money.previousDueMinor(),
                money.newDueMinor(), money.collectedMinor(), money.refundRequiredMinor());
        applier.apply(order, result, money, row.id(), kitchen, now, MDC.get(CorrelationIdFilter.MDC_KEY));
        Row decided = cancellations.find(order.getRestaurantId(), order.getId(), row.id()).orElseThrow();
        Map<UUID, Integer> remainingAfter = result.items().stream().collect(Collectors.toMap(
                CancellationCalculator.ItemCancellation::orderItemId,
                CancellationCalculator.ItemCancellation::remainingAfter));
        events.applied(order, decided, items, cancellations.findReasons(row.id()), remainingAfter,
                new CancellationEventPublisher.ApplicationSnapshot(previousFulfillment, order.getInitialGrossMinor(),
                        order.getInitialGrossMinor() - result.payableAfterMinor()), now);
    }

    private void reject(Order order, Row row, List<ItemRow> items, String source, ChangeActor decider,
                        RejectionReason reason, Instant now) {
        boolean byService = decider.type() == ChangeActor.Type.SERVICE;
        cancellations.reject(row.id(), new CancellationStore.DecisionActor(source, byService ? SERVICE : SYSTEM,
                        decider.id(), decider.role(), now), reason.code(), reason.note(), order.getCurrentDueMinor(),
                applier.collected(order));
        Row decided = cancellations.find(order.getRestaurantId(), order.getId(), row.id()).orElseThrow();
        Map<UUID, Integer> remaining = new HashMap<>();
        order.getItems().forEach(item -> remaining.put(item.getId(), item.getRemainingQuantity()));
        events.rejected(order, decided, items, remaining, byService, now);
    }

    /** What was asked for must be exactly what would be cancelled now: same lines, same units, same scope. */
    private static boolean sameAsRequested(Result result, Row row, List<ItemRow> items) {
        Map<UUID, Integer> asked = items.stream().collect(Collectors.toMap(ItemRow::orderItemId, ItemRow::quantity));
        Map<UUID, Integer> now = result.items().stream().collect(Collectors.toMap(
                CancellationCalculator.ItemCancellation::orderItemId,
                CancellationCalculator.ItemCancellation::quantity));
        return asked.equals(now) && result.scope().name().equals(row.scope());
    }

    private static List<Line> linesOf(Order order) {
        return order.getItems().stream().sorted(Comparator.comparingInt(OrderItem::getLineNo))
                .map(item -> new Line(item.getId(), item.getProductId(), item.getProductNameSnapshot(),
                        item.getOrderedQuantity(), item.getCancelledQuantity(), item.getUnitGrossMinor(),
                        item.getTaxRateBps()))
                .toList();
    }

    private static Requester requesterOf(Row row) {
        if ("GUEST".equals(row.requestedByType())) {
            return Requester.GUEST;
        }
        if (SERVICE.equals(row.requestedByType())) {
            return Requester.KITCHEN;
        }
        return "ADMIN".equals(row.requestedByRole()) ? Requester.ADMIN : Requester.WAITER;
    }

    private static CancellationDecisionResponse response(String outcome, boolean duplicate, Order order, Row row) {
        return new CancellationDecisionResponse(outcome, duplicate, order.getId(), row.id(), row.status(),
                order.getFulfillmentStatus().name(), order.getPaymentStatus().name());
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.CANCELLATION_NOT_FOUND, "The cancellation does not exist");
    }
}
