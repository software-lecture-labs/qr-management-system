package com.restofy.order.application.service;

import com.restofy.order.api.dto.request.CancelOrderRequest;
import com.restofy.order.api.dto.request.CancellationReasonRequest;
import com.restofy.order.api.dto.response.CancellationListResponse;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.api.dto.response.CancellationResponse;
import com.restofy.order.application.mapper.CancellationMapper;
import com.restofy.order.application.service.CancellationApplier.Money;
import com.restofy.order.domain.cancellation.CancellationCalculator;
import com.restofy.order.domain.cancellation.CancellationCalculator.ItemCancellation;
import com.restofy.order.domain.cancellation.CancellationCalculator.Line;
import com.restofy.order.domain.cancellation.CancellationCalculator.Result;
import com.restofy.order.domain.cancellation.CancellationCalculator.Selection;
import com.restofy.order.domain.cancellation.CancellationPolicy;
import com.restofy.order.domain.cancellation.CancellationPolicy.Mode;
import com.restofy.order.domain.cancellation.CancellationPolicy.Requester;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.enums.CancellationStatus;
import com.restofy.order.domain.enums.ReasonSource;
import com.restofy.order.domain.enums.StaffRole;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cancels a whole order or chosen lines and unit counts, for a guest (their own order, active table session) or for
 * waiter / admin staff (any order of their own restaurant). A request and its finalisation are separate facts:
 * <ul>
 *   <li>Kitchen has not started (RECEIVED): the request is decided at once (decision source AUTO).</li>
 *   <li>Kitchen is preparing (PREPARING): the request is stored as REQUESTED and only the trusted Kitchen service can
 *       finalise it, so the order never changes on an unverified word. Until then nothing about the order changes.</li>
 *   <li>READY, SERVED, COMPLETED, CANCELLED: refused. A guest cannot cancel a paid order; staff can, and the refund the
 *       cancellation requires is recorded, never performed or assumed.</li>
 * </ul>
 * One transaction locks the order row, so concurrent cancellations, payments and kitchen steps of one order run one
 * after the other; the cancellation, its lines and reasons, the order change, the history and the outbox events are
 * all-or-nothing. The Idempotency-Key makes a retry return the same cancellation instead of cancelling twice.
 */
@Slf4j
@Service
public class OrderCancellationService {

    static final String OPERATION = "CANCEL_ORDER";
    private static final int CREATED = HttpStatus.CREATED.value();
    private static final String PARAMETER = "parameter";

    public record Outcome(CancellationResponse cancellation, boolean replayed) {
    }

    private final OrderRepository orders;
    private final CancellationStore cancellations;
    private final CancellationApplier applier;
    private final CancellationEventPublisher events;
    private final CancellationMapper mapper;
    private final IdempotencyStore idempotency;
    private final IdempotencyProperties retention;
    private final Clock clock;

    public OrderCancellationService(OrderRepository orders, CancellationStore cancellations,
                                    CancellationApplier applier, CancellationEventPublisher events,
                                    CancellationMapper mapper, IdempotencyStore idempotency,
                                    IdempotencyProperties retention, Clock clock) {
        this.orders = orders;
        this.cancellations = cancellations;
        this.applier = applier;
        this.events = events;
        this.mapper = mapper;
        this.idempotency = idempotency;
        this.retention = retention;
        this.clock = clock;
    }

    @Transactional
    public Outcome request(RequestActor actor, UUID orderId, String idempotencyKey, CancelOrderRequest request) {
        String key = IdempotencyKeys.require(idempotencyKey);
        boolean cancelAll = Boolean.TRUE.equals(request.cancelAll());
        List<Selection> selections = selections(request.cancelAll(), request.items());
        var reasons = CancellationReasons.parse(request.reasonCodes(), request.reasonText());
        String digest = digest(orderId, cancelAll, selections, reasons);

        Requester requester = requester(actor);
        UUID restaurantId = restaurantOf(actor);
        // the order row is locked from here on: whoever else touches this order waits for us
        Order order = orders.findForUpdate(orderId, restaurantId)
                .orElseThrow(OrderCancellationService::notFound);
        authorise(actor, order);

        String actorScope = scopeOf(actor);
        Optional<IdempotencyStore.Record> previous = idempotency.find(restaurantId, actorScope, OPERATION, key);
        if (previous.isPresent()) {
            return replay(actor, order, previous.get(), digest);
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID cancellationId = UUID.randomUUID();
        if (!idempotency.tryClaim(restaurantId, actorScope, OPERATION, key, digest, cancellationId, CREATED, now,
                now.plus(retention.cancellationRetention()))) {
            // a concurrent request with the same key committed first
            IdempotencyStore.Record winner = idempotency.find(restaurantId, actorScope, OPERATION, key)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                            "The request conflicts with the current state, please retry"));
            return replay(actor, order, winner, digest);
        }

        if (cancellations.findOpen(order.getId()).isPresent()) {
            throw new BusinessException(ErrorCode.CANCELLATION_ALREADY_PENDING,
                    "A cancellation of this order is already waiting for the kitchen",
                    Map.of("orderId", order.getId()));
        }
        Mode mode = CancellationPolicy.evaluate(requester, order.getFulfillmentStatus(), order.getPaymentStatus());
        Result result = CancellationCalculator.calculate(linesOf(order), cancelAll, selections);
        Money money = applier.money(order, result);
        boolean immediate = mode == Mode.IMMEDIATE;
        String previousFulfillment = order.getFulfillmentStatus().name();
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);

        Row row = row(order, cancellationId, result, money, actor, mode, now);
        List<ItemRow> itemRows = result.items().stream().map(OrderCancellationService::itemRow).toList();
        List<ReasonRow> reasonRows = reasonRows(reasons, actor, now);
        cancellations.insert(row);
        cancellations.insertItems(restaurantId, cancellationId, itemRows);
        cancellations.insertReasons(restaurantId, cancellationId, reasonRows);

        Map<UUID, Integer> remainingAfter = result.items().stream()
                .collect(Collectors.toMap(ItemCancellation::orderItemId, ItemCancellation::remainingAfter));
        if (immediate) {
            applier.apply(order, result, money, cancellationId, changeActor(actor), now, correlationId);
            events.applied(order, row, itemRows, reasonRows, remainingAfter,
                    new CancellationEventPublisher.ApplicationSnapshot(previousFulfillment, order.getInitialGrossMinor(),
                            order.getInitialGrossMinor() - result.payableAfterMinor()), now);
        } else {
            events.requested(order, row, itemRows, reasonRows, remainingAfter, now);
        }
        log.info("Order cancellation {}: cancellationId={} orderId={} restaurantId={} scope={} status={}",
                immediate ? "applied" : "requested", cancellationId, order.getId(), restaurantId, row.scope(),
                row.status());
        return new Outcome(mapper.toResponse(order, row, itemRows, reasonRows, revealStaffIds(actor)), false);
    }

    /**
     * A waiter or the restaurant's administrator gives the reason afterwards for a cancellation that has none. Only
     * WAITER and ADMIN (KITCHEN and every other role are refused; the ADMIN's authority is not shared with anybody),
     * only when the cancellation has no reason yet, and only once: reasons are append-only, nothing is ever overwritten,
     * so neither the customer's reason nor a Kitchen's or a colleague's can be replaced or added to. Repeating the very
     * same completion by the same person returns the stored result (a retry), anything else is 409. Who did it and when
     * is recorded. The database enforces the same: a staff reason cannot be added next to a customer's, and not twice.
     */
    @Transactional
    public CancellationResponse completeReason(StaffActor staff, UUID orderId, UUID cancellationId,
                                               CancellationReasonRequest request) {
        if (staff.role() != StaffRole.WAITER && staff.role() != StaffRole.ADMIN) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "Only a waiter or an administrator completes the reason of a cancellation");
        }
        var reasons = CancellationReasons.parse(request.reasonCodes(), request.reasonText());
        if (reasons.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Give at least one reason code or a reason text",
                    Map.of(PARAMETER, "reasonCodes", "rule", "reasonCodes or reasonText is required"));
        }
        Order order = orders.findForUpdate(orderId, staff.restaurantId()).orElseThrow(OrderCancellationService::notFound);
        Row row = cancellations.find(order.getRestaurantId(), order.getId(), cancellationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CANCELLATION_NOT_FOUND,
                        "The cancellation does not exist"));
        List<ReasonRow> existing = cancellations.findReasons(row.id());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<ReasonRow> added = reasonRows(reasons, new RequestActor.Staff(staff), now);
        if (!existing.isEmpty()) {
            if (sameCompletion(existing, added, staff)) {
                return mapper.toResponse(order, row, cancellations.findItems(row.id()), existing,
                        staff.role() == StaffRole.ADMIN);
            }
            throw new BusinessException(ErrorCode.CANCELLATION_REASON_ALREADY_SET,
                    "This cancellation already has a reason; reasons are never changed or added to");
        }
        cancellations.insertReasons(order.getRestaurantId(), row.id(), added);
        cancellations.markStaffReason(row.id(), staff.userId().toString(), now);
        log.info("Cancellation reason completed: cancellationId={} orderId={} restaurantId={}", row.id(), order.getId(),
                order.getRestaurantId());
        Row updated = cancellations.find(order.getRestaurantId(), order.getId(), row.id()).orElseThrow();
        return mapper.toResponse(order, updated, cancellations.findItems(row.id()), cancellations.findReasons(row.id()),
                staff.role() == StaffRole.ADMIN);
    }

    /** Every cancellation of an order, for the order's own guest or for waiter / admin staff. */
    @Transactional(readOnly = true)
    public CancellationListResponse list(RequestActor actor, UUID orderId) {
        requester(actor);   // KITCHEN and any other role are refused here
        Order order = orders.findByIdAndRestaurantId(orderId, restaurantOf(actor))
                .orElseThrow(OrderCancellationService::notFound);
        authorise(actor, order);
        boolean reveal = revealStaffIds(actor);
        List<CancellationResponse> all = cancellations.findByOrder(order.getRestaurantId(), order.getId()).stream()
                .map(row -> mapper.toResponse(order, row, cancellations.findItems(row.id()),
                        cancellations.findReasons(row.id()), reveal))
                .toList();
        return new CancellationListResponse(order.getId(), all);
    }

    private static boolean sameCompletion(List<ReasonRow> existing, List<ReasonRow> added, StaffActor staff) {
        if (existing.size() != added.size()) {
            return false;
        }
        // only a reason that THIS staff member really wrote counts; a service's reason (even one that names this user as the
        // staff member it claims to act for) has another id and another role and is never a repeat of their own
        Set<String> stored = existing.stream().filter(row -> "STAFF".equals(row.source())
                && staff.role().name().equals(row.addedByRole())
                && staff.userId().toString().equals(row.addedById())).map(OrderCancellationService::signature)
                .collect(Collectors.toSet());
        return stored.size() == existing.size()
                && stored.equals(added.stream().map(OrderCancellationService::signature).collect(Collectors.toSet()));
    }

    private static String signature(ReasonRow row) {
        return (row.code() == null ? "-" : row.code()) + "|" + (row.text() == null ? "-" : row.text());
    }

    private Outcome replay(RequestActor actor, Order order, IdempotencyStore.Record previous, String digest) {
        if (!previous.requestDigest().equals(digest)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "This Idempotency-Key was already used with a different request");
        }
        Row row = cancellations.find(order.getRestaurantId(), order.getId(), previous.resourceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                        "The request conflicts with the current state, please retry"));
        return new Outcome(mapper.toResponse(order, row, cancellations.findItems(row.id()),
                cancellations.findReasons(row.id()), revealStaffIds(actor)), true);
    }

    /** Exactly one of cancelAll and items; shared with the Kitchen's own request. */
    static List<Selection> selections(Boolean cancelAllFlag, List<CancelOrderRequest.Item> items) {
        boolean cancelAll = Boolean.TRUE.equals(cancelAllFlag);
        boolean hasItems = items != null && !items.isEmpty();
        if (cancelAll == hasItems) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Send either cancelAll: true or the items to cancel, not both and not neither",
                    Map.of(PARAMETER, "cancelAll", "rule", "exactly one of cancelAll and items"));
        }
        if (!hasItems) {
            return List.of();
        }
        return items.stream().map(item -> new Selection(item.orderItemId(), item.quantity())).toList();
    }

    static List<Line> linesOf(Order order) {
        return order.getItems().stream()
                .sorted(Comparator.comparingInt(item -> item.getLineNo()))
                .map(item -> new Line(item.getId(), item.getProductId(), item.getProductNameSnapshot(),
                        item.getOrderedQuantity(), item.getCancelledQuantity(), item.getUnitGrossMinor(),
                        item.getTaxRateBps()))
                .toList();
    }

    private static Row row(Order order, UUID id, Result result, Money money, RequestActor actor, Mode mode,
                           Instant now) {
        boolean immediate = mode == Mode.IMMEDIATE;
        ChangeActor who = changeActor(actor);
        return new Row(id, order.getRestaurantId(), order.getId(), result.scope().name(),
                (immediate ? CancellationStatus.APPLIED : CancellationStatus.REQUESTED).name(), who.type().name(),
                who.id(), who.role(), null, now, !immediate, order.getFulfillmentStatus().name(),
                order.getPaymentStatus().name(), immediate ? "AUTO" : null, immediate ? "SYSTEM" : null, null, null,
                immediate ? now : null, null, null, order.getCurrency(), money.previousDueMinor(),
                result.grossMinor(), result.netMinor(), result.vatMinor(), money.newDueMinor(),
                money.collectedMinor(), money.refundRequiredMinor(), 0L, null, null, 0L, now);
    }

    static ItemRow itemRow(ItemCancellation item) {
        return new ItemRow(item.orderItemId(), item.productId(), item.productName(), item.quantity(),
                item.unitGrossMinor(), item.taxRateBps(), item.grossMinor(), item.netMinor(), item.vatMinor());
    }

    private static List<ReasonRow> reasonRows(CancellationReasons.Parsed reasons, RequestActor actor, Instant now) {
        ChangeActor who = changeActor(actor);
        String source = (actor instanceof RequestActor.Guest ? ReasonSource.GUEST : ReasonSource.STAFF).name();
        List<ReasonRow> rows = new ArrayList<>();
        reasons.codes().forEach(code -> rows.add(new ReasonRow(source, code.name(), null, source, who.id(), who.role(),
                now)));
        if (reasons.text() != null) {
            rows.add(new ReasonRow(source, null, reasons.text(), source, who.id(), who.role(), now));
        }
        return rows;
    }

    private static ChangeActor changeActor(RequestActor actor) {
        return switch (actor) {
            case RequestActor.Guest(var guest) -> ChangeActor.guest(guest.guestSessionId());
            case RequestActor.Staff(var staff) -> ChangeActor.staff(staff);
        };
    }

    private static Requester requester(RequestActor actor) {
        return switch (actor) {
            case RequestActor.Guest _ -> Requester.GUEST;
            case RequestActor.Staff(var staff) -> switch (staff.role()) {
                case WAITER -> Requester.WAITER;
                case ADMIN -> Requester.ADMIN;
                default -> throw new BusinessException(ErrorCode.ACCESS_DENIED, "This role may not cancel orders");
            };
        };
    }

    private static UUID restaurantOf(RequestActor actor) {
        return switch (actor) {
            case RequestActor.Guest(var guest) -> guest.restaurantId();
            case RequestActor.Staff(var staff) -> staff.restaurantId();
        };
    }

    private static String scopeOf(RequestActor actor) {
        return switch (actor) {
            case RequestActor.Guest(var guest) -> "guest:" + guest.guestSessionId();
            case RequestActor.Staff(var staff) -> "staff:" + staff.userId();
        };
    }

    private static boolean revealStaffIds(RequestActor actor) {
        return actor instanceof RequestActor.Staff(var staff) && staff.role() == StaffRole.ADMIN;
    }

    /** A guest reaches only their own order of their active table session; anything else looks like a missing order. */
    private static void authorise(RequestActor actor, Order order) {
        if (actor instanceof RequestActor.Guest(var guest)) {
            if (!guest.isActive()) {
                throw new BusinessException(ErrorCode.TABLE_SESSION_NOT_ACTIVE,
                        "The table session is no longer active; the order can no longer be cancelled");
            }
            if (!order.getGuestSessionId().equals(guest.guestSessionId())) {
                throw notFound();
            }
        }
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.ORDER_NOT_FOUND, "The order does not exist");
    }

    private static String digest(UUID orderId, boolean cancelAll, List<Selection> selections,
                                 CancellationReasons.Parsed reasons) {
        Map<UUID, Integer> sorted = new HashMap<>();
        selections.forEach(selection -> sorted.put(selection.orderItemId(), selection.quantity()));
        String items = sorted.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue()).collect(Collectors.joining(","));
        String codes = reasons.codes().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        return IdempotencyKeys.sha256("v1|" + orderId + "|" + (cancelAll ? "all" : items) + "|" + codes + "|"
                + (reasons.text() == null ? "" : reasons.text()));
    }
}
