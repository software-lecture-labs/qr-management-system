package com.restofy.order.api.controller;

import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.application.port.ProductSnapshot;
import com.restofy.order.application.port.ProductSnapshot.OptionType;
import com.restofy.order.application.port.ProductSnapshot.ProductOption;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CartViewAndClearApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;
    private static final String A2 = DevFixtures.TOKEN_A2;

    // ---- view -------------------------------------------------------------------------------------------------

    @Test
    void guestWithoutItemsGetsAnEmptyCartAndNothingIsPersisted() throws Exception {
        getCart(A1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartId").value(nullValue()))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totals.grossMinor").value(0))
                .andExpect(jsonPath("$.totals.netMinor").value(0))
                .andExpect(jsonPath("$.totals.vatMinor").value(0))
                .andExpect(jsonPath("$.sessionActive").value(true))
                .andExpect(jsonPath("$.estimate").value(true));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM carts", Integer.class));
    }

    @Test
    void privateCartResponsesAreNeverCacheable() throws Exception {
        getCart(A1)
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void mixedVatRatesAreRoundedPerLineAndSummed() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        addItem(A1, product(DevFixtures.SALAD, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].lineNetMinor").value(22_727))
                .andExpect(jsonPath("$.items[0].lineVatMinor").value(2_273))
                .andExpect(jsonPath("$.items[1].taxRateBps").value(2_000))
                .andExpect(jsonPath("$.items[1].lineNetMinor").value(7_500))
                .andExpect(jsonPath("$.items[1].lineVatMinor").value(1_500))
                .andExpect(jsonPath("$.totals.grossMinor").value(34_000))
                .andExpect(jsonPath("$.totals.netMinor").value(30_227))
                .andExpect(jsonPath("$.totals.vatMinor").value(3_773));
    }

    @Test
    void linesKeepAStableCreationOrder() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        for (int i = 0; i < 3; i++) {
            getCart(A1)
                    .andExpect(jsonPath("$.items[0].productId").value(DevFixtures.SALAD.toString()))
                    .andExpect(jsonPath("$.items[1].productId").value(DevFixtures.BURGER.toString()));
        }
    }

    @Test
    void viewRecomputesPricesFromTheCatalogAndFlagsTheChange() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].priceChanged").value(false));

        ProductSnapshot burger = DevFixtures.burgerSnapshot();
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(burger.productId(), burger.name(), true, true,
                "TRY", 30_000L, 1_000, burger.options()));

        getCart(A1)
                .andExpect(jsonPath("$.items[0].unitGrossMinor").value(30_000))
                .andExpect(jsonPath("$.items[0].lineGrossMinor").value(60_000))
                .andExpect(jsonPath("$.items[0].priceChanged").value(true))
                .andExpect(jsonPath("$.totals.grossMinor").value(60_000));
        // the stored value is only the last validated one; it does not silently follow the catalog
        assertEquals(25_000L, jdbc.queryForObject("SELECT unit_gross_minor_snapshot FROM cart_items", Long.class));
    }

    @Test
    void extraPriceChangesAreAlsoReflected() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(), List.of(extra(DevFixtures.OPT_CHEESE, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitGrossMinor").value(27_500));
        ProductSnapshot burger = DevFixtures.burgerSnapshot();
        List<ProductOption> options = burger.options().stream()
                .map(o -> o.optionId().equals(DevFixtures.OPT_CHEESE)
                        ? new ProductOption(o.optionId(), o.name(), OptionType.ADD, 5_000L, 0, 2, true) : o).toList();
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(burger.productId(), burger.name(), true, true,
                "TRY", burger.basePriceGrossMinor(), burger.taxRateBps(), options));

        getCart(A1)
                .andExpect(jsonPath("$.items[0].unitGrossMinor").value(30_000))
                .andExpect(jsonPath("$.items[0].priceChanged").value(true));
    }

    @Test
    void unknownTaxRateLeavesNetAndVatUnsetInsteadOfInventingOne() throws Exception {
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(DevFixtures.SALAD, "Dev Salad", true, true, "TRY",
                9_000L, null, List.of()));
        addItem(A1, product(DevFixtures.SALAD, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].lineGrossMinor").value(9_000))
                .andExpect(jsonPath("$.items[0].taxRateBps").value(nullValue()))
                .andExpect(jsonPath("$.items[0].lineNetMinor").value(nullValue()))
                .andExpect(jsonPath("$.totals.grossMinor").value(9_000))
                .andExpect(jsonPath("$.totals.netMinor").value(nullValue()))
                .andExpect(jsonPath("$.totals.vatMinor").value(nullValue()));
    }

    @Test
    void linesThatCanNoLongerBePricedAreFlaggedAndExcludedFromTotals() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());

        // the burger disappears from the menu entirely
        menu.remove(DevFixtures.RESTAURANT_A, DevFixtures.BURGER);
        getCart(A1)
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].available").value(false))
                .andExpect(jsonPath("$.items[0].lineGrossMinor").value(nullValue()))
                .andExpect(jsonPath("$.items[1].available").value(true))
                .andExpect(jsonPath("$.totals.grossMinor").value(9_000));

        // sold out is also unavailable; the line stays visible so the guest can remove it
        ProductSnapshot burger = DevFixtures.burgerSnapshot();
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(burger.productId(), burger.name(), true, false,
                "TRY", burger.basePriceGrossMinor(), burger.taxRateBps(), burger.options()));
        getCart(A1).andExpect(jsonPath("$.items[0].available").value(false));
    }

    @Test
    void lineWithAnIngredientThatWasRetiredBecomesUnavailable() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(DevFixtures.OPT_ONION),
                List.of(extra(DevFixtures.OPT_CHEESE, 1)))).andExpect(status().isOk());
        ProductSnapshot burger = DevFixtures.burgerSnapshot();
        List<ProductOption> options = burger.options().stream()
                .map(o -> o.optionId().equals(DevFixtures.OPT_CHEESE)
                        ? new ProductOption(o.optionId(), o.name(), OptionType.ADD, 2_500L, 0, 2, false) : o).toList();
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(burger.productId(), burger.name(), true, true,
                "TRY", burger.basePriceGrossMinor(), burger.taxRateBps(), options));

        getCart(A1)
                .andExpect(jsonPath("$.items[0].available").value(false))
                .andExpect(jsonPath("$.totals.grossMinor").value(0));
    }

    @Test
    void closedSessionStillShowsTheCartReadOnly() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        qr.register(A1, DevFixtures.guestA1(SessionState.CLOSED));
        getCart(A1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionActive").value(false))
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    // ---- clear ------------------------------------------------------------------------------------------------

    @Test
    void clearRemovesEveryLineAndKeepsTheCartForReuse() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 2, List.of(DevFixtures.OPT_ONION),
                List.of(extra(DevFixtures.OPT_CHEESE, 1)))).andExpect(status().isOk());
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());

        clearCart(A1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totals.grossMinor").value(0));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cart_items", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cart_item_options", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM carts WHERE status = 'ACTIVE'", Integer.class));

        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void repeatedClearIsIdempotentAndDoesNotBumpTheVersion() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        String first = clearCart(A1).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long versionAfterClear = json.readTree(first).path("version").asLong();

        for (int i = 0; i < 3; i++) {
            clearCart(A1)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(versionAfterClear))
                    .andExpect(jsonPath("$.items", hasSize(0)));
        }
    }

    @Test
    void clearingWhenThereIsNoCartSucceedsWithoutCreatingOne() throws Exception {
        clearCart(A1).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM carts", Integer.class));
    }

    @Test
    void clearHonoursTheExpectedVersionOnlyWhenThereIsSomethingToClear() throws Exception {
        String body = addItem(A1, product(DevFixtures.BURGER, 1)).andReturn().getResponse().getContentAsString();
        long version = json.readTree(body).path("version").asLong();

        mvc.perform(authorized(delete(CART + "/items?expectedCartVersion=" + (version + 5)), A1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_VERSION_CONFLICT"));
        getCart(A1).andExpect(jsonPath("$.items", hasSize(1)));

        mvc.perform(authorized(delete(CART + "/items?expectedCartVersion=" + version), A1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
        // retry with the now-stale version: already empty, so it is a harmless success
        mvc.perform(authorized(delete(CART + "/items?expectedCartVersion=" + version), A1))
                .andExpect(status().isOk());
    }

    @Test
    void clearOnlyAffectsTheCallersOwnCart() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        addItem(A2, product(DevFixtures.BURGER, 4)).andExpect(status().isOk());

        clearCart(A1).andExpect(status().isOk());
        getCart(A2).andExpect(jsonPath("$.items[0].quantity").value(4));
    }

    @Test
    void clearIsBlockedOnceTheSessionIsNoLongerActive() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        qr.register(A1, DevFixtures.guestA1(SessionState.EXPIRED));
        clearCart(A1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_SESSION_NOT_ACTIVE"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM cart_items", Integer.class));
    }

    // ---- currency ---------------------------------------------------------------------------------------------

    @Test
    void aCartHoldsOneCurrencyUntilItIsEmptied() throws Exception {
        UUID usdProduct = UUID.randomUUID();
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(usdProduct, "Dev Import Soda", true, true, "USD",
                500L, 800, List.of()));

        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk()).andExpect(jsonPath("$.currency").value("TRY"));
        addItem(A1, Map.of("productId", usdProduct, "quantity", 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));

        clearCart(A1).andExpect(status().isOk());
        addItem(A1, Map.of("productId", usdProduct, "quantity", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"));
    }
}
