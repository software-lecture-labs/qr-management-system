package com.restofy.order.api.controller;

import com.restofy.order.api.dto.request.ConfirmClosureRequest;
import com.restofy.order.api.dto.request.PrepareClosureRequest;
import com.restofy.order.api.dto.response.ClosureResponse;
import com.restofy.order.api.dto.response.OrderSessionStateResponse;
import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.application.service.TableSessionClosureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service endpoints for the lifecycle of a table session (ADR-QR-001): closing a settled session and expiring
 * an idle one. They are not routed by the API Gateway and only the verified QR Service may use them
 * ({@code Authorization: Service ...}); a guest token, a staff credential and the Kitchen and Payment services are
 * refused. The answers are shaped after the QR Service's own authority types (status, effect, source revision,
 * verification time, validity, completeness, checkout fence). This contract is a DRAFT proposed by the Order Service:
 * the QR Service has not adopted it yet, and no real QR adapter calls it.
 */
@RestController
@RequestMapping("/api/v1/internal/table-sessions")
@Tag(name = "Internal", description = "Table session lifecycle (QR service credential only, not exposed by the gateway)")
@SecurityRequirement(name = "service")
public class InternalTableSessionController {

    private final TableSessionClosureService closures;

    public InternalTableSessionController(TableSessionClosureService closures) {
        this.closures = closures;
    }

    @PostMapping("/{tableSessionId}/closure-preparations")
    @Operation(summary = "Ask for a permit to close a settled table session or to expire an idle one",
            description = "purpose SETTLED: the Order Service looks at ALL orders of the table session, whichever guest "
                    + "placed them: every order must be finished (COMPLETED, or CANCELLED with no refund owed) and paid; "
                    + "a guest who ordered nothing is not part of the scope; a session without orders is never eligible. "
                    + "purpose IDLE: requestedAt, mainIdleExpiresAt and guests (each with its idleExpiresAt) are "
                    + "required; the whole session may expire (effect IDLE_MAIN) only when no order protects it and its "
                    + "own idle window ended, otherwise only the guests that are idle AND not protected by one of their "
                    + "orders (effect IDLE_GUESTS, idleGuestIds). The answer is a permit: sourceStatus AUTHORISED "
                    + "(effect says what), BLOCKED (definitely not) or UNKNOWN (not known well enough; never 'nothing "
                    + "owed'), with sourceRevision, verifiedAt, validUntil, complete and checkoutFenced. Only an "
                    + "AUTHORISED operation is stored (state PREPARED) and from then on checkout answers 409 "
                    + "TABLE_SESSION_CLOSING for everybody (SETTLED_CLOSE, IDLE_MAIN) or for the idleGuestIds "
                    + "(IDLE_GUESTS) and writes nothing; otherwise state is NOT_PREPARED and nothing is held. "
                    + "operationId is the QR Service's durable id and commandDigest the digest of the exact command: "
                    + "asking again with both returns the stored operation (permit renewed or withdrawn), the same "
                    + "operationId with another digest, purpose or table session is 409 IDEMPOTENCY_CONFLICT. Another "
                    + "operation that is PREPARED, or COMPLETED and ended the session, is 409 CLOSURE_IN_PROGRESS. An "
                    + "operation never expires by time.")
    @ApiResponse(responseCode = "200", description = "A permit (AUTHORISED, BLOCKED or UNKNOWN)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid service credential")
    @ApiResponse(responseCode = "403", description = "Not the QR service")
    @ApiResponse(responseCode = "409", description = "CLOSURE_IN_PROGRESS or IDEMPOTENCY_CONFLICT")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR (also: IDLE without its details)")
    @ApiResponse(responseCode = "503", description = "Service identity verification unavailable (fail closed)")
    public ClosureResponse prepare(@AuthenticationPrincipal ServiceActor service, @PathVariable UUID tableSessionId,
                                   @Valid @RequestBody PrepareClosureRequest request) {
        return closures.prepare(service, tableSessionId, request);
    }

    @GetMapping("/{tableSessionId}/closure-preparations/{operationId}")
    @Operation(summary = "What became of an operation, and the permit as it is now",
            description = "For a QR Service that lost a response or whose permit ran out: the stored operation with the "
                    + "orders checked again, so a PREPARED permit is renewed (new verifiedAt and validUntil) or "
                    + "withdrawn (BLOCKED or UNKNOWN, with blockers) when the orders changed before anything was "
                    + "applied. An operation this Order Service never saw, or one that belongs to another restaurant "
                    + "or table session, is answered with sourceStatus NOT_STARTED (state NOT_STARTED), a complete and "
                    + "current answer, so the QR Service knows to prepare it. Nothing is written.")
    @ApiResponse(responseCode = "200", description = "The operation, or NOT_STARTED")
    @ApiResponse(responseCode = "401", description = "Missing or invalid service credential")
    @ApiResponse(responseCode = "403", description = "Not the QR service")
    @ApiResponse(responseCode = "503", description = "Service identity verification unavailable (fail closed)")
    public ClosureResponse get(@PathVariable UUID tableSessionId, @PathVariable UUID operationId,
                               @RequestParam UUID restaurantId,
                               @RequestParam(required = false) @Pattern(regexp = "^[0-9a-f]{64}$") String commandDigest) {
        return closures.get(restaurantId, tableSessionId, operationId, commandDigest);
    }

    @PostMapping("/{tableSessionId}/closure-preparations/{operationId}/confirmation")
    @Operation(summary = "The QR Service reports whether it applied the change (CLOSED or NOT_CLOSED)",
            description = "CLOSED always completes a PREPARED operation: the QR Service has already applied the change "
                    + "and does not undo it by itself, so a participant that said 'ready' cannot refuse the final "
                    + "decision. The optional receipt is the QR Service's local result; its effect must be the one that "
                    + "was permitted (409 CLOSURE_RECEIPT_MISMATCH otherwise). If the orders no longer match what the "
                    + "operation was prepared on (in practice a money report that arrived after the preparation), the "
                    + "answer carries scopeDrift=true and the unmet or unknown driftBlockers, and the difference is "
                    + "recorded append-only for people to reconcile; nothing is rejected. NOT_CLOSED aborts: it "
                    + "releases the hold so orders are accepted again, and for an operation that holds nothing (never "
                    + "prepared, or not authorised) it is a safe 200 'nothing to abort'. Repeating the same "
                    + "confirmation is safe and returns the same answer; the opposite one after the operation is final "
                    + "is 409 INVALID_STATUS_TRANSITION. CLOSED for an operation that was never prepared is 404. "
                    + "commandDigest (the digest of the exact command) is required and must be the one the operation "
                    + "was prepared with (409 IDEMPOTENCY_CONFLICT otherwise); it is echoed in every answer, also "
                    + "the 'nothing to abort' one. "
                    + "Nothing here pays, refunds or closes anything: it only records the outcome.")
    @ApiResponse(responseCode = "200", description = "COMPLETED (maybe with scopeDrift) or RELEASED, also when repeated")
    @ApiResponse(responseCode = "401", description = "Missing or invalid service credential")
    @ApiResponse(responseCode = "403", description = "Not the QR service")
    @ApiResponse(responseCode = "404", description = "CLOSURE_NOT_FOUND (CLOSED for an unknown operation)")
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION or CLOSURE_RECEIPT_MISMATCH")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "503", description = "Service identity verification unavailable (fail closed)")
    public ClosureResponse confirm(@AuthenticationPrincipal ServiceActor service, @PathVariable UUID tableSessionId,
                                   @PathVariable UUID operationId, @Valid @RequestBody ConfirmClosureRequest request) {
        return closures.confirm(service, tableSessionId, operationId, request);
    }

    @GetMapping("/{tableSessionId}/order-state")
    @Operation(summary = "What the orders of the table session mean for its idle rules (read-only)",
            description = "Not a permit to do anything. knowledge is NO_ORDERS (none ever, or every order safely "
                    + "cancelled), OPEN_WORK (an order is unfinished, unpaid or its money is uncertain) or SETTLED. "
                    + "protectedGuests still have an order that is not safely cancelled (a refund still owed, a payment "
                    + "in flight, an unverified or unreconciled report or an undecided cancellation keeps an order "
                    + "protecting); cancellationReleases maps each guest whose orders were all safely cancelled to "
                    + "when; mainCancellationAt is when the main session was released (only when every order of the "
                    + "session was safely cancelled). The answer is usable until validUntil and revision only goes up. "
                    + "Missing data is never reported as 'no orders'.")
    @ApiResponse(responseCode = "200", description = "The order state of the table session")
    @ApiResponse(responseCode = "401", description = "Missing or invalid service credential")
    @ApiResponse(responseCode = "403", description = "Not the QR service")
    @ApiResponse(responseCode = "503", description = "Service identity verification unavailable (fail closed)")
    public OrderSessionStateResponse orderState(@PathVariable UUID tableSessionId, @RequestParam UUID restaurantId) {
        return closures.orderState(restaurantId, tableSessionId);
    }
}
