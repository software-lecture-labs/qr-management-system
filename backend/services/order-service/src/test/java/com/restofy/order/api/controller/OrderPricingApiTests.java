package com.restofy.order.api.controller;

import com.restofy.order.application.port.ProductSnapshot;
import com.restofy.order.application.port.ProductSnapshot.OptionType;
import com.restofy.order.application.port.ProductSnapshot.ProductOption;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Server-side pricing, the immutable snapshot, and the client's confirmation of what it agreed to pay. */
class OrderPricingApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;

    private void republishBurger(long basePrice, long cheesePrice, int taxBps) {
        ProductSnapshot burger = DevFixtures.burgerSnapshot();
        List<ProductOption> options = burger.options().stream()
                .map(o -> o.optionId().equals(DevFixtures.OPT_CHEESE)
                        ? new ProductOption(o.optionId(), o.name(), OptionType.ADD, cheesePrice, 0, 2, true) : o)
                .toList();
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(burger.productId(), burger.name(), true, true,
                "TRY", basePrice, taxBps, options));
    }

    @Test
    void mixedVatManyUnitsAndExtrasAreTotalledFromRoundedLines() throws Exception {
        // burger: (25000 + 2 x 2500 + 4000) = 34000 x 3 = 102000 gross @10% -> net 92727 vat 9273
        addItem(A1, product(DevFixtures.BURGER, 3, List.of(DevFixtures.OPT_ONION, DevFixtures.OPT_PICKLE),
                List.of(extra(DevFixtures.OPT_CHEESE, 2), extra(DevFixtures.OPT_BACON, 1)))).andExpect(status().isOk());
        // salad: 9000 x 2 = 18000 gross @20% -> net 15000 vat 3000
        addItem(A1, product(DevFixtures.SALAD, 2)).andExpect(status().isOk());

        placeOrder(A1, newKey(), confirmedCheckout(A1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].unitGrossMinor").value(34_000))
                .andExpect(jsonPath("$.items[0].lineGrossMinor").value(102_000))
                .andExpect(jsonPath("$.items[0].lineNetMinor").value(92_727))
                .andExpect(jsonPath("$.items[0].lineVatMinor").value(9_273))
                .andExpect(jsonPath("$.items[0].taxRateBps").value(1_000))
                .andExpect(jsonPath("$.items[1].lineGrossMinor").value(18_000))
                .andExpect(jsonPath("$.items[1].lineNetMinor").value(15_000))
                .andExpect(jsonPath("$.items[1].lineVatMinor").value(3_000))
                .andExpect(jsonPath("$.amounts.grossMinor").value(120_000))
                .andExpect(jsonPath("$.amounts.netMinor").value(107_727))
                .andExpect(jsonPath("$.amounts.vatMinor").value(12_273));
    }

    @Test
    void storedLinesAndOrderAgreeAndNetPlusVatEqualsGrossEverywhere() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 7, List.of(), List.of(extra(DevFixtures.OPT_CHEESE, 1)))).andExpect(status().isOk());
        addItem(A1, product(DevFixtures.SALAD, 5)).andExpect(status().isOk());
        placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isCreated());

        var order = jdbc.queryForMap("SELECT initial_net_minor, initial_vat_minor, initial_gross_minor, current_due_minor FROM orders");
        var lines = jdbc.queryForMap("SELECT sum(line_net_minor) n, sum(line_vat_minor) v, sum(line_gross_minor) g FROM order_items");
        assertEquals(((Number) lines.get("n")).longValue(), ((Number) order.get("initial_net_minor")).longValue());
        assertEquals(((Number) lines.get("v")).longValue(), ((Number) order.get("initial_vat_minor")).longValue());
        assertEquals(((Number) lines.get("g")).longValue(), ((Number) order.get("initial_gross_minor")).longValue());
        assertEquals(((Number) order.get("initial_gross_minor")).longValue(),
                ((Number) order.get("initial_net_minor")).longValue() + ((Number) order.get("initial_vat_minor")).longValue());
        assertEquals(order.get("initial_gross_minor"), order.get("current_due_minor"), "nothing cancelled yet");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM order_items WHERE cancelled_quantity <> 0", Integer.class));
    }

    @Test
    void aZeroVatProductIsAllNet() throws Exception {
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(DevFixtures.SALAD, "Dev Salad", true, true, "TRY",
                9_000L, 0, List.of()));
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        placeOrder(A1, newKey(), confirmedCheckout(A1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amounts.netMinor").value(9_000))
                .andExpect(jsonPath("$.amounts.vatMinor").value(0));
    }

    @Test
    void halfUpRoundingIsAppliedPerLineAtCheckout() throws Exception {
        // 3 minor units at 100% VAT: net is exactly 1.5 -> 2, vat 1
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(DevFixtures.SALAD, "Dev Salad", true, true, "TRY",
                3L, 10_000, List.of()));
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        placeOrder(A1, newKey(), confirmedCheckout(A1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amounts.netMinor").value(2))
                .andExpect(jsonPath("$.amounts.vatMinor").value(1));
    }

    @Test
    void largeCatalogAmountsStayExact() throws Exception {
        long price = 1_000_000_000_000L; // the catalog cap: 10 billion in major units
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(DevFixtures.SALAD, "Dev Salad", true, true, "TRY",
                price, 1_000, List.of()));
        addItem(A1, product(DevFixtures.SALAD, 50)).andExpect(status().isOk());
        placeOrder(A1, newKey(), confirmedCheckout(A1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amounts.grossMinor").value(50 * price));
    }

    // ---- confirmation of the total ---------------------------------------------------------------------------------

    @Test
    void aPriceChangeAfterReviewingTheCartIsRejectedWithTheNewSummary() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 2)).andExpect(status().isOk());
        Map<String, Object> reviewed = confirmedCheckout(A1);   // guest saw 50000
        republishBurger(30_000L, 2_500L, 1_000);                // the menu changed afterwards

        placeOrder(A1, newKey(), reviewed)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.details.currentTotalMinor").value(60_000))
                .andExpect(jsonPath("$.details.cart.totals.grossMinor").value(60_000))
                .andExpect(jsonPath("$.details.cart.items[0].priceChanged").value(true))
                .andExpect(jsonPath("$.details.cart.items[0].unitGrossMinor").value(30_000))
                .andExpect(jsonPath("$.details.changedLines.length()").value(1))
                .andExpect(jsonPath("$.details.acknowledgementRequired").value(true))
                .andExpect(jsonPath("$.details.currentCartVersion").value(1));
        assertEquals(0, count("orders"));
        assertEquals(0, count("idempotency_records"), "a rejected confirmation must not burn the key");
        assertEquals(1L, cartVersion(), "and must not change the cart");
    }

    @Test
    void confirmingTheNewTotalOrdersAtTheNewPrice() throws Exception {
        // the guest reviews the cart, the price changes, the guest re-reads the cart and confirms the new total
        addItem(A1, product(DevFixtures.BURGER, 2)).andExpect(status().isOk());
        Map<String, Object> reviewed = confirmedCheckout(A1);
        republishBurger(30_000L, 2_500L, 1_000);
        String retryKey = newKey();
        placeOrder(A1, retryKey, reviewed).andExpect(status().isConflict());
        // re-reading the version and total and retrying blindly is NOT an acceptance of the new price
        placeOrder(A1, retryKey, confirmedCheckout(A1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"));
        assertEquals(0, count("orders"));

        // only the explicit acknowledgement of the prices the guest was shown orders at the new price
        placeOrder(A1, retryKey, acknowledgedCheckout(A1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amounts.grossMinor").value(60_000))
                .andExpect(jsonPath("$.items[0].unitGrossMinor").value(30_000));
    }

    @Test
    void anUnderstatedTotalCannotBuyACheaperOrder() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 2)).andExpect(status().isOk());
        Map<String, Object> real = confirmedCheckout(A1);
        for (long lie : new long[]{0, 1, 49_999, 50_001, Long.MAX_VALUE}) {
            placeOrder(A1, newKey(), Map.of("expectedCartVersion", real.get("expectedCartVersion"),
                    "expectedTotalMinor", lie))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                    .andExpect(jsonPath("$.details.currentTotalMinor").value(50_000));
        }
        assertEquals(0, count("orders"));
    }

    @Test
    void clientCannotSupplyPricesNamesOrTaxForTheOrder() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        Map<String, Object> real = confirmedCheckout(A1);
        for (String field : new String[]{"\"unitPrice\": 1", "\"amounts\": {\"grossMinor\": 1}",
                "\"taxRateBps\": 0", "\"items\": [{\"productId\": \"" + DevFixtures.BURGER + "\", \"price\": 1}]",
                "\"currency\": \"USD\""}) {
            mvc.perform(MockMvcRequestBuilders.post("/api/v1/orders")
                            .header("Authorization", "Bearer " + A1).header("Idempotency-Key", newKey())
                            .contentType("application/json")
                            .content("{\"expectedCartVersion\": " + real.get("expectedCartVersion")
                                    + ", \"expectedTotalMinor\": " + real.get("expectedTotalMinor") + ", " + field + "}"))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(0, count("orders"));
    }

    // ---- snapshot immutability ---------------------------------------------------------------------------------------

    @Test
    void laterCatalogChangesNeverTouchAPlacedOrder() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 2, List.of(DevFixtures.OPT_ONION),
                List.of(extra(DevFixtures.OPT_CHEESE, 1)))).andExpect(status().isOk());
        Map<String, Object> body = confirmedCheckout(A1);
        String key = newKey();
        JsonNode original = json.readTree(placeOrder(A1, key, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        var before = jdbc.queryForList("SELECT product_name_snapshot, unit_gross_minor, tax_rate_bps, line_gross_minor,"
                + " line_net_minor, line_vat_minor FROM order_items");
        var optionsBefore = jdbc.queryForList(
                "SELECT name_snapshot, unit_price_delta_minor FROM order_item_options ORDER BY name_snapshot");

        // price, tax, names and the whole product change, then the product is deleted
        republishBurger(99_900L, 9_900L, 2_000);
        ProductSnapshot burger = DevFixtures.burgerSnapshot();
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(burger.productId(), "Renamed Burger", true, true, "TRY",
                99_900L, 2_000, List.of(new ProductOption(DevFixtures.OPT_CHEESE, "Renamed cheese", OptionType.ADD,
                9_900L, 0, 2, true))));
        menu.remove(DevFixtures.RESTAURANT_A, DevFixtures.BURGER);

        assertEquals(before, jdbc.queryForList("SELECT product_name_snapshot, unit_gross_minor, tax_rate_bps,"
                + " line_gross_minor, line_net_minor, line_vat_minor FROM order_items"));
        assertEquals(optionsBefore, jdbc.queryForList("SELECT name_snapshot, unit_price_delta_minor FROM order_item_options"
                + " ORDER BY name_snapshot"));
        // and the order read back (via a replay) is exactly what was originally returned
        JsonNode replay = json.readTree(placeOrder(A1, key, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertEquals(original, replay);
        assertEquals("Dev Burger", replay.path("items").get(0).path("name").asString());
        assertEquals(50_000L + 5_000L, replay.path("amounts").path("grossMinor").asLong());
    }

    @Test
    void theOrderSnapshotUsesLiveCatalogNamesNotStaleCartValues() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(DevFixtures.OPT_ONION), List.of())).andExpect(status().isOk());
        ProductSnapshot burger = DevFixtures.burgerSnapshot();
        List<ProductOption> renamed = burger.options().stream()
                .map(o -> o.optionId().equals(DevFixtures.OPT_ONION)
                        ? new ProductOption(o.optionId(), "Red onion", o.type(), 0L, 0, 1, true) : o).toList();
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(burger.productId(), "Dev Burger Deluxe", true, true,
                "TRY", burger.basePriceGrossMinor(), burger.taxRateBps(), renamed));

        placeOrder(A1, newKey(), confirmedCheckout(A1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].name").value("Dev Burger Deluxe"))
                .andExpect(jsonPath("$.items[0].removedIngredients[0].name").value("Red onion"));
    }

    @Test
    void orderIdsAreRandomUuidsNotDerivedFromTheCart() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        String orderId = json.readTree(placeOrder(A1, newKey(), confirmedCheckout(A1)).andReturn().getResponse()
                .getContentAsString()).path("orderId").asString();
        UUID cartId = jdbc.queryForObject("SELECT id FROM carts", UUID.class);
        assertEquals(false, orderId.equals(cartId.toString()));
    }

    // ---- component-level reconciliation: changes that leave the total untouched ---------------------------------------

    private void publishBurger(long base, long cheese, int taxBps, String version) {
        ProductSnapshot burger = DevFixtures.burgerSnapshot();
        List<ProductOption> options = burger.options().stream()
                .map(o -> o.optionId().equals(DevFixtures.OPT_CHEESE)
                        ? new ProductOption(o.optionId(), o.name(), OptionType.ADD, cheese, 0, 2, true) : o).toList();
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(burger.productId(), burger.name(), true, true, "TRY",
                base, taxBps, options, version));
    }

    private void publishSalad(long price, int taxBps) {
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(DevFixtures.SALAD, "Dev Salad", true, true, "TRY",
                price, taxBps, List.of()));
    }

    private long cartVersion() throws Exception {
        return json.readTree(getCart(A1).andReturn().getResponse().getContentAsString()).path("version").asLong();
    }

    @Test
    void twoPricesMovingInOppositeDirectionsAreStillAChangeTheGuestHasNotSeen() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());   // 25000
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());    // 9000  -> total 34000
        Map<String, Object> reviewed = confirmedCheckout(A1);
        assertEquals(34_000L, ((Number) reviewed.get("expectedTotalMinor")).longValue());

        publishBurger(25_500L, 2_500L, 1_000, null);   // +500
        publishSalad(8_500L, 2_000);                   // -500: the total is unchanged

        placeOrder(A1, newKey(), reviewed)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.details.currentTotalMinor").value(34_000))
                .andExpect(jsonPath("$.details.changedLines.length()").value(2))
                .andExpect(jsonPath("$.details.cart.items[0].priceChanged").value(true))
                .andExpect(jsonPath("$.details.cart.items[1].priceChanged").value(true));
        assertEquals(0, count("orders"));

        // a blind retry stays refused; the guest who looked at both changes and acknowledges them orders at new prices
        placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isConflict());
        placeOrder(A1, newKey(), acknowledgedCheckout(A1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].unitGrossMinor").value(25_500))
                .andExpect(jsonPath("$.items[1].unitGrossMinor").value(8_500))
                .andExpect(jsonPath("$.amounts.grossMinor").value(34_000));
    }

    @Test
    void anExtraGettingDearerWhileTheBaseGetsCheaperIsDetected() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 2, List.of(), List.of(extra(DevFixtures.OPT_CHEESE, 1))))
                .andExpect(status().isOk());                       // unit 25000 + 2500
        Map<String, Object> reviewed = confirmedCheckout(A1);

        publishBurger(24_000L, 3_500L, 1_000, null);               // base -1000, extra +1000: unit price unchanged

        placeOrder(A1, newKey(), reviewed)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.details.currentTotalMinor").value(55_000))
                .andExpect(jsonPath("$.details.cart.items[0].unitGrossMinor").value(27_500));
        assertEquals(0, count("orders"));
        // once the guest acknowledges, the order records the new components
        placeOrder(A1, newKey(), acknowledgedCheckout(A1)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].extras[0].unitPriceMinor").value(3_500));
        assertEquals(3_500L, jdbc.queryForObject("SELECT unit_price_delta_minor FROM order_item_options"
                + " WHERE action = 'ADD'", Long.class));
    }

    @Test
    void aVatOnlyChangeIsDetectedBecauseItChangesTheNetAndVatSplit() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 2)).andExpect(status().isOk());   // 18000 @20%
        Map<String, Object> reviewed = confirmedCheckout(A1);
        publishSalad(9_000L, 1_000);                                            // same gross, now 10% VAT

        placeOrder(A1, newKey(), reviewed)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.details.currentTotalMinor").value(18_000));
        placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isConflict());
        placeOrder(A1, newKey(), acknowledgedCheckout(A1)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].taxRateBps").value(1_000))
                .andExpect(jsonPath("$.amounts.netMinor").value(16_364))      // 18000 * 10000 / 11000, half up
                .andExpect(jsonPath("$.amounts.vatMinor").value(1_636));
    }

    @Test
    void aChangedPriceVersionIsAChangeEvenWhenEveryAmountIsIdentical() throws Exception {
        publishBurger(25_000L, 2_500L, 1_000, "v1");
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        Map<String, Object> reviewed = confirmedCheckout(A1);
        assertEquals("v1", jdbc.queryForObject("SELECT price_version_snapshot FROM cart_items", String.class));

        publishBurger(25_000L, 2_500L, 1_000, "v2");

        placeOrder(A1, newKey(), reviewed).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"));
        placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isConflict());
        placeOrder(A1, newKey(), acknowledgedCheckout(A1)).andExpect(status().isCreated());
        assertEquals("v2", jdbc.queryForObject("SELECT price_version FROM order_items", String.class),
                "the order snapshot records the price version it was priced with");
    }

    @Test
    void catalogsWithoutAPriceVersionDoNotTriggerFalseAlarms() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isCreated());
        assertEquals(null, jdbc.queryForObject("SELECT price_version FROM order_items", String.class));
    }

    @Test
    void aWrongTotalWithoutAnyPriceChangeDoesNotTouchTheCart() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        long before = cartVersion();
        placeOrder(A1, newKey(), Map.of("expectedCartVersion", before, "expectedTotalMinor", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.details.changedLines.length()").value(0))
                .andExpect(jsonPath("$.details.currentCartVersion").value(before));
        assertEquals(before, cartVersion(), "a lie about the total must not change the cart");
    }

    @Test
    void aRefusedConfirmationWritesNothingAndAnAcknowledgementOfOldPricesIsStale() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        Map<String, Object> reviewed = confirmedCheckout(A1);
        long before = cartVersion();
        publishBurger(26_000L, 2_500L, 1_000, null);

        placeOrder(A1, newKey(), reviewed).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.currentCartVersion").value(before));
        assertEquals(before, cartVersion(), "the refusal does not advance the cart version");
        assertEquals(25_000L, jdbc.queryForObject("SELECT unit_gross_minor_snapshot FROM cart_items", Long.class),
                "nor rewrite the stored price");
        getCart(A1).andExpect(jsonPath("$.items[0].priceChanged").value(true));   // the change stays visible

        // an acknowledgement given for the prices shown NOW is void once the price moves again
        Map<String, Object> acknowledged = acknowledgedCheckout(A1);
        publishBurger(27_000L, 2_500L, 1_000, null);
        placeOrder(A1, newKey(), acknowledged).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"));
        assertEquals(0, count("orders"));
        placeOrder(A1, newKey(), acknowledgedCheckout(A1)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.amounts.grossMinor").value(27_000));
    }

    @Test
    void priceChangesOfProductsNotInTheCartAreIrrelevant() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        Map<String, Object> reviewed = confirmedCheckout(A1);
        publishBurger(99_999L, 9_999L, 2_000, "other");
        placeOrder(A1, newKey(), reviewed).andExpect(status().isCreated());
    }

    @Test
    void theClientCannotSkipTheComponentCheckByMatchingTheTotal() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        Map<String, Object> reviewed = confirmedCheckout(A1);
        publishBurger(26_000L, 2_500L, 1_000, null);
        publishSalad(8_000L, 2_000);   // 26000 + 8000 == 34000: the guest "confirms" the unchanged total

        for (int attempt = 0; attempt < 3; attempt++) {
            placeOrder(A1, newKey(), reviewed).andExpect(status().isConflict());
        }
        assertEquals(0, count("orders"));
    }
}
