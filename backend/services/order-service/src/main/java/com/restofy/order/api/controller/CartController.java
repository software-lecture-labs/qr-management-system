package com.restofy.order.api.controller;

import com.restofy.order.api.dto.request.AddCartItemRequest;
import com.restofy.order.api.dto.request.UpdateCartItemQuantityRequest;
import com.restofy.order.api.dto.response.CartResponse;
import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The QR guest's own cart. "current" is resolved from the authenticated guest session; there is no way to address
 * another guest's, table's or restaurant's cart through this API.
 */
@RestController
@RequestMapping("/api/v1/carts/current")
@Tag(name = "Cart", description = "Guest cart bound to the active QR table session")
@SecurityRequirement(name = "guestSession")
public class CartController {

    private final CartService carts;

    public CartController(CartService carts) {
        this.carts = carts;
    }

    @GetMapping
    @Operation(summary = "Get the current cart (empty when nothing was added yet; never creates a cart)")
    public CartResponse get(@AuthenticationPrincipal GuestSessionContext guest) {
        return carts.getCurrent(guest);
    }

    @PostMapping("/items")
    @Operation(summary = "Add a product (optionally customized); identical selections merge into one line",
            description = "Send an Idempotency-Key (a random UUID generated once per user action) to make retries "
                    + "safe: repeating the same key with the same body does not add the quantity again and answers "
                    + "with the current cart plus the header Idempotent-Replayed: true; the same key with a different "
                    + "body is 409 IDEMPOTENCY_CONFLICT. Without the header every call adds again. Keys are kept "
                    + "24 hours and are scoped to the guest session.")
    @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT, CART_VERSION_CONFLICT, "
            + "PRODUCT_UNAVAILABLE or TABLE_SESSION_NOT_ACTIVE")
    public ResponseEntity<CartResponse> addItem(
            @AuthenticationPrincipal GuestSessionContext guest,
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = false,
                    description = "8-120 characters of letters, digits, '.', '_', ':' or '-'. Scoped to the guest.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AddCartItemRequest request) {
        CartService.CartMutation result = carts.addItem(guest, idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.cart());
    }

    @PatchMapping("/items/{cartItemId}")
    @Operation(summary = "Set the quantity of a line (1..max); use DELETE to remove it")
    public CartResponse updateQuantity(@AuthenticationPrincipal GuestSessionContext guest,
                                       @PathVariable UUID cartItemId,
                                       @Valid @RequestBody UpdateCartItemQuantityRequest request) {
        return carts.updateQuantity(guest, cartItemId, request);
    }

    @DeleteMapping("/items/{cartItemId}")
    @Operation(summary = "Remove one line from the cart")
    public CartResponse removeItem(@AuthenticationPrincipal GuestSessionContext guest,
                                   @PathVariable UUID cartItemId,
                                   @RequestParam(required = false) Long expectedCartVersion) {
        return carts.removeItem(guest, cartItemId, expectedCartVersion);
    }

    @DeleteMapping("/items")
    @Operation(summary = "Remove all lines (idempotent)")
    public CartResponse clear(@AuthenticationPrincipal GuestSessionContext guest,
                              @RequestParam(required = false) Long expectedCartVersion) {
        return carts.clear(guest, expectedCartVersion);
    }
}
