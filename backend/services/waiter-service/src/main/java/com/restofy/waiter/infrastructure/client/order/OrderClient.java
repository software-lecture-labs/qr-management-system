package com.restofy.waiter.infrastructure.client.order;

import com.restofy.waiter.domain.enums.OrderView;
import com.restofy.waiter.domain.enums.PreparationStatus;
import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import com.restofy.waiter.infrastructure.client.DownstreamCalls;
import com.restofy.waiter.infrastructure.config.ServiceUrlProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter for the Order service, the owner of orders, snapshots, cancellations and the served fact. Queries are
 * read-only (the search POSTs only carry large filters in a body); the single command is
 * {@link #serve}. The contract is a DRAFT; Order has not implemented it yet.
 */
@Component
public class OrderClient {

    public static final String DEPENDENCY = "order";
    /** Upper bound of session ids in one request; equals the table snapshot limit of the QR adapter. */
    public static final int MAX_SESSION_IDS = 500;

    private final RestClient client;

    public OrderClient(RestClient.Builder builder, ServiceUrlProperties urls) {
        this.client = builder.clone().baseUrl(urls.orderUrl().toString()).build();
    }

    /** Active order count per live session in one request; sessions without active orders map to zero. */
    public Map<UUID, Integer> activeOrderCounts(UUID restaurantId, Collection<UUID> tableSessionIds) {
        if (tableSessionIds.isEmpty()) return Map.of();
        if (tableSessionIds.size() > MAX_SESSION_IDS) {
            throw new IllegalArgumentException("Too many session ids for one request");
        }
        Set<UUID> requested = new HashSet<>(tableSessionIds);
        SessionSummariesDto result = DownstreamCalls.execute(DEPENDENCY, () -> client.post()
                .uri("/api/v1/internal/orders/session-summaries")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new SessionSummaryRequest(restaurantId, List.copyOf(requested)))
                .retrieve().body(SessionSummariesDto.class));
        Map<UUID, Integer> counts = new HashMap<>();
        for (SessionSummariesDto.Entry entry : result.items()) {
            if (!requested.contains(entry.tableSessionId())) throw badResponse("summary for a session that was not requested");
            if (counts.put(entry.tableSessionId(), entry.activeOrderCount()) != null) {
                throw badResponse("duplicate session summary");
            }
        }
        return counts;
    }

    /**
     * One page of orders. Filters and ordering are applied by Order to the whole data set; the answer is
     * checked against the request so a downstream that ignores a filter or the tenant is never shown.
     */
    public OrderPageDto search(UUID restaurantId, UUID tableId, Collection<UUID> tableSessionIds, OrderView view,
                               int page, int size) {
        if (tableSessionIds != null && (tableSessionIds.isEmpty() || tableSessionIds.size() > MAX_SESSION_IDS)) {
            throw new IllegalArgumentException("tableSessionIds must hold between 1 and " + MAX_SESSION_IDS + " ids");
        }
        List<UUID> sessions = tableSessionIds == null ? null : List.copyOf(new HashSet<>(tableSessionIds));
        OrderPageDto result = DownstreamCalls.execute(DEPENDENCY, () -> client.post()
                .uri("/api/v1/internal/orders/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OrderSearchRequest(restaurantId, tableId, sessions, view, page, size))
                .retrieve().body(OrderPageDto.class));
        if (result.page() != page || result.size() != size || result.items().size() > size
                || result.totalElements() < result.items().size()) {
            throw badResponse("page metadata does not match the request");
        }
        Set<UUID> ids = new HashSet<>();
        for (OrderDto order : result.items()) {
            requireTenant(restaurantId, order);
            if (!ids.add(order.id())) throw badResponse("duplicate order in the page");
            if (tableId != null && !tableId.equals(order.tableId())) throw badResponse("order of another table");
            if (sessions != null && !sessions.contains(order.tableSessionId())) {
                throw badResponse("order of a session that was not requested");
            }
            if (view != null && !view.matches(order.businessStatus(), preparationOf(order), order.deliveryStatus())) {
                throw badResponse("order does not match the requested view");
            }
        }
        return result;
    }

    public Optional<OrderDto> findOrder(UUID restaurantId, UUID orderId) {
        Optional<OrderDto> order = DownstreamCalls.executeOptional(DEPENDENCY, () -> client.get()
                .uri(uri -> uri.path("/api/v1/internal/orders/{orderId}")
                        .queryParam("restaurantId", restaurantId).build(orderId))
                .retrieve().body(OrderDto.class));
        order.ifPresent(found -> {
            requireTenant(restaurantId, found);
            if (!found.id().equals(orderId)) throw badResponse("order id does not match the request");
        });
        return order;
    }

    /**
     * Sends the serve command. Order alone decides, atomically, whether the order may be served (tenant,
     * ownership, session, version, ready, not cancelled, not yet served). Three kinds of outcome:
     * <ul>
     *   <li>a returned answer: Order applied the command (or had applied the same command id before);</li>
     *   <li>{@link OrderRejectedException}: Order definitively did not apply it;</li>
     *   <li>{@link DependencyException}: no usable answer. The command may or may not have been applied, and
     *       nothing here sends it again: resending is a decision of the caller, with the same command id.</li>
     * </ul>
     * A 401/403 is reported as a dependency rejection ({@code REJECTED}): Order did not process the request.
     */
    public ServeResponseDto serve(UUID orderId, ServeCommandRequestDto command) {
        try {
            ServeResponseDto answer = client.post()
                    .uri("/api/v1/internal/orders/{orderId}/serve", orderId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", command.commandId().toString())
                    .body(command)
                    .retrieve().body(ServeResponseDto.class);
            if (answer == null) throw badResponse("empty answer to the serve command");
            if (!answer.commandId().equals(command.commandId())) throw badResponse("answer for another command");
            requireTenant(command.restaurantId(), answer.order());
            if (!answer.order().id().equals(orderId)) throw badResponse("answer for another order");
            return answer;
        } catch (RestClientResponseException refusal) {
            int status = refusal.getStatusCode().value();
            if (DownstreamCalls.isDefiniteRefusal(status)) {
                throw new OrderRejectedException(rejectionCode(status, refusal));
            }
            throw DownstreamCalls.translate(DEPENDENCY, refusal);
        } catch (RestClientException failure) {
            throw DownstreamCalls.translate(DEPENDENCY, failure);
        }
    }

    /** What Order recorded for a command id; empty if Order has no record of it (not received, or still in flight). */
    public Optional<ServeStatusDto> serveStatus(UUID restaurantId, UUID orderId, UUID commandId) {
        Optional<ServeStatusDto> status = DownstreamCalls.executeOptional(DEPENDENCY, () -> client.get()
                .uri(uri -> uri.path("/api/v1/internal/orders/{orderId}/serve-commands/{commandId}")
                        .queryParam("restaurantId", restaurantId).build(orderId, commandId))
                .retrieve().body(ServeStatusDto.class));
        status.ifPresent(found -> {
            if (!found.commandId().equals(commandId)) throw badResponse("status of another command");
            if (found.order() != null) {
                requireTenant(restaurantId, found.order());
                if (!found.order().id().equals(orderId)) throw badResponse("status of another order");
            }
        });
        return status;
    }

    /** Reasons Order may give; anything else becomes the generic code so foreign text is never reflected. */
    private static final Set<String> KNOWN_REJECTIONS = Set.of("ORDER_VERSION_CONFLICT",
            "ORDER_SESSION_MISMATCH", "ORDER_NOT_READY", "ORDER_CANCELLED", "ORDER_ALREADY_SERVED",
            "ORDER_NOT_DELIVERABLE", "ORDER_COMMAND_CONFLICT");
    private static final JsonMapper REJECTION_JSON = JsonMapper.builder().build();

    private static String rejectionCode(int status, RestClientResponseException refusal) {
        if (status == HttpStatus.NOT_FOUND.value()) return OrderRejectedException.NOT_FOUND;
        try {
            return knownRejection(REJECTION_JSON.readTree(refusal.getResponseBodyAsString()).path("code").asString(""));
        } catch (RuntimeException _) {
            return OrderRejectedException.GENERIC;
        }
    }

    /** A reason Order may give is passed on; any other text becomes the generic code. */
    public static String knownRejection(String code) {
        if (OrderRejectedException.NOT_FOUND.equals(code)) return code;
        return code != null && KNOWN_REJECTIONS.contains(code) ? code : OrderRejectedException.GENERIC;
    }

    private static PreparationStatus preparationOf(OrderDto order) {
        return order.preparation() == null ? null : order.preparation().status();
    }

    private static void requireTenant(UUID restaurantId, OrderDto order) {
        if (!restaurantId.equals(order.restaurantId())) throw badResponse("order belongs to another restaurant");
    }

    private static DependencyException badResponse(String detail) {
        return new DependencyException(DEPENDENCY, DependencyFailure.BAD_RESPONSE,
                "order service returned an unusable response: " + detail);
    }
}
