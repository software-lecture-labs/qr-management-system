package com.restofy.order.api.controller;

import com.restofy.order.api.dto.request.AddCartItemRequest;
import com.restofy.order.api.dto.request.UpdateCartItemQuantityRequest;
import com.restofy.order.api.dto.response.CartResponse;
import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.application.service.CartService;
import com.restofy.order.application.service.CartService.CartMutation;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The controller is wired by constructor injection alone and is exercised here with a mocked {@link CartService}: no
 * Spring context, no servlet container, no application server, no database. Nothing in these tests starts a server.
 */
class CartControllerUnitTests {

    private static final GuestSessionContext GUEST = new GuestSessionContext(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), SessionState.ACTIVE);

    private final CartService service = mock(CartService.class);
    private final CartController controller = new CartController(service);

    private static CartResponse emptyCart() {
        return new CartResponse(null, 0L, null, true, List.of(), new CartResponse.Totals(0L, 0L, 0L), true, null);
    }

    @Test
    void theControllerHasExactlyOneConstructorThatTakesItsCollaborator() {
        Constructor<?>[] constructors = CartController.class.getDeclaredConstructors();

        assertEquals(1, constructors.length);
        assertEquals(List.of(CartService.class), Arrays.asList(constructors[0].getParameterTypes()));
    }

    @Test
    void noFieldIsInjectedAndEveryDependencyIsFinal() {
        for (Field field : CartController.class.getDeclaredFields()) {
            assertTrue(Modifier.isFinal(field.getModifiers()), field.getName() + " must be final");
            assertTrue(Arrays.stream(field.getAnnotations()).noneMatch(annotation -> {
                String name = annotation.annotationType().getSimpleName();
                return name.equals("Autowired") || name.equals("Inject") || name.equals("Resource")
                        || name.equals("Value");
            }), field.getName() + " must not be field-injected");
        }
    }

    @Test
    void theServiceLayerDoesNotDependOnTheServletContainerOrTheWebLayer() {
        for (Constructor<?> constructor : CartService.class.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse(parameter.getName().startsWith("jakarta.servlet"), parameter.getName());
                assertFalse(parameter.getName().startsWith("org.springframework.web"), parameter.getName());
                assertFalse(parameter.getName().startsWith("org.apache.catalina"), parameter.getName());
            }
        }
        for (Field field : CartService.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().startsWith("jakarta.servlet"), field.getName());
            assertFalse(field.getType().getName().startsWith("org.springframework.web"), field.getName());
        }
    }

    @Test
    void readingTheCartAsksTheServiceForTheAuthenticatedGuestsCart() {
        CartResponse cart = emptyCart();
        when(service.getCurrent(GUEST)).thenReturn(cart);

        assertSame(cart, controller.get(GUEST));

        verify(service).getCurrent(GUEST);
        verifyNoMoreInteractions(service);
    }

    @Test
    void anAddedItemIsAnsweredWithTheCartAndNoReplayHeaderOnTheFirstCall() {
        CartResponse cart = emptyCart();
        AddCartItemRequest request = new AddCartItemRequest(UUID.randomUUID(), 2, null, null, null, null);
        when(service.addItem(GUEST, "key-12345678", request)).thenReturn(new CartMutation(cart, false));

        ResponseEntity<CartResponse> response = controller.addItem(GUEST, "key-12345678", request);

        assertEquals(200, response.getStatusCode().value());
        assertSame(cart, response.getBody());
        assertNull(response.getHeaders().getFirst("Idempotent-Replayed"));
    }

    @Test
    void aReplayedAdditionIsMarkedWithTheReplayHeader() {
        CartResponse cart = emptyCart();
        AddCartItemRequest request = new AddCartItemRequest(UUID.randomUUID(), 1, null, null, "no salt", 3L);
        when(service.addItem(GUEST, "key-12345678", request)).thenReturn(new CartMutation(cart, true));

        ResponseEntity<CartResponse> response = controller.addItem(GUEST, "key-12345678", request);

        assertEquals("true", response.getHeaders().getFirst("Idempotent-Replayed"));
        assertSame(cart, response.getBody());
    }

    @Test
    void anAdditionWithoutAKeyPassesNullToTheService() {
        AddCartItemRequest request = new AddCartItemRequest(UUID.randomUUID(), 1, null, null, null, null);
        when(service.addItem(GUEST, null, request)).thenReturn(new CartMutation(emptyCart(), false));

        controller.addItem(GUEST, null, request);

        verify(service).addItem(GUEST, null, request);
    }

    @Test
    void quantityChangeRemovalAndClearingAreDelegatedWithTheirArguments() {
        UUID line = UUID.randomUUID();
        UpdateCartItemQuantityRequest update = new UpdateCartItemQuantityRequest(4, 7L);
        CartResponse updated = emptyCart();
        CartResponse removed = emptyCart();
        CartResponse cleared = emptyCart();
        when(service.updateQuantity(GUEST, line, update)).thenReturn(updated);
        when(service.removeItem(GUEST, line, 8L)).thenReturn(removed);
        when(service.clear(GUEST, null)).thenReturn(cleared);

        assertSame(updated, controller.updateQuantity(GUEST, line, update));
        assertSame(removed, controller.removeItem(GUEST, line, 8L));
        assertSame(cleared, controller.clear(GUEST, null));
    }
}
