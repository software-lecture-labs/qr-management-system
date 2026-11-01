package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CartCustomizationApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;

    @Test
    void customizedProductIsPricedFromCatalogExtras() throws Exception {
        // 25000 base + 2 x 2500 cheese + 1 x 4000 bacon = 34000 per unit, x2 = 68000, 10% VAT inclusive
        addItem(A1, product(DevFixtures.BURGER, 2, List.of(DevFixtures.OPT_ONION),
                List.of(extra(DevFixtures.OPT_CHEESE, 2), extra(DevFixtures.OPT_BACON, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].unitGrossMinor").value(34_000))
                .andExpect(jsonPath("$.items[0].lineGrossMinor").value(68_000))
                .andExpect(jsonPath("$.items[0].lineNetMinor").value(61_818))
                .andExpect(jsonPath("$.items[0].lineVatMinor").value(6_182))
                .andExpect(jsonPath("$.items[0].removedIngredients", hasSize(1)))
                .andExpect(jsonPath("$.items[0].removedIngredients[0].name").value("Onion"))
                .andExpect(jsonPath("$.items[0].extras", hasSize(2)))
                .andExpect(jsonPath("$.items[0].extras[?(@.name == 'Extra cheese')].quantity").value(2))
                .andExpect(jsonPath("$.items[0].extras[?(@.name == 'Extra cheese')].unitPriceMinor").value(2_500))
                .andExpect(jsonPath("$.totals.grossMinor").value(68_000));
    }

    @Test
    void removingIngredientsDoesNotChangeThePrice() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(DevFixtures.OPT_ONION, DevFixtures.OPT_PICKLE), List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitGrossMinor").value(25_000))
                .andExpect(jsonPath("$.items[0].removedIngredients", hasSize(2)));
    }

    @Test
    void standardAndCustomizedVersionsOfTheSameProductAreSeparateLines() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(DevFixtures.OPT_ONION), List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)));
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(), List.of(extra(DevFixtures.OPT_CHEESE, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.totals.grossMinor").value(25_000 + 25_000 + 27_500));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM cart_items", Integer.class));
    }

    @Test
    void identicalPreferencesInAnyOrderMergeIntoOneLine() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(DevFixtures.OPT_ONION, DevFixtures.OPT_PICKLE),
                List.of(extra(DevFixtures.OPT_CHEESE, 1), extra(DevFixtures.OPT_BACON, 1))))
                .andExpect(status().isOk());
        addItem(A1, product(DevFixtures.BURGER, 2, List.of(DevFixtures.OPT_PICKLE, DevFixtures.OPT_ONION),
                List.of(extra(DevFixtures.OPT_BACON, 1), extra(DevFixtures.OPT_CHEESE, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].quantity").value(3));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM cart_item_options", Integer.class),
                "merging must not duplicate the stored selections");
    }

    @Test
    void sameProductWithDifferentNotesIsSeparate() throws Exception {
        addItem(A1, Map.of("productId", DevFixtures.BURGER, "quantity", 1, "note", "well done"))
                .andExpect(status().isOk());
        addItem(A1, Map.of("productId", DevFixtures.BURGER, "quantity", 1, "note", "rare"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)));
    }

    @Test
    void invalidIngredientChoicesAreRejectedWithoutTouchingTheCart() throws Exception {
        UUID unknown = UUID.randomUUID();
        // unknown / foreign ingredient
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(unknown), List.of()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNKNOWN_OPTION"))
                .andExpect(jsonPath("$.details.ingredientId").value(unknown.toString()));
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(), List.of(extra(unknown, 1))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNKNOWN_OPTION"));
        // mandatory ingredient / extra used as removal
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(DevFixtures.OPT_BUN), List.of()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OPTION_NOT_REMOVABLE"));
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(DevFixtures.OPT_CHEESE), List.of()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OPTION_NOT_REMOVABLE"));
        // standard ingredient used as paid extra
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(), List.of(extra(DevFixtures.OPT_ONION, 1))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OPTION_NOT_EXTRA"));
        // double selection
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(DevFixtures.OPT_ONION, DevFixtures.OPT_ONION), List.of()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DUPLICATE_OPTION"));
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(),
                List.of(extra(DevFixtures.OPT_CHEESE, 1), extra(DevFixtures.OPT_CHEESE, 1))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DUPLICATE_OPTION"));
        // contradictory
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(DevFixtures.OPT_ONION),
                List.of(extra(DevFixtures.OPT_ONION, 1))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CONFLICTING_OPTION"));
        // extra quantity outside the catalog bounds (cheese max 2)
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(), List.of(extra(DevFixtures.OPT_CHEESE, 3))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OPTION_QUANTITY_INVALID"));
        addItem(A1, product(DevFixtures.BURGER, 1, List.of(), List.of(extra(DevFixtures.OPT_CHEESE, 0))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM carts", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cart_items", Integer.class));
    }

    @Test
    void ingredientOfAnotherProductIsRejected() throws Exception {
        // SALAD has no options at all; BURGER's cheese must not be usable on it
        addItem(A1, product(DevFixtures.SALAD, 1, List.of(), List.of(extra(DevFixtures.OPT_CHEESE, 1))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNKNOWN_OPTION"));
    }

    @Test
    void clientCannotInjectOptionPricesOrNames() throws Exception {
        addItemRaw(A1, "{\"productId\": \"" + DevFixtures.BURGER + "\", \"quantity\": 1, \"extras\": ["
                + "{\"ingredientId\": \"" + DevFixtures.OPT_CHEESE + "\", \"quantity\": 1, \"price\": 0}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        addItemRaw(A1, "{\"productId\": \"" + DevFixtures.BURGER + "\", \"quantity\": 1, \"extras\": ["
                + "{\"ingredientId\": \"" + DevFixtures.OPT_CHEESE + "\", \"quantity\": 1, \"name\": \"Free\"}]}")
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM carts", Integer.class));
    }

    @Test
    void notesAreStoredAsPlainTextAndBounded() throws Exception {
        String html = "<script>alert('x')</script> no onions";
        addItem(A1, Map.of("productId", DevFixtures.BURGER, "quantity", 1, "note", html))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].note").value(html));
        assertEquals(html, jdbc.queryForObject("SELECT note FROM cart_items", String.class));

        addItem(A1, Map.of("productId", DevFixtures.SALAD, "quantity", 1, "note", "x".repeat(301)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        addItem(A1, Map.of("productId", DevFixtures.SALAD, "quantity", 1, "note", "bad\u0007bell"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_NOTE"));
    }

    @Test
    void tooManySelectionsAreRejected() throws Exception {
        List<UUID> manyRemoved = IntStream.range(0, 51).mapToObj(i -> UUID.randomUUID()).toList();
        addItem(A1, product(DevFixtures.BURGER, 1, manyRemoved, List.of()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void cartHoldsAtMostTheConfiguredNumberOfDistinctLines() throws Exception {
        int maxLines = 50; // restofy.cart.max-lines
        for (int i = 0; i < maxLines; i++) {
            addItem(A1, Map.of("productId", DevFixtures.SALAD, "quantity", 1, "note", "variant " + i))
                    .andExpect(status().isOk());
        }
        addItem(A1, Map.of("productId", DevFixtures.SALAD, "quantity", 1, "note", "one too many"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CART_ITEM_LIMIT_EXCEEDED"));
        // merging into an existing line is still allowed at the limit
        addItem(A1, Map.of("productId", DevFixtures.SALAD, "quantity", 1, "note", "variant 0"))
                .andExpect(status().isOk());
    }
}
