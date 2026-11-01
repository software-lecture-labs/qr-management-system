package com.restofy.order.application.service;

import com.restofy.order.api.dto.request.CheckoutRequest;
import com.restofy.order.api.dto.response.OrderResponse;
import com.restofy.order.application.mapper.CartMapper;
import com.restofy.order.application.mapper.OrderMapper;
import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.application.port.MenuCatalogPort;
import com.restofy.order.application.port.ProductSnapshot;
import com.restofy.order.application.port.QrSessionPort;
import com.restofy.order.application.service.CartLinePricer.Evaluation;
import com.restofy.order.application.service.CartLinePricer.PricedLine;
import com.restofy.order.application.service.CartLinePricer.PricedOption;
import com.restofy.order.domain.entity.Cart;
import com.restofy.order.domain.entity.CartItem;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.entity.OrderItem;
import com.restofy.order.domain.entity.OrderItemOption;
import com.restofy.order.domain.event.EventDestination;
import com.restofy.order.domain.event.OrderPlacedEvent;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.domain.history.ChangeActor;
import com.restofy.order.domain.pricing.PriceCalculator;
import com.restofy.order.domain.pricing.PriceCalculator.OrderAmounts;
import com.restofy.order.infrastructure.config.CorrelationIdFilter;
import com.restofy.order.infrastructure.config.IdempotencyProperties;
import com.restofy.order.infrastructure.messaging.OutboxEventWriter;
import com.restofy.order.infrastructure.persistence.IdempotencyStore;
import com.restofy.order.infrastructure.persistence.OrderNumberGenerator;
import com.restofy.order.infrastructure.persistence.OrderStatusHistoryStore;
import com.restofy.order.infrastructure.persistence.repository.CartRepository;
import com.restofy.order.infrastructure.persistence.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns the guest's cart into an order, atomically and at most once.
 *
 * <p>Flow: validate the idempotency key and replay a finished request; check session, cart, catalog, tax and the
 * client's confirmed total WITHOUT any DB lock (remote calls happen here); re-check the QR session; then one
 * short transaction claims the idempotency key, locks the cart, allocates the order number, writes the order
 * snapshot, marks the cart CHECKED_OUT and records the OrderPlaced outbox event. Any failure rolls all of it back.
 */
@Slf4j
@Service
public class OrderCheckoutService {

    static final String OPERATION = "CREATE_ORDER";
    private static final int CREATED = HttpStatus.CREATED.value();

    public record CheckoutResult(OrderResponse order, boolean replayed) {
    }

    private record PricedItem(CartItem item, PricedLine line) {
    }

    private record Prepared(List<PricedItem> items, OrderAmounts amounts, Map<UUID, ProductSnapshot> catalog) {
    }

    private final CartRepository carts;
    private final OrderRepository orders;
    private final MenuCatalogPort catalog;
    private final QrSessionPort qrSessions;
    private final CartLinePricer pricer;
    private final CartMapper cartMapper;
    private final OrderMapper orderMapper;
    private final IdempotencyStore idempotency;
    private final OrderNumberGenerator orderNumbers;
    private final OutboxEventWriter outbox;
    private final OrderStatusHistoryStore history;
    private final TableSessionClosureService closures;
    private final IdempotencyProperties retention;
    private final Clock clock;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    public OrderCheckoutService(CartRepository carts, OrderRepository orders, MenuCatalogPort catalog,
                                QrSessionPort qrSessions, CartLinePricer pricer, CartMapper cartMapper,
                                OrderMapper orderMapper, IdempotencyStore idempotency,
                                OrderNumberGenerator orderNumbers, OutboxEventWriter outbox, OrderStatusHistoryStore history,
                                TableSessionClosureService closures, IdempotencyProperties retention, Clock clock,
                                PlatformTransactionManager txManager) {
        this.carts = carts;
        this.orders = orders;
        this.catalog = catalog;
        this.qrSessions = qrSessions;
        this.pricer = pricer;
        this.cartMapper = cartMapper;
        this.orderMapper = orderMapper;
        this.idempotency = idempotency;
        this.orderNumbers = orderNumbers;
        this.outbox = outbox;
        this.history = history;
        this.closures = closures;
        this.retention = retention;
        this.clock = clock;
        this.writeTx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    public CheckoutResult checkout(GuestSessionContext guest, String idempotencyKey, CheckoutRequest request) {
        String key = IdempotencyKeys.require(idempotencyKey);
        String note = CartSelectionResolver.normalizeNote(request.note());
        String digest = digest(request, note);
        String actor = "guest:" + guest.guestSessionId();

        // A retry is answered only to a guest whose session is still active; a closed or expired session gets
        // nothing back (the order is then read through the order read API with its own authorization).
        requireActiveSession(guest.isActive());
        // A finished request is replayed even if the cart has changed since.
        Optional<IdempotencyStore.Record> previous = idempotency.find(guest.restaurantId(), actor, OPERATION, key);
        if (previous.isPresent()) {
            return replay(guest, previous.get(), digest);
        }

        Cart cart;
        try {
            cart = loadConfirmedCart(guest, request);
        } catch (BusinessException cartChanged) {
            // Another checkout may have committed after our first key lookup, consuming this cart.
            // Its idempotency record commits with the order, so re-read it before reporting a cart error.
            Optional<IdempotencyStore.Record> committed = idempotency.find(guest.restaurantId(), actor, OPERATION, key);
            if (committed.isPresent()) {
                return replay(guest, committed.get(), digest);
            }
            throw cartChanged;
        }

        Prepared prepared = price(guest, cart);
        // Reconcile per line, not only the total: two prices that move in opposite directions keep the total equal
        // but are still a change the guest has not seen. Any component difference (unit price, extras, VAT rate,
        // price version) or a different total means the guest must look at the cart again.
        List<PricedItem> changed = prepared.items().stream()
                .filter(p -> pricer.differsFromSnapshot(p.item(), p.line())).toList();
        String liveFingerprint = PriceFingerprint.of(cart, prepared.items().stream()
                .map(p -> new PriceFingerprint.Line(p.item(), p.line())).toList());
        boolean totalMatches = prepared.amounts().grossMinor() == request.expectedTotalMinor();
        String sentFingerprint = request.expectedPriceFingerprint();
        boolean fingerprintMatches = liveFingerprint.equals(sentFingerprint);
        boolean acknowledged = Boolean.TRUE.equals(request.acknowledgePriceChanges());
        // A price that differs from what the cart last validated is never ordered on the guest's behalf. It needs the
        // fingerprint of exactly these live prices (bound to this cart version, so an old or foreign confirmation cannot
        // be reused) AND an explicit acknowledgement; a client that sends neither (including every older client) is
        // refused and told what changed, and a blind retry of the same request is refused again. A client that sends a
        // fingerprint must always send the right one. Nothing is ever written to the cart by a refusal.
        boolean rejected = !totalMatches
                || (sentFingerprint != null && !fingerprintMatches)
                || (!changed.isEmpty() && !(fingerprintMatches && acknowledged));
        if (rejected) {
            throw priceChanged(guest, cart, prepared, changed, liveFingerprint);
        }

        // Re-ask the QR Service at the moment of ordering; the session may have closed since the request began.
        SessionState state = qrSessions.currentSessionState(guest.restaurantId(), guest.tableSessionId(),
                guest.guestSessionId()).orElse(SessionState.CLOSED);
        requireActiveSession(state == SessionState.ACTIVE);

        UUID orderId = UUID.randomUUID();
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        Order placed = writeTx.execute(status -> place(guest, key, digest, actor, orderId, request, note, prepared,
                correlationId));
        if (placed == null) {
            // A concurrent request with the same key committed first.
            IdempotencyStore.Record winner = idempotency.find(guest.restaurantId(), actor, OPERATION, key)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                            "The request conflicts with the current state, please retry"));
            return replay(guest, winner, digest);
        }
        log.info("Order placed: orderNo={} orderId={} restaurantId={} tableSessionId={}", placed.getOrderNo(),
                placed.getId(), placed.getRestaurantId(), placed.getTableSessionId());
        return new CheckoutResult(orderMapper.toResponse(placed), false);
    }

    private Cart loadConfirmedCart(GuestSessionContext guest, CheckoutRequest request) {
        Cart cart = readTx.execute(status -> carts.findActiveWithItems(guest.restaurantId(), guest.guestSessionId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_EMPTY, "The cart is empty"));
        if (cart.getItems().isEmpty()) {
            throw new BusinessException(ErrorCode.CART_EMPTY, "The cart is empty");
        }
        if (cart.getVersion() != request.expectedCartVersion()) {
            throw new BusinessException(ErrorCode.CART_VERSION_CONFLICT,
                    "The cart was changed in the meantime; reload it and try again",
                    Map.of("currentVersion", cart.getVersion()));
        }
        return cart;
    }

    /** Prices every line from the live catalog; nothing is ordered unless every line is priced with a known tax. */
    private Prepared price(GuestSessionContext guest, Cart cart) {
        Set<UUID> productIds = cart.getItems().stream().map(CartItem::getProductId).collect(Collectors.toSet());
        Map<UUID, ProductSnapshot> products = catalog.findProducts(guest.restaurantId(), productIds);
        List<PricedItem> items = new ArrayList<>();
        for (CartItem item : cart.getItems().stream()
                .sorted(Comparator.comparing(CartItem::getCreatedAt).thenComparing(CartItem::getId)).toList()) {
            Evaluation evaluation = pricer.evaluate(cart.getCurrency(), item, products.get(item.getProductId()));
            if (evaluation.line() == null) {
                throw new BusinessException(evaluation.failure(), evaluation.reason(),
                        Map.of("productId", item.getProductId()));
            }
            if (evaluation.line().price().lineNetMinor() == null) {
                throw new BusinessException(ErrorCode.TAX_DATA_MISSING,
                        "The tax rate of a product is unknown, so it cannot be ordered right now",
                        Map.of("productId", item.getProductId()));
            }
            items.add(new PricedItem(item, evaluation.line()));
        }
        try {
            OrderAmounts amounts = PriceCalculator.totalOf(items.stream().map(i -> i.line().price()).toList());
            return new Prepared(items, amounts, products);
        } catch (ArithmeticException _) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The order amount exceeds the allowed limit");
        }
    }

    /** Runs inside the write transaction. Returns null when the idempotency key was already claimed. */
    private Order place(GuestSessionContext guest, String key, String digest, String actor, UUID orderId,
                        CheckoutRequest request, String note, Prepared prepared, String correlationId) {
        // First statement of the transaction (ADR-QR-001 K8): order this checkout against closing the table session. A
        // closure prepared or completed first refuses the order here, before the idempotency key or anything else is
        // written; a checkout that got in first is part of the scope the closure then sees.
        closures.requireOpenForCheckout(guest.restaurantId(), guest.tableSessionId(), guest.guestSessionId());
        // PostgreSQL stores microseconds; truncating here keeps the first response identical to every replay.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!idempotency.tryClaim(guest.restaurantId(), actor, OPERATION, key, digest, orderId, CREATED, now,
                now.plus(retention.checkoutRetention()))) {
            return null;
        }
        Cart cart = carts.findActiveForUpdate(guest.restaurantId(), guest.guestSessionId()).orElseThrow(() ->
                new BusinessException(ErrorCode.CART_ALREADY_CHECKED_OUT, "This cart has already been ordered"));
        if (cart.getVersion() != request.expectedCartVersion()) {
            throw new BusinessException(ErrorCode.CART_VERSION_CONFLICT,
                    "The cart was changed in the meantime; reload it and try again",
                    Map.of("currentVersion", cart.getVersion()));
        }

        // Last step before the inserts: allocating the number takes the per-restaurant counter lock.
        String orderNo = orderNumbers.next(guest.restaurantId());
        OrderAmounts amounts = prepared.amounts();
        Order order = new Order(orderId, orderNo, guest.restaurantId(), cart.getId(), guest.tableId(),
                guest.tableSessionId(), guest.guestSessionId(), cart.getCurrency(), amounts.netMinor(),
                amounts.vatMinor(), amounts.grossMinor(), note, now);
        int lineNo = 0;
        for (PricedItem priced : prepared.items()) {
            var price = priced.line().price();
            OrderItem line = new OrderItem(order, ++lineNo, priced.item().getProductId(),
                    priced.line().product().name(),
                    priced.item().getQuantity(), cart.getCurrency(), price.unitGrossMinor(),
                    priced.line().product().taxRateBps(), price.lineGrossMinor(), price.lineNetMinor(),
                    price.lineVatMinor(), priced.line().product().priceVersion(), priced.item().getNote(), now);
            for (PricedOption option : priced.line().options()) {
                line.addOption(new OrderItemOption(line, option.optionId(), option.action(), option.quantity(),
                        option.name(), option.unitPriceDeltaMinor()));
            }
            order.addItem(line);
        }
        cart.checkOut(now);
        orders.saveAndFlush(order);
        // The timeline starts with the order itself: one initial row per independent status axis.
        history.append(new OrderStatusHistoryStore.Change(order.getRestaurantId(), order.getId(),
                OrderStatusHistoryStore.Axis.FULFILLMENT, null, order.getFulfillmentStatus().name(),
                ChangeActor.guest(guest.guestSessionId()), "CHECKOUT", null, null, now, correlationId));
        history.append(new OrderStatusHistoryStore.Change(order.getRestaurantId(), order.getId(),
                OrderStatusHistoryStore.Axis.PAYMENT, null, order.getPaymentStatus().name(), ChangeActor.system(),
                "CHECKOUT", null, null, now, correlationId));

        UUID eventId = UUID.randomUUID();
        OrderPlacedEvent event = orderMapper.toPlacedEvent(order, eventId, correlationId, now);
        outbox.write(eventId, order.getRestaurantId(), "ORDER", order.getId(), order.getVersion(), event.eventType(),
                event.schemaVersion(), correlationId, event, now, List.of(EventDestination.KITCHEN,
                        EventDestination.QR, EventDestination.NOTIFICATION, EventDestination.WAITER));
        return order;
    }

    /**
     * Builds the PRICE_CHANGED refusal. It names what changed (per product and per extra), the live total and the live
     * fingerprint, and tells whether an explicit acknowledgement is needed. It never writes anything: the cart (stored
     * prices and version) and the idempotency key stay untouched, so the guest can look at the prices and confirm again
     * with {@code expectedPriceFingerprint} = {@code currentPriceFingerprint}, {@code acknowledgePriceChanges=true} and
     * {@code expectedTotalMinor} = {@code currentTotalMinor}, with the very same Idempotency-Key.
     */
    private BusinessException priceChanged(GuestSessionContext guest, Cart cart, Prepared prepared,
                                           List<PricedItem> changed, String liveFingerprint) {
        var summary = cartMapper.toResponse(guest, Optional.of(cart), prepared.catalog());
        List<CartLinePricer.PriceChange> priceChanges = changed.stream()
                .flatMap(p -> pricer.describeChanges(p.item(), p.line()).stream()).toList();
        return new BusinessException(ErrorCode.PRICE_CHANGED,
                changed.isEmpty() ? "The confirmed total or price fingerprint does not match the current cart"
                        : "The prices changed since you reviewed the cart; review the changes and confirm them explicitly",
                Map.of("currentTotalMinor", prepared.amounts().grossMinor(), "currentCartVersion", cart.getVersion(),
                        "currentPriceFingerprint", liveFingerprint,
                        "acknowledgementRequired", !changed.isEmpty(),
                        "changedLines", changed.stream().map(p -> p.item().getId()).toList(),
                        "priceChanges", priceChanges, "cart", summary));
    }

    private CheckoutResult replay(GuestSessionContext guest, IdempotencyStore.Record previous, String digest) {
        if (!previous.requestDigest().equals(digest)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "This Idempotency-Key was already used with a different request");
        }
        Order order = readTx.execute(status -> orders.findByIdAndRestaurantId(previous.resourceId(),
                guest.restaurantId())).orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                "The request conflicts with the current state, please retry"));
        return new CheckoutResult(orderMapper.toResponse(order), true);
    }

    private static void requireActiveSession(boolean active) {
        if (!active) {
            throw new BusinessException(ErrorCode.TABLE_SESSION_NOT_ACTIVE,
                    "The table session is no longer active; an order cannot be placed");
        }
    }

    private static String digest(CheckoutRequest request, String note) {
        // Without a fingerprint the canonical form is exactly the original one, so retries recorded before the
        // fingerprint existed still match; with one, a different fingerprint is a different request.
        String canonical = "v1|" + request.expectedCartVersion() + "|" + request.expectedTotalMinor() + "|"
                + (note == null ? "" : note)
                + (request.expectedPriceFingerprint() == null ? "" : "|fp:" + request.expectedPriceFingerprint())
                + (Boolean.TRUE.equals(request.acknowledgePriceChanges()) ? "|ack" : "");
        return IdempotencyKeys.sha256(canonical);
    }
}
