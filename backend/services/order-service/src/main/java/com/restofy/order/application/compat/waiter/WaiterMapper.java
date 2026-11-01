package com.restofy.order.application.compat.waiter;

import com.restofy.order.application.compat.waiter.WaiterCompat.ReasonAuthor;
import com.restofy.order.application.compat.waiter.WaiterCompat.UnrepresentableCancellation;
import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterCancellation;
import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterCancellationSource;
import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterCancellationStatus;
import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterCancellations;
import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterCancelledItem;
import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterItem;
import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterOption;
import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterOrderMoney;
import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterReason;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.entity.OrderItem;
import com.restofy.order.domain.entity.OrderItemOption;
import com.restofy.order.domain.pricing.MoneyConverter;
import com.restofy.order.infrastructure.persistence.CancellationStore.ItemRow;
import com.restofy.order.infrastructure.persistence.CancellationStore.ReasonRow;
import com.restofy.order.infrastructure.persistence.CancellationStore.Row;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pure translation of the Order Service's model into the Waiter's draft shapes (see {@link WaiterCompat}). No I/O, no
 * guessing: a value that has no faithful counterpart is reported as unrepresentable instead of being mapped to the
 * nearest-looking one.
 *
 * <p><b>Actors</b> (the requester of a cancellation): GUEST becomes CUSTOMER; STAFF becomes WAITER only when the verified
 * role really is WAITER (an ADMIN has no counterpart in the Waiter's model and is reported, never shown as a waiter);
 * SERVICE becomes KITCHEN only for the verified Kitchen service (id and role KITCHEN). {@code reported_staff_id} is the
 * staff member a service CLAIMED to act for: it is never the requester and is not used here at all.
 *
 * <p><b>Status</b>: REQUESTED and REJECTED are the same in both models. APPLIED becomes APPROVED. In the Order Service a
 * cancellation is decided and applied in one transaction (and the database keeps the cancelled quantities equal to the
 * APPLIED cancellations), so there is no approved-but-not-yet-applied state: every Order cancellation that is approved IS
 * applied. The Waiter's draft has exactly these three states, never treats APPROVED differently from a final accepted
 * decision, and derives nothing from it; so APPLIED is APPROVED. (Whether the Waiter intends APPROVED as something weaker
 * than applied is for the Waiter team to confirm.)
 *
 * <p><b>Money</b>: exact decimal of the currency's own scale ({@link MoneyConverter#toMajor}).
 */
public final class WaiterMapper {

    private WaiterMapper() {
    }

    // ---- money ------------------------------------------------------------------------------------------------------

    public static WaiterOrderMoney money(OrderFinance.Breakdown finance) {
        String currency = finance.currency();
        return new WaiterOrderMoney(currency, MoneyConverter.toMajor(finance.originalGrossMinor(), currency),
                MoneyConverter.toMajor(finance.cancelledGrossMinor(), currency),
                MoneyConverter.toMajor(finance.netAfterCancellationsMinor(), currency));
    }

    public static List<WaiterItem> items(Order order) {
        String currency = order.getCurrency();
        return order.getItems().stream().sorted(Comparator.comparingInt(OrderItem::getLineNo))
                .map(item -> new WaiterItem(item.getId(), item.getProductId(), item.getProductNameSnapshot(),
                        item.getOrderedQuantity(), item.getCancelledQuantity(),
                        MoneyConverter.toMajor(item.getUnitGrossMinor(), currency),
                        MoneyConverter.toMajor(item.getLineGrossMinor(), currency), item.getNote(),
                        item.getOptions().stream().sorted(Comparator.comparing(OrderItemOption::getNameSnapshot)
                                        .thenComparing(OrderItemOption::getOptionId))
                                .map(option -> new WaiterOption(option.getNameSnapshot(), option.getAction().name(),
                                        option.getQuantity(), MoneyConverter.toMajor(option.getUnitPriceDeltaMinor(),
                                        currency))).toList()))
                .toList();
    }

    // ---- cancellations ----------------------------------------------------------------------------------------------

    /** A cancellation row with its lines and its reasons. */
    public record CancellationData(Row row, List<ItemRow> items, List<ReasonRow> reasons) {
    }

    public static WaiterCancellations cancellations(List<CancellationData> data) {
        List<WaiterCancellation> mapped = new ArrayList<>();
        List<UnrepresentableCancellation> unrepresentable = new ArrayList<>();
        for (CancellationData each : data) {
            Row row = each.row();
            Optional<WaiterCancellationSource> source = source(row.requestedByType(), row.requestedByRole(),
                    row.requestedById());
            Optional<WaiterCancellationStatus> status = status(row.status());
            if (source.isEmpty()) {
                unrepresentable.add(new UnrepresentableCancellation(row.id(), "REQUESTER_HAS_NO_WAITER_EQUIVALENT",
                        row.requestedByType(), row.requestedByRole()));
            } else if (status.isEmpty()) {
                unrepresentable.add(new UnrepresentableCancellation(row.id(), "STATUS_HAS_NO_WAITER_EQUIVALENT",
                        row.requestedByType(), row.requestedByRole()));
            } else {
                mapped.add(new WaiterCancellation(row.id(), source.get(), status.get(), row.requestedAt(), row.decidedAt(),
                        each.items().stream().map(item -> new WaiterCancelledItem(item.orderItemId(), item.quantity()))
                                .toList(),
                        each.reasons().stream().map(WaiterMapper::reason).toList()));
            }
        }
        return new WaiterCancellations(List.copyOf(mapped), List.copyOf(unrepresentable));
    }

    /** Empty when the requester has no faithful Waiter counterpart. */
    public static Optional<WaiterCancellationSource> source(String type, String role, String id) {
        if (type == null) {
            return Optional.empty();
        }
        return switch (type) {
            case "GUEST" -> Optional.of(WaiterCancellationSource.CUSTOMER);
            case "STAFF" -> "WAITER".equals(role) ? Optional.of(WaiterCancellationSource.WAITER) : Optional.empty();
            case "SERVICE" -> "KITCHEN".equals(role) && "KITCHEN".equals(id)
                    ? Optional.of(WaiterCancellationSource.KITCHEN) : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** Empty for a status the Waiter has no word for. */
    public static Optional<WaiterCancellationStatus> status(String status) {
        if (status == null) {
            return Optional.empty();
        }
        return switch (status) {
            case "REQUESTED" -> Optional.of(WaiterCancellationStatus.REQUESTED);
            case "REJECTED" -> Optional.of(WaiterCancellationStatus.REJECTED);
            case "APPLIED" -> Optional.of(WaiterCancellationStatus.APPROVED);
            default -> Optional.empty();
        };
    }

    private static WaiterReason reason(ReasonRow reason) {
        String role = "GUEST".equals(reason.addedByType()) ? null : reason.addedByRole();
        return new WaiterReason(reason.code(), reason.text(), new ReasonAuthor(reason.addedByType(), role));
    }
}
