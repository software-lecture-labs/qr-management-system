package com.restofy.order.api.dto.response;

import com.restofy.order.api.dto.response.CartResponse.CartItemResponse;
import com.restofy.order.api.dto.response.CartResponse.ExtraIngredient;
import com.restofy.order.api.dto.response.CartResponse.RemovedIngredient;
import com.restofy.order.api.dto.response.CartResponse.Totals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A response is a value: whoever built its lists cannot change it afterwards, and changing the list it hands out is
 * impossible. The first group of tests pins the cart response named in review; the reflective test holds every other
 * response record to the same rule so a future response cannot silently skip it.
 */
class ResponseImmutabilityTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static CartItemResponse line(String name, List<RemovedIngredient> removed, List<ExtraIngredient> extras) {
        return new CartItemResponse(UUID.randomUUID(), UUID.randomUUID(), name, 1, removed, extras, null, true, false,
                1_000L, 1_000L, 1_000, 909L, 91L);
    }

    private static CartResponse cart(List<CartItemResponse> items) {
        return new CartResponse(UUID.randomUUID(), 3L, "TRY", true, items, new Totals(1_000L, 909L, 91L), true, "fp");
    }

    // ---- the cart response -------------------------------------------------------------------------------------

    @Test
    void theItemListOfACartResponseCannotBeChanged() {
        CartResponse response = cart(new ArrayList<>(List.of(line("Burger", List.of(), List.of()))));

        List<CartItemResponse> exposed = response.items();
        CartItemResponse another = line("X", List.of(), List.of());
        assertThrows(UnsupportedOperationException.class, exposed::clear);
        assertThrows(UnsupportedOperationException.class, () -> exposed.add(another));
        assertEquals(1, response.items().size());
    }

    @Test
    void changingTheSourceListAfterwardsDoesNotChangeTheResponse() {
        List<CartItemResponse> source = new ArrayList<>(List.of(line("Burger", List.of(), List.of())));
        CartResponse response = cart(source);

        source.add(line("Salad", List.of(), List.of()));
        source.removeFirst();

        assertEquals(1, response.items().size());
        assertEquals("Burger", response.items().getFirst().name());
    }

    @Test
    void theNestedListsOfALineAreImmutableAndCopied() {
        List<RemovedIngredient> removed = new ArrayList<>(List.of(new RemovedIngredient(UUID.randomUUID(), "Onion")));
        List<ExtraIngredient> extras = new ArrayList<>(List.of(new ExtraIngredient(UUID.randomUUID(), "Cheese", 2,
                250L)));
        CartItemResponse line = line("Burger", removed, extras);

        removed.clear();
        extras.add(new ExtraIngredient(UUID.randomUUID(), "Bacon", 1, 400L));

        assertEquals(1, line.removedIngredients().size());
        assertEquals(1, line.extras().size());
        List<RemovedIngredient> exposedRemoved = line.removedIngredients();
        List<ExtraIngredient> exposedExtras = line.extras();
        assertThrows(UnsupportedOperationException.class, exposedRemoved::clear);
        assertThrows(UnsupportedOperationException.class, exposedExtras::clear);
    }

    @Test
    void aMissingListBecomesAnEmptyOneAndAnEmptyListStaysEmpty() {
        CartResponse nullItems = cart(null);
        CartItemResponse nullNested = line("Burger", null, null);
        CartResponse emptyItems = cart(List.of());

        assertTrue(nullItems.items().isEmpty());
        assertTrue(nullNested.removedIngredients().isEmpty());
        assertTrue(nullNested.extras().isEmpty());
        assertTrue(emptyItems.items().isEmpty());
        List<CartItemResponse> exposed = nullItems.items();
        CartItemResponse another = line("X", null, null);
        assertThrows(UnsupportedOperationException.class, () -> exposed.add(another));
    }

    @Test
    void theJsonShapeIsUnchangedAndEmptyCollectionsStayArrays() throws Exception {
        UUID cartItemId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID onion = UUID.fromString("00000000-0000-0000-0000-000000000003");
        CartItemResponse burger = new CartItemResponse(cartItemId, productId, "Burger", 2,
                List.of(new RemovedIngredient(onion, "Onion")), List.of(), "no salt", true, false, 500L, 1_000L, 1_000,
                909L, 91L);
        CartResponse response = new CartResponse(null, 0L, null, true, List.of(burger), new Totals(1_000L, null, null),
                true, null);

        JsonNode tree = JSON.readTree(JSON.writeValueAsString(response));

        assertEquals(List.of("cartId", "version", "currency", "sessionActive", "items", "totals", "estimate",
                "priceFingerprint"), tree.propertyNames().stream().toList());
        assertTrue(tree.get("cartId").isNull());
        assertTrue(tree.get("priceFingerprint").isNull());
        JsonNode item = tree.get("items").get(0);
        assertEquals("Burger", item.get("name").asString());
        assertEquals(1, item.get("removedIngredients").size());
        assertEquals("Onion", item.get("removedIngredients").get(0).get("name").asString());
        assertTrue(item.get("extras").isArray());
        assertEquals(0, item.get("extras").size());
        assertTrue(tree.get("totals").get("netMinor").isNull());

        JsonNode empty = JSON.readTree(JSON.writeValueAsString(cart(null)));
        assertTrue(empty.get("items").isArray());
        assertEquals(0, empty.get("items").size());
    }

    // ---- every response record ----------------------------------------------------------------------------------

    @Test
    void everyResponseRecordCopiesItsCollectionsDefensively() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Record.class));
        List<Class<?>> records = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.restofy.order.api.dto.response")) {
            records.add(Class.forName(candidate.getBeanClassName()));
        }
        assertTrue(records.size() >= 20, "the scan really found the response records: " + records.size());

        int checkedCollections = 0;
        for (Class<?> type : records) {
            for (RecordComponent component : type.getRecordComponents()) {
                Class<?> componentType = component.getType();
                if (!List.class.isAssignableFrom(componentType) && !Map.class.isAssignableFrom(componentType)) {
                    continue;
                }
                checkedCollections++;
                Object source = List.class.isAssignableFrom(componentType)
                        ? new ArrayList<>(List.of("kept")) : new LinkedHashMap<>(Map.of("kept", "kept"));
                Object instance = newInstanceWith(type, component, source);

                Object exposed = component.getAccessor().invoke(instance);

                assertNotNull(exposed, type.getSimpleName() + "." + component.getName() + " must not be null");
                assertNotSame(source, exposed, type.getSimpleName() + "." + component.getName()
                        + " exposes the caller's own collection");
                assertThrows(UnsupportedOperationException.class, () -> clear(exposed),
                        type.getSimpleName() + "." + component.getName() + " must be unmodifiable");
                if (source instanceof List<?> list) {
                    list.clear();
                    assertEquals(1, ((List<?>) exposed).size(), type.getSimpleName() + "." + component.getName());
                } else {
                    ((Map<?, ?>) source).clear();
                    assertEquals(1, ((Map<?, ?>) exposed).size(), type.getSimpleName() + "." + component.getName());
                }
                Object missing = component.getAccessor().invoke(newInstanceWith(type, component, null));
                assertNotNull(missing, type.getSimpleName() + "." + component.getName() + " must be empty, not null");
            }
        }
        assertTrue(checkedCollections >= 14, "every collection component was exercised: " + checkedCollections);
    }

    private static void clear(Object collection) {
        if (collection instanceof List<?> list) {
            list.clear();
        } else {
            ((Map<?, ?>) collection).clear();
        }
    }

    /** Builds the record with neutral values everywhere except the one collection under test. */
    private static Object newInstanceWith(Class<?> type, RecordComponent target, Object collection) throws Exception {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            if (components[i].getName().equals(target.getName())) {
                arguments[i] = collection;
            } else if (types[i] == long.class) {
                arguments[i] = 0L;
            } else if (types[i] == int.class) {
                arguments[i] = 0;
            } else if (types[i] == boolean.class) {
                arguments[i] = false;
            } else if (types[i] == Instant.class) {
                arguments[i] = Instant.EPOCH;
            } else {
                arguments[i] = null;
            }
        }
        Constructor<?> constructor = type.getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }
}
