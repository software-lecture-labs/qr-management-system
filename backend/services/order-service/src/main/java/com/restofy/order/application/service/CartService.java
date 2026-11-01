package com.restofy.order.application.service;

import com.restofy.order.api.dto.request.AddCartItemRequest;
import com.restofy.order.api.dto.request.ExtraSelectionRequest;
import com.restofy.order.api.dto.request.UpdateCartItemQuantityRequest;
import com.restofy.order.api.dto.response.CartResponse;
import com.restofy.order.application.mapper.CartMapper;
import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.MenuCatalogPort;
import com.restofy.order.application.port.ProductSnapshot;
import com.restofy.order.application.service.CartSelectionResolver.ExtraChoice;
import com.restofy.order.application.service.ResolvedSelection.ResolvedOption;
import com.restofy.order.domain.entity.Cart;
import com.restofy.order.domain.entity.CartItem;
import com.restofy.order.domain.entity.CartItemOption;
import com.restofy.order.domain.event.CartActivityRecordedEvent;
import com.restofy.order.domain.event.CartActivityRecordedEvent.Activity;
import com.restofy.order.domain.event.EventDestination;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.domain.pricing.PriceCalculator;
import com.restofy.order.infrastructure.config.CartProperties;
import com.restofy.order.infrastructure.config.CorrelationIdFilter;
import com.restofy.order.infrastructure.config.IdempotencyProperties;
import com.restofy.order.infrastructure.messaging.OutboxEventWriter;
import com.restofy.order.infrastructure.persistence.IdempotencyStore;
import com.restofy.order.infrastructure.persistence.repository.CartRepository;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cart use cases for a QR guest. Remote catalog calls happen outside DB transactions; each mutation runs in
 * one short transaction that row-locks the guest's cart, so concurrent changes to one cart are serialized.
 * All ids come from the trusted {@link GuestSessionContext}, never from the request.
 */
@Service
public class CartService {

    private final CartRepository carts;
    private final MenuCatalogPort catalog;
    private final CartSelectionResolver selections;
    private final CartMapper mapper;
    private final CartProperties limits;
    private final IdempotencyStore idempotency;
    private final IdempotencyProperties retention;
    private final OutboxEventWriter outbox;
    private final Clock clock;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    public CartService(CartRepository carts, MenuCatalogPort catalog, CartSelectionResolver selections,
                       CartMapper mapper, CartProperties limits, IdempotencyStore idempotency,
                       IdempotencyProperties retention, OutboxEventWriter outbox, Clock clock,
                       PlatformTransactionManager txManager) {
        this.carts = carts;
        this.catalog = catalog;
        this.selections = selections;
        this.mapper = mapper;
        this.limits = limits;
        this.idempotency = idempotency;
        this.retention = retention;
        this.outbox = outbox;
        this.clock = clock;
        this.writeTx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    /** Never writes: a guest without items simply gets an empty cart view. */
    public CartResponse getCurrent(GuestSessionContext context) {
        return view(context);
    }

    /** The cart after an addition, and whether it is the replay of an earlier identical request. */
    public record CartMutation(CartResponse cart, boolean replayed) {
    }

    /**
     * Adds a product. With an {@code Idempotency-Key} the call is idempotent per (guest, key): an exact retry does
     * not add the quantity again, the same key with a different request is a conflict, and a failed attempt does not
     * consume the key. Without a key the call is not idempotent (a retry adds again).
     */
    public CartMutation addItem(GuestSessionContext context, String idempotencyKey, AddCartItemRequest request) {
        String key = IdempotencyKeys.optional(idempotencyKey);
        requireActiveSession(context);
        requireQuantityWithinLimit(request.quantity());
        String note = CartSelectionResolver.normalizeNote(request.note());
        String digest = key == null ? null : IdempotencyKeys.sha256(canonical(request, note));
        String actor = "guest:" + context.guestSessionId();

        if (key != null) {
            Optional<IdempotencyStore.Record> previous = idempotency.find(context.restaurantId(), actor,
                    IdempotencyKeys.CART_ITEM, key);
            if (previous.isPresent()) {
                return replay(context, previous.get(), digest);
            }
        }

        ProductSnapshot product = sellableProduct(context, request.productId());
        List<ExtraChoice> extras = request.extras() == null ? List.of() : request.extras().stream()
                .map((ExtraSelectionRequest extra) -> new ExtraChoice(extra.ingredientId(), extra.quantity()))
                .toList();
        ResolvedSelection selection = selections.resolve(product, request.removedIngredientIds(), extras,
                request.note());
        long unitGross = unitGrossOf(product, selection, request.quantity());

        Boolean alreadyClaimed = writeTx.execute(status -> {
            Cart cart = lockOrCreateCart(context);
            Instant now = clock.instant();
            // The cart lock serializes this guest's requests, so a concurrent twin has already committed (or not
            // started) by the time we claim. A failure further down rolls the claim back with everything else.
            if (key != null && !idempotency.tryClaim(context.restaurantId(), actor, IdempotencyKeys.CART_ITEM, key,
                    digest, cart.getId(), HttpStatus.OK.value(), now, now.plus(retention.cartItemRetention()))) {
                return true;
            }
            requireExpectedVersion(cart, request.expectedCartVersion());
            putLine(cart, product, selection, unitGross, request.quantity(), now);
            cart.touch(now);
            recordActivity(context, cart, Activity.ITEM_ADDED, now);
            return false;
        });
        if (Boolean.TRUE.equals(alreadyClaimed)) {
            IdempotencyStore.Record winner = idempotency.find(context.restaurantId(), actor,
                    IdempotencyKeys.CART_ITEM, key).orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                    "The request conflicts with the current state, please retry"));
            return replay(context, winner, digest);
        }
        return new CartMutation(view(context), false);
    }

    /** The catalog's current view of a product that exists and may be sold right now, or the matching refusal. */
    private ProductSnapshot sellableProduct(GuestSessionContext context, UUID productId) {
        ProductSnapshot product = catalog.findProducts(context.restaurantId(), Set.of(productId)).get(productId);
        if (product == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "The product does not exist");
        }
        if (!product.sellable()) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE, "The product is currently not available");
        }
        return product;
    }

    /** The unit price including extras; refuses a line whose amount would not fit the money arithmetic. */
    private static long unitGrossOf(ProductSnapshot product, ResolvedSelection selection, int quantity) {
        try {
            long unitGross = PriceCalculator.unitGross(product.basePriceGrossMinor(), selection.extrasUnitDeltaMinor());
            PriceCalculator.priceLine(unitGross, quantity, product.taxRateBps());
            return unitGross;
        } catch (ArithmeticException _) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The line amount exceeds the allowed limit");
        }
    }

    /**
     * Adds the validated selection to the locked cart: the first line fixes the cart's currency, a later product must
     * use it; an identical selection merges into its line (and refreshes the validated prices), a new one is a new line.
     */
    private void putLine(Cart cart, ProductSnapshot product, ResolvedSelection selection, long unitGross, int quantity,
                         Instant now) {
        if (cart.getItems().isEmpty()) {
            cart.useCurrency(product.currency());
        } else if (!product.currency().equals(cart.getCurrency())) {
            throw new BusinessException(ErrorCode.CURRENCY_MISMATCH,
                    "The product currency does not match the cart currency");
        }

        Optional<CartItem> existing = cart.findItem(product.productId(), selection.selectionKey());
        if (existing.isPresent()) {
            CartItem item = existing.get();
            int merged = item.getQuantity() + quantity;
            requireQuantityWithinLimit(merged);
            item.changeQuantity(merged, now);
            item.refreshSnapshot(product.name(), unitGross, product.taxRateBps(), product.priceVersion(), now);
            item.refreshOptionPrices(selection.options().stream().collect(Collectors.toMap(
                    ResolvedOption::optionId, ResolvedOption::unitPriceDeltaMinor)));
            return;
        }
        if (cart.getItems().size() >= limits.maxLines()) {
            throw new BusinessException(ErrorCode.CART_ITEM_LIMIT_EXCEEDED,
                    "The cart cannot contain more than " + limits.maxLines() + " different lines");
        }
        CartItem item = new CartItem(cart, product.productId(), quantity, selection.selectionKey(), selection.note(),
                product.name(), product.currency(), unitGross, product.taxRateBps(), product.priceVersion(), now);
        for (ResolvedOption option : selection.options()) {
            item.addOption(new CartItemOption(item, option.optionId(), option.action(), option.quantity(),
                    option.name(), option.unitPriceDeltaMinor()));
        }
        cart.addItem(item);
    }

    /**
     * Answers an exact retry with the cart as it is now (which already contains the original addition). Nothing is
     * replayed to a guest whose session is no longer active, to a different request, or for a cart that was
     * meanwhile ordered or replaced.
     */
    private CartMutation replay(GuestSessionContext context, IdempotencyStore.Record previous, String digest) {
        if (!previous.requestDigest().equals(digest)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "This Idempotency-Key was already used with a different request");
        }
        Optional<Cart> active = readTx.execute(status ->
                carts.findActiveWithItems(context.restaurantId(), context.guestSessionId()));
        if (active.isEmpty() || !active.get().getId().equals(previous.resourceId())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "This Idempotency-Key belongs to a cart that is no longer active; use a new key");
        }
        return new CartMutation(view(context), true);
    }

    private static String canonical(AddCartItemRequest request, String note) {
        String removed = request.removedIngredientIds() == null ? "" : request.removedIngredientIds().stream()
                .map(UUID::toString).sorted().collect(Collectors.joining(","));
        String extras = request.extras() == null ? "" : request.extras().stream()
                .sorted(Comparator.comparing((ExtraSelectionRequest e) -> e.ingredientId().toString()))
                .map(e -> e.ingredientId() + ":" + e.quantity()).collect(Collectors.joining(","));
        return "v1|" + request.productId() + "|" + request.quantity() + "|" + removed + "|" + extras + "|"
                + (note == null ? "" : note) + "|" + (request.expectedCartVersion() == null ? ""
                : request.expectedCartVersion());
    }

    public CartResponse updateQuantity(GuestSessionContext context, UUID cartItemId,
                                       UpdateCartItemQuantityRequest request) {
        requireActiveSession(context);
        requireQuantityWithinLimit(request.quantity());

        writeTx.executeWithoutResult(status -> {
            Cart cart = lockExistingCart(context).orElseThrow(CartService::itemNotFound);
            CartItem item = cart.findItem(cartItemId).orElseThrow(CartService::itemNotFound);
            requireExpectedVersion(cart, request.expectedCartVersion());
            if (item.getQuantity() != request.quantity()) {
                Instant now = clock.instant();
                item.changeQuantity(request.quantity(), now);
                cart.touch(now);
                recordActivity(context, cart, Activity.ITEM_QUANTITY_CHANGED, now);
            }
        });
        return view(context);
    }

    public CartResponse removeItem(GuestSessionContext context, UUID cartItemId, Long expectedCartVersion) {
        requireActiveSession(context);

        writeTx.executeWithoutResult(status -> {
            Cart cart = lockExistingCart(context).orElseThrow(CartService::itemNotFound);
            CartItem item = cart.findItem(cartItemId).orElseThrow(CartService::itemNotFound);
            requireExpectedVersion(cart, expectedCartVersion);
            cart.removeItem(item);
            Instant now = clock.instant();
            cart.touch(now);
            recordActivity(context, cart, Activity.ITEM_REMOVED, now);
        });
        return view(context);
    }

    /** Idempotent: clearing an already empty (or missing) cart succeeds without changing anything. */
    public CartResponse clear(GuestSessionContext context, Long expectedCartVersion) {
        requireActiveSession(context);

        writeTx.executeWithoutResult(status ->
                lockExistingCart(context).filter(cart -> !cart.getItems().isEmpty()).ifPresent(cart -> {
                    requireExpectedVersion(cart, expectedCartVersion);
                    cart.clearItems();
                    Instant now = clock.instant();
                    cart.touch(now);
                    recordActivity(context, cart, Activity.CART_CLEARED, now);
                }));
        return view(context);
    }

    /**
     * Announces a successful cart change to the QR Service in the same transaction as the change (ADR-QR-001 K6): the
     * event exists if and only if the change was committed. The aggregate version is the one the cart has after this
     * transaction commits.
     */
    private void recordActivity(GuestSessionContext context, Cart cart, Activity activity, Instant now) {
        UUID eventId = UUID.randomUUID();
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        CartActivityRecordedEvent event = new CartActivityRecordedEvent(eventId, CartActivityRecordedEvent.TYPE,
                CartActivityRecordedEvent.SCHEMA_VERSION, now, correlationId, context.restaurantId(),
                context.tableSessionId(), context.guestSessionId(), cart.getId(), activity);
        outbox.write(eventId, context.restaurantId(), "CART", cart.getId(), cart.getVersion() + 1, event.eventType(),
                event.schemaVersion(), correlationId, event, now, List.of(EventDestination.QR));
    }

    private CartResponse view(GuestSessionContext context) {
        Optional<Cart> cart = readTx.execute(status ->
                carts.findActiveWithItems(context.restaurantId(), context.guestSessionId()));
        Map<UUID, ProductSnapshot> products = cart.filter(found -> !found.getItems().isEmpty())
                .map(found -> catalog.findProducts(context.restaurantId(), found.getItems().stream()
                        .map(CartItem::getProductId).collect(Collectors.toSet())))
                .orElse(Map.of());
        return mapper.toResponse(context, cart, products);
    }

    private Cart lockOrCreateCart(GuestSessionContext context) {
        carts.insertActiveIfAbsent(UUID.randomUUID(), context.restaurantId(), context.tableId(),
                context.tableSessionId(), context.guestSessionId(), clock.instant());
        return lockExistingCart(context).orElseThrow(() ->
                new BusinessException(ErrorCode.CONFLICT, "The cart could not be opened, please retry"));
    }

    private Optional<Cart> lockExistingCart(GuestSessionContext context) {
        return carts.findActiveForUpdate(context.restaurantId(), context.guestSessionId());
    }

    private static void requireActiveSession(GuestSessionContext context) {
        if (!context.isActive()) {
            throw new BusinessException(ErrorCode.TABLE_SESSION_NOT_ACTIVE,
                    "The table session is no longer active; the cart cannot be changed");
        }
    }

    private void requireQuantityWithinLimit(int quantity) {
        if (quantity < 1 || quantity > limits.maxItemQuantity()) {
            throw new BusinessException(ErrorCode.QUANTITY_LIMIT_EXCEEDED,
                    "Quantity must be between 1 and " + limits.maxItemQuantity(),
                    Map.of("maxQuantity", limits.maxItemQuantity()));
        }
    }

    private static void requireExpectedVersion(Cart cart, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != cart.getVersion()) {
            throw new BusinessException(ErrorCode.CART_VERSION_CONFLICT,
                    "The cart was changed in the meantime; reload it and try again",
                    Map.of("currentVersion", cart.getVersion()));
        }
    }

    private static BusinessException itemNotFound() {
        return new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND, "The cart item does not exist");
    }
}
