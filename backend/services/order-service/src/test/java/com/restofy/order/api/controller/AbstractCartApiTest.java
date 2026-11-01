package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import com.restofy.order.infrastructure.client.mock.InMemoryAuthContextAdapter;
import com.restofy.order.infrastructure.client.mock.InMemoryMenuCatalogAdapter;
import com.restofy.order.infrastructure.client.mock.InMemoryQrSessionAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Real HTTP-layer + PostgreSQL tests of the cart API. The QR and Menu services are the dev/test MOCK adapters;
 * they are reset before every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractCartApiTest {

    protected static final String CART = "/api/v1/carts/current";
    private static final String CART_ACTIVITY = "CartActivityRecorded";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JsonMapper json;
    @Autowired
    protected InMemoryQrSessionAdapter qr;
    @Autowired
    protected InMemoryMenuCatalogAdapter menu;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected InMemoryAuthContextAdapter auth;

    @BeforeEach
    void resetState() {
        qr.reset();
        menu.reset();
        auth.reset();
        // append-only table: rows cannot be DELETEd, TRUNCATE is the test-only way to clear it
        jdbc.execute("TRUNCATE order_status_history");
        jdbc.execute("TRUNCATE payment_reconciliation_cases, payment_captures");
        jdbc.execute("TRUNCATE order_cancellation_reasons, order_cancellation_items, order_cancellations");
        jdbc.execute("TRUNCATE table_session_closure_drifts, table_session_closures");
        for (String table : new String[]{"inbox_events", "outbox_deliveries", "outbox_events", "idempotency_records",
                "order_item_options", "order_items", "orders", "order_number_counters", "carts"}) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    protected ResultActions placeOrder(String token, String idempotencyKey, Object body) throws Exception {
        var request = authorized(post("/api/v1/orders"), token)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    /** Reads the cart as the guest sees it and builds the matching "I confirm this" checkout body. */
    protected Map<String, Object> confirmedCheckout(String token) throws Exception {
        var cart = json.readTree(getCart(token).andReturn().getResponse().getContentAsString());
        return Map.of("expectedCartVersion", cart.path("version").asLong(),
                "expectedTotalMinor", cart.path("totals").path("grossMinor").asLong());
    }

    /**
     * Reads the cart as the guest sees it and builds the checkout body of a guest who looked at every changed price and
     * accepts them explicitly: version, total, the fingerprint of exactly these prices, and the acknowledgement.
     */
    protected Map<String, Object> acknowledgedCheckout(String token) throws Exception {
        var cart = json.readTree(getCart(token).andReturn().getResponse().getContentAsString());
        return Map.of("expectedCartVersion", cart.path("version").asLong(),
                "expectedTotalMinor", cart.path("totals").path("grossMinor").asLong(),
                "expectedPriceFingerprint", cart.path("priceFingerprint").asString(),
                "acknowledgePriceChanges", true);
    }

    protected static String guest(String token) {
        return "Bearer " + token;
    }

    protected static String staff(String credential) {
        return "Staff " + credential;
    }

    protected static String service(String credential) {
        return "Service " + credential;
    }

    /** GET with a complete Authorization header value (Bearer for guests, Staff for staff). */
    protected ResultActions getWith(String authorization, String path) throws Exception {
        var request = get(path);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    /** A guest fills the cart with one salad and orders it; returns the new order id. */
    protected UUID placeSaladOrder(String guestToken) throws Exception {
        addItem(guestToken, product(DevFixtures.SALAD, 1))
                .andExpect(MockMvcResultMatchers.status().isOk());
        String body = placeOrder(guestToken, newKey(), confirmedCheckout(guestToken))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("orderId").asString());
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * Rows of a table. The outbox tables count the BUSINESS events of an order (placed, status, payment, cancellation)
     * only: every successful cart change also records a CartActivityRecorded notification for the QR Service
     * (ADR-QR-001 K6), which CartActivityEventApiTests covers on its own. Use {@link #countCartActivityEvents()} for those.
     */
    protected int count(String table) {
        String sql = switch (table) {
            case "outbox_events" ->
                    "SELECT count(*) FROM outbox_events WHERE event_type <> '" + CART_ACTIVITY + "'";
            case "outbox_deliveries" -> "SELECT count(*) FROM outbox_deliveries d JOIN outbox_events e"
                    + " ON e.id = d.event_id WHERE e.event_type <> '" + CART_ACTIVITY + "'";
            default -> "SELECT count(*) FROM " + table;
        };
        return jdbc.queryForObject(sql, Integer.class);
    }

    protected int countCartActivityEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ?", Integer.class,
                CART_ACTIVITY);
    }

    protected ResultActions getCart(String token) throws Exception {
        return mvc.perform(authorized(get(CART), token));
    }

    protected ResultActions addItem(String token, Object body) throws Exception {
        return mvc.perform(authorized(post(CART + "/items"), token)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    protected ResultActions addItemRaw(String token, String rawJson) throws Exception {
        return mvc.perform(authorized(post(CART + "/items"), token)
                .contentType(MediaType.APPLICATION_JSON).content(rawJson));
    }

    protected ResultActions patchItem(String token, Object cartItemId, Object body) throws Exception {
        return mvc.perform(authorized(patch(CART + "/items/" + cartItemId), token)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    protected ResultActions removeItem(String token, Object cartItemId) throws Exception {
        return mvc.perform(authorized(delete(CART + "/items/" + cartItemId), token));
    }

    protected ResultActions clearCart(String token) throws Exception {
        return mvc.perform(authorized(delete(CART + "/items"), token));
    }

    protected static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    protected String toJson(Object body) {
        return json.writeValueAsString(body);
    }

    protected static Map<String, Object> product(UUID productId, int quantity) {
        return Map.of("productId", productId, "quantity", quantity);
    }

    protected static Map<String, Object> product(UUID productId, int quantity, List<UUID> removed,
                                                 List<Map<String, Object>> extras) {
        return Map.of("productId", productId, "quantity", quantity,
                "removedIngredientIds", removed, "extras", extras);
    }

    protected static Map<String, Object> extra(UUID ingredientId, int quantity) {
        return Map.of("ingredientId", ingredientId, "quantity", quantity);
    }

    /** Adds a plain product and returns the id of the first cart line from the response. */
    protected String addAndGetFirstItemId(String token, UUID productId, int quantity) throws Exception {
        String body = addItem(token, product(productId, quantity)).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("items").get(0).path("cartItemId").asString();
    }
}
