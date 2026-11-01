package com.restofy.order.application.service;

import com.restofy.order.application.service.CartLinePricer.PricedLine;
import com.restofy.order.application.service.CartLinePricer.PricedOption;
import com.restofy.order.domain.entity.Cart;
import com.restofy.order.domain.entity.CartItem;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A server-computed digest of everything that decides what a cart costs RIGHT NOW: the cart and its version, and for
 * every line the quantity, the live unit and line price, the VAT rate, the catalog price version and each selected
 * extra with its live price. The cart view returns it and checkout recomputes it from the live catalog.
 *
 * <p>It is not a secret and not an authority. A client can only repeat a value it was given; the server never trusts it,
 * it recomputes the digest and compares. A match proves the client reviewed exactly these prices for exactly this cart
 * version (so an approval cannot be reused after the cart or any price changed); it never replaces authentication, the
 * active-session check, the cart version check, the total check or the checkout transaction.
 */
public final class PriceFingerprint {

    /** One cart line with its live pricing; {@code priced} is null while the line cannot be priced (unavailable). */
    public record Line(CartItem item, PricedLine priced) {
    }

    private PriceFingerprint() {
    }

    public static String of(Cart cart, List<Line> lines) {
        return of(cart.getId(), cart.getVersion(), cart.getCurrency(), lines);
    }

    /** For a cart whose version is about to change: the same live prices bound to the given version. */
    public static String of(UUID cartId, long version, String currency, List<Line> lines) {
        StringBuilder canonical = new StringBuilder("pf1|").append(cartId).append('|').append(version)
                .append('|').append(currency);
        lines.stream().sorted(Comparator.comparing((Line l) -> l.item().getCreatedAt())
                .thenComparing(l -> l.item().getId())).forEach(line -> append(canonical, line));
        return IdempotencyKeys.sha256(canonical.toString());
    }

    private static void append(StringBuilder canonical, Line line) {
        CartItem item = line.item();
        canonical.append("|L:").append(item.getId()).append(':').append(item.getProductId()).append(':')
                .append(item.getQuantity());
        PricedLine priced = line.priced();
        if (priced == null) {
            canonical.append(":UNAVAILABLE");
            return;
        }
        canonical.append(':').append(priced.price().unitGrossMinor()).append(':').append(priced.price().lineGrossMinor())
                .append(':').append(priced.product().taxRateBps()).append(':').append(priced.product().priceVersion());
        priced.options().stream().sorted(Comparator.comparing(PricedOption::optionId)
                        .thenComparing(PricedOption::action))
                .forEach(o -> canonical.append(":O:").append(o.optionId()).append('/').append(o.action()).append('/')
                        .append(o.quantity()).append('/').append(o.unitPriceDeltaMinor()));
    }
}
