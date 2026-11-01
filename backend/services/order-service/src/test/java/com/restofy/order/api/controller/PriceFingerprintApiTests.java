package com.restofy.order.api.controller;

import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.ProductSnapshot;
import com.restofy.order.application.port.ProductSnapshot.OptionType;
import com.restofy.order.application.port.ProductSnapshot.ProductOption;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * K-01: a confirmation can be bound to the exact live prices of one cart version with {@code expectedPriceFingerprint}.
 * The server recomputes the fingerprint, names what changed per product and extra, and a rejected confirmation writes
 * nothing to the cart.
 */
class PriceFingerprintApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;
    private static final String A2 = DevFixtures.TOKEN_A2;

    private void publishBurger(long base, long cheese, int taxBps, String version) {
        ProductSnapshot burger = DevFixtures.burgerSnapshot();
        List<ProductOption> options = burger.options().stream()
                .map(o -> o.optionId().equals(DevFixtures.OPT_CHEESE)
                        ? new ProductOption(o.optionId(), o.name(), OptionType.ADD, cheese, 0, 2, true) : o).toList();
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(burger.productId(), burger.name(), true, true, "TRY",
                base, taxBps, options, version));
    }

    private void publishSalad(long price) {
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(DevFixtures.SALAD, "Dev Salad", true, true, "TRY",
                price, 2_000, List.of()));
    }

    private JsonNode cart(String token) throws Exception {
        return json.readTree(getCart(token).andReturn().getResponse().getContentAsString());
    }

    /** What an honest client sends: the version, total and fingerprint of the cart view it displayed. */
    private Map<String, Object> confirmation(JsonNode cartView) {
        Map<String, Object> body = new HashMap<>();
        body.put("expectedCartVersion", cartView.path("version").asLong());
        body.put("expectedTotalMinor", cartView.path("totals").path("grossMinor").asLong());
        body.put("expectedPriceFingerprint", cartView.path("priceFingerprint").asString());
        return body;
    }

    private long storedUnitPrice() {
        return jdbc.queryForObject("SELECT unit_gross_minor_snapshot FROM cart_items", Long.class);
    }

    private long storedVersion() {
        return jdbc.queryForObject("SELECT version FROM carts", Long.class);
    }

    // ---- the fingerprint in the cart view ----------------------------------------------------------------------------

    @Test
    void theCartViewCarriesAStableServerComputedFingerprint() throws Exception {
        assertTrue(cart(A1).path("priceFingerprint").isNull(), "an empty cart has none");
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        String first = cart(A1).path("priceFingerprint").asString();
        assertTrue(first.matches("[0-9a-f]{64}"), first);
        assertEquals(first, cart(A1).path("priceFingerprint").asString(), "reading does not change it");

        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        assertNotEquals(first, cart(A1).path("priceFingerprint").asString(), "a changed cart has a new fingerprint");
    }

    @Test
    void theFingerprintFollowsEveryLivePriceComponent() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(), List.of(extra(DevFixtures.OPT_CHEESE, 1))))
                .andExpect(status().isOk());
        String base = cart(A1).path("priceFingerprint").asString();
        String[] seen = new String[4];
        publishBurger(25_100L, 2_500L, 1_000, null);   // product price
        seen[0] = cart(A1).path("priceFingerprint").asString();
        publishBurger(25_000L, 2_600L, 1_000, null);   // extra price
        seen[1] = cart(A1).path("priceFingerprint").asString();
        publishBurger(25_000L, 2_500L, 2_000, null);   // VAT rate only
        seen[2] = cart(A1).path("priceFingerprint").asString();
        publishBurger(25_000L, 2_500L, 1_000, "v9");   // catalog price version only
        seen[3] = cart(A1).path("priceFingerprint").asString();
        assertEquals(4, Set.of(seen).size());
        for (String changed : seen) {
            assertNotEquals(base, changed);
        }
        publishBurger(25_000L, 2_500L, 1_000, null);   // everything back
        assertEquals(base, cart(A1).path("priceFingerprint").asString(), "same prices, same cart, same fingerprint");
    }

    @Test
    void twoGuestsNeverShareAFingerprint() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        addItem(A2, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        JsonNode mine = cart(A1);
        JsonNode theirs = cart(A2);
        assertNotEquals(mine.path("priceFingerprint").asString(), theirs.path("priceFingerprint").asString());
        // someone else's confirmation is not mine
        Map<String, Object> stolen = confirmation(theirs);
        stolen.put("expectedCartVersion", mine.path("version").asLong());
        placeOrder(A1, newKey(), stolen).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"));
        assertEquals(0, count("orders"));
    }

    // ---- confirming with a fingerprint ---------------------------------------------------------------------------------

    @Test
    void anHonestConfirmationOrdersAndIsIdempotent() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 2)).andExpect(status().isOk());
        Map<String, Object> body = confirmation(cart(A1));
        String key = newKey();
        placeOrder(A1, key, body).andExpect(status().isCreated()).andExpect(jsonPath("$.amounts.grossMinor").value(50_000));
        placeOrder(A1, key, body).andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true"));
        // the same key with a different fingerprint is a different request
        Map<String, Object> other = new HashMap<>(body);
        other.put("expectedPriceFingerprint", "0".repeat(64));
        placeOrder(A1, key, other).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertEquals(1, count("orders"));
    }

    @Test
    void aChangedPriceIsRefusedWithPerComponentDetailAndTheCartIsNotTouched() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 2, List.of(), List.of(extra(DevFixtures.OPT_CHEESE, 1))))
                .andExpect(status().isOk());                              // unit 25000 + 2500
        Map<String, Object> reviewed = confirmation(cart(A1));
        long version = storedVersion();
        long unit = storedUnitPrice();
        publishBurger(24_000L, 3_500L, 1_000, null);                       // base -1000, extra +1000: total unchanged

        placeOrder(A1, newKey(), reviewed).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.details.priceChanges.length()").value(2))
                .andExpect(jsonPath("$.details.priceChanges[?(@.component=='PRODUCT_PRICE')].previous").value(25_000))
                .andExpect(jsonPath("$.details.priceChanges[?(@.component=='PRODUCT_PRICE')].current").value(24_000))
                .andExpect(jsonPath("$.details.priceChanges[?(@.component=='EXTRA_PRICE')].previous").value(2_500))
                .andExpect(jsonPath("$.details.priceChanges[?(@.component=='EXTRA_PRICE')].current").value(3_500))
                .andExpect(jsonPath("$.details.priceChanges[?(@.component=='EXTRA_PRICE')].optionId")
                        .value(DevFixtures.OPT_CHEESE.toString()))
                .andExpect(jsonPath("$.details.currentCartVersion").value(version))
                .andExpect(jsonPath("$.details.currentPriceFingerprint").isString());

        assertEquals(version, storedVersion(), "a rejected confirmation does not touch the cart version");
        assertEquals(unit, storedUnitPrice(), "nor its stored prices");
        assertEquals(0, count("orders"));
        assertEquals(0, count("idempotency_records"), "nor burn the idempotency key");
    }

    @Test
    void confirmingTheReportedFingerprintAndTotalOrdersAtTheNewPrices() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 2)).andExpect(status().isOk());
        Map<String, Object> reviewed = confirmation(cart(A1));
        publishBurger(30_000L, 2_500L, 1_000, null);
        String key = newKey();
        String rejection = placeOrder(A1, key, reviewed).andExpect(status().isConflict()).andReturn().getResponse()
                .getContentAsString();
        JsonNode details = json.readTree(rejection).path("details");

        Map<String, Object> accepted = new HashMap<>(reviewed);
        accepted.put("expectedTotalMinor", details.path("currentTotalMinor").asLong());
        accepted.put("expectedPriceFingerprint", details.path("currentPriceFingerprint").asString());
        accepted.put("expectedCartVersion", details.path("currentCartVersion").asLong());
        // the fingerprint alone is not enough while a price changed: the guest must also say that they accept it
        placeOrder(A1, key, accepted).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.acknowledgementRequired").value(true));
        accepted.put("acknowledgePriceChanges", true);
        placeOrder(A1, key, accepted).andExpect(status().isCreated())
                .andExpect(jsonPath("$.amounts.grossMinor").value(60_000))
                .andExpect(jsonPath("$.items[0].unitGrossMinor").value(30_000));
        // the same fingerprint reported by the cart view is identical
        assertEquals(1, count("orders"));
    }

    @Test
    void anOldConfirmationCannotBeReusedAfterAnotherPriceOrCartChange() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        Map<String, Object> first = confirmation(cart(A1));

        publishSalad(9_500L);
        Map<String, Object> second = confirmation(cart(A1));
        placeOrder(A1, newKey(), first).andExpect(status().isConflict());

        publishSalad(10_000L);                      // the price moved again after the second review
        placeOrder(A1, newKey(), second).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"));

        // a changed cart (new version) makes every earlier confirmation fail on the version first
        publishSalad(9_000L);
        Map<String, Object> third = confirmation(cart(A1));
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        placeOrder(A1, newKey(), third).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_VERSION_CONFLICT"));
        assertEquals(0, count("orders"));
    }

    @Test
    void aMadeUpOrMalformedFingerprintIsNeverAccepted() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        Map<String, Object> honest = confirmation(cart(A1));
        for (String forged : new String[]{"0".repeat(64), "a".repeat(64)}) {
            Map<String, Object> body = new HashMap<>(honest);
            body.put("expectedPriceFingerprint", forged);
            placeOrder(A1, newKey(), body).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                    .andExpect(jsonPath("$.details.priceChanges.length()").value(0));
        }
        for (String malformed : new String[]{"abc", "G".repeat(64), "A".repeat(64), "a".repeat(65), ""}) {
            Map<String, Object> body = new HashMap<>(honest);
            body.put("expectedPriceFingerprint", malformed);
            placeOrder(A1, newKey(), body).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        // the right fingerprint with a lied total is still refused: the fingerprint does not replace the total check
        Map<String, Object> lie = new HashMap<>(honest);
        lie.put("expectedTotalMinor", 1);
        placeOrder(A1, newKey(), lie).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"));
        assertEquals(0, count("orders"));
    }

    @Test
    void aFingerprintDoesNotReplaceAuthenticationOrAnActiveSession() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        Map<String, Object> honest = confirmation(cart(A1));
        placeOrder("not-a-known-token", newKey(), honest).andExpect(status().isUnauthorized());
        qr.register(A1, DevFixtures.guestA1(GuestSessionContext.SessionState.CLOSED));
        placeOrder(A1, newKey(), honest).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_SESSION_NOT_ACTIVE"));
        assertEquals(0, count("orders"));
    }

    @Test
    void oppositePriceMovesAreNamedPerProduct() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        Map<String, Object> reviewed = confirmation(cart(A1));
        publishBurger(25_500L, 2_500L, 1_000, null);   // +500
        publishSalad(8_500L);                          // -500: the total is unchanged

        placeOrder(A1, newKey(), reviewed).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.currentTotalMinor").value(34_000))
                .andExpect(jsonPath("$.details.priceChanges.length()").value(2))
                .andExpect(jsonPath("$.details.changedLines.length()").value(2));
    }

    @Test
    void aVatOnlyAndAVersionOnlyChangeAreNamed() throws Exception {
        publishBurger(25_000L, 2_500L, 1_000, "v1");
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        Map<String, Object> reviewed = confirmation(cart(A1));
        publishBurger(25_000L, 2_500L, 2_000, "v2");

        placeOrder(A1, newKey(), reviewed).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.priceChanges[?(@.component=='TAX_RATE')].previous").value(1_000))
                .andExpect(jsonPath("$.details.priceChanges[?(@.component=='TAX_RATE')].current").value(2_000))
                .andExpect(jsonPath("$.details.priceChanges[?(@.component=='PRICE_VERSION')].name").value("v2"));
    }

    // ---- the original flow keeps working -------------------------------------------------------------------------------

    @Test
    void withoutAFingerprintAChangedPriceIsRefusedEveryTimeAndNothingIsWritten() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 2)).andExpect(status().isOk());
        Map<String, Object> legacy = confirmedCheckout(A1);
        assertTrue(!legacy.containsKey("expectedPriceFingerprint"));
        long version = storedVersion();
        publishBurger(30_000L, 2_500L, 1_000, null);
        String key = newKey();

        // an older client (and a blind automatic retry of it) never gets the new price accepted for it
        for (int attempt = 0; attempt < 3; attempt++) {
            placeOrder(A1, key, legacy).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                    .andExpect(jsonPath("$.details.acknowledgementRequired").value(true))
                    .andExpect(jsonPath("$.details.priceChanges[0].component").value("PRODUCT_PRICE"))
                    .andExpect(jsonPath("$.details.priceChanges[0].previous").value(25_000))
                    .andExpect(jsonPath("$.details.priceChanges[0].current").value(30_000));
        }
        placeOrder(A1, key, confirmedCheckout(A1)).andExpect(status().isConflict());   // re-read and retried blindly

        assertEquals(version, storedVersion(), "the cart version is unchanged");
        assertEquals(25_000L, storedUnitPrice(), "and so is the stored price");
        assertEquals(0, count("orders"));
        assertEquals(0, count("idempotency_records"), "refusals never burn the key");
        getCart(A1).andExpect(jsonPath("$.items[0].priceChanged").value(true));

        // the explicit acknowledgement works with the very same key
        placeOrder(A1, key, acknowledgedCheckout(A1)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.amounts.grossMinor").value(60_000));
    }

    @Test
    void aFingerprintAloneOrAnAcknowledgementAloneNeverAcceptsAChangedPrice() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        publishSalad(9_500L);
        Map<String, Object> full = acknowledgedCheckout(A1);

        Map<String, Object> fingerprintOnly = new HashMap<>(full);
        fingerprintOnly.remove("acknowledgePriceChanges");
        Map<String, Object> falseAck = new HashMap<>(full);
        falseAck.put("acknowledgePriceChanges", false);
        Map<String, Object> acknowledgementOnly = new HashMap<>(full);
        acknowledgementOnly.remove("expectedPriceFingerprint");
        Map<String, Object> wrongFingerprint = new HashMap<>(full);
        wrongFingerprint.put("expectedPriceFingerprint", "0".repeat(64));
        for (Map<String, Object> body : List.of(fingerprintOnly, falseAck, acknowledgementOnly, wrongFingerprint)) {
            placeOrder(A1, newKey(), body).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRICE_CHANGED"));
        }
        assertEquals(0, count("orders"));
        placeOrder(A1, newKey(), full).andExpect(status().isCreated()).andExpect(jsonPath("$.amounts.grossMinor")
                .value(9_500));
    }

    @Test
    void anAcknowledgementIsHarmlessWhenNothingChangedButStillNeedsTheRightFingerprintIfSent() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        Map<String, Object> noFingerprint = new HashMap<>(confirmedCheckout(A1));
        noFingerprint.put("acknowledgePriceChanges", true);
        Map<String, Object> wrong = new HashMap<>(noFingerprint);
        wrong.put("expectedPriceFingerprint", "f".repeat(64));
        placeOrder(A1, newKey(), wrong).andExpect(status().isConflict());
        placeOrder(A1, newKey(), noFingerprint).andExpect(status().isCreated());
    }

    @Test
    void theIdempotencyKeyIsBoundToTheAcknowledgementThatWasGiven() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        publishSalad(9_500L);
        Map<String, Object> acknowledged = acknowledgedCheckout(A1);
        String key = newKey();
        placeOrder(A1, key, acknowledged).andExpect(status().isCreated());
        placeOrder(A1, key, acknowledged).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        Map<String, Object> withoutAck = new HashMap<>(acknowledged);
        withoutAck.remove("acknowledgePriceChanges");
        placeOrder(A1, key, withoutAck).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertEquals(1, count("orders"));
    }

    @Test
    void anUnchangedCartOrdersWithEitherStyleOfConfirmation() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isCreated());
        addItem(A2, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        placeOrder(A2, newKey(), confirmation(cart(A2))).andExpect(status().isCreated());
        assertEquals(2, count("orders"));
    }
}
