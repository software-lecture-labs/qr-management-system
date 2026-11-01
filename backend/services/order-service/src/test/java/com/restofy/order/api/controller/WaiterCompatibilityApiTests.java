package com.restofy.order.api.controller;

import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterCancellation;
import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterCancellationSource;
import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterCancellationStatus;
import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterItem;
import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterOrderCompat;
import com.restofy.order.application.compat.waiter.WaiterCompatibilityService;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterators;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests (Order side only, against the Order Service's own database and flows; the Waiter Service is not involved)
 * for the Waiter-shaped money and cancellation projection: the figures it derives from real orders, and that the existing
 * Order API keeps its minor-unit contract.
 */
class WaiterCompatibilityApiTests extends AbstractCancellationApiTest {

    private static final String WAITER = staff(DevFixtures.STAFF_WAITER_A);
    private static final String ADMIN = staff(DevFixtures.STAFF_ADMIN_A);

    @Autowired
    private WaiterCompatibilityService compat;

    private WaiterOrderCompat projection(UUID orderId) {
        return compat.forOrder(DevFixtures.RESTAURANT_A, orderId).orElseThrow();
    }

    private static String plain(BigDecimal value) {
        return value.toPlainString();
    }

    private static WaiterItem line(WaiterOrderCompat compat, String name) {
        return compat.items().stream().filter(item -> item.productName().equals(name)).findFirst().orElseThrow();
    }

    private static WaiterCancellation only(WaiterOrderCompat compat) {
        assertEquals(1, compat.cancellations().cancellations().size());
        return compat.cancellations().cancellations().get(0);
    }

    private UUID kitchenAsks(UUID orderId, List<String> codes, UUID claimedStaff) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("eventId", UUID.randomUUID());
        body.put("restaurantId", DevFixtures.RESTAURANT_A);
        body.put("orderId", orderId);
        body.put("cancelAll", true);
        body.put("reasonCodes", codes);
        if (claimedStaff != null) {
            body.put("requestedByStaffId", claimedStaff);
        }
        mvc.perform(post(KITCHEN_CANCELLATION_EVENTS + "-request").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(body)).header("Authorization", service(KITCHEN_CREDENTIAL)))
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM order_cancellations WHERE order_id = ?", UUID.class, orderId);
    }

    // ---- money of a real order ----------------------------------------------------------------------------------------

    @Test
    void aFreshOrderIsProjectedWithItsSnapshotPricesAndNothingCancelled() throws Exception {
        UUID orderId = placeMixedOrder(A1);

        WaiterOrderCompat result = projection(orderId);

        assertEquals("TRY", result.money().currency());
        assertEquals("520.00", plain(result.money().originalGrossTotal()));
        assertEquals("0.00", plain(result.money().cancelledGrossTotal()));
        assertEquals("520.00", plain(result.money().payableTotal()));
        assertEquals(2, result.items().size());
        WaiterItem salad = line(result, "Dev Salad");
        assertEquals(3, salad.quantity());
        assertEquals(0, salad.cancelledQuantity());
        assertEquals("90.00", plain(salad.unitGrossPrice()));
        assertEquals("270.00", plain(salad.lineGrossTotal()));
        assertEquals("250.00", plain(line(result, "Dev Burger").unitGrossPrice()));
        assertTrue(result.cancellations().cancellations().isEmpty());
        assertTrue(result.cancellations().complete());
        // the same order, in the Order Service's own terms, is untouched integer minor units
        assertEquals(52_000L, result.finance().originalGrossMinor());
        assertEquals(52_000L, result.finance().outstandingDueMinor());
    }

    @Test
    void thePaidExtrasAreInsideTheUnitPriceAndTheOptionsAreOnlyInformation() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(DevFixtures.OPT_ONION),
                List.of(extra(DevFixtures.OPT_CHEESE, 1)))).andExpect(status().isOk());
        String body = placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        UUID orderId = UUID.fromString(json.readTree(body).path("orderId").asString());

        WaiterOrderCompat result = projection(orderId);

        WaiterItem burger = line(result, "Dev Burger");
        assertEquals("275.00", plain(burger.unitGrossPrice()), "250.00 base + 25.00 extra cheese");
        assertEquals("275.00", plain(burger.lineGrossTotal()));
        assertEquals("275.00", plain(result.money().originalGrossTotal()));
        assertEquals(2, burger.options().size());
        var cheese = burger.options().stream().filter(o -> o.action().equals("ADD")).findFirst().orElseThrow();
        var onion = burger.options().stream().filter(o -> o.action().equals("REMOVE")).findFirst().orElseThrow();
        assertEquals("25.00", plain(cheese.unitPriceDelta()));
        assertEquals("0.00", plain(onion.unitPriceDelta()));
    }

    @Test
    void aFullCancellationTakesEverythingOffAndTheMoneyAddsUp() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        cancelAll(guest(A1), orderId).andExpect(status().isCreated());

        WaiterOrderCompat result = projection(orderId);

        assertEquals("520.00", plain(result.money().originalGrossTotal()));
        assertEquals("520.00", plain(result.money().cancelledGrossTotal()));
        assertEquals("0.00", plain(result.money().payableTotal()));
        result.items().forEach(item -> assertEquals(item.quantity(), item.cancelledQuantity()));
        WaiterCancellation cancellation = only(result);
        assertEquals(WaiterCancellationSource.CUSTOMER, cancellation.source());
        assertEquals(WaiterCancellationStatus.APPROVED, cancellation.status());
        assertEquals(4, cancellation.items().stream().mapToInt(i -> i.quantity()).sum());
    }

    @Test
    void partialCancellationsOfTheSameLineAreCountedOnceEach() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        UUID salad = lineOf(orderId, DevFixtures.SALAD);
        cancel(WAITER, orderId, newKey(), cancelItems(salad, 1)).andExpect(status().isCreated());
        cancel(guest(A1), orderId, newKey(), cancelItems(salad, 1)).andExpect(status().isCreated());

        WaiterOrderCompat result = projection(orderId);

        assertEquals("180.00", plain(result.money().cancelledGrossTotal()));
        assertEquals("340.00", plain(result.money().payableTotal()));
        assertEquals(2, line(result, "Dev Salad").cancelledQuantity());
        assertEquals(0, line(result, "Dev Burger").cancelledQuantity());
        assertEquals("270.00", plain(line(result, "Dev Salad").lineGrossTotal()), "the snapshot never shrinks");
        assertEquals(Set.of(WaiterCancellationSource.WAITER, WaiterCancellationSource.CUSTOMER),
                result.cancellations().cancellations().stream().map(WaiterCancellation::source).collect(Collectors.toSet()));
        // per line, the units of the approved cancellations are exactly the units the line shows as cancelled
        int approvedUnits = result.cancellations().cancellations().stream()
                .filter(c -> c.status() == WaiterCancellationStatus.APPROVED)
                .flatMap(c -> c.items().stream()).filter(i -> i.orderItemId().equals(salad)).mapToInt(i -> i.quantity()).sum();
        assertEquals(line(result, "Dev Salad").cancelledQuantity(), approvedUnits);
    }

    @Test
    void aWaitingOrRejectedRequestCancelsNothingAndAnApprovedOneIsCountedOnlyOnce() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        advanceTo(orderId, "PREPARING");
        UUID salad = lineOf(orderId, DevFixtures.SALAD);
        UUID first = requestIdOf(cancel(guest(A1), orderId, newKey(), cancelItems(salad, 2)).andExpect(status().isCreated()));

        WaiterOrderCompat waiting = projection(orderId);
        assertEquals(WaiterCancellationStatus.REQUESTED, only(waiting).status());
        assertEquals(null, only(waiting).decidedAt());
        assertEquals("0.00", plain(waiting.money().cancelledGrossTotal()));
        assertEquals("520.00", plain(waiting.money().payableTotal()));
        assertEquals(0, line(waiting, "Dev Salad").cancelledQuantity());
        assertEquals(2, only(waiting).items().get(0).quantity(), "the request still names what it asks for");

        kitchenDecides(orderId, first, "REJECTED", "KITCHEN_DECLINED").andExpect(status().isOk());
        WaiterOrderCompat rejected = projection(orderId);
        assertEquals(WaiterCancellationStatus.REJECTED, only(rejected).status());
        Assertions.assertNotNull(only(rejected).decidedAt());
        assertEquals("0.00", plain(rejected.money().cancelledGrossTotal()));
        assertEquals(0, line(rejected, "Dev Salad").cancelledQuantity());

        UUID second = requestIdOf(cancel(guest(A1), orderId, newKey(), cancelItems(salad, 2)).andExpect(status().isCreated()));
        kitchenDecides(orderId, second, "APPROVED", null).andExpect(status().isOk());
        WaiterOrderCompat approved = projection(orderId);
        assertEquals("180.00", plain(approved.money().cancelledGrossTotal()), "once, not once per request");
        assertEquals("340.00", plain(approved.money().payableTotal()));
        assertEquals(List.of(WaiterCancellationStatus.REJECTED, WaiterCancellationStatus.APPROVED),
                approved.cancellations().cancellations().stream().map(WaiterCancellation::status).toList());
    }

    @Test
    void anOrderThatIsPaidIsStillWorthItsTotalAndOwesNothing() throws Exception {
        UUID orderId = placeAndPayMixedOrder();

        WaiterOrderCompat paid = projection(orderId);
        assertEquals("520.00", plain(paid.money().payableTotal()), "worth its total even though it is paid");
        assertEquals(0L, paid.finance().outstandingDueMinor(), "and owes nothing");
        assertEquals(52_000L, paid.finance().collectedMinor());

        cancel(WAITER, orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.BURGER), 1)).andExpect(status().isCreated());
        WaiterOrderCompat cancelled = projection(orderId);
        assertEquals("250.00", plain(cancelled.money().cancelledGrossTotal()));
        assertEquals("270.00", plain(cancelled.money().payableTotal()));
        assertEquals(0L, cancelled.finance().outstandingDueMinor());
        assertEquals(52_000L, cancelled.finance().collectedMinor());
        assertEquals(25_000L, cancelled.finance().refundRequiredMinor(), "required...");
        assertEquals(0L, cancelled.finance().refundedMinor(), "...is not made");
        assertNotEquals(cancelled.money().payableTotal().movePointRight(2).longValueExact(),
                cancelled.finance().outstandingDueMinor(), "payable and debt are different figures");
    }

    @Test
    void anUnpaidOrderOwesExactlyWhatItIsWorthAfterItsCancellations() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        cancel(guest(A1), orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.SALAD), 2)).andExpect(status().isCreated());

        WaiterOrderCompat result = projection(orderId);

        assertEquals(result.finance().netAfterCancellationsMinor(), result.finance().outstandingDueMinor());
        assertEquals(0L, result.finance().collectedMinor());
    }

    // ---- who asked ----------------------------------------------------------------------------------------------------

    @Test
    void theKitchensRequestIsKitchenAndTheStaffMemberItClaimedIsNowhereInTheProjection() throws Exception {
        UUID claimed = DevFixtures.USER_ADMIN_A;
        UUID orderId = placeSaladOrder(A1);
        kitchenAsks(orderId, List.of("ITEM_UNAVAILABLE"), claimed);

        WaiterOrderCompat result = projection(orderId);

        WaiterCancellation cancellation = only(result);
        assertEquals(WaiterCancellationSource.KITCHEN, cancellation.source());
        assertEquals(WaiterCancellationStatus.APPROVED, cancellation.status());
        assertEquals("SERVICE", cancellation.reasons().get(0).author().kind());
        assertEquals("KITCHEN", cancellation.reasons().get(0).author().role());
        assertFalse(result.toString().contains(claimed.toString()), "a claim is not an actor");
    }

    @Test
    void anAdminsCancellationHasNoWaiterEquivalentAndIsReportedNotRelabelled() throws Exception {
        UUID byAdmin = placeSaladOrder(A1);
        cancelAll(ADMIN, byAdmin).andExpect(status().isCreated());
        UUID byWaiter = placeSaladOrder(A1);
        cancelAll(WAITER, byWaiter).andExpect(status().isCreated());

        WaiterOrderCompat admin = projection(byAdmin);
        assertFalse(admin.cancellations().complete());
        assertTrue(admin.cancellations().cancellations().isEmpty(), "not shown as a waiter's");
        assertEquals(1, admin.cancellations().unrepresentable().size());
        assertEquals("REQUESTER_HAS_NO_WAITER_EQUIVALENT", admin.cancellations().unrepresentable().get(0).reason());
        assertEquals("ADMIN", admin.cancellations().unrepresentable().get(0).requestedByRole());
        // the money is not affected by what the Waiter cannot name
        assertEquals("90.00", plain(admin.money().cancelledGrossTotal()));

        WaiterOrderCompat waiter = projection(byWaiter);
        assertTrue(waiter.cancellations().complete());
        assertEquals(WaiterCancellationSource.WAITER, only(waiter).source());
    }

    // ---- reasons ------------------------------------------------------------------------------------------------------

    @Test
    void severalReasonsAreAllKeptAndTheWaitersOneCodeShapeIsNotOfferedForThem() throws Exception {
        UUID many = placeSaladOrder(A1);
        cancel(guest(A1), many, newKey(), cancelAllWith(List.of("CHANGED_MIND", "WAIT_TOO_LONG"), "tired of waiting"))
                .andExpect(status().isCreated());
        UUID single = placeSaladOrder(A1);
        cancel(guest(A1), single, newKey(), cancelAllWith(List.of("ORDERED_BY_MISTAKE"), "wrong table"))
                .andExpect(status().isCreated());
        UUID none = placeSaladOrder(A1);
        cancelAll(guest(A1), none).andExpect(status().isCreated());

        WaiterCancellation severalReasons = only(projection(many));
        assertEquals(3, severalReasons.reasons().size());
        assertTrue(severalReasons.legacy().isEmpty(), "no first one, no last one, no summary");
        assertEquals(List.of("CHANGED_MIND", "WAIT_TOO_LONG"),
                severalReasons.reasons().stream().map(r -> r.code()).filter(c -> c != null).toList());

        var oneCodeOneText = only(projection(single)).legacy().orElseThrow();
        assertEquals("ORDERED_BY_MISTAKE", oneCodeOneText.reasonCode());
        assertEquals("wrong table", oneCodeOneText.reasonText());

        var noReason = only(projection(none)).legacy().orElseThrow();
        assertEquals(null, noReason.reasonCode());
        assertEquals(null, noReason.reasonText());
        assertTrue(only(projection(none)).reasons().isEmpty(), "a missing reason is not invented");
    }

    @Test
    void aReasonCompletedLaterKeepsItsAuthorAndTheRequesterDoesNotChange() throws Exception {
        UUID byWaiter = placeSaladOrder(A1);
        UUID waiterCancellation = requestIdOf(cancelAll(guest(A1), byWaiter).andExpect(status().isCreated()));
        mvc.perform(MockMvcRequestBuilders
                .patch("/api/v1/orders/" + byWaiter + "/cancellations/" + waiterCancellation + "/reason")
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("reasonCodes", List.of("WAIT_TOO_LONG"))))
                .header("Authorization", WAITER)).andExpect(status().isOk());
        UUID byAdmin = placeSaladOrder(A1);
        UUID adminCancellation = requestIdOf(cancelAll(guest(A1), byAdmin).andExpect(status().isCreated()));
        mvc.perform(MockMvcRequestBuilders
                .patch("/api/v1/orders/" + byAdmin + "/cancellations/" + adminCancellation + "/reason")
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("reasonText", "called the guest")))
                .header("Authorization", ADMIN)).andExpect(status().isOk());

        WaiterCancellation completedByWaiter = only(projection(byWaiter));
        assertEquals(WaiterCancellationSource.CUSTOMER, completedByWaiter.source(), "the customer asked");
        assertEquals("STAFF", completedByWaiter.reasons().get(0).author().kind());
        assertEquals("WAITER", completedByWaiter.reasons().get(0).author().role());
        assertEquals("WAIT_TOO_LONG", completedByWaiter.legacy().orElseThrow().reasonCode());

        WaiterCancellation completedByAdmin = only(projection(byAdmin));
        assertEquals(WaiterCancellationSource.CUSTOMER, completedByAdmin.source());
        assertEquals("ADMIN", completedByAdmin.reasons().get(0).author().role(), "the admin is not relabelled a waiter");
    }

    // ---- isolation, purity, and the Waiter's JSON ----------------------------------------------------------------------

    @Test
    void anotherRestaurantSeesNothingOfTheOrder() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        cancelAll(guest(A1), orderId).andExpect(status().isCreated());

        assertTrue(compat.forOrder(DevFixtures.RESTAURANT_B, orderId).isEmpty());
        assertTrue(compat.forOrder(DevFixtures.RESTAURANT_A, UUID.randomUUID()).isEmpty());
        assertTrue(compat.forOrder(DevFixtures.RESTAURANT_A, orderId).isPresent());
    }

    @Test
    void buildingTheProjectionChangesNothing() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        cancel(WAITER, orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.SALAD), 1)).andExpect(status().isCreated());
        Map<String, Object> before = Map.of("orders", jdbc.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class,
                orderId), "outbox", count("outbox_events"), "history", count("order_status_history"), "cancellations",
                count("order_cancellations"), "reasons", count("order_cancellation_reasons"), "items",
                count("order_cancellation_items"), "inbox", count("inbox_events"));

        projection(orderId);
        projection(orderId);

        assertEquals(before, Map.of("orders", jdbc.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class,
                orderId), "outbox", count("outbox_events"), "history", count("order_status_history"), "cancellations",
                count("order_cancellations"), "reasons", count("order_cancellation_reasons"), "items",
                count("order_cancellation_items"), "inbox", count("inbox_events")));
    }

    @Test
    void theProjectionSerialisesToExactlyTheFieldsTheWaitersDraftReadsWithDecimalNumbers() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        cancel(WAITER, orderId, newKey(), cancelAllWith(List.of("OTHER"), "closing early")).andExpect(status().isCreated());
        WaiterOrderCompat result = projection(orderId);

        JsonNode money = json.readTree(json.writeValueAsString(result.money()));
        assertEquals(Set.of("currency", "originalGrossTotal", "cancelledGrossTotal", "payableTotal"), names(money));
        assertTrue(money.path("originalGrossTotal").isNumber() && !money.path("originalGrossTotal").isString(),
                "a JSON number, not a string");
        // the text itself keeps the currency's scale: 520.00, never 520 or 5.2E+2
        String text = json.writeValueAsString(result.money());
        assertTrue(text.contains("\"originalGrossTotal\":520.00") && text.contains("\"cancelledGrossTotal\":520.00")
                && text.contains("\"payableTotal\":0.00"), text);

        JsonNode item = json.readTree(json.writeValueAsString(result.items().get(0)));
        assertEquals(Set.of("id", "productId", "productName", "quantity", "cancelledQuantity", "unitGrossPrice",
                "lineGrossTotal", "note", "options"), names(item));

        JsonNode legacy = json.readTree(json.writeValueAsString(only(result).legacy().orElseThrow()));
        assertEquals(Set.of("id", "source", "status", "reasonCode", "reasonText", "requestedAt", "decidedAt", "items"),
                names(legacy));
        assertEquals("WAITER", legacy.path("source").asString());
        assertEquals("APPROVED", legacy.path("status").asString());
        assertEquals(Set.of("orderItemId", "quantity"), names(legacy.path("items").get(0)));
    }

    private static Set<String> names(JsonNode node) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(node.propertyNames().iterator(), 0), false)
                .collect(Collectors.toSet());
    }

    // ---- the Order Service's own API is exactly what it was -------------------------------------------------------------

    @Test
    void theExistingOrderApiStillSpeaksIntegerMinorUnitsAndHasNoDecimalFields() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        cancel(WAITER, orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.SALAD), 1)).andExpect(status().isCreated());

        getWith(guest(A1), "/api/v1/orders/" + orderId).andExpect(status().isOk())
                .andExpect(jsonPath("$.amounts.grossMinor").value(52_000))
                .andExpect(jsonPath("$.currentDueMinor").value(43_000))
                .andExpect(jsonPath("$.cancelledGrossMinor").value(9_000))
                .andExpect(jsonPath("$.activeGrossMinor").value(43_000))
                .andExpect(jsonPath("$.items[0].unitGrossMinor").value(9_000))
                .andExpect(jsonPath("$.items[0].lineGrossMinor").value(27_000))
                .andExpect(jsonPath("$.originalGrossTotal").doesNotExist())
                .andExpect(jsonPath("$.cancelledGrossTotal").doesNotExist())
                .andExpect(jsonPath("$.payableTotal").doesNotExist())
                .andExpect(jsonPath("$.items[0].unitGrossPrice").doesNotExist());
        String raw = getWith(guest(A1), "/api/v1/orders/" + orderId).andReturn().getResponse().getContentAsString();
        assertFalse(raw.matches("(?s).*\"(grossMinor|currentDueMinor|unitGrossMinor|lineGrossMinor)\":[0-9]+\\.[0-9].*"),
                "no minor-unit field turned into a decimal: " + raw);

        getWith(guest(A1), "/api/v1/orders/" + orderId + "/cancellations").andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellations[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.cancellations[0].requestedBy.type").value("STAFF"))
                .andExpect(jsonPath("$.cancellations[0].amounts.cancelledGrossMinor").value(9_000))
                .andExpect(jsonPath("$.cancellations[0].amounts.newDueMinor").value(43_000));
        getWith(ADMIN, "/api/v1/admin/order-cancellations").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].amounts.cancelledGrossMinor").value(9_000))
                .andExpect(jsonPath("$.summary.totals[0].cancelledGrossMinor").value(9_000));
    }
}
