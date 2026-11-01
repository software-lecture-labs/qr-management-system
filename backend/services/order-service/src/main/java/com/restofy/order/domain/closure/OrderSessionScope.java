package com.restofy.order.domain.closure;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the orders of one table session mean for its lifecycle, in the QR Service's terms (ADR-QR-001 K6): who is
 * protected by an order, who was released by a safely completed cancellation and when, and whether the main session is
 * protected at all. Pure: no clock, no database, no client input.
 *
 * <ul>
 *   <li>A guest is protected as long as one of their orders is not safely cancelled. A guest who ordered nothing is
 *       not protected and is not part of any order scope.</li>
 *   <li>The main session is protected as long as any guest is protected. When every order is safely cancelled it is
 *       not, and {@code mainCancellationAt} says since when (the QR Service then counts a fresh idle window).</li>
 *   <li>Unknown money never releases anybody: an order whose cancellation is not safe stays protecting.</li>
 * </ul>
 */
public record OrderSessionScope(Knowledge knowledge, Set<UUID> protectedGuests,
                                Map<UUID, Instant> cancellationReleases, Instant mainCancellationAt) {

    public enum Knowledge {
        /** No order protects the session (none ever, or every one safely cancelled). */
        NO_ORDERS,
        /** An order is unfinished, unpaid, or its money is uncertain. */
        OPEN_WORK,
        /** Every real order is finished and settled: the session may be closed. */
        SETTLED
    }

    public OrderSessionScope {
        protectedGuests = Set.copyOf(protectedGuests);
        cancellationReleases = Map.copyOf(cancellationReleases);
    }

    public static OrderSessionScope of(List<OrderClosureFacts> orders) {
        if (orders.isEmpty()) {
            return new OrderSessionScope(Knowledge.NO_ORDERS, Set.of(), Map.of(), null);
        }
        Set<UUID> protectedGuests = new HashSet<>();
        Map<UUID, Instant> releases = new HashMap<>();
        for (OrderClosureFacts order : orders) {
            UUID guest = order.guestSessionId();
            if (guest == null || !order.safelyCancelled()) {
                if (guest != null) {
                    protectedGuests.add(guest);
                }
                continue;
            }
            releases.merge(guest, order.cancellationSafeAt(), (a, b) -> a.isAfter(b) ? a : b);
        }
        releases.keySet().removeAll(protectedGuests);
        boolean anyUnattributed = orders.stream().anyMatch(o -> o.guestSessionId() == null && !o.safelyCancelled());
        if (protectedGuests.isEmpty() && !anyUnattributed) {
            Instant latest = orders.stream().map(OrderClosureFacts::cancellationSafeAt)
                    .max(Comparator.naturalOrder()).orElse(null);
            return new OrderSessionScope(Knowledge.NO_ORDERS, Set.of(), releases, latest);
        }
        Knowledge knowledge = ClosureEligibility.evaluate(orders).eligible() ? Knowledge.SETTLED : Knowledge.OPEN_WORK;
        return new OrderSessionScope(knowledge, protectedGuests, releases, null);
    }

    /** True when an order keeps the main session (and so every guest's idle expiry as a whole) from expiring. */
    public boolean protectsMain() {
        return knowledge != Knowledge.NO_ORDERS;
    }
}
