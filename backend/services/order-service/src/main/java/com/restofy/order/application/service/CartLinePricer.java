package com.restofy.order.application.service;

import com.restofy.order.application.port.ProductSnapshot;
import com.restofy.order.application.port.ProductSnapshot.OptionType;
import com.restofy.order.application.port.ProductSnapshot.ProductOption;
import com.restofy.order.domain.entity.CartItem;
import com.restofy.order.domain.entity.CartItemOption;
import com.restofy.order.domain.enums.SelectionAction;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.domain.pricing.PriceCalculator;
import com.restofy.order.domain.pricing.PriceCalculator.LinePrice;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The one place that prices a cart line from the live catalog. The cart view shows the result as an estimate and
 * checkout turns the very same result into the binding order snapshot, so the two can never disagree.
 */
@Component
public class CartLinePricer {

    /** Catalog name/price of a selected ingredient at pricing time. */
    public record PricedOption(UUID optionId, SelectionAction action, int quantity, String name,
                               long unitPriceDeltaMinor) {
    }

    public record PricedLine(LinePrice price, ProductSnapshot product, List<PricedOption> options) {
    }

    /**
     * One price component of a cart line that differs from what the cart last validated. {@code component} is
     * PRODUCT_PRICE (base price, minor units), EXTRA_PRICE (one extra's unit price, minor units, {@code optionId} set),
     * TAX_RATE (basis points) or PRICE_VERSION (the catalog's opaque version; {@code name} carries the new one and the
     * numbers are null). {@code previous} and {@code current} are null when unknown.
     */
    public record PriceChange(UUID cartItemId, UUID productId, String component, UUID optionId, String name,
                              Long previous, Long current) {
    }

    /** Exactly one of {@code line} / {@code failure} is set. */
    public record Evaluation(PricedLine line, ErrorCode failure, String reason) {

        static Evaluation priced(PricedLine line) {
            return new Evaluation(line, null, null);
        }

        static Evaluation failed(ErrorCode failure, String reason) {
            return new Evaluation(null, failure, reason);
        }
    }

    /**
     * True when the live catalog differs from what the cart last validated for this line in ANY price component:
     * unit price (base + extras), each extra's unit price or quantity, the VAT rate, or the catalog's price version.
     * Comparing components, not just the total, is what catches changes that cancel each other out (product A dearer,
     * product B cheaper by the same amount) and VAT-only changes.
     */
    public boolean differsFromSnapshot(CartItem item, PricedLine live) {
        if (live.price().unitGrossMinor() != item.getUnitGrossMinorSnapshot()) {
            return true;
        }
        if (!Objects.equals(live.product().taxRateBps(), item.getTaxRateBpsSnapshot())) {
            return true;
        }
        if (!Objects.equals(live.product().priceVersion(), item.getPriceVersionSnapshot())) {
            return true;
        }
        for (CartItemOption stored : item.getOptions()) {
            boolean unchanged = live.options().stream().anyMatch(o -> o.optionId().equals(stored.getOptionId())
                    && o.action() == stored.getAction() && o.quantity() == stored.getQuantity()
                    && o.unitPriceDeltaMinor() == stored.getUnitPriceDeltaMinorSnapshot());
            if (!unchanged) {
                return true;
            }
        }
        return false;
    }

    /**
     * Names the components that make a line differ from its stored snapshot, per product and per extra, so a guest (and
     * a client) can see WHAT changed and not only that the total moved. Empty when {@link #differsFromSnapshot} is false.
     */
    public List<PriceChange> describeChanges(CartItem item, PricedLine live) {
        List<PriceChange> changes = new ArrayList<>();
        long storedExtras = 0L;
        for (CartItemOption stored : item.getOptions()) {
            if (stored.getAction() == SelectionAction.ADD) {
                storedExtras += stored.getUnitPriceDeltaMinorSnapshot() * stored.getQuantity();
            }
        }
        long previousBase = item.getUnitGrossMinorSnapshot() - storedExtras;
        long currentBase = live.product().basePriceGrossMinor();
        if (previousBase != currentBase) {
            changes.add(new PriceChange(item.getId(), item.getProductId(), "PRODUCT_PRICE", null,
                    live.product().name(), previousBase, currentBase));
        }
        for (CartItemOption stored : item.getOptions()) {
            if (stored.getAction() != SelectionAction.ADD) {
                continue;
            }
            live.options().stream().filter(o -> o.optionId().equals(stored.getOptionId())
                            && o.unitPriceDeltaMinor() != stored.getUnitPriceDeltaMinorSnapshot()).findFirst()
                    .ifPresent(o -> changes.add(new PriceChange(item.getId(), item.getProductId(), "EXTRA_PRICE",
                            o.optionId(), o.name(), stored.getUnitPriceDeltaMinorSnapshot(), o.unitPriceDeltaMinor())));
        }
        if (!Objects.equals(live.product().taxRateBps(), item.getTaxRateBpsSnapshot())) {
            changes.add(new PriceChange(item.getId(), item.getProductId(), "TAX_RATE", null, live.product().name(),
                    item.getTaxRateBpsSnapshot() == null ? null : item.getTaxRateBpsSnapshot().longValue(),
                    live.product().taxRateBps() == null ? null : live.product().taxRateBps().longValue()));
        }
        if (!Objects.equals(live.product().priceVersion(), item.getPriceVersionSnapshot())) {
            changes.add(new PriceChange(item.getId(), item.getProductId(), "PRICE_VERSION", null,
                    live.product().priceVersion(), null, null));
        }
        return changes;
    }

    public Evaluation evaluate(String cartCurrency, CartItem item, ProductSnapshot product) {
        if (product == null) {
            return Evaluation.failed(ErrorCode.PRODUCT_UNAVAILABLE, "The product is no longer on the menu");
        }
        if (!product.sellable()) {
            return Evaluation.failed(ErrorCode.PRODUCT_UNAVAILABLE, "The product is currently not available");
        }
        if (!product.currency().equals(cartCurrency)) {
            return Evaluation.failed(ErrorCode.CURRENCY_MISMATCH, "The product currency changed");
        }
        Map<UUID, ProductOption> catalogOptions = new HashMap<>();
        product.options().forEach(option -> catalogOptions.put(option.optionId(), option));
        try {
            long extrasDelta = 0L;
            List<PricedOption> options = new ArrayList<>();
            for (CartItemOption chosen : item.getOptions()) {
                ProductOption option = catalogOptions.get(chosen.getOptionId());
                if (option == null) {
                    return Evaluation.failed(ErrorCode.PRODUCT_UNAVAILABLE, "A selected ingredient no longer exists");
                }
                if (chosen.getAction() == SelectionAction.REMOVE) {
                    if (!option.removable()) {
                        return Evaluation.failed(ErrorCode.PRODUCT_UNAVAILABLE,
                                "A selected ingredient can no longer be removed");
                    }
                    options.add(new PricedOption(option.optionId(), SelectionAction.REMOVE, 1, option.name(), 0L));
                } else {
                    if (option.type() != OptionType.ADD || !option.active()
                            || chosen.getQuantity() > option.maxQuantity()) {
                        return Evaluation.failed(ErrorCode.PRODUCT_UNAVAILABLE,
                                "A selected extra is no longer available");
                    }
                    extrasDelta = Math.addExact(extrasDelta,
                            Math.multiplyExact(option.priceDeltaMinor(), (long) chosen.getQuantity()));
                    options.add(new PricedOption(option.optionId(), SelectionAction.ADD, chosen.getQuantity(),
                            option.name(), option.priceDeltaMinor()));
                }
            }
            long unitGross = PriceCalculator.unitGross(product.basePriceGrossMinor(), extrasDelta);
            LinePrice price = PriceCalculator.priceLine(unitGross, item.getQuantity(), product.taxRateBps());
            return Evaluation.priced(new PricedLine(price, product, List.copyOf(options)));
        } catch (ArithmeticException _) {
            return Evaluation.failed(ErrorCode.VALIDATION_ERROR, "The line amount exceeds the allowed limit");
        }
    }
}
